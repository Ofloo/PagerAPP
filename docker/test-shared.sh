#!/usr/bin/env bash
# Run the shared PagerAPP logic tests inside Docker (JVM only, no emulator/simulator).
# Canonical local test command per AGENTS.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="gradle:8.10-jdk17"

docker run --rm \
    -v "${REPO_ROOT}/shared:/workspace" \
    -v pagerapp-gradle-cache:/home/gradle/.gradle \
    -w /workspace \
    "${IMAGE}" \
    gradle --no-daemon test "$@"
