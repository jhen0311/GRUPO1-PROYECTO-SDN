#!/usr/bin/env python3
"""
base_auto.py - Régimen base con los puertos descubiertos en ONOS (perfil una_instancia.env).

Topología: un switch core (DPID_CORE) y N switches de acceso (DPIDS_ACCESO, en orden de
pabellón); el controlador cuelga del core por su interfaz de datos y hace de todos los
servidores 10.200.x.y (comun/topo/servicios_coubicados.sh). Los números de puerto salen de
los enlaces LLDP que ONOS ya descubrió: no hay que llenarlos a mano.

  core (Tabla 0, como SW-C1/SW-C2 del piloto)
    210  tránsito etiquetado VLAN 100+N y 999 -> hacia el switch de acceso N
    205  10.(112+N).0.0/16 -> push VLAN 999 -> N
    200  10.(b+N).0.0/16 -> push VLAN 100+N -> N (una por rol y pabellón)
    200  10.200.0.0/16 -> MAC de la interfaz de datos del controlador -> su puerto
    0    drop (sin salida a Internet)
  acceso: las mismas Tablas 0, 1 y 2 de base_slice.py, con el uplink y los puertos de host
  descubiertos.

Desde la app tel354.base (M7 + M8, comun/onos-app-base) este script queda para diagnóstico:
el régimen base y los caminos fijos los instala el controlador al detectar cada switch. Mientras
M2 no programe la Tabla 1, --solo-tabla1 la carga por REST (permisos 100, comunes y los meters
captura y cuarentena) con el appId tel354.tabla1, sin tocar lo de tel354.base.

Uso (root en el controlador, con ONOS, los switches y PostgreSQL arriba):
  python3 r2-autorizacion/proactivo/base_auto.py --dry-run
  python3 r2-autorizacion/proactivo/base_auto.py --solo-tabla1           # puente hasta M2
  python3 r2-autorizacion/proactivo/base_auto.py --solo-tabla1 --borrar
  python3 r2-autorizacion/proactivo/base_auto.py                         # todo (sin tel354.base)
  python3 r2-autorizacion/proactivo/base_auto.py --borrar
"""
import argparse, collections, json, os, sys, time

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.join(AQUI, "..", "..")


def leer_env(ruta):
    v = {}
    for linea in open(ruta, encoding="utf-8"):
        linea = linea.split("#")[0].strip()
        if "=" in linea:
            k, x = linea.split("=", 1)
            v[k.strip()] = x.strip().strip('"')
    return v


ENV = leer_env(os.path.join(RAIZ, "comun", "config", os.environ.get("TEL354_ENV", "una_instancia.env")))
os.environ["ONOS_URL"] = ENV["ONOS_URL"]
os.environ["ONOS_AUTH"] = ENV["ONOS_AUTH"]
sys.path.insert(0, AQUI)
import base_slice as B                     # noqa: E402  (reutiliza reglas_acceso y la lectura de permisos)
from onos_rest import *                    # noqa: E402,F401
import requests                            # noqa: E402

APP = "tel354.base.rest"        # distinto de los appId de la app tel354.base (tel354.m7 y tel354.m8)
APP_T1 = "tel354.tabla1"       # puente de la Tabla 1 hasta que la programe M2
APP_REST_METERS = "org.onosproject.rest"   # los meters creados por REST quedan con este appId
CORE = f"of:{ENV['DPID_CORE']}"
PABELLON = {n: f"of:{d}" for n, d in enumerate(ENV["DPIDS_ACCESO"].split(), start=1)}


def get(ruta):
    r = requests.get(f"{ENV['ONOS_URL']}{ruta}", auth=tuple(ENV["ONOS_AUTH"].split(":")), timeout=10)
    r.raise_for_status()
    return r.json()


def descubrir():
    """Puertos de cada enlace (LLDP) y puertos libres (hosts o controlador) de cada switch."""
    disp = {d["id"] for d in get("/devices")["devices"] if d["available"]}
    faltan = {CORE, *PABELLON.values()} - disp
    if faltan:
        sys.exit(f"ONOS no ve {sorted(faltan)}: ¿se fijó el datapath-id (ovs_preparar.sh) y el switch está conectado?")
    hacia = collections.defaultdict(dict)            # hacia[a][b] = puerto de a que va a b
    for l in get("/links")["links"]:
        hacia[l["src"]["device"]][l["dst"]["device"]] = int(l["src"]["port"])
    for a, b in [(CORE, d) for d in PABELLON.values()] + [(d, CORE) for d in PABELLON.values()]:
        if b not in hacia[a]:
            sys.exit(f"ONOS aún no descubre el enlace {a} -> {b} (LLDP): esperar unos segundos y repetir")
    libres = {}
    for d in (CORE, *PABELLON.values()):
        enlaces = set(hacia[d].values())
        libres[d] = sorted(int(p["port"]) for p in get(f"/devices/{d}/ports")["ports"]
                           if p["port"].isdigit() and p["isEnabled"] and int(p["port"]) not in enlaces)
    return hacia, libres


def reglas_core(hacia, puerto_ctl, mac_ctl):
    R = []
    for n, dev in PABELLON.items():
        p = hacia[CORE][dev]
        R.append(regla(CORE, 0, 210, [vlan(100 + n), eth_type("0x0800")], [output(p)]))
        R.append(regla(CORE, 0, 210, [vlan(999), eth_type("0x0800"), ipv4_dst(f"10.{112 + n}.0.0/16")], [output(p)]))
        R.append(regla(CORE, 0, 205, [eth_type("0x0800"), ipv4_dst(f"10.{112 + n}.0.0/16")],
                       [push_vlan(), set_vlan(999), output(p)]))
        for rol, b in B.ROLES.items():
            if rol != "CUARENTENA":
                R.append(regla(CORE, 0, 200, [eth_type("0x0800"), ipv4_dst(f"10.{b + n}.0.0/16")],
                               [push_vlan(), set_vlan(100 + n), output(p)]))
    R.append(regla(CORE, 0, 200, [eth_type("0x0800"), ipv4_dst("10.200.0.0/16")],
                   [set_eth_dst(mac_ctl), output(puerto_ctl)]))
    R.append(regla(CORE, 0, 0, [], []))
    return R


def app_activa(nombre):
    try:
        return get(f"/applications/{nombre}").get("state") == "ACTIVE"
    except requests.HTTPError:
        return False


def tabla1(a):
    """Tabla 1 de cada switch de acceso (lo que hará M2): permisos 100, 60, 58, 57, 55, 50 y 0."""
    if a.borrar:
        borrar_app(APP_T1)
        for d in PABELLON.values():
            borrar_meters(d, app=APP_REST_METERS)
        print("Tabla 1 y meters captura y cuarentena retirados.")
        return
    permisos = ([("ALUMNO", 16, "10.200.1.10", "TCP", 443)] * 13 if a.dry_run else B.leer_permisos(a.dsn))
    B.HOST_PORTS = ()
    for n, dev in PABELLON.items():
        if a.sin_meters:
            m = {"arp": {}, "captura": None, "cuarentena": None}
        else:
            m = {"arp": {}, "captura": crear_meter(dev, "PKTS_PER_SEC", 50, 20, a.dry_run),
                 "cuarentena": crear_meter(dev, "KB_PER_SEC", 1000, 1000, a.dry_run)}
        R = [r for r in B.reglas_acceso(dev, m, permisos) if r["tableId"] == 1]
        print(f"acceso {dev} (pabellón {n}): Tabla 1 con {post_flows(R, app=APP_T1, dry=a.dry_run)} reglas")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--if-datos", default=ENV.get("IF_DATOS", "ens4"),
                    help="interfaz de datos del controlador (enlace hacia el core)")
    ap.add_argument("--dsn", default=f"host={ENV['IP_PG']} dbname={ENV['PG_DB']} user=onos_m2 "
                                     "password=m2-demo sslmode=require")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--borrar", action="store_true")
    ap.add_argument("--sin-meters", action="store_true")
    ap.add_argument("--solo-tabla1", action="store_true",
                    help="solo la Tabla 1 y sus meters (con la app tel354.base activa)")
    a = ap.parse_args()

    if a.solo_tabla1:
        tabla1(a)
        return
    if not a.dry_run and app_activa("tel354.base"):
        sys.exit("La app tel354.base está activa: ella instala el régimen base. "
                 "Usar --dry-run (diagnóstico) o --solo-tabla1.")

    if a.borrar:
        borrar_app(APP)
        for d in PABELLON.values():
            borrar_meters(d, app=APP_REST_METERS)
        print("Reglas y meters del régimen base retirados.")
        return

    hacia, libres = descubrir()
    if len(libres[CORE]) != 1:
        sys.exit(f"El core debería tener un solo puerto libre (hacia el controlador) y tiene {libres[CORE]}")
    puerto_ctl = libres[CORE][0]
    mac_ctl = open(f"/sys/class/net/{a.if_datos}/address").read().strip()
    print("core: " + ", ".join(f"pabellón {n} por {hacia[CORE][d]}" for n, d in PABELLON.items())
          + f", controlador ({mac_ctl}) por {puerto_ctl}")

    if not a.dry_run:
        configurar("org.onosproject.provider.host.impl.HostLocationProvider", {"requestInterceptsEnabled": "false"})
    R = reglas_core(hacia, puerto_ctl, mac_ctl)
    print(f"core ({CORE}): {post_flows(R, app=APP, dry=a.dry_run)} reglas")
    permisos = ([("ALUMNO", 16, "10.200.1.10", "TCP", 443)] * 13 if a.dry_run
                else (time.sleep(2), B.leer_permisos(a.dsn))[1])

    for n, dev in PABELLON.items():
        B.UPLINK = hacia[dev][CORE]
        B.HOST_PORTS = tuple(libres[dev])
        if a.sin_meters:
            m = {"arp": {p: None for p in B.HOST_PORTS}, "captura": None, "cuarentena": None}
        else:
            m = {"arp": {p: crear_meter(dev, "PKTS_PER_SEC", 10, 10, a.dry_run) for p in B.HOST_PORTS},
                 "captura": crear_meter(dev, "PKTS_PER_SEC", 50, 20, a.dry_run),
                 "cuarentena": crear_meter(dev, "KB_PER_SEC", 1000, 1000, a.dry_run)}
        R = B.reglas_acceso(dev, m, permisos)
        cuenta = collections.Counter(r["tableId"] for r in R)
        print(f"acceso {dev} (pabellón {n}): uplink {B.UPLINK}, hosts {list(B.HOST_PORTS)}: "
              f"{post_flows(R, app=APP, dry=a.dry_run)} reglas [T0 {cuenta[0]}, T1 {cuenta[1]}, T2 {cuenta[2]}]")
    print("Listo. En la consola de ONOS:  flows -s any " + PABELLON[1])


if __name__ == "__main__":
    main()
