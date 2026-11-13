#!/usr/bin/env bash
# =====================================================================
# Red de datos de una VM servidor del piloto (LAB6, Tabla 2): IP en su
# interfaz de datos, rutas hacia los usuarios (10.0.0.0/8) y el plano de
# control (172.16.0.0/16) por el gateway virtual 10.200.x.1, y su ARP
# estático (02:00:00:00:00:c8): en el core no hay router, las reglas de
# SW-C2 reescriben la MAC de destino (AVZ07, sección 5.5).
# La interfaz de gestión (ens3) no se toca.
#
# Uso (como root en la VM, desde la raíz del repositorio):
#   bash comun/topo/slice/red_servidor.sh S-IDENTIDAD ens4
#   (S-SERVICIOS, S-IDENTIDAD, S-RECURSOS, S-CURSOS o IDS-Suricata)
# No es persistente: volver a ejecutarlo tras reiniciar la VM.
# =====================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
source "$RAIZ/comun/config/slice.env"
SRV=${1:?Uso: red_servidor.sh <servidor> <interfaz>}
IF=${2:?Falta la interfaz de datos (ver: ip -br link)}
IPS=$(python3 -c "import json,sys; print(' '.join(json.load(open('$RAIZ/comun/config/topologia_slice.json'))['servidores']['$SRV']['ips']))")
ip link set "$IF" up
for ip in $IPS; do
  ip addr replace "$ip/24" dev "$IF"
done
PRIMERA=${IPS%% *}
GW="${PRIMERA%.*}.1"
ip route replace 10.0.0.0/8 via "$GW" dev "$IF"
ip route replace 172.16.0.0/16 via "$GW" dev "$IF"
ip neigh replace "$GW" lladdr "$MAC_GW" dev "$IF" nud permanent
echo "$SRV: $IPS en $IF; 10.0.0.0/8 y 172.16.0.0/16 por $GW ($MAC_GW)"
echo "MAC de $IF para topologia_slice.json: $(cat /sys/class/net/$IF/address)"
