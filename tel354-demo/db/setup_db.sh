#!/usr/bin/env bash
# =====================================================================
# PostgreSQL de la demo: base campus (modelo de R1 y R2) y base kea (leases).
# Escucha en 10.200.0.12 y solo acepta a sus clientes del diseño.
# Uso: bash db/setup_db.sh
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../demo.env
PGV=$(ls /etc/postgresql | sort -n | tail -1)          # 12 en Ubuntu 20.04, 16 en 24.04
CONF=/etc/postgresql/$PGV/main

pg_ctlcluster "$PGV" main start 2>/dev/null || true
sleep 2

# Base del modelo
su postgres -c "psql -qc 'DROP DATABASE IF EXISTS ${PG_DB}'"
su postgres -c "psql -qc 'CREATE DATABASE ${PG_DB} ENCODING UTF8 LC_COLLATE \"C\" LC_CTYPE \"C\" TEMPLATE template0'"
for f in 01_schema.sql 02_roles.sql 03_seed_demo.sql; do
  su postgres -c "psql -q -v ON_ERROR_STOP=1 -d ${PG_DB} -f $PWD/$f"
done

# Base de leases de Kea (AVZ07, sección 4.3: backend PostgreSQL)
su postgres -c "psql -qc \"DROP DATABASE IF EXISTS ${KEA_DB}\""
su postgres -c "psql -qc \"DO \\\$\\\$BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='${KEA_DB_USER}') THEN CREATE ROLE ${KEA_DB_USER} LOGIN PASSWORD '${KEA_DB_PW}'; END IF; END\\\$\\\$\""
su postgres -c "psql -qc 'CREATE DATABASE ${KEA_DB} OWNER ${KEA_DB_USER}'"
kea-admin db-init pgsql -u "${KEA_DB_USER}" -p "${KEA_DB_PW}" -n "${KEA_DB}" -h 127.0.0.1 >/dev/null

# Red: solo la dirección del diseño y los clientes autorizados
sed -i "s/^#\?listen_addresses.*/listen_addresses = '${IP_PG},127.0.0.1'/" $CONF/postgresql.conf
sed -i '/TEL354/,$d' $CONF/pg_hba.conf
cat >> $CONF/pg_hba.conf <<EOH
# TEL354: clientes del modelo (AVZ07, sección 3.2)
# FreeRADIUS, Kea y PostgreSQL comparten la VM de servicios (10.200.0.0/24)
host  ${PG_DB}  radius_ro  10.200.0.0/24    md5
host  ${PG_DB}  onos_m1    ${IP_ONOS}/32    md5
host  ${PG_DB}  onos_m2    ${IP_ONOS}/32    md5
host  ${PG_DB}  onos_m6    ${IP_ONOS}/32    md5
host  ${KEA_DB} ${KEA_DB_USER} 10.200.0.0/24 md5
host  all       all        127.0.0.1/32     md5
EOH
pg_ctlcluster "$PGV" main stop
echo "PostgreSQL $PGV configurado (se arranca en el host svc con resources/start_services.sh)."
