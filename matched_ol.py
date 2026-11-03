"""Pinned upstream shaders, daytime matched observer geometry; run under laptop_job --gpu."""
import importlib.util, json, pathlib, subprocess
ROOT = pathlib.Path('/mnt/data/projects/incoming/mod/run/incoming-sessions/OL-source')
OUT = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('olpreview', ROOT / 'tools/preview.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
exe = ROOT / 'tools/preview/target/release/preview'
for laser, hit in [('payload', 13.6), ('nuke', 11.0)]:
    for distance in (300, 700):
        out = OUT / f'{laser}-{distance}'
        out.mkdir(exist_ok=True)
        shader = p.build_pass(laser, out, False)
        env = p.mod_data(laser, out, [], str(distance))
        capture = json.loads((OUT / 'incoming' / f'ol-game-nuke-{distance}' / 'capture.json').read_text())
        relative = [f['age'] for f in capture['frames']]
        times = [hit + (distance - 10) / 343 + t for t in relative]
        shots = []
        for t in times:
            shots += ['--', f't={t:.6f}', 'cx=0.5', 'cy=67', f'cz={distance + .5}',
                      'lx=0.5', 'ly=67', 'lz=0.5', 'flat=1', 'trees=1', 'fov=70', 'sunel=90', 'night=0']
        args = [str(exe), str(shader), str(out), f'laser={p.LASERS.index(laser)}',
                'clouds=0', 'w=1280', 'h=692', 'frames=1', 'gpu=RTX', 'mode=0', 'style=65',
                f'reach={distance}', *shots]
        with (out / 'gpu.log').open('w') as log:
            subprocess.run(args, env=env, check=True, stdout=log, stderr=log)
        (out / 'capture.json').write_text(json.dumps(dict(laser=laser, distance_m=distance,
            eye_above_ground_m=2, vertical_fov_deg=70, quality='FULL', scene='synthetic preview',
            time_since_front_10m_ahead_s=relative, shader_clock_s=times, command=args), indent=2))
        print(laser, distance, 'complete', flush=True)
