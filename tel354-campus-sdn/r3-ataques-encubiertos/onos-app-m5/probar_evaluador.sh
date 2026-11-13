#!/usr/bin/env bash
# Prueba de la evaluación de M5 (Tablas 15 y 16 del LLSD R3) sin ONOS ni Maven: solo javac.
# Uso (desde la raíz del repositorio):  bash r3-ataques-encubiertos/onos-app-m5/probar_evaluador.sh
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
A=$RAIZ/comun/tel354-api/src/main/java/pe/edu/pucp/tel354/api/r3
M=$RAIZ/r3-ataques-encubiertos/onos-app-m5/src
OUT=$(mktemp -d)
javac -encoding UTF-8 -d "$OUT" $A/Criticidad.java $A/Nivel.java $A/NivelAtaque.java $A/TipoAtaque.java \
  $M/main/java/pe/edu/pucp/tel354/m5/Criticidades.java $M/main/java/pe/edu/pucp/tel354/m5/Evaluador.java \
  $M/test/java/pe/edu/pucp/tel354/m5/EvaluadorPrueba.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" pe.edu.pucp.tel354.m5.EvaluadorPrueba
rm -rf "$OUT"
