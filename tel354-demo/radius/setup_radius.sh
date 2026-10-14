#!/usr/bin/env bash
# =====================================================================
# Instala la configuración de FreeRADIUS de la demo sobre /etc/freeradius/3.0.
# Uso: bash radius/setup_radius.sh   (después de gen_certs.sh y setup_ldap.sh)
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../demo.env
R=/etc/freeradius/3.0

cp -n "$R/clients.conf" "$R/clients.conf.orig" 2>/dev/null || true
sed "s/\${RADIUS_SECRET}/${RADIUS_SECRET}/" clients.conf > "$R/clients.conf"
grep -q dictionary.pucp "$R/dictionary" || echo '$INCLUDE dictionary.pucp' >> "$R/dictionary"
cp dictionary.pucp "$R/dictionary.pucp"

cp mods/eap "$R/mods-available/eap"
sed "s/\${LDAP_RADIUS_PW}/${LDAP_RADIUS_PW}/" mods/ldap > "$R/mods-available/ldap"
cp mods/sql "$R/mods-available/sql"
ln -sf ../mods-available/ldap "$R/mods-enabled/ldap"
ln -sf ../mods-available/sql  "$R/mods-enabled/sql"

cp sites/campus sites/campus-inner "$R/sites-available/"
rm -f "$R/sites-enabled/default" "$R/sites-enabled/inner-tunnel"
ln -sf ../sites-available/campus       "$R/sites-enabled/campus"
ln -sf ../sites-available/campus-inner "$R/sites-enabled/campus-inner"

chgrp -R freerad "$R" && chmod 640 "$R/mods-available/ldap" "$R/clients.conf"

# FreeRADIUS corre en el host svc (10.200.0.10), no como servicio del namespace raíz.
systemctl stop freeradius 2>/dev/null || true
systemctl disable freeradius 2>/dev/null || true
echo "Configuración instalada. Verificación de sintaxis:"
freeradius -C -X 2>&1 | tail -2
