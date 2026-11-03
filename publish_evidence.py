"""Assemble a selected evidence tree; originals remain untouched."""
from pathlib import Path
import hashlib, json, shutil, subprocess
from PIL import Image
BASE=Path('/mnt/data/projects/incoming/mod/run/incoming-sessions')
SRC=BASE/'OL-stage2-evidence'
OUT=BASE/'OL-stage2-publish'
OUT.mkdir(exist_ok=True)
for p in (SRC/'incoming').iterdir():
    if not p.is_dir() or p.name=='ol-trial-grass': continue
    target=OUT/'incoming'/p.name
    target.mkdir(parents=True,exist_ok=True)
    for f in p.iterdir():
        if f.suffix in ('.png','.json'):
            shutil.copy2(f,target/f.name)
        if f.suffix=='.wav' and p.name in ('ol-main-grass','ol-main-grass-far','ol-game-nuke-300','ol-game-nuke-700'):
            shutil.copy2(f,target/f.name)
for laser in ('nuke','payload'):
    for distance in (300,700):
        source=SRC/f'{laser}-{distance}'
        target=OUT/source.name
        target.mkdir(exist_ok=True)
        for f in source.iterdir():
            if f.suffix in ('.png','.json','.log'): shutil.copy2(f,target/f.name)
for kind in ('strike','nuke'):
    source=SRC/f'audition-{kind}'
    target=OUT/source.name
    target.mkdir(exist_ok=True)
    for name in ('report.json','near-001-full.wav','near-001-small.wav','far-001-full.wav','far-001-small.wav','mixed-016-full.wav','mixed-016-small.wav'):
        shutil.copy2(source/name,target/name)
for name in ('capture_baseline.sh','capture_trial.sh','capture_nuke_main.sh','capture_nuke_game.sh','matched_ol.py','publish_evidence.py',
             'baseline.log','trial.log','trial-grass-ready.log','trial-swap.log','restore-swap.log','main-repeat.log','nuke-main.log',
             'nuke-game.log','matched-ol.log','audio-final-tests.log','audio-file-check.log','pr-562-ci.log','sandbox-latest.log',
             'rejected-lighting-trial.patch','decay-diagnostic.json','invalid-captures.txt','sandbox-config-before.toml'):
    shutil.copy2(SRC/name,OUT/name)
for name in ('OL-dust-proposal.md','OL-nuke-proposal.md'):
    shutil.copy2(BASE/name,OUT/name)
(OUT/'SOUND-CREDITS.md').write_text(subprocess.check_output(['git','show','341c2ea6:SOUND-CREDITS.md'],text=True))
shutil.copy2(BASE/'OL-source/LICENSE',OUT/'ORBITAL-LASER-LICENSE')

def cell(path,label):
    return f'[![{label}]({path})]({path})'

lines=['# Nuclear wall: matched observer ranges', '',
       'See [method, limits and proposal](OL-nuke-proposal.md). Incoming: 15 kt, ground burst, scale 1, wave 0.25. '
       'OL: FULL exact-shader preview on synthetic terrain, no equivalent yield. Day, eye 2 m, horizontal view, 70° vertical FOV, 1280×692. '
       'The terrain/lighting engines differ. Click any original image to inspect it.', '',
       'Time is measured after the front reaches the point 10 m ahead of the eye. OL uses the actual Incoming frame age, not the requested filename time.']
for distance in (300,700):
    lines += ['',f'## {distance} m','','| Actual local age, s | Incoming | OL Nuke | OL Payload |','|---:|---|---|---|']
    name=f'ol-game-nuke-{distance}'
    frames=json.loads((OUT/'incoming'/name/'capture.json').read_text())['frames']
    for i,f in enumerate(frames):
        paths=[f'incoming/{name}/{f["file"]}',f'nuke-{distance}/{i:02}.png',f'payload-{distance}/{i:02}.png']
        lines.append(f'| {f["age"]:+.3f} | '+' | '.join(cell(p,l) for p,l in zip(paths,['Incoming','OL Nuke','OL Payload']))+' |')
(OUT/'nuke-comparison.md').write_text('\n'.join(lines)+'\n')
lines=['# Conventional lighting experiment — rejected','','See [decision and explanation](OL-dust-proposal.md). '
       'All originals are preserved. The trial adds bounded light contrast only; the final code is unchanged. '
       'These are matching nominal scenes, not pixel-identical frames (see actual ages in capture manifests).', '']
for label,groups,times in [('Grass, 40 m',('ol-main-grass','ol-trial-grass-ready','ol-main-repeat-grass'),('000.30','002.00','006.00')),
                           ('Missile from above, 70 m',('ol-main-high','ol-trial-high','ol-main-repeat-high'),('000.70','002.00','006.00')),
                           ('Forest, 60 m',('ol-main-forest','ol-trial-forest'),('000.30','002.00','006.00')),
                           ('Missile at night, 70 m',('ol-main-high-night','ol-trial-high-night'),('000.70','002.00','006.00'))]:
    lines += [f'## {label}','','| Version | '+ ' | '.join(str(float(t))+' s' for t in times)+' |','|---|'+'---|'*len(times)]
    for name in groups:
        lines.append('| '+name+' | '+' | '.join(cell(f'incoming/{name}/{name}_{t}s.png',name) for t in times)+' |')
    lines += ['']
(OUT/'dust-comparison.md').write_text('\n'.join(lines)+'\n')
lines=['# Offline sound evidence','','Generated with PR #562 (`d8d942185`), 48 kHz, 75 ms copy spacing, counts 1/4/16. '
       'All files within each audition share a single attenuation. No host playback occurred. '
       'Stress sums exclude runtime voice allocation/dynamic mixing; they do not predict game output. '
       'The source recordings may already contain output limiting. Sound attribution: [credits](SOUND-CREDITS.md).', '',
       '| Capture pair | Near/far range | Shared audition gain | Report |','|---|---|---:|---|']
for kind,dist in [('strike','40 / 200 m, Shahed'),('nuke','300 / 700 m, 15 kt ground, wave 0.25')]:
    r=json.loads((OUT/f'audition-{kind}'/'report.json').read_text())
    lines.append(f'| {kind} | {dist} | {r["audition_gain_db"]:.2f} dB | [Unscaled measurements](audition-{kind}/report.json) |')
for kind in ('strike','nuke'):
    lines += ['',f'## {kind} WAVs','','| Case | Full range | Small speaker |','|---|---|---|']
    for case in ['near-001','far-001','mixed-016']:
        lines.append(f'| {case} | [WAV](audition-{kind}/{case}-full.wav) | [WAV](audition-{kind}/{case}-small.wav) |')
    lines += ['']
(OUT/'audio.md').write_text('\n'.join(lines)+'\n')
(OUT/'README.md').write_text('''# OL stage 2 evidence — 3 November 2026

- [Conventional lighting experiment: rejected](dust-comparison.md) · [decision](OL-dust-proposal.md)
- [Nuclear wall comparison, 300/700 m](nuke-comparison.md) · [proposal](OL-nuke-proposal.md)
- [Offline sound audition and measured headroom](audio.md)
- [Final audio tests](audio-final-tests.log) · [file integration](audio-file-check.log) · [PR CI](pr-562-ci.log)

PR #562 is tools-only and remains a draft. No nuclear code was changed. The conventional trial is preserved only as a rejected patch, not a merge candidate. No rendering change remains, so no final-head scene bench was run. Original PNGs are unedited. The invalid first conventional shot after returning from the nuke site is omitted; its reason is recorded in invalid-captures.txt. All local originals remain on disk.

Incoming capture commit: 341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e. OL: Stink-O, MIT, 8ae6409af90335e461ba473c92a3e56798f30d38; upstream shaders ran only in its standalone preview. Its source license is retained as ORBITAL-LASER-LICENSE. Synthetic OL terrain is not Horizon gameplay. Sound recording credits are in SOUND-CREDITS.md. This orphan evidence branch is excluded from the implementation PR diff.

Capture scripts use task-local paths and require the pinned OL checkout and its built preview. GPU commands must run through the project's queued lease/laptop_job wrapper. Sandbox coordinates, measured ages and preview arguments are in each capture.json. The scripts never operate Artem's playing instance.
''')
manifest={}
for p in OUT.rglob('*'):
    if not p.is_file() or p.name=='manifest.json': continue
    if p.suffix=='.png':
        assert Image.open(p).size==(1280,692),(p,Image.open(p).size)
    manifest[str(p.relative_to(OUT))]={'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(len(manifest),'files',sum(f['bytes'] for f in manifest.values())//(1024*1024),'MiB')
