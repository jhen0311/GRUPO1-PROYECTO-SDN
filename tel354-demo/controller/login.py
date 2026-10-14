#!/usr/bin/env python3
"""
login.py - Acceso de un usuario a la red: encadena R1 y R2 como lo hará M1 en ONOS.

  1. 802.1X  EAP-TTLS/PAP contra FreeRADIUS (10.200.0.10) con eapol_test, como NAS 172.16.0.10.
             Access-Accept trae el rol (VSA PUCP-Role-Code = k) y el Session-Timeout.
  2. DHCP    Relay con opción 82 (sub 1 = pabellón N, sub 2 = rol k) hacia Kea (10.200.0.13).
             Kea concede una IP del pool 10.(16k+N).0.0/16 y el gateway 10.(16k).0.1.
  3. Host    Aplica IP, ruta y ARP del gateway en el host de Mininet (lo que harían dhclient y M8).
  4. Reglas  Instala las reglas de la sesión en su switch de acceso: 525, 520, 505, 110 x c, 200, 190,
             bajo un appId propio de la sesión (tel354.sesion.hX), que es su llave de borrado.
  5. BD      Registra el dispositivo y la sesión (ACTIVA) en PostgreSQL.
Logout: borra en bloque las reglas de la sesión, cierra la sesión en la BD y quita la IP al host.

Diferencia con el diseño: la REST de ONOS 1.13 no deja fijar la cookie 0x02|k|sesion.id, así
que la sesión se agrupa por appId (ONOS lo codifica en la cookie de cada regla). M1 usará withCookie.

Uso (root, desde la carpeta del kit; topología SIN --manual):
  python3 controller/login.py h1                 # pide usuario y contraseña
  python3 controller/login.py h1 -u alumno.demo  # pide solo la contraseña
  python3 controller/login.py --logout h1
  python3 controller/login.py --estado
  python3 controller/login.py --lote h1:alumno.demo h2:docente.demo h3:ti.demo   # logins concurrentes
"""
import argparse, getpass, ipaddress, os, random, re, socket, struct, subprocess, sys, tempfile
import threading, time

KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for sub in ("controller", "topo", "tests"):
    sys.path.insert(0, os.path.join(KIT, sub))

from onos_rest import (ONOS, AUTH, ACCESS, CONTROLLER, regla, post_flows, borrar_app, in_port,
                       eth_src, eth_dst, eth_type, ip_proto, udp_dst, ipv4_src, ipv4_dst, vlan,
                       output, table, push_vlan, set_vlan, pop_vlan, set_eth_dst)
import requests

RADIUS_IP, NAS_IP = "10.200.0.10", "172.16.0.10"
CA = "/etc/tel354/certs/ca.pem"
EAP_DIR = os.path.join(KIT, "tests", "eap")
DSN_M1 = "host=10.200.0.12 dbname=campus user=onos_m1 password=m1-demo"
APP_SESION = "tel354.sesion."
ROL_K = {1: "ALUMNO", 2: "JP", 3: "DOCENTE", 4: "ADMINISTRATIVO", 5: "TI", 6: "INVITADO", 7: "CUARENTENA"}

# Hosts de la topología (igual que topo/campus_topo_demo.py): switch, pabellón, MAC
HOSTS = {"h1": ("as1", 1, "00:00:00:10:01:01"), "h2": ("as1", 1, "00:00:00:30:01:02"),
         "h3": ("as2", 2, "00:00:00:50:02:01"), "h4": ("as2", 2, "00:00:00:60:02:02")}

def _secreto():
    try:
        for l in open(os.path.join(KIT, "demo.env")):
            m = re.match(r"\s*RADIUS_SECRET=(\S+)", l)
            if m:
                return m.group(1)
    except OSError:
        pass
    return "tel354-secreto"

SECRETO = _secreto()
_dhcp_lock = threading.Lock()

def puerto_de(host):
    return 2 + (int(host[1:]) + 1) % 2          # h1/h3 -> 2, h2/h4 -> 3 (Tabla M.3)

def clave_demo(usuario):
    """Contraseña de un usuario de demo, leída de tests/eap/<usuario>.conf (para los logins en lote)."""
    m = re.search(r'password="([^"]*)"', open(os.path.join(EAP_DIR, f"{usuario}.conf")).read())
    return m.group(1) if m else ""

# ------------------------------------------------------------------ 1. 802.1X (R1)
def parsear_eapol(out):
    """Extrae del log de eapol_test: resultado, código de rol k, Session-Timeout y rondas RADIUS."""
    ok = re.search(r"^SUCCESS$", out, re.M) is not None
    m = re.search(r"Value: 0001869f0106([0-9a-f]{8})", out)          # VSA PUCP-Role-Code (vendor 99999)
    k = int(m.group(1), 16) if (ok and m) else None
    st = re.search(r"Attribute 27 \(Session-Timeout\) length=\d+\s+Value: (\d+)", out)
    return {"ok": ok, "k": k, "rol": ROL_K.get(k), "session_timeout": int(st.group(1)) if st else None,
            "rondas": len(re.findall(r"Sending RADIUS message", out))}

def autenticar(usuario, clave, mac):
    if any(c in usuario + clave for c in '"\n'):
        return {"ok": False, "k": None, "rol": None, "session_timeout": None, "rondas": 0, "ms": 0}
    conf = (f'network={{\n\tkey_mgmt=IEEE8021X\n\teap=TTLS\n\tidentity="{usuario}"\n'
            f'\tanonymous_identity="anonymous"\n\tpassword="{clave}"\n\tphase2="auth=PAP"\n'
            f'\tca_cert="{CA}"\n\teapol_flags=0\n}}\n')
    fd, ruta = tempfile.mkstemp(prefix="login_", suffix=".conf")
    try:
        os.write(fd, conf.encode()); os.close(fd)
        t0 = time.perf_counter()
        p = subprocess.run(["eapol_test", "-c", ruta, "-a", RADIUS_IP, "-A", NAS_IP, "-s", SECRETO,
                            "-M", mac, "-r0", "-t", "10"], capture_output=True, text=True)
        ms = (time.perf_counter() - t0) * 1000
    finally:
        os.unlink(ruta)
    r = parsear_eapol(p.stdout)
    r["ms"] = ms
    return r

# ------------------------------------------------------------------ 2. DHCP con opción 82
def dhcp_lease(mac, k, n, timeout=3.0):
    import dhcp_relay_test as d
    macb = bytes.fromhex(mac.replace(":", ""))
    with _dhcp_lock:                                  # un solo socket en 172.16.0.10:67 a la vez
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((NAS_IP, 67))
        try:
            xid = random.getrandbits(32)
            t0 = time.perf_counter()
            s.sendto(d.paquete(1, xid, macb, k, n), (d.KEA_IP, 67))
            offer = d.esperar(s, xid, 2, timeout)
            if not offer:
                return None
            ip = socket.inet_ntoa(offer[16:20])
            srv = socket.inet_ntoa(d.leer_opciones(offer)[54])
            s.sendto(d.paquete(3, xid, macb, k, n, pedida=ip, servidor=srv), (d.KEA_IP, 67))
            ack = d.esperar(s, xid, 5, timeout)
            if not ack:
                return None
            o = d.leer_opciones(ack)
            pref = 12
            if 1 in o:
                pref = ipaddress.IPv4Network(f"0.0.0.0/{socket.inet_ntoa(o[1])}").prefixlen
            return {"ip": ip, "prefijo": pref, "router": socket.inet_ntoa(o[3]) if 3 in o else None,
                    "lease": struct.unpack("!I", o[51])[0] if 51 in o else None,
                    "ms": (time.perf_counter() - t0) * 1000}
        finally:
            s.close()

# ------------------------------------------------------------------ 3. Host de Mininet
def pid_host(nombre):
    p = subprocess.run(["pgrep", "-f", f"mininet:{nombre}$"], capture_output=True, text=True)
    pids = p.stdout.split()
    if not pids:
        raise RuntimeError(f"No encuentro el host {nombre}: ¿está levantada la topología?")
    return pids[0]

def en_host(nombre, cmd, timeout=None):
    return subprocess.run(["mnexec", "-a", pid_host(nombre), "sh", "-c", cmd],
                          capture_output=True, text=True, timeout=timeout)

def configurar_host(nombre, ip, prefijo, gw, gwmac):
    i = f"{nombre}-eth0"
    en_host(nombre, f"ip addr flush dev {i}; ip addr add {ip}/{prefijo} dev {i}; "
                    f"ip route replace default via {gw} dev {i}; arp -s {gw} {gwmac}")

def limpiar_host(nombre):
    i = f"{nombre}-eth0"
    en_host(nombre, f"ip addr flush dev {i}; ip neigh flush dev {i}")

def ip_host(nombre):
    try:
        out = en_host(nombre, f"ip -4 -o addr show dev {nombre}-eth0").stdout
    except RuntimeError:
        return None
    m = re.search(r"inet (\S+)", out)
    return m.group(1) if m else None

# ------------------------------------------------------------------ 4. Reglas de la sesión
def reglas_sesion(dev, puerto, mac, ip, v):
    """Tabla 0: 525, 520, 505 · Tabla 2: 200, 190 (AVZ07, Tablas 5 y 7)."""
    return [regla(dev, 0, 525, [in_port(puerto), eth_src(mac), eth_type("0x0800"), ip_proto(17), udp_dst(67)],
                  [output(CONTROLLER)]),
            regla(dev, 0, 520, [in_port(puerto), eth_src(mac), eth_type("0x0800"), ipv4_src(f"{ip}/32")],
                  [push_vlan(), set_vlan(v), table(1)]),
            regla(dev, 0, 505, [in_port(puerto), eth_src(mac), eth_type("0x0800")], [output(CONTROLLER)]),
            regla(dev, 2, 200, [vlan(v), eth_type("0x0800"), ipv4_dst(f"{ip}/32")],
                  [pop_vlan(), set_eth_dst(mac), output(puerto)]),
            regla(dev, 2, 190, [vlan(v), eth_dst(mac)], [pop_vlan(), output(puerto)])]

def flows_app(app):
    try:
        r = requests.get(f"{ONOS}/flows/application/{app}", auth=AUTH, timeout=10)
        return r.json().get("flows", []) if r.ok else []
    except requests.RequestException:
        return []

# ------------------------------------------------------------------ login / logout
def login(host, usuario, clave, dsn=DSN_M1, log=print):
    """Devuelve un dict con el resultado; log recibe las líneas a mostrar."""
    import sesion_manual as sm                      # accesos_curso, reglas_curso, registrar
    sw, pab, mac = HOSTS[host]
    dev, puerto, app = ACCESS[pab], puerto_de(host), APP_SESION + host
    t0 = time.perf_counter()
    log(f"── Login de {usuario} en {host} ({sw}, puerto {puerto}, pabellón {pab}, MAC {mac})")

    a = autenticar(usuario, clave, mac)
    if not a["ok"] or not a["rol"]:
        log(f"  [1] 802.1X  RECHAZADO por FreeRADIUS ({a['ms']:.0f} ms). Sin sesión: el host no recibe IP")
        log(f"      ni reglas; su tráfico muere en la regla 10 (table-miss) del switch.")
        borrar_app(app); limpiar_host(host); cerrar_bd(host, dsn)
        return {"ok": False, "host": host, "usuario": usuario, "ms": (time.perf_counter() - t0) * 1000}
    k, rol = a["k"], a["rol"]
    b = 16 * k
    v = 999 if rol == "CUARENTENA" else 100 + pab
    log(f"  [1] 802.1X  ACCEPT · rol {rol} (k = {k}, b = {b})"
        + (f" · Session-Timeout {a['session_timeout']} s" if a["session_timeout"] else "")
        + f" · {a['rondas']} rondas RADIUS · {a['ms']:.0f} ms")

    l = dhcp_lease(mac, k, pab)
    if not l:
        log("  [2] DHCP    Kea no respondió: revisa los servicios en svc"); return {"ok": False, "host": host}
    red = ipaddress.ip_network(f"10.{b + pab}.0.0/16")
    dentro = ipaddress.ip_address(l["ip"]) in red
    log(f"  [2] DHCP    opción 82 (N = {pab}, k = {k}) → {l['ip']}/{l['prefijo']} "
        f"{'∈' if dentro else '∉'} {red} · gateway {l['router']} · {l['ms']:.1f} ms")

    gw = l["router"] or f"10.{b}.0.1"
    configurar_host(host, l["ip"], l["prefijo"], gw, "02:00:00:00:00:%02x" % b)
    log(f"  [3] Host    {host}-eth0 = {l['ip']}/{l['prefijo']}, ruta por defecto {gw}, VLAN de la sesión {v}")

    accesos = [] if rol in ("CUARENTENA", "INVITADO") else sm.accesos_curso(dsn, usuario)
    R = reglas_sesion(dev, puerto, mac, l["ip"], v) + sm.reglas_curso(dev, l["ip"], accesos)
    borrar_app(app)                                   # si el host tenía otra sesión, se reemplaza
    n = post_flows(R, app=app)
    cursos = ", ".join(sorted({c for c, *_ in accesos})) or "ninguno"
    log(f"  [4] Reglas  {n} en {sw} (appId {app}): 525, 520, 505 (T0) + {len(accesos)} × 110 (T1, cursos: {cursos})"
        f" + 200, 190 (T2)")

    sid = sm.registrar(dsn, dev, puerto, l["ip"], v, mac, usuario, rol)
    ms = (time.perf_counter() - t0) * 1000
    log(f"  [5] BD      sesion.id = {sid} · estado ACTIVA")
    time.sleep(0.5)
    fl = flows_app(app)
    if fl:
        est = {}
        for f in fl:
            est[f.get("state", "?")] = est.get(f.get("state", "?"), 0) + 1
        ck = sorted({int(f["id"]) >> 48 for f in fl})
        log(f"      ONOS: {', '.join(f'{c} {e}' for e, c in est.items())} · cookies 0x{ck[0]:04x}… "
            f"(los 16 bits altos identifican la sesión)" if len(ck) == 1 else
            f"      ONOS: {', '.join(f'{c} {e}' for e, c in est.items())}")
    log(f"  ✔ {usuario} admitido como {rol} en {ms:.0f} ms (autenticación + DHCP + reglas + BD)")
    return {"ok": True, "host": host, "usuario": usuario, "rol": rol, "ip": l["ip"], "reglas": n,
            "sesion": sid, "ms": ms, "ms_auth": a["ms"], "ms_dhcp": l["ms"]}

def cerrar_bd(host, dsn=DSN_M1, motivo="DESCONEXION"):
    import psycopg2
    with psycopg2.connect(dsn, connect_timeout=5) as cx, cx.cursor() as cur:
        cur.execute("""UPDATE sesion SET estado='CERRADA', fin=now(), motivo_cierre=%s
                       WHERE estado<>'CERRADA' AND dispositivo_id=(SELECT id FROM dispositivo WHERE mac=%s)
                       RETURNING id""", (motivo, HOSTS[host][2]))
        return [r[0] for r in cur.fetchall()]

def contar_switch(sw):
    """Entradas instaladas en un switch, leídas del propio OVS."""
    out = subprocess.run(["ovs-ofctl", "-O", "OpenFlow13", "dump-flows", sw],
                         capture_output=True, text=True).stdout
    return sum(1 for l in out.splitlines() if "cookie=" in l)

def logout(host, dsn=DSN_M1, log=print):
    app = APP_SESION + host
    sw = HOSTS[host][0]
    en_sw = contar_switch(sw)
    antes = flows_app(app)
    otras = {h: len(flows_app(APP_SESION + h)) for h in HOSTS if h != host}
    borrar_app(app)
    time.sleep(1)
    despues = flows_app(app)
    cerradas = cerrar_bd(host, dsn)
    limpiar_host(host)
    otras_d = {h: len(flows_app(APP_SESION + h)) for h in otras}
    log(f"── Logout de {host}")
    log(f"  Reglas de la sesión: {len(antes)} → {len(despues)} (borrado en bloque por su appId/cookie)")
    log(f"  Entradas en {sw}: {en_sw} → {contar_switch(sw)} (medido con ovs-ofctl)")
    log(f"  Sesión en la BD: {', '.join(map(str, cerradas)) or 'ninguna activa'} → CERRADA (DESCONEXION)")
    log(f"  {host} sin IP: vuelve a quedar solo con EAPOL y ARP (reglas 900 y 800)")
    log("  Otras sesiones intactas: " + ", ".join(f"{h} {otras[h]}→{otras_d[h]}" for h in otras))
    return len(antes) - len(despues)

def sesiones_activas(dsn=DSN_M1):
    import psycopg2
    with psycopg2.connect(dsn, connect_timeout=5) as cx, cx.cursor() as cur:
        cur.execute("""SELECT s.id, u.uid_ldap, r.nombre, host(s.ip), s.vlan_id, s.switch_dpid, s.puerto,
                              to_char(s.inicio, 'HH24:MI:SS'), d.mac
                       FROM sesion s JOIN usuario u ON u.id = s.usuario_id JOIN rol r ON r.id = s.rol_efectivo_id
                       JOIN dispositivo d ON d.id = s.dispositivo_id
                       WHERE s.estado <> 'CERRADA' ORDER BY s.id""")
        return cur.fetchall()

def estado(log=print):
    mac_host = {m: h for h, (_, _, m) in HOSTS.items()}
    log(f"{'host':4} {'IP':16} {'reglas':>6}  {'sesión':>6} {'usuario':14} {'rol':11} VLAN")
    filas = {mac_host.get(f[8]): f for f in sesiones_activas()}
    for h in HOSTS:
        f = filas.get(h)
        log(f"{h:4} {str(ip_host(h) or '-'):16} {len(flows_app(APP_SESION + h)):>6}  "
            + (f"{f[0]:>6} {f[1]:14} {f[2]:11} {f[4]}" if f else f"{'-':>6} {'(sin sesión)':14}"))

def lote(pares, log=print):
    """Logins concurrentes: uno por hilo. La autenticación corre en paralelo; el DHCP comparte socket."""
    res, salidas, hilos = {}, {}, []
    def tarea(h, u):
        salidas[h] = []
        res[h] = login(h, u, clave_demo(u), log=salidas[h].append)
    t0 = time.perf_counter()
    for par in pares:
        h, u = par.split(":")
        t = threading.Thread(target=tarea, args=(h, u)); t.start(); hilos.append(t)
    for t in hilos:
        t.join()
    total = (time.perf_counter() - t0) * 1000
    for h in sorted(salidas):
        for l in salidas[h]:
            log(l)
        log("")
    ok = sum(1 for r in res.values() if r.get("ok"))
    suma = sum(r.get("ms", 0) for r in res.values())
    log(f"Logins concurrentes: {ok}/{len(res)} admitidos en {total:.0f} ms de reloj "
        f"(en serie habrían sumado ~{suma:.0f} ms)")
    return res

def main():
    ap = argparse.ArgumentParser(description="Login y logout de la demo de R1 y R2")
    ap.add_argument("host", nargs="?", choices=sorted(HOSTS))
    ap.add_argument("-u", "--usuario")
    ap.add_argument("--logout", action="store_true")
    ap.add_argument("--estado", action="store_true")
    ap.add_argument("--lote", nargs="+", metavar="hX:usuario")
    a = ap.parse_args()
    if a.estado:
        estado()
    elif a.lote:
        lote(a.lote)
    elif a.host and a.logout:
        logout(a.host)
    elif a.host:
        u = a.usuario or input("Usuario: ").strip()
        login(a.host, u, getpass.getpass("Contraseña: "))
    else:
        ap.print_help()

if __name__ == "__main__":
    main()
