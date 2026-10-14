#!/usr/bin/env python3
"""
sesion_manual.py - Emula lo que M1 instala al cerrar las Fases 2 y 3 de una sesión
(AVZ07, Tablas 11 y 13), para demostrar R2 y medir flows mientras M1 no está en ONOS.

Por sesión instala 5 + c reglas en el switch de acceso del host:
  Tabla 0: 525 (DHCP de la sesión), 520 (binding puerto+MAC+IP), 505 (IP incoherente)
  Tabla 1: 110, una por cada servidor de los cursos en los que el usuario está inscrito
           en el ciclo vigente (vista v_acceso_curso): acceso por curso
  Tabla 2: 200 (entrega por IP) y 190 (entrega por MAC)
y registra el dispositivo y la sesión en PostgreSQL como lo haría M1.
Requiere la topología en modo --manual (IP fija del pool del rol y ARP estático al gateway).

Uso:  python3 controller/sesion_manual.py h1        (h1, h2, h3 o h4)
      python3 controller/sesion_manual.py --cerrar h1
"""
import argparse, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "topo"))
from onos_rest import *
from campus_topo_demo import HOSTS, ip_manual

APP_SESION = "tel354.sesion."

def reglas_sesion(nombre):
    sw, pab, mac, usuario, rol = HOSTS[nombre]
    dev, puerto = ACCESS[pab], 2 + (int(nombre[1:]) + 1) % 2
    ip = ip_manual(nombre)[0]
    v = 999 if rol == "CUARENTENA" else 100 + pab
    R = [regla(dev, 0, 525, [in_port(puerto), eth_src(mac), eth_type("0x0800"), ip_proto(17), udp_dst(67)], [output(CONTROLLER)]),
         regla(dev, 0, 520, [in_port(puerto), eth_src(mac), eth_type("0x0800"), ipv4_src(f"{ip}/32")],
               [push_vlan(), set_vlan(v), table(1)]),
         regla(dev, 0, 505, [in_port(puerto), eth_src(mac), eth_type("0x0800")], [output(CONTROLLER)]),
         regla(dev, 2, 200, [vlan(v), eth_type("0x0800"), ipv4_dst(f"{ip}/32")],
               [pop_vlan(), set_eth_dst(mac), output(puerto)]),
         regla(dev, 2, 190, [vlan(v), eth_dst(mac)], [pop_vlan(), output(puerto)])]
    return R, dev, puerto, ip, v, mac, usuario, rol

def accesos_curso(dsn, usuario):
    """Servidores de los cursos del usuario en el ciclo vigente: (curso, ip, protocolo, puerto)."""
    import psycopg2
    with psycopg2.connect(dsn, connect_timeout=5) as cx, cx.cursor() as cur:
        cur.execute("SELECT curso, host(ip), protocolo, puerto FROM v_acceso_curso WHERE uid_ldap=%s ORDER BY 1",
                    (usuario,))
        return cur.fetchall()

def reglas_curso(dev, ip, accesos):
    return [regla(dev, 1, 110, [eth_type("0x0800"), ipv4_src(f"{ip}/32"), ipv4_dst(f"{dst}/32")]
                  + l4_dst(proto, puerto), [table(2)]) for _, dst, proto, puerto in accesos]

def registrar(dsn, dev, puerto, ip, v, mac, usuario, rol):
    import psycopg2
    with psycopg2.connect(dsn, connect_timeout=5) as cx, cx.cursor() as cur:
        cur.execute("""INSERT INTO dispositivo (mac, switch_dpid, puerto) VALUES (%s,%s,%s)
                       ON CONFLICT (mac) DO UPDATE SET switch_dpid=EXCLUDED.switch_dpid, puerto=EXCLUDED.puerto
                       RETURNING id""", (mac, dev, puerto))
        disp = cur.fetchone()[0]
        # una sola sesión activa por equipo: la anterior del mismo dispositivo se cierra
        cur.execute("""UPDATE sesion SET estado='CERRADA', fin=now(), motivo_cierre='DESCONEXION'
                       WHERE dispositivo_id=%s AND estado<>'CERRADA'""", (disp,))
        cur.execute("""INSERT INTO sesion (usuario_id, dispositivo_id, switch_dpid, puerto, rol_efectivo_id, ip, vlan_id, estado)
                       SELECT u.id, %s, %s, %s, r.id, %s, %s, 'ACTIVA' FROM usuario u, rol r
                       WHERE u.uid_ldap=%s AND r.nombre=%s RETURNING id""",
                    (disp, dev, puerto, ip, v, usuario, rol))
        fila = cur.fetchone()
        return fila[0] if fila else None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host", choices=sorted(HOSTS))
    ap.add_argument("--cerrar", action="store_true")
    ap.add_argument("--dsn", default="host=10.200.0.12 dbname=campus user=onos_m1 password=m1-demo")
    ap.add_argument("--sin-bd", action="store_true")
    a = ap.parse_args()
    app = APP_SESION + a.host
    if a.cerrar:
        borrar_app(app)
        if not a.sin_bd:
            import psycopg2
            mac = HOSTS[a.host][2]
            with psycopg2.connect(a.dsn, connect_timeout=5) as cx, cx.cursor() as cur:
                cur.execute("""UPDATE sesion SET estado='CERRADA', fin=now(), motivo_cierre='DESCONEXION'
                               WHERE estado<>'CERRADA' AND dispositivo_id=(SELECT id FROM dispositivo WHERE mac=%s)""",
                            (mac,))
        print(f"Reglas de la sesión de {a.host} retiradas (cierre por cookie, como M7); sesión CERRADA en la BD.")
        return
    R, dev, puerto, ip, v, mac, usuario, rol = reglas_sesion(a.host)
    # Cuarentena e Invitado nunca reciben acceso por curso: la regla 110 (prioridad > 58)
    # saltaría el aislamiento de la VLAN 999
    accesos = [] if (a.sin_bd or not usuario or rol in ("CUARENTENA", "INVITADO")) else accesos_curso(a.dsn, usuario)
    R += reglas_curso(dev, ip, accesos)
    n = post_flows(R, app=app)
    sid = None if (a.sin_bd or not usuario) else registrar(a.dsn, dev, puerto, ip, v, mac, usuario, rol)
    cursos = ", ".join(sorted({c for c, *_ in accesos})) or "ninguno"
    print(f"{a.host}: {n} reglas en {dev} puerto {puerto} | {usuario or 'sin usuario'} {rol} {ip} VLAN {v}"
          f" | cursos: {cursos} ({len(accesos)} reglas 110)" + (f" | sesion.id = {sid}" if sid else ""))

if __name__ == "__main__":
    main()
