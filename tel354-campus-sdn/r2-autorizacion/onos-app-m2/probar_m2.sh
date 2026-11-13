#!/usr/bin/env bash
# Prueba del núcleo de M2 (matriz y Tabla 1) sin ONOS ni Maven: solo javac.
# Uso (desde la raíz del repositorio):  bash r2-autorizacion/onos-app-m2/probar_m2.sh
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
B=$RAIZ/r2-autorizacion/onos-app-m2/src
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -nowarn -d "$OUT" \
  $B/main/java/pe/edu/pucp/tel354/m2/nucleo/*.java $B/test/java/pe/edu/pucp/tel354/m2/nucleo/Tabla1Prueba.java 2>&1 \
  | grep -v "^Picked up\|^Note:" || true
java -Dstdout.encoding=UTF-8 -cp "$OUT" pe.edu.pucp.tel354.m2.nucleo.Tabla1Prueba 2>&1 | grep -v "^Picked up"
exit "${PIPESTATUS[0]}"
