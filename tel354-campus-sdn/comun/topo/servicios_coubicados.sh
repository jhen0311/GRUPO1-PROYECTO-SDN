#!/usr/bin/env bash
# =====================================================================
# Perfil una_instancia.env: la interfaz de datos del controlador (enlace
# hacia el core) hace de todos los servidores del piloto. Recibe las IP
# 10.200.x.y de S-SERVICIOS, S-IDENTIDAD, S-RECURSOS y S-CURSOS, la ruta
# hacia los usuarios (10.0.0.0/8) por el gateway virtual 10.200.0.1 y su
# ARP estático (MAC_GW). La interfaz de control no se toca.
# Uso (root en el controlador, desde la raíz del repositorio):
#   bash comun/topo/servicios_coubicados.sh            # usa IF_DATOS del perfil
#   bash comun/topo/servicios_coubicados.sh ens5       # otra interfaz
#   bash comun/topo/servicios_coubicados.sh --quitar
# No es persistente: volver a ejecutarlo tras reiniciar el equipo.
# =====================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
source "$RAIZ/comun/config/${TEL354_ENV:-una_instancia.env}"
if [ "${1:-}" = "--quitar" ]; then
  ip addr flush dev "$IF_DATOS"; ip route del 10.0.0.0/8 2>/dev/null || true
  echo "IP de servicios retiradas de $IF_DATOS"; exit 0
fi
IF=${1:-$IF_DATOS}
[ -e "/sys/class/net/$IF" ] || { echo "No existe la interfaz $IF (ver: ip -br link)"; exit 1; }
ip addr flush dev "$IF"
ip link set "$IF" up
for ip in $IP_NAS $IP_GESTION $IP_KEA $IP_DNS $IP_RADIUS $IP_LDAP $IP_PG $IP_CAMPUS_VIRTUAL $IP_BIBLIOTECA \
          $IP_BD_ACADEMICA $IP_BD_ADMINISTRATIVA $IP_LAB_TEL354 $IP_NUBE_TEL141 $IP_CURSO3; do
  ip addr add "$ip/24" dev "$IF"
done
ip route replace 10.0.0.0/8 via 10.200.0.1 dev "$IF"
ip neigh replace 10.200.0.1 lladdr "$MAC_GW" dev "$IF" nud permanent
ip -br addr show dev "$IF"
echo "MAC de $IF (la usa base_auto.py): $(cat /sys/class/net/$IF/address)"
