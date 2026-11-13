#!/usr/bin/env bash
# Prueba del conjunto de reglas de M7 y M8 (Regimen) sin ONOS: solo javac.
# Necesita tel354-api compilado (~/.m2) y onlab-misc, que Maven descarga al compilar la API.
# Uso (desde la raíz del repositorio):  bash comun/onos-app-base/probar_base.sh
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
M2=${M2_REPO:-$HOME/.m2/repository}
CP=$(find "$M2/pe/edu/pucp/tel354/tel354-api" "$M2/org/onosproject/onlab-misc" -name '*.jar' 2>/dev/null | tr '\n' ':')
[ -n "$CP" ] || { echo "Falta tel354-api en $M2: bash comun/tel354-api/instalar_api.sh"; exit 1; }
B=$RAIZ/comun/onos-app-base/src
OUT=$(mktemp -d)
javac -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" \
  $B/main/java/pe/edu/pucp/tel354/base/nucleo/Regla.java $B/main/java/pe/edu/pucp/tel354/base/nucleo/Regimen.java \
  $B/test/java/pe/edu/pucp/tel354/base/nucleo/RegimenPrueba.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" pe.edu.pucp.tel354.base.nucleo.RegimenPrueba
rm -rf "$OUT"
