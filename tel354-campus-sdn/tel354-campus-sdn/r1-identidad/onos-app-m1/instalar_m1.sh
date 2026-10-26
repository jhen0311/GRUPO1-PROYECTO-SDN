#!/usr/bin/env bash
# =====================================================================
# Compila M1, lo instala y activa en ONOS y carga su network config.
# Requisitos: ONOS 1.13 en marcha (ventana ONOS), Maven y Java 8.
# Uso (como root, desde la raíz del repositorio):
#   bash r1-identidad/onos-app-m1/instalar_m1.sh            # compilar + instalar
#   bash r1-identidad/onos-app-m1/instalar_m1.sh --sin-compilar
#   bash r1-identidad/onos-app-m1/instalar_m1.sh --desinstalar
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../../comun/config/demo.env
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
ONOS_REST=${ONOS_URL:-http://127.0.0.1:8181/onos/v1}
APP=tel354.m1
OAR=target/tel354-m1-1.0.0.oar

if [ "${1:-}" = "--desinstalar" ]; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_REST/applications/$APP" >/dev/null && echo "M1 desinstalado." \
    || echo "M1 no estaba instalado."
  exit 0
fi

if [ "${1:-}" != "--sin-compilar" ]; then
  echo "=== Compilando M1 (la primera vez Maven descarga las dependencias de ONOS 1.13.0)"
  mvn -q clean install -DskipTests
fi
[ -f "$OAR" ] || { echo "No existe $OAR"; exit 1; }

echo "=== Network config de M1"
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_REST/network/configuration" -d @m1-netcfg.json && echo "cargada"

echo "=== Instalando y activando $APP"
curl -s -u "$ONOS_AUTH" -X DELETE "$ONOS_REST/applications/$APP" >/dev/null 2>&1 || true
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
     "$ONOS_REST/applications?activate=true" --data-binary @"$OAR" >/dev/null && echo "instalada y activa"

sleep 2
curl -sf -u "$ONOS_AUTH" "$ONOS_REST/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print("Estado:", a.get("state"), "| versión", a.get("version"))'
echo "En la ventana ONOS:  apps -s -a | grep tel354   y   log:tail | grep M1"
