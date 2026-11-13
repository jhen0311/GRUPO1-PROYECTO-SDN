#!/usr/bin/env bash
# =====================================================================
# Compila M5, carga la network config de R3 y lo instala y activa en el
# clúster de ONOS 2.7. Requiere tel354-api y tel354.m1 activos.
# Uso (como root en el Controlador, desde la raíz del repositorio):
#   bash r3-ataques-encubiertos/onos-app-m5/instalar_m5.sh [--sin-compilar | --desinstalar]
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../../comun/config/${TEL354_ENV:-slice.env}   # piloto: slice.env; una instancia: TEL354_ENV=una_instancia.env
APP=tel354.m5
OAR=target/tel354-m5-2.0.0.oar

if [ "${1:-}" = "--desinstalar" ]; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null && echo "M5 desinstalado." \
    || echo "M5 no estaba instalado."
  exit 0
fi
if [ "${1:-}" != "--sin-compilar" ]; then
  echo "=== Prueba de la evaluación (sin ONOS)"
  bash probar_evaluador.sh | tail -1
  echo "=== Compilando M5"
  mvn -q clean install -DskipTests
fi
[ -f "$OAR" ] || { echo "No existe $OAR"; exit 1; }

echo "=== Network config de R3 (M4a y M5)"
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_URL/network/configuration" -d @../config/r3-netcfg.json && echo "cargada"

echo "=== Instalando y activando $APP"
curl -s -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null 2>&1 || true
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
     "$ONOS_URL/applications?activate=true" --data-binary @"$OAR" >/dev/null && echo "instalada y activa"
sleep 3
curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print("Estado:", a.get("state"), "| versión", a.get("version"))'
B=${ONOS_URL%/onos/v1}
echo "API de TI: curl -u $ONOS_AUTH $B/onos/campus/v1/r3/modo"
