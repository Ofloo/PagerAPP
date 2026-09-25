#!/usr/bin/env bash
# Drive a VM's VNC console without installing anything on the host.
#
# bhyve's VNC server allows only ONE client at a time, so every helper that connects must
# disconnect cleanly, and bulk work should happen in a single connection (`batch`).
#
# Usage (HOST defaults to 10.13.40.1:5900):
#   docker/vnc.sh capture                    # screenshot -> /tmp/opencode/vnc/screen.png
#   docker/vnc.sh batch "key enter" "pause 2" "key down" "key enter"
#   docker/vnc.sh key enter
#   docker/vnc.sh type "text"
#   docker/vnc.sh click 512 400              # note: bhyve uses a relative PS/2 mouse; prefer keys
#
# vncdotool key names: enter esc tab up down left right space f2 etc.
set -euo pipefail

HOST="${VNC_HOST:-10.13.40.1::5900}"
OUT_DIR="${VNC_OUT:-/tmp/opencode/vnc}"
IMAGE="${VNC_IMAGE:-opencode-vncdo}"
mkdir -p "${OUT_DIR}"

# Build a tiny image once so we do not pip-install on every call.
docker image inspect "${IMAGE}" >/dev/null 2>&1 || {
    printf 'FROM python:3.12-slim\nRUN pip install --no-cache-dir vncdotool\n' | docker build -t "${IMAGE}" - >/dev/null
}

# Run one vncdo invocation with all args (single connection).
vncdo() {
    docker run --rm -v "${OUT_DIR}:/out" "${IMAGE}" \
        vncdo -s "${HOST}" "$@"
}

cmd="${1:-capture}"; shift || true
case "${cmd}" in
    capture) vncdo capture /out/screen.png; echo "${OUT_DIR}/screen.png" ;;
    batch)   vncdo "$@" ;;
    key)     vncdo key "$1" ;;
    type)    vncdo type "$1" ;;
    move)    vncdo move "$1" "$2" ;;
    click)   vncdo move "$1" "$2" click 1 ;;
    pause)   vncdo pause "$1" ;;
    *)       echo "unknown command: ${cmd}" >&2; exit 1 ;;
esac
