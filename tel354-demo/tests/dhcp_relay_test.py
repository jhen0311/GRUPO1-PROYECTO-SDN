#!/usr/bin/env python3
"""
Prueba del servicio DHCP tal como lo usa M1 (AVZ07, secciones 4 y 6.4).

Se comporta como el relay de M1: envía DISCOVER y REQUEST con giaddr = 172.16.0.10
y la opción 82 (sub-opción 1 = pabellón N, sub-opción 2 = código de rol k,
sub-opción 11 = server identifier override) y mide la latencia del intercambio.
Verifica que la IP concedida pertenezca al pool 10.(16k + N).0.0/16.

Uso (en la topología: desde el namespace raíz, que tiene 172.16.0.10):
  python3 tests/dhcp_relay_test.py --casos 1:1,3:2,5:1 --repeticiones 20
  python3 tests/dhcp_relay_test.py --rol-invalido      # k = 9: Kea no debe ofrecer
"""
import argparse, ipaddress, os, random, socket, statistics, struct, time

RELAY_IP, KEA_IP = "172.16.0.10", "10.200.0.13"
MAGIC = b"\x63\x82\x53\x63"

def opcion(code, data):
    return bytes([code, len(data)]) + data

def paquete(tipo, xid, mac, k, n, pedida=None, servidor=None):
    hdr = struct.pack("!BBBBIHH4s4s4s4s16s64s128s", 1, 1, 6, 1, xid, 0, 0x8000,
                      b"\0" * 4, b"\0" * 4, b"\0" * 4, socket.inet_aton(RELAY_IP),
                      mac + b"\0" * 10, b"\0" * 64, b"\0" * 128)
    opts = opcion(53, bytes([tipo]))
    opts += opcion(61, b"\x01" + mac)
    opts += opcion(12, b"equipo-demo")                      # hostname -> dispositivo.hostname
    opts += opcion(60, b"tel354-demo")                      # vendor class -> dispositivo.sistema_op
    if pedida:
        opts += opcion(50, socket.inet_aton(pedida))
    if servidor:
        opts += opcion(54, socket.inet_aton(servidor))
    opts += opcion(55, bytes([1, 3, 6, 15, 51, 54]))
    sub = (opcion(1, bytes([n])) + opcion(2, bytes([k])) +
           opcion(11, socket.inet_aton(RELAY_IP)))            # RFC 5107
    opts += opcion(82, sub) + b"\xff"
    return hdr + MAGIC + opts

def leer_opciones(data):
    o, i = {}, 240
    while i < len(data) and data[i] != 255:
        if data[i] == 0:
            i += 1; continue
        c, l = data[i], data[i + 1]
        o[c] = data[i + 2:i + 2 + l]; i += 2 + l
    return o

def intercambio(sock, k, n, timeout):
    mac = bytes([0x02, 0x54, k, n]) + os.urandom(2)
    xid = random.getrandbits(32)
    t0 = time.perf_counter()
    sock.sendto(paquete(1, xid, mac, k, n), (KEA_IP, 67))
    offer = esperar(sock, xid, 2, timeout)
    if not offer:
        return None
    ip = socket.inet_ntoa(offer[16:20])
    srv = socket.inet_ntoa(leer_opciones(offer)[54])
    sock.sendto(paquete(3, xid, mac, k, n, pedida=ip, servidor=srv), (KEA_IP, 67))
    ack = esperar(sock, xid, 5, timeout)
    t1 = time.perf_counter()
    if not ack:
        return None
    o = leer_opciones(ack)
    return {"ip": ip, "server_id": srv, "router": socket.inet_ntoa(o[3]),
            "lease": struct.unpack("!I", o[51])[0], "ms": (t1 - t0) * 1000, "mac": mac.hex(":")}

def esperar(sock, xid, tipo, timeout):
    fin = time.time() + timeout
    while time.time() < fin:
        sock.settimeout(max(0.01, fin - time.time()))
        try:
            data, _ = sock.recvfrom(2048)
        except socket.timeout:
            return None
        if struct.unpack("!I", data[4:8])[0] == xid and leer_opciones(data).get(53) == bytes([tipo]):
            return data
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--casos", default="1:1,3:2,5:1", help="pares k:N (rol:pabellón)")
    ap.add_argument("--repeticiones", type=int, default=10)
    ap.add_argument("--rol-invalido", action="store_true")
    ap.add_argument("--timeout", type=float, default=2.0)
    a = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((RELAY_IP, 67))     # el relay recibe las respuestas en giaddr:67

    if a.rol_invalido:
        r = intercambio(sock, 9, 1, a.timeout)
        print("Rol inexistente (k=9):", "SIN OFERTA (correcto)" if r is None else f"OFERTA {r['ip']} (error)")
        return

    tiempos, errores = [], 0
    print(f"{'k:N':6} {'IP concedida':16} {'pool esperado':16} {'router':12} {'server-id':13} {'lease':6} {'ms':>6}")
    for caso in a.casos.split(","):
        k, n = map(int, caso.split(":"))
        red = ipaddress.ip_network(f"10.{16*k+n}.0.0/16")
        for _ in range(a.repeticiones):
            r = intercambio(sock, k, n, a.timeout)
            ok = r is not None and ipaddress.ip_address(r["ip"]) in red
            if not ok:
                errores += 1
                print(f"{caso:6} ERROR: {r}")
                continue
            tiempos.append(r["ms"])
        if r is None:
            continue
        print(f"{caso:6} {r['ip']:16} {str(red):16} {r['router']:12} {r['server_id']:13} {r['lease']:<6} {r['ms']:6.1f}")
    total = len(tiempos) + errores
    if tiempos:
        t = sorted(tiempos)
        p95 = t[max(0, int(round(0.95 * len(t))) - 1)]
        print(f"\nIntercambios: {total} | correctos: {len(tiempos)} ({100*len(tiempos)/total:.1f} %)")
        print(f"Latencia DISCOVER->ACK: media {statistics.mean(t):.1f} ms | mediana {statistics.median(t):.1f} ms"
              f" | p95 {p95:.1f} ms | máx {t[-1]:.1f} ms")

if __name__ == "__main__":
    main()
