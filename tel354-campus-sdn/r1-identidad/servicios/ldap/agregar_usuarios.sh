#!/usr/bin/env bash
# =====================================================================
# Agrega cuentas a un OpenLDAP ya configurado (sin reinstalar slapd),
# a partir de una lista uid:nombre:contraseña. Las que ya existen se
# mantienen. El rol de cada cuenta se carga aparte en PostgreSQL.
# Uso (en S-IDENTIDAD, desde la raíz del repositorio):
#   bash r1-identidad/servicios/ldap/agregar_usuarios.sh r1-identidad/servicios/ldap/usuarios_piloto.txt
# =====================================================================
set -euo pipefail
LISTA=${1:?Uso: agregar_usuarios.sh <lista uid:nombre:contraseña>}
source "$(cd "$(dirname "$0")/../../.." && pwd)/comun/config/${TEL354_ENV:-slice.env}"
BASE="dc=campus,dc=pucp,dc=edu,dc=pe"
hash() { openssl passwd -6 "$1"; }
while IFS=: read -r uid nombre pw; do
  [[ -z "$uid" || "$uid" == \#* ]] && continue
  printf 'dn: uid=%s,ou=usuarios,%s\nobjectClass: inetOrgPerson\nuid: %s\ncn: %s\nsn: %s\nuserPassword: {CRYPT}%s\n\n' \
    "$uid" "$BASE" "$uid" "$nombre" "$uid" "$(hash "$pw")"
done < "$LISTA" | ldapadd -x -c -H ldapi:/// -D "cn=admin,${BASE}" -w "${LDAP_ADMIN_PW}" 2>&1 \
  | grep -c "^adding new entry" | sed 's/$/ cuentas nuevas en OpenLDAP/'
ldapsearch -LLL -x -H ldapi:/// -D "cn=admin,${BASE}" -w "${LDAP_ADMIN_PW}" -b "ou=usuarios,${BASE}" uid \
  | grep -c '^uid:' | sed 's/$/ cuentas en total/'
