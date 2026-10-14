#!/usr/bin/env python3
"""
yaml2sql.py - Convierte la base YAML del laboratorio de Floodlight (database.yaml) al modelo
PostgreSQL del proyecto, para reutilizar sus datos en la demo.

Correspondencias:
  students         -> usuario (rol base ALUMNO) + línea para ldap/usuarios_demo.txt
  courses          -> curso; si state = ACTIVE, inscripcion (tipo ALUMNO, ciclo 2026-2)
  servers.services -> recurso (una fila por servicio); la IP 10.0.0.X pasa a <red>.X
  allowed_services -> permiso_rol (ALUMNO, recurso), solo de cursos ACTIVE

Diferencia de modelo: en el laboratorio el permiso era por curso; en el proyecto es por rol
(R2 proactivo). Un servicio permitido por un curso activo queda permitido a todo el rol
Alumno; la restricción por matrícula queda en la aplicación del curso (AVZ07, sección 9).

Uso: python3 tools/yaml2sql.py database.yaml --red 10.200.2 > db/04_seed_lab.sql 2> ldap/usuarios_lab.txt
"""
import argparse, re, sys, unicodedata
import yaml

def slug(nombre):
    s = unicodedata.normalize("NFKD", nombre).encode("ascii", "ignore").decode().lower().strip()
    return re.sub(r"[^a-z0-9]+", ".", s).strip(".")

def q(s):
    return "'" + str(s).replace("'", "''") + "'"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("yaml")
    ap.add_argument("--red", default="10.200.2", help="red /24 de los servidores de cursos")
    ap.add_argument("--ciclo", default="2026-2")
    a = ap.parse_args()
    d = yaml.safe_load(open(a.yaml))
    out = ["-- Generado por tools/yaml2sql.py a partir de " + a.yaml, "BEGIN;"]

    uids = {}
    for i, s in enumerate(d.get("students", []), 1):
        uid = slug(s["name"]); uids[s["name"]] = uid
        mac = s.get("mac", "")
        if not re.fullmatch(r"([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", mac or ""):
            out.append(f"-- aviso: MAC no válida para {s['name']} ({mac}); el dispositivo lo registrará M1")
        out.append(f"INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id) VALUES "
                   f"({q('LAB%03d' % i)}, {q(uid)}, {q(s['name'].strip())}, (SELECT id FROM rol WHERE nombre='ALUMNO')) "
                   f"ON CONFLICT (uid_ldap) DO NOTHING;")
        print(f"{uid}:{s['name'].strip()}:Lab2026-{i:02d}!", file=sys.stderr)

    srv = {s["name"]: s for s in d.get("servers", [])}
    def ip(nombre):
        return f"{a.red}.{srv[nombre]['ip'].split('.')[-1]}"
    for s in srv.values():
        for sv in s["services"]:
            out.append(f"INSERT INTO recurso (nombre, ip, protocolo, puerto) VALUES "
                       f"({q(s['name'] + ' (' + sv['name'] + ')')}, {q(ip(s['name']))}, {q(sv['protocol'].upper())}, {sv['port']}) "
                       f"ON CONFLICT (ip, protocolo, puerto) DO NOTHING;")

    for c in d.get("courses", []):
        out.append(f"INSERT INTO curso (codigo, nombre) VALUES ({q(c['code'])}, {q(c['name'])}) ON CONFLICT (codigo) DO NOTHING;")
        activo = str(c.get("state", "")).upper() == "ACTIVE"
        if not activo:
            out.append(f"-- {c['code']} está INACTIVE: no se crean inscripciones ni permisos")
            continue
        for st in c.get("students", []):
            out.append(f"INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) SELECT u.id, cu.id, ci.id, 'ALUMNO' "
                       f"FROM usuario u, curso cu, ciclo ci WHERE u.uid_ldap={q(uids[st])} AND cu.codigo={q(c['code'])} "
                       f"AND ci.codigo={q(a.ciclo)} ON CONFLICT DO NOTHING;")
        for ref in c.get("servers", []):
            for nombre_sv in ref.get("allowed_services", []):
                sv = next(x for x in srv[ref["name"]]["services"] if x["name"] == nombre_sv)
                cond = f"ip={q(ip(ref['name']))} AND protocolo={q(sv['protocol'].upper())} AND puerto={sv['port']}"
                out.append(f"UPDATE recurso SET curso_id=(SELECT id FROM curso WHERE codigo={q(c['code'])}) "
                           f"WHERE {cond} AND curso_id IS NULL;")
                out.append(f"INSERT INTO permiso_rol (rol_id, recurso_id) SELECT r.id, rc.id FROM rol r, recurso rc "
                           f"WHERE r.nombre='ALUMNO' AND rc.{cond.replace(' AND ', ' AND rc.')} ON CONFLICT DO NOTHING;")
    out.append("COMMIT;")
    print("\n".join(out))

if __name__ == "__main__":
    main()
