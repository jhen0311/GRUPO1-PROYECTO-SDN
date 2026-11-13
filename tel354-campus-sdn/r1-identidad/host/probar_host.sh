#!/usr/bin/env bash
# =====================================================================
# Prueba local de login.sh, logout.sh, wpa_accion.sh y el hook de dhclient
# sin tocar la red del equipo: ip, wpa_supplicant, wpa_cli y dhclient se
# reemplazan por simuladores que guardan el estado en un directorio
# temporal (interfaces, direcciones, rutas y un registro de lo que cada
# script intentó hacer). Verifica que ens3 no se toque en ningún caso.
#
# Uso (cualquier equipo con bash y python3; no necesita root):
#   bash r1-identidad/host/probar_host.sh
# =====================================================================
set -u
AQUI="$(cd "$(dirname "$0")" && pwd)"
T=$(mktemp -d)
trap 'pkill -f "tel354-simulado-$$" 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/bin"
FALLAS=0

# ------------------------------------------------------------- simuladores
cat > "$T/bin/ip" <<'EOF'
#!/usr/bin/env bash
# Simulador de ip(8): solo lo que usan los scripts del host. Estado en $SIM.
S=$SIM; A="$*"
echo "ip $A" >> "$S/registro"
existe() { grep -qx "$1" "$S/enlaces"; }
dev() { local p=""; for x in "$@"; do [ "$p" = dev ] && { echo "$x"; return; }; p=$x; done; }
D=$(dev "$@")
case "$A" in
  "-o link show")
    n=1; while read -r l; do echo "$n: $l: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500"; n=$((n+1)); done < "$S/enlaces" ;;
  "-o link show dev "*|"link show dev "*)
    existe "$D" && echo "2: $D: <UP>" || { echo "Device \"$D\" does not exist." >&2; exit 1; } ;;
  "-4 addr flush dev "*)
    existe "$D" || exit 1
    : > "$S/dir/$D"; sed -i "/ dev $D\( \|$\)/d" "$S/rutas" ;;
  "neigh flush dev "*) : ;;
  "-4 -o addr show dev "*)
    existe "$D" || exit 1
    while read -r c; do [ -n "$c" ] && echo "2: $D    inet $c scope global $D"; done < "$S/dir/$D" ;;
  "-br -4 addr show dev "*)
    echo "$D UP $(xargs < "$S/dir/$D")" ;;
  "-4 route show default")
    grep "^default" "$S/rutas" || true ;;
  "route replace "*)
    shift 2; sed -i "\|^$1 |d" "$S/rutas"; echo "$*" >> "$S/rutas" ;;
  *) echo "ip simulado: no soportado: $A" >&2; echo "NO SOPORTADO: ip $A" >> "$S/registro"; exit 2 ;;
esac
EOF
cat > "$T/bin/wpa_supplicant" <<'EOF'
#!/usr/bin/env bash
# Simulador: deja el socket de control y un proceso con su pid, como el demonio real.
while [ $# -gt 0 ]; do case "$1" in -i) IF=$2; shift ;; -c) CONF=$2; shift ;; -P) PID=$2; shift ;; esac; shift; done
echo "wpa_supplicant $IF" >> "$SIM/registro"
CTRL=$(sed -n 's/^ctrl_interface=//p' "$CONF"); mkdir -p "$CTRL"
python3 -c "import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1])" "$CTRL/$IF"
( exec -a "tel354-simulado-$SIM_ID" sleep 300 ) & echo $! > "$PID"
EOF
cat > "$T/bin/wpa_cli" <<'EOF'
#!/usr/bin/env bash
# Simulador: status según SIM_AUTH (OK, RECHAZO o SIN_RESPUESTA); -a deja un proceso en segundo plano.
IF=""; PID=""; ACC=""
while [ $# -gt 0 ]; do case "$1" in -p) shift ;; -i) IF=$2; shift ;; -a) ACC=$2; shift ;; -P) PID=$2; shift ;; -B) ;; *) break ;; esac; shift; done
echo "wpa_cli $IF ${ACC:+-a }$*" >> "$SIM/registro"
if [ -n "$ACC" ]; then ( exec -a "tel354-simulado-$SIM_ID" sleep 300 ) & echo $! > "$PID"; exit 0; fi
case "${1:-}" in
  status) case "${SIM_AUTH:-OK}" in OK) echo "suppPortStatus=Authorized" ;; RECHAZO) echo "EAP state=FAILURE" ;; esac ;;
  "") cat > /dev/null ;;      # set_network / enable_network por la entrada estándar
esac
EOF
cat > "$T/bin/dhclient" <<'EOF'
#!/bin/sh
# Simulador de dhclient + dhclient-script: carga el hook instalado y aplica el lease como el real
# (dirección en la interfaz y, si el hook no la retira, ruta por defecto por el gateway del lease).
LIB=0
while [ $# -gt 1 ]; do case "$1" in -r) LIB=1 ;; -lf) LF=$2; shift ;; -pf) PF=$2; shift ;; esac; shift; done
interface=$1
echo "dhclient $([ $LIB = 1 ] && echo -r) $interface" >> "$SIM/registro"
[ $LIB = 1 ] && exit 0
[ "${SIM_DHCP:-OK}" = OK ] || exit 2
new_ip_address=10.17.0.10; new_routers=10.16.0.1
make_resolv_conf() { echo "resolv.conf reescrito por $interface" >> "$SIM/registro"; }
for h in "$TEL354_RAIZ_PRUEBA"/etc/dhcp/dhclient-enter-hooks.d/*; do [ -f "$h" ] && . "$h"; done
echo "$new_ip_address/12" > "$SIM/dir/$interface"
echo "10.16.0.0/12 dev $interface proto kernel" >> "$SIM/rutas"
if [ -n "${new_routers:-}" ]; then
  { echo "default via $new_routers dev $interface"; cat "$SIM/rutas"; } > "$SIM/rutas.n" && mv "$SIM/rutas.n" "$SIM/rutas"
fi
make_resolv_conf
printf 'lease {\n  interface "%s";\n  fixed-address %s;\n  option routers 10.16.0.1;\n}\n' "$interface" "$new_ip_address" > "$LF"
echo $$ > "$PF"
EOF
chmod +x "$T"/bin/*

# ------------------------------------------------------------- escenarios
equipo() {   # nombre del equipo simulado: slice-tunel | slice | mininet | dos-tuneles
  SIM="$T/$1"; rm -rf "$SIM" "$T/raiz"; mkdir -p "$SIM/dir" "$T/raiz/run" "$T/raiz/etc/tel354/certs" \
                                                  "$T/raiz/etc/dhcp/dhclient-enter-hooks.d"
  echo CA > "$T/raiz/etc/tel354/certs/ca.pem"; : > "$SIM/registro"; : > "$SIM/rutas"
  case "$1" in
    slice-tunel)  E="lo ens3 ens4 vx-sw2" ;;
    slice)        E="lo ens3 ens4" ;;
    dos-tuneles)  E="lo ens3 ens4 vx-sw2 vx-sw3" ;;
    mininet)      E="lo h1-eth0@if7" ;;
  esac
  for e in $E; do echo "${e%%@*}" >> "$SIM/enlaces"; : > "$SIM/dir/${e%%@*}"; done
  if grep -qx ens3 "$SIM/enlaces"; then
    echo "192.168.0.22/24" > "$SIM/dir/ens3"
    printf 'default via 192.168.0.1 dev ens3 proto dhcp src 192.168.0.22 metric 100\n192.168.0.0/24 dev ens3 proto kernel\n' > "$SIM/rutas"
  fi
  grep -qx ens4 "$SIM/enlaces" && grep -q vx- "$SIM/enlaces" && echo "172.31.22.1/30" > "$SIM/dir/ens4"
  export SIM
}
correr() {   # script y argumentos; usuario y clave por la entrada estándar
  printf 'alumno02\nAlumno2026!\n' | env PATH="$T/bin:$PATH" SIM_ID=$$ TEL354_RAIZ_PRUEBA="$T/raiz" \
    SIM_AUTH="${SIM_AUTH:-OK}" SIM_DHCP="${SIM_DHCP:-OK}" bash "$@" > "$SIM/salida" 2>&1
  RC=$?
}
ok() {   # descripción y condición ya evaluada (0 = cumple)
  if [ "$2" -eq 0 ]; then echo "  OK    $1"; else echo "  FALLA $1"; FALLAS=$((FALLAS+1)); sed 's/^/        | /' "$SIM/salida"; fi
}
ens3_intacta() {   # IP de gestión, ruta por defecto de gestión y ningún comando sobre ens3
  [ "$(cat "$SIM/dir/ens3")" = "192.168.0.22/24" ] \
    && [ "$(grep '^default' "$SIM/rutas" | head -1)" = "default via 192.168.0.1 dev ens3 proto dhcp src 192.168.0.22 metric 100" ] \
    && ! grep -qE "flush dev ens3|wpa_supplicant ens3|wpa_cli ens3|dhclient (-r )?ens3|NO SOPORTADO" "$SIM/registro"
}
usa() { grep -q "$1" "$SIM/registro"; }

echo "== VM del slice con túnel (lo, ens3, ens4 portadora, vx-sw2)"
equipo slice-tunel; correr "$AQUI/logout.sh"
ens3_intacta; ok "logout.sh sin argumento: ens3 intacta" $?
usa "flush dev vx-sw2"; ok "logout.sh sin argumento: limpia vx-sw2" $?
equipo slice-tunel; correr "$AQUI/logout.sh" --silencioso
ens3_intacta && usa "flush dev vx-sw2"; ok "logout.sh --silencioso: ens3 intacta, limpia vx-sw2" $?
equipo slice-tunel; correr "$AQUI/logout.sh" ens3
[ $RC -ne 0 ] && ens3_intacta && ! usa "flush"; ok "logout.sh ens3: se niega y no limpia nada" $?
equipo slice-tunel; correr "$AQUI/login.sh" ens3
[ $RC -ne 0 ] && ens3_intacta && ! usa "wpa_supplicant" && ! usa "flush"; ok "login.sh ens3: se niega antes de iniciar 802.1X" $?
equipo slice-tunel; correr "$AQUI/logout.sh" ens4
[ $RC -ne 0 ] && [ "$(cat "$SIM/dir/ens4")" = "172.31.22.1/30" ]; ok "logout.sh ens4: se niega (es la portadora del túnel)" $?
equipo slice-tunel; correr "$AQUI/login.sh"
[ $RC -eq 0 ] && ens3_intacta; ok "login.sh sin argumento: sesión abierta y ens3 intacta" $?
usa "wpa_supplicant vx-sw2" && usa "dhclient  vx-sw2" && [ "$(cat "$SIM/dir/vx-sw2")" = "10.17.0.10/12" ]
ok "login.sh sin argumento: 802.1X y DHCP por vx-sw2 (10.17.0.10/12)" $?
grep -q "^10.0.0.0/8 via 10.16.0.1 dev vx-sw2" "$SIM/rutas" && [ "$(grep -c '^default' "$SIM/rutas")" -eq 1 ]
ok "login.sh: intranet por el gateway del rol y una sola ruta por defecto (la de gestión)" $?
[ "$(cat "$SIM/dir/ens4")" = "172.31.22.1/30" ]; ok "login.sh: la portadora del túnel (ens4) conserva su IP" $?
correr "$AQUI/wpa_accion.sh" vx-sw2 CONNECTED
ens3_intacta && [ "$(cat "$SIM/dir/vx-sw2")" = "10.17.0.10/12" ]; ok "wpa_accion.sh vx-sw2 (reautenticación): renueva la IP, ens3 intacta" $?
correr "$AQUI/wpa_accion.sh" ens3 CONNECTED
[ $RC -ne 0 ] && ens3_intacta; ok "wpa_accion.sh ens3: se niega" $?
correr "$AQUI/logout.sh" vx-sw2
ens3_intacta && [ -z "$(cat "$SIM/dir/vx-sw2")" ] && usa "dhclient -r vx-sw2"; ok "logout.sh vx-sw2: sesión cerrada, ens3 intacta" $?
equipo slice-tunel; SIM_AUTH=RECHAZO correr "$AQUI/login.sh" vx-sw2
[ $RC -eq 2 ] && ens3_intacta; ok "login.sh con credenciales rechazadas: ens3 intacta" $?
equipo slice-tunel; SIM_DHCP=FALLA correr "$AQUI/login.sh" vx-sw2
[ $RC -eq 4 ] && ens3_intacta; ok "login.sh sin respuesta de DHCP: ens3 intacta" $?
equipo slice-tunel; : > "$SIM/dir/ens3"; correr "$AQUI/login.sh" vx-sw2
grep -q "AVISO: ens3 no tiene IPv4" "$SIM/salida" && ! grep -qE "flush dev ens3|dhclient (-r )?ens3" "$SIM/registro"
ok "login.sh con ens3 ya sin IP: avisa cómo recuperarla y no la toca" $?

echo "== VM del slice sin túnel (lo, ens3, ens4)"
equipo slice; correr "$AQUI/login.sh"
[ $RC -eq 0 ] && ens3_intacta && usa "wpa_supplicant ens4"; ok "login.sh sin argumento: usa ens4, ens3 intacta" $?
correr "$AQUI/logout.sh"
ens3_intacta && usa "flush dev ens4"; ok "logout.sh sin argumento: limpia ens4, ens3 intacta" $?
equipo slice; correr "$AQUI/login.sh" ens3
[ $RC -ne 0 ] && ens3_intacta; ok "login.sh ens3: se niega" $?

echo "== VM con dos túneles"
equipo dos-tuneles; correr "$AQUI/logout.sh"
[ $RC -ne 0 ] && ens3_intacta && ! usa "flush"; ok "logout.sh sin argumento: pide indicar el túnel y no limpia nada" $?
correr "$AQUI/logout.sh" vx-sw3
ens3_intacta && usa "flush dev vx-sw3"; ok "logout.sh vx-sw3: limpia solo vx-sw3" $?

echo "== Host de Mininet (lo, h1-eth0; sin ens3): igual que antes"
equipo mininet; correr "$AQUI/login.sh"
[ $RC -eq 0 ] && usa "wpa_supplicant h1-eth0" && grep -q "^default via 10.16.0.1 dev h1-eth0" "$SIM/rutas"
ok "login.sh sin argumento: h1-eth0 y ruta por defecto del lease" $?
! grep -q "resolv.conf reescrito" "$SIM/registro"; ok "el hook sigue protegiendo /etc/resolv.conf de la VM" $?
correr "$AQUI/logout.sh"
usa "flush dev h1-eth0" && [ $RC -eq 0 ]; ok "logout.sh sin argumento: limpia h1-eth0" $?

echo "== Hook de dhclient"
hook() {   # interfaz del lease; imprime los routers que quedan
  env PATH="$T/bin:$PATH" SIM="$SIM" sh -c 'interface=$1; new_routers=10.16.0.1; . "$2"; echo "${new_routers:-}"' _ "$1" "$AQUI/dhclient-hook-tel354"
}
equipo slice-tunel
[ -z "$(hook vx-sw2)" ]; ok "VM con ens3, lease de vx-sw2: sin ruta por defecto" $?
[ "$(hook ens3)" = "10.16.0.1" ]; ok "VM con ens3, lease de la propia ens3: no se altera" $?
equipo mininet
[ "$(hook h1-eth0)" = "10.16.0.1" ]; ok "equipo sin ens3: el lease conserva su ruta por defecto" $?

echo
[ $FALLAS -eq 0 ] && echo "Resultado: todas las verificaciones pasan" || echo "Resultado: $FALLAS fallas"
exit $((FALLAS > 0))
