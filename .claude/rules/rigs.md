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
- The host's pack (zentixua/open-skies-friends, includes Sinytra Connector) does not start in a Gradle run: Connector requires
  Minecraft's launcher file layout (“Could not determine clean minecraft artifact path”). Use `tools/prod_client.py`
  for full-pack checks (libraries and ForgeWrapper from the Prism directory, instance copy in `mod/run/prod`; Artem's
  instance is untouched).
- Measurement server without a panel: `./gradlew runServer -PserverDir=run/<name> -PmcModsDir=run/ci-mods` (after
  `tools/fetch_runtime_mods.py`), `advertiseDedicatedServerToLan=false` in its `config/neoforge-server.toml`
  (LoopbackGuard), RCON for commands. `forceload add` only queues tickets: wait with `execute if loaded` before acting.
- Laptop memory: all agent work (missions, delegate runs, `laptop_job.sh` jobs, units agents start) shares one ceiling,
  the user unit `agents.slice` that laptop-agents installs (MemoryMax/MemorySwapMax, no MemoryHigh). Without it, their
  sum fills RAM and zram swap and systemd-oomd kills the cgroup with the most swap, which is always the Claude desktop
  app (idle pages swap out first). Heavy runs from a Claude app session therefore go through `laptop_job.sh`, never
  directly (a `java` started there counts as the app), and temporary files go to `$TMPDIR` (on disk in agent units),
  never `/tmp` (a 16 GB RAM tmpfs).
- A fake process in a tooling test (a pretend `java`) is a `#!/bin/bash` script with that name (process name = script
  name); a copy of `sleep` fails on GitHub runners, whose coreutils is one multicall binary ("unknown program 'java'").
- Stop background scripts by PID: `pkill -f <pattern>` also matches your own shell's command line and kills it.
- `tools/prod_client.py --video` copies Artem's `options.txt`; with `fullscreen:true` the window grows to the nested
  screen and `x11_record.py` (which starts a segment only at exactly `--size`) records nothing after the menu: run
  `--copy-only --dir D`, set `fullscreen:false` in `D/options.txt`, then `--no-copy --dir D --video …`. `--video` is a
  diagnostic, never a clip: x11grab reads the window on its own 60 Hz clock, not at the game's buffer swaps, so it catches
  the window between swaps (black, torn frames) and resends the last picture while the game moves on (frozen frames), and
  its 1080p60 grab and encoder share the unit's CPU with the game. Measured on a 26 s full-pack nuke clip: 108 black, 16
  torn and 199 frozen frames of 1559 (longest freeze 1.75 s); through a nuke the machine sits at 100 % CPU, the game JVM gets
  18-30 % of it and draws 1-9 fps, against 35-52 fps for the same pack and a 15 kt ground burst without the recorder. Clips for Artem and
  players are Flashback recordings exported by Flashback (`tools/media/README.md`, `tools/replayscan` filming);
  `tools/clip_check.py` passes each delivered file (no black, torn or frozen frames, constant clock, whole file, audio as
  long as the video, its decoded peak under -0.5 dBFS) before it leaves. Flashback renders an export's sound through a
  float OpenAL loopback device opened without `ALC_OUTPUT_LIMITER_SOFT` (its `MixinAudioLibrary`), so OpenAL Soft's
  output limiter, on in live play's integer output, is off: a 15 kt wave's arrival reached +2.3 dBFS in the export (443
  samples over full scale; a 1 Mt test burst +2.4) and clips in every player. A delivered clip's sound goes through
  `alimiter=limit=0.84:level=false:attack=1:release=50:asc=true` (−1.4 dBFS after AAC, loudness −0.3 LU). Each take overwrites the instance's `logs/run.jfr` and `gc.log`: copy them
  out per take. LOD Server Support serves a single-player world only with `--prop lss.test.integratedServer=true`;
  `/publish` opens a LAN socket on every interface, and `rig/LoopbackGuard` stops the server (the client then sits in the
  menu while the recorder runs: a take wrapper must stop on a guard line or a silent log).
- `tools/prod_client.py --server` without a scenario (a plain join, `--incoming-jar`) stops at a server's resource-pack
  prompt: the headless client cannot answer it, the server shows the player only in its configuration phase, and the run
  ends at `--seconds` without joining. Before `--no-copy`, set the entry's `acceptTextures` byte to 1 for that address in
  the copy's `servers.dat` (quick play reads it); the `commands` scenario accepts the pack itself.
- A player's slow line without sudo: one user and network namespace (`unshare -U -n --map-user=$(id -u)
  --map-group=$(id -g) --keep-caps`, inside a `laptop_job.sh` unit) holds the `rigLaunch` server and client; there
  `tc` shapes loopback (a `prio` qdisc, a `u32 match ip sport <server port>` filter to a `netem delay rate` band for
  server→client, delay only for the rest) and `sysctl` sets per-namespace `tcp_congestion_control` and
  `tcp_notsent_lowat`. Sample the server socket with `ss -tin` (`bytes_acked`, `notsent`, `rtt`). netem's delay is
  constant: real mobile jitter is absent, model it in a simulation. The rig runs the tree's `build/classes`: never
  compile in that tree while it runs (a queued GPU job starts later on whatever is compiled then).
