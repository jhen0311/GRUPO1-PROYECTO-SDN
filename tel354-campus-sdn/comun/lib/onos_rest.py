"""
onos_rest.py - Cliente mínimo de la API REST de ONOS 1.13 y constructores de reglas.

Reutiliza la idea del laboratorio con Floodlight (POST de flows por REST), adaptada a
ONOS: las reglas se envían en lote a /onos/v1/flows?appId=... con tableId, selector
(criteria) y treatment (instructions) en el formato de los codecs de ONOS 1.13.
"""
import json, os, re
import requests

ONOS = os.environ.get("ONOS_URL", "http://127.0.0.1:8181/onos/v1")
AUTH = tuple(os.environ.get("ONOS_AUTH", "onos:rocks").split(":"))
APP = "tel354.demo"

# ----------------------------- topología Mininet ------------------------------
CORE = "of:0000000000000100"
CORE_PORT = {"edge": 1, "T": 2, "C": 3, 1: 4, 2: 5}           # 1 y 2 = pabellones
ACCESS = {1: "of:0000000000000001", 2: "of:0000000000000002"}  # pabellón -> dpid
UPLINK, HOST_PORTS = 1, (2, 3)
SVC_MAC = "00:00:00:c8:00:0a"
GW_MAC = "02:00:00:00:00:c8"
SERVICIOS = ["10.200.0.5", "10.200.0.10", "10.200.0.11", "10.200.0.12", "10.200.0.13", "10.200.0.14"]
ROLES = {"ALUMNO": 16, "JP": 32, "DOCENTE": 48, "ADMINISTRATIVO": 64, "TI": 80, "INVITADO": 96, "CUARENTENA": 112}

# ------------------------------- criterios ------------------------------------
def in_port(p):       return {"type": "IN_PORT", "port": p}
def eth_type(t):      return {"type": "ETH_TYPE", "ethType": t}
def eth_src(m):       return {"type": "ETH_SRC", "mac": m}
def eth_dst(m):       return {"type": "ETH_DST", "mac": m}
def vlan(v):          return {"type": "VLAN_VID", "vlanId": v}
def vlan_presente():   return {"type": "VLAN_VID", "vlanId": 4096}   # VlanId.ANY = OFPVID_PRESENT
def ip_proto(p):      return {"type": "IP_PROTO", "protocol": p}
def ipv4_src(n):      return {"type": "IPV4_SRC", "ip": n}
def ipv4_dst(n):      return {"type": "IPV4_DST", "ip": n}
def tcp_dst(p):       return {"type": "TCP_DST", "tcpPort": p}
def udp_dst(p):       return {"type": "UDP_DST", "udpPort": p}

# ----------------------------- instrucciones ----------------------------------
def output(p):        return {"type": "OUTPUT", "port": str(p)}
def table(t):         return {"type": "TABLE", "tableId": t}
def meter(m):         return {"type": "METER", "meterId": str(m)}
def push_vlan():      return {"type": "L2MODIFICATION", "subtype": "VLAN_PUSH"}
def set_vlan(v):      return {"type": "L2MODIFICATION", "subtype": "VLAN_ID", "vlanId": v}
def pop_vlan():       return {"type": "L2MODIFICATION", "subtype": "VLAN_POP"}
def set_eth_dst(m):   return {"type": "L2MODIFICATION", "subtype": "ETH_DST", "mac": m}
CONTROLLER = "CONTROLLER"

def regla(dev, tabla, prio, criterios, instrucciones):
    """Regla permanente. Una lista de instrucciones vacía equivale a drop."""
    return {"deviceId": dev, "tableId": tabla, "priority": prio, "isPermanent": True,
            "timeout": 0, "selector": {"criteria": criterios},
            "treatment": {"instructions": instrucciones}}

def l4_dst(protocolo, puerto):
    return [ip_proto(6), tcp_dst(puerto)] if protocolo == "TCP" else [ip_proto(17), udp_dst(puerto)]

# ---------------------------------- REST --------------------------------------
def post_flows(reglas, app=APP, dry=False):
    if dry:
        return len(reglas)
    r = requests.post(f"{ONOS}/flows?appId={app}", auth=AUTH,
                      json={"flows": reglas}, timeout=20)
    r.raise_for_status()
    return len(r.json().get("flows", []))

def borrar_app(app=APP):
    requests.delete(f"{ONOS}/flows/application/{app}", auth=AUTH, timeout=20)

def crear_meter(dev, unidad, rate, burst=None, dry=False):
    """unidad: PKTS_PER_SEC o KB_PER_SEC. Devuelve el meterId asignado por ONOS."""
    if dry:
        crear_meter.n = getattr(crear_meter, "n", 0) + 1
        return crear_meter.n
    banda = {"type": "DROP", "rate": rate, "burstSize": burst or rate}
    if burst:
        banda["burstSize"] = burst
    body = {"deviceId": dev, "unit": unidad, "burst": True, "bands": [banda]}
    r = requests.post(f"{ONOS}/meters/{dev}", auth=AUTH, json=body, timeout=10)
    if r.status_code >= 400:
        raise RuntimeError(f"ONOS rechazó el meter {body}: {r.status_code} {r.text}")
    loc = r.headers.get("Location", "")
    m = re.search(r"/(\d+)$", loc)
    if not m:
        raise RuntimeError(f"ONOS no devolvió el id del meter: {r.status_code} {loc} {r.text}")
    return int(m.group(1))

def borrar_meters(dev):
    r = requests.get(f"{ONOS}/meters/{dev}", auth=AUTH, timeout=10)
    for mt in r.json().get("meters", []):      # en la demo todos los meters son de este script
        requests.delete(f"{ONOS}/meters/{dev}/{mt['id']}", auth=AUTH, timeout=10)

def configurar(componente, propiedades):
    requests.post(f"{ONOS}/configuration/{componente}", auth=AUTH,
                  json=propiedades, timeout=10)
