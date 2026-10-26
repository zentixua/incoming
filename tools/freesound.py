#!/usr/bin/env python3
"""Original Freesound recordings, lossless files as uploaded by the author, for tools/build_sounds.py.

Freesound previews are lossy ogg; originals are served only after Freesound login (OAuth2, API v2). Once:

  1. https://freesound.org/apiv2/apply — API key (Client id and Client secret);
  2. FREESOUND_CLIENT_ID=… FREESOUND_CLIENT_SECRET=… python3 tools/freesound.py login
     — prints a login URL; after login Freesound shows a code to paste here.

Login is stored outside the repository: ~/.config/incoming/freesound.json (or the FREESOUND_AUTH file). The token lasts
a day and refreshes automatically if FREESOUND_CLIENT_ID and FREESOUND_CLIENT_SECRET are set. Downloaded originals
are in tools/.sound-cache/orig/<id>.<type>; no login is needed once the original has been downloaded.

  python3 tools/freesound.py fetch 251401 475780   — download originals (for checking and analyzing recordings)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://freesound.org/apiv2/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORIG = os.path.join(ROOT, "tools", ".sound-cache", "orig")
AUTH = os.environ.get("FREESOUND_AUTH") or os.path.expanduser("~/.config/incoming/freesound.json")


def _client():
    cid, secret = os.environ.get("FREESOUND_CLIENT_ID"), os.environ.get("FREESOUND_CLIENT_SECRET")
    if not cid or not secret:
        sys.exit("need FREESOUND_CLIENT_ID and FREESOUND_CLIENT_SECRET (key API: https://freesound.org/apiv2/apply)")
    return cid, secret


def _grant(**fields):
    cid, secret = _client()
    body = urllib.parse.urlencode(dict(client_id=cid, client_secret=secret, **fields)).encode()
    with urllib.request.urlopen(API + "oauth2/access_token/", body) as r:
        tok = json.load(r)
    tok["expires_at"] = time.time() + tok["expires_in"] - 60
    os.makedirs(os.path.dirname(AUTH), exist_ok=True)
    fd = os.open(AUTH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump(tok, fh)
    return tok


def login():
    cid, _ = _client()
    print("Log in to Freesound and allow access:")
    print(f"  {API}oauth2/authorize/?client_id={cid}&response_type=code")
    code = input("code from page Freesound: ").strip()
    _grant(grant_type="authorization_code", code=code)
    print("done, login saved in", AUTH)


def _token():
    if not os.path.exists(AUTH):
        sys.exit("no entry to Freesound: python3 tools/freesound.py login (see tools/freesound.py)")
    with open(AUTH) as fh:
        tok = json.load(fh)
    if time.time() > tok.get("expires_at", 0):
        tok = _grant(grant_type="refresh_token", refresh_token=tok["refresh_token"])
    return tok["access_token"]


def _get(path, token):
    """Request to API; on “too often” (429) Freesound asks to wait - we wait and repeat."""
    req = urllib.request.Request(API + path, headers={"Authorization": "Bearer " + token})
    for attempt in range(8):
        try:
            return urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 7:
                raise
            wait = int(e.headers.get("Retry-After") or 60)
            print(f"  Freesound asks to wait {wait} s")
            time.sleep(wait)


def original(fid):
    """Path to the original entry fid; not in cache - download (login required)."""
    os.makedirs(ORIG, exist_ok=True)
    for f in os.listdir(ORIG):
        if f.split(".")[0] == str(fid) and not f.endswith(".part"):
            return os.path.join(ORIG, f)
    token = _token()
    with _get(f"sounds/{fid}/?fields=type", token) as r:
        kind = json.load(r)["type"]
    path = os.path.join(ORIG, f"{fid}.{kind}")
    print("  downloading the original", fid)
    with _get(f"sounds/{fid}/download/", token) as r, open(path + ".part", "wb") as fh:
        while chunk := r.read(1 << 20):
            fh.write(chunk)
    os.replace(path + ".part", path)
    return path


if __name__ == "__main__":
    if sys.argv[1:2] == ["login"]:
        login()
    elif sys.argv[1:2] == ["fetch"]:
        for a in sys.argv[2:]:
            print(original(int(a)))
    else:
        print(__doc__)
