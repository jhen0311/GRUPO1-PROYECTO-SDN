#!/usr/bin/env bash
# =====================================================================
# Compila tel354.base (M7 + M8), carga su network config (generada del
# perfil TEL354_ENV) y la instala y activa. Va después de tel354-api y
# antes de M1: api -> base -> m1 -> m3 -> m5 (para retirar, al revés).
# Uso (como root en el controlador, desde la raíz del repositorio):
#   bash comun/onos-app-base/instalar_base.sh             # compilar + instalar
#   bash comun/onos-app-base/instalar_base.sh --migrar    # además retira lo que dejó base_auto.py
#   bash comun/onos-app-base/instalar_base.sh --sin-compilar
#   bash comun/onos-app-base/instalar_base.sh --desinstalar
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../config/${TEL354_ENV:-slice.env}   # una instancia: TEL354_ENV=una_instancia.env
APP=tel354.base
OAR=target/tel354-base-2.0.0.oar

if [ "${1:-}" = "--desinstalar" ]; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null && echo "tel354.base desinstalada." \
    || echo "tel354.base no estaba instalada."
  exit 0
fi
if [ "${1:-}" = "--migrar" ]; then
  # Reglas y meters que base_auto.py instaló por REST (appId tel354.base y meters de REST)
  echo "=== Retirando el régimen que dejó base_auto.py"
  curl -s -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/flows/application/tel354.base" >/dev/null || true
  ONOS_URL="$ONOS_URL" ONOS_AUTH="$ONOS_AUTH" python3 - <<'PY'
import os, requests
U, A = os.environ["ONOS_URL"], tuple(os.environ["ONOS_AUTH"].split(":"))
n = 0
for m in requests.get(f"{U}/meters", auth=A, timeout=10).json().get("meters", []):
    if m.get("appId") == "org.onosproject.rest":
        requests.delete(f"{U}/meters/{m['deviceId']}/{m['id']}", auth=A, timeout=10); n += 1
print(f"{n} meters de REST retirados")
PY
fi
if [ "${1:-}" != "--sin-compilar" ]; then
  echo "=== Prueba del conjunto de reglas (sin ONOS)"
  bash probar_base.sh | tail -1
  echo "=== Banco de pruebas de la app (ONOS y switches simulados, ~1 min)"
  bash banco/banco.sh | tail -1
  echo "=== Compilando tel354.base"
  mvn -q clean install -DskipTests
fi
[ -f "$OAR" ] || { echo "No existe $OAR"; exit 1; }

echo "=== Ajuste del núcleo: el ARP lo atrapa la regla 800 (con meter), no la 40000 de ONOS"
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_URL/configuration/org.onosproject.provider.host.impl.HostLocationProvider" \
     -d '{"requestInterceptsEnabled": "false"}' >/dev/null && echo "hecho"

echo "=== Network config de tel354.base"
NETCFG=${BASE_NETCFG:-/tmp/base-netcfg.json}
[ -n "${BASE_NETCFG:-}" ] || python3 gen_base_netcfg.py > "$NETCFG"
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_URL/network/configuration" -d @"$NETCFG" && echo "cargada ($NETCFG)"

echo "=== Instalando y activando $APP"
# M1 (y con él M5) requiere tel354.base: retirarla los desinstala. Hay que reinstalarlos después.
if curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" >/dev/null; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null
  echo "versión anterior retirada: reinstalar M1, M3 y M5"
  sleep 3
fi
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
     "$ONOS_URL/applications?activate=true" --data-binary @"$OAR" >/dev/null && echo "instalada y activa"
sleep 3
curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print("Estado:", a.get("state"), "| versión", a.get("version"))'
echo "En la consola de ONOS:  tel354:base   y   tel354:base -r"
