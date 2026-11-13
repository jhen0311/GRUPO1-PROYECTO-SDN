#!/usr/bin/env python3
"""
gen_m2_netcfg.py - Network config de tel354.m2 a partir del perfil (TEL354_ENV, por defecto slice.env).

  dns        IP_DNS del perfil (regla 60)
  meters     captura 50 pps (ráfaga 20) y cuarentena 1 Mbps (LLSD R3, Tabla 12)
  jdbc       rol onos_m2 (solo SELECT sobre la matriz) con TLS verify-ca

Los switches de acceso no van aquí: M2 programa la Tabla 1 en los que tienen la regla 600 de M7.

Uso (en el controlador, desde la raíz del repositorio):
  python3 r2-autorizacion/onos-app-m2/gen_m2_netcfg.py > /tmp/m2-netcfg.json
  python3 r2-autorizacion/onos-app-m2/gen_m2_netcfg.py --sin-tabla1   # solo slow-path (puente REST activo)
"""
import json, os, sys

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


def leer_env(ruta):
    v = {}
    for linea in open(ruta, encoding="utf-8"):
        linea = linea.split("#")[0].strip()
        if "=" in linea:
            k, x = linea.split("=", 1)
            v[k.strip()] = x.strip().strip('"')
    return v


E = leer_env(os.path.join(RAIZ, "comun", "config", os.environ.get("TEL354_ENV", "slice.env")))
m2 = {
    "tabla1": "--sin-tabla1" not in sys.argv,
    "dns": E.get("IP_DNS", "10.200.0.14"),
    "capturaPps": 50,
    "capturaRafaga": 20,
    "cuarentenaKbps": 1000,
    "cuarentenaRafaga": 1000,
    "conciliarSeg": 30,
    "jdbcUrl": f"jdbc:postgresql://{E['IP_PG']}:5432/{E.get('PG_DB', 'campus')}"
               "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem",
    "jdbcUsuario": "onos_m2",
    "jdbcClave": "m2-demo",
}
print(json.dumps({"apps": {"tel354.m2": {"m2": m2}}}, indent=2, ensure_ascii=False))
