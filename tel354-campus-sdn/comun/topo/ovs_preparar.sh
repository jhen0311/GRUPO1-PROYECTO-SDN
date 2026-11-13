#!/usr/bin/env bash
# =====================================================================
# Deja un Open vSwitch ya creado listo para el modelo: datapath-id fijo
# (el de switch_acceso en la base de datos), OpenFlow 1.3, fail_mode=secure
# y el controlador indicado. No crea el bridge ni agrega puertos: sirve
# cuando el entorno ya los tiene (si no, usar comun/topo/slice/ovs_switch.sh).
# Uso (root en el switch):
#   bash ovs_preparar.sh <bridge> <dpid de 16 hex> tcp:<IP>:6653 [tcp:<IP>:6653 ...]
#   bash ovs_preparar.sh <bridge> --ver
# =====================================================================
set -euo pipefail
BR=${1:?Uso: ovs_preparar.sh <bridge> <dpid> <controlador...> | <bridge> --ver}
ver() {
  echo "bridge $BR: dpid $(ovs-vsctl get bridge "$BR" datapath-id | tr -d '"')," \
       "protocolos $(ovs-vsctl get bridge "$BR" protocols), fail_mode $(ovs-vsctl get bridge "$BR" fail_mode)"
  ovs-vsctl --columns=target,is_connected list controller
  ovs-ofctl -O OpenFlow13 show "$BR" | grep -E '^ [0-9]+\(' || true
}
[ "${2:-}" = "--ver" ] && { ver; exit 0; }
DPID=${2:?Falta el datapath-id}; shift 2
[[ "$DPID" =~ ^[0-9a-fA-F]{16}$ ]] || { echo "El datapath-id debe tener 16 dígitos hex"; exit 1; }
[ $# -ge 1 ] || { echo "Falta al menos un controlador (tcp:<IP>:6653)"; exit 1; }
ovs-vsctl br-exists "$BR" || { echo "No existe el bridge $BR (ovs-vsctl list-br)"; exit 1; }
ovs-vsctl set bridge "$BR" other-config:datapath-id="$DPID" protocols=OpenFlow13 fail_mode=secure
ovs-vsctl set-controller "$BR" "$@"
sleep 3
ver
