#!/usr/bin/env python3
"""
r1_concurrente.py - Logueos concurrentes contra la cadena de autenticación (AVZ08, sección 3.1.1).

Lanza N autenticaciones EAP-TTLS/PAP con H hilos en paralelo (eapol_test como M1, NAS 172.16.0.10)
y mide: éxito, logins por segundo y latencia (mediana y p95) para cada nivel de concurrencia.
Con --con-invalidos mezcla una clave incorrecta cada 5 intentos: FreeRADIUS retiene ~1 s cada
Access-Reject (reject_delay) y la prueba muestra que eso no frena a los usuarios válidos.

Uso (namespace raíz, con los servicios arriba):
  python3 tests/r1_concurrente.py                      # 40 logins con 1, 5 y 10 hilos
  python3 tests/r1_concurrente.py --total 60 --hilos 1,10,20 --con-invalidos
"""
import argparse, statistics, time
from concurrent.futures import ThreadPoolExecutor
from r1_radius import autenticar

VALIDOS = ["alumno.demo", "docente.demo", "ti.demo", "jp.demo"]

def p95(v):
    v = sorted(v)
    return v[max(0, int(round(0.95 * len(v))) - 1)] if v else 0.0

def ronda(total, hilos, invalidos):
    casos = []
    for i in range(total):
        if invalidos and i % 5 == 4:
            casos.append(("clave_incorrecta", "REJECT"))
        else:
            casos.append((VALIDOS[i % len(VALIDOS)], "ACCEPT"))
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=hilos) as ex:
        res = list(ex.map(lambda c: (c[1], autenticar(c[0])), casos))
    reloj = time.perf_counter() - t0
    val = [r[2] for esp, r in res if esp == "ACCEPT" and r[0] == "ACCEPT"]
    bien = sum(1 for esp, r in res if r[0] == esp)
    return {"hilos": hilos, "total": total, "bien": bien, "reloj": reloj, "tasa": total / reloj,
            "mediana": statistics.median(val) if val else 0.0, "p95": p95(val)}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--total", type=int, default=40)
    ap.add_argument("--hilos", default="1,5,10")
    ap.add_argument("--con-invalidos", action="store_true")
    a = ap.parse_args()
    print(f"{a.total} autenticaciones por ronda ({'con' if a.con_invalidos else 'sin'} claves incorrectas)\n")
    print(f"{'hilos':>5} {'correctas':>10} {'reloj (s)':>10} {'logins/s':>9} {'mediana ms':>11} {'p95 ms':>8}")
    for h in [int(x) for x in a.hilos.split(",")]:
        r = ronda(a.total, h, a.con_invalidos)
        print(f"{r['hilos']:>5} {r['bien']:>4}/{r['total']:<5} {r['reloj']:>10.2f} {r['tasa']:>9.1f} "
              f"{r['mediana']:>11.1f} {r['p95']:>8.1f}")
    print("\nLa latencia es la de los accesos válidos. Si los logins/s suben con los hilos, la cadena")
    print("FreeRADIUS + OpenLDAP + PostgreSQL atiende logueos concurrentes sin serializarlos.")

if __name__ == "__main__":
    main()
