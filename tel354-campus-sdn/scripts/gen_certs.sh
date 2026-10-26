#!/usr/bin/env bash
# =====================================================================
# CA propia de la demo y certificados de servidor (EAP-TTLS, LDAPS y
# PostgreSQL con TLS para M1).
# No hace falta un servidor de certificados aparte: la CA solo firma
# una vez, y los hosts reciben ca.pem para validar a FreeRADIUS.
# Uso: bash scripts/gen_certs.sh [directorio_destino]
# =====================================================================
set -euo pipefail
OUT=${1:-/etc/tel354/certs}
DAYS=825
mkdir -p "$OUT" && cd "$OUT"

if [[ ! -f ca.key ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -sha256 \
    -subj "/C=PE/O=PUCP TEL354 G01/CN=TEL354 Demo CA" \
    -keyout ca.key -out ca.pem
fi

emitir() {   # emitir <nombre> <CN> <IP> <eku>
  local n=$1 cn=$2 ip=$3 eku=$4
  cat > "$n.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=$eku
subjectAltName=DNS:$cn,IP:$ip
EOF
  openssl req -newkey rsa:2048 -nodes -sha256 -subj "/C=PE/O=PUCP TEL354 G01/CN=$cn" \
    -keyout "$n.key" -out "$n.csr"
  openssl x509 -req -in "$n.csr" -CA ca.pem -CAkey ca.key -CAcreateserial \
    -days $DAYS -sha256 -extfile "$n.ext" -out "$n.pem"
  rm -f "$n.csr" "$n.ext"
}

emitir radius radius.campus.pucp.edu.pe 10.200.0.10 serverAuth
emitir ldap   ldap.campus.pucp.edu.pe   10.200.0.11 serverAuth
emitir db     db.campus.pucp.edu.pe     10.200.0.12 serverAuth
openssl dhparam -out dh 2048 2>/dev/null

chmod 644 ca.pem radius.pem ldap.pem db.pem dh
chmod 640 ca.key radius.key ldap.key db.key
getent group freerad  >/dev/null && chgrp freerad  radius.key || true
getent group openldap >/dev/null && chgrp openldap ldap.key   || true
# PostgreSQL acepta una clave de root con permiso de lectura para su grupo (0640)
getent group postgres >/dev/null && chgrp postgres db.key     || true
echo "Certificados en $OUT:"; ls -l "$OUT"
