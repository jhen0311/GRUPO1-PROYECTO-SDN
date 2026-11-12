#!/usr/bin/env python3
"""
gen_base_netcfg.py - Network config de tel354.base (M7 + M8) a partir del perfil (TEL354_ENV).

  core      of:DPID_CORE
  destinos  10.200.0.0/16 -> MAC de IF_DATOS (interfaz de datos del controlador, donde están
            coubicados los servicios), por PUERTO_SERVIDORES del perfil. Fijarlo es lo robusto:
            con 0 la app lo descubre como el único puerto del core sin enlace, y eso falla si un
            pabellón está apagado al arrancar (banco de pruebas, E14)
  jdbc      rol onos_m7 (solo SELECT sobre switch_acceso)

Los switches de acceso no van aquí: salen de la tabla switch_acceso (dpid -> pabellón).

Uso (en el controlador, desde la raíz del repositorio):
  TEL354_ENV=una_instancia.env python3 comun/onos-app-base/gen_base_netcfg.py > /tmp/base-netcfg.json
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
if not E.get("DPID_CORE"):
    sys.exit("El perfil no define DPID_CORE (perfil de un solo core, p. ej. una_instancia.env)")
ifd = E.get("IF_DATOS", "ens4")
try:
    mac = open(f"/sys/class/net/{ifd}/address").read().strip()
except OSError:
    sys.exit(f"No existe la interfaz {ifd} (IF_DATOS): correr en el controlador")
base = {
    "core": f"of:{E['DPID_CORE']}",
    "destinos": [{"prefijo": "10.200.0.0/16", "mac": mac, "puerto": int(os.environ.get("PUERTO_SERVIDORES", E.get("PUERTO_SERVIDORES", 0)))}],
    "ctlPps": 10,
    "ctlRafaga": 10,
    "conciliarSeg": 30,
    "jdbcUrl": f"jdbc:postgresql://{E['IP_PG']}:5432/{E.get('PG_DB', 'campus')}"
               "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem",
    "jdbcUsuario": "onos_m7",
    "jdbcClave": "m7-demo",
}
print(json.dumps({"apps": {"tel354.base": {"base": base}}}, indent=2, ensure_ascii=False))
