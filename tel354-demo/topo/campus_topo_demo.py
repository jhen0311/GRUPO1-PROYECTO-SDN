#!/usr/bin/env python3
"""
campus_topo_demo.py - Topología de la demo de R1 y R2 (TEL354, Grupo 01)

Actualiza campus_topo.py al plan de direccionamiento vigente (AVZ07, sección 2):
  - rol en los 4 bits altos del 2.º octeto (b = 16·k) y pabellón en los 4 bajos (b + N)
  - ctl = ONOS en 172.16.0.10 (namespace raíz), puerto C del core
  - svc = segmento de servicios (puerto T): FreeRADIUS .10, OpenLDAP .11, PostgreSQL .12,
          Kea .13, DNS .14, gestión .5 y los servidores de recursos 10.200.1-3.x
  - rutas y ARP estáticos hacia el gateway virtual 02:00:00:00:00:c8 (AVZ07, sección 5.5)

Numeración de puertos (igual que el manual de despliegue):
  core: 1 edge, 2 svc (T), 3 ctl (C), 4 as1, 5 as2      asN: 1 uplink, 2 y 3 hosts

Usuarios de la demo (tres con permisos y un host sin supplicant):
  h1 (as1, puerto 2) alumno.demo   h2 (as1, puerto 3) docente.demo
  h3 (as2, puerto 2) ti.demo       h4 (as2, puerto 3) sin supplicant (invitado / atacante)

Uso (como root, sudo -i, desde la carpeta del kit):
  python3 topo/campus_topo_demo.py --services            # hosts sin IP (esperan 802.1X + DHCP)
  python3 topo/campus_topo_demo.py --services --manual   # IP fijas del pool de su rol, sin M1
"""
import argparse, os
from mininet.cli import CLI
from mininet.link import TCLink
from mininet.log import info, setLogLevel
from mininet.net import Mininet
from mininet.node import OVSSwitch, RemoteController

KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GW_MAC = "02:00:00:00:00:c8"                       # MAC virtual de los gateways del core
SVC_MAC = "00:00:00:c8:00:0a"
SVC_IPS = ["10.200.0.5", "10.200.0.11", "10.200.0.12", "10.200.0.13", "10.200.0.14",
           "10.200.1.10", "10.200.1.20", "10.200.2.10", "10.200.2.20",
           "10.200.3.10", "10.200.3.20"]
K = {"ALUMNO": 1, "JP": 2, "DOCENTE": 3, "ADMINISTRATIVO": 4, "TI": 5, "INVITADO": 6}

# host: (switch, pabellón, MAC, usuario, rol de la demo)
HOSTS = {
    "h1": ("as1", 1, "00:00:00:10:01:01", "alumno.demo",  "ALUMNO"),
    "h2": ("as1", 1, "00:00:00:30:01:02", "docente.demo", "DOCENTE"),
    "h3": ("as2", 2, "00:00:00:50:02:01", "ti.demo",      "TI"),
    "h4": ("as2", 2, "00:00:00:60:02:02", None,           "INVITADO"),
}

def dpid(n):
    return "%016x" % n

def ip_manual(nombre):
    """IP fija dentro del pool (rol, pabellón) para el modo --manual (sin M1)."""
    _, n, _, _, rol = HOSTS[nombre]
    b = 16 * K[rol]
    return f"10.{b + n}.0.{10 + int(nombre[1:])}", f"10.{b}.0.1", "02:00:00:00:00:%02x" % b

def build(args):
    net = Mininet(controller=None, switch=OVSSwitch, link=TCLink, autoSetMacs=False, build=False)
    net.addController("onos", controller=RemoteController, ip=args.controller, port=6653)
    of = dict(protocols="OpenFlow13", failMode="secure")
    core = net.addSwitch("core", dpid=dpid(0x100), **of)
    edge = net.addSwitch("edge", dpid=dpid(0x200), **of)
    net.addLink(edge, core)                                         # core:1
    svc = net.addHost("svc", ip="10.200.0.10/16", mac=SVC_MAC)
    net.addLink(svc, core)                                          # core:2 (T)
    ctl = net.addHost("ctl", inNamespace=False, ip="172.16.0.10/16")
    net.addLink(ctl, core)                                          # core:3 (C)
    sws = {}
    for p in (1, 2):
        sws[p] = net.addSwitch("as%d" % p, dpid=dpid(p), **of)
        net.addLink(sws[p], core)                                   # core:4 y core:5; asN:1
    for nombre, (sw, p, mac, _, _) in HOSTS.items():
        h = net.addHost(nombre, ip=None, mac=mac)
        net.addLink(h, sws[p])                                      # asN:2 y asN:3
    return net

def post_start(net, args):
    # Techo de Packet-In (AVZ07, sección 5.6): 200 pps en acceso, 100 pps en el core
    for sw, pps in (("as1", 200), ("as2", 200), ("core", 100)):
        net.get(sw).cmd(f"ovs-vsctl set controller {sw} controller_rate_limit={pps} controller_burst_limit=25")
    for nombre in HOSTS:
        h = net.get(nombre)
        h.cmd("sysctl -qw net.ipv6.conf.all.disable_ipv6=1")
        h.cmd("ip addr flush dev %s" % h.defaultIntf())
        if args.manual:
            ip, gw, gwmac = ip_manual(nombre)
            h.cmd(f"ip addr add {ip}/12 dev {h.defaultIntf()}")
            h.cmd(f"ip route add default via {gw}")
            h.cmd(f"arp -s {gw} {gwmac}")                           # lo haría el proxy ARP de M8

    svc = net.get("svc")
    i = svc.defaultIntf()
    svc.cmd("sysctl -qw net.ipv6.conf.all.disable_ipv6=1")
    for ip in SVC_IPS:
        svc.cmd(f"ip addr add {ip}/16 dev {i}")
    svc.cmd(f"ip route add 172.16.0.0/16 via 10.200.0.1 dev {i}")
    svc.cmd(f"ip route add 10.0.0.0/9 via 10.200.0.1 dev {i}")
    svc.cmd(f"arp -s 10.200.0.1 {GW_MAC}")

    ctl = net.get("ctl")
    c = ctl.defaultIntf()
    ctl.cmd(f"ip route replace 10.200.0.0/16 via 172.16.0.1 dev {c}")
    ctl.cmd(f"arp -s 172.16.0.1 {GW_MAC}")

    info("\n*** Plan de la demo\n")
    info("%-4s %-4s %-7s %-17s %-14s %-10s %s\n" % ("host", "sw", "puerto", "MAC", "usuario", "rol", "IP"))
    for nombre, (sw, p, mac, usuario, rol) in HOSTS.items():
        ip = ip_manual(nombre)[0] if args.manual else "DHCP tras 802.1X"
        info("%-4s %-4s %-7d %-17s %-14s %-10s %s\n" % (nombre, sw, 2 + (int(nombre[1:]) + 1) % 2,
                                                        mac, usuario or "-", rol, ip))
    info("svc  core 2       %s  servicios        10.200.0.0/16 (%d alias)\n" % (SVC_MAC, len(SVC_IPS)))
    info("ctl  core 3       %-17s ONOS             172.16.0.10 (namespace raíz)\n\n" % c.MAC())

    if args.services:
        info("*** Servicios en svc\n")
        info(svc.cmd(f"cd {KIT} && bash resources/start_services.sh"))

def main():
    ap = argparse.ArgumentParser(description="Topología de la demo de R1 y R2 (TEL354 G01)")
    ap.add_argument("--controller", default="127.0.0.1", help="IP de ONOS para OpenFlow")
    ap.add_argument("--services", action="store_true", help="arrancar los servicios en svc")
    ap.add_argument("--manual", action="store_true",
                    help="IP fijas del pool de cada rol (modo sin M1, con sesion_manual.py)")
    args = ap.parse_args()
    setLogLevel("info")
    net = build(args)
    net.build()
    net.start()
    post_start(net, args)
    CLI(net)
    net.stop()

if __name__ == "__main__":
    main()
