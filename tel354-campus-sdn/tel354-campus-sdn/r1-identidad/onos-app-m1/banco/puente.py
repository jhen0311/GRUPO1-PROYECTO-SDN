#!/usr/bin/env python3
"""Emulador del switch de acceso as1 para el banco de M1: hace lo que las reglas 900 (EAPOL),
800 (ARP) y 525 (DHCP) envían al controlador y ejecuta los Packet-Out del núcleo de M1."""
import select, socket, struct, sys
PUERTOS = {2: "s1-p2", 3: "s1-p3"}
ETH_P_ALL = 3
socks = {}
for p, ifn in PUERTOS.items():
    s = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(ETH_P_ALL))
    s.bind((ifn, 0)); socks[s] = p
porp = {p: s for s, p in socks.items()}
ctl = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); ctl.bind(("127.0.0.1", 9901))
destino = ("127.0.0.1", 9900)
def al_controlador(t):
    tipo = struct.unpack("!H", t[12:14])[0]
    if tipo in (0x888e, 0x0806):
        return True
    if tipo == 0x0800 and t[23] == 17:
        ihl = (t[14] & 0xf) * 4
        return struct.unpack("!H", t[14 + ihl + 2:14 + ihl + 4])[0] == 67
    return False
print("puente listo", flush=True)
while True:
    r, _, _ = select.select(list(socks) + [ctl], [], [])
    for s in r:
        if s is ctl:
            d, _ = ctl.recvfrom(9000)
            porp[d[0]].send(d[1:])
            continue
        t, addr = s.recvfrom(9000)
        if addr[2] == socket.PACKET_OUTGOING:
            continue
        if len(t) >= 14 and al_controlador(t):
            ctl.sendto(bytes([socks[s]]) + t, destino)
