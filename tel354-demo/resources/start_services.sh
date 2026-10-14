#!/usr/bin/env bash
# =====================================================================
# Arranca los servicios de R1 y R2 dentro del host svc de Mininet.
# Desde la CLI de Mininet:   mininet> svc bash resources/start_services.sh
# (el directorio de trabajo debe ser la raíz del kit: ~/tel354-demo)
# =====================================================================
set -u
cd "$(dirname "$0")/.."
source demo.env
IFACE=$(ip -o -4 addr show | awk '/10\.200\.0\.10\//{print $2; exit}')
[ -z "$IFACE" ] && { echo "No se encontró 10.200.0.10: ¿se ejecuta dentro de svc?"; exit 1; }
mkdir -p /var/run/kea /var/log/kea /run/slapd /var/run/postgresql && chown openldap:openldap /run/slapd
chown postgres:postgres /var/run/postgresql

paso() { printf '%-28s' "$1"; }

paso "PostgreSQL (10.200.0.12)";  PGV=$(ls /etc/postgresql | sort -n | tail -1)
pg_ctlcluster --skip-systemctl-redirect "$PGV" main start >/dev/null 2>&1; sleep 2
pg_isready -h 10.200.0.12 -q && echo OK || echo FALLO

paso "OpenLDAP (10.200.0.11)"
pkill -x slapd 2>/dev/null; sleep 0.5
slapd -h "ldap://10.200.0.11/ ldaps://10.200.0.11/ ldapi:///" -u openldap -g openldap </dev/null >/dev/null 2>&1 && echo OK || echo FALLO

paso "FreeRADIUS (10.200.0.10)"
pkill -x freeradius 2>/dev/null; sleep 0.5
freeradius -l /var/log/freeradius/radius.log </dev/null >/dev/null 2>&1 && echo OK || echo FALLO

paso "Kea DHCPv4 (10.200.0.13)"
python3 kea/gen_kea_conf.py --iface "$IFACE" --pabellones 2 --hooks "${KEA_HOOKS:-/usr/lib/x86_64-linux-gnu/kea/hooks}" > /etc/kea/kea-dhcp4-tel354.conf
pkill -x kea-dhcp4 2>/dev/null; pkill -x kea-ctrl-agent 2>/dev/null; rm -f /var/run/kea/*.pid; sleep 0.5
setsid nohup kea-dhcp4 -c /etc/kea/kea-dhcp4-tel354.conf >/var/log/kea/kea-dhcp4.out 2>&1 < /dev/null &
sleep 1; setsid nohup kea-ctrl-agent -c kea/kea-ctrl-agent.conf >/var/log/kea/kea-ca.out 2>&1 < /dev/null &
sleep 1; pgrep -x kea-dhcp4 >/dev/null && echo OK || echo FALLO

paso "DNS dnsmasq (10.200.0.14)"
[ -f /run/dnsmasq-tel354.pid ] && kill $(cat /run/dnsmasq-tel354.pid) 2>/dev/null
dnsmasq --conf-file=resources/dnsmasq.conf --pid-file=/run/dnsmasq-tel354.pid </dev/null >/dev/null 2>&1 && echo OK || echo FALLO

paso "Recursos del campus"
[ -f /run/tel354-recursos.pid ] && kill $(cat /run/tel354-recursos.pid) 2>/dev/null
su postgres -c "psql -d ${PG_DB} -Atc \"SELECT json_agg(json_build_object('nombre',nombre,'ip',host(ip),'protocolo',protocolo,'puerto',puerto)) FROM recurso WHERE host(ip) <> '${IP_PG}'\"" > /run/tel354_recursos.json
setsid nohup python3 -u resources/recursos.py --json /run/tel354_recursos.json > /var/log/tel354-recursos.log 2>&1 < /dev/null &
echo $! > /run/tel354-recursos.pid
sleep 2; grep -c '^\[ok\]' /var/log/tel354-recursos.log | sed 's/$/ servicios/'
