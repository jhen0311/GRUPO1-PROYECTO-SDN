#!/usr/bin/env bash
# =====================================================================
# "Encender" un equipo de la topología: abre una terminal dentro del
# namespace de red del host de Mininet y lanza el inicio de sesión.
# Los hosts de Mininet son namespaces de red (no contenedores): se
# entra por el PID de su shell, aunque todavía no tengan IP.
#
# Uso (en el namespace raíz de la VM, con la topología levantada):
#   bash r1-identidad/host/entrar.sh h1            # login y luego una shell en h1
#   bash r1-identidad/host/entrar.sh h1 --shell    # solo la shell, sin login
# =====================================================================
set -u
H="${1:?Uso: entrar.sh <host> [--shell]}"
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
PID=$(pgrep -f "mininet:${H}\$" | head -1)
[ -n "$PID" ] || { echo "No existe el host $H (¿está levantada la topología?)."; exit 1; }

if [ "${2:-}" = "--shell" ]; then
  exec nsenter -t "$PID" -n -- env LANG=C.UTF-8 PYTHONIOENCODING=utf-8 PS1="[$H] \\w# " bash --norc -i
fi
exec nsenter -t "$PID" -n -- env LANG=C.UTF-8 PYTHONIOENCODING=utf-8 PS1="[$H] \\w# " bash --norc -i -c \
  "cd '$RAIZ'; bash r1-identidad/host/login.sh ${H}-eth0; exec env PS1='[$H] \\w# ' bash --norc -i"
