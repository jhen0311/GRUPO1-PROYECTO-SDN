#!/usr/bin/env python3
"""
bateria_r2.py - Precisión de la restricción de R2 (AVZ08, sección 5.2), desde un host.

Intenta una conexión TCP a cada recurso del inventario y la compara con lo autorizado
para el usuario: los permisos de su rol más los servidores de los cursos en los que
está inscrito (acceso por curso):
  precisión = accesos no autorizados bloqueados / accesos no autorizados intentados
  alcance   = accesos autorizados que conectan / accesos autorizados intentados

Uso (en la CLI de Mininet, con la matriz exportada antes en /tmp/matriz.json):
  mininet> h1 python3 r2-autorizacion/tests/bateria_r2.py --usuario alumno.demo
"""
import argparse, json, socket, time

def intento(ip, puerto, timeout):
    try:
        s = socket.create_connection((ip, puerto), timeout)
        s.close()
        return True
    except OSError:
        return False

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--usuario", help="uid del usuario de la sesión (alumno.demo, docente.demo, ti.demo)")
    ap.add_argument("--rol", help="solo permisos del rol, sin acceso por curso")
    ap.add_argument("--matriz", default="/tmp/matriz.json")
    ap.add_argument("--intentos", type=int, default=5)
    ap.add_argument("--timeout", type=float, default=1.0)
    a = ap.parse_args()
    m = json.load(open(a.matriz))
    if a.usuario:
        u = m["usuarios"][a.usuario]
        rol, cursos = u["rol"], set(u["cursos"])
    elif a.rol:
        rol, cursos = a.rol, set()
    else:
        ap.error("indica --usuario (recomendado) o --rol")
    permitidos = set(m["permisos"].get(rol, [])) | cursos
    nb = nnb = na = nac = 0
    print(f"{a.usuario or 'rol'} ({rol}): {len(permitidos)} recursos autorizados de {len(m['recursos'])}"
          f" ({len(cursos)} por inscripción en cursos)\n")
    print(f"{'recurso':30} {'destino':20} {'esperado':9} {'conecta':>8}  resultado")
    for r in m["recursos"]:
        if r["protocolo"] != "TCP":
            continue
        autorizado = r["id"] in permitidos
        exitos = sum(intento(r["ip"], r["puerto"], a.timeout) for _ in range(a.intentos))
        if autorizado:
            na += a.intentos; nac += exitos
            res = "OK" if exitos == a.intentos else "FALLA (debía conectar)"
        else:
            nnb += a.intentos; nb += a.intentos - exitos
            res = "OK" if exitos == 0 else "FALLA (no debía conectar)"
        print(f"{r['nombre'][:30]:30} {r['ip']+':'+str(r['puerto']):20} "
              f"{'permitir' if autorizado else 'bloquear':9} {exitos:>3}/{a.intentos:<3}  {res}")
    print()
    if nnb:
        print(f"Precisión (no autorizados bloqueados): {nb}/{nnb} = {100*nb/nnb:.1f} %")
    if na:
        print(f"Alcance (autorizados que conectan):    {nac}/{na} = {100*nac/na:.1f} %")

if __name__ == "__main__":
    main()
