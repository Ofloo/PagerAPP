#!/usr/bin/env bash
# Build the PagerAPP Android app inside Docker.
# Mounts the repo root because :app depends on :shared (../shared).
# Usage: docker/build-android.sh [gradle-task]   (default: assembleDebug)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="pagerapp-android"
TASK="${1:-assembleDebug}"

docker build -t "${IMAGE}" "${REPO_ROOT}/docker/android"

# A build stopped with SIGKILL leaves a stale journal lock in the shared Gradle cache, and every
# later build then dies with "Timeout waiting to lock journal cache". Clear it — but only when no
# other build is running, so a parallel build is never disturbed.
# `set -e` would abort on the loop's non-zero exit when nothing matches, hence the `|| true`.
BUSY="$(docker ps --format '{{.ID}}' | while read -r id; do
    docker inspect -f '{{.Config.Cmd}}' "$id" 2>/dev/null | grep -q gradlew && { echo busy; break; }
done || true)"
if [ -z "${BUSY}" ]; then
    docker run --rm -v pagerapp-gradle-cache:/cache "${IMAGE}" \
        sh -c 'rm -f /cache/caches/journal-1/journal-1.lock' >/dev/null 2>&1 || true
fi

docker run --rm \
    -v "${REPO_ROOT}:/repo" \
    -v pagerapp-gradle-cache:/root/.gradle \
    -w /repo/android \
    "${IMAGE}" \
    ./gradlew --no-daemon "${TASK}"
