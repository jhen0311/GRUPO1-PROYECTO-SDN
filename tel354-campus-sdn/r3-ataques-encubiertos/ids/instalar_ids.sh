#!/usr/bin/env bash
# =====================================================================
# Instala el IDS de R3 (Suricata, pasivo) y su adaptador hacia M4a.
#
#   Slice (TEL354_ENV=una_instancia.env): en controller, escuchando la interfaz de datos
#     (IF_DATOS, ens4), por donde llega todo el tráfico hacia los servidores 10.200.x.y.
#   Piloto (VM-IDS): IDS_IF=eth0 (espejo del CORE2); ver suricata-slice.yaml.
#
# No edita /etc/suricata/suricata.yaml: la interfaz, las variables y las reglas van como
# parámetros de arranque en un drop-in de systemd (suricata.service.d/tel354.conf).
# ens3 (gestión) no se toca.
#
# Uso (como root, desde la raíz del repositorio):
#   bash r3-ataques-encubiertos/ids/instalar_ids.sh
#   bash r3-ataques-encubiertos/ids/instalar_ids.sh --desinstalar
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Ejecutar como root (sudo -i)."; exit 1; }
source ../../comun/config/${TEL354_ENV:-slice.env}
IFACE=${IDS_IF:-${IF_DATOS:-ens4}}
[ "$IFACE" = ens3 ] && { echo "ens3 es la interfaz de gestión: no se usa para el IDS."; exit 1; }
DROPIN_S=/etc/systemd/system/suricata.service.d/tel354.conf
DROPIN_A=/etc/systemd/system/ids-adapter.service.d/tel354.conf

if [ "${1:-}" = "--desinstalar" ]; then
  systemctl disable --now ids-adapter 2>/dev/null || true
  rm -f /etc/systemd/system/ids-adapter.service "$DROPIN_A" "$DROPIN_S" /etc/suricata/rules/tel354.rules
  systemctl daemon-reload
  systemctl stop suricata 2>/dev/null || true
  echo "IDS desinstalado (Suricata queda instalado pero detenido)."
  exit 0
fi

echo "=== Memoria disponible (Suricata con solo las reglas locales usa poco)"
free -h | head -2

echo "=== Suricata"
command -v suricata >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y suricata
suricata -V
install -m 644 local.rules /etc/suricata/rules/tel354.rules

# Variables de las reglas: la red de usuarios, los recursos web y el servidor de gestión TI
ARGS="-c /etc/suricata/suricata.yaml -S /etc/suricata/rules/tel354.rules"
ARGS="$ARGS --set vars.address-groups.HOME_NET=[10.0.0.0/8] --set vars.address-groups.EXTERNAL_NET=any"
ARGS="$ARGS --set vars.address-groups.HTTP_SERVERS=[10.200.1.0/24,10.200.2.0/24]"
ARGS="$ARGS --set vars.address-groups.SSH_SERVERS=[${IP_GESTION:-10.200.0.5}]"

echo "=== Prueba de la configuración"
suricata -T --af-packet="$IFACE" $ARGS 2>&1 | tail -2

mkdir -p "$(dirname "$DROPIN_S")"
cat > "$DROPIN_S" <<EOF
# TEL354 R3: Suricata pasivo en $IFACE, solo con las reglas locales (tel354.rules)
[Service]
ExecStart=
ExecStart=/usr/bin/suricata -D --af-packet=$IFACE $ARGS --pidfile /run/suricata.pid
EOF

echo "=== Adaptador (eve.json -> REST de M4a)"
if [ -n "${DOCKER_ONOS_1:-}" ]; then
  URLS="http://${DOCKER_ONOS_3}:8181/onos/campus-ids/v1/alertas,http://${DOCKER_ONOS_1}:8181/onos/campus-ids/v1/alertas,http://${DOCKER_ONOS_2}:8181/onos/campus-ids/v1/alertas"
else
  URLS="http://127.0.0.1:8181/onos/campus-ids/v1/alertas"
fi
mkdir -p /opt/tel354 "$(dirname "$DROPIN_A")"
install -m 755 ids_adapter.py /opt/tel354/ids_adapter.py
install -m 644 ids-adapter.service /etc/systemd/system/ids-adapter.service
cat > "$DROPIN_A" <<EOF
[Service]
Environment=ONOS_URLS=$URLS
Environment=ONOS_AUTH=${ONOS_AUTH:-onos:rocks}
Environment=SENSORES=$IFACE=slice
EOF
chmod 600 "$DROPIN_A"

echo "=== Servicios"
systemctl daemon-reload
systemctl enable suricata ids-adapter >/dev/null 2>&1
systemctl restart suricata
sleep 5
systemctl restart ids-adapter
systemctl --no-pager --lines=0 status suricata ids-adapter | grep -E "●|Active:"
echo "Interfaz: $IFACE · Reglas: $(grep -c '^alert' /etc/suricata/rules/tel354.rules) · Destino: $URLS"
echo "Alertas:  tail -f /var/log/suricata/eve.json | grep '\"alert\"'   ·   journalctl -u ids-adapter -f"
