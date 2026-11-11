#!/usr/bin/env python3
"""
Genera la configuración del clúster ONOS 2.7 + Atomix 3.1 del piloto (LAB6, sección 1.1), con el
mismo formato que onos-gen-config: atomix-<i>.json para cada nodo Atomix y onos-<i>.json
(cluster.json) para cada instancia de ONOS. Lee las direcciones de comun/config/slice.env.

Uso (en el Controlador):  python3 comun/cluster/gen_cluster.py [directorio_salida]
"""
import json, os, re, sys

AQUI = os.path.dirname(os.path.abspath(__file__))
ENV = os.path.join(AQUI, "..", "config", "slice.env")

def leer_env(ruta):
    v = {}
    for linea in open(ruta, encoding="utf-8"):
        m = re.match(r"^\s*([A-Z0-9_]+)=([^#\s]*)", linea)
        if m:
            v[m.group(1)] = m.group(2)
    return v

def main():
    e = leer_env(ENV)
    salida = sys.argv[1] if len(sys.argv) > 1 else os.path.join(AQUI, "generado")
    os.makedirs(salida, exist_ok=True)
    atomix = [{"id": f"atomix-{i}", "address": f"{e[f'DOCKER_ATOMIX_{i}']}:5679"} for i in (1, 2, 3)]
    miembros = [a["id"] for a in atomix]
    for i, a in enumerate(atomix, 1):
        conf = {
            "cluster": {"clusterId": "onos", "node": a,
                        "discovery": {"type": "bootstrap", "nodes": atomix}},
            "managementGroup": {"type": "raft", "partitions": 1, "partitionSize": 3,
                                "members": miembros, "storage": {"level": "disk"}},
            "partitionGroups": {"raft": {"type": "raft", "partitions": 3, "partitionSize": 3,
                                         "members": miembros, "storage": {"level": "disk"}}}}
        json.dump(conf, open(os.path.join(salida, f"atomix-{i}.json"), "w"), indent=2)
    for i in (1, 2, 3):
        conf = {"name": "onos",
                "node": {"id": f"onos-{i}", "ip": e[f"DOCKER_ONOS_{i}"], "port": 9876},
                "storage": [{"id": a["id"], "ip": a["address"].split(":")[0], "port": 5679} for a in atomix]}
        json.dump(conf, open(os.path.join(salida, f"onos-{i}.json"), "w"), indent=2)
    print(f"Configuración del clúster en {salida}: " + ", ".join(sorted(os.listdir(salida))))

if __name__ == "__main__":
    main()
