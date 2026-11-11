#!/usr/bin/env bash
# =====================================================================
# Configura el Open vSwitch de una VM switch del piloto (LAB6, Tablas 2 y 3):
#   - bridge br0 con datapath-id fijo, OpenFlow 1.3 y fail_mode=secure
#   - puertos de datos con número OpenFlow fijo (ofport_request), según topologia_slice.json
#   - interfaz LOCAL con su IP del plano de control (172.16.0.2x/16)
#   - set-controller a las 3 instancias de ONOS (control in-band: el tráfico OpenFlow usa los
#     mismos enlaces que los datos; OVS lo deja pasar con sus reglas ocultas de in-band)
# La interfaz de gestión (ens3, 192.168.100.0/24) queda fuera del bridge.
#
# Uso (como root en la VM del switch, desde la raíz del repositorio):
#   bash comun/topo/slice/ovs_switch.sh SW-A1          # o SW-A2, SW-C1, SW-C2
#   bash comun/topo/slice/ovs_switch.sh SW-A1 --ver    # solo muestra lo que haría
# =====================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
source "$RAIZ/comun/config/slice.env"
SW=${1:?Uso: ovs_switch.sh SW-A1|SW-A2|SW-C1|SW-C2 [--ver]}
VER=${2:-}

read -r DPID IP PUERTOS < <(python3 - "$RAIZ/comun/config/topologia_slice.json" "$SW" <<'PY'
import json, sys
t = json.load(open(sys.argv[1]))["switches"][sys.argv[2]]
p = ",".join(f"{n}={v['nic']}" for n, v in sorted(t["puertos"].items(), key=lambda x: int(x[0])))
print(t["dpid"].replace("of:", ""), t["ip"], p)
PY
)
CTRL="tcp:${IP_ONOS_1}:6653 tcp:${IP_ONOS_2}:6653 tcp:${IP_ONOS_3}:6653"
echo "$SW: dpid $DPID, LOCAL $IP/16, puertos $PUERTOS"
[ "$VER" = "--ver" ] && exit 0

ovs-vsctl --if-exists del-br br0
ovs-vsctl add-br br0 -- set bridge br0 other-config:datapath-id="$DPID" \
  protocols=OpenFlow13 fail_mode=secure other-config:disable-in-band=false
IFS=, read -ra LISTA <<< "$PUERTOS"
for par in "${LISTA[@]}"; do
  n=${par%%=*}; nic=${par#*=}
  ip addr flush dev "$nic"
  ip link set "$nic" up
  ovs-vsctl add-port br0 "$nic" -- set interface "$nic" ofport_request="$n"
done
ip addr replace "$IP/16" dev br0
ip link set br0 up
ovs-vsctl set-controller br0 $CTRL
# Sondas de inactividad más cortas: si cae una instancia, el switch lo nota en ~10 s
for c in $CTRL; do
  ovs-vsctl set controller "$(ovs-vsctl --bare --columns=_uuid find controller target="\"$c\"")" \
    inactivity_probe=5000 max_backoff=2000 2>/dev/null || true
done
sleep 3
ovs-vsctl show
ovs-ofctl -O OpenFlow13 show br0 | grep -E '^ [0-9]+\(' || true
echo "Controladores: (is_connected=true cuando ONOS acepta la sesión)"
ovs-vsctl --columns=target,is_connected,role list controller
