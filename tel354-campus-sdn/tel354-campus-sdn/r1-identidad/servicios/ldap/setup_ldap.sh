#!/usr/bin/env bash
# =====================================================================
# OpenLDAP de la demo: dominio campus.pucp.edu.pe, usuarios con
# SHA-512 crypt, ACL que impide leer userPassword y LDAPS con la CA
# de la demo (AVZ07, sección 3.1).
# Uso: bash r1-identidad/servicios/ldap/setup_ldap.sh   (requiere scripts/gen_certs.sh antes)
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../../../comun/config/demo.env

BASE="dc=campus,dc=pucp,dc=edu,dc=pe"
CERTS=/etc/tel354/certs

# 0. AppArmor: el perfil de slapd en Ubuntu solo permite leer certificados de /etc/ssl
#    y /etc/ldap; sin esta regla, la configuración TLS falla con "ldap_modify: error (80)"
if [ -f /etc/apparmor.d/usr.sbin.slapd ]; then
  L=/etc/apparmor.d/local/usr.sbin.slapd
  grep -q "${CERTS}/" "$L" 2>/dev/null || printf '%s/ r,\n%s/* r,\n' "${CERTS}" "${CERTS}" >> "$L"
  apparmor_parser -r /etc/apparmor.d/usr.sbin.slapd 2>/dev/null || true
fi

# 1. Dominio y contraseña del administrador (debconf, sin preguntas)
debconf-set-selections <<EOF
slapd slapd/no_configuration boolean false
slapd slapd/domain string campus.pucp.edu.pe
slapd shared/organization string PUCP Campus SDN
slapd slapd/password1 password ${LDAP_ADMIN_PW}
slapd slapd/password2 password ${LDAP_ADMIN_PW}
slapd slapd/purge_database boolean true
slapd slapd/move_old_database boolean true
EOF
DEBIAN_FRONTEND=noninteractive dpkg-reconfigure slapd >/dev/null 2>&1 || true

# 2. slapd no debe correr como servicio del namespace raíz: en Mininet
#    vive en el host svc (10.200.0.11). Se detiene la unidad de systemd.
systemctl stop slapd 2>/dev/null || true
systemctl disable slapd 2>/dev/null || true
pkill -x slapd 2>/dev/null || true
sleep 1

# 3. Arranque temporal solo por ldapi:/// para configurar
slapd -h "ldapi:///" -u openldap -g openldap
sleep 1

# 4. TLS (LDAPS), hash por defecto SHA-512 crypt y ACL
ldapmodify -Q -Y EXTERNAL -H ldapi:/// <<EOF
dn: cn=config
changetype: modify
replace: olcTLSCACertificateFile
olcTLSCACertificateFile: ${CERTS}/ca.pem
-
replace: olcTLSCertificateFile
olcTLSCertificateFile: ${CERTS}/ldap.pem
-
replace: olcTLSCertificateKeyFile
olcTLSCertificateKeyFile: ${CERTS}/ldap.key
EOF

ldapmodify -Q -Y EXTERNAL -H ldapi:/// <<EOF
dn: olcDatabase={-1}frontend,cn=config
changetype: modify
replace: olcPasswordHash
olcPasswordHash: {CRYPT}

dn: cn=config
changetype: modify
replace: olcPasswordCryptSaltFormat
olcPasswordCryptSaltFormat: \$6\$%.16s
EOF

ldapmodify -Q -Y EXTERNAL -H ldapi:/// <<EOF
dn: olcDatabase={1}mdb,cn=config
changetype: modify
replace: olcAccess
olcAccess: {0}to attrs=userPassword by self =wx by anonymous auth by * none
olcAccess: {1}to dn.subtree="ou=usuarios,${BASE}" by dn.exact="cn=radius,${BASE}" read by self read by * none
olcAccess: {2}to * by dn.exact="cn=radius,${BASE}" read by * none
EOF

# 5. Estructura, cuenta de servicio de FreeRADIUS y usuarios de la demo
hash() { openssl passwd -6 "$1"; }
{
cat <<EOF
dn: ou=usuarios,${BASE}
objectClass: organizationalUnit
ou: usuarios

dn: cn=radius,${BASE}
objectClass: simpleSecurityObject
objectClass: organizationalRole
cn: radius
description: Cuenta de busqueda de FreeRADIUS
userPassword: {CRYPT}$(hash "${LDAP_RADIUS_PW}")

EOF
while IFS=: read -r uid nombre pw; do
  [[ -z "$uid" || "$uid" == \#* ]] && continue
cat <<EOF
dn: uid=${uid},ou=usuarios,${BASE}
objectClass: inetOrgPerson
uid: ${uid}
cn: ${nombre}
sn: ${uid}
userPassword: {CRYPT}$(hash "${pw}")

EOF
done < usuarios_demo.txt
} | ldapadd -x -H ldapi:/// -D "cn=admin,${BASE}" -w "${LDAP_ADMIN_PW}" -c >/dev/null 2>&1 || \
  echo "Aviso: algunas entradas ya existían (se mantienen)."

if ! ldapsearch -LLL -x -H ldapi:/// -D "cn=admin,${BASE}" -w "${LDAP_ADMIN_PW}" -b "ou=usuarios,${BASE}" uid >/dev/null 2>&1; then
  echo "No se pudo verificar el árbol"; exit 1
fi

echo "Usuarios cargados:"
ldapsearch -LLL -x -H ldapi:/// -D "cn=admin,${BASE}" -w "${LDAP_ADMIN_PW}" -b "ou=usuarios,${BASE}" uid | grep '^uid:'

pkill -x slapd; sleep 1
echo "OpenLDAP configurado. Se arranca en el host svc con scripts/servicios/start_services.sh."
