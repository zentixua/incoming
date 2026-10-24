# /// script
# dependencies = ["pillow"]
# ///
# Доказательства круга 3 PR #425: листы кадров main / R2 / R3, ролики в реальном времени, интервалы кадров у рождения.
# Источники: r3/ (этот круг), ../wv-codex-evidence/r2 и r2-main (круг 2 и main cd310f2b на том же стенде).
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json, shutil, subprocess

here = Path(__file__).resolve().parent
codex = here.parent / 'wv-codex-evidence'
out = here / 'publish' / 'r3'
out.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype(subprocess.check_output(['fc-match', '-f', '%{file}', 'DejaVu Sans'], text=True), 18)
TREES = {'main': codex / 'r2-main', 'R2': codex / 'r2', 'R3': here / 'r3'}


def cap(tree, folder):
    p = TREES[tree] / folder
    return p, json.loads((p / 'capture.json').read_text())


def nearest(c, t):
    return min(c['frames'], key=lambda f: abs(f['age'] - t))


def sheet(title, rows, times, filename, crop=None, w=640):
    h0 = 692 if crop is None else crop[3] - crop[1]
    w0 = 1280 if crop is None else crop[2] - crop[0]
    h = int(h0 * w / w0)
    image = Image.new('RGB', (110 + w * len(times), 42 + len(rows) * (h + 36)), '#181818')
    d = ImageDraw.Draw(image)
    d.text((10, 8), title, font=font, fill='white')
    for row, (label, tree, folder) in enumerate(rows):
        p, c = cap(tree, folder)
        y = 42 + row * (h + 36)
        d.text((5, y + 32 + h // 2 - 9), label, font=font, fill='white')
        for col, t in enumerate(times):
            f = nearest(c, t)
            im = Image.open(p / f['file']).convert('RGB')
            if crop: im = im.crop(crop)
            image.paste(im.resize((w, h), Image.Resampling.LANCZOS), (110 + col * w, y + 32))
            d.text((114 + col * w, y + 4), f'actual age {f["age"]:.3f} s', font=font, fill='white')
    image.save(out / filename, quality=93)


for dist in (20, 50, 100):
    early = {20: [0.017, 0.033, 0.05, 0.1], 50: [0.05, 0.08, 0.115, 0.15], 100: [0.1, 0.18, 0.26, 0.35]}[dist]
    sheet(f'Shahed 50 kg, {dist} m, day, standing: main / R2 (reviewed) / R3. Actual frame ages.',
          [('main', 'main', f'd{dist}'), ('R2', 'R2', f'd{dist}'), ('R3', 'R3', f'd{dist}')], early, f'compare-{dist}.jpg')
    sheet(f'Shahed {dist} m: lifted dust stays and settles. R2 (reviewed) / R3.',
          [('R2', 'R2', f'd{dist}'), ('R3', 'R3', f'd{dist}')], [0.15, 0.3, 0.5, 1, 2, 3], f'settle-{dist}.jpg', w=480)
sheet('First frames: R2 white dome / R3 shimmer bounded to 3 px. Raw-pixel crops.',
      [('R2 20 m', 'R2', 'd20'), ('R3 20 m', 'R3', 'd20'), ('R2 50 m', 'R2', 'd50'), ('R3 50 m', 'R3', 'd50')],
      [0.0, 0.017, 0.033], 'dome-crop.jpg', crop=(340, 160, 940, 460))
sheet('Rain 50 m: main / R2 / R3. Condensation forms behind the shock and evaporates.',
      [('main', 'main', 'wet50'), ('R2', 'R2', 'wet50'), ('R3', 'R3', 'wet50')], [0.05, 0.08, 0.115, 0.15], 'rain-50.jpg')
sheet('50 m horizon, raw pixels: R2 thin lines from neighbouring atlas frames / R3 none.',
      [('R2', 'R2', 'd50'), ('R3', 'R3', 'd50')], [0.08, 0.12, 0.2], 'horizon-crop-50.jpg', crop=(100, 330, 600, 400), w=700)

# интервалы кадров вокруг рождения взрыва: рывок в тике рождения
lines = ['scene,tree,first_interval_ms,max_interval_first_0.3s_ms,mean_fps_first_0.3s']
for folder in ('d20', 'd50', 'd100', 'wet50'):
    for tree in ('main', 'R2', 'R3'):
        p, c = cap(tree, folder)
        ages = sorted({round(f['age'], 6) for f in c['frames'] if f['age'] <= 0.3})
        gaps = [b - a for a, b in zip(ages, ages[1:])]
        lines.append(f'{folder},{tree},{1000 * gaps[0]:.1f},{1000 * max(gaps):.1f},{(len(ages) - 1) / (ages[-1] - ages[0]):.1f}')
(out / 'frame-intervals.csv').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))

# выбранные исходные кадры, исходный звук и возраст кадров
for folder in ('d20', 'd50', 'd100', 'wet50'):
    p, c = cap('R3', folder)
    dest = out / folder
    dest.mkdir(exist_ok=True)
    frames = list({f['file']: f for f in [nearest(c, t) for t in (0, .017, .033, .05, .067, .083, .1, .115, .15, .25, .5, 1, 2, 3, 5)]}.values())
    clean = dict(c, frames=frames, continuous_frames_total=len(c['frames']))
    clean['audio'] = dict(c['audio'], file=f'{folder}.wav')
    (dest / 'capture.json').write_text(json.dumps(clean, indent=2) + '\n')
    (dest / 'frame-ages.csv').write_text('requested_s,actual_age_s\n' + ''.join(f'{f["t"]},{f["age"]}\n' for f in c['frames']))
    for f in frames: shutil.copy2(p / f['file'], dest / f['file'])
    shutil.copy2(p / f'{c["name"]}.wav', dest / f'{folder}.wav')
shutil.copy2(here / 'r3' / 'source.json', out / 'source.json')


# ролики в реальном времени по настоящему возрасту кадров, со звуком; пара main / R3 на 50 м
def encode(tree, folder, label):
    p, c = cap(tree, folder)
    frames = [f for f in sorted(c['frames'], key=lambda f: f['age']) if f['age'] <= 2.05]
    concat = here / f'{label}.ffconcat'
    lines = ['ffconcat version 1.0']
    for i, f in enumerate(frames):
        duration = frames[i + 1]['age'] - f['age'] if i + 1 < len(frames) else 1 / 60
        lines += [f"file '{p / f['file']}'", 'option framerate 1000', f'duration {max(.001, duration):.6f}']
    concat.write_text('\n'.join(lines) + '\n')
    target = out / f'{label}.mp4'
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-y', '-f', 'concat', '-safe', '0', '-i', str(concat),
                    '-ss', str(max(0, frames[0]['age'])), '-i', str(p / f'{c["name"]}.wav'), '-r', '60', '-c:v', 'libx264',
                    '-threads', '2', '-preset', 'fast', '-crf', '18', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k',
                    '-shortest', '-movflags', '+faststart', str(target)], check=True)
    return target


for folder in ('d20', 'd50', 'd100', 'wet50'):
    encode('R3', folder, f'wv-{folder}')
main = encode('main', 'd50', 'main-50')
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-y', '-i', str(main), '-i', str(out / 'wv-d50.mp4'), '-filter_complex',
                '[0:v][1:v]hstack=shortest=1,drawtext=text=main:x=12:y=12:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.7,'
                'drawtext=text=WV_R3:x=1292:y=12:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.7[v]', '-map', '[v]', '-an',
                '-c:v', 'libx264', '-threads', '2', '-preset', 'fast', '-crf', '18', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                str(out / 'main-wv-50.mp4')], check=True)
(main).unlink()
print('assembled', out)
