#!/usr/bin/env bash
# =====================================================================
# Compila tel354.m2 (M2 · autorización, R2), carga su network config
# (generada del perfil TEL354_ENV) y la instala y activa.
# Orden: api -> base -> m1 -> m2 -> m3 -> m4a -> m5 (para retirar, al revés).
# ONOS lo impone: tel354.m2 declara tel354.base y tel354.m1 en onos.app.requires.
# Uso (como root en el controlador, desde la raíz del repositorio):
#   bash r2-autorizacion/onos-app-m2/instalar_m2.sh             # compilar + instalar
#   bash r2-autorizacion/onos-app-m2/instalar_m2.sh --migrar    # además retira el puente base_auto.py --solo-tabla1
#   bash r2-autorizacion/onos-app-m2/instalar_m2.sh --sin-compilar
#   bash r2-autorizacion/onos-app-m2/instalar_m2.sh --desinstalar
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../../comun/config/${TEL354_ENV:-slice.env}   # una instancia: TEL354_ENV=una_instancia.env
APP=tel354.m2
OAR=target/tel354-m2-2.0.0.oar

if [ "${1:-}" = "--desinstalar" ]; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null && echo "tel354.m2 desinstalada." \
    || echo "tel354.m2 no estaba instalada."
  exit 0
fi
if [ "${1:-}" = "--migrar" ]; then
  # La Tabla 1 y sus meters pasan del puente REST (appId tel354.tabla1) a M2: no pueden convivir,
  # porque OVS trata como la misma regla dos con igual tabla, prioridad y coincidencia.
  echo "=== Retirando el puente de la Tabla 1 (base_auto.py --solo-tabla1)"
  (cd ../.. && python3 r2-autorizacion/proactivo/base_auto.py --solo-tabla1 --borrar) || true
fi
if [ "${1:-}" != "--sin-compilar" ]; then
  echo "=== Prueba del núcleo de M2 (sin ONOS)"
  bash probar_m2.sh | tail -1
  echo "=== Compilando tel354.m2"
  mvn -q clean install -DskipTests
fi
[ -f "$OAR" ] || { echo "No existe $OAR"; exit 1; }

echo "=== Network config de tel354.m2"
NETCFG=${M2_NETCFG:-/tmp/m2-netcfg.json}
[ -n "${M2_NETCFG:-}" ] || python3 gen_m2_netcfg.py > "$NETCFG"
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_URL/network/configuration" -d @"$NETCFG" && echo "cargada ($NETCFG)"

echo "=== Instalando y activando $APP"
if curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" >/dev/null; then
  curl -sf -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/$APP" >/dev/null
  echo "versión anterior retirada"
  sleep 3
fi
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
     "$ONOS_URL/applications?activate=true" --data-binary @"$OAR" >/dev/null && echo "instalada y activa"
sleep 3
curl -sf -u "$ONOS_AUTH" "$ONOS_URL/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print("Estado:", a.get("state"), "| versión", a.get("version"))'
echo "En la consola de ONOS:  tel354:m2   y   tel354:m2 -r"
