#!/usr/bin/env python3
"""Upload an Android App Bundle to a Google Play track, using only the standard library.

Auth is a service-account JSON key (RS256 JWT signed with openssl), so no pip packages are
needed. The app must already exist in Play Console and the service account must have been
invited with at least "Release to testing tracks".

Usage:
  play_upload.py --key ~/.config/pagerapp-play.json --package io.ofloo.pagerapp \
                 --aab dist/PagerAPP-store.aab --track internal [--status completed] [--release-notes notes.txt]
"""
import argparse, base64, json, os, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def get_token(key: dict) -> str:
    header = {"alg": "RS256", "typ": "JWT"}
    now = int(time.time())
    claims = {
        "iss": key["client_email"],
        "scope": SCOPE,
        "aud": key["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(claims).encode())}".encode()

    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as f:
        f.write(key["private_key"])
        pem = f.name
    os.chmod(pem, 0o600)
    try:
        sig = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", pem],
            input=signing_input, stdout=subprocess.PIPE, check=True,
        ).stdout
    finally:
        os.unlink(pem)

    jwt = f"{signing_input.decode()}.{b64url(sig)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt,
    }).encode()
    req = urllib.request.Request(key["token_uri"], data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)["access_token"]


def call(method, url, token, data=None, ctype="application/json"):
    body = None
    if data is not None:
        body = data if isinstance(data, bytes) else json.dumps(data).encode()
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.stderr.write(f"HTTP {e.code} on {method} {url}\n{e.read().decode(errors='replace')}\n")
        raise


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", required=True)
    ap.add_argument("--package", required=True)
    ap.add_argument("--aab", required=True)
    ap.add_argument("--track", default="internal")
    ap.add_argument("--status", default="completed", choices=["completed", "draft", "halted"])
    ap.add_argument("--release-notes", default=None)
    args = ap.parse_args()

    key = json.load(open(args.key))
    token = get_token(key)
    print("authenticated as", key["client_email"])

    edit = call("POST", f"{API}/applications/{args.package}/edits", token, {})
    edit_id = edit["id"]
    print("edit", edit_id)

    with open(args.aab, "rb") as f:
        bundle = f.read()
    up = call("POST",
              f"{UPLOAD}/applications/{args.package}/edits/{edit_id}/bundles?uploadType=media",
              token, bundle, ctype="application/octet-stream")
    vc = up.get("versionCode")
    print("uploaded bundle versionCode", vc)

    release = {"status": args.status, "versionCodes": [str(vc)]}
    if args.release_notes and os.path.exists(args.release_notes):
        notes = open(args.release_notes).read()
        release["releaseNotes"] = [{"language": "en-US", "text": notes}]

    call("PUT", f"{API}/applications/{args.package}/edits/{edit_id}/tracks/{args.track}",
         token, {"track": args.track, "releases": [release]})
    print(f"assigned versionCode {vc} to track '{args.track}'")

    call("POST", f"{API}/applications/{args.package}/edits/{edit_id}:commit", token, {})
    print("committed. done.")


if __name__ == "__main__":
    main()
