# Карта кода: картинка взрывов, объём, факелы и следы (снимок 18.10.2026, main после 3.3.0)

Номера строк — на момент снимка; перед работой сверить. Префиксы: J = mod/src/main/java/ua/zentix/incoming,
S = mod/src/main/resources/assets/incoming/shaders, T = mod/src/test/java/ua/zentix/incoming. Правила области:
.claude/rules/client-fx.md, client.md, nuclear.md, client-scenario.md.

**Главное**
- Объёмный проход работает только при живой ядерке: `NukePall.frame` без подрыва отдаёт null (J/client/nuclear/NukePall.java:179-181).
- Шейдер написан не под один гриб: он перебирает до 4 общих параметрических «грибов»; простой шар — уже одно из состояний.
- Ни один эффект обычного взрыва не берёт `BlackBody`, кубический корень заряда или настоящий ветер: везде ручные
  постоянные и общий `Fx.WIND_X/Z`.

## 1. Картинка обычного взрыва сейчас

- Пакет `S2C.Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile)`, виды DRONE=0,
  MISSILE=1, BUNKER=2, ROCKET=3 (J/net/S2C.java:34-35): ни заряда, ни ветра, ни времени. Шлёт конструктор
  `Warheads.SurfaceBlast` (J/warhead/Warheads.java:500-509). Заряды в кг ТНТ — `WeaponSpec.charge` (шахед 50, ракета 450,
  «Град» 6.4, «Ланцет» 3; J/strike/WeaponSpec.java:332,348,398,411); «Ланцет» идёт видом `Blast.DRONE` и выглядит как
  шахед. Прочие пакеты: `Intercept(pos, kill, target, seed)` (S2C.java:515); `BunkerImpact`, `Vent`, `Collapse` (pos,
  material) :73, :85, :97.
- **BlastEffects** (J/client/fx/BlastEffects.java): `blast()` (:36-47) ближе `FarBlasts.NEAR` (640) — таймлайн частиц,
  `FarBlasts.add` — всегда. Приход — `Warheads.FRONT_SPEED` (звук/20 ≈ 17.15 блока/тик) полосами по 17 блоков (:76-91).
  `flash()` → `FlashFalloff` × `daylight` → `Flash.trigger(s, decay, 0xFFF1C8)` (:100-122). `spray()` — ванильные частицы
  350/900, 150/400 (:125-133). По видам (всё вручную): Drone R=5 («≈50 кг»), вспышка ближе 3R, дальность 240, decay .72,
  горение 220 тиков, тряска 26/22/16/10 по полосам (:138-174), «Град» берёт Drone с дымом R=5; Airburst R=2.5, 7.5/160/.6,
  тряска 10/6 (:178-217); Missile R=8.5 («≈450 кг»), 25.5/400/.8, конденсация r=30, горение 320, подрывы на t=60/97/143
  и `MISSILE_SECONDARIES` (:221-273); Bunker R=11 (2.4 т, «∝W^⅓ ×1.75») (:277-355); `bunkerImpact`, `vent`, `collapse` (:360-449).
- **Explosions** (J/client/fx/Explosions.java), всё вручную: `burst` (:28-61) — радиус и тики шара из `Look`, а дым,
  осколки, искры и пыль от `r` вызывающего: `ballSmoke` = 22r/4·k + 4 (:81-83); дым r·0.55 → r·(1.6–2.3), жизнь 420–720,
  подъём .011, цвета 0x24201D→0x6E6862 (:36-42); осколки 9r/4·k + 3, искры 60r/4·k + 10 (:46-58); `dustSurge` 44r/4·k + 6
  (:86-95). `fireball` (:68-78): шар и `LOBES`=4, вверх 0.012·радиус за тик. `burn` (:105-122), `cookoff` (:125-136),
  `condensation` (:139-146).
- **FarBlasts** (J/client/far/FarBlasts.java): запись `Look` (:127-128), таблица (:130-134):

| Look | fireball | flash | flashT | ballT | burnT | column | riseT | base | top | puffs | life | dust | audible |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DRONE | 5 | 40 | 3 | 13 | 1200 | 150 | 2400 | 10 | 40 | 12 | 3600 | .25 | 20 000 |
| MISSILE | 11 | 60 | 4 | 30 | 600 | 270 | 2400 | 18 | 70 | 14 | 4800 | .2 | 40 000 |
| ROCKET | 3.5 | 30 | 2 | 8 | 0 | 105 | 2400 | 7 | 26 | 10 | 3000 | .45 | 15 000 |
| BUNKER_BREACH | 9 | 60 | 3 | 20 | 0 | 210 | 2400 | 18 | 55 | 14 | 4200 | .5 | 30 000 |
| BUNKER_DEEP | 0 | 0 | 0 | 0 | 0 | 110 | 500 | 14 | 32 | 12 | 3000 | 1 | 30 000 |

  Привязано к замерам (doc :40-42, :107-111): шар ≈1.7·W^⅓, свечение 0.2·W^0.35 с, верх столба по Чёрчу 92.6·W^¼ м на
  2 минуте. `FarBlastsTest` проверяет это при W = 6.4/30/300 кг — не 50/450 паспорта. Шар ракеты дальний 11, а ближний
  `Missile.R` 8.5. Вручную: flash, flashTicks, burnTicks, riseTicks (2400 у всех), base, top, puffs, life, dust, audible;
  постоянные (:46-104, :136): CAP 256, BALL 8, BURN 2, BEHIND .001, HALO_SIZE 3, BALL_LIFT .45, BALL_RISE .2, BURN_SIZE .3,
  BALL_FADE .55, CLOUD_ALBEDO .7, SKY_*, OPACITY .85, SPREAD .4, DRIFT 20, FADE_IN 8. Цвета DARK/AGED/DUST_GREY и оттенки
  BALL_HOT/MID/COLD — постоянный RGB, не тело (:101-104). Законы: `ball(u)` = 8(1−u)³ (:461-464); `flash` ∝ 1−f²
  (:455-458); `growth` как в gen_particles (:441-444); `tint` — три ключа (:510-517); `cloudGlow` (:550-553). Столб
  (:560-593): `rise` = 1−e^(−3·age/riseT) (:610-612); `height` = 0.6r + f·column·rise (:628-630); радиус base→top линейно
  (:623-625); расплывание 1+0.5·age/life; снос = `Fx.WIND_X/Z`·DRIFT·age·shear(.3+.7·f·rise) (:567, :578-579). Клубы —
  плоские спрайты к камере (`FarSprites.puff`), не объём. `light()` питает `FxLights` (:414).
- Вспышка: J/client/fx/Flash.java:19-26 (сильнейшая, спад за тик, множитель настройки). `FlashFalloff`: PEAK .85, AMBIENT
  .5, OCCLUDED .35, DAYLIGHT .08 (J/client/fx/FlashFalloff.java:25-34); `strength` (:54-64).
- Тряска у обычного взрыва: `CameraShake.blast(ticks)` держит только число тиков (J/client/fx/CameraShake.java:53-55);
  амплитуда по корзинам: ≥26 тиков 5°/3.5°, ≥18 3/2, ≥8 1.5/1, иначе .6/.4, дрожь 9.7/12.3 Гц (:171-176). Законы psi и ∛Z
  — только у ядерки (:80-130).
- Осколки: CAP 1200 (J/client/fx/Fragments.java:31), только NukeDust, не обычные взрывы.
- `GroundMaterial`: ручные r,g,b и пористость (J/warhead/GroundMaterial.java:25-44); их берут `Explosions.rgb/lighten`
  (:155-162) и цвет дальней пыли — на 25% к DUST_GREY (FarBlasts:195-197).

## 2. Объёмный проход

- **PallPass** (J/client/fx/layer/PallPass.java; обзор :41-79). Проходы в `draw()` (:522-707): 0 — карта теней
  `fx_mush_shadow` (:587-594); 1 — лучи `fx_pall` в 1/factor разрешения (:596-642); 2 — донакопление `fx_pall_resolve`
  (:644-657); 3 — сведение `fx_pall_composite`, смесь ONE/SRC_ALPHA, копия кадра для света шара (:659-698).
- Качество (:96-111): OFF, LOW(4,20), MEDIUM(2,28), HIGH(2,48); цена шага = steps/factor² (:114-119). `LADDER` (:125-126):
  (2,48), (2,36), (2,28), (2,22), (3,26), (3,20), (4,24), (4,20). `Detail` (:135-235): BUDGET_MS 2, UP_SHARE .7, окно .5 с,
  удержание 3 с (×4 после отскока), MIN_SAMPLES 4, CALM 2; закреплён в сценариях и экспорте Flashback (:528-536). Качество
  OFF всё равно рисует грибы на LOW (NukePall.java:198).
- Вход: `FxLayer` (J/client/fx/layer/FxLayer.java:188-205) → `NukePall.frame` (J/client/nuclear/NukePall.java:178-229),
  нужен непустой `ClientNuclear.detonations()` (:180-181). Таблица пелены: 128 × ROWS=4, пыль и мгла не больше 2 мест
  (:82-84); раскладка mat4 места — S/include/pall.glsl:5-15.
- **Таблица MushroomField** (J/client/nuclear/MushroomField.java): MUSHROOMS 4, TEXELS 19, BB_NODES 256 (500–30 000 K,
  логарифм), WIDTH 256, ROWS 5, BB_ROW 4 (:31-38). Текстура RGBA32F 256×5 создаётся в PallPass:885-889; строки 0–3
  грузятся каждый кадр (:556-559), строка тела — один раз (`blackBodyRow` :141-143). Раскладка текселей
  (S/include/mushroom.glsl:8-32; пишет `write()` :496-551): 0 — эпицентр x, z, y земли, флаг; 1 — снос x, z, endTop, радиус
  рамки; 2 — capY, capR, ringR, boundTop; 3 — ringA, ringB, domeY, domeR; 4 — domeH, capSigma, capTile, секунды; 5/7/9 —
  ω′ rgb и g′ шапки / ножки / юбки; 6 — stemTop, stemR, lowDust, stemTau; 8 — юбка R, W, H, σ; 10 — T_amb, ΔT ядра,
  capBottom, stemTile; 11 — подъём ножки, fireTop, fireR; 12 — свет огня rgb (`fire()` :196-211) и часы клубления в w;
  13–15 — водяной столб; 16 — edgeShare, billows; 17 — прямоугольник тени; 18 — верх мира.
- Выбор `frame()` (:151-187): до 4 по угловому размеру capR/расстояние, пирамида видимости без дальней плоскости, грибы
  только для тени — последними. Вход — `List<ClientNuclear.Active>` (Detonation, `MushroomModel.Shape`, вода, пелена, шторм).
- **Шейдер**. mushroom.glsl: структура `Mush` (:80-128), `mushLoad` (:130-141), `mushOn` (:144), SDF `mushDist`/`mushMean`
  (:300-330), пропуск пустоты `mushGap` (:336-360), рамка наклонного цилиндра `mushLine`/`mushSpan` (:366-396), таблица
  тела (:399-415), `mushTransmit` (:419-436), тень (:449-529). fx_pall.fsh: массивы `gMush[MUSHROOMS]` (:735-737); отсев
  на луч — строки до первой пустой, `mushSpan` на гриб с обрезкой по глубине сцены и дальности, общий отрезок [m0, m1]
  (:1179-1192); **один общий ход для всех грибов**: mSteps = steps+16, dsMin = max(0.35·m0·pixel, (m1−m0)/(4·mSteps)),
  dsMax = (m1−m0)/6 (:1236-1245); `mushStep` перебирает все грибы с пропуском пустоты (:1073-1100); `mushAt` (:975-1070):
  плотность из шума (`mushDense` :902), четыре 4-точечных `mushTau` (вверх/вниз/дальше/прочь), `mushSunTau` (5 + 4 точки),
  свет шара, излучение = тело(T_amb + ΔT·heat) × `PallExposure`; выход при пропускании < 0.005 (:1249, :1301).
- Кто ещё читает таблицу: fx_pall_resolve.fsh:61-63, fx_probe.fsh:28-33, fx.vsh:86 (6 шагов на четырёхугольник на гриб),
  fx_mush_shadow.fsh:34-38, fx_pall_composite.fsh:101. Карта теней 128×512 RGBA16F (PallPass:902-912).
- Огонь и пелена (только ядерка): карта FireStorm RGBA16F на ячейку 32 блока, сторона ≤256, с mip; R пламя, G подача
  дыма, B дым × подъём (J/client/nuclear/FireStorm.java:37-49), одна на место (`PallPass.Fire` :238-260); FireStorms
  вручную: FIRE_R/G/B 1/.42/.11, FLAME_NEAR 96, WHIRLS 4 (J/client/nuclear/FireStorms.java:28-56); FireField — кольца по 32
  блока, MAX_FIRES 20 000, RISE 400 тиков, TAU 6000 (J/client/nuclear/FireField.java:14-27), для звука и подрывов, не для
  видеокарты; PallField BINS 128 (J/client/nuclear/PallField.java:42), `row()` :404.
- **Что менять для облаков обычных взрывов**: путь кадра без условия ядерки (NukePall:180, FxLayer:188-193); запись строки
  без Detonation/Shape (`write()` :496); шар уже ложится в формат (ringR 0 — шар до свёртки, mushroom.glsl:1-3; stemTau,
  skirtSigma и colSigma = 0 пропускают части, `mushGap` :339-357; edgeShare 1 — шар; температура — тексель 10 и строка
  тела, 500–30 000 K покрывает ~2000 K); пределы числа или своя таблица (Java MUSHROOMS/ROWS/BB_ROW, GLSL
  `MUSHROOMS`/`MUSH_BB_ROW` mushroom.glsl:34-35, высота карты теней SHADOW×MUSHROOMS; `MushroomGlslTest.tableLayoutMatchesJava`);
  выбор — топ-4 по угловому размеру вытеснит ядерку близкими облаками; **общий шаг**: малое облако рядом и ядерка вдали
  на одном луче растягивают dsMin до ~(m1−m0)/176 — каждому объекту нужна своя сетка шагов (как уже у голов стены);
  цена заслона четырёхугольников в fx.vsh растёт как четырёхугольники × строки; часы клубления и законы шапки —
  `MushroomModel.churn` (ядерное).

## 3. Факелы и следы

- `Exhaust.Plume` (J/client/fx/Exhaust.java:37): `plume()` — `switch` по классу сущности: длины, радиусы, яркость, цвета
  0xFFF4E0/0xFF8A30, мерцание (:71-99) — не в паспорте. PlumeRenderer: внешняя полоса ×2.6, ядро 0.6 длины, ореол 4.5r,
  аддитивный `eyes` (J/client/render/PlumeRenderer.java:34-53). Частицы выхлопа: VOLUME_PUFFS_PER_TICK 1, VOLUME_ALPHA .45
  (:127-129); «Град» и обратный выхлоп (:131-188); ускоритель (:220-250); клубы и облако старта МБР, 10/4 клуба за тик
  140 тиков (:259-301); след бомбы (:306-322); зенитная ракета отрезками 1.6 блока (:332-355); горячие обломки (:362-377).
  Всё вручную.
- ClientWeaponSpec (J/client/ClientWeaponSpec.java): `FarLook` :113, `Stage` :124, `Flame(radius, back, brightness, color)`
  :137; `Trail(step, life, width0, width1, color0, color1, alpha, fadeIn, fadeFrom, wind, rise)` :159-209 — ширина
  кубической кривой, цвет f^0.6, снос = wind·9·(age−9), подъём = rise·9·(0.15a + 0.85·40(1−e^(−a/40))); **закона
  диффузии нет**. Значения (:212-268, «взято с ближних клубов»): пламя — ускоритель шахеда .3/3/15, ускоритель ракеты
  .56/5.4/20, маршевый .21/3.4/.05, пике .34/3.9/.15, «Град» .38/3/20, МБР 2.5/18.7/25; следы — ускоритель шахеда
  2/320/.4→2.7, ускоритель ракеты →3.9, выхлоп шахеда 4/40, дымка ракеты 4/60, «Град» 2/520/.6→3.6, МБР 4/1050/2.5→11,
  CONTRAIL 4/500/.5→3.9 (α .6, wind .6, rise 0), след бомбы 2/24.
- Инверсия B-2: 4 сопла (±3.2, .3, −9.5) и (±1.9, .3, −9.3) (:285); крейсер — `Stage(null, CONTRAIL)` (:257) без условия
  высоты и влажности.
- FarFlightView (J/client/far/FarFlightView.java): SIGHT_PERIOD 4, TONE .76, MAX_NOZZLES 4 (:60-72); снос `Fx.WIND_X/Z`·drift
  и подъём (:363-364). TrailPoints: кольцо на 16 384 (J/client/far/TrailPoints.java:16).

## 4. BlackBody (J/nuclear/model/BlackBody.java)

- `light(double kelvin, double[4] out)`: линейный sRGB и яркость Y (белая стена в полдень = 1) (:28-36);
  `luminance(kelvin)` (:39-41); SUN_DISK 46 200, SUN_K 5800 (:11-13). Планк × приближение Wyman–Sloan–Shirley, 380–780 нм
  шагом 2 нм (~200 итераций на вызов) — табулировать, не звать каждый кадр.
- Берут: строка тела MushroomField (:125-133) и `balls` (:232), NukeRenderer.java:132, CloudPuffs.java:102,
  `MushroomModel.light` :138; тест `BlackBodyTest`. Обычные взрывы — нет: кадры их шара раскрашены заранее
  (tools/gen_particles.py:234-253, 700–4000 K, BB_DAY 30 при 2000 K) плюс `FxAtlas.fireLight` из fireball.json.

## 5. Огонь после обычного удара

- Сервер (Warheads.java): зажигательный второй взрыв на t==1, сила 3 (ракета) / 2 (шахед) (:519); «Fire ring» ракеты — 8
  точек на r=7 (:521-527); `igniteGround` (:416-420), `igniteAt` (:427-433); огонь каверны бункера — 6 точек (:667-668,
  :714-721). `StagedExplosion.burn` — треть снятых блоков (J/warhead/StagedExplosion.java:680-686). Горящие обломки —
  состояния костра/огня (J/warhead/DebrisSpawner.java:47-53).
- На клиенте модели этих пожаров нет: ванильный огонь — ванильный. Свои: `Explosions.burn`/`cookoff`, дальний спрайт
  горения (FarBlasts:424-433, burnTicks 1200/600), `Exhaust.debris`. FireField, FireWatch, FireStorm — только ядерка.

## 6. Частицы

- `Fx.WIND_X` = .0022, `WIND_Z` = .0011 — общие постоянные (J/client/fx/particle/Fx.java:20), не ветер ядерки
  `Detonation.windDir/windSpeed` (J/nuclear/Detonation.java:32-33).
- Виды частиц (:24-42); по умолчанию drag .96, wind 1 (:127-157); у огненного шара wind .3 (:68-70). `density`: 1 / .6 / .3
  по настройке частиц, × max(.35, 160/d) дальше 160 блоков (:82-93). Физика (J/client/fx/particle/FxParticle.java:132-143):
  rise·(.15 + .85e^(−age/40)) − gravity, плюс ветер, × drag.
- Пределы FxBudget (J/client/fx/particle/FxBudget.java:9-31): CLOUD 5120, GROUND 3584, TRAIL 5120, DEBRIS 2560, SPRAY 2048,
  SCORCH 1536, FLASH 512, SPARK 15 360, ASH 3072; ещё Fragments 1200, события FarBlasts 256, TrailPoints 16 384,
  `FxLights.MAX` 4.

## 7. Видеокарта: бюджеты и замеры

- Бюджет `Detail.BUDGET_MS` = 2 мс (PallPass:137).
- Замеры: свет шара на мир ≤0.03 мс (RTX 4060, ночь, MEDIUM; client-fx.md:169-170); карта тени гриба 128² — +2–8% к
  проходам пелены на llvmpipe, 256² — +18% (client-fx.md:288-289); первая отрисовка слоя — 35 мс на ноутбуке
  (FxLayer.java:57-61); память текстур <2.5 МБ при 1080p (PallPass:70-72). Замера одного хода грибов отдельно нет:
  `SCENARIO pall` (PallPass.describe :1047-1054) и строка DEBUG (:1015) пишут весь проход пелены. Из памяти проекта:
  гриб с пылью 0.5–1.3 мс до 2 минуты, 2.8 мс на 4 минуте (рост — пелена).

## 8. Тесты и сценарии

- Юнит: T/client/fx — `ExplosionsTest.salvoSmokeFitsCloudGroup`, `ExhaustTest.volumePuffsOfThreeRocketPacksFitTrailGroup`,
  `FlashFalloffTest` (6), `CameraShakeTest` (4); T/client/far — `FarBlastsTest` (`columnsMatchTheirWarheads`,
  `fireballsMatchTheirCharges`, `ballOutshinesDaySky…`, `smokeWaitsForTheBallToFade`, `columnRisesFastThenSettles`, …),
  `FarFlightViewTest` (`bomberLeavesAContrailFromEachEngine`, `rocketTrail…`, …), `TrailPointsTest`, `FarSpritesTest`,
  `SightTest`; T/client/FarLookTest; T/client/fx/layer — `PallDetailTest`, `PallNoiseTest`, `ShaderSourceTest`,
  `FxLightsTest`, `SourceProbesTest`; T/client/fx/particle/FxParticleTest; T/client/nuclear — `MushroomFieldTest`,
  `MushroomGlslTest` (`tableLayoutMatchesJava`, `blackBodyNodesMatchJava`, `ballLightMatchesJava`, …), `PallGlslTest`,
  `FireStormTest`; T/nuclear/model — `BlackBodyTest`, `FireballModelTest`.
- GameTest: `EdgeGuardGameTests.igniteGroundAtUnreadyChunkLoadsNothing`.
- tools/client_scenario.sh (разбор — mod/src/devtest/java/ua/zentix/incoming/scenario/ClientScenario.java:192-221): взрывы
  `fx`, `fx-night`, `fx-late` (`planFx` :1373), `occlusion`; пуски `launch`, `rocket`, `loiter`, `all`, `far-models`,
  `onboard`, `flyby`/`flyby-all`; объём `nuke`, `mushroom` (`INCOMING_MUSHROOM`=night|shadow|glow), `pall`, `pall-front`,
  `pall-low`, `wall`, `firestorm`. tools/prod_client.py: `fx --prop incoming.fx.targets=…` и `salvo-bench`.
