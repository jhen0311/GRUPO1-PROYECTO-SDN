#!/usr/bin/env python3
"""
Levanta un servicio TCP por cada fila de la tabla `recurso` (AVZ07, Tabla 23),
en la IP y el puerto del diseño. Corre en el host svc, que tiene esas IP como alias.

- puertos web (443, 8181, 8000, 80): responde HTTP 200 con el nombre del recurso
- puerto 22: envía un banner tipo SSH
- otros (p. ej. 5432 de las BD de recursos): envía una línea con el nombre del recurso
La BD real del modelo (10.200.0.12:5432) no se toca: es PostgreSQL.

Uso (dentro de svc): python3 resources/recursos.py --json /run/tel354_recursos.json
"""
import argparse, json, socket, threading

WEB = {80, 443, 8000, 8080, 8181}

def atender(srv, nombre, puerto):
    while True:
        c, addr = srv.accept()
        try:
            c.settimeout(2)
            if puerto in WEB:
                try:
                    c.recv(1024)
                except socket.timeout:
                    pass
                cuerpo = f"{nombre}\n".encode()
                c.sendall(b"HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                          + f"Content-Length: {len(cuerpo)}\r\n\r\n".encode() + cuerpo)
            elif puerto == 22:
                c.sendall(f"SSH-2.0-TEL354-demo {nombre}\r\n".encode())
            else:
                c.sendall(f"{nombre}\n".encode())
        except OSError:
            pass
        finally:
            c.close()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default="/run/tel354_recursos.json",
                    help="exportación de la tabla recurso (la genera start_services.sh)")
    a = ap.parse_args()
    filas = [(r["nombre"], r["ip"], r["protocolo"], r["puerto"]) for r in json.load(open(a.json))]
    hilos = 0
    for nombre, ip, proto, puerto in filas:
        if proto != "TCP":
            print(f"[omitido] {nombre}: solo se simulan servicios TCP")
            continue
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((ip, puerto))
        except OSError as e:
            print(f"[error] {nombre} {ip}:{puerto}: {e}")
            continue
        s.listen(16)
        threading.Thread(target=atender, args=(s, nombre, puerto), daemon=True).start()
        hilos += 1
        print(f"[ok] {nombre:28} {ip}:{puerto}/{proto}")
    print(f"{hilos} servicios de recursos activos")
    threading.Event().wait()

if __name__ == "__main__":
    main()
