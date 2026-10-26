#!/usr/bin/env python3
"""Test dedicated-server configuration (tools/stress.sh, tools/mp_scenario.sh): no LAN advertising.

NeoForge advertises dedicated servers on the local network by default (advertiseDedicatedServerToLan in
config/neoforge-server.toml): LanServerPinger holds a UDP socket on 0.0.0.0 and sends the MOTD and port to
224.0.2.60:4445 every 1.5 s. The test server needs no network. The file is edited in place (other keys remain as
NeoForge wrote them); if absent, write one key and let NeoForge add the rest. The devtest LoopbackGuard watchdog
crashes the server if advertising is enabled.
"""
import pathlib
import re
import sys

KEY = "advertiseDedicatedServerToLan"

cfg = pathlib.Path(sys.argv[1]) / "config" / "neoforge-server.toml"
cfg.parent.mkdir(parents=True, exist_ok=True)
text = cfg.read_text(encoding="utf-8") if cfg.exists() else ""
line = re.compile(rf"^(\s*{KEY}\s*=\s*).*$", re.M)
if line.search(text):
    text = line.sub(r"\1false", text)
else:
    # file without a final newline: put the key on a new line, otherwise it joins the last line (invalid TOML)
    text += ("" if text.endswith("\n") or not text else "\n") + f"{KEY} = false\n"
cfg.write_text(text, encoding="utf-8")
