#!/usr/bin/env bash
# =====================================================================
# PostgreSQL: base campus (modelo de R1 a R3) y base kea (leases).
# Escucha en IP_PG y solo acepta a sus clientes del diseño.
# Uso en el slice (S-IDENTIDAD, 10.200.3.12):  bash comun/db/setup_db.sh
# Uso en la maqueta de Mininet (host svc):     TEL354_ENV=demo.env bash comun/db/setup_db.sh
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
ENVF=${TEL354_ENV:-slice.env}
source ../config/$ENVF
PGV=$(ls /etc/postgresql | sort -n | tail -1)          # 12 en Ubuntu 20.04, 16 en 24.04
CONF=/etc/postgresql/$PGV/main

pg_ctlcluster "$PGV" main start 2>/dev/null || true
sleep 2

# Base del modelo
su postgres -c "psql -qc 'DROP DATABASE IF EXISTS ${PG_DB}'"
su postgres -c "psql -qc 'CREATE DATABASE ${PG_DB} ENCODING UTF8 LC_COLLATE \"C\" LC_CTYPE \"C\" TEMPLATE template0'"
# 05_usuarios_piloto.sql: N cuentas por rol (python3 comun/db/gen_usuarios.py)
for f in 01_schema.sql 02_roles.sql 03_seed_demo.sql 04_invitado.sql $(ls 05_usuarios_piloto.sql 06_m6.sql 2>/dev/null); do
  # El archivo lo lee root y lo pasa por stdin: el usuario postgres no puede entrar a /root
  su postgres -c "psql -q -v ON_ERROR_STOP=1 -d ${PG_DB}" < "$f"
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
if [ "$ENVF" != "demo.env" ]; then
  # Slice (LAB6): FreeRADIUS comparte la VM (S-IDENTIDAD); Kea está en S-SERVICIOS; las tres
  # instancias de ONOS (172.16.0.11-13) y el host Docker (172.16.0.10, base_slice.py) llegan in-band.
  # Las conexiones salen con la primera IP de la interfaz del cliente (p. ej., Kea con 10.200.0.5),
  # por eso se autoriza la subred. Con otro perfil (una_instancia.env) los rangos vienen de PG_RED_*.
  cat >> $CONF/pg_hba.conf <<EOH
# TEL354: clientes del modelo (piloto en el slice)
host    ${PG_DB}  radius_ro  127.0.0.1/32       scram-sha-256
host    ${PG_DB}  radius_ro  ${PG_RED_RADIUS:-10.200.3.0/24}     scram-sha-256
$(for r in ${PG_RED_ONOS:-172.16.0.0/24}; do for u in onos_m1 onos_m2 onos_m5 onos_m6 onos_m7; do
    echo "hostssl ${PG_DB}  $u    $r      scram-sha-256"; done; done)
host    ${KEA_DB} ${KEA_DB_USER} ${PG_RED_KEA:-10.200.0.0/24}    scram-sha-256
host    all       all        127.0.0.1/32       scram-sha-256
EOH
  systemctl enable postgresql >/dev/null 2>&1 || true
  systemctl restart postgresql
  sleep 2
  pg_isready -h "${IP_PG}" && echo "PostgreSQL $PGV configurado y en marcha en ${IP_PG}:5432."
else
  cat >> $CONF/pg_hba.conf <<EOH
# TEL354: clientes del modelo (AVZ07, sección 3.2)
# FreeRADIUS, Kea y PostgreSQL comparten la VM de servicios (10.200.0.0/24)
host  ${PG_DB}  radius_ro  10.200.0.0/24    md5
hostssl ${PG_DB}  onos_m1  ${IP_ONOS}/32    md5
hostssl ${PG_DB}  onos_m2  ${IP_ONOS}/32    md5
hostssl ${PG_DB}  onos_m5  ${IP_ONOS}/32    md5
hostssl ${PG_DB}  onos_m6  ${IP_ONOS}/32    md5
hostssl ${PG_DB}  onos_m7  ${IP_ONOS}/32    md5
host  ${KEA_DB} ${KEA_DB_USER} 10.200.0.0/24 md5
host  all       all        127.0.0.1/32     md5
EOH
  pg_ctlcluster "$PGV" main stop
  echo "PostgreSQL $PGV configurado (se arranca en el host svc con scripts/servicios/start_services.sh)."
fi
