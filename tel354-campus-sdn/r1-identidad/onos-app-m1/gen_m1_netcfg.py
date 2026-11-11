#!/usr/bin/env python3
"""
gen_m1_netcfg.py - Network config de M1 a partir del perfil de despliegue (TEL354_ENV).

  - Con IP_NAS (una instancia): "nasIp" = IP_NAS.
  - Sin IP_NAS (clúster): "nasIpPorNodo" con IP_ONOS_1..3 para onos-1..3.
Así el perfil .env es la única fuente de las IP; m1-netcfg.json queda como la salida de
referencia del piloto (slice.env).

Uso (desde la raíz del repositorio):
  TEL354_ENV=una_instancia.env python3 r1-identidad/onos-app-m1/gen_m1_netcfg.py > /tmp/m1-netcfg.json
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
m1 = {}
if E.get("IP_NAS"):
    m1["nasIp"] = E["IP_NAS"]
else:
    nodos = {f"onos-{i}": E[f"IP_ONOS_{i}"] for i in (1, 2, 3) if E.get(f"IP_ONOS_{i}")}
    if not nodos:
        sys.exit("El perfil no define IP_NAS ni IP_ONOS_1..3")
    m1["nasIpPorNodo"] = nodos
m1.update({
    "radiusIp": E["IP_RADIUS"],
    "radiusPuerto": 1812,
    "radiusSecreto": E["RADIUS_SECRET"],
    "keaIp": E["IP_KEA"],
    "keaCtrlUrl": f"http://{E['IP_KEA']}:8000/",
    "jdbcUrl": f"jdbc:postgresql://{E['IP_PG']}:5432/{E.get('PG_DB', 'campus')}"
               "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem",
    "jdbcUsuario": "onos_m1",
    "jdbcClave": "m1-demo",
    "rebotarPuertoAlExpirar": True,
    "autenticandoSeg": 30,
    "sinDhcpSeg": 60,
    "rebotePuertoSeg": 5,
    "eapIdentidadIntervaloSeg": 30,
    "eapIdentidadIntentos": 3,
})
print(json.dumps({"apps": {"tel354.m1": {"m1": m1}}}, indent=2, ensure_ascii=False))
