#!/usr/bin/env bash
# =====================================================================
# Banco de pruebas de tel354.base (M7 + M8) sin ONOS, sin switches y sin
# Maven: solo javac. Compila la app real (src/main) contra dobles de la
# API de ONOS (banco/src/org/...) y la ejecuta sobre un ONOS y unos
# switches simulados (SimOnos), recorriendo los escenarios del plan de
# pruebas y los casos de robustez (BancoBase).
#
# Los dobles imitan ONOS 2.7.0 en lo que la app usa; no lo sustituyen: la
# compilación real (Maven) y las pruebas en el slice siguen siendo necesarias.
#
# Uso (desde la raíz del repositorio; tarda cerca de un minuto):
#   bash comun/onos-app-base/banco/banco.sh        # resumen
#   bash comun/onos-app-base/banco/banco.sh -v     # además, el log de la app
# =====================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
APP=$RAIZ/comun/onos-app-base
API=$RAIZ/comun/tel354-api/src/main/java/pe/edu/pucp/tel354/api
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -nowarn -d "$OUT" \
  $(find "$APP/banco/src" "$APP/src/main/java" -name '*.java') "$API/Pipeline.java" "$API/Cookies.java" 2>&1 \
  | grep -v "^Picked up\|^Note:" || true
[ -f "$OUT/pe/edu/pucp/tel354/base/banco/BancoBase.class" ] || { echo "No compiló"; exit 1; }
java -Dstdout.encoding=UTF-8 -cp "$OUT" pe.edu.pucp.tel354.base.banco.BancoBase "$@" 2>&1 | grep -v "^Picked up"
exit "${PIPESTATUS[0]}"
