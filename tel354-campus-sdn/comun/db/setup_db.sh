#!/usr/bin/env bash
# =====================================================================
# PostgreSQL de la demo: base campus (modelo de R1 y R2) y base kea (leases).
# Escucha en 10.200.0.12 y solo acepta a sus clientes del diseño.
# Uso: bash comun/db/setup_db.sh
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../config/demo.env
PGV=$(ls /etc/postgresql | sort -n | tail -1)          # 12 en Ubuntu 20.04, 16 en 24.04
CONF=/etc/postgresql/$PGV/main

pg_ctlcluster "$PGV" main start 2>/dev/null || true
sleep 2

# Base del modelo
su postgres -c "psql -qc 'DROP DATABASE IF EXISTS ${PG_DB}'"
su postgres -c "psql -qc 'CREATE DATABASE ${PG_DB} ENCODING UTF8 LC_COLLATE \"C\" LC_CTYPE \"C\" TEMPLATE template0'"
for f in 01_schema.sql 02_roles.sql 03_seed_demo.sql 04_invitado.sql; do
  su postgres -c "psql -q -v ON_ERROR_STOP=1 -d ${PG_DB} -f $PWD/$f"
done

# Base de leases de Kea (AVZ07, sección 4.3: backend PostgreSQL)
su postgres -c "psql -qc \"DROP DATABASE IF EXISTS ${KEA_DB}\""
su postgres -c "psql -qc \"DO \\\$\\\$BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='${KEA_DB_USER}') THEN CREATE ROLE ${KEA_DB_USER} LOGIN PASSWORD '${KEA_DB_PW}'; END IF; END\\\$\\\$\""
su postgres -c "psql -qc 'CREATE DATABASE ${KEA_DB} OWNER ${KEA_DB_USER}'"
kea-admin db-init pgsql -u "${KEA_DB_USER}" -p "${KEA_DB_PW}" -n "${KEA_DB}" -h 127.0.0.1 >/dev/null

# Red: solo la dirección del diseño y los clientes autorizados
sed -i "s/^#\?listen_addresses.*/listen_addresses = '${IP_PG},127.0.0.1'/" $CONF/postgresql.conf
# TLS con el certificado de la CA de la demo (scripts/gen_certs.sh): M1 y M2 llegan desde
# ONOS por el fabric, así que su conexión va cifrada (hostssl) y M1 verifica la CA.
sed -i "/^#\?ssl \?=/d; /^#\?ssl_cert_file/d; /^#\?ssl_key_file/d" $CONF/postgresql.conf
# UTC: Kea 2.4 (leases en PostgreSQL) falla al renovar o liberar un lease si su zona horaria y
# la del servidor no coinciden ("unable to update lease ... as it does not exist").
sed -i "/^#\?timezone \?=/d; /^#\?log_timezone \?=/d" $CONF/postgresql.conf
cat >> $CONF/postgresql.conf <<EOS
timezone = 'UTC'
log_timezone = 'UTC'
ssl = on
ssl_cert_file = '/etc/tel354/certs/db.pem'
ssl_key_file = '/etc/tel354/certs/db.key'
EOS
sed -i '/TEL354/,$d' $CONF/pg_hba.conf
cat >> $CONF/pg_hba.conf <<EOH
# TEL354: clientes del modelo (AVZ07, sección 3.2)
# FreeRADIUS, Kea y PostgreSQL comparten la VM de servicios (10.200.0.0/24)
host  ${PG_DB}  radius_ro  10.200.0.0/24    md5
hostssl ${PG_DB}  onos_m1  ${IP_ONOS}/32    md5
hostssl ${PG_DB}  onos_m2  ${IP_ONOS}/32    md5
hostssl ${PG_DB}  onos_m6  ${IP_ONOS}/32    md5
host  ${KEA_DB} ${KEA_DB_USER} 10.200.0.0/24 md5
host  all       all        127.0.0.1/32     md5
EOH
pg_ctlcluster "$PGV" main stop
echo "PostgreSQL $PGV configurado (se arranca en el host svc con scripts/servicios/start_services.sh)."
