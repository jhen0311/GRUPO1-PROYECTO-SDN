#!/usr/bin/env bash
# =====================================================================
# Compila tel354-api (y lo deja en ~/.m2 para M1, M5 y las apps de R3),
# lo instala y activa en el clúster de ONOS 2.7 (una vez para las 3 instancias).
# Requisitos: Java 11, Maven 3.6+ y el clúster en marcha.
# Uso (como root en el Controlador, desde la raíz del repositorio):
#   bash comun/tel354-api/instalar_api.sh [--sin-compilar | --desinstalar]
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../config/${TEL354_ENV:-slice.env}   # piloto: slice.env; una instancia: TEL354_ENV=una_instancia.env
APP=tel354.api
OAR=target/tel354-api-2.0.0.oar

if [ "${1:-}" = "--desinstalar" ]; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null && echo "tel354-api desinstalado." \
    || echo "tel354-api no estaba instalado."
  exit 0
fi
if [ "${1:-}" != "--sin-compilar" ]; then
  echo "=== Compilando tel354-api (la primera vez Maven descarga las dependencias de ONOS 2.7.0)"
  mvn -q clean install -DskipTests
fi
[ -f "$OAR" ] || { echo "No existe $OAR"; exit 1; }
echo "=== Instalando y activando $APP"
# ONOS rechaza un .oar con la misma versión si la app ya existe: se retira antes. Retirar la API
# retira también las apps que dependen de ella (M1, M3, M5...): reinstalarlas después.
if curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" >/dev/null; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null
  echo "versión anterior retirada: reinstalar M1, M3 y M5"
  sleep 3
fi
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
     "$ONOS_URL/applications?activate=true" --data-binary @"$OAR" >/dev/null \
  || { echo "ONOS rechazó $OAR"; exit 1; }
echo "instalado y activo"
curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print("Estado:", a.get("state"), "| versión", a.get("version"))'
