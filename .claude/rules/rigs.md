---
paths:
  - "tools/**"
  - "mod/src/devtest/java/ua/zentix/incoming/rig/**"
  - "mod/src/devtest/java/ua/zentix/incoming/stress/**"
---

# Rigs, multiplayer, nested KWin, full-pack client

## Pitfalls
- Stress rig (`tools/stress.sh`, `MODS=run/ci-mods` in the cloud): server and three clients on 4 cores and 16 GB — JVM
  memory limits are in `build.gradle` (otherwise the kernel kills a client); `INCOMING_RIG_JVM=artem` uses the server JVM
  setup Artem has (8 GB, generational ZGC; only where memory permits — VPS); the collector is in the “server started”
  line. For ticks over 0.5 s, the director logs the server stack: 35 s in the cloud comes from Sable
  (`PhysicsChunkTicketManager` loads chunks synchronously) while generation waits in the queue.
- Test servers listen only on loopback: `server-ip=127.0.0.1` and a free port (`tools/free_port.py`) in `stress.sh`
  and `mp_scenario.sh` — internet scanners find a rig server on 0.0.0.0 with `online-mode=false`.
  The devtest guard `rig/LoopbackGuard` crashes the server (before world load; a LAN world on tick) if the socket is not
  on loopback. UDP: Sable's channel joins the same channel list at the TCP address (the guard sees it too); scripts
  disable NeoForge LAN advertisement (`advertiseDedicatedServerToLan` in `config/neoforge-server.toml`: socket on
  0.0.0.0, writes to 224.0.2.60:4445) via `tools/rig_config.py`; the guard crashes the server if it is enabled. Gradle's
  own UDP socket (file locks, `DefaultFileLockCommunicator`) listens on 0.0.0.0 with no address setting, so Gradle only
  prepares the rig and multiplayer: `./gradlew rigLaunch -PrigRun=<run… task> -PrigOut=<name>` writes
  `build/rig/<name>.sh` (java, MDG @files copied per launch, classpath, directory, task environment; settings the task
  reads from the environment and `-P` are captured at preparation time); Gradle exits, the game runs from scripts
  without a live Gradle. Rig and multiplayer servers and clients each have their own session (`tools/rig_procs.sh`:
  `rig_spawn`, `rig_trap`): the client JVM is a grandchild of the wrapper (xvfb-run, nested_kwin → dbus-run-session →
  kwin), and `kill` on the wrapper leaves it orphaned; script exit (completion, error, Ctrl+C, TERM) stops entire sessions
  — TERM, then KILL after 20 s (a second Ctrl+C does not interrupt this), then exits with the same signal. Only sessions
  with `INCOMING_RIG_TAG` in their environment are stopped: after PID wraparound, an ended session's number may belong
  to someone else.
- Test clients do not open host audio: `PIPEWIRE_REMOTE`/`PULSE_SERVER` point to nonexistent sockets (`nested_kwin.sh`,
  same for the `xvfb-run` path); OpenAL writes scenario audio to a file with the “wave” driver. New client-launch
  scripts must do the same.
- Launch nested KWin only via `tools/nested_kwin.sh` (its own `dbus-run-session` bus and `XDG_*_HOME`): on the shared
  bus it attaches to the desktop's kglobalaccel as “kwin” and on exit disables all Artem's KWin shortcuts (Alt+Tab),
  while writing kwinrc to the shared `~/.config`. Check: `busctl --user call org.kde.kglobalaccel /component/kwin
  org.kde.kglobalaccel.Component isActive` must remain `true`. The nested session's bus starts no services
  (`tools/nested_kwin_bus.conf`, no D-Bus activation), while its `XDG_RUNTIME_DIR` is shared with the desktop: with
  activation it starts xdg-desktop-portal and xdg-document-portal; on startup the latter unmounts the host's
  `/run/user/1000/doc` (`fusermount3 -u -z`), and its own on exit; Flatpak apps (Prism) fail to start (“bwrap: Can't find
  source path …/doc/by-app/…”; restore with `systemctl --user restart xdg-document-portal.service`). Check:
  `findmnt /run/user/1000/doc` before and after must show the same mount.
- The host's pack (zentixua/open-skies, includes Sinytra Connector) does not start in a Gradle run: Connector requires
  Minecraft's launcher file layout (“Could not determine clean minecraft artifact path”). Use `tools/prod_client.py`
  for full-pack checks (libraries and ForgeWrapper from the Prism directory, instance copy in `mod/run/prod`; Artem's
  instance is untouched).
