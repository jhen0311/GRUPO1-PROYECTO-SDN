#!/usr/bin/env python3
"""
Genera kea-dhcp4.conf a partir del plan de direccionamiento del AVZ07 (sección 4).
- shared-network con un /12 por rol (b = 16·k) y un pool /16 por pabellón (b + N)
- client-classes por la opción 82 que inserta M1: sub-opción 2 (Remote-ID) = k,
  sub-opción 1 (Circuit-ID) = N, ambas de 1 byte
- leases en PostgreSQL y hook lease_cmds (lease4-del para el paso a cuarentena)
Uso: python3 gen_kea_conf.py --iface svc-eth0 --pabellones 2 > kea-dhcp4.conf
"""
import argparse, json

ROLES = [  # nombre, k   (CUARENTENA = 7, b = 112)
    ("ALUMNO", 1), ("JP", 2), ("DOCENTE", 3), ("ADMINISTRATIVO", 4),
    ("TI", 5), ("INVITADO", 6), ("CUARENTENA", 7)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iface", default="svc-eth0", help="interfaz del host svc")
    ap.add_argument("--ip", default="10.200.0.13")
    ap.add_argument("--pabellones", type=int, default=2)
    ap.add_argument("--db-host", default="10.200.0.12")
    ap.add_argument("--db-pass", default="kea-demo")
    ap.add_argument("--relay", default="172.16.0.10", help="giaddr de M1")
    ap.add_argument("--hooks", default="/usr/lib/x86_64-linux-gnu/kea/hooks")
    a = ap.parse_args()

    classes, subnets = [], []
    for nombre, k in ROLES:
        b = 16 * k
        classes.append({"name": f"R_{nombre}", "test": f"relay4[2].hex == 0x{k:02x}"})
        pools = []
        for n in range(1, a.pabellones + 1):
            cls = f"R_{nombre}_P{n:02d}"
            classes.append({"name": cls,
                            "test": f"relay4[2].hex == 0x{k:02x} and relay4[1].hex == 0x{n:02x}"})
            pools.append({"pool": f"10.{b+n}.0.10 - 10.{b+n}.255.250", "client-class": cls})
        subnets.append({
            "id": k, "subnet": f"10.{b}.0.0/12", "client-class": f"R_{nombre}",
            "pools": pools,
            "option-data": [{"name": "routers", "data": f"10.{b}.0.1"}]})

    conf = {"Dhcp4": {
        "interfaces-config": {"interfaces": [f"{a.iface}/{a.ip}"], "dhcp-socket-type": "udp"},
        "authoritative": True,
        "valid-lifetime": 3600, "renew-timer": 1800, "rebind-timer": 3150,
        "lease-database": {"type": "postgresql", "name": "kea", "user": "kea",
                           "password": a.db_pass, "host": a.db_host},
        "control-socket": {"socket-type": "unix", "socket-name": "/var/run/kea/kea4-ctrl-socket"},
        "hooks-libraries": [{"library": f"{a.hooks}/libdhcp_lease_cmds.so"}],
        # Server Identifier = dirección del relay (equivale a la sub-opción 11 del RFC 5107):
        # las renovaciones unicast del host van a M1, que las vuelve a relayar (regla 525).
        "option-data": [{"name": "dhcp-server-identifier", "data": a.relay},
                        {"name": "domain-name-servers", "data": "10.200.0.14"},
                        {"name": "domain-name", "data": "campus.pucp.edu.pe"}],
        "client-classes": classes,
        "shared-networks": [{"name": "campus",
                             # todas las solicitudes llegan del relay de M1 (giaddr = 172.16.0.10)
                             "relay": {"ip-addresses": [a.relay]},
                             "subnet4": subnets}],
        "loggers": [{"name": "kea-dhcp4", "severity": "INFO",
                     "output_options": [{"output": "/var/log/kea/kea-dhcp4.log"}]}]}}
    print(json.dumps(conf, indent=2, ensure_ascii=False))

if __name__ == "__main__":
    main()
