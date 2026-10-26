#!/usr/bin/env bash
# =====================================================================
# Instala todas las dependencias del kit en la Mininet-VM (Ubuntu 20.04).
# Recoge las correcciones de la validación del 12/10/2026:
#  - policy-rc.d durante la instalación: apt no arranca servicios (FreeRADIUS
#    fallaba al arrancar con su configuración de fábrica y dejaba paquetes a medias)
#  - eapoltest no existe en 20.04: eapol_test se compila desde wpa_supplicant 2.9,
#    sin soporte D-Bus
#  - Kea 2.4 desde el repositorio de ISC, con isc-kea-hooks (lease_cmds)
#  - todos los servicios quedan desactivados en systemd: corren dentro del host svc
# Uso (como root, desde la carpeta del kit):  bash scripts/instalar_dependencias.sh
# =====================================================================
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Ejecutar como root (sudo -i)."; exit 1; }
paso() { echo; echo "=== $1"; }

paso "1/6 Impedir que apt arranque servicios durante la instalación"
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
trap 'rm -f /usr/sbin/policy-rc.d' EXIT

paso "2/6 Paquetes de Ubuntu (Java, servicios, supplicant, compilación, pruebas de R3)"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-8-jdk curl unzip \
    freeradius freeradius-ldap freeradius-postgresql freeradius-utils \
    slapd ldap-utils postgresql python3-psycopg2 python3-yaml python3-requests \
    wpasupplicant isc-dhcp-client iputils-arping tcpdump maven \
    dnsmasq-base build-essential pkg-config libssl-dev libnl-3-dev libnl-genl-3-dev \
    nmap hping3 dsniff python3-scapy

# maven instala Java 11 como dependencia: ONOS 1.13 y la compilación de M1 usan Java 8
J8=/usr/lib/jvm/java-8-openjdk-amd64
[ -x $J8/jre/bin/java ] && update-alternatives --set java $J8/jre/bin/java >/dev/null 2>&1 || true
[ -x $J8/bin/javac ] && update-alternatives --set javac $J8/bin/javac >/dev/null 2>&1 || true

paso "3/6 eapol_test (cliente EAP de las pruebas de R1)"
if command -v eapol_test >/dev/null; then
    echo "Ya instalado: $(command -v eapol_test)"
elif apt-cache show eapoltest >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y eapoltest
else
    cd /tmp
    [ -f wpa_supplicant-2.9.tar.gz ] || curl -fLO https://w1.fi/releases/wpa_supplicant-2.9.tar.gz
    rm -rf wpa_supplicant-2.9 && tar -xzf wpa_supplicant-2.9.tar.gz
    cd wpa_supplicant-2.9/wpa_supplicant
    cp defconfig .config
    sed -i 's/^CONFIG_CTRL_IFACE_DBUS/#CONFIG_CTRL_IFACE_DBUS/' .config
    echo "CONFIG_EAPOL_TEST=y" >> .config
    make -j2 eapol_test >/tmp/eapol_test_build.log 2>&1 || { tail -20 /tmp/eapol_test_build.log; exit 1; }
    cp eapol_test /usr/local/bin/
    cd - >/dev/null
fi

paso "4/6 Kea 2.4 desde el repositorio de ISC"
if kea-dhcp4 -V 2>/dev/null | grep -q '^2\.4'; then
    echo "Kea $(kea-dhcp4 -V | head -1) ya instalado"
else
    curl -1sLf 'https://dl.cloudsmith.io/public/isc/kea-2-4/setup.deb.sh' | bash
    DEBIAN_FRONTEND=noninteractive apt-get install -y isc-kea-dhcp4-server isc-kea-ctrl-agent isc-kea-admin
fi
DEBIAN_FRONTEND=noninteractive apt-get install -y isc-kea-hooks 2>/dev/null || true

paso "5/6 Desactivar los servicios en systemd (corren dentro de svc)"
for s in freeradius slapd postgresql isc-kea-dhcp4-server isc-kea-ctrl-agent; do
    systemctl disable --now "$s" >/dev/null 2>&1 || true
done
echo "Listo."

paso "6/6 Comprobación"
ok=0
printf '%-28s' "Java 8";          java -version 2>&1 | grep -q '1\.8' && echo OK || { echo FALLO; ok=1; }
printf '%-28s' "Kea 2.4";         kea-dhcp4 -V 2>/dev/null | grep -q '^2\.4' && echo "OK ($(kea-dhcp4 -V | head -1))" || { echo FALLO; ok=1; }
H=$(find /usr/lib -name libdhcp_lease_cmds.so 2>/dev/null | head -1)
printf '%-28s' "Hook lease_cmds"; [ -n "$H" ] && echo "OK ($H)" || { echo FALLO; ok=1; }
printf '%-28s' "Kea con PostgreSQL"; kea-dhcp4 -W 2>/dev/null | grep -q PGSQL_VERSION && echo OK || { echo FALLO; ok=1; }
printf '%-28s' "eapol_test";      command -v eapol_test >/dev/null && echo "OK ($(command -v eapol_test))" || { echo FALLO; ok=1; }
for b in freeradius slapd psql dnsmasq wpa_supplicant wpa_cli dhclient mvn nmap hping3 arpspoof; do
    printf '%-28s' "$b"; command -v $b >/dev/null && echo OK || { echo FALLO; ok=1; }
done
if [ -n "$H" ] && ! grep -q "^KEA_HOOKS=$(dirname "$H")$" "$(dirname "$0")/../comun/config/demo.env"; then
    echo "Aviso: el hook está en $(dirname "$H"); ajusta KEA_HOOKS en demo.env"
fi
exit $ok
