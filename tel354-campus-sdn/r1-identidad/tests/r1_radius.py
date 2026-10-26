#!/usr/bin/env python3
"""
Pruebas cuantitativas de R1 sobre la cadena de autenticación (AVZ08, secciones 4.1 y 4.2).

Usa eapol_test como si fuera M1: el NAS es 172.16.0.10 y el servidor 10.200.0.10.
  --bateria      casos válidos e inválidos con su resultado esperado -> tasa de bloqueo
  --latencia N   N autenticaciones EAP-TTLS/PAP por usuario -> media, mediana, p95

Mide el tramo FreeRADIUS + OpenLDAP + PostgreSQL de la Tabla 9 del AVZ08
(EAP-TTLS, bind LDAPS con SHA-512 crypt y consulta del rol). No incluye el
tramo host-switch-ONOS, que se mide cuando M1 esté en la topología.

Uso (desde el namespace raíz de Mininet, que tiene 172.16.0.10):
  python3 r1-identidad/tests/r1_radius.py --bateria
  python3 r1-identidad/tests/r1_radius.py --latencia 50
"""
import argparse, os, re, statistics, subprocess, time

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eap")
SECRETO = "tel354-secreto"
ROLES = {1: "ALUMNO", 2: "JP", 3: "DOCENTE", 4: "ADMINISTRATIVO", 5: "TI", 6: "INVITADO", 7: "CUARENTENA"}

# caso: (archivo de eapol_test, resultado esperado, rol esperado, descripción)
BATERIA = [
    ("alumno.demo",         "ACCEPT", "ALUMNO",     "credencial válida, rol base"),
    ("docente.demo",        "ACCEPT", "DOCENTE",    "credencial válida, rol base"),
    ("ti.demo",             "ACCEPT", "TI",         "credencial válida, rol base"),
    ("jp.demo",             "ACCEPT", "JP",         "doble rol: base ALUMNO + inscripción JP (D4)"),
    ("sancion.demo",        "ACCEPT", "CUARENTENA", "sanción vigente: rol degradado (D3)"),
    ("clave_incorrecta",    "REJECT", None,         "contraseña incorrecta"),
    ("usuario_inexistente", "REJECT", None,         "usuario que no existe en LDAP"),
    ("inactivo.demo",       "REJECT", None,         "cuenta con estado INACTIVO"),
]

def autenticar(nombre, timeout=10):
    t0 = time.perf_counter()
    p = subprocess.run(["eapol_test", "-c", f"{D}/{nombre}.conf", "-a", "10.200.0.10",
                        "-A", "172.16.0.10", "-s", SECRETO, "-r0", "-t", str(timeout)],
                       capture_output=True, text=True)
    ms = (time.perf_counter() - t0) * 1000
    out = p.stdout
    res = "ACCEPT" if re.search(r"^SUCCESS$", out, re.M) else "REJECT"
    # El Filter-Id no se imprime; el rol se lee del VSA PUCP-Role-Code (vendor 99999, tipo 1)
    rol = None
    m = re.search(r"Value: 0001869f0106([0-9a-f]{8})", out)
    if res == "ACCEPT" and m:
        rol = ROLES.get(int(m.group(1), 16))
    usuario = re.findall(r"Attribute 1 \(User-Name\) length=\d+\s+Value: '([^']+)'", out)
    rondas = len(re.findall(r"Sending RADIUS message", out))
    return res, rol, ms, rondas

def bateria():
    print(f"{'caso':22} {'esperado':9} {'obtenido':9} {'rol':11} {'ms':>6}  descripción")
    ok_val = ok_inv = n_val = n_inv = 0
    for nombre, esp, rol_esp, desc in BATERIA:
        res, rol, ms, _ = autenticar(nombre)
        bien = res == esp and (rol_esp is None or rol == rol_esp)
        if esp == "ACCEPT":
            n_val += 1; ok_val += bien
        else:
            n_inv += 1; ok_inv += bien
        print(f"{nombre:22} {esp:9} {res:9} {str(rol or '-'):11} {ms:6.0f}  {desc} {'' if bien else '  <-- FALLA'}")
    print(f"\nTasa de bloqueo de accesos inválidos: {ok_inv}/{n_inv} = {100*ok_inv/n_inv:.1f} %")
    print(f"Accesos válidos con el rol correcto:  {ok_val}/{n_val} = {100*ok_val/n_val:.1f} %")

def latencia(n):
    for usuario in ("alumno.demo", "docente.demo", "ti.demo"):
        tiempos, fallos, rondas = [], 0, 0
        for _ in range(n):
            res, _, ms, r = autenticar(usuario)
            if res == "ACCEPT":
                tiempos.append(ms); rondas = r
            else:
                fallos += 1
        t = sorted(tiempos)
        p95 = t[max(0, int(round(0.95 * len(t))) - 1)]
        print(f"{usuario:13} n={n} éxito={100*len(t)/n:5.1f} %  rondas RADIUS={rondas}  "
              f"media={statistics.mean(t):6.1f} ms  mediana={statistics.median(t):6.1f} ms  "
              f"p95={p95:6.1f} ms  máx={t[-1]:6.1f} ms")
    print("\nNota: incluye el arranque de eapol_test en cada intento (unos ms); "
          "es una cota superior del tramo de autenticación de la Tabla 9 del AVZ08.")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--bateria", action="store_true")
    ap.add_argument("--latencia", type=int, default=0)
    a = ap.parse_args()
    if a.bateria:
        bateria()
    if a.latencia:
        latencia(a.latencia)
    if not (a.bateria or a.latencia):
        ap.print_help()
