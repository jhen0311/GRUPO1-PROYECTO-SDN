# =====================================================================
# Reglas comunes de login.sh, logout.sh y wpa_accion.sh sobre las
# interfaces del equipo (se carga con source; no se ejecuta).
#
# ens3 es la interfaz de gestión de las VM y no se toca: ningún script de
# esta carpeta la limpia, la configura ni ejecuta 802.1X o DHCP sobre ella.
# Hay cinco barreras independientes:
#   1. interfaz_datos nunca la elige por defecto.
#   2. interfaz_datos la rechaza si se pasa como argumento.
#   3. limpiar_ipv4 (el único punto que borra direcciones) también la rechaza.
#   4. El hook de dhclient impide que el lease de la interfaz de datos
#      reemplace la ruta por defecto de gestión.
#   5. comprobar_gestion compara la IPv4 de ens3 antes y después de cada
#      script y avisa si cambió.
#
# Interfaz de la sesión (interfaz_datos):
#   - La que se pasa como argumento.
#   - Sin argumento, la única interfaz de túnel vx-* del equipo.
#   - Sin túnel, la primera que no sea lo ni ens3 (en Mininet, hN-eth0).
#   Con túnel solo se acepta vx-*: limpiar su portadora (ens4) lo deja caído.
# =====================================================================
readonly IF_GESTION=ens3
RAIZ_SIS="${TEL354_RAIZ_PRUEBA:-}"      # solo para probar_host.sh: prefijo de /run y /etc

interfaz_datos() {
  local pedida="${1:-}" vx
  vx=$(ip -o link show | awk -F': ' '{sub(/@.*/, "", $2)} $2 ~ /^vx-/ {print $2}' | xargs)
  if [ -z "$pedida" ]; then
    case $(wc -w <<<"$vx") in
      1) pedida=$vx ;;
      0) pedida=$(ip -o link show | awk -F': ' -v g="$IF_GESTION" \
                  '{sub(/@.*/, "", $2)} $2 != "lo" && $2 != g {print $2; exit}') ;;
      *) echo "Este equipo tiene varios túneles ($vx): indicar cuál." >&2
         return 1 ;;
    esac
  fi
  if [ "$pedida" = "$IF_GESTION" ]; then
    echo "$IF_GESTION es la interfaz de gestión y no se toca." \
         "Indicar la de datos (p. ej., vx-sw2 o ens4)." >&2
    return 1
  fi
  if [ -z "$pedida" ]; then
    echo "No hay interfaz de datos en este equipo." >&2
    return 1
  fi
  if [ -n "$vx" ] && [[ "$pedida" != vx-* ]]; then
    echo "Este equipo usa el túnel $vx: la sesión va por el túnel, no por $pedida." >&2
    return 1
  fi
  echo "$pedida"
}

# Único punto que borra direcciones IPv4 de una interfaz.
limpiar_ipv4() {
  if [ "${1:-}" = "$IF_GESTION" ] || [ -z "${1:-}" ]; then
    echo "limpiar_ipv4: se rechaza actuar sobre '${1:-}' ($IF_GESTION no se toca)." >&2
    return 1
  fi
  ip -4 addr flush dev "$1" 2>/dev/null
}

# IPv4 de la interfaz de gestión (vacío si el equipo no la tiene, como en Mininet). Solo lee.
ipv4_gestion() {
  ip -4 -o addr show dev "$IF_GESTION" 2>/dev/null | awk '{print $4}' | xargs
}

# Compara la IPv4 de gestión con la que había al empezar ($1) y avisa si cambió.
comprobar_gestion() {
  local ahora
  ahora=$(ipv4_gestion)
  if [ "$ahora" != "${1:-}" ]; then
    echo "AVISO: la IPv4 de $IF_GESTION cambió durante el script (antes: '${1:-}'; ahora: '$ahora')." >&2
    echo "       Recuperarla con: networkctl reconfigure $IF_GESTION   (o: dhclient -v $IF_GESTION)" >&2
    return 1
  fi
  return 0
}

# Aviso si el equipo tiene interfaz de gestión pero ya está sin IPv4 (no lo causó este script).
avisar_gestion_caida() {
  if ip -o link show dev "$IF_GESTION" >/dev/null 2>&1 && [ -z "$(ipv4_gestion)" ]; then
    echo "AVISO: $IF_GESTION no tiene IPv4: el equipo está sin gestión (SSH)." >&2
    echo "       Recuperarla con: networkctl reconfigure $IF_GESTION   (o: dhclient -v $IF_GESTION)" >&2
  fi
}
