#!/usr/bin/env python3
"""
m6_registro.py - M6 · Auditoría: escritura en auth_log (módulo 2 de 2).

Recibe eventos ya normalizados (de m6_colector.py) y los inserta en auth_log con el rol onos_m6
(solo INSERT; 02_roles.sql), por TLS (sslmode=verify-ca), como pide el LLSD de R3 (sección 5.7):

  - en lotes: cada 1 s o al juntar 100 filas, desde un hilo propio;
  - si PostgreSQL falla, retiene hasta 10 000 filas en memoria y luego descarta las más antiguas,
    contando cuántas perdió (se registra una fila M6_PERDIDAS al recuperarse);
  - si un lote falla por una referencia (FOREIGN KEY), lo reintenta fila por fila y la fila con la
    referencia rota se guarda igual, con el id movido a detalle;
  - usuario_id y dispositivo_id los completa la base (disparador de 06_m6.sql) a partir de sesion_id
    o del uid en detalle, así onos_m6 se queda con solo INSERT.

Un evento es un dict:
  {"ts": datetime con zona, "evento": "AUTORIZACION_DENEGADA", "sesion_id": 12 | None,
   "usuario_id": 3 | None, "uid": "alumno.demo" | None, "detalle": {...},
   "origen": ("onos-1", "2026-11-12T15:04:05.123456789Z") | None}
"""
import collections, datetime, json, logging, threading, time

log = logging.getLogger("m6")

# onos_m6 solo tiene INSERT en auth_log (acuerdo 7 y modelo de datos). usuario_id y dispositivo_id
# los completa el disparador auth_log_referencias (06_m6.sql) desde sesion_id o desde detalle->>'usuario'.
SQL_INSERT = """
INSERT INTO auth_log (ts, evento, sesion_id, usuario_id, dispositivo_id, detalle)
VALUES (%(ts)s, %(evento)s, %(sesion_id)s, %(usuario_id)s, NULL, %(detalle)s::jsonb)
"""


def fila(ev):
    """Evento → parámetros del INSERT (el uid y el origen van también en detalle)."""
    det = dict(ev.get("detalle") or {})
    if ev.get("uid"):
        det.setdefault("usuario", ev["uid"])
    if ev.get("origen"):
        det.setdefault("instancia", ev["origen"][0])
    return {
        "ts": ev.get("ts") or datetime.datetime.now(datetime.timezone.utc),
        "evento": str(ev["evento"])[:30],                  # auth_log.evento es VARCHAR(30)
        "sesion_id": ev.get("sesion_id"),
        "usuario_id": ev.get("usuario_id"),
        "uid": ev.get("uid"),
        "detalle": json.dumps(det, ensure_ascii=False, default=str),
    }


class Registro:
    """Cola en memoria + hilo escritor por lotes hacia auth_log."""

    def __init__(self, conectar, lote=100, periodo=1.0, maximo=10_000, al_confirmar=None):
        """
        conectar: función sin argumentos que devuelve una conexión DB-API (psycopg2.connect(...)).
        al_confirmar: función(dict instancia → último ts de Docker) que se llama tras cada lote guardado.
        """
        self._conectar = conectar
        self.lote = lote
        self.periodo = periodo
        self.maximo = maximo
        self._al_confirmar = al_confirmar
        self._cola = collections.deque()
        self._cv = threading.Condition()
        self._cx = None
        self._activo = False
        self._hilo = None
        self.perdidas = 0           # descartadas por desborde (aún no informadas)
        self.perdidas_total = 0
        self.escritas = 0
        self.bd_caida = False

    # ------------------------------------------------------------------ entrada

    def agregar(self, ev):
        with self._cv:
            if len(self._cola) >= self.maximo:
                self._cola.popleft()                       # se descarta la más antigua
                self.perdidas += 1
                self.perdidas_total += 1
            self._cola.append(ev)
            if len(self._cola) >= self.lote:
                self._cv.notify()

    def pendientes(self):
        with self._cv:
            return len(self._cola)

    # ------------------------------------------------------------------ hilo escritor

    def iniciar(self):
        self._activo = True
        self._hilo = threading.Thread(target=self._bucle, name="m6-registro", daemon=True)
        self._hilo.start()

    def detener(self, vaciar=True):
        self._activo = False
        with self._cv:
            self._cv.notify()
        if self._hilo:
            self._hilo.join(timeout=10)
        if vaciar:
            while self.pendientes() and self.escribir_lote():
                pass
        self._cerrar()

    def _bucle(self):
        while self._activo:
            with self._cv:
                if len(self._cola) < self.lote:
                    self._cv.wait(self.periodo)
            if not self.escribir_lote() and self._activo:
                time.sleep(min(5.0, max(self.periodo, 1.0)))   # BD caída: se reintenta sin girar en vacío

    def escribir_lote(self):
        """Escribe hasta `lote` filas. Devuelve True si no quedó nada pendiente por un error."""
        with self._cv:
            if not self._cola and not self.perdidas:
                return True
            eventos = [self._cola[i] for i in range(min(self.lote, len(self._cola)))]
            perdidas = self.perdidas
        if perdidas:
            eventos = eventos + [{
                "evento": "M6_PERDIDAS", "detalle": {"filas_descartadas": perdidas,
                                                     "motivo": "PostgreSQL no disponible; cola llena"}}]
        try:
            self._insertar(eventos)
        except Exception as e:                              # noqa: BLE001 (cualquier error del driver)
            if not self.bd_caida:
                log.warning("M6: no se pudo escribir en auth_log (%s); se retienen hasta %d filas", e,
                            self.maximo)
            self.bd_caida = True
            self._cerrar()
            return False
        with self._cv:
            for _ in range(len(eventos) - (1 if perdidas else 0)):
                self._cola.popleft()
            self.perdidas -= perdidas
        if self.bd_caida:
            log.info("M6: PostgreSQL disponible otra vez")
        self.bd_caida = False
        self.escritas += len(eventos)
        if self._al_confirmar:
            ultimos = {}
            for ev in eventos:
                o = ev.get("origen")
                if o and o[1] and o[1] > ultimos.get(o[0], ""):
                    ultimos[o[0]] = o[1]
            if ultimos:
                self._al_confirmar(ultimos)
        return True

    # ------------------------------------------------------------------ base de datos

    def _conexion(self):
        if self._cx is None or getattr(self._cx, "closed", 0):
            self._cx = self._conectar()
        return self._cx

    def _cerrar(self):
        try:
            if self._cx is not None:
                self._cx.close()
        except Exception:                                  # noqa: BLE001
            pass
        self._cx = None

    def _insertar(self, eventos):
        filas = [fila(ev) for ev in eventos]
        cx = self._conexion()
        try:
            self._ejecutar(cx, filas)
            cx.commit()
        except Exception as e:                             # noqa: BLE001
            cx.rollback()
            codigo = getattr(e, "pgcode", None)
            if codigo == "23503":                          # FOREIGN KEY: fila por fila
                return self._fila_por_fila(cx, filas)
            raise

    def _ejecutar(self, cx, filas):
        cur = cx.cursor()
        try:
            cur.executemany(SQL_INSERT, filas)
        finally:
            cur.close()

    def _fila_por_fila(self, cx, filas):
        for f in filas:
            try:
                self._ejecutar(cx, [f])
                cx.commit()
            except Exception as e:                         # noqa: BLE001
                cx.rollback()
                if getattr(e, "pgcode", None) != "23503":
                    raise
                det = json.loads(f["detalle"])
                det["referencia_rota"] = {"sesion_id": f["sesion_id"], "usuario_id": f["usuario_id"]}
                g = dict(f, sesion_id=None, usuario_id=None, detalle=json.dumps(det, ensure_ascii=False))
                self._ejecutar(cx, [g])
                cx.commit()


def conectar_postgres(dsn):
    """Fábrica de conexiones psycopg2 (import tardío: las pruebas no lo necesitan)."""
    import psycopg2

    def conectar():
        return psycopg2.connect(dsn, connect_timeout=5, application_name="m6-auditoria")
    return conectar
