#!/usr/bin/env bash
# =====================================================================
# Banco de pruebas de M1 sin ONOS ni Mininet (validación del núcleo).
#
# Reproduce los tramos de la topología con namespaces de red:
#   ctl-eth0 (172.16.0.10, namespace raíz)  <->  svc (10.200.0.10-14 y servidores)
#   h1-eth0 / h2-eth0 (namespaces h1, h2)   <->  s1-p2 / s1-p3 (puertos de as1)
# puente.py hace de switch as1 (lo que las reglas 900, 800 y 525 envían al
# controlador) y BancoM1 ejecuta el núcleo de M1 con los servicios reales
# (FreeRADIUS, OpenLDAP, PostgreSQL, Kea). Las reglas se imprimen en vez de
# instalarse. Requiere haber corrido antes la configuración de los servicios.
#
# Uso (root, desde la raíz del repositorio):
#   bash r1-identidad/onos-app-m1/banco/banco.sh red        # namespaces y servicios
#   bash r1-identidad/onos-app-m1/banco/banco.sh m1 CLASES  # CLASES: clases compiladas de M1 + onlab
#   printf 'alumno.demo\nAlumno2026!\n' | ip netns exec h1 bash r1-identidad/host/login.sh h1-eth0
# =====================================================================
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
AQUI="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
red)
  for n in svc h1 h2; do ip netns del $n 2>/dev/null || true; ip netns add $n; done
  ip link del ctl-eth0 2>/dev/null || true
  ip link add ctl-eth0 type veth peer name svc-eth0
  ip link set svc-eth0 netns svc
  ip addr add 172.16.0.10/16 dev ctl-eth0; ip link set ctl-eth0 up
  ip route replace 10.200.0.0/16 dev ctl-eth0
  ip netns exec svc ip addr add 10.200.0.10/16 dev svc-eth0
  for a in 0.5 0.11 0.12 0.13 0.14 1.10 1.20 2.10 2.20 3.10 3.20; do
    ip netns exec svc ip addr add 10.200.$a/16 dev svc-eth0
  done
  ip netns exec svc ip link set svc-eth0 up; ip netns exec svc ip link set lo up
  ip netns exec svc ip route add 172.16.0.0/16 dev svc-eth0
  i=1; for mac in 00:00:00:10:01:01 00:00:00:30:01:02; do
    ip link del s1-p$((i+1)) 2>/dev/null || true
    ip link add h$i-eth0 type veth peer name s1-p$((i+1))
    ip link set h$i-eth0 netns h$i; ip link set s1-p$((i+1)) up
    ip netns exec h$i ip link set lo up
    ip netns exec h$i ip link set h$i-eth0 address $mac
    ip netns exec h$i ip link set h$i-eth0 up
    i=$((i+1))
  done
  cd "$RAIZ" && ip netns exec svc bash scripts/servicios/start_services.sh
  ;;
m1)
  CLASES="${2:?indica el directorio de clases compiladas de M1 y de onlab-misc}"
  J=/usr/share/java
  CP="$CLASES:$J/guava.jar:$J/slf4j-api.jar:$J/slf4j-simple.jar:$J/postgresql.jar:$J/commons-lang3.jar"
  mkdir -p /tmp/banco-m1 && javac -nowarn -d /tmp/banco-m1 -cp "$CP" "$AQUI"/src/pe/edu/pucp/tel354/m1/banco/BancoM1.java
  python3 -u "$AQUI/puente.py" & PUENTE=$!
  trap 'kill $PUENTE 2>/dev/null' EXIT
  java -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS \
       -cp "/tmp/banco-m1:$CP" pe.edu.pucp.tel354.m1.banco.BancoM1
  ;;
*) sed -n 2,21p "$0" ;;
esac
