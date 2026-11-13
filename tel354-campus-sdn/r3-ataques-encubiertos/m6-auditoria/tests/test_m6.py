#!/usr/bin/env python3
"""
Pruebas de M6 sin ONOS ni PostgreSQL: interpretación de las líneas reales del log (los formatos son
los de los log.info/log.warn de M1, M2, M4a y M5), deduplicación, punto de control, REST de M5 y la
escritura por lotes de Registro (con una conexión simulada que imita los errores de psycopg2).

Uso (desde la raíz del repositorio):  python3 r3-ataques-encubiertos/m6-auditoria/tests/test_m6.py
"""
import datetime, json, os, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import m6_colector as C   # noqa: E402
import m6_registro as R   # noqa: E402

K = "2026-11-12T15:04:05,123 | INFO  | m2 | M2Manager | 227 - pe.edu.pucp.tel354.tel354-m2 - 2.0.0 | "
SES_A = "sesion 12 [alumno.demo ALUMNO 0a:00:00:00:00:01 en of:0000000000000001/2 IP 10.17.0.5 VLAN 101 ACTIVA]"
SES_N = "sesion 12 [alumno.demo ALUMNO 0a:00:00:00:00:01 en of:0000000000000001/2 VLAN 101 AUTENTICADA]"
INC = "incidente 7 [alumno.demo ESCANEO FUERTE/CRITICO N2, 1 sesiones]"

CASOS = [
    ("M1: Access-Accept alumno.demo rol ALUMNO (Session-Timeout 3600 s) para 0a:00:00:00:00:01 en "
     "of:0000000000000001/2", "AUTH_ACEPTADA", None, "alumno.demo"),
    ("M1: Access-Reject para 0a:00:00:00:00:01 en of:0000000000000001/2", "AUTH_FALLIDA", None, None),
    ("M1: usuario inactivo.demo o rol ALUMNO sin fila activa en la BD; se rechaza", "AUTH_FALLIDA", None,
     "inactivo.demo"),
    ("M1: of:0000000000000009 no es un switch de acceso registrado; se rechaza alumno.demo", "AUTH_FALLIDA", None,
     "alumno.demo"),
    ("M1: AUTENTICADA " + SES_N, "SESION_AUTENTICADA", 12, "alumno.demo"),
    ("M1: ACTIVA " + SES_A + " con 1 reglas 110 (SessionReady)", "SESION_ACTIVA", 12, "alumno.demo"),
    ("M1: CERRADA " + SES_A + " (DESCONEXION)", "SESION_CERRADA", 12, "alumno.demo"),
    ("M1: CERRADA " + SES_A + " (reautenticación)", "SESION_CERRADA", 12, "alumno.demo"),
    ("M1: BindingViolation (ARP) " + SES_A + " usa 10.16.0.1 (concedida 10.17.0.5) [inequívoca]", "SUPLANTACION",
     12, "alumno.demo"),
    ("M1: revocación de la sesión 12 (CUARENTENA) pedida por rest:onos", "SESION_REVOCADA", 12, None),
    ("M1: incidente 7: 1 sesiones del usuario 3 pasan a cuarentena", "CUARENTENA_APLICADA", None, None),
    ("M2: AuthorizationDenied sesion 12 [alumno.demo ALUMNO] -> 10.200.1.30 proto 6 puerto 5432 en "
     "of:0000000000000001/2", "AUTORIZACION_DENEGADA", 12, "alumno.demo"),
    ("M2: AuthorizationDenied sesion 12 [alumno.demo ALUMNO] -> 10.200.1.30 proto 1 en of:0000000000000001/2",
     "AUTORIZACION_DENEGADA", 12, "alumno.demo"),
    ("M4a: ThreatSuspected P=12/10 W=10s sesion 12 -> [10.200.1.30/6[22, 23, 80]]", "SOSPECHA", 12, None),
    ("M5: " + INC + " FUERTE/CRITICO -> N2 [] -> [" + SES_A + "] (sospecha P=12/10 W=10s sesion 12 -> []; "
     "modo ACTIVO)", "CONTENCION_APLICADA", 12, "alumno.demo"),
    ("M5: " + INC + " sube de N1 a N2 con la nueva evidencia", "CONTENCION_APLICADA", None, "alumno.demo"),
    ("M5: " + INC + " venció sin renovación (ContainmentLifted)", "CONTENCION_RETIRADA", None, "alumno.demo"),
    ("M5: incidente 7 DESCARTADO por rest:onos vía rest (falso positivo)", "ADMIN_INCIDENTE", None, None),
    ("M5: modo OBSERVACION fijado por consola:onos vía consola", "ADMIN_INCIDENTE", None, None),
    ("M1: " + SES_A + " sin reglas en el switch: CERRADA (REINICIO)", "SESION_CERRADA", 12, "alumno.demo"),
    ("M4a: IdsAlert sid 1000004 sev 1 tipo PETICION_MALICIOSA sesion 12 [alumno.demo ALUMNO] -> 10.200.1.20 proto 6 "
     "puerto 443 sensor slice: TEL354 peticion maliciosa SQLi", "IDS_ALERTA", 12, "alumno.demo"),
    ("M4a: alerta del IDS sin sesión para 10.200.1.20 (sid 1000002), no se atribuye", "IDS_ALERTA", None, None),
]


class Interpretar(unittest.TestCase):
    def test_cada_formato(self):
        for linea, evento, sid, uid in CASOS:
            with self.subTest(linea=linea[:60]):
                evs = C.interpretar(K + linea)
                self.assertTrue(evs, "no se reconoció")
                self.assertEqual(evs[0]["evento"], evento)
                self.assertEqual(evs[0]["sesion_id"], sid)
                self.assertEqual(evs[0]["uid"], uid)
                self.assertLessEqual(len(evento), 30)

    def test_detalles(self):
        d = C.interpretar(CASOS[11][0])[0]["detalle"]
        self.assertEqual((d["ip_dst"], d["proto"], d["puerto"], d["dpid"]), ("10.200.1.30", 6, 5432,
                                                                             "of:0000000000000001"))
        self.assertNotIn("puerto", C.interpretar(CASOS[12][0])[0]["detalle"])       # ICMP sin puerto
        self.assertTrue(C.interpretar(CASOS[8][0])[0]["detalle"]["inequivoca"])
        self.assertEqual(C.interpretar(CASOS[6][0])[0]["detalle"]["motivo"], "DESCONEXION")
        self.assertEqual(C.interpretar(CASOS[10][0])[0]["usuario_id"], 3)
        s = C.interpretar(CASOS[13][0])[0]["detalle"]
        self.assertEqual((s["metrica"], s["valor"], s["umbral"], s["ventana_s"]), ("P", 12.0, 10.0, 10))

    def test_confirmacion_da_dos_filas(self):
        evs = C.interpretar("M5: incidente 7 CONFIRMADO por rest:onos vía rest (escaneo reiterado): cuarentena de "
                            "alumno.demo hasta 2026-11-13T15:04:05Z")
        self.assertEqual([e["evento"] for e in evs], ["CUARENTENA", "ADMIN_INCIDENTE"])
        self.assertEqual(evs[0]["detalle"]["fin"], "2026-11-13T15:04:05Z")

    def test_ignora_lo_que_no_es_auditoria(self):
        for l in ["M2 iniciado (appId 210)", "M1: EAPOL-Start de 0a:00:00:00:00:01 en of:01/2",
                  "M5: aplicado a 1 sesiones (4 reglas)", "Device of:0000000000000001 connected", ""]:
            self.assertEqual(C.interpretar(K + l), [], l)

    def test_ts_docker(self):
        crudo, ts, resto = C.separar_ts("2026-11-12T20:04:05.123456789Z M2: x")
        self.assertEqual(crudo, "2026-11-12T20:04:05.123456789Z")
        self.assertEqual(ts, datetime.datetime(2026, 11, 12, 20, 4, 5, 123456, tzinfo=datetime.timezone.utc))
        self.assertEqual(resto, "M2: x")


class Colectar(unittest.TestCase):
    def test_dedup_y_control(self):
        salida = []
        with tempfile.TemporaryDirectory() as d:
            ruta = os.path.join(d, "estado.json")
            col = C.Colector(salida.append, ruta)
            l1 = "2026-11-12T20:04:05.000000001Z " + K + CASOS[11][0]
            self.assertEqual(col.linea("onos-1", l1), 1)
            self.assertEqual(col.linea("onos-2", "2026-11-12T20:04:05.000000002Z " + K + CASOS[11][0]), 0)
            col.confirmar({"onos-1": "2026-11-12T20:04:05.000000001Z"})
            col2 = C.Colector(salida.append, ruta)                  # reinicio: lee el punto de control
            self.assertEqual(col2.linea("onos-1", l1), 0)           # esa línea ya estaba guardada
            self.assertEqual(col2.linea("onos-1", "2026-11-12T20:04:06.0Z " + K + CASOS[6][0]), 1)
        self.assertEqual(salida[0]["origen"], ("onos-1", "2026-11-12T20:04:05.000000001Z"))

    def test_misma_instancia_no_es_duplicado(self):
        """Dos Access-Reject seguidos del mismo equipo en una instancia son dos intentos."""
        salida = []
        col = C.Colector(salida.append)
        l = K + CASOS[1][0]
        self.assertEqual(col.linea("onos", "2026-11-12T20:04:05.1Z " + l), 1)
        self.assertEqual(col.linea("onos", "2026-11-12T20:04:05.2Z " + l), 1)
        self.assertEqual(len(salida), 2)

    def test_rest(self):
        salida = []
        col = C.Colector(salida.append)
        base = [{"id": 7, "estado": "EN_INVESTIGACION", "sesion": 12, "usuario": "alumno.demo"}]
        self.assertEqual(col.comparar_incidentes(base, primera=True), 0)     # línea base
        self.assertEqual(col.comparar_incidentes(base), 0)
        col.linea("onos-1", K + "M5: " + INC + " venció sin renovación (ContainmentLifted)")
        self.assertEqual(col.comparar_incidentes([dict(base[0], estado="VENCIDO")]), 0)   # ya visto en el log
        self.assertEqual(col.comparar_incidentes([dict(base[0], estado="CONFIRMADO")]), 1)
        self.assertEqual(salida[-1]["evento"], "INCIDENTE_ESTADO")
        self.assertEqual(salida[-1]["detalle"]["estado_anterior"], "VENCIDO")


class ErrorPg(Exception):
    def __init__(self, codigo):
        super().__init__(codigo)
        self.pgcode = codigo


class CxFalsa:
    """Imita lo que Registro usa de una conexión psycopg2."""
    def __init__(self, fallar=None):
        self.filas, self.fallar, self.closed, self.commits = [], fallar, 0, 0

    def cursor(self):
        cx = self

        class Cur:
            def executemany(self, sql, filas):
                for f in filas:
                    e = cx.fallar(sql, f) if cx.fallar else None
                    if e:
                        raise e
                cx.pendiente = list(filas)

            def close(self):
                pass
        return Cur()

    def commit(self):
        self.filas += getattr(self, "pendiente", [])
        self.pendiente = []
        self.commits += 1

    def rollback(self):
        self.pendiente = []

    def close(self):
        self.closed = 1


def ev(i, **k):
    return dict({"evento": "AUTORIZACION_DENEGADA", "sesion_id": i, "uid": "alumno.demo",
                 "detalle": {"n": i}, "origen": ("onos-1", "2026-11-12T20:00:%02d.0Z" % (i % 60))}, **k)


class Registrar(unittest.TestCase):
    def test_lotes_de_100(self):
        cx = CxFalsa()
        confirmados = []
        r = R.Registro(lambda: cx, lote=100, al_confirmar=confirmados.append)
        for i in range(250):
            r.agregar(ev(i))
        while r.pendientes():
            self.assertTrue(r.escribir_lote())
        self.assertEqual((len(cx.filas), cx.commits), (250, 3))
        self.assertEqual(confirmados[-1], {"onos-1": "2026-11-12T20:00:59.0Z"})
        self.assertEqual(json.loads(cx.filas[0]["detalle"])["usuario"], "alumno.demo")

    def test_bd_caida_retiene_10000_y_cuenta_perdidas(self):
        caida = {"si": True}

        def conectar():
            if caida["si"]:
                raise OSError("conexión rechazada")
            return cx
        cx = CxFalsa()
        r = R.Registro(conectar, lote=100, maximo=10_000)
        for i in range(10_050):
            r.agregar(ev(i))
        self.assertFalse(r.escribir_lote())
        self.assertEqual((r.pendientes(), r.perdidas_total), (10_000, 50))
        caida["si"] = False
        while r.pendientes():
            self.assertTrue(r.escribir_lote())
        self.assertEqual(len(cx.filas), 10_001)
        perd = [f for f in cx.filas if f["evento"] == "M6_PERDIDAS"]
        self.assertEqual(json.loads(perd[0]["detalle"])["filas_descartadas"], 50)
        self.assertEqual(cx.filas[-1]["sesion_id"], 10_049)                  # se conservan las más nuevas

    def test_solo_insert(self):
        """onos_m6 solo tiene INSERT: la sentencia no consulta otras tablas (lo hace el disparador)."""
        cx = CxFalsa(fallar=lambda sql, f: ErrorPg("42501") if "SELECT" in sql else None)
        r = R.Registro(lambda: cx)
        r.agregar(ev(1))
        self.assertTrue(r.escribir_lote())
        self.assertEqual(len(cx.filas), 1)
        self.assertNotIn("SELECT", R.SQL_INSERT)

    def test_referencia_rota_fila_por_fila(self):
        cx = CxFalsa(fallar=lambda sql, f: ErrorPg("23503") if f["sesion_id"] == 999 else None)
        r = R.Registro(lambda: cx)
        for i in (1, 999, 2):
            r.agregar(ev(i))
        self.assertTrue(r.escribir_lote())
        self.assertEqual([f["sesion_id"] for f in cx.filas], [1, None, 2])
        self.assertEqual(json.loads(cx.filas[1]["detalle"])["referencia_rota"]["sesion_id"], 999)

    def test_evento_largo_se_recorta(self):
        self.assertEqual(len(R.fila({"evento": "X" * 40})["evento"]), 30)


class Archivo(unittest.TestCase):
    def test_reproduce_un_log(self):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8") as f:
            for linea, *_ in CASOS:
                f.write(K + linea + "\n")
            f.write(K + "M2 iniciado (appId 210)\n")
            ruta = f.name
        import contextlib, io
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            C.main(["--archivo", ruta, "--stdout", "--config", "/no/existe"])
        os.unlink(ruta)
        filas = [json.loads(l) for l in out.getvalue().splitlines()]
        self.assertEqual(len(filas), len(CASOS) + 1)          # el DESCARTADO da dos filas
        self.assertTrue(all(len(x["evento"]) <= 30 for x in filas))


if __name__ == "__main__":
    unittest.main(verbosity=1)
