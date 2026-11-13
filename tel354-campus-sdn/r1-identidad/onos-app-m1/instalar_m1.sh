#!/usr/bin/env bash
# =====================================================================
# Compila M1, carga su network config (generada del perfil TEL354_ENV)
# y lo instala y activa en ONOS 2.7 (en el clúster, en las 3 instancias).
# Requisitos: Java 11, Maven 3.6+, tel354-api instalado (comun/tel354-api/instalar_api.sh).
# Uso (como root en el Controlador, desde la raíz del repositorio):
#   bash r1-identidad/onos-app-m1/instalar_m1.sh            # compilar + instalar
#   bash r1-identidad/onos-app-m1/instalar_m1.sh --sin-compilar
#   bash r1-identidad/onos-app-m1/instalar_m1.sh --desinstalar
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../../comun/config/${TEL354_ENV:-slice.env}   # piloto: slice.env; una instancia: TEL354_ENV=una_instancia.env
APP=tel354.m1
OAR=target/tel354-m1-2.0.0.oar

if [ "${1:-}" = "--desinstalar" ]; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null && echo "M1 desinstalado." \
    || echo "M1 no estaba instalado."
  exit 0
fi

if [ "${1:-}" != "--sin-compilar" ]; then
  echo "=== Compilando M1"
  mvn -q clean install -DskipTests
fi
[ -f "$OAR" ] || { echo "No existe $OAR"; exit 1; }

echo "=== Network config de M1"
NETCFG=${M1_NETCFG:-/tmp/m1-netcfg.json}
[ -n "${M1_NETCFG:-}" ] || python3 gen_m1_netcfg.py > "$NETCFG"
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_URL/network/configuration" -d @"$NETCFG" && echo "cargada ($NETCFG)"

echo "=== Instalando y activando $APP"
curl -s -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null 2>&1 || true
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
     "$ONOS_URL/applications?activate=true" --data-binary @"$OAR" >/dev/null && echo "instalada y activa"

sleep 3
curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print("Estado:", a.get("state"), "| versión", a.get("version"))'
echo "En la consola de cada instancia:  apps -s -a | grep tel354   y   log:tail | grep M1"
