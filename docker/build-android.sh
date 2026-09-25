#!/usr/bin/env bash
# Build the PagerAPP Android app inside Docker.
# Mounts the repo root because :app depends on :shared (../shared).
# Usage: docker/build-android.sh [gradle-task]   (default: assembleDebug)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="pagerapp-android"
TASK="${1:-assembleDebug}"

docker build -t "${IMAGE}" "${REPO_ROOT}/docker/android"

docker run --rm \
    -v "${REPO_ROOT}:/repo" \
    -v pagerapp-gradle-cache:/root/.gradle \
    -w /repo/android \
    "${IMAGE}" \
    ./gradlew --no-daemon "${TASK}"
