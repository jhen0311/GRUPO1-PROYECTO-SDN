#!/usr/bin/env bash
# Detiene los servicios de la demo (dentro de svc: mininet> svc bash scripts/servicios/stop_services.sh)
for f in /run/tel354-recursos.pid /run/dnsmasq-tel354.pid; do [ -f $f ] && kill $(cat $f) 2>/dev/null; rm -f $f; done
pkill -x kea-ctrl-agent; pkill -x kea-dhcp4; pkill -x freeradius; pkill -x slapd
pg_ctlcluster --skip-systemctl-redirect "$(ls /etc/postgresql | sort -n | tail -1)" main stop
echo "Servicios detenidos."
