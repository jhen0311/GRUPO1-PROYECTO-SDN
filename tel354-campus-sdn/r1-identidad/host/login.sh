#!/usr/bin/env bash
# =====================================================================
# Inicio de sesión de red de un equipo del campus (lo que haría el
# cliente 802.1X del sistema operativo al conectar el cable).
#
#   1. Pide usuario y contraseña (la contraseña no se muestra ni se
#      escribe en disco: va al supplicant por su socket de control).
#   2. wpa_supplicant envía EAPOL-Start; M1 responde y el túnel
#      EAP-TTLS se arma de extremo a extremo con FreeRADIUS, que
#      prueba su identidad (domain_suffix_match) con la CA de la demo.
#   3. Con EAP-Success, dhclient pide la IP: M1 relaya el DHCP con la
#      opción 82 y Kea asigna una IP del pool del rol y del pabellón.
#
# Uso, dentro del host (ver entrar.sh):   bash r1-identidad/host/login.sh [interfaz]
# =====================================================================
set -u
AQUI="$(cd "$(dirname "$0")" && pwd)"
IF="${1:-$(ip -o link show | awk -F': ' '$2 != "lo" {sub(/@.*/, "", $2); print $2; exit}')}"
RUN=/run/tel354
CTRL=$RUN/wpa-ctrl
CA=/etc/tel354/certs/ca.pem
mkdir -p "$RUN" && chmod 700 "$RUN"

[ -n "$IF" ] && ip link show dev "$IF" >/dev/null 2>&1 || { echo "No hay interfaz de red en este equipo."; exit 1; }
[ -r "$CA" ] || { echo "Falta $CA (certificado de la CA del campus)."; exit 1; }

# Estado anterior de esta interfaz
bash "$AQUI/logout.sh" "$IF" --silencioso

cat > "$RUN/wpa-$IF.conf" <<EOF
ctrl_interface=$CTRL
ap_scan=0
network={
	key_mgmt=IEEE8021X
	eap=TTLS
	anonymous_identity="anonymous"
	phase2="auth=PAP"
	ca_cert="$CA"
	domain_suffix_match="radius.campus.pucp.edu.pe"
	eapol_flags=0
	disabled=1
}
EOF
chmod 600 "$RUN/wpa-$IF.conf"

echo "=== Red del campus · inicio de sesión ($IF) ==="
read -r -p "Usuario: " USUARIO
read -r -s -p "Contraseña: " CLAVE; echo
[ -n "$USUARIO" ] && [ -n "$CLAVE" ] || { echo "Usuario y contraseña son obligatorios."; exit 1; }

wpa_supplicant -B -D wired -i "$IF" -c "$RUN/wpa-$IF.conf" -P "$RUN/wpa-$IF.pid" \
               -f "$RUN/wpa-$IF.log" -t >/dev/null 2>&1 || { echo "No se pudo iniciar wpa_supplicant."; exit 1; }
for _ in $(seq 20); do [ -S "$CTRL/$IF" ] && break; sleep 0.1; done

# La contraseña se entrega por la entrada estándar de wpa_cli: no aparece en 'ps' ni en disco
{
  printf 'set_network 0 identity "%s"\n' "$USUARIO"
  printf 'set_network 0 password "%s"\n' "$CLAVE"
  printf 'enable_network 0\n'
} | wpa_cli -p "$CTRL" -i "$IF" >/dev/null
unset CLAVE

printf 'Autenticando (802.1X / EAP-TTLS)'
ESTADO=""
# Sondeo cada 0,1 s (antes 0,5 s): la espera del script no debe sumarse a la latencia medida.
# 200 × 0,1 s = 20 s de plazo, igual que antes; se imprime un punto cada 0,5 s.
for i in $(seq 200); do
  ST=$(wpa_cli -p "$CTRL" -i "$IF" status 2>/dev/null)
  if grep -q "^suppPortStatus=Authorized" <<<"$ST"; then ESTADO=OK; break; fi
  if grep -q "^EAP state=FAILURE" <<<"$ST"; then ESTADO=RECHAZO; break; fi
  [ $((i % 5)) -eq 0 ] && printf '.'
  sleep 0.1
done
echo
case "$ESTADO" in
  OK)      echo "Autenticación correcta: $USUARIO" ;;
  RECHAZO) echo "Credenciales rechazadas."; bash "$AQUI/logout.sh" "$IF" --silencioso; exit 2 ;;
  *)       echo "Sin respuesta del autenticador (¿M1 activo en ONOS?). Registro: $RUN/wpa-$IF.log"
           bash "$AQUI/logout.sh" "$IF" --silencioso; exit 3 ;;
esac

# dhclient con archivos propios de la interfaz (los hosts de Mininet comparten el disco de la VM).
# Rutas permitidas por el perfil AppArmor de dhclient; el hook evita que toque /etc/resolv.conf.
HOOK=/etc/dhcp/dhclient-enter-hooks.d/zz-tel354
cmp -s "$AQUI/dhclient-hook-tel354" "$HOOK" 2>/dev/null || cp "$AQUI/dhclient-hook-tel354" "$HOOK"
printf 'Solicitando dirección IP (DHCP)... '
if timeout 30 dhclient -1 -lf "/run/dhclient-$IF.leases" -pf "/run/dhclient-$IF.pid" "$IF" 2>/dev/null; then
  echo "listo"
else
  echo "sin respuesta del servidor DHCP"; exit 4
fi
echo
ip -br -4 addr show dev "$IF"
ip -4 route show default
echo "Sesión abierta. Para cerrarla: bash $AQUI/logout.sh"
