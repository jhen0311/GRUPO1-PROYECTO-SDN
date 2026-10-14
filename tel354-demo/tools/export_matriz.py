#!/usr/bin/env python3
"""
export_matriz.py - Exporta la matriz de autorización a JSON para la batería de R2.
Los hosts de usuario no pueden consultar la BD (R2 lo impide), así que la batería lee
este archivo, que se genera desde el namespace raíz.
Uso: python3 tools/export_matriz.py > /tmp/matriz.json
"""
import json, sys
import psycopg2
dsn = sys.argv[1] if len(sys.argv) > 1 else "host=10.200.0.12 dbname=campus user=onos_m2 password=m2-demo"
with psycopg2.connect(dsn) as cx, cx.cursor() as cur:
    cur.execute("SELECT id, nombre, host(ip), protocolo, puerto FROM recurso ORDER BY id")
    recursos = [dict(zip(("id", "nombre", "ip", "protocolo", "puerto"), r)) for r in cur.fetchall()]
    cur.execute("SELECT nombre FROM rol ORDER BY id")
    roles = [r[0] for r in cur.fetchall()]
    cur.execute("SELECT rol, recurso_id FROM v_permiso_efectivo")
    permisos = {r: [] for r in roles}
    for rol, rid in cur.fetchall():
        permisos[rol].append(rid)
    # usuario -> rol efectivo y servidores de sus cursos (acceso por inscripción)
    cur.execute("SELECT uid_ldap, split_part(campus_rol_efectivo(uid_ldap), '|', 1) FROM usuario")
    usuarios = {u: {"rol": r, "cursos": []} for u, r in cur.fetchall() if r != "REJECT"}
    cur.execute("SELECT uid_ldap, recurso_id FROM v_acceso_curso")
    for u, rid in cur.fetchall():
        if u in usuarios:
            usuarios[u]["cursos"].append(rid)
json.dump({"recursos": recursos, "permisos": permisos, "usuarios": usuarios},
          sys.stdout, ensure_ascii=False, indent=1)
