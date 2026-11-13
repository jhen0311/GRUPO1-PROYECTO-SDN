#!/usr/bin/env bash
# =====================================================================
# Clúster de control del piloto en la VM Controlador (LAB6, Tabla 2):
# 3 nodos Atomix 3.1 y 3 instancias de ONOS 2.7 en contenedores Docker.
#
#   red interna 192.168.200.0/24  Atomix <-> ONOS (no sale al slice)
#   in-band (macvlan)             onos-1 172.16.0.11 y onos-2 172.16.0.12 por eth0 (SW-C1)
#                                 onos-3 172.16.0.13 por eth1 (SW-C2)
#
# Uso (como root en el Controlador, desde la raíz del repositorio):
#   bash comun/cluster/cluster.sh crear      # genera la configuración y levanta los 6 contenedores
#   bash comun/cluster/cluster.sh inband     # (re)conecta onos-1..3 a la red in-band (tras reiniciarlos)
#   bash comun/cluster/cluster.sh estado     # contenedores, nodos del clúster y switches
#   bash comun/cluster/cluster.sh borrar     # elimina contenedores y red interna
# Variables: IF_SWC1 (eth0) e IF_SWC2 (eth1) si las interfaces del Controlador tienen otro nombre.
# =====================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
source "$RAIZ/comun/config/slice.env"
IF_SWC1=${IF_SWC1:-eth0}
IF_SWC2=${IF_SWC2:-eth1}
GEN="$RAIZ/comun/cluster/generado"
RED=onos-int

pid() { docker inspect -f '{{.State.Pid}}' "$1"; }

# Interfaz macvlan dentro del contenedor (namespace de red del proceso), con IP y MAC fijas,
# ruta a los servidores (10.200.0.0/16) por el gateway virtual y su ARP estático.
inband_nodo() {   # inband_nodo <contenedor> <interfaz padre> <ip> <mac>
  local c=$1 padre=$2 ip=$3 mac=$4 p
  p=$(pid "$c")
  ip link del "mv-$c" 2>/dev/null || true
  nsenter -t "$p" -n ip link del inband 2>/dev/null || true
  ip link add "mv-$c" link "$padre" address "$mac" type macvlan mode bridge
  ip link set "mv-$c" netns "$p"
  nsenter -t "$p" -n ip link set "mv-$c" name inband
  nsenter -t "$p" -n ip addr add "$ip/16" dev inband
  nsenter -t "$p" -n ip link set inband up
  nsenter -t "$p" -n ip route replace 10.200.0.0/16 via 172.16.0.1 dev inband
  nsenter -t "$p" -n ip neigh replace 172.16.0.1 lladdr "$MAC_GW" dev inband nud permanent
  echo "  $c: inband $ip ($mac) sobre $padre"
}

inband() {
  ip link set "$IF_SWC1" up; ip link set "$IF_SWC2" up
  # El host Docker también usa la red in-band (172.16.0.10) para base_slice.py y psql
  ip addr replace "$IP_CLUSTER_HOST/16" dev "$IF_SWC1"
  ip route replace 10.200.0.0/16 via 172.16.0.1 dev "$IF_SWC1"
  ip neigh replace 172.16.0.1 lladdr "$MAC_GW" dev "$IF_SWC1" nud permanent
  inband_nodo onos-1 "$IF_SWC1" "$IP_ONOS_1" "$MAC_ONOS_1"
  inband_nodo onos-2 "$IF_SWC1" "$IP_ONOS_2" "$MAC_ONOS_2"
  inband_nodo onos-3 "$IF_SWC2" "$IP_ONOS_3" "$MAC_ONOS_3"
}

case "${1:-}" in
crear)
  python3 "$RAIZ/comun/cluster/gen_cluster.py" "$GEN"
  docker network inspect $RED >/dev/null 2>&1 || docker network create --subnet "$RED_DOCKER" $RED
  for i in 1 2 3; do
    eval ip_a=\$DOCKER_ATOMIX_$i
    docker rm -f atomix-$i >/dev/null 2>&1 || true
    docker run -d --name atomix-$i --hostname atomix-$i --net $RED --ip "$ip_a" --restart unless-stopped \
      -v "$GEN/atomix-$i.json:/opt/atomix/conf/atomix.json:ro" \
      "$IMG_ATOMIX" --config /opt/atomix/conf/atomix.json --ignore-resources >/dev/null
    echo "  atomix-$i en $ip_a"
  done
  sleep 10
  for i in 1 2 3; do
    eval ip_o=\$DOCKER_ONOS_$i
    docker rm -f onos-$i >/dev/null 2>&1 || true
    docker run -d --name onos-$i --hostname onos-$i --net $RED --ip "$ip_o" --restart unless-stopped \
      -e ONOS_APPS="drivers,openflow,gui2" \
      -v "$GEN/onos-$i.json:/root/onos/config/cluster.json:ro" \
      -v /etc/tel354/certs:/etc/tel354/certs:ro \
      "$IMG_ONOS" >/dev/null
    echo "  onos-$i en $ip_o"
  done
  sleep 5
  inband
  echo "Clúster creado. ONOS tarda 1-2 min en arrancar: bash comun/cluster/cluster.sh estado"
  ;;
inband)
  inband
  ;;
estado)
  docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | grep -E 'NAMES|onos|atomix'
  echo "--- nodos y switches (REST de onos-1)"
  curl -s -u "$ONOS_AUTH" "$ONOS_URL/cluster" | python3 -c \
    'import json,sys; [print("  ", n["id"], n["ip"], n["status"]) for n in json.load(sys.stdin)["nodes"]]' || true
  curl -s -u "$ONOS_AUTH" "$ONOS_URL/devices" | python3 -c \
    'import json,sys; [print("  ", d["id"], "disponible" if d["available"] else "NO disponible", d.get("annotations",{}).get("managementAddress","")) for d in json.load(sys.stdin)["devices"]]' || true
  curl -s -u "$ONOS_AUTH" "$ONOS_URL/mastership/of:0000000000000001/master" 2>/dev/null | sed 's/^/   master SW-A1: /'; echo
  ;;
borrar)
  for c in onos-1 onos-2 onos-3 atomix-1 atomix-2 atomix-3; do docker rm -f $c >/dev/null 2>&1 || true; done
  docker network rm $RED >/dev/null 2>&1 || true
  echo "Clúster eliminado."
  ;;
*)
  sed -n '2,20p' "$0"; exit 1 ;;
esac
