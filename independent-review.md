# RDELIVERY4 — independent disposable-installer review

Reviewed 4 November 2026 in a fresh gpt-6.1-sol/high session. Worktree `wt-DELIVERY2` is clean, detached at `7bdb5b2c12c9c387aa346046250a9fd62fdf3990`. Scope: ignored `prepare_pin.py`, `stage.py`, `check.py`, captured installer/Prism source, raw test logs, receipts and artifact provenance. RDELIVERY3's accepted base contract was read; no DELIVERY4 author conclusions were used. No production edits, installer execution, actual target writes, server/client/UI/GPU operation or heavy jobs were performed by this reviewer.

## Verdict

No serious safety/correctness defect found for the exact captured pack and test artifact. This clears the disposable mechanism evidence only. It does not clear production runtime, final delivery, world compatibility, final heap or integrated readiness; those remain LEAD3-owned.

## Independently checked evidence

- CI run `41873148325`, attempt 1, completed successfully. GitHub artifact API identifies `13345172928` as `incoming-jar`, with digest `sha256:8badb94437b7dbf24419d1b97424cebfb87ff85d329e115d2d41a10e896c294b`. The captured ZIP independently hashes to that digest and contains exactly `incoming-3.11.0.jar`. Extracted JAR equals the local artifact and hashes to `0de8fb1cc9a484d6e9c92716a8f782c921359c3f07e61decb61eea651a7333d8`.
- Build log checks out PR merge `1aa7dce9e142dacecc9edce06bf5b900d1d9ed6c`, runs `assemble prepareGameTestServerRun`, builds `jar`, then uploads `mod/build/libs/*.jar`. GitHub commit API gives tree `de41573e090269a066b916c7058c15eae2bf93bc`, exactly the local source tree at `b5610b3c91a2c1e2e2d09d28c0fd503464a43982`; parents are `7bdb5b2c...` and `b5610b3c...`. Thus merge-checkout versus source-head is not a provenance mismatch. This is ordinary trusted CI provenance, not a reproducible-build attestation.
- JAR metadata identifies Incoming 3.11.0; independent `javap -constants` reads `IncomingNetwork.PROTOCOL = "55"`. No `MtB7` or `ua/zentix/incoming/devtest/` member names were found. The positive provenance chain, rather than those exclusions alone, binds this JAR to production source b561 rather than diagnostic NATURAL 0f1a.
- Private pack is pinned to `68e77a75c1d4172c5a86e0d239ec3a09cc22a0f0` / 0.1.54. All 89 indexed local files independently match their declared SHA256. Compared with the captured original index, the only removed entry is `mods/incoming.pw.toml`, the only added entry is `mods/incoming-3.11.0.jar`; remaining entries are identical except `preserve=true` on the ten plain config entries. The pack text changes only its index hash. Candidate JAR bytes match the verified CI artifact.
- Rehashed all receipt `before`/`after` paths against the retained original/scratch trees, all inventory JARs and all preserved original Incoming JARs. Client has 72 JARs, server 43; the script inspects mod metadata to require a single Incoming and its exact candidate SHA. All captured original files other than `packwiz.json` and the moved old Incoming retain identical bytes, including captured shaderpacks and installer binaries outside the script's narrower `state()` view. Original Incoming bytes remain in each scratch `preserved-originals`.
- The script compares first/second pass states; both raw pass-2 logs take the installer up-to-date path and finish with successful external candidate SHA verification. Retained final state matches the first-pass receipt. Both manifests replace only Incoming's entry; every non-Incoming cached entry is independently equal before/after, including optional state. Client preserves all five enabled choices. Server retains its mixed true/false optional flags (including wrong-side entries).
- For each side, `corrupt-installed`, `missing-source-and-installed`, and `corrupt-source-and-missing-installed` report exit 1 with no launch sentinel; raw logs show external SHA failure, missing source, and rejected source hash respectively. Missing-installed alone reports exit 0, repairs the correct JAR and reaches the sentinel. All eight sentinel states independently match their retained directories. `set -euo pipefail` places the sentinel after installer and checksum success. Installer v0.5.14 source explains the existence-only cached fast path and why the extra checksum is necessary.
- Captured Prism 11.1.1 source supports the proposed logical command `bash private-prelaunch.sh "$INST_JAVA"`: MinecraftInstance sets prelaunch cwd to gameRoot and supplies INST_JAVA; LaunchTask expands it; PreLaunchCommand uses `QProcess::splitCommand` and fails the launch step on nonzero exit. The scratch tests call the shell directly, not Prism.

## Evidence limits and nonblocking notes

1. Server `stage.py` copies the ten manifest-managed config files, not every untracked server config/defaultconfig file. Client includes 205 config files plus captured options. Therefore receipts support unchanged **captured** settings, not a complete server-settings inventory/backup or arbitrary pack update. Full target backups and current path/manifest reconciliation remain required at actual delivery. This does not invalidate the exact unchanged-index mechanism test.
2. Optional verification covers current values and metadata. It does not exercise a disabled correct-side optional client choice or a newly introduced optional entry; RDELIVERY3's CLI selection boundary still applies if pack SHA changes.
3. No actual Prism QSettings write/read round trip, Flatpak sandbox invocation, target shell/start integration or Crafty behavior was executed. Direct Bash sentinel success plus matching upstream source supports the hook design, not an executed launcher integration claim. No public unpin/rollback or backup-restoration cycle was tested.
4. The scripts are disposable exact-snapshot tools. In particular the client staging path list is trusted rather than generically sanitized, staging is not generally rerunnable, and config `preserve` semantics were not forced through a changed-upstream-config fixture. Observed manifest paths were independently checked as relative without `..`; captured originals have no symlinks. Do not promote these scripts to a reusable target installer without separate hardening/review.
5. A new root-selected source/JAR, protocol or pack requires substitution and renewed artifact/manifest/guard checks. This b561 artifact is test-only until root readiness. No game startup, 1Mt behavior or save downgrade compatibility can be inferred from any installer receipt.

## Exact reviewed hashes

SHA256, paths relative to `wt-DELIVERY2`:

- `mod/run/delivery3/prepare_pin.py`: `c96ae346ea319f21e713b34743860cb18dfb00e2ba1c2c747c2d6983d25c4fee`.
- `mod/run/delivery4/stage.py`: `71d9f66df242f50b82c065348880cf66c32298cad0d12be07fe2c14f0259d53c`.
- `mod/run/delivery4/check.py`: `208838552bcab4e5d70db76e4d05fcd9357d02d378d0470460685d032d39e1e6`.
- `mod/run/delivery4/receipts.json`: `7018472c162516b063a176ff8b00b829a3e0b2e23427dcd9be5ddcb9846917c2`.
- `mod/run/delivery4/artifact/provenance.json`: `88619933d43f8a3f22eec4d67c816cb9b5ab5566a68174d5c35712117c197ba1`.
- `mod/run/delivery4/artifact/build.log`: `aee457fd2b8bc7d36a0626a3c27b52827133c4f6179b161041bdd7078fbde07d`.
- `mod/run/delivery4/pin-b561/pack.toml`: `38c77be99ce6aecaa4ef5888ab43739fedfecfa67f49f73315ab9157a3453ed7`.
- `mod/run/delivery4/pin-b561/index.toml`: `80474bcb73fb68681aec94a23937623abcf786a17a23ea01e49cf5d7078b85ba`.
- `mod/run/delivery4/pin-b561/private-pin.json`: `39187f4dfdcf377fd8455f2d371b05dea413ae8d24834b632235a5ed7ef836d5`.
- `mod/run/delivery4/original-client-sha.json`: `e9b1e063048be68ca63842b8389ab50b4e0a0a981100f3972a2ef4f14ff97f34`.
- `mod/run/delivery4/original-server-sha.json`: `817d6fdf69f6e786b7c65c254737e31798f5796f08964c048686fc2ec7cc36f4`.
- `mod/run/delivery4/client-corrupt-installed.log`: `38719169f7baa9006fb0b0fc505e422a3f9037aca97ba4919329242b8b00d8c3`.
- `mod/run/delivery4/client-corrupt-source-and-missing-installed.log`: `e29c997cc05772595cc2afcf384a27bd1ababf7ec9deb6b395e13d914a28c819`.
- `mod/run/delivery4/client-missing-installed-repair.log`: `b60e7b9a7562bac4358997941094cf465cea413a74500557c354749b369f0cb9`.
- `mod/run/delivery4/client-missing-source-and-installed.log`: `0685c8c2a529073206f81fddf26594f65addee663a42dec6589f48824d6565bc`.
- `mod/run/delivery4/client-pass1.log`: `56832f4d63bd739f455063b03558f0ad7d542d006faa5cdf9557bdf32bb8e67b`.
- `mod/run/delivery4/client-pass2.log`: `23ba30baf2d87814009d2f2cae3215f0c803db3f22379739bf141a69e0de659d`.
- `mod/run/delivery4/server-corrupt-installed.log`: `38719169f7baa9006fb0b0fc505e422a3f9037aca97ba4919329242b8b00d8c3`.
- `mod/run/delivery4/server-corrupt-source-and-missing-installed.log`: `4a54939de16d7884d8cdda775b3be0ccf93a0ada7b0fa9c98684a0ef6532f0ee`.
- `mod/run/delivery4/server-missing-installed-repair.log`: `4d45f943fd36f5a3c5b7e6e1b211eafc0b1351b2353fb771e181bc2854b8b1e4`.
- `mod/run/delivery4/server-missing-source-and-installed.log`: `67926901702982b8ccdb9d22203c868f31fdb11df409b0aa7dd52027ee4b0237`.
- `mod/run/delivery4/server-pass1.log`: `93540d19b4be7fa57739644e505ceb66c7f19134f7c4c53356ca87261369c3cf`.
- `mod/run/delivery4/server-pass2.log`: `23ba30baf2d87814009d2f2cae3215f0c803db3f22379739bf141a69e0de659d`.
- `mod/run/delivery4/prism-source/11.1.1-Commandline.cpp`: `d4e1792a66c62ce728371f6a991f285290b9a6304ba894efe2843bfc3f9b875f`.
- `mod/run/delivery4/prism-source/11.1.1-LaunchTask.cpp`: `f6fd07c4abf28a30feb24ece2fd3536e3050979576aaa541800297a248057e13`.
- `mod/run/delivery4/prism-source/11.1.1-MinecraftInstance.cpp`: `73fd67f50cc793e70ad8a24e9e82549b06e1a9176770907aa375cb8ea007ed42`.
- `mod/run/delivery4/prism-source/11.1.1-PreLaunchCommand.cpp`: `27a0d7ae86d454b03496c4a52a7773d3fb6c5268f30ddba97942b4df87f21090`.
- `mod/run/delivery3/source/installer/src/main/kotlin/link/infra/packwiz/installer/UpdateManager.kt`: `a768b1c3ac98c15db34130f9f316aa5c4081264952f7bf7097cab82b29dd4495`.
- `mod/run/delivery3/source/installer/src/main/kotlin/link/infra/packwiz/installer/DownloadTask.kt`: `386e53bbe72d1cede44661682a20f560be2451f870eed946ede18a0bf1cab7d7`.

В память: added the Prism 11.1.1 prelaunch parsing/cwd boundary and disposable evidence limits to canonical `packwiz-private-review.md`. No new operating authorization or runtime acceptance inferred.
