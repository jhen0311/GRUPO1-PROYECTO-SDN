#!/usr/bin/env python3
"""
onos_static.py - Régimen proactivo de R1 y R2 por la API REST de ONOS (AVZ07, sección 5).

Instala lo que no depende de las sesiones:
  meters    arp_P (10 pps por puerto de host), captura (50 pps), cuarentena y contencion (1 Mbps)
  acceso    Tabla 0: 900, 800 (por puerto), 600, 10
            Tabla 1: 100 (una por fila de v_permiso_efectivo: recursos sin curso), 60, 58 x2, 57, 55 x2, 50, 0
            (el acceso a servidores de cursos es por sesión: regla 110, ver sesion_manual.py)
            Tabla 2: 200 (servicios), 180
  core      200 por servidor, 205 por pabellón, 200 de retorno por rol y pabellón,
            200 hacia 172.16.0.0/16, 190 hacia el Edge, 0
En la arquitectura estas reglas las solicitan M2, M7 y M8 dentro de ONOS; para la demo
se cargan por REST, igual que en el laboratorio de Floodlight.

Uso (en el namespace raíz de la VM, con la topología y los servicios arriba):
  python3 r2-autorizacion/proactivo/onos_static.py            # instala
  python3 r2-autorizacion/proactivo/onos_static.py --dry-run  # solo cuenta las reglas
  python3 r2-autorizacion/proactivo/onos_static.py --borrar   # retira reglas y meters de la demo
"""
import argparse, collections, os, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "comun", "lib"))
from onos_rest import *

def mac_onos():
    try:
        return open("/sys/class/net/ctl-eth0/address").read().strip()
    except OSError:
        sys.exit("No existe ctl-eth0: levanta primero comun/topo/campus_topo_demo.py")

def leer_bd(dsn):
    import psycopg2
    try:
        cx = psycopg2.connect(dsn, connect_timeout=5)
    except psycopg2.OperationalError as e:
        sys.exit(f"ONOS (172.16.0.10) no alcanza PostgreSQL (10.200.0.12) por el fabric: {e}\n"
                 "Revisa en ONOS:  flows -s any of:0000000000000100   (las reglas deben estar ADDED)")
    with cx, cx.cursor() as cur:
        cur.execute("SELECT rol, octeto, host(ip), protocolo, puerto FROM v_permiso_efectivo ORDER BY rol, recurso_id")
        permisos = cur.fetchall()
        cur.execute("SELECT DISTINCT host(ip) FROM recurso")
        servidores = sorted(set(r[0] for r in cur.fetchall()) | set(SERVICIOS))
    return permisos, servidores

def reglas_acceso(pab, dev, m, permisos):
    R = []
    # Tabla 0 (admisión, R1)
    R.append(regla(dev, 0, 900, [eth_type("0x888e")], [output(CONTROLLER)]))
    for p in HOST_PORTS:
        R.append(regla(dev, 0, 800, [in_port(p), eth_type("0x0806")], ([meter(m["arp"][p])] if m["arp"][p] else []) + [output(CONTROLLER)]))
    R.append(regla(dev, 0, 600, [in_port(UPLINK)], [table(2)]))
    R.append(regla(dev, 0, 10, [], []))
    # Tabla 1 (autorización, R2)
    for rol, octeto, ip, proto, puerto in permisos:
        R.append(regla(dev, 1, 100, [eth_type("0x0800"), ipv4_src(f"10.{octeto}.0.0/12"),
                                     ipv4_dst(f"{ip}/32")] + l4_dst(proto, puerto), [table(2)]))
    R.append(regla(dev, 1, 60, [eth_type("0x0800"), ipv4_dst("10.200.0.14/32"), ip_proto(17), udp_dst(53)], [table(2)]))
    for red in ("10.0.0.0/8", "172.16.0.0/16"):
        R.append(regla(dev, 1, 58, [vlan(999), eth_type("0x0800"), ipv4_dst(red)], []))
    R.append(regla(dev, 1, 57, [vlan(999), eth_type("0x0800")], ([meter(m["cuarentena"])] if m["cuarentena"] else []) + [table(2)]))
    for red in ("10.0.0.0/8", "172.16.0.0/16"):
        R.append(regla(dev, 1, 55, [eth_type("0x0800"), ipv4_dst(red)], ([meter(m["captura"])] if m["captura"] else []) + [output(CONTROLLER)]))
    R.append(regla(dev, 1, 50, [eth_type("0x0800")], [table(2)]))
    R.append(regla(dev, 1, 0, [], []))
    # Tabla 2 (entrega, M8): solo lo permanente; 200/190 por host los instala M1 (o sesion_manual.py)
    # OVS (OpenFlow 1.3) solo acepta pop_vlan si la regla exige VLAN: se usa "VLAN presente"
    R.append(regla(dev, 2, 200, [vlan_presente(), eth_type("0x0800"), ipv4_dst("10.200.0.0/16")], [pop_vlan(), output(UPLINK)]))
    R.append(regla(dev, 2, 180, [vlan_presente(), eth_type("0x0800")], [pop_vlan(), output(UPLINK)]))
    return R

def reglas_core(servidores, mac_ctl):
    R = []
    for ip in servidores:
        R.append(regla(CORE, 0, 200, [eth_type("0x0800"), ipv4_dst(f"{ip}/32")],
                       [set_eth_dst(SVC_MAC), output(CORE_PORT["T"])]))
    for n in ACCESS:
        R.append(regla(CORE, 0, 205, [eth_type("0x0800"), ipv4_dst(f"10.{112+n}.0.0/16")],
                       [push_vlan(), set_vlan(999), output(CORE_PORT[n])]))
        for rol, b in ROLES.items():
            if rol == "CUARENTENA":
                continue
            R.append(regla(CORE, 0, 200, [eth_type("0x0800"), ipv4_dst(f"10.{b+n}.0.0/16")],
                           [push_vlan(), set_vlan(100 + n), output(CORE_PORT[n])]))
    R.append(regla(CORE, 0, 200, [eth_type("0x0800"), ipv4_dst("172.16.0.0/16")],
                   [set_eth_dst(mac_ctl), output(CORE_PORT["C"])]))
    R.append(regla(CORE, 0, 190, [eth_type("0x0800")], [output(CORE_PORT["edge"])]))
    R.append(regla(CORE, 0, 0, [], []))
    return R

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default="host=10.200.0.12 dbname=campus user=onos_m2 password=m2-demo")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--borrar", action="store_true")
    ap.add_argument("--sin-meters", action="store_true",
                    help="no crear meters (si la API de meters falla en tu versión de ONOS/OVS)")
    a = ap.parse_args()

    if a.borrar:
        borrar_app()
        for dev in ACCESS.values():
            borrar_meters(dev)
        print("Reglas y meters de la demo retirados.")
        return

    # 0. Que el núcleo de ONOS no intercepte ARP con su regla 40000 (lo hace la regla 800 con meter)
    if not a.dry_run:
        configurar("org.onosproject.provider.host.impl.HostLocationProvider",
                   {"requestInterceptsEnabled": "false"})

    # 1. El core primero: sin él, ONOS (172.16.0.10) no alcanza PostgreSQL (10.200.0.12)
    mac_ctl = "00:00:00:00:00:00" if a.dry_run else mac_onos()
    servidores = SERVICIOS + ["10.200.1.10", "10.200.1.20", "10.200.2.10", "10.200.2.20", "10.200.3.10", "10.200.3.20"]
    permisos = [("ALUMNO", 16, "10.200.1.10", "TCP", 443)] * 13   # en seco: inventario de la demo (13 reglas 100)
    core = reglas_core(servidores, mac_ctl)
    print(f"core: {post_flows(core, dry=a.dry_run)} reglas")
    if not a.dry_run:
        time.sleep(2)
        permisos, servidores_bd = leer_bd(a.dsn)
        extra = sorted(set(servidores_bd) - set(servidores))
        if extra:
            print(f"core: {post_flows(reglas_core(extra, mac_ctl)[:len(extra)])} reglas más (servidores nuevos)")

    # 2. Acceso: meters y Tablas 0, 1 y 2
    for pab, dev in ACCESS.items():
        if a.sin_meters:
            m = {"arp": {p: None for p in HOST_PORTS}, "captura": None, "cuarentena": None, "contencion": None}
        else:
            m = {"arp": {p: crear_meter(dev, "PKTS_PER_SEC", 10, 10, a.dry_run) for p in HOST_PORTS},
                 "captura": crear_meter(dev, "PKTS_PER_SEC", 50, 20, a.dry_run),
                 "cuarentena": crear_meter(dev, "KB_PER_SEC", 1000, None, a.dry_run),
                 "contencion": crear_meter(dev, "KB_PER_SEC", 1000, None, a.dry_run)}
        R = reglas_acceso(pab, dev, m, permisos)
        cuenta = collections.Counter(r["tableId"] for r in R)
        n = post_flows(R, dry=a.dry_run)
        print(f"as{pab} ({dev}): {n} reglas  [Tabla 0: {cuenta[0]}, Tabla 1: {cuenta[1]}, Tabla 2: {cuenta[2]}]"
              f"  meters: {0 if a.sin_meters else len(HOST_PORTS) + 3}")
    print("Listo. Comprueba con: python3 r2-autorizacion/tests/contar_flows.py")

if __name__ == "__main__":
    main()
