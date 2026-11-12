#!/usr/bin/env bash
# =====================================================================
# Cierre de sesión de red: EAPOL-Logoff (M1 cierra la sesión y retira
# sus reglas), liberación del lease DHCP y limpieza de la interfaz.
# Uso, dentro del host:   bash r1-identidad/host/logout.sh [interfaz] [--silencioso]
# Sin interfaz se usa la de túnel vx-* del equipo. La de gestión (ens3) nunca se toca y, con túnel,
# tampoco su portadora (interfaz.sh).
# =====================================================================
set -u
AQUI="$(cd "$(dirname "$0")" && pwd)"
source "$AQUI/interfaz.sh"
ARG="${1:-}"; [ "$ARG" = "--silencioso" ] && ARG=""
IF=$(interfaz_datos "$ARG") || exit 1
GESTION_ANTES=$(ipv4_gestion)
SILENCIO=0; [[ " $* " == *" --silencioso "* ]] && SILENCIO=1
RUN=$RAIZ_SIS/run/tel354
CTRL=$RUN/wpa-ctrl

if [ -f "$RUN/wpa-cli-$IF.pid" ]; then                  # acción de P8 (login.sh)
  kill "$(cat "$RUN/wpa-cli-$IF.pid")" 2>/dev/null; rm -f "$RUN/wpa-cli-$IF.pid"
fi
if [ -f "$RAIZ_SIS/run/dhclient-$IF.pid" ]; then
  timeout 5 dhclient -r -lf "$RAIZ_SIS/run/dhclient-$IF.leases" -pf "$RAIZ_SIS/run/dhclient-$IF.pid" "$IF" >/dev/null 2>&1
  rm -f "$RAIZ_SIS/run/dhclient-$IF.pid"
fi
if [ -S "$CTRL/$IF" ]; then
  wpa_cli -p "$CTRL" -i "$IF" logoff >/dev/null 2>&1     # EAPOL-Logoff
  sleep 0.3
  wpa_cli -p "$CTRL" -i "$IF" terminate >/dev/null 2>&1
fi
if [ -f "$RUN/wpa-$IF.pid" ]; then
  P=$(cat "$RUN/wpa-$IF.pid"); kill "$P" 2>/dev/null
  for _ in $(seq 20); do kill -0 "$P" 2>/dev/null || break; sleep 0.1; done   # esperar a que termine
fi
rm -f "$RUN/wpa-$IF.pid" "$CTRL/$IF"
limpiar_ipv4 "$IF"
ip neigh flush dev "$IF" 2>/dev/null   # como al desconectar el cable: se olvida la MAC del gateway
comprobar_gestion "$GESTION_ANTES"
[ $SILENCIO -eq 1 ] || echo "Sesión cerrada en $IF."
