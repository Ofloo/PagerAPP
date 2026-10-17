#!/usr/bin/env bash
# Run adb without installing anything on the host.
#
# Uses a long-lived helper container so the adb server keeps its device connections between
# calls (adb connect only persists in the server's memory, so `docker run --rm` would drop it).
#
# Usage:
#   docker/adb.sh devices
#   docker/adb.sh connect 192.168.1.50:5555
#   docker/adb.sh install -r dist/PagerAPP-release.apk
#   docker/adb.sh shell am start -n io.ofloo.pagerapp/.ui.MainActivity
#   docker/adb.sh logcat -d '*:E'
#
# Stop/remove the helper with: docker rm -f pagerapp-adb
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="pagerapp-android"
NAME="pagerapp-adb"

# Remote adb server (e.g. a Raspberry Pi with phones attached over USB):
#   ADB_SERVER_SOCKET=tcp:10.13.40.50:5037 docker/adb.sh devices
# When unset, adb runs its own local server inside the container as before.
ENV_ARGS=()
if [ -n "${ADB_SERVER_SOCKET:-}" ]; then
    ENV_ARGS+=(-e "ADB_SERVER_SOCKET=${ADB_SERVER_SOCKET}")
fi

# Locally-attached USB phones (e.g. one attached with `usbip attach`, which makes it appear as a
# local USB device). Set ADB_USB=1 to give the helper container access to /dev/bus/usb.
USB_ARGS=()
if [ "${ADB_USB:-0}" = "1" ]; then
    USB_ARGS+=(--privileged -v /dev/bus/usb:/dev/bus/usb)
fi

docker image inspect "${IMAGE}" >/dev/null 2>&1 || docker build -t "${IMAGE}" "${REPO_ROOT}/docker/android"

# Recreate the helper when it is not running, or when the requested adb server differs from the
# one baked into the running container (env is fixed at `docker run` time).
RUNNING="$(docker inspect -f '{{.State.Running}}' "${NAME}" 2>/dev/null || echo false)"
CURRENT_SOCKET="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "${NAME}" 2>/dev/null | sed -n 's/^ADB_SERVER_SOCKET=//p')"
CURRENT_USB="$(docker inspect -f '{{.HostConfig.Privileged}}' "${NAME}" 2>/dev/null || echo false)"
WANT_USB=false; [ "${ADB_USB:-0}" = "1" ] && WANT_USB=true
if [ "${RUNNING}" != "true" ] || [ "${CURRENT_SOCKET}" != "${ADB_SERVER_SOCKET:-}" ] || [ "${CURRENT_USB}" != "${WANT_USB}" ]; then
    docker rm -f "${NAME}" >/dev/null 2>&1 || true
    docker run -d --name "${NAME}" \
        --net=host \
        -v "${REPO_ROOT}:/repo" \
        -w /repo \
        "${ENV_ARGS[@]}" \
        "${USB_ARGS[@]}" \
        --entrypoint sleep \
        "${IMAGE}" infinity >/dev/null
fi

exec docker exec "${ENV_ARGS[@]}" "${NAME}" adb "$@"
