#!/usr/bin/env python3
"""
m6_colector.py - M6 · Auditoría fuera de ONOS: de dónde salen los eventos (módulo 1 de 2) y servicio.

M6 no es una app de ONOS: corre como servicio en el host de VM-CLUSTER (172.16.0.10), junto a los
contenedores onos-1, onos-2 y onos-3. Como no puede suscribirse a los eventos OSGi de las apps, los
obtiene de dos fuentes:

  1. Log de cada instancia (`docker logs -f --timestamps`): cada app escribe una línea con la marca
     de su módulo y del evento (PLAN_MODULOS, acuerdo 9), p. ej. "M2: AuthorizationDenied sesion 12 ...".
     interpretar() la convierte en un evento de auth_log (LLSD R3, Tabla 17, más los de sesión de R1).
  2. REST de M5 (`GET /onos/campus/v1/incidentes`): cada 10 s compara el estado de los incidentes y
     registra los cambios que no aparecieron en el log (respaldo si M6 estuvo detenido).

Cada evento se entrega a m6_registro.Registro, que escribe en auth_log por lotes. Tras cada lote
guardado se actualiza un punto de control por instancia: al reiniciar, M6 sigue desde ahí
(`--since`) sin duplicar ni perder líneas.

Uso (root en el host de VM-CLUSTER, desde la raíz del repositorio):
  python3 r3-ataques-encubiertos/m6-auditoria/m6_colector.py --config /etc/tel354/m6.env
  python3 r3-ataques-encubiertos/m6-auditoria/m6_colector.py --stdout              # sin BD: imprime JSON
  python3 r3-ataques-encubiertos/m6-auditoria/m6_colector.py --archivo karaf.log --stdout   # reproduce un log
"""
import argparse, base64, datetime, json, logging, os, re, signal, subprocess, sys, threading, time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import m6_registro                                                      # noqa: E402

log = logging.getLogger("m6")
UTC = datetime.timezone.utc

# ---------------------------------------------------------------------- interpretación del log

# SesionInfo/Sesion.toString(): sesion 12 [alumno.demo ALUMNO 0a:..:01 en of:..01/2 IP 10.17.0.5 VLAN 101 ACTIVA]
SES = (r"sesion (?P<sid>\d+) \[(?P<u>\S+) (?P<rol>\S+) (?P<mac>\S+) en (?P<dpid>\S+)/(?P<puerto>\d+)"
       r"(?: IP (?P<ip>\S+))? VLAN (?P<vlan>-?\d+) (?P<estado>\w+)\]")
# IncidenteVivo.toString() de M5: incidente 7 [alumno.demo ESCANEO FUERTE/CRITICO N2, 1 sesiones]
INC = (r"incidente (?P<inc>\d+) \[(?P<u>\S+) (?P<tipo>\S+) (?P<na>\w+)/(?P<crit>\w+) (?P<nivel>\w+)"
       r"(?: réplica)?, (?P<ns>\d+) sesiones\]")


def _ses(m, extra=None):
    d = {k: m.group(k) for k in ("mac", "dpid", "puerto", "ip", "vlan", "estado", "rol") if m.group(k)}
    d.update(extra or {})
    return d


def _ev(evento, sid=None, uid=None, usuario_id=None, **detalle):
    return {"evento": evento, "sesion_id": int(sid) if sid else None, "uid": uid,
            "usuario_id": int(usuario_id) if usuario_id else None,
            "detalle": {k: v for k, v in detalle.items() if v is not None}}


# (módulo, regex del mensaje, constructor de eventos)
REGLAS = [
    # --- R1 (M1): autenticación y sesión
    ("M1", r"^Access-Accept (?P<u>\S+) rol (?P<rol>\S+) \(Session-Timeout (?P<to>\d+) s\) para (?P<mac>\S+) "
           r"en (?P<dpid>\S+)/(?P<puerto>\d+)",
     lambda m: [_ev("AUTH_ACEPTADA", uid=m["u"], rol=m["rol"], session_timeout=int(m["to"]), mac=m["mac"],
                    dpid=m["dpid"], puerto=m["puerto"])]),
    ("M1", r"^Access-Reject para (?P<mac>\S+) en (?P<dpid>\S+)/(?P<puerto>\d+)",
     lambda m: [_ev("AUTH_FALLIDA", motivo="ACCESS_REJECT", mac=m["mac"], dpid=m["dpid"], puerto=m["puerto"])]),
    ("M1", r"^usuario (?P<u>\S+) o rol (?P<rol>\S+) sin fila activa en la BD",
     lambda m: [_ev("AUTH_FALLIDA", uid=m["u"], motivo="SIN_FILA_ACTIVA", rol=m["rol"])]),
    ("M1", r"^(?P<dpid>\S+) no es un switch de acceso registrado; se rechaza (?P<u>\S+)",
     lambda m: [_ev("AUTH_FALLIDA", uid=m["u"], motivo="SWITCH_NO_REGISTRADO", dpid=m["dpid"])]),
    ("M1", r"^AUTENTICADA " + SES,
     lambda m: [_ev("SESION_AUTENTICADA", m["sid"], m["u"], **_ses(m))]),
    ("M1", r"^ACTIVA " + SES + r" con (?P<n>\d+) reglas 110",
     lambda m: [_ev("SESION_ACTIVA", m["sid"], m["u"], **_ses(m, {"reglas_110": int(m["n"])}))]),
    ("M1", r"^" + SES + r" sin reglas en el switch: CERRADA \((?P<motivo>[^)]*)\)",
     lambda m: [_ev("SESION_CERRADA", m["sid"], m["u"], **_ses(m, {"motivo": m["motivo"]}))]),
    ("M1", r"^CERRADA " + SES + r" \((?P<motivo>[^)]*)\)",
     lambda m: [_ev("SESION_CERRADA", m["sid"], m["u"], **_ses(m, {"motivo": m["motivo"]}))]),
    ("M1", r"^BindingViolation \((?P<tipo>\w+)\) " + SES + r" usa (?P<usada>\S+) \(concedida (?P<conc>[^)]*)\)"
           r"(?P<resto>.*)$",
     lambda m: [_ev("SUPLANTACION", m["sid"], m["u"], tipo=m["tipo"], ip_usada=m["usada"],
                    ip_concedida=m["conc"], inequivoca="inequ" in m["resto"], dpid=m["dpid"],
                    puerto=m["puerto"], mac=m["mac"])]),
    ("M1", r"^revocación de la sesión (?P<sid>\d+) \((?P<motivo>[^)]*)\) pedida por (?P<sol>.+)$",
     lambda m: [_ev("SESION_REVOCADA", m["sid"], motivo=m["motivo"], solicitante=m["sol"])]),
    ("M1", r"^incidente (?P<inc>\d+): (?P<n>\d+) sesiones del usuario (?P<usr>\d+) pasan a cuarentena",
     lambda m: [_ev("CUARENTENA_APLICADA", usuario_id=m["usr"], incidente_id=int(m["inc"]),
                    sesiones=int(m["n"]))]),
    # --- R2 (M2): slow-path de la regla 55
    ("M2", r"^AuthorizationDenied sesion (?P<sid>\d+) \[(?P<u>\S+) (?P<rol>\S+)\] -> (?P<dst>\S+) "
           r"proto (?P<proto>\d+)(?: puerto (?P<pto>\d+))? en (?P<dpid>\S+)/(?P<puerto>\S+)",
     lambda m: [_ev("AUTORIZACION_DENEGADA", m["sid"], m["u"], rol=m["rol"], ip_dst=m["dst"],
                    proto=int(m["proto"]), puerto=int(m["pto"]) if m["pto"] else None, dpid=m["dpid"],
                    puerto_acceso=m["puerto"])]),
    # --- R3 (M4a): sospechas
    ("M4a", r"^ThreatSuspected (?P<met>\w+)=(?P<val>[\d.]+)/(?P<umb>[\d.]+) W=(?P<w>\d+)s sesion (?P<sid>\d+) "
            r"-> (?P<dest>.*?)(?P<ineq> inequívoca)?$",
     lambda m: [_ev("SOSPECHA", m["sid"], metrica=m["met"], valor=float(m["val"]), umbral=float(m["umb"]),
                    ventana_s=int(m["w"]), destinos=m["dest"], inequivoca=bool(m["ineq"]))]),
    # --- R3 (M4a): alertas del IDS (Suricata) que llegan por la REST de M4a (LLSD R3, Tabla 9: IdsAlert)
    ("M4a", r"^IdsAlert sid (?P<sid>\d+) sev (?P<sev>\d+) tipo (?P<tipo>\w+) sesion (?P<ses>\d+) \[(?P<u>\S+) (?P<rol>\S+)\] "
            r"-> (?P<dst>\S+) proto (?P<proto>\d+) puerto (?P<pto>\d+) sensor (?P<sensor>\S+): (?P<firma>.*)$",
     lambda m: [_ev("IDS_ALERTA", m["ses"], m["u"], firma_sid=int(m["sid"]), severidad=int(m["sev"]), tipo=m["tipo"],
                    rol=m["rol"], ip_dst=m["dst"], proto=int(m["proto"]), puerto=int(m["pto"]) or None,
                    sensor=m["sensor"], firma=m["firma"])]),
    ("M4a", r"^alerta del IDS sin sesión para (?P<src>\S+) \(sid (?P<sid>\d+)\), no se atribuye",
     lambda m: [_ev("IDS_ALERTA", ip_origen=m["src"], firma_sid=int(m["sid"]), motivo="SIN_SESION")]),
    # --- R3 (M5): incidentes, contención y operaciones de TI
    ("M5", r"^" + INC + r" (?P<res>.*?) -> (?P<obj>.*) \(sospecha (?P<sosp>.*); modo (?P<modo>\w+)\)$",
     lambda m: _contencion(m)),
    ("M5", r"^" + INC + r" sube de (?P<a>\w+) a (?P<b>\w+)",
     lambda m: [_ev("CONTENCION_APLICADA", uid=m["u"], incidente_id=int(m["inc"]), nivel=m["b"],
                    nivel_anterior=m["a"], motivo="ESCALADA", tipo=m["tipo"])]),
    ("M5", r"^" + INC + r" venció sin renovación",
     lambda m: [_ev("CONTENCION_RETIRADA", uid=m["u"], incidente_id=int(m["inc"]), nivel=m["nivel"],
                    motivo="VENCIMIENTO", tipo=m["tipo"])]),
    ("M5", r"^incidente (?P<inc>\d+) CONFIRMADO por (?P<sol>\S+) vía (?P<via>\S+) \((?P<motivo>.*)\): "
           r"cuarentena de (?P<u>\S+) hasta (?P<fin>.+)$",
     lambda m: [_ev("CUARENTENA", uid=m["u"], incidente_id=int(m["inc"]), fin=m["fin"], solicitante=m["sol"],
                    via=m["via"], motivo=m["motivo"]),
                _ev("ADMIN_INCIDENTE", uid=m["u"], operacion="CONFIRMAR", incidente_id=int(m["inc"]),
                    solicitante=m["sol"], via=m["via"], motivo=m["motivo"])]),
    ("M5", r"^incidente (?P<inc>\d+) DESCARTADO por (?P<sol>\S+) vía (?P<via>\S+) \((?P<motivo>.*)\)$",
     lambda m: [_ev("ADMIN_INCIDENTE", operacion="DESCARTAR", incidente_id=int(m["inc"]), solicitante=m["sol"],
                    via=m["via"], motivo=m["motivo"]),
                _ev("CONTENCION_RETIRADA", incidente_id=int(m["inc"]), motivo="DESCARTE")]),
    ("M5", r"^modo (?P<modo>\w+) fijado por (?P<sol>\S+) vía (?P<via>\S+)",
     lambda m: [_ev("ADMIN_INCIDENTE", operacion="MODO", modo=m["modo"], solicitante=m["sol"], via=m["via"])]),
]
REGLAS = [(mod, re.compile(rx), f) for mod, rx, f in REGLAS]
# "... | M2: AuthorizationDenied ..." (formato de Karaf) o "M2: ..." al inicio
MARCA = re.compile(r"(?:^|\|\s|\s)(M1|M2|M4a|M5): (.*)$")
TS_DOCKER = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z) (.*)$")
TS_KARAF = re.compile(r"^(\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d)[,.](\d{3})")


def _contencion(m):
    nivel = re.search(r"-> (N\d)", m["res"])
    nivel = nivel.group(1) if nivel else m["nivel"]
    sesiones = [int(x) for x in re.findall(r"sesion (\d+)", m["obj"])]
    ev = _ev("CONTENCION_APLICADA" if nivel in ("N1", "N2") else "INCIDENTE_REGISTRADO",
             sesiones[0] if sesiones else None, m["u"], incidente_id=int(m["inc"]), nivel=nivel,
             tipo=m["tipo"], nivel_ataque=m["na"], criticidad=m["crit"], modo=m["modo"],
             sesiones=sesiones or None, sospecha=m["sosp"])
    return [ev]


def interpretar(linea):
    """Línea del log de ONOS → lista de eventos (vacía si no es de auditoría)."""
    m = MARCA.search(linea.rstrip("\n"))
    if not m:
        return []
    modulo, texto = m.group(1), m.group(2).strip()
    for mod, rx, f in REGLAS:
        if mod == modulo:
            x = rx.match(texto)
            if x:
                evs = f(x)
                for e in evs:
                    e["detalle"]["modulo"] = modulo
                    e["texto"] = texto
                return evs
    return []


def separar_ts(linea):
    """'2026-11-12T20:04:05.123456789Z resto' → (ts de Docker o None, datetime UTC, resto)."""
    m = TS_DOCKER.match(linea)
    if m:
        crudo = m.group(1)
        base, _, frac = crudo[:-1].partition(".")
        ts = datetime.datetime.strptime(base, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=UTC)
        ts += datetime.timedelta(microseconds=int((frac + "000000")[:6]) if frac else 0)
        return crudo, ts, m.group(2)
    k = TS_KARAF.match(linea)
    if k:
        ts = datetime.datetime.strptime(k.group(1).replace(" ", "T"), "%Y-%m-%dT%H:%M:%S")
        ts = ts.replace(microsecond=int(k.group(2)) * 1000).astimezone(UTC)   # hora local del log
        return None, ts, linea
    return None, datetime.datetime.now(UTC), linea


# ---------------------------------------------------------------------- colector

class Colector:
    """Une las fuentes, deduplica entre instancias y entrega a un destino (Registro o stdout)."""

    def __init__(self, destino, estado_ruta=None, ventana_dup=5.0):
        self.destino = destino
        self.estado_ruta = estado_ruta
        self.ventana_dup = ventana_dup
        self._recientes = {}                 # (evento, texto) → (instancia, instante)
        self._lock = threading.Lock()
        self.control = self._leer_estado()   # instancia → último ts de Docker ya guardado
        self.incidentes = {}                 # id → estado conocido (log o REST)
        self.contados = 0
        self.activo = True
        self._desde_inicial = "1m"           # primera vez sin punto de control: solo lo reciente

    # -- punto de control
    def _leer_estado(self):
        try:
            with open(self.estado_ruta, encoding="utf-8") as f:
                return json.load(f).get("control", {})
        except (OSError, ValueError, TypeError):
            return {}

    def confirmar(self, ultimos):
        """Lo llama Registro tras guardar un lote: avanza el punto de control de cada instancia."""
        with self._lock:
            for inst, ts in ultimos.items():
                if ts > self.control.get(inst, ""):
                    self.control[inst] = ts
            datos = {"control": dict(self.control)}
        if self.estado_ruta:
            tmp = self.estado_ruta + ".tmp"
            try:
                os.makedirs(os.path.dirname(self.estado_ruta) or ".", exist_ok=True)
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(datos, f)
                os.replace(tmp, self.estado_ruta)
            except OSError as e:
                log.warning("M6: no se pudo guardar el punto de control (%s)", e)

    # -- entrada de líneas
    def linea(self, instancia, linea):
        crudo, ts, resto = separar_ts(linea)
        if crudo and crudo <= self.control.get(instancia, ""):
            return 0                          # ya guardada antes del reinicio
        evs = interpretar(resto)
        n = 0
        for ev in evs:
            if self._duplicado(ev, instancia):
                continue
            ev["ts"] = ts
            ev["origen"] = (instancia, crudo)
            self._recordar_incidente(ev)
            ev.pop("texto", None)
            self.destino(ev)
            n += 1
        self.contados += n
        return n

    def _duplicado(self, ev, instancia):
        """La misma línea vista en OTRA instancia dentro de la ventana cuenta una vez. Dos líneas
        iguales de la misma instancia son dos eventos (p. ej., dos Access-Reject seguidos del mismo
        equipo) y se registran ambas."""
        clave = (ev["evento"], ev.get("texto"))
        ahora = time.monotonic()
        with self._lock:
            for k in [k for k, (_, t) in self._recientes.items() if ahora - t > self.ventana_dup]:
                del self._recientes[k]
            previa = self._recientes.get(clave)
            self._recientes[clave] = (instancia, ahora)
            return previa is not None and previa[0] != instancia

    def _recordar_incidente(self, ev):
        inc = ev["detalle"].get("incidente_id")
        if inc is None:
            return
        e = ev["evento"]
        if e == "CUARENTENA":
            nuevo = "CONFIRMADO"
        elif e == "CONTENCION_RETIRADA":
            nuevo = "VENCIDO" if ev["detalle"].get("motivo") == "VENCIMIENTO" else "DESCARTADO"
        elif e in ("CONTENCION_APLICADA", "INCIDENTE_REGISTRADO"):
            nuevo = "EN_INVESTIGACION"
        else:
            nuevo = None                      # ADMIN_INCIDENTE: el estado lo da su pareja (CUARENTENA, ...)
        if nuevo:
            with self._lock:
                self.incidentes[inc] = nuevo

    # -- fuente 1: docker logs
    def seguir_contenedor(self, nombre):
        aviso = False
        while self.activo:
            desde = self.control.get(nombre) or self._desde_inicial
            cmd = ["docker", "logs", "-f", "--timestamps", "--since", desde, nombre]
            try:
                p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                     encoding="utf-8", errors="replace")
                log.info("M6: leyendo el log de %s desde %s", nombre, desde)
                aviso = False
                for l in p.stdout:
                    if not self.activo:
                        break
                    self.linea(nombre, l)
                p.wait(timeout=5)
            except (OSError, subprocess.SubprocessError) as e:
                if not aviso:
                    log.warning("M6: no se pudo leer el log de %s (%s); se reintenta cada 5 s", nombre, e)
                    aviso = True
            time.sleep(5)                     # contenedor reiniciado o detenido

    # -- fuente 2: REST de M5 (respaldo de cambios de estado)
    def sondear_rest(self, urls, auth, periodo=10.0):
        base = None
        cab = {"Authorization": "Basic " + base64.b64encode(auth.encode()).decode()}
        while self.activo:
            for url in ([base] if base else []) + [u for u in urls if u != base]:
                try:
                    req = urllib.request.Request(url.rstrip("/") + "/onos/campus/v1/incidentes?limite=500",
                                                 headers=cab)
                    with urllib.request.urlopen(req, timeout=5) as r:
                        datos = json.load(r)
                    self.comparar_incidentes(datos.get("incidentes", []), primera=base is None)
                    base = url
                    break
                except Exception:             # noqa: BLE001 (instancia caída o M5 inactivo: la siguiente)
                    continue
            time.sleep(periodo)

    def comparar_incidentes(self, lista, primera=False):
        """La primera lectura solo fija la línea base; después, cada cambio no visto en el log se registra."""
        n = 0
        for i in lista:
            inc, estado = i.get("id"), i.get("estado")
            with self._lock:
                previo = self.incidentes.get(inc)
                self.incidentes[inc] = estado
            if primera or previo == estado:
                continue
            self.destino({"evento": "INCIDENTE_ESTADO", "sesion_id": i.get("sesion"), "uid": i.get("usuario"),
                          "usuario_id": None, "ts": datetime.datetime.now(UTC), "origen": None,
                          "detalle": {"incidente_id": inc, "estado": estado, "estado_anterior": previo,
                                      "nivel": i.get("nivel"), "tipo": i.get("tipo"), "fuente": "REST M5"}})
            n += 1
        return n


# ---------------------------------------------------------------------- servicio

def leer_config(ruta):
    v = {}
    if ruta and os.path.exists(ruta):
        for linea in open(ruta, encoding="utf-8"):
            linea = linea.split("#")[0].strip()
            if "=" in linea:
                k, x = linea.split("=", 1)
                v[k.strip()] = x.strip().strip('"')
    return v


def main(argv=None):
    ap = argparse.ArgumentParser(description="M6 · Auditoría (auth_log) fuera de ONOS")
    ap.add_argument("--config", default="/etc/tel354/m6.env")
    ap.add_argument("--stdout", action="store_true", help="no escribe en la BD: imprime cada evento en JSON")
    ap.add_argument("--archivo", help="reproduce un log guardado en lugar de seguir los contenedores")
    ap.add_argument("--sin-rest", action="store_true", help="no consulta la REST de M5")
    ap.add_argument("-v", action="store_true")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if a.v else logging.INFO, format="%(asctime)s %(message)s")
    C = leer_config(a.config)

    if a.stdout:
        def destino(ev):
            print(json.dumps(m6_registro.fila(ev), ensure_ascii=False, default=str), flush=True)
        registro = None
    else:
        dsn = (f"host={C.get('PG_HOST', '10.200.3.12')} port={C.get('PG_PORT', '5432')} "
               f"dbname={C.get('PG_DB', 'campus')} "
               f"user={C.get('PG_USUARIO', 'onos_m6')} password={C.get('PG_CLAVE', 'm6-demo')} "
               f"sslmode={C.get('PG_SSLMODE', 'verify-ca')} sslrootcert={C.get('PG_CA', '/etc/tel354/certs/ca.pem')}")
        registro = m6_registro.Registro(m6_registro.conectar_postgres(dsn),
                                        lote=int(C.get("LOTE", 100)), periodo=float(C.get("PERIODO_S", 1)),
                                        maximo=int(C.get("MAXIMO", 10000)))
        destino = registro.agregar

    col = Colector(destino, None if a.archivo or a.stdout else C.get("ESTADO", "/var/lib/tel354/m6-estado.json"))
    col._desde_inicial = C.get("DESDE", "1m")
    if registro:
        registro._al_confirmar = col.confirmar
        registro.iniciar()

    def parar(*_):
        col.activo = False
    signal.signal(signal.SIGTERM, parar)
    signal.signal(signal.SIGINT, parar)

    if a.archivo:
        with open(a.archivo, encoding="utf-8", errors="replace") as f:
            for l in f:
                col.linea("archivo", l)
        log.info("M6: %d eventos de auditoría en %s", col.contados, a.archivo)
    else:
        hilos = [threading.Thread(target=col.seguir_contenedor, args=(c,), daemon=True, name=f"m6-{c}")
                 for c in C.get("CONTENEDORES", "onos-1 onos-2 onos-3").split()]
        if not a.sin_rest:
            urls = C.get("ONOS_REST", "http://192.168.200.11:8181 http://192.168.200.12:8181 "
                                      "http://192.168.200.13:8181").split()
            hilos.append(threading.Thread(target=col.sondear_rest, args=(urls, C.get("ONOS_AUTH", "onos:rocks")),
                                          daemon=True, name="m6-rest"))
        for h in hilos:
            h.start()
        log.info("M6 iniciado: %d fuentes, destino %s", len(hilos), "stdout" if a.stdout else "auth_log")
        while col.activo:
            time.sleep(1)
    if registro:
        registro.detener(vaciar=True)
        log.info("M6 detenido: %d filas escritas, %d perdidas", registro.escritas, registro.perdidas_total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
