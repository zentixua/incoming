# Fireball source and reproduction

`fireball_00.png` through `fireball_47.png` (and their motion vectors `fireballv_00.png` through `fireballv_47.png`)
are rendered from JangaFX's **Gasoline Explosion** OpenVDB sequence
(131 frames), generated with EmberGen. The source is CC0, as stated both on the
[JangaFX download page](https://jangafx.com/software/embergen/download/free-vdb-animations/)
and in the archive's `LICENSE.txt` and `README.txt`. No EmberGen installation or runtime mod is required.

Download and extract Gasoline Explosion from that page. Keep the VDB sequence and render output outside tracked files.
Run Blender through `tools/laptop_job.sh --gpu --wait` on the laptop, while the owner's game is closed:

```sh
blender --background --factory-startup --python tools/bake_fx/bake.py -- \
  /path/to/bake fireball-vdb --vdb /path/to/Gasoline_Explosion_01 --tile 512 --samples 256 \
  --frames 17,18,…,96
uv run tools/bake_fx/pack.py /path/to/bake
```

The supplied simulation provides billows and soot, not weapon dimensions or timing. `CloudModel` still gives each
weapon's fireball radius, rise, burn time and cooling temperature. The bake maps EmberGen's unitless temperature
field to 1800 K at its burning 90th percentile (an approximation); surface emissive power in the selected burn
frames is about 314 kW/m², within the CCPS fireball range of 200–350 kW/m². `pack.py` reports that check.

The display uses the [Narkowicz ACES film curve approximation](https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/)
under CC0 (the author offers CC0 or MIT), with one exposure shared by all frames.
The 95th burning percentile maps to 0.60, with highlight desaturation only when an exposed channel exceeds white.
Renders are 512×512 at 256 samples without adaptive sampling (Cycles' denoiser does not clean transparent soot, so
32 samples left speckled cooling frames); tiles are 256×256: burning frames are taken without enlargement, and the wider cooling frames average several
render pixels per texel. There are 32
burning frames, evenly spaced in simulation time, and 16 cooling frames, evenly spaced up to the last glowing frame.
Each tile holds all of its visible matter: the edge comes from density, not from a circle, and the frame never shrinks;
`fireball.json` gives each frame's half-size in fireball radii. Optical flow (Farneback, as for the smoke puffs) gives
motion vectors to the next frame, and the layer shader blends neighbouring frames along them.
Display opacity is at least the displayed luminance; thin edges darken and redden.
Adjacent premultiplied frames blend in the layer shader along their motion vectors; the upward simulation is only mirrored horizontally, never rotated. This is presentation: it preserves bright folds and dark soot without
altering the source temperature or the model's light power. Surrounding glare, pall and light on smoke/dust remain bounded by the displayed fire (`fireball.json`),
about 0.35 at peak instead of the old clipped sheet’s 0.87. World light is therefore also lower; the night capture is
the visual check of that presentation. A small additive source bloom is drawn behind the opaque fire (presentation),
with scene-depth and smoke occlusion; this is not a new world light or an Iris HDR integration.
The soot texture is self-emissive; the separate smoke six-way maps are unchanged.

# Smoke puff source and reproduction

`puff6a_00.png` through `puff6a_15.png`, `puff6b_*` and `puff6v_*` (six-way light maps and motion vectors of the smoke
puff) are rendered from JangaFX's **Ground Explosion** OpenVDB sequence (200 frames, EmberGen), CC0 as stated on the
same download page and in the archive's `LICENSE.txt` and `README.txt`. Our own Mantaflow puff was a fuzzy ball with
almost flat light maps; the EmberGen cloud has real billows.

```sh
blender --background --factory-startup --python tools/bake_fx/bake.py -- \
  /path/to/bake puff-vdb --vdb /path/to/ground_explosion_VDB --cap --tile 384 --samples 96 \
  --frames 60,66,72,78,84,90,96,102,108,114,120,126,132,138,144,150
uv run tools/bake_fx/pack.py /path/to/bake
```

A puff of the mod is a lump of cloud, not the whole cloud with its stem: the bake keeps the cap above the cloud's neck
(the narrowest silhouette between the ground dust and the cap) with a soft edge, because the mod draws the stem and the
ground dust with puffs of their own. Every frame is rendered at the same optical depth (median 12 across the
silhouette): the simulation thins out by its own dissipation, while the mod's cloud takes its opacity and colour from
`CloudModel`. The six suns, albedo 0.55 and asymmetry 0.55 are those of the old bake; the frames give the shape of the
billows and how light passes them, not the density. The camera frames each cap, and `pack.py` measures growth in voxels.
`pack.py` also moves the water-spray maps into the new measure of the smoke maps (one normalisation for all puffs).

## Источник и воспроизведение

Кадры шара — рендер бесплатной симуляции JangaFX «Gasoline Explosion» (EmberGen, 131 кадр). Лицензия CC0 проверена
на странице автора и в `LICENSE.txt`/`README.txt` архива. Команды выше повторяют запекание; исходные VDB в git не входят.
Клубы и сажа — из симуляции, размеры и время — из `CloudModel`. Перевод температуры EmberGen в кельвины приближённый;
излучение поверхности при горении — около 314 кВт/м² (CCPS: 200–350). Плёночная кривая — подача, не изменение физики.

Рендер 512×512, 256 выборок без адаптивной выборки (шумодав Cycles прозрачную сажу не чистит: при 32 выборках кадры
остывания были крапчатыми), кадры 256×256: горение — без увеличения, у более широких кадров остывания пиксель —
среднее нескольких пикселей рендера. Горение — 32 кадра равно по времени симуляции, остывание — 16 до последнего
кадра со светящимся пламенем. Кадр вмещает всё вещество: край — по плотности, а не кругом; полуразмер кадра в радиусах
шара — в `fireball.json`. Векторы движения к следующему кадру — оптический поток (Farneback, как у клубов дыма);
шейдер смешивает соседние кадры вдоль них.

Подача: верх закреплён, соседние кадры смешиваются в шейдере вдоль векторов движения. Тонкий край краснеет и темнеет;
альфа не ниже экранной яркости, ядра выше белого теряют насыщенность. Свет окружения ограничен таблицей огня на экране,
не прежним пересвеченным листом. Мягкий ореол — подача после Iris, не изменение физической мощности.

## Клуб дыма

`puff6a_*`, `puff6b_*`, `puff6v_*` — рендер CC0-симуляции JangaFX «Ground Explosion» (EmberGen, 200 кадров), команды выше.
Своя Mantaflow давала пушистый шар с почти ровными картами света, у EmberGen — клубы настоящего облака взрыва. Клуб мода —
комок облака, а не всё облако с ножкой: берётся шапка выше шейки облака с мягким краем (ножку и пыль у земли мод рисует
своими клубами). Толща кадров одна (медиана 12 поперёк силуэта): симуляция тает своим рассеиванием, а непрозрачность и
цвет облака мода — из `CloudModel`. Шесть солнц, альбедо 0,55 и асимметрия 0,55 — прежние: кадры дают форму клубов и
как свет их проходит, а не плотность. Карты брызг `pack.py` переводит в новую меру карт дыма (одна на все клубы).
