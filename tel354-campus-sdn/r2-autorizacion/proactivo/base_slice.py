#!/usr/bin/env python3
"""
base_slice.py - Régimen base del piloto en el slice (LAB6), por la API REST de ONOS 2.7.

Es la versión para 2 cores y 2 switches de acceso de onos_static.py (maqueta de Mininet).
Instala lo que no depende de las sesiones (las reglas de sesión las pone M1 y las de
contención M5):

  SW-C1 y SW-C2 (Tabla 0)
    210  tránsito etiquetado: VLAN 100+N -> hacia el switch de acceso N; VLAN 999 por /16 de Cuarentena
    205  retorno a Cuarentena: 10.(112+N).0.0/16 -> push VLAN 999 -> hacia N
    200  retorno a usuarios: 10.(b+N).0.0/16 -> push VLAN 100+N -> hacia N (una por rol y pabellón)
    200  servidores 10.200.x.y/32 -> MAC del servidor -> su puerto trusted (+ espejo al IDS en SW-C2)
    200  plano de control 172.16.0.10-13/32 -> MAC del nodo -> su puerto
    190  resto de IPv4 -> salida (SW-C2: hacia SW-C1; SW-C1: Internet, + espejo al IDS)
    0    drop
  SW-A1 y SW-A2 (Tablas 0, 1 y 2), igual que en la maqueta:
    meters  ctl_P (10 pps por puerto de host), captura (50 pps), cuarentena (1 Mbps)
    T0  900 EAPOL, 800 ARP por puerto (meter ctl_P), 600 uplink -> T2, 10 drop
    T1  100 permisos por rol (v_permiso_efectivo), 60 DNS, 58 x2 y 57 Cuarentena, 55 x2 captura, 50 salida, 0
    T2  200 servidores y 180 salida (pop VLAN -> uplink)
  (El meter contencion de 1 Mbps de N2 lo crea M5 en su primera contención; LLSD R3, Tabla 12.)

En la arquitectura estas reglas las solicitan M2, M7 y M8 dentro de ONOS; en el piloto se cargan
por REST, como en el laboratorio de Floodlight.

Uso (en el Controlador, como root, con el clúster y los switches conectados):
  python3 r2-autorizacion/proactivo/base_slice.py            # instala
  python3 r2-autorizacion/proactivo/base_slice.py --dry-run  # solo cuenta las reglas
  python3 r2-autorizacion/proactivo/base_slice.py --borrar   # retira reglas y meters del régimen base
"""
import argparse, collections, json, os, sys, time
AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.join(AQUI, "..", "..")
sys.path.insert(0, os.path.join(RAIZ, "comun", "lib"))


def leer_env(ruta):
    v = {}
    for linea in open(ruta, encoding="utf-8"):
        linea = linea.split("#")[0].strip()
        if "=" in linea:
            k, x = linea.split("=", 1)
            v[k.strip()] = x.strip()
    return v


ENV = leer_env(os.path.join(RAIZ, "comun", "config", "slice.env"))
os.environ.setdefault("ONOS_URL", ENV["ONOS_URL"])
os.environ.setdefault("ONOS_AUTH", ENV["ONOS_AUTH"])
from onos_rest import *  # noqa: E402  (usa ONOS_URL y ONOS_AUTH)

APP = "tel354.base"
TOPO = json.load(open(os.path.join(RAIZ, "comun", "config", "topologia_slice.json"), encoding="utf-8"))
SW = TOPO["switches"]
ACCESO = {SW[n]["pabellon"]: n for n in ("SW-A1", "SW-A2")}
UPLINK, HOST_PORTS = 1, (2, 3)
ROLES = {"ALUMNO": 16, "JP": 32, "DOCENTE": 48, "ADMINISTRATIVO": 64, "TI": 80, "INVITADO": 96, "CUARENTENA": 112}


def dpid(nombre):
    return SW[nombre]["dpid"]


def mac_local(ifname):
    try:
        return open(f"/sys/class/net/{ifname}/address").read().strip()
    except OSError:
        return None


DRY = "--dry-run" in sys.argv


def mac_de(nombre, datos):
    m = datos.get("mac", "RELLENAR")
    if m == "RELLENAR" and DRY:
        return "00:00:00:00:00:00"
    if m == "RELLENAR" and nombre == "Controlador":
        m = mac_local(os.environ.get("IF_SWC1", "eth0")) or m
    if m == "RELLENAR":
        sys.exit(f"Falta la MAC de {nombre} en comun/config/topologia_slice.json "
                 "(en esa VM: ip -br link show <interfaz de datos>)")
    return m


def reglas_core(nombre, espejo=True):
    dev = dpid(nombre)
    c = TOPO["cores"][nombre]
    R = []
    # Tránsito ya etiquetado (prioridad mayor que las de push, que también lo verían)
    for n, puerto in c["pabellones"].items():
        R.append(regla(dev, 0, 210, [vlan(100 + int(n)), eth_type("0x0800")], [output(puerto)]))
        R.append(regla(dev, 0, 210, [vlan(999), eth_type("0x0800"), ipv4_dst(f"10.{112 + int(n)}.0.0/16")],
                       [output(puerto)]))
    # Retorno a usuarios sin etiqueta (desde servidores, el plano de control o Internet)
    for n, puerto in c["pabellones"].items():
        n = int(n)
        R.append(regla(dev, 0, 205, [eth_type("0x0800"), ipv4_dst(f"10.{112 + n}.0.0/16")],
                       [push_vlan(), set_vlan(999), output(puerto)]))
        for rol, b in ROLES.items():
            if rol != "CUARENTENA":
                R.append(regla(dev, 0, 200, [eth_type("0x0800"), ipv4_dst(f"10.{b + n}.0.0/16")],
                               [push_vlan(), set_vlan(100 + n), output(puerto)]))
    # Servidores: todos en el SW-C2
    for srv, d in TOPO["servidores"].items():
        for ip in d["ips"]:
            if nombre == "SW-C2":
                acc = [set_eth_dst(mac_de(srv, d)), output(d["puerto_sw_c2"])]
                if espejo and srv != "IDS-Suricata":
                    acc.append(output(TOPO["espejos"]["SW-C2"]))       # copia hacia el IDS (E11)
            else:
                acc = [output(c["hacia_SW-C2"])]
            R.append(regla(dev, 0, 200, [eth_type("0x0800"), ipv4_dst(f"{ip}/32")], acc))
    # Plano de control: host Docker y las 3 instancias
    for nodo, d in TOPO["control"].items():
        if d["switch"] == nombre:
            acc = [set_eth_dst(mac_de(nodo, d)), output(d["puerto"])]
        else:
            acc = [output(c.get("hacia_SW-C1", c.get("hacia_SW-C2")))]
        R.append(regla(dev, 0, 200, [eth_type("0x0800"), ipv4_dst(f"{d['ip']}/32")], acc))
    # Resto: hacia la salida (SW-C1: Internet, con copia del edge al IDS por E17)
    acc = [output(c["salida"])]
    if espejo and nombre == "SW-C1":
        acc.append(output(TOPO["espejos"]["SW-C1"]))
    R.append(regla(dev, 0, 190, [eth_type("0x0800")], acc))
    R.append(regla(dev, 0, 0, [], []))
    return R


def reglas_acceso(dev, m, permisos):
    R = []
    # Tabla 0 (admisión, R1)
    R.append(regla(dev, 0, 900, [eth_type("0x888e")], [output(CONTROLLER)]))
    for p in HOST_PORTS:
        R.append(regla(dev, 0, 800, [in_port(p), eth_type("0x0806")],
                       ([meter(m["arp"][p])] if m["arp"][p] else []) + [output(CONTROLLER)]))
    R.append(regla(dev, 0, 600, [in_port(UPLINK)], [table(2)]))
    R.append(regla(dev, 0, 10, [], []))
    # Tabla 1 (autorización, R2)
    for rol, octeto, ip, proto, puerto in permisos:
        R.append(regla(dev, 1, 100, [eth_type("0x0800"), ipv4_src(f"10.{octeto}.0.0/12"),
                                     ipv4_dst(f"{ip}/32")] + l4_dst(proto, puerto), [table(2)]))
    R.append(regla(dev, 1, 60, [eth_type("0x0800"), ipv4_dst(f"{ENV['IP_DNS']}/32"), ip_proto(17), udp_dst(53)],
                   [table(2)]))
    for red in ("10.0.0.0/8", "172.16.0.0/16"):
        R.append(regla(dev, 1, 58, [vlan(999), eth_type("0x0800"), ipv4_dst(red)], []))
    R.append(regla(dev, 1, 57, [vlan(999), eth_type("0x0800")],
                   ([meter(m["cuarentena"])] if m["cuarentena"] else []) + [table(2)]))
    for red in ("10.0.0.0/8", "172.16.0.0/16"):
        R.append(regla(dev, 1, 55, [eth_type("0x0800"), ipv4_dst(red)],
                       ([meter(m["captura"])] if m["captura"] else []) + [output(CONTROLLER)]))
    R.append(regla(dev, 1, 50, [eth_type("0x0800")], [table(2)]))
    R.append(regla(dev, 1, 0, [], []))
    # Tabla 2 (entrega, M8): solo lo permanente; 200/190 por host los instala M1
    R.append(regla(dev, 2, 200, [vlan_presente(), eth_type("0x0800"), ipv4_dst("10.200.0.0/16")],
                   [pop_vlan(), output(UPLINK)]))
    R.append(regla(dev, 2, 180, [vlan_presente(), eth_type("0x0800")], [pop_vlan(), output(UPLINK)]))
    return R


def leer_permisos(dsn):
    import psycopg2
    try:
        cx = psycopg2.connect(dsn, connect_timeout=5)
    except psycopg2.OperationalError as e:
        sys.exit(f"El Controlador (172.16.0.10) no alcanza PostgreSQL ({ENV['IP_PG']}) por el fabric: {e}\n"
                 "Revisa en ONOS:  flows -s any of:00000000000000c1   y   of:00000000000000c2")
    with cx, cx.cursor() as cur:
        cur.execute("SELECT rol, octeto, host(ip), protocolo, puerto FROM v_permiso_efectivo ORDER BY rol, recurso_id")
        return cur.fetchall()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=f"host={ENV['IP_PG']} dbname={ENV['PG_DB']} user=onos_m2 password=m2-demo "
                                     "sslmode=require")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--borrar", action="store_true")
    ap.add_argument("--sin-espejo", action="store_true", help="sin copias hacia el IDS")
    ap.add_argument("--sin-meters", action="store_true", help="no crear meters (si la API falla en tu OVS)")
    a = ap.parse_args()

    if a.borrar:
        borrar_app(APP)
        for n in ACCESO.values():
            borrar_meters(dpid(n))
        print("Reglas y meters del régimen base retirados.")
        return

    # 0. Que el núcleo de ONOS no intercepte ARP con su regla 40000 (lo hace la regla 800 con meter)
    if not a.dry_run:
        configurar("org.onosproject.provider.host.impl.HostLocationProvider",
                   {"requestInterceptsEnabled": "false"})

    # 1. Los cores primero: sin ellos, ONOS no alcanza PostgreSQL, FreeRADIUS ni Kea
    for core in ("SW-C2", "SW-C1"):
        R = reglas_core(core, espejo=not a.sin_espejo)
        print(f"{core} ({dpid(core)}): {post_flows(R, app=APP, dry=a.dry_run)} reglas")
    if a.dry_run:
        permisos = [("ALUMNO", 16, "10.200.1.10", "TCP", 443)] * 9     # inventario del piloto (9 reglas 100)
    else:
        time.sleep(2)
        permisos = leer_permisos(a.dsn)

    # 2. Acceso: meters y Tablas 0, 1 y 2
    for pab, nombre in sorted(ACCESO.items()):
        dev = dpid(nombre)
        if a.sin_meters:
            m = {"arp": {p: None for p in HOST_PORTS}, "captura": None, "cuarentena": None}
        else:
            m = {"arp": {p: crear_meter(dev, "PKTS_PER_SEC", 10, 10, a.dry_run) for p in HOST_PORTS},
                 "captura": crear_meter(dev, "PKTS_PER_SEC", 50, 20, a.dry_run),
                 "cuarentena": crear_meter(dev, "KB_PER_SEC", 1000, 1000, a.dry_run)}
        R = reglas_acceso(dev, m, permisos)
        cuenta = collections.Counter(r["tableId"] for r in R)
        n = post_flows(R, app=APP, dry=a.dry_run)
        print(f"{nombre} ({dev}): {n} reglas  [Tabla 0: {cuenta[0]}, Tabla 1: {cuenta[1]}, Tabla 2: {cuenta[2]}]"
              f"  meters: {0 if a.sin_meters else len(HOST_PORTS) + 2}")
    print("Listo. En la consola de ONOS:  flows -s any of:0000000000000001 | wc -l")


if __name__ == "__main__":
    main()
