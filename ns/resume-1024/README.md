# PR #427: final stem-foot inflow comparison

Left: main `3a8b6eb1`. Right: PR `d1886ae2`.
Dev sandbox, flat land, 15 kt ground burst, noon, no shader pack, vanilla clouds off.
Both clients started fresh; the far burst is first and the near burst second.

- Far camera: `4000 250 0 1500`; stills at 45, 60 and 120 seconds.
- Near camera: `2500 300 0 350`; stills at 45, 60 and 120 seconds.
- Motion: near-camera crop `(400,180)-(880,440)`, frames every 0.5 seconds from 45 to 50 seconds; GIF uses a shared palette.
- WAV: 45–53 seconds of each capture, 48 kHz stereo PCM; trimmed without gain changes or resampling. Sound code is unchanged by this PR.
- JPEGs pair the ungraded captures at their original size, with labels above the images.
- `comparison.json` verifies equal camera, site, effect and requested frame times; actual frame ages differ by less than 0.1 seconds.
- Original `capture.json` files are included. Their absolute source paths refer to the local raw PNGs and full WAVs.

The six still comparisons and the motion GIF replace the earlier PR images, which preceded the final restriction of inflow and twist to the foot band.

Local validation: 1,207 unit tests; GameTest `nuke_ground`, `nuke_icbm`, `nuke_clear`, 3/3 passed. Latest branch CI: Итог and Записка passed. Independent review of the final diff is handed to Claude.
