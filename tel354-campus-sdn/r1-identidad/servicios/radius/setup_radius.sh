#!/usr/bin/env bash
# =====================================================================
# Instala la configuración de FreeRADIUS sobre /etc/freeradius/3.0.
# Uso (slice, S-IDENTIDAD): bash r1-identidad/servicios/radius/setup_radius.sh
# Uso (maqueta):            TEL354_ENV=demo.env bash r1-identidad/servicios/radius/setup_radius.sh
# (después de gen_certs.sh y setup_ldap.sh)
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
ENVF=${TEL354_ENV:-slice.env}
source ../../../comun/config/$ENVF
R=/etc/freeradius/3.0

cp -n "$R/clients.conf" "$R/clients.conf.orig" 2>/dev/null || true
sed -e "s/\${RADIUS_SECRET}/${RADIUS_SECRET}/" -e "s#ipaddr = 172.16.0.0/24#ipaddr = ${RADIUS_NAS_RED:-172.16.0.0/24}#" clients.conf > "$R/clients.conf"
grep -q dictionary.pucp "$R/dictionary" || echo '$INCLUDE dictionary.pucp' >> "$R/dictionary"
cp dictionary.pucp "$R/dictionary.pucp"

cp mods/eap "$R/mods-available/eap"
sed -e "s/\${LDAP_RADIUS_PW}/${LDAP_RADIUS_PW}/" -e "s#ldaps://10.200.0.11#ldaps://${IP_LDAP}#" \
    mods/ldap > "$R/mods-available/ldap"
sed "s/server = \"10.200.0.12\"/server = \"${IP_PG}\"/" mods/sql > "$R/mods-available/sql"
ln -sf ../mods-available/ldap "$R/mods-enabled/ldap"
ln -sf ../mods-available/sql  "$R/mods-enabled/sql"

cp sites/campus sites/campus-inner "$R/sites-available/"
rm -f "$R/sites-enabled/default" "$R/sites-enabled/inner-tunnel"
ln -sf ../sites-available/campus       "$R/sites-enabled/campus"
ln -sf ../sites-available/campus-inner "$R/sites-enabled/campus-inner"

chgrp -R freerad "$R" && chmod 640 "$R/mods-available/ldap" "$R/clients.conf"

echo "Configuración instalada. Verificación de sintaxis:"
freeradius -C -X 2>&1 | tail -2
if [ "$ENVF" != "demo.env" ]; then
  systemctl enable freeradius >/dev/null 2>&1 || true
  systemctl restart freeradius && echo "FreeRADIUS en marcha (UDP ${IP_RADIUS}:1812)."
else
  # Maqueta: FreeRADIUS corre en el host svc (10.200.0.10), no como servicio del namespace raíz.
  systemctl stop freeradius 2>/dev/null || true
  systemctl disable freeradius 2>/dev/null || true
fi
