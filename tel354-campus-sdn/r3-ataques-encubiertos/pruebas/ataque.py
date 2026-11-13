#!/usr/bin/env python3
"""
ataque.py - Escenarios de prueba para validar R3 (M2 -> M4a -> M5) con Scapy.

Herramienta de laboratorio para el slice de pruebas del Grupo 01. Cada escenario genera
tráfico a un ritmo fijo desde un host autenticado (Kali) hacia los recursos del propio
slice, y deja un registro CSV con la hora de cada envío, para cruzarlo con el log de ONOS
y medir el tiempo de detección y de contención. Es el estímulo que necesitan las ventanas
de M4a; no explota ninguna vulnerabilidad.

  puertos      SYN a varios puertos de un destino            -> ESCANEO_PUERTOS (P1 en W1, P2 en W2)
  red          SYN a un puerto de varios destinos            -> ESCANEO_RED (D1 en W1, D2 en W2)
  reiterado    el mismo destino denegado, espaciado > 8 s    -> ACCESO_REITERADO (N2 en W2)
  ip-falsa     SYN con IP de origen ajena                    -> SUPLANTACION (BindingViolation IP)
  arp-falso    ARP que reclama una IP ajena (p. ej., el gw)  -> SUPLANTACION inequívoca

Umbrales por defecto (r3-netcfg.json): W1 = 10 s, W2 = 600 s, P1 = 10, D1 = 8, P2 = 30,
D2 = 24, N2 = 30, F2 = 3. Dos límites del modelo a tener en cuenta:
  - M2 instala un DROP 150 de 8 s por cuádrupla: repetir la misma cuádrupla antes de 8 s no
    llega al controlador (por eso 'reiterado' espacia los intentos 9 s).
  - La captura 55 tiene un meter de 50 pps por switch: a más ritmo se pierden paquetes.

Uso (root en el host, con la sesión ACTIVA):
  python3 ataque.py puertos --destino 10.200.1.30 --puertos 20-35 --tasa 2
  python3 ataque.py red --destinos 10.200.1.31-45 --puerto 22 --tasa 2
  python3 ataque.py reiterado --destino 10.200.1.30 --puerto 5432 --intentos 30 --intervalo 9
  python3 ataque.py ip-falsa --destino 10.200.1.10 --puerto 443 --ip-falsa 10.17.0.77
  python3 ataque.py arp-falso --ip-falsa 10.16.0.1
  (--simular: arma los paquetes y el registro sin enviarlos; no requiere root)
"""
import argparse, csv, datetime, ipaddress, os, sys, time


def rango_puertos(txt):
    r = []
    for parte in txt.split(","):
        if "-" in parte:
            a, b = parte.split("-")
            r += range(int(a), int(b) + 1)
        else:
            r.append(int(parte))
    return r


def rango_ips(txt):
    """10.200.1.31-45, 10.200.1.0/28 o lista separada por comas."""
    r = []
    for parte in txt.split(","):
        if "/" in parte:
            r += [str(h) for h in ipaddress.ip_network(parte, strict=False).hosts()]
        elif "-" in parte.split(".")[-1]:
            base, ult = parte.rsplit(".", 1)
            a, b = ult.split("-")
            r += [f"{base}.{n}" for n in range(int(a), int(b) + 1)]
        else:
            r.append(parte)
    return r


def ahora():
    return datetime.datetime.now().isoformat(timespec="milliseconds")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="esc", required=True)
    p = sub.add_parser("puertos"); p.add_argument("--destino", required=True)
    p.add_argument("--puertos", default="20-35"); p.add_argument("--tasa", type=float, default=2)
    p = sub.add_parser("red"); p.add_argument("--destinos", required=True)
    p.add_argument("--puerto", type=int, default=22); p.add_argument("--tasa", type=float, default=2)
    p = sub.add_parser("reiterado"); p.add_argument("--destino", required=True)
    p.add_argument("--puerto", type=int, required=True); p.add_argument("--intentos", type=int, default=30)
    p.add_argument("--intervalo", type=float, default=9)
    p = sub.add_parser("ip-falsa"); p.add_argument("--destino", required=True)
    p.add_argument("--puerto", type=int, default=443); p.add_argument("--ip-falsa", required=True)
    p.add_argument("--cuenta", type=int, default=3)
    p = sub.add_parser("arp-falso"); p.add_argument("--ip-falsa", required=True)
    p.add_argument("--cuenta", type=int, default=3)
    for s in sub.choices.values():
        s.add_argument("--iface", default=None, help="interfaz de datos (por defecto, la de la ruta)")
        s.add_argument("--registro", default=None, help="CSV de salida (por defecto ataque_<esc>_<hora>.csv)")
        s.add_argument("--simular", action="store_true")
    a = ap.parse_args()

    from scapy.all import ARP, Ether, IP, TCP, conf, get_if_hwaddr, send, sendp   # noqa: E402
    conf.verb = 0
    if not a.simular and os.geteuid() != 0:
        sys.exit("Scapy necesita root para enviar (o usar --simular)")

    # (paquete, función de envío, destino, protocolo, puerto); intervalo entre envíos
    plan, intervalo = [], 0.0
    if a.esc == "puertos":
        plan = [(IP(dst=a.destino) / TCP(dport=n, flags="S"), send, a.destino, 6, n) for n in rango_puertos(a.puertos)]
        intervalo = 1 / a.tasa
    elif a.esc == "red":
        plan = [(IP(dst=d) / TCP(dport=a.puerto, flags="S"), send, d, 6, a.puerto) for d in rango_ips(a.destinos)]
        intervalo = 1 / a.tasa
    elif a.esc == "reiterado":
        plan = [(IP(dst=a.destino) / TCP(dport=a.puerto, flags="S"), send, a.destino, 6, a.puerto)] * a.intentos
        intervalo = a.intervalo
    elif a.esc == "ip-falsa":
        plan = [(IP(src=a.ip_falsa, dst=a.destino) / TCP(dport=a.puerto, flags="S"), send, a.destino, 6, a.puerto)] * a.cuenta
        intervalo = 1
    elif a.esc == "arp-falso":
        iface = a.iface or conf.iface
        mac = "02:00:00:00:00:01" if a.simular else get_if_hwaddr(iface)
        pkt = Ether(src=mac, dst="ff:ff:ff:ff:ff:ff") / ARP(op=2, hwsrc=mac, psrc=a.ip_falsa,
                                                            hwdst="ff:ff:ff:ff:ff:ff", pdst=a.ip_falsa)
        plan = [(pkt, sendp, a.ip_falsa, 0, -1)] * a.cuenta
        intervalo = 1

    nombre = a.registro or f"ataque_{a.esc}_{datetime.datetime.now():%Y%m%d_%H%M%S}.csv"
    dur = intervalo * (len(plan) - 1)
    print(f"{a.esc}: {len(plan)} paquetes, uno cada {intervalo:g} s (aprox. {dur:g} s); registro {nombre}")
    print(f"inicio {ahora()}", flush=True)
    t0 = time.monotonic()
    with open(nombre, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["n", "hora", "t_rel_s", "escenario", "destino", "proto", "puerto"])
        for i, (pkt, envio, d, proto, puerto) in enumerate(plan):
            espera = t0 + i * intervalo - time.monotonic()       # ritmo fijo, sin deriva acumulada
            if espera > 0:
                time.sleep(espera)
            if not a.simular:
                envio(pkt, iface=a.iface) if a.iface else envio(pkt)
            w.writerow([i + 1, ahora(), f"{time.monotonic() - t0:.3f}", a.esc, d, proto, puerto])
    print(f"fin    {ahora()} ({time.monotonic() - t0:.1f} s)")


if __name__ == "__main__":
    main()
