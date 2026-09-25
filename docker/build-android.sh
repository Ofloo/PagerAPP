#!/usr/bin/env bash
# Build the PagerAPP Android APK inside Docker.
# Usage: docker/build-android.sh [gradle-task]   (default: assembleDebug)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="pagerapp-android"
TASK="${1:-assembleDebug}"

docker build -t "${IMAGE}" "${REPO_ROOT}/docker/android"

docker run --rm \
    -v "${REPO_ROOT}/android:/workspace" \
    -w /workspace \
    "${IMAGE}" \
    ./gradlew "${TASK}"
