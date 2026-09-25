#!/usr/bin/env bash
# Run adb without installing anything on the host: uses the Android SDK image with host
# networking so it can reach a VM or device on the LAN.
#
# Usage:
#   docker/adb.sh devices
#   docker/adb.sh connect 192.168.1.50:5555
#   docker/adb.sh install -r dist/PagerAPP-release.apk
#   docker/adb.sh logcat -d *:E
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="pagerapp-android"

# Build the image if missing.
docker image inspect "${IMAGE}" >/dev/null 2>&1 || docker build -t "${IMAGE}" "${REPO_ROOT}/docker/android"

exec docker run --rm --net=host \
    -v "${REPO_ROOT}:/repo" \
    -w /repo \
    -e ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}" \
    --entrypoint bash \
    "${IMAGE}" \
    -lc "adb \"\$@\"" _ "$@"
