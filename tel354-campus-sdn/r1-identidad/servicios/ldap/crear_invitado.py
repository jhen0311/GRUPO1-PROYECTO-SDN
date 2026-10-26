#!/usr/bin/env python3
"""
crear_invitado.py - Credencial temporal de invitado (R1).

TI crea la cuenta: OpenLDAP guarda la contraseña (SHA-512 crypt) y PostgreSQL el
rol INVITADO con su vencimiento (usuario.vence_en, ver comun/db/04_invitado.sql).
Desde el vencimiento, FreeRADIUS la rechaza; mientras tanto, el Session-Timeout no
pasa del vencimiento. Invitado: DNS e Internet, sin recursos internos ni reglas 110.

Uso (VM, namespace raíz, con los servicios arriba en svc):
  python3 r1-identidad/servicios/ldap/crear_invitado.py --horas 4 --nombre "Visitante charla"
  python3 r1-identidad/servicios/ldap/crear_invitado.py --listar
  python3 r1-identidad/servicios/ldap/crear_invitado.py --revocar invitado-1234
"""
import argparse, os, secrets, string, subprocess, sys

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BASE = "dc=campus,dc=pucp,dc=edu,dc=pe"

def env():
    v = {}
    for linea in open(os.path.join(RAIZ, "comun", "config", "demo.env"), encoding="utf-8"):
        linea = linea.split("#")[0].strip()
        if "=" in linea:
            k, x = linea.split("=", 1); v[k.strip()] = x.strip()
    return v

def psql(sql, db="campus"):
    r = subprocess.run(["su", "postgres", "-c", f"psql -X -q -t -A -d {db} -v ON_ERROR_STOP=1"],
                       input=sql, capture_output=True, text=True)
    if r.returncode:
        sys.exit("PostgreSQL: " + r.stderr.strip())
    return r.stdout.strip()

def ldap(cmd, ldif, e):
    r = subprocess.run([cmd, "-x", "-H", "ldapi:///", "-D", f"cn=admin,{BASE}", "-w", e["LDAP_ADMIN_PW"]],
                       input=ldif, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"OpenLDAP ({cmd}): " + r.stderr.strip())

def crear(a, e):
    sufijo = f"{secrets.randbelow(10000):04d}"
    uid = f"invitado-{sufijo}"
    clave = "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(10))
    hashc = subprocess.run(["openssl", "passwd", "-6", "-stdin"], input=clave,
                           capture_output=True, text=True, check=True).stdout.strip()
    ldap("ldapadd", f"dn: uid={uid},ou=usuarios,{BASE}\nobjectClass: inetOrgPerson\nuid: {uid}\n"
                    f"cn: {a.nombre}\nsn: {uid}\nuserPassword: {{CRYPT}}{hashc}\n", e)
    vence = psql(f"""INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en)
        VALUES ('INV{sufijo}', '{uid}', $${a.nombre}$$, (SELECT id FROM rol WHERE nombre='INVITADO'),
                'ACTIVO', now() + interval '{a.horas} hours')
        RETURNING to_char(vence_en AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') || ' UTC';""")
    print(f"Credencial temporal creada\n  usuario:    {uid}\n  contraseña: {clave}\n  vence:      {vence}")
    print("La contraseña no se guarda en ningún lugar: entréguesela al invitado ahora.")

def listar():
    print(psql("""SELECT uid_ldap || '  ' || estado || '  vence ' ||
        coalesce(to_char(vence_en AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI'),'-') ||
        CASE WHEN vence_en <= now() THEN '  (VENCIDA)' ELSE '' END
        FROM usuario WHERE vence_en IS NOT NULL ORDER BY vence_en;""") or "Sin credenciales temporales.")

def revocar(uid, e):
    psql(f"UPDATE usuario SET estado='INACTIVO', vence_en=least(coalesce(vence_en, now()), now()) WHERE uid_ldap=$${uid}$$;")
    ldap("ldapdelete", f"uid={uid},ou=usuarios,{BASE}\n", e)
    print(f"{uid} revocada: FreeRADIUS la rechazará en el próximo intento.")
    print("Si tiene una sesión abierta, cerrarla con logout en el host o esperar a su Session-Timeout.")

if __name__ == "__main__":
    if os.geteuid() != 0:
        sys.exit("Ejecutar como root (sudo -i).")
    ap = argparse.ArgumentParser()
    ap.add_argument("--horas", type=int, default=4, help="vigencia (1 a 12 h; el rol limita la sesión a 4 h)")
    ap.add_argument("--nombre", default="Invitado")
    ap.add_argument("--listar", action="store_true")
    ap.add_argument("--revocar", metavar="UID")
    a = ap.parse_args()
    e = env()
    if a.listar: listar()
    elif a.revocar: revocar(a.revocar, e)
    else:
        if not 1 <= a.horas <= 12: sys.exit("--horas debe estar entre 1 y 12")
        crear(a, e)
