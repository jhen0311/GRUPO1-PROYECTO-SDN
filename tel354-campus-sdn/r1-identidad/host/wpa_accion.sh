#!/usr/bin/env bash
# =====================================================================
# Acción de wpa_cli (-a) en el host (P8 de M1 v1.1): cuando 802.1X se
# vuelve a completar sin que el usuario haga nada (M1 rebotó el puerto
# y envió un EAP-Request/Identity, p. ej. al pasar a Cuarentena), el
# host libera su IP y pide otra, como un cliente real al recuperar el
# enlace. Así Kea le entrega la IP del rol nuevo (10.‹112+N›.x.y).
# La instala login.sh; no se ejecuta a mano. Argumentos: interfaz evento
# =====================================================================
IF="$1"; EVENTO="$2"
LOG=/run/tel354/wpa-accion-$IF.log
case "$EVENTO" in
CONNECTED)
  echo "$(date '+%F %T') 802.1X completado de nuevo: se renueva la IP" >> "$LOG"
  timeout 5 dhclient -r -lf "/run/dhclient-$IF.leases" -pf "/run/dhclient-$IF.pid" "$IF" >/dev/null 2>&1
  ip -4 addr flush dev "$IF"
  timeout 30 dhclient -1 -lf "/run/dhclient-$IF.leases" -pf "/run/dhclient-$IF.pid" "$IF" >> "$LOG" 2>&1
  GW=$(awk '/option routers/ {gsub(";",""); g=$3} END {print g}' "/run/dhclient-$IF.leases" 2>/dev/null)
  if [ -n "$GW" ] && ! ip -4 route show default | grep -q "dev $IF"; then
    ip route replace 10.0.0.0/8 via "$GW" dev "$IF"
  fi
  ip -br -4 addr show dev "$IF" >> "$LOG"
  ;;
DISCONNECTED)
  echo "$(date '+%F %T') 802.1X desconectado (puerto rebotado o sesión cerrada)" >> "$LOG"
  ;;
esac
