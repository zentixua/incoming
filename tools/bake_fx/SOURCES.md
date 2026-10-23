# Fireball source and reproduction

`fireball_00.png` through `fireball_15.png` are rendered from JangaFX's **Gasoline Explosion** OpenVDB sequence
(131 frames), generated with EmberGen. The source is CC0, as stated both on the
[JangaFX download page](https://jangafx.com/software/embergen/download/free-vdb-animations/)
and in the archive's `LICENSE.txt` and `README.txt`. No EmberGen installation or runtime mod is required.

Download and extract Gasoline Explosion from that page. Keep the VDB sequence and render output outside tracked files.
Run Blender through `tools/laptop_job.sh --gpu --wait` on the laptop, while the owner's game is closed:

```sh
blender --background --factory-startup --python tools/bake_fx/bake.py -- \
  /path/to/bake fireball-vdb --vdb /path/to/Gasoline_Explosion_01 --tile 256 --samples 32
uv run tools/bake_fx/pack.py /path/to/bake
```

The supplied simulation provides billows and soot, not weapon dimensions or timing. `CloudModel` still gives each
weapon's fireball radius, rise, burn time and cooling temperature. The bake maps EmberGen's unitless temperature
field to 1800 K at its burning 90th percentile (an approximation); surface emissive power in the selected burn
frames is about 314 kW/m², within the CCPS fireball range of 200–350 kW/m². `pack.py` reports that check.

The display uses the [Narkowicz ACES film curve approximation](https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/)
with one exposure shared by all frames. This is presentation: it preserves bright folds and dark soot without
altering the source temperature or the model's light power. Surrounding glare remains bounded by the displayed fire.
The soot texture is self-emissive; the separate smoke six-way maps are unchanged.

## Источник и воспроизведение

Кадры шара — рендер бесплатной симуляции JangaFX «Gasoline Explosion» (EmberGen, 131 кадр). Лицензия CC0 проверена
на странице автора и в `LICENSE.txt`/`README.txt` архива. Команды выше повторяют запекание; исходные VDB в git не входят.
Клубы и сажа — из симуляции, размеры и время — из `CloudModel`. Перевод температуры EmberGen в кельвины приближённый;
излучение поверхности при горении — около 314 кВт/м² (CCPS: 200–350). Плёночная кривая — подача, не изменение физики.
