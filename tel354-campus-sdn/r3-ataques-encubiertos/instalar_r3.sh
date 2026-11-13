#!/usr/bin/env bash
# =====================================================================
# Compila e instala las apps de R3 en el clúster, en el orden de sus
# dependencias: M2 -> M3 -> M4a -> M5 (tel354-api y M1 ya instalados).
# M2 vive en r2-autorizacion/onos-app-m2 (es de R2): se instala con su propio instalar_m2.sh.
# M5 toma a M4a y M3 en caliente (referencias opcionales), así que no
# hace falta reinstalarlo si ya estaba activo.
# Uso (como root en el Controlador, desde la raíz del repositorio):
#   bash r3-ataques-encubiertos/instalar_r3.sh [m2 m3 m4a m5]
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ../comun/config/${TEL354_ENV:-slice.env}   # piloto: slice.env; una instancia: TEL354_ENV=una_instancia.env
APPS=${*:-m2 m3 m4a m5}
for m in $APPS; do
  echo "=== $m"
  if [ "$m" = "m5" ]; then bash onos-app-m5/instalar_m5.sh; continue; fi
  if [ "$m" = "m2" ]; then bash ../r2-autorizacion/onos-app-m2/instalar_m2.sh; continue; fi
  (cd onos-app-$m && mvn -q clean install -DskipTests)
  OAR=onos-app-$m/target/tel354-$m-2.0.0.oar
  curl -s -u "$ONOS_AUTH" -X DELETE "$ONOS_URL/applications/tel354.$m" >/dev/null 2>&1 || true
  curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/octet-stream" \
       "$ONOS_URL/applications?activate=true" --data-binary @"$OAR" >/dev/null && echo "tel354.$m instalada y activa"
done
curl -sf -u "$ONOS_AUTH" -X POST -H "Content-Type: application/json" \
     "$ONOS_URL/network/configuration" -d @config/r3-netcfg.json >/dev/null && echo "r3-netcfg.json cargada"
