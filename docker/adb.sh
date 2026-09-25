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

docker image inspect "${IMAGE}" >/dev/null 2>&1 || docker build -t "${IMAGE}" "${REPO_ROOT}/docker/android"

if [ "$(docker inspect -f '{{.State.Running}}' "${NAME}" 2>/dev/null || echo false)" != "true" ]; then
    docker rm -f "${NAME}" >/dev/null 2>&1 || true
    docker run -d --name "${NAME}" \
        --net=host \
        -v "${REPO_ROOT}:/repo" \
        -w /repo \
        --entrypoint sleep \
        "${IMAGE}" infinity >/dev/null
fi

exec docker exec "${NAME}" adb "$@"
