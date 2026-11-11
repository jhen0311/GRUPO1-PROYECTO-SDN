#!/usr/bin/env python3
"""
gen_usuarios.py - Cuentas de prueba del piloto: N usuarios por cada rol de acceso (N = 20).

  ALUMNO          alumno01..   rol base ALUMNO, inscritos como ALUMNO en un curso
  JP              jp01..       rol base ALUMNO + inscripción JP en un curso (rol efectivo JP)
  DOCENTE         docente01..  rol base DOCENTE, inscritos como DOCENTE en un curso
  ADMINISTRATIVO  administrativo01..
  TI              ti01..
  INVITADO        invitado01.. con vencimiento al fin del ciclo (usuario.vence_en)
  sancionados     sancion01..  rol base ALUMNO con sanción vigente (incidente N3 + cuarentena),
                               para probar el rol CUARENTENA
Los cursos se reparten en ronda (TEL354, TEL141, CUR03), así cada curso tiene usuarios con
reglas 110. Genera dos archivos que deben cargarse juntos (la contraseña vive en OpenLDAP y el
rol en PostgreSQL):
  comun/db/05_usuarios_piloto.sql                         (lo carga setup_db.sh)
  r1-identidad/servicios/ldap/usuarios_piloto.txt         (lo carga setup_ldap.sh o agregar_usuarios.sh)
El SQL es idempotente (ON CONFLICT / NOT EXISTS): se puede volver a ejecutar sobre una base cargada.

Uso (en cualquier VM con el repositorio):
  python3 comun/db/gen_usuarios.py                 # 20 por rol
  python3 comun/db/gen_usuarios.py --n 30 --dias-sancion 60
"""
import argparse, os

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CURSOS = ["TEL354", "TEL141", "CUR03"]
# prefijo de uid, rol base, tipo de inscripción (o None), contraseña, prefijo de código, nombre
GRUPOS = [
    ("alumno",         "ALUMNO",         "ALUMNO",  "Alumno2026!",         "26AL", "Alumno de prueba"),
    ("jp",             "ALUMNO",         "JP",      "Jp2026!",             "26JP", "Jefe de practica de prueba"),
    ("docente",        "DOCENTE",        "DOCENTE", "Docente2026!",        "DOC",   "Docente de prueba"),
    ("administrativo", "ADMINISTRATIVO", None,      "Administrativo2026!", "ADM",  "Administrativo de prueba"),
    ("ti",             "TI",             None,      "TI2026!",             "TIC",  "Analista de TI de prueba"),
    ("invitado",       "INVITADO",       None,      "Invitado2026!",       "INV",  "Invitado de prueba"),
    ("sancion",        "ALUMNO",         "ALUMNO",  "Sancion2026!",        "26SA", "Alumno sancionado de prueba"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20, help="usuarios por rol (máximo 99)")
    ap.add_argument("--dias-sancion", type=int, default=30, help="duración de la sanción de los sancionados")
    a = ap.parse_args()
    if not 1 <= a.n <= 99:
        ap.error("--n debe estar entre 1 y 99")

    sql = [f"-- Generado por comun/db/gen_usuarios.py --n {a.n} --dias-sancion {a.dias_sancion}",
           "-- Cuentas de prueba del piloto (OpenLDAP: r1-identidad/servicios/ldap/usuarios_piloto.txt)",
           "BEGIN;"]
    ldap = [f"# Generado por comun/db/gen_usuarios.py --n {a.n}  (uid:nombre:contraseña)"]
    for pref, rol, tipo, clave, cod, nombre in GRUPOS:
        sql.append(f"\n-- {pref}01..{pref}{a.n:02d} (rol base {rol}{', inscripción ' + tipo if tipo else ''})")
        filas = []
        for i in range(1, a.n + 1):
            uid = f"{pref}{i:02d}"
            vence = "(SELECT fin FROM ciclo WHERE codigo = '2026-2')" if rol == "INVITADO" else "NULL"
            filas.append(f" ('{cod}{i:03d}', '{uid}', '{nombre} {i:02d}', "
                         f"(SELECT id FROM rol WHERE nombre = '{rol}'), 'ACTIVO', {vence})")
            ldap.append(f"{uid}:{nombre} {i:02d}:{clave}")
        sql.append("INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES\n"
                   + ",\n".join(filas) + "\nON CONFLICT DO NOTHING;")
        if tipo:
            ins = []
            for i in range(1, a.n + 1):
                curso = CURSOS[(i - 1) % len(CURSOS)]
                ins.append(f" ((SELECT id FROM usuario WHERE uid_ldap = '{pref}{i:02d}'), "
                           f"(SELECT id FROM curso WHERE codigo = '{curso}'), "
                           f"(SELECT id FROM ciclo WHERE codigo = '2026-2'), '{tipo}')")
            sql.append("INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) VALUES\n"
                       + ",\n".join(ins) + "\nON CONFLICT DO NOTHING;")

    # Sanción vigente: la cuarentena exige un incidente, y el incidente una sesión
    sql.append(f"""
-- Sanción vigente de sancion01..sancion{a.n:02d}: equipo, sesión cerrada, incidente N3 y cuarentena
DO $$
DECLARE u RECORD; d INTEGER; s BIGINT; i BIGINT;
BEGIN
  FOR u IN SELECT id, uid_ldap, right(uid_ldap, 2)::int AS n FROM usuario
           WHERE uid_ldap LIKE 'sancion__' AND uid_ldap <> 'sancion.demo' LOOP
    CONTINUE WHEN EXISTS (SELECT 1 FROM cuarentena q WHERE q.usuario_id = u.id AND q.fin > now());
    INSERT INTO dispositivo (mac, hostname, switch_dpid, puerto)
      VALUES (('02:5a:00:00:00:' || lpad(to_hex(u.n), 2, '0'))::macaddr, u.uid_ldap || '-pc',
              'of:0000000000000002', 3)
      ON CONFLICT (mac) DO UPDATE SET hostname = EXCLUDED.hostname RETURNING id INTO d;
    INSERT INTO sesion (usuario_id, dispositivo_id, switch_dpid, puerto, rol_efectivo_id, ip, vlan_id,
                        estado, inicio, fin, motivo_cierre)
      VALUES (u.id, d, 'of:0000000000000002', 3, (SELECT id FROM rol WHERE nombre = 'ALUMNO'),
              ('10.18.1.' || u.n)::inet, 102, 'CERRADA', now() - interval '2 hours',
              now() - interval '1 hour', 'CUARENTENA') RETURNING id INTO s;
    INSERT INTO incidente (sesion_id, requisito, tipo_ataque, fuente, severidad, accion, evidencia,
                           detectado_en, expira_en)
      VALUES (s, 'R3', 'SUPLANTACION', 'SIM', 'Fuerte', 'N3',
              '{{"nota": "sanción de prueba (gen_usuarios.py)", "criticidad": "CRITICO", "cierre": "CONFIRMACION"}}',
              now() - interval '90 minutes', now() - interval '1 hour') RETURNING id INTO i;
    INSERT INTO cuarentena (usuario_id, incidente_id, inicio, fin)
      VALUES (u.id, i, now() - interval '1 hour', now() + interval '{a.dias_sancion} days');
  END LOOP;
END $$;
COMMIT;""")

    ruta_sql = os.path.join(RAIZ, "comun", "db", "05_usuarios_piloto.sql")
    ruta_ldap = os.path.join(RAIZ, "r1-identidad", "servicios", "ldap", "usuarios_piloto.txt")
    open(ruta_sql, "w", encoding="utf-8").write("\n".join(sql) + "\n")
    open(ruta_ldap, "w", encoding="utf-8").write("\n".join(ldap) + "\n")
    print(f"{a.n * len(GRUPOS)} cuentas: {ruta_sql} y {ruta_ldap}")


if __name__ == "__main__":
    main()
