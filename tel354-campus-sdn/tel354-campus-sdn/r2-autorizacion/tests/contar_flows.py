#!/usr/bin/env python3
"""
contar_flows.py - Compara las flow entries medidas en los switches con el modelo del AVZ08.

  F_acc  = 5 + P + 3S   (Tabla 0) + (8 + Σ|perm*| + C)  (Tabla 1) + (2 + 2S)  (Tabla 2)
         = c0 + P + 5S + C,  con C = Σ c_s (reglas 110 de acceso por curso de cada sesión)
  F_core = 5 + 7·N_pab + N_srv
donde Σ|perm*| es el número de filas de v_permiso_efectivo (reglas 100) y S el número de
sesiones del switch (se cuentan las reglas 520). En la maqueta P = 2 y N_pab = 2.
Las reglas reactivas (150, 300-302) se informan aparte.

Uso (namespace raíz de la VM):  python3 r2-autorizacion/tests/contar_flows.py
"""
import argparse, collections, re, subprocess

def volcar(sw):
    out = subprocess.run(["ovs-ofctl", "-O", "OpenFlow13", "dump-flows", sw],
                         capture_output=True, text=True).stdout
    flows = []
    for l in out.splitlines():
        m = re.search(r"table=(\d+).*?priority=(\d+)", l)
        if m:
            flows.append((int(m.group(1)), int(m.group(2)), l))
        elif "cookie=" in l and "table=" not in l:          # tabla 0 sin 'table=' explícito
            p = re.search(r"priority=(\d+)", l)
            flows.append((0, int(p.group(1)) if p else 0, l))
    return flows

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default="host=10.200.0.12 dbname=campus user=onos_m2 password=m2-demo")
    ap.add_argument("--perm", type=int, help="Σ|perm*| (si no se quiere consultar la BD)")
    ap.add_argument("--nsrv", type=int, default=12)
    a = ap.parse_args()
    perm, cesp = a.perm, {}
    if perm is None:
        import psycopg2
        with psycopg2.connect(a.dsn, connect_timeout=5) as cx, cx.cursor() as cur:
            cur.execute("SELECT count(*) FROM v_permiso_efectivo"); perm = cur.fetchone()[0]
            cur.execute("""SELECT s.switch_dpid, count(ac.recurso_id) FROM sesion s
                           LEFT JOIN v_acceso_curso ac ON ac.usuario_id = s.usuario_id
                           WHERE s.estado = 'ACTIVA' GROUP BY 1""")
            cesp = dict(cur.fetchall())

    P, NPAB = 2, 2
    print(f"Σ|perm*| = {perm}   P = {P}   N_pab = {NPAB}   N_srv = {a.nsrv}\n")
    print(f"{'switch':6} {'S':>2} {'C':>2} {'T0 med/mod':>11} {'T1 med/mod':>11} {'T2 med/mod':>11} {'total med/mod':>14} "
          f"{'reactivas':>9}  ARP-40000")
    for sw, dpid in (("as1", "of:0000000000000001"), ("as2", "of:0000000000000002")):
        f = volcar(sw)
        por = collections.Counter(t for t, p, _ in f)
        S = sum(1 for t, p, _ in f if t == 0 and p == 520)
        C = sum(1 for t, p, _ in f if t == 1 and p == 110)
        react = sum(1 for t, p, _ in f if t == 1 and (p == 150 or 300 <= p <= 302))
        arp = any(t == 0 and p == 40000 and "arp" in l for t, p, l in f)
        Cmod = cesp.get(dpid, C) if cesp else C
        mod = {0: 5 + P + 3 * S, 1: 8 + perm + Cmod, 2: 2 + 2 * S}
        med = {0: por[0] - (1 if arp else 0), 1: por[1] - react, 2: por[2]}
        ok = all(med[t] == mod[t] for t in mod)
        print(f"{sw:6} {S:>2} {C:>2} {med[0]:>5}/{mod[0]:<5} {med[1]:>5}/{mod[1]:<5} {med[2]:>5}/{mod[2]:<5} "
              f"{sum(med.values()):>6}/{sum(mod.values()):<7} {react:>9}  {'sí (desactivar)' if arp else 'no'}"
              f"  {'OK' if ok else '<-- difiere'}")
    f = volcar("core")
    arp = any(p == 40000 and "arp" in l for t, p, l in f)
    med = len(f) - (1 if arp else 0)
    mod = 5 + 7 * NPAB + a.nsrv
    print(f"\ncore   medido {med} / modelo {mod}  (5 + 7·{NPAB} + {a.nsrv})  {'OK' if med == mod else '<-- difiere'}")
    print("\nCada sesión añade 5 entradas a su switch de acceso (3 en la Tabla 0 y 2 en la Tabla 2),")
    print("más una regla 110 por cada servidor de los cursos en los que el usuario está inscrito (C).")

if __name__ == "__main__":
    main()
