#!/usr/bin/env python3
"""
latencia_m1.py - Latencia de admisión medida con el log de M1 (AVZ08, Tabla 9).

Para cada login empareja, por MAC, la última línea «EAPOL-Start» con la siguiente
«AUTENTICADA» (fin de 802.1X) y «ACTIVA ... (SessionReady)» (fin del DHCP y reglas
instaladas). Incluye el tramo host–switch–ONOS que las pruebas directas no miden.

Uso (en la VM, namespace raíz):
  python3 r1-identidad/tests/latencia_m1.py                 # log por defecto de ONOS 1.13
  python3 r1-identidad/tests/latencia_m1.py --log RUTA      # otro karaf.log
"""
import argparse, glob, re, statistics, sys
from datetime import datetime

TS = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d{3})")
MAC = re.compile(r"([0-9a-f]{2}(?::[0-9a-f]{2}){5})")

def ts(linea):
    m = TS.match(linea)
    return datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S,%f") if m else None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=None)
    a = ap.parse_args()
    rutas = [a.log] if a.log else sorted(glob.glob("/opt/onos/apache-karaf-*/data/log/karaf.log*"), reverse=True)
    if not rutas:
        sys.exit("No se encontró karaf.log: indica la ruta con --log")
    inicio, auth, filas = {}, {}, []
    for ruta in rutas:
        for linea in open(ruta, errors="replace"):
            if "M1:" not in linea:
                continue
            t = ts(linea)
            low = linea.lower()
            m = MAC.search(low)
            if not (t and m):
                continue
            mac = m.group(1)
            if "eapol-start" in low:
                inicio[mac] = t
                auth.pop(mac, None)
            elif "sessionready" in low and mac in inicio:
                t0 = inicio.pop(mac)
                ta = auth.pop(mac, None)
                usuario = re.search(r"sesion \d+ \[(\S+) ", linea)
                filas.append((t0, mac, usuario.group(1) if usuario else "?",
                              (ta - t0).total_seconds() * 1000 if ta else None,
                              (t - t0).total_seconds() * 1000))
            elif "m1: autenticada" in low and mac in inicio:
                auth[mac] = t
    if not filas:
        sys.exit("No hay logins completos en el log (EAPOL-Start ... SessionReady).")
    print(f"{'inicio':19}  {'MAC':17}  {'usuario':13} {'802.1X ms':>10} {'hasta SessionReady ms':>22}")
    for t0, mac, u, a1, a2 in sorted(filas):
        print(f"{t0:%Y-%m-%d %H:%M:%S}  {mac:17}  {u:13} {a1 if a1 is None else round(a1):>10} {round(a2):>22}")
    tot = [f[4] for f in filas]
    print(f"\nlogins: {len(tot)}   mediana: {statistics.median(tot):.0f} ms   máximo: {max(tot):.0f} ms")
    print("login.sh pide la clave antes de arrancar wpa_supplicant: la medida no incluye el tiempo de tipeo.")

if __name__ == "__main__":
    main()
