#!/usr/bin/env python3
"""
demo.py - Menú de la demo de R1 y R2 (TEL354, Grupo 01): interfaz de control de acceso.

Requisitos: ONOS arriba (ventana 1), topología levantada (ventana 2) y reglas proactivas
instaladas (opción 12 o controller/onos_static.py). Se ejecuta como root en la tercera ventana:

    cd /home/mininet/tel354-demo && python3 demo.py
"""
import getpass, json, os, re, subprocess, sys, time

KIT = os.path.dirname(os.path.abspath(__file__))
os.chdir(KIT)
for sub in ("controller", "topo", "tests"):
    sys.path.insert(0, os.path.join(KIT, sub))
os.environ.setdefault("PYTHONIOENCODING", "utf-8")

import login as L
from onos_rest import ONOS, AUTH, ACCESS, APP, regla, post_flows, eth_type, ipv4_src, ipv4_dst, l4_dst, table
import requests

DSN_M2 = "host=10.200.0.12 dbname=campus user=onos_m2 password=m2-demo"
SWITCHES = {"as1": "of:0000000000000001", "as2": "of:0000000000000002", "core": "of:0000000000000100"}
USUARIOS_DEMO = [("alumno.demo", "Alumno2026!", "ALUMNO"), ("docente.demo", "Docente2026!", "DOCENTE"),
                 ("ti.demo", "TI2026!", "TI"), ("jp.demo", "Jp2026!", "JP (doble rol)"),
                 ("sancion.demo", "Sancion2026!", "CUARENTENA"), ("inactivo.demo", "Inactivo2026!", "rechazo")]

# Qué es cada prioridad (AVZ07, Tablas 5 a 8), para leer las reglas en pantalla
PRIO = {(0, 900): "EAPOL → controlador (inicio de 802.1X)", (0, 800): "ARP → controlador (con meter)",
        (0, 600): "tráfico del uplink → Tabla 2", (0, 525): "DHCP de la sesión → M1 (relay)",
        (0, 520): "binding puerto+MAC+IP → push VLAN, Tabla 1", (0, 505): "IP falsa de la MAC → controlador",
        (0, 10): "table-miss: descarte (denegación por defecto)", (0, 40000): "LLDP/BDDP → ONOS",
        (1, 110): "acceso por curso (sesión)", (1, 100): "permiso del rol (/12 → recurso)",
        (1, 60): "DNS permitido", (1, 58): "cuarentena → interno: descarte", (1, 57): "cuarentena → Internet (1 Mbps)",
        (1, 55): "otro destino interno → captura", (1, 50): "Internet", (1, 0): "table-miss: descarte",
        (2, 200): "entrega (host o servicios)", (2, 190): "entrega al host por MAC", (2, 180): "salida por uplink"}

B, V, R, A, G, X = "\033[1m", "\033[32m", "\033[31m", "\033[36m", "\033[90m", "\033[0m"

def titulo(t):
    print(f"\n{B}{A}══ {t} ══{X}\n")

def pausa():
    input(f"\n{G}Enter para volver al menú…{X}")

def correr(cmd):
    """Ejecuta un comando del kit mostrando su salida en vivo."""
    print(f"{G}$ {' '.join(cmd)}{X}")
    subprocess.call(cmd, cwd=KIT)

def elegir(texto, opciones):
    """opciones: lista de (clave, descripción). Devuelve la clave elegida o None."""
    for i, (_, d) in enumerate(opciones, 1):
        print(f"  {i}. {d}")
    r = input(f"\n{texto}: ").strip()
    if r.isdigit() and 1 <= int(r) <= len(opciones):
        return opciones[int(r) - 1][0]
    return None

def elegir_host(texto="Host"):
    ses = {f[8]: f for f in L.sesiones_activas()}
    ops = []
    for h, (sw, n, mac) in L.HOSTS.items():
        f = ses.get(mac)
        ops.append((h, f"{h}  ({sw}, pabellón {n})  " + (f"sesión de {f[1]} ({f[2]})" if f else "sin sesión")))
    return elegir(texto, ops)

def bd(dsn, sql, args=()):
    import psycopg2
    with psycopg2.connect(dsn, connect_timeout=5) as cx, cx.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall() if cur.description else None

def bd_admin(sql):
    """Escritura en la BD como postgres, dentro del host svc (los roles de ONOS son de solo lectura)."""
    p = subprocess.run(["mnexec", "-a", L.pid_host("svc"), "su", "postgres", "-c",
                        f'psql -d campus -v ON_ERROR_STOP=1 -Atc "{sql}"'], capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(p.stderr.strip())

# ---------------------------------------------------------------- 1. estado
def op_estado():
    titulo("Estado de la red")
    try:
        devs = requests.get(f"{ONOS}/devices", auth=AUTH, timeout=5).json().get("devices", [])
        print(f"ONOS: {sum(d.get('available') for d in devs)}/{len(devs)} switches conectados")
    except requests.RequestException:
        print(f"{R}ONOS no responde en {ONOS}: ¿está arriba la ventana 1?{X}")
    print("Entradas por switch: " + ", ".join(f"{s} {L.contar_switch(s)}" for s in SWITCHES))
    print()
    L.estado()

# ---------------------------------------------------------------- R1
def op_login():
    titulo("Iniciar sesión (802.1X → rol → DHCP → reglas)")
    h = elegir_host("¿En qué host inicia sesión el usuario?")
    if not h:
        return
    print(f"\n{G}Usuarios de demo: " + ", ".join(u for u, *_ in USUARIOS_DEMO) + f"{X}")
    u = input("Usuario: ").strip()
    c = getpass.getpass("Contraseña: ")
    print()
    r = L.login(h, u, c)
    if r.get("ok"):
        print(f"\n{V}Prueba sus accesos con la opción 8.{X}")

def op_bateria_r1():
    titulo("Batería de autenticación (R1)")
    correr(["python3", "tests/r1_radius.py", "--bateria"])

def op_lote():
    titulo("Logins concurrentes: h1, h2 y h3 a la vez")
    L.lote(["h1:alumno.demo", "h2:docente.demo", "h3:ti.demo"])

def op_concurrencia():
    titulo("Logueos concurrentes contra la cadena de autenticación")
    correr(["python3", "tests/r1_concurrente.py", "--total", "40", "--hilos", "1,5,10", "--con-invalidos"])

# ---------------------------------------------------------------- R2
def leer_matriz():
    roles = [r[0] for r in bd(DSN_M2, "SELECT nombre FROM rol WHERE rama <> 'MINIMA' ORDER BY id")]
    recursos = bd(DSN_M2, "SELECT id, nombre, host(ip), puerto, curso_id IS NOT NULL FROM recurso ORDER BY id")
    efectivo = set(bd(DSN_M2, "SELECT rol, recurso_id FROM v_permiso_efectivo"))
    directo = set(bd(DSN_M2, "SELECT r.nombre, p.recurso_id FROM permiso_rol p JOIN rol r ON r.id = p.rol_id"))
    return roles, recursos, efectivo, directo

def op_ver_matriz():
    titulo("Matriz de autorización (rol × recurso)")
    roles, recursos, efectivo, directo = leer_matriz()
    ab = {"ALUMNO": "ALUM", "JP": "JP", "DOCENTE": "DOC", "ADMINISTRATIVO": "ADM", "TI": "TI"}
    print(f"{'#':>2} {'recurso':28} {'destino':20} " + " ".join(f"{ab.get(r, r[:4]):>5}" for r in roles))
    for rid, nom, ip, pto, curso in recursos:
        celdas = []
        for r in roles:
            if curso:
                celdas.append(f"{'cur':>5}")
            elif (r, rid) in directo:
                celdas.append(f"{V}{'✔':>5}{X}")
            elif (r, rid) in efectivo:
                celdas.append(f"{A}{'↑':>5}{X}")
            else:
                celdas.append(f"{G}{'·':>5}{X}")
        print(f"{rid:>2} {nom[:28]:28} {ip + ':' + str(pto):20} " + " ".join(celdas))
    print(f"\n{V}✔{X} permiso directo   {A}↑{X} heredado (Alumno ⊂ JP ⊂ Docente)   "
          f"cur = solo inscritos en el curso (regla 110 por sesión)   · sin acceso")
    print("Invitado y Cuarentena no tienen permisos. Cada ✔/↑ es UNA regla 100 por switch, con origen 10.‹b›.0.0/12.")

def sincronizar_100():
    """Rehace las reglas 100 de los switches de acceso desde v_permiso_efectivo (lo que hará M2)."""
    viejas = [f for f in L.flows_app(APP) if f.get("tableId") == 1 and f.get("priority") == 100]
    for f in viejas:
        requests.delete(f"{ONOS}/flows/{f['deviceId']}/{f['id']}", auth=AUTH, timeout=10)
    permisos = bd(DSN_M2, "SELECT rol, octeto, host(ip), protocolo, puerto FROM v_permiso_efectivo ORDER BY rol, recurso_id")
    nuevas = [regla(dev, 1, 100, [eth_type("0x0800"), ipv4_src(f"10.{octeto}.0.0/12"), ipv4_dst(f"{ip}/32")]
                    + l4_dst(proto, puerto), [table(2)])
              for dev in ACCESS.values() for _, octeto, ip, proto, puerto in permisos]
    post_flows(nuevas)
    return len(viejas), len(nuevas)

def op_cambiar_permiso():
    titulo("Cambiar un permiso de la matriz (en caliente)")
    roles, recursos, efectivo, directo = leer_matriz()
    rol = elegir("Rol", [(r, r) for r in roles])
    if not rol:
        return
    print()
    rec = elegir("Recurso", [(r, f"{r[1]}  ({r[2]}:{r[3]})" + ("  [ya tiene permiso directo]" if (rol, r[0]) in directo else ""))
                             for r in recursos if not r[4]])
    if not rec:
        return
    rid = rec[0]
    rol_id = bd(DSN_M2, "SELECT id FROM rol WHERE nombre = %s", (rol,))[0][0]
    if (rol, rid) in directo:
        bd_admin(f"DELETE FROM permiso_rol WHERE rol_id = {int(rol_id)} AND recurso_id = {int(rid)}")
        print(f"\n{R}Quitado{X}: {rol} ya no tiene permiso directo a {rec[1]}")
    else:
        bd_admin(f"INSERT INTO permiso_rol (rol_id, recurso_id) VALUES ({int(rol_id)}, {int(rid)})")
        print(f"\n{V}Agregado{X}: {rol} → {rec[1]} (los roles que heredan de {rol} también lo reciben)")
    antes, ahora = sincronizar_100()
    print(f"Reglas 100 en los switches de acceso: {antes} → {ahora} (sin tocar las sesiones abiertas)")
    print(f"{G}Compruébalo con la opción 6 (matriz) y la 8 (accesos desde un host).{X}")

def op_probar_accesos():
    titulo("Probar accesos desde un host (R2)")
    h = elegir_host()
    if not h:
        return
    with open("/tmp/matriz.json", "w") as f:
        subprocess.run(["python3", "tools/export_matriz.py"], stdout=f, cwd=KIT)
    fila = {s[8]: s for s in L.sesiones_activas()}.get(L.HOSTS[h][2])
    args = ["--usuario", fila[1]] if fila else ["--rol", "INVITADO"]
    print(f"\n{h}: " + (f"sesión de {fila[1]} ({fila[2]}), IP {fila[3]}" if fila else
                        "sin sesión: todo debe quedar bloqueado (regla 10)") + "\n")
    subprocess.call(["mnexec", "-a", L.pid_host(h), "python3", "tests/bateria_r2.py", *args,
                     "--intentos", "2"], cwd=KIT)

# ---------------------------------------------------------------- reglas
def op_ver_reglas():
    titulo("Reglas instaladas en un switch")
    sw = elegir("Switch", [(s, f"{s} ({d})") for s, d in SWITCHES.items()])
    if not sw:
        return
    duenos = {}
    for app in [APP] + [L.APP_SESION + h for h in L.HOSTS]:
        for f in L.flows_app(app):
            duenos[int(f["id"])] = "régimen base" if app == APP else f"sesión {app.split('.')[-1]}"
    out = subprocess.run(["ovs-ofctl", "-O", "OpenFlow13", "--no-stats", "dump-flows", sw],
                         capture_output=True, text=True).stdout
    filas = []
    for l in out.splitlines():
        m = re.search(r"cookie=(0x[0-9a-f]+)", l)
        if not m:
            continue
        t = int(re.search(r"table=(\d+)", l).group(1)) if "table=" in l else 0
        p = int(re.search(r"priority=(\d+)", l).group(1)) if "priority=" in l else 0
        match = re.sub(r".*priority=\d+,?", "", l.split(" actions=")[0]).strip() or "*"
        acc = l.split(" actions=")[1] if " actions=" in l else "drop"
        filas.append((t, -p, int(m.group(1), 16), match, acc))
    print(f"{'T':>1} {'prio':>5}  {'cookie':18} {'dueño':13} {'qué es':42} match → acciones")
    for t, p, ck, match, acc in sorted(filas):
        d = duenos.get(ck, "ONOS" if -p == 40000 else "-")
        col = V if d.startswith("sesión") else ""
        print(f"{t:>1} {-p:>5}  0x{ck:016x} {col}{d:13}{X} {PRIO.get((t, -p), '')[:42]:42} {G}{match[:60]} → {acc[:50]}{X}")
    print(f"\n{len(filas)} entradas. Las reglas de una sesión comparten los 16 bits altos de la cookie "
          f"(su appId en ONOS): por eso se borran en bloque.")

def op_contar():
    titulo("Entradas TCAM medidas vs modelo del informe")
    correr(["python3", "tests/contar_flows.py"])

def op_logout():
    titulo("Cerrar sesión (borrado inteligente de reglas)")
    h = elegir_host("¿Qué host cierra sesión?")
    if h:
        print()
        L.logout(h)

def op_proactivo():
    titulo("Instalar reglas proactivas (régimen base, permisos por rol y core)")
    correr(["python3", "controller/onos_static.py"])

# ---------------------------------------------------------------- menú
MENU = [
    ("Estado", [("1", "Estado de la red y sesiones activas", op_estado)]),
    ("R1 · Acceso de usuarios", [
        ("2", "Iniciar sesión en un host (usuario y contraseña)", op_login),
        ("3", "Batería de autenticación (válidos e inválidos)", op_bateria_r1),
        ("4", "Logins concurrentes: h1, h2 y h3 a la vez", op_lote),
        ("5", "Logueos concurrentes contra RADIUS (logins/s y latencia)", op_concurrencia)]),
    ("R2 · Autorización por rol", [
        ("6", "Ver matriz de roles", op_ver_matriz),
        ("7", "Cambiar un permiso de la matriz (en caliente)", op_cambiar_permiso),
        ("8", "Probar accesos desde un host", op_probar_accesos)]),
    ("Reglas y TCAM", [
        ("9", "Ver reglas de un switch", op_ver_reglas),
        ("10", "Contar entradas TCAM vs modelo", op_contar),
        ("11", "Cerrar sesión de un host (borrado en bloque)", op_logout),
        ("12", "Instalar reglas proactivas", op_proactivo)]),
]

def main():
    if os.geteuid() != 0:
        sys.exit("Ejecuta como root (sudo -i).")
    acciones = {k: f for _, ops in MENU for k, _, f in ops}
    while True:
        print("\033[2J\033[H", end="")
        t = "Seguridad de campus con SDN · Demo R1 y R2 · TEL354 G01"
        print(f"{B}{A}╔{'═' * (len(t) + 6)}╗\n║   {t}   ║\n╚{'═' * (len(t) + 6)}╝{X}")
        for grupo, ops in MENU:
            print(f"\n {B}{grupo}{X}")
            for k, d, _ in ops:
                print(f"  {k:>2}. {d}")
        print(f"\n   0. Salir")
        op = input(f"\n{B}Opción:{X} ").strip()
        if op == "0":
            break
        if op in acciones:
            try:
                acciones[op]()
            except KeyboardInterrupt:
                print("\n(cancelado)")
            except Exception as e:
                print(f"\n{R}Error: {e}{X}")
            pausa()

if __name__ == "__main__":
    main()
