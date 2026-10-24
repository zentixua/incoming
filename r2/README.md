# WV revision 2: ring and condensation

Current evidence for PR #425 after Claude round 1. Do not merge this orphan evidence branch into production.

- 50 kg TNT Shahed, ground hit, seed 7, flat grass, day; saturated-air scene in rain at 50 m.
- Standing creative player; distance 20/50/100 m, height 1.62 m, bearing 0°, look height 3 m, camera_shake 1.
- Native GPU dev sandbox, no Iris. Requested window 1280×720; native framebuffer 1280×692. Quiet power profile, not a performance benchmark.
- Both copies use the dev standing/weather commands. A temporary VSync-off/60-FPS capture fixture is documented in `capture-settings.patch`; it changes only the sandbox copy. It is absent from the production PR.
- Exact WV/main commits, base and allowed dev-file changes: `source.json`, `main-source.json`. Main includes DU2, WT and CR5. All production sources were committed before capture.

`compare-20.jpg`, `compare-50.jpg`, `compare-100.jpg`: main/WV at early ages. `front-motion-*.jpg`: outward motion within the first tenth of a second. `rain-50.jpg`: formation and evaporation of the white shell. Labels use actual recorded ages; requested filenames are not exact timings.

`wv-d20.mp4`, `wv-d50.mp4`, `wv-d100.mp4`, `wv-wet50.mp4`: native-resolution first-two-second movies with blast audio. They follow actual frame intervals, without interpolation. `main-wv-50.mp4` is a silent side-by-side pair. `front-50-slow.mp4` is explicitly quarter speed and silent. Original unnormalised 48 kHz stereo four-second WAVs and selected unedited PNGs are in each scene directory; `capture.json` records selected frames, while `frame-ages.csv` retains all captured timestamps. Main originals are under `main/`.

`reference-341-50.jpg` uses the existing Newisle/Iris 3.4.1 archive. Its scenery and rendering differ; it is a presentation reference, not matched pixels. No matched 3.4.1 sandbox recordings exist in the archive at 20/100 m. Root-level material remains the round-1 archive; early R1 first-frame delays are visible in its actual-age labels.

The cover accent is presentation: pale lifted clumps, no physical mass/settling changes to DU2 GroundDust. It begins behind ChemicalFront, on grass/leaves passing the DustModel cover threshold, and fades on a local pressure-phase scale. Radius and timing are physical. Refraction gain 24 applies only to ordinary fronts; nuke optics are unchanged. Camera response parameters remain those approved in round 1; the ordinary birth-clock correction aligns onset.

`condensation.csv` and `CondensationNumbers.java` provide the pressure/RH/dew-point check. At 20 m, 50 kg surface charge, 15°C, RH=1: peak 36.17 kPa, estimated minimum −4.89 kPa, dry expanded air 11.27°C, dew point 14.23°C, condensed water 0.880 g/m³ after latent heating. At RH=0.5 it remains dry there. The rendered saturated shell reaches 27.91 m and ends around 0.149 s. The negative phase is an estimated Friedlander continuation; finite droplet-growth kinetics are omitted, and the old 20 kPa outer rendering envelope remains an estimate rather than a universal condensation threshold.

Community comparison: Vinlanx's [official outdoor TNT GIF](https://i.ibb.co/Qvqp3Mdb/New-Project23.gif), linked from [Explosion Overhaul](https://modrinth.com/mod/explosion-overhaul-a-new-level-of-destruction), was inspected locally. It separates a broad pale ground surge from darker debris; WV follows that readability with pressure-governed timing. The source has unmatched charge/distance/scenery. No third-party textures, GIFs or binaries are distributed here.

Capture/assembly/encoding scripts and fixture patches in this directory are measurement provenance, not production tooling. Full continuous source frames remain locally at `mod/run/wv-codex-evidence/r2/` and `r2-main/` in wt-WV.
