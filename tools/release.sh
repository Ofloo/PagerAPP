#!/usr/bin/env bash
# Build, publish and upload PagerAPP in one step.
#
#   tools/release.sh                 # build + upload APK to share, upload AAB to Play (internal)
#   tools/release.sh --bump          # also increment versionCode first
#   TRACK=production tools/release.sh
#
# Play upload needs a service-account JSON at ./play-service-account.json whose e-mail has been
# invited in Play Console with "Release to testing tracks" (or "Release to production").
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PACKAGE="io.ofloo.pagerapp"
TRACK="${TRACK:-internal}"
KEY="${PLAY_KEY:-$REPO_ROOT/play-service-account.json}"

BUMP=0
for a in "$@"; do [ "$a" = "--bump" ] && BUMP=1; done

if [ "$BUMP" = 1 ]; then
    python3 - <<'PY'
import pathlib, re
p = pathlib.Path("android/app/build.gradle.kts")
s = p.read_text()

m = re.search(r"versionCode = (\d+)", s)
new = int(m.group(1)) + 1
s = s[:m.start(1)] + str(new) + s[m.end(1):]

# Keep the human-visible version in step with the build, so "About" shows at a glance
# whether the installed app is the one that was just released.
n = re.search(r'versionName = "([^"]*)"', s)
if n:
    s = s[:n.start(1)] + f"0.1.{new}" + s[n.end(1):]

p.write_text(s)
print(f"versionCode -> {new}, versionName -> 0.1.{new}")
PY
fi

if [ ! -f "$KEY" ]; then
    echo "Play service-account key not found at $KEY" >&2
    exit 1
fi
if [ ! -f android/keystore.properties ]; then
    echo "Android release signing configuration not found" >&2
    exit 1
fi

if [ "$BUMP" = 0 ]; then
    echo "Refusing to release without --bump; the Play versionCode must increase." >&2
    exit 1
fi

echo "== building signed AAB =="
docker/build-android.sh bundleRelease

AAB="android/app/build/outputs/bundle/release/app-release.aab"
python3 tools/play_upload.py --key "$KEY" --package "$PACKAGE" \
    --aab "$AAB" --track "$TRACK" --status completed
echo "Play: released to '$TRACK'"
