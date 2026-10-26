#!/usr/bin/env bash
# =====================================================================
# Cierre de sesión de red: EAPOL-Logoff (M1 cierra la sesión y retira
# sus reglas), liberación del lease DHCP y limpieza de la interfaz.
# Uso, dentro del host:   bash r1-identidad/host/logout.sh [interfaz] [--silencioso]
# =====================================================================
set -u
IF="${1:-$(ip -o link show | awk -F': ' '$2 != "lo" {sub(/@.*/, "", $2); print $2; exit}')}"
[ "$IF" = "--silencioso" ] && IF="$(ip -o link show | awk -F': ' '$2 != "lo" {sub(/@.*/, "", $2); print $2; exit}')"
SILENCIO=0; [[ " $* " == *" --silencioso "* ]] && SILENCIO=1
RUN=/run/tel354
CTRL=$RUN/wpa-ctrl

if [ -f "/run/dhclient-$IF.pid" ]; then
  timeout 5 dhclient -r -lf "/run/dhclient-$IF.leases" -pf "/run/dhclient-$IF.pid" "$IF" >/dev/null 2>&1
  rm -f "/run/dhclient-$IF.pid"
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
ip -4 addr flush dev "$IF" 2>/dev/null
ip neigh flush dev "$IF" 2>/dev/null   # como al desconectar el cable: se olvida la MAC del gateway
[ $SILENCIO -eq 1 ] || echo "Sesión cerrada en $IF."
