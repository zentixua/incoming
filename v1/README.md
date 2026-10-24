# V1 fireball review evidence

Paired dev-client frames: Iris 1.8.14 + Complementary Unbound r5.9.3, quiet laptop profile.
Each capture folder has original PNGs, the recorded WAV, camera settings and actual ages in capture.json.
The main-art and V1-art variants use identical production render/model code; only fireball PNGs and metadata differ.
The dev-only `real bunker` dispatcher is corrected in both variants, using the existing BunkerBlast law at a chosen
surface detonation point. These bomb frames test the fireball, not flight or penetration.

First capture: feature 00e3a664 on base 3a8b6eb1. Affected-case recapture: feature 240bb42f on base 4498ecfc.
The intervening main change is the salvo flight lanes; the conventional renderer/model is unchanged.
Static requested ages: 0.1 / 0.5 / 1 / 3 seconds; the actual ages are printed in the sheets.
Motion is a 20 fps sequence from requested 0.05 to 2 seconds; originals and actual ages remain available.
Only failed bomb captures and the main drone case affected by teleport/chunk settling are re-shot.

3.4.1 references are the existing full-pack recordings from compare-final; terrain and framing differ from the
sandbox. Missile50 and drone150 have no matching old recording in this archive; hero is the older style reference.
The 3.4.1 sound files are the unmodified archived OGGs. New WAVs are sandbox recordings; no audio assets change in V1.

Audio attribution and licences: SOUND-CREDITS.md; the legacy version's credits are also available at
https://github.com/zentixua/incoming/blob/92669f5/SOUND-CREDITS.md .
JangaFX's CC0 source and the fireball bake recipe: tools/bake_fx/SOURCES.md on the feature branch.
