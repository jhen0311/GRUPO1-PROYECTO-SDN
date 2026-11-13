#!/usr/bin/env python3
"""Adaptador Suricata -> M4a. Lee eve.json, filtra las alertas y las envía a la REST de M4a.

Variables de entorno (todas opcionales):
  EVE        ruta de eve.json                     (/var/log/suricata/eve.json)
  ONOS_URLS  URLs separadas por comas, en orden de preferencia; la primera que responde gana
             slice:  http://127.0.0.1:8181/onos/campus-ids/v1/alertas
             piloto: http://172.16.0.13:8181/onos/campus-ids/v1/alertas,... (ONOS-3 primero)
  ONOS_AUTH  usuario:clave                        (onos:rocks)
  SENSORES   interfaz=nombre separados por comas  (ens4=slice  |  eth0=core2,eth1=core1)
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

EVE = os.environ.get("EVE", "/var/log/suricata/eve.json")
URLS = os.environ.get("ONOS_URLS", "http://127.0.0.1:8181/onos/campus-ids/v1/alertas").split(",")
AUTH = base64.b64encode(os.environ.get("ONOS_AUTH", "onos:rocks").encode()).decode()
SENSORES = dict(x.split("=") for x in os.environ.get("SENSORES", "ens4=slice").split(","))
LIMITE_POR_SEG = 20      # un flood no debe saturar la REST
DEDUP_SEG = 5            # la misma (sid, origen, destino) no se reenvía antes de este plazo


def seguir(ruta):
    """Equivalente a tail -F: reabre el archivo si logrotate lo reemplaza."""
    f = open(ruta, "r", encoding="utf-8", errors="replace")
    f.seek(0, os.SEEK_END)
    ino = os.fstat(f.fileno()).st_ino
    while True:
        linea = f.readline()
        if linea:
            yield linea
            continue
        time.sleep(0.5)
        try:
            if os.stat(ruta).st_ino != ino:
                f.close()
                f = open(ruta, "r", encoding="utf-8", errors="replace")
                ino = os.fstat(f.fileno()).st_ino
        except FileNotFoundError:
            pass


def normalizar(ev):
    a = ev["alert"]
    return {
        "src_ip": ev["src_ip"],
        "dst_ip": ev["dest_ip"],
        "dst_port": ev.get("dest_port", 0),
        "proto": ev.get("proto", "TCP").lower(),
        "sid": a["signature_id"],
        "categoria": a.get("category", ""),     # texto de la clasificación, no el nombre corto del classtype
        "firma": a.get("signature", ""),
        "severidad": a.get("severity", 3),
        "sensor": SENSORES.get(ev.get("in_iface", ""), ev.get("in_iface", "")),
    }


def enviar(p):
    """True si ONOS procesó la alerta (cualquier 2xx o 4xx). False si hay que reintentar."""
    cuerpo = json.dumps(p).encode()
    for url in URLS:
        req = urllib.request.Request(url, data=cuerpo, method="POST", headers={
            "Content-Type": "application/json", "Authorization": "Basic " + AUTH})
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                print("OK", r.status, r.read().decode(), p["sid"], p["src_ip"], flush=True)
                return True
        except urllib.error.HTTPError as e:
            if e.code < 500:                      # 400/401/403: reintentar no cambia nada
                print("RECHAZADA", e.code, p, file=sys.stderr, flush=True)
                return True
        except (urllib.error.URLError, OSError):
            pass                                  # probar la siguiente instancia
    return False


def main():
    vistos, cuenta, t0 = {}, 0, time.time()
    for linea in seguir(EVE):
        try:
            ev = json.loads(linea)
        except ValueError:
            continue
        if ev.get("event_type") != "alert":
            continue
        p = normalizar(ev)
        k = (p["sid"], p["src_ip"], p["dst_ip"])
        ahora = time.time()
        if ahora - vistos.get(k, 0) < DEDUP_SEG:
            continue
        vistos[k] = ahora
        if ahora - t0 >= 1:
            cuenta, t0 = 0, ahora
        cuenta += 1
        if cuenta > LIMITE_POR_SEG:
            continue
        for espera in (0, 1, 3):                  # reintentos con espera creciente
            time.sleep(espera)
            if enviar(p):
                break
        else:
            print("ONOS no respondió, alerta perdida:", p, file=sys.stderr, flush=True)
        if len(vistos) > 5000:
            vistos = {kk: v for kk, v in vistos.items() if ahora - v < DEDUP_SEG}


if __name__ == "__main__":
    main()
