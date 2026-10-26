#!/usr/bin/env bash
# =====================================================================
# Reúne en evidencias/<fecha>/ las métricas de R1 y R2 en modo automático
# (para la Tabla 19 del AVZ08, el AVZ07 y el Informe Parcial).
# Uso (VM, namespace raíz, ventana 3):
#   bash scripts/evidencias_r1r2.sh                       # foto del estado: flows, sesiones, latencia
#   bash scripts/evidencias_r1r2.sh --radius 60 'Alumno2026!' alumno.demo
#        captura RADIUS 60 s (hacer un login en ese lapso) y cuenta clave y usuario en claro
# =====================================================================
set -u
cd "$(dirname "$0")/.."
source comun/config/demo.env
DIR="evidencias/$(date +%Y%m%d-%H%M)"; mkdir -p "$DIR"
R="$DIR/resumen.txt"
export PYTHONIOENCODING=utf-8 LANG=C.UTF-8

if [ "${1:-}" = "--radius" ]; then
  SEG=${2:-60}; CLAVE=${3:?clave}; USUARIO=${4:?usuario}
  echo "Capturando RADIUS en ctl-eth0 durante $SEG s: haz ahora un login en otra ventana..."
  timeout "$SEG" tcpdump -ni ctl-eth0 -w "$DIR/radius.pcap" udp port 1812 2>/dev/null
  P=$(tcpdump -r "$DIR/radius.pcap" 2>/dev/null | wc -l)
  C=$(tcpdump -A -r "$DIR/radius.pcap" 2>/dev/null | grep -c -- "$CLAVE")
  U=$(tcpdump -A -r "$DIR/radius.pcap" 2>/dev/null | grep -c -- "$USUARIO")
  { echo "== Confidencialidad RADIUS ($(date '+%F %T'))"
    echo "paquetes RADIUS: $P   apariciones de la clave: $C   apariciones del usuario: $U"
    echo "esperado: clave 0; usuario >= 1 (solo en el Access-Accept)"; } | tee -a "$R"
  exit 0
fi

{ echo "== Evidencias R1/R2 · $(date '+%F %T') · $(hostname)"; echo; } > "$R"

echo "== Flow entries frente al modelo (contar_flows.py)" | tee -a "$R"
python3 r2-autorizacion/tests/contar_flows.py 2>&1 | tee -a "$R"; echo | tee -a "$R"

for sw in as1 as2 core; do
  ovs-ofctl -O OpenFlow13 --names --rsort=priority dump-flows $sw > "$DIR/dump-flows-$sw.txt" 2>&1
  echo "$sw: $(grep -c 'priority=' "$DIR/dump-flows-$sw.txt") entradas (detalle en dump-flows-$sw.txt)" | tee -a "$R"
done; echo | tee -a "$R"

echo "== Reglas en ONOS por aplicación y estado" | tee -a "$R"
curl -s -u "$ONOS_AUTH" "$ONOS_URL/flows" | python3 -c '
import json,sys,collections
f=json.load(sys.stdin)["flows"]; c=collections.Counter((x["appId"],x["state"]) for x in f)
for (a,s),n in sorted(c.items()): print(f"  {a:28} {s:12} {n}")' | tee -a "$R"; echo | tee -a "$R"

echo "== Sesiones (tabla sesion, últimas 15)" | tee -a "$R"
su postgres -c "psql -X -d ${PG_DB} -c \"SELECT s.id, u.uid_ldap, r.nombre AS rol, s.switch_dpid AS dpid, s.puerto, host(s.ip) AS ip, s.vlan_id AS vlan, s.estado, s.motivo_cierre AS motivo, to_char(s.inicio,'HH24:MI:SS') AS inicio, to_char(s.fin,'HH24:MI:SS') AS fin FROM sesion s JOIN usuario u ON u.id=s.usuario_id JOIN rol r ON r.id=s.rol_efectivo_id ORDER BY s.id DESC LIMIT 15\"" 2>&1 | tee -a "$R"

echo "== Latencia EAPOL-Start → SessionReady (latencia_m1.py)" | tee -a "$R"
python3 r1-identidad/tests/latencia_m1.py 2>&1 | tee -a "$R"

echo; echo "Guardado en $DIR (resumen.txt y dump-flows-*.txt)."
