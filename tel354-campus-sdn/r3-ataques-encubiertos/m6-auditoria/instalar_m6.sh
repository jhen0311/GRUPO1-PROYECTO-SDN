#!/usr/bin/env bash
# =====================================================================
# Instala M6 (auditoría) como servicio systemd en el host de VM-CLUSTER.
# M6 no es una app de ONOS: lee el log de onos-1..3 y la REST de M5, y
# escribe en auth_log con el rol onos_m6.
# Uso (como root en el host de VM-CLUSTER, desde la raíz del repositorio):
#   bash r3-ataques-encubiertos/m6-auditoria/instalar_m6.sh             # instala y arranca
#   bash r3-ataques-encubiertos/m6-auditoria/instalar_m6.sh --probar    # solo pruebas, sin instalar
#   bash r3-ataques-encubiertos/m6-auditoria/instalar_m6.sh --desinstalar
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Ejecutar como root (sudo -i)."; exit 1; }
DEST=/opt/tel354/m6-auditoria

if [ "${1:-}" = "--desinstalar" ]; then
  systemctl disable --now m6-auditoria 2>/dev/null || true
  rm -f /etc/systemd/system/m6-auditoria.service && systemctl daemon-reload
  echo "M6 desinstalado (se conservan /etc/tel354/m6.env y /var/lib/tel354/m6-estado.json)."
  exit 0
fi

echo "=== Pruebas de M6 (sin ONOS ni PostgreSQL)"
python3 tests/test_m6.py 2>&1 | tail -3
[ "${1:-}" = "--probar" ] && exit 0

echo "=== Dependencias"
python3 -c "import psycopg2" 2>/dev/null || apt-get install -y python3-psycopg2
command -v docker >/dev/null || { echo "Falta docker: M6 va en el host de VM-CLUSTER"; exit 1; }
[ -f /etc/tel354/certs/ca.pem ] || echo "AVISO: falta /etc/tel354/certs/ca.pem (scripts/gen_certs.sh)"

echo "=== Archivos"
mkdir -p "$DEST" /etc/tel354 /var/lib/tel354
cp m6_colector.py m6_registro.py README.md "$DEST/"
if [ ! -f /etc/tel354/m6.env ]; then
  # Primera vez: la plantilla, ajustada al perfil (TEL354_ENV). Con una instancia (una_instancia.env)
  # el contenedor es "onos" con --network host; con el clúster (slice.env), onos-1..3 en la red Docker.
  source ../../comun/config/${TEL354_ENV:-slice.env}
  cp m6.env.ejemplo /etc/tel354/m6.env
  sed -i "s|^PG_HOST=.*|PG_HOST=${IP_PG}|; s|^PG_DB=.*|PG_DB=${PG_DB:-campus}|" /etc/tel354/m6.env
  if [ -z "${DOCKER_ONOS_1:-}" ]; then
    sed -i "s|^CONTENEDORES=.*|CONTENEDORES=onos|; s|^ONOS_REST=.*|ONOS_REST=http://127.0.0.1:8181|" /etc/tel354/m6.env
  else
    sed -i "s|^ONOS_REST=.*|ONOS_REST=http://${DOCKER_ONOS_1}:8181 http://${DOCKER_ONOS_2}:8181 http://${DOCKER_ONOS_3}:8181|" \
      /etc/tel354/m6.env
  fi
  echo "creada /etc/tel354/m6.env para el perfil ${TEL354_ENV:-slice.env}"
fi
chmod 600 /etc/tel354/m6.env
cp m6-auditoria.service /etc/systemd/system/

echo "=== Comprobación de PostgreSQL (rol onos_m6)"
python3 - <<'PY' || echo "AVISO: sin conexión a PostgreSQL; M6 retiene hasta 10 000 filas y reintenta"
import sys; sys.path.insert(0, "/opt/tel354/m6-auditoria")
import m6_colector as C, psycopg2
c = C.leer_config("/etc/tel354/m6.env")
cx = psycopg2.connect(f"host={c['PG_HOST']} dbname={c['PG_DB']} user={c['PG_USUARIO']} password={c['PG_CLAVE']} "
                      f"sslmode={c['PG_SSLMODE']} sslrootcert={c['PG_CA']}", connect_timeout=5)
print("conexión OK:", cx.server_version)
PY

echo "=== Servicio"
systemctl daemon-reload
systemctl enable --now m6-auditoria
sleep 2
systemctl --no-pager --lines=5 status m6-auditoria | sed -n 1,8p
echo "Log:  journalctl -u m6-auditoria -f"
