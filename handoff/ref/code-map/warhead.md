# Карта кода: обычный взрыв, урон, пробитие (снимок 18.10.2026, main после 3.3.0)

Номера строк — на момент снимка; перед работой сверить (`git log -1`, поиск по имени).

Префиксы путей:
- M = mod/src/main/java/ua/zentix/incoming
- D = mod/src/devtest/java/ua/zentix/incoming/gametest
- T = mod/src/test/java/ua/zentix/incoming

## 1. M/warhead/Warheads.java

**Входы**
- `detonate(level, weapon, pos, proj, owner)` :79 ставит таймлайн `SurfaceBlast`. Зовёт `StrikeProjectile.impact`
  (шахед, ракета, «Град», «Ланцет»; M/entity/StrikeProjectile.java:816-818).
- Падение до взведения — ванильный `level.explode` силы 1.5, `NONE`, через `whenReady` (StrikeProjectile:847-849).
- Ядерная бетонобойная минует всё это: `NuclearWarhead.detonate` (M/entity/BunkerBusterEntity.java:130-134).
- `bunkerEntry` :90: `explode(... point+1, BUNKER_ENTRY_POWER=4 (:87))` без района; `hurtAround` 14 блоков, KINETIC,
  урон по расстоянию 60/22/7 (:96); `S2C.BunkerImpact` и тряска игрокам до 60.
- `bunker(pos, entry)` :105 ставит `BunkerBlast`.

**Помощники**
- `reach(power) = 2·power + 1` :118. Лучи в воздухе идут ~1.73·power; поиск сущностей — 2·power. Задаёт размер
  `BlastArea`, запрос аппаратов Sable, `whenReady` для аппаратов (BlastFront:232) и путь падения.
- `whenReady` :127 — сейчас или позже через `Deferred` :292 (сдаётся через 6000 тиков).
- `explode(level, area, after, at, power, fire, direct, owner, calculator)` :144-153 — единственная фабрика
  `StagedExplosion`: читает `blockDamage`, `fire && config.fire`, держит район (или свой `reach(power)`), `area.queued`,
  `impacts().add`.
- Крючки тестов, все по силе: `testRays` :159, `testBlast` :173, `Probe` :184.
- `unit(level, area, after, kind, what, work)` :243 / `Unit` :248 — общая единица под бюджетом, ждёт взрывы `after`.
- `power(WeaponType)` :322 = `spec().blastPower()` (M/strike/WeaponSpec.java:315) — IntValue конфига.
- `hurtAround` :330 — ступенчатый урон по расстоянию всем LivingEntity за один тик: без линии видимости, без бюджета.
- `shatter` :355 (Box), `shatterGlass` :365 (Wave по `tntKg`), `glassReach` :370 — входы тестов.
- `push(c, band, strength[], filter, effects)` :380: кольцо `[(band−1)·FRONT_SPEED, band·FRONT_SPEED)`, `FRONT_SPEED` =
  17.15 блока/тик (:73). Толчок: по горизонтали k, вверх 0.3k, × (1 − сопротивление отбросу); эффекты — игрокам.
- Ещё: `igniteGround` :416, `igniteAt` :427, `blob` :439, `fill` :458.
- `MISSILE_SECONDARIES` :473 (тики 3/3/5/5/9, сила 5/5/4/4/3) — общий с M/client/fx/BlastEffects.java:244 и
  T/client/fx/ExplosionsTest.java:14.

**SurfaceBlast** :479-588
- Конструктор: радиус района = max(11 + reach(5), reach(power)) :496; `GroundMaterial.sample` :499; вид `S2C.Blast` из
  `spec().blast()` :500-509; `main = explode(power(weapon), fire=false)` :510.
- t=1: огненный шар — взрыв силы 3 у ракеты / 2 у прочих, fire=true, после `main` :519; только ракета — единица
  «Fire ring», 8× `igniteGround` на r=7 :521-527; единица «Debris» :529; `shatterGlass`, если `config.shatterGlass` :531.
- Ракета: доп. подрывы :534-536; полосы толчка {1.4, 1.0, 0.6} и CONFUSION/DARKNESS :537-546.
- Прочие (виды DRONE и ROCKET: шахед, «Град», «Ланцет»): t3 два взрыва силы 3, t6 силы 4 и 2 :548-557; толчок {1.1, 0.7} :559.
- Таймлайн — 12 тиков :561.
- `shatterGlass` :573-587: листва у ракеты — `Box(11,3,16)` :576; стекло — `Wave.of(pos, spec().charge())` :579.

**BunkerBlast** :593-761
- Конструктор: район = max(6 + reach(12), reach(power(BUNKER))) :616; `surfaceY` по карте высот `MOTION_BLOCKING`,
  `depth` :623-624; укрытие `BunkerCover` :626/:705 уходит только клиенту (пролом `BunkerCover.breaches` читают
  M/client/fx/BlastEffects.java:290 и M/client/far/FarBlasts.java:242; сервер по нему не ветвится).
- Каверна: 12 случайных `blob` из DRILLABLE в множество `weakened` :632-636; калькулятор даёт сопротивление 0 внутри
  `weakened` :637-643.
- Три взрыва в одном тике с этим калькулятором: два силы 12 со сдвигом (±3, ±2, ±3) и главный `power(BUNKER)` :644-650
  (для них есть `BlastArea.peersPicking`).
- `hurtAround` 24 блока, SHOCKWAVE, 200/40/12, сквозь породу; игрокам на 24–34 — 4; тряска до 320 :653-659.
- Таймлайн: t1 «Cavity fire» :667; t2 «Rubble» :671/:724-735 (уцелевшие ослабленные блоки — гравий над зарядом, булыжник
  под ним; магма в BB_ROCK); t3 два взрыва силы 4, `S2C.Vent`, обломки «Ejecta» :672-677; t22 «Roof collapse», если
  `config.collapse` и 4 ≤ depth ≤ 48 :678-684 (`collapse()` :738-761: труба из (depth − 8)/3 комков гравия, воронка из
  гравия, грубая/корнистая земля, `S2C.Collapse`, тряска до 50).
- Толчок {1.2, 0.8} только сущностям без неба над головой :689. Длится 24 тика.

## 2. M/warhead/StagedExplosion.java

Фазы: START → RAYS → DETONATE → SPLIT → DAMAGE → BLOCKS (:134). Вид `ImpactCost` по фазе: RAYS / HITS / BLOCKS (:286-292).

- **START** :327-361. Центр в сетке плотов Sable — целый ванильный `level.explode` :333-337. Иначе
  `new Explosion(... power, fire, DESTROY_WITH_DECAY | KEEP, NONE частицы, SILENT)` :339-343, `EventHooks.onExplosionStart`
  :345. `SubLevels.mayHaveCraftNear(at, reach(power))` или `forceVanilla` — ванильный путь `vanilla()` :353/:370-402 (весь
  `e.explode()` одной единицей; список сущностей Detonate берёт `onDetonate` :605-610). Иначе `gameEvent(EXPLODE)` и 1352
  силы лучей `power·(0.7 + 0.6·rand)` :357-358.
- **RAYS** :405-441. Пачки по `RAYS_PER_UNIT` = 128 (:97) по заранее посчитанным ванильным направлениям :101-127.
  Сопротивление блока — от калькулятора с настоящим миром :424, кэш по одинаковому блоку :418. **Ванильная потеря —
  :429** `f -= (res + 0.3)·0.3` и **:434** `f -= 0.225`. Выбор — `shouldBlockExplode(... f)` → `chosen` :430. Шаг 0.3
  блока :431-433. Физическая потеря заменит начальную силу (:357-358), эти две строки вычитания и проверку выбора :430.
  `damage` — `ExplosionDamageCalculator` (обычный или ослабленный бункерный) :169/:344. `RayCounts` :211.
- **DETONATE** :444-461: `toBlow.addAll(chosen)`; рамка сущностей ±(2·power + 1) :450-454; `onExplosionDetonate(... f2)` :457.
- **SPLIT**: `partition` :467-496 — чанки плотов (аппараты) и мира; `split` :527-550 — без воздуха (кроме огня),
  по расстоянию, снимок `Block` на этот момент.
- **DAMAGE** :553-595 — копия ванильного цикла, `ENTITIES_PER_UNIT` = 8 :99; `d11 = dist / 2·power` :562; урон
  `damage.getEntityDamageAmount` :572; отброс `(1 − d11)·getSeenPercent·…` :573-576; пакет `tell()` с толчком :613-618;
  потом `blowCrafts` :504-520.
- **BLOCKS** `portion` :629-661 — по времени (`PORTION_NANOS` 5 мс :91), пачки по 16 вперемешку :95/:636-639; пропуск
  блока, который уже не тот `Block` :645; `PoiRecords` + `state.onExplosionHit` :646-649; блоки дольше 10 мс — в лог;
  `burn` :682-688 (ванильное 1/3). Выпадения сливаются и выпадают в конце :691-708. `blocked()` = ждёт `after` или
  (BLOCKS и `peersPicking`) :277-279.

## 3. Прочие классы warhead/

- **Shatter.java** — `UnitQueue.Job` вида GLASS :362. `Zone` запечатан: `Box` :90 или `Wave` :123. `Wave.of`: радиус =
  `rangeForSurfaceOverpressure(kpa(FRAGILE_PSI·JITTER_MIN), tntKg)` :124-127. `Wave.breaks` :154-159:
  `BlastModel.surfaceOverpressureKpa(d, tntKg)` → `BlockResponse.of(state).breaksAt(psi, murmurHash(pos))` — это уже
  образец «давление → порог». Секции от ближних :223-250; отсев по палитре :266-268; `PORTION` 32 :60, `SKIPS` 1024 :62.
  `open()` — обход в ширину к наружному воздуху через ≤ `SKY_DEPTH` (4) + 1 шагов сквозь не-`MOTION_BLOCKING_NO_LEAVES`
  или стекло :311-340. Края: `EDGE_FLAGS` / `EDGE` :280-296. Метка DH в `end` :385-388. `now()` :209 для тестов.
- **BlastArea.java** — `hold` :85 = тикет региона `AreaLoader` + `CraterFalls.open`; счёт `retain`/`release` :115/:172;
  `peersPicking` :103-112 (тот же тик, не `waiting`); `record`, `recordStages`, `slowBlock`, `summary` :125-201 (строка
  «Strike summary»).
- **CraterFalls.java** — предел падающего песка/гравия: `LIVE_CAP` 48 на район, `TOTAL_CAP` 96 на мир :43-45; `GRACE`
  200 :47. `onEntityJoin` :86 отменяет сущность и зовёт `restingPlace` :160 (только ColoredFallingBlock /
  ConcretePowder). Зоны открывает `BlastArea`.
- **DebrisSpawner.java** — `drone` :57 / `missile` :66 / `vent` :77 / `wreck` :88: `DebrisEntity` с постоянными
  разбросами скорости (шахед ±1.5 по горизонтали, ракета ±2.1). `DebrisEntity` ранит на `min(16, max(2, fall·2 + v·3))`,
  тип DEBRIS (M/entity/DebrisEntity.java:116-121).
- **GroundMaterial.java** — перечисление: цвет, пористость, 9 id обломков :25-44. `sample` :81 — GRAVEL, если чанк не
  готов; `of(state)` по тегам :89-100. Им же пользуется `CraterJob.sampleSoil` (земля/песок/камень → `CraterModel.Soil`).
- **BunkerCover.java** (чистый): `RING` :12, `BREACH_DEPTH` 10 :17, `surface` (нижняя медиана) :25, `depth`/`breaches` :32-39.
- **ExplosionStage.java** — метки времени.

## 4. Части ядерки: что звать напрямую, а что связано с ядеркой

**Можно звать из обычного взрыва**
- `BlastModel` (M/nuclear/model/BlastModel.java): `surfaceOverpressureKpa(R, kg)` :36 = Кинни–Грэхем(R, 1.8·W);
  `kinneyGraham` закрыт :47; `rangeForSurfaceOverpressure` :41, `psi`/`kpa` :53-59, `dynamicPressureKpa` :68,
  `frontSpeed` :73, `windSpeed` :81. **Функции импульса нет**, `positivePhaseSeconds(yieldKt)` :87 берёт только кт —
  импульс и длительность по кг добавить.
- `BlockResponse` (M/nuclear/world/BlockResponse.java), открытая запись: `of(state)` :47 с кэшем; `breaksAt(psi, seed)`
  :61 с разбросом ±15% `JITTER_MIN/MAX` :28. Классы :68-88: воздух/жидкость и прочность < 0 или ≥ 50 → NONE. Теги:
  `nuke_ground` 15 psi GROUND, `nuke_fragile` 0.8, `nuke_plants` 8, листва 2, брёвна 5, `nuke_light` 4, `nuke_masonry` 12.
  Иначе по прочности: ≤0.5 → 1, ≤3 → 5, ≤6 → 12, ≤9 → 25, дальше NONE. Только давление, без импульса. Кэш чистится при
  перезагрузке тегов :52-59.
- `Fragility` (M/nuclear/model/Fragility.java), всё чистое статическое: `probability` :14, `hit(x, m, β, z)` :26 (z в
  ±2.5), `possible` :34, `cdf`/`inverse`.
- `WorldNoise` (M/nuclear/model/WorldNoise.java:27): `new WorldNoise(seed, blocksPerMetre)`, `at(x,z,layer)` :44,
  `at(x,y,z,layer)` :55. Чистый, чтение потокобезопасно.
- `Detonation.throwSpeed(kpa)` (M/nuclear/Detonation.java:168) = min(6, 0.8·√q) блоков/тик, открытый статический, только кПа.
- `Detonation.underOpenSky(level, pos)` :261, открытый статический.

**Связано с ядеркой**
- `BlastFront` (M/nuclear/world/BlastFront.java) — пакетный, им владеет `NuclearWorld` (`NuclearWorld.java:47,208,330-335`).
  `hit` :181-197: 12 psi — смерть, иначе 1.6·psi^1.35, укрытие ×0.3 урона / ×0.2 броска. Пользуется `Detonation`
  (`overpressureKpa(pos)`, `burst`), `NuclearWarhead.sees(level, d, e)` (NuclearWarhead.java:173) и `MobPanic.wave(d, …)`.
  Аппараты: `sweepAircraft` :211-223, сила = clamp(6 + (psi − 3)·1.15, 6, 60); `blast` :230 — ванильный взрыв с
  калькулятором `AircraftOnly` :243-264 (только блоки плотов, без урона сущностям). Для обычного взрыва — вынести без
  `Detonation` (закон урона, проверка укрытия, калькулятор аппаратов).
- `CraterJob` (M/nuclear/world/CraterJob.java): открытый класс, но конструктор требует `Detonation` :48; пользуется
  `NuclearTickets.hold` :95, `d.blocks/metres/seed`, законами `CraterModel` Y^0.3 кт (M/nuclear/model/CraterModel.java:32-58).
  Переносимая логика — профиль и вал `dig` :124-162 и `naturalSurface` :165, но они закрыты. Ведёт `NuclearWorld` по
  колонке за шаг :370-393; ход сохраняется в NuclearEvents.
- `ColumnScar.replace` (M/nuclear/world/ColumnScar.java:60) — **пакетный**: очистка блок-сущности, запись POI, флаги
  без выпадения.
- `Collapse` (M/nuclear/world/Collapse.java:42) — пакетный; `solve(RuinContext.Grid)` :127 требует окна снимков
  (`RuinWindow`), результаты `Blast` по чанкам и `ctx().d`; привязан к машине руин (`RuinPlanner` :218/:262, `RuinContext` :422).
- `Blast.solve(RuinWindow, Detonation, …)` (M/nuclear/world/Blast.java:239) и `Blast.SKY_DEPTH` :54 — пакетные.
- `ScarQueue`, `NuclearWorld` — только ядерка.

## 5. Пробитие бомбы (M/entity/BunkerBusterEntity.java)

- Удар: `energy = config.bunkerEnergy` :137 (по умолчанию 1000, «≈30 блоков камня»; IncomingConfig.java:110-111).
  Тангаж ≥70°, или ≥45° к цели :143-148. Потом `bunkerEntry`.
- `drillTick` :155: `steps = min(4, energy/300 + 1)` блоков за тик.
- `step` :171-201: PASSABLE после ≥4 пройденных — датчик пустоты, взрыватель 3; STOP → взрыватель 8; иначе
  `energy -= RockClass.cost`, `traveled++`, `carve`, `home` (поправка 30%), `wobble`. Подрыв при energy ≤ 0 или traveled ≥ 70.
- `RockClass` :264-286, по тегу, побеждает последнее совпадение:

| Класс | Тег | Цена |
|---|---|---|
| PASSABLE | passable | 0 |
| SOFT | bb_soft (земля, листва, шерсть) | 12 |
| ROCK | bb_rock | 30 |
| DEEP | bb_deep | 40 |
| HARD | bb_hard (бетон) | 110 |
| VERY_HARD | bb_vhard (обсидиан…) | 300 |
| STOP | bb_stop | 0 (стоп) |
| FLUID | bb_fluid | 5 |
| OTHER | — | 25 |

- Глубина — просто сумма цен: нет скорости, массы, S-числа Янга. Теги —
  `mod/src/main/resources/data/incoming/tags/block/bb_*.json`.
- `carve` :223-244: колонна 1×3 и 65% соседей через DRILLABLE; стенки дробятся; KINETIC 100 в 1.8 блока.
- Подрыв `Warheads.bunker` :256-261.

## 6. Настройки и типы урона

- `warheads` (M/IncomingConfig.java:99-111): `drone_power` 12, `missile_power` 20, `rocket_power` 7, `loiter_power` 8,
  `bunker_power` 20 (все 1–60, «TNT = 4»), `bunker_energy`. `world` :114-124: `block_damage`, `shatter_glass`, `fire`,
  `debris_stay`, `collapse`. `nuclear.block_damage` (`nukeBlockDamage`) :182, его же читает BlastFront для аппаратов.
  `farRange` :260. `work_ms_per_tick` 30 :256; `destruction_ms_per_tick` :205. Ключи языка — `assets/incoming/lang/en_us.json`
  и `ru_ru.json`.
- Паспорт: `charge` в кг ТНТ уже есть: DRONE 50 (WeaponSpec.java:332), MISSILE 450 (:348), BUNKER 0 (:361), NUKE 0 (:385),
  ROCKET 6.4 (:398), LOITER 3 (:411); сейчас только для стёкол. Поставщик `power` — 5-е поле записи (:50). МБР делит
  `bunkerPower` (:384).
- Типы урона (M/registry/ModDamageTypes.java:26-35): STRIKE (StagedExplosion), SHOCKWAVE (бункер `hurtAround`), KINETIC
  (вход, сверление), DEBRIS, NUCLEAR_BLAST (+ PLAYER_ через `WITH_OWNER` :38). JSON — `resources/data/incoming/damage_type/`.
- Кто ещё читает радиус ванильного взрыва: M/grid/Blackouts.java:55-60 (`ExplosionEvent.Detonate`, `radius() ≥ MIN_BLAST`
  (2), охват 6 + radius); D/scenario/ScenarioRun.java:668-672 (`ExplosionEvent.Start`, `radius() <= BUNKER_ENTRY_POWER`
  пропускается); mod/src/devtest/java/ua/zentix/incoming/stress/StressDirector.java:1122 (взрывы через `ExplosionEvent.Start`).

## 7. Тесты

**Юнит**
- T/strike/WeaponSpecTest.java:63-76 `chargeBreaksGlassAsDescribed`: charge > 0 ⇔ blast ≠ NONE; стёкла: шахед 70±7,
  ракета 150±15, «Град» 37±4, «Ланцет» 29±3.
- T/nuclear/model/BlastModelTest.java: `surfaceChargeIsNuclearEquivalent` :78, `surfaceRangeForGlass` :89 (0.8 psi на
  дальности стёкол шахеда для 50 кг), плюс давление, фронт, скоростной напор, ветер, фаза.
- Ещё: FragilityTest, WorldNoiseTest, CraterModelTest, T/warhead/BunkerCoverTest.java (4), T/client/fx/ExplosionsTest.java
  (число доп. подрывов). ShatterTest и юнит-теста StagedExplosion нет.

**GameTest D/WorkGameTests.java** (все по силе): `impactsStayWithinBudget` :157, `impactWithEmptyQueueRunsSameTick` :191,
`impactUnitsKeepOrder` :223, `stopFinishesQueue` :278, `portionSkipsChangedBlock` :307, `portionTakesReshapedNeighbours`
:337, `raysMatchVanilla` :377 (`testRays` силы 6: ваниль против своего цикла, слабый калькулятор бункера, лучи 1352/128,
равенство множеств Detonate), `craftBlocksGoRightAfterDamage` :453, `craftInRayReachGoesVanilla` :510 (reach = 2p + 1),
`areaExplosionsPickTogether` :546 (3 взрыва бункера в одном тике), `blockPortionsByTime` :588, `poiInBlastStaysConsistent`
:625, `blastDropsDecay` :701 (куб силы 16, выпадение 1/power), `vanillaPathDamagesDetonateList` :741,
`craftBlockLeftByDetonateStays` :770, `craftHullShieldsLikeVanilla` :830, `unitSkipsUnreadyChunk` :907, `lanesShareTick`
:958. Несколько проверяют «все блоки из Detonate сняты».

**GameTest D/StrikeGameTests.java**: `assertCrater` :167-177 (≥30 воздуха в коробке 7×3×7) — у ~15 тестов полёта
(:189, 223, 257, 508, 549, …). Про взрыв: `blastWaitsForUnreadyChunks` :2305 (`reach(4)`), `deferredBlastKeepsItsProjectile`
:2337, `bunkerBusterDrillsAndDetonatesUnderground` :2497, `craterFallsStayUnderCap` :2521 (1384 живых без пределов),
`craterFallsShareWorldCap` :2581, `shatterBreaksGlassAndLeavesInBox` :3737, `shockwaveBreaksGlassByPressure` :3775,
`shockwaveBreaksGlassCubeInPortions` :3822, `debrisLandsAsBlocks` :3902.

**Другие**: D/EdgeGuardGameTests.java (`igniteGroundAtUnreadyChunkLoadsNothing` :57, `groundSampleAtUnreadyChunkLoadsNothing`
:68, `blastAtUnreadyChunkLoadsNothing` :82, `blastAtLoadingChunkWaitsForNothing` :93, `shatterKeepsGlassNextToUnreadyChunk`
:127); D/DhGameTests.java:96 `blastReachesDhTwice` (`testBlast` силы 6); D/GridGameTests.java:400-403 (ванильный
`level.explode` против порога радиуса). Записи сценариев кончаются на взрыве через `ExplosionEvent.Start`.

**Что переписать, если уйдёт ванильная сила**: всё, что зовёт `testRays`/`testBlast` с силой или `reach(power)`:
`raysMatchVanilla`, `craftInRayReachGoesVanilla`, `blastDropsDecay` (выпадение 1/power), `blockPortionsByTime`,
`portionTakesReshapedNeighbours`, `craftHullShieldsLikeVanilla`, `vanillaPathDamagesDetonateList`,
`craftBlockLeftByDetonateStays`, `blastReachesDhTwice`, `blastWaitsForUnreadyChunks`; `areaExplosionsPickTogether` и
`craterFallsStayUnderCap` (опираются на три взрыва бункера); всё, что читает `ExplosionEvent`/`Explosion.radius()`
(Blackouts, ScenarioRun, StressDirector, равенство множеств в WorkGameTests). Тесты стёкол и WeaponSpec остаются, пока
есть `charge`.

## 8. Цена на сервере

- `ImpactCost.Kind` (M/strike/ImpactCost.java:19-31): RAYS, BLOCKS, GLASS, DEBRIS, GROUND, HITS. Тик мира дольше
  `SLOW_NANOS` (50 мс) — строка «Impacts (…)» не чаще раза в 100 тиков :45-47, :123-143; очередь ждёт дольше 20 тиков —
  строка :49/:118-121; «Impact series summary» после 100 тихих тиков :61, :97-112.
- Бюджет: `work_ms_per_tick` 30; полосе IMPACT — весь или 2/3, когда работают ядерка или блэкаут
  (M/work/WorkScheduler.java:57/:100); `BUSY_MS` = 2 мс на полосу у сервера без свободного времени :54; оценки по видам
  единиц — `WorkClock.canStart(kind)` (M/nuclear/world/WorkClock.java:124); одна единица за тик всегда;
  `UnitQueue.GIVE_UP_TICKS` 6000.
- Замеры из комментариев: два ванильных взрыва силы 12–20 в городе хоста — 559 мс в одном тике (StagedExplosion:59,
  warhead.md); одна единица «лучи и урон» — до 150 мс (StagedExplosion:60, ExplosionStage:4); ракета у дирижабля,
  ванильный взрыв — 181.6 мс (StagedExplosion:367); порция 16 блоков — до 65 мс у хоста (BlastArea:149); одно стекло с
  обновлением соседей — ~10 мкс (Shatter:57); доп. подрыв у края загрузки остановил сервер на 1.2 с (Warheads:126);
  ядерная волна по всем мобам в одном тике — 84 мс (BlastFront:47); воронка B-2 без пределов — 151+ падающих сущностей,
  1384 в тесте (CraterFalls / StrikeGameTests); выпадения ТНТ без убывания после залпов по 30 — около двух третей потока
  сервера (StagedExplosion:330); воронка бункера 5.3–8.6 тыс. блоков в CI без синхронизации соседей
  (`areaExplosionsPickTogether`); лучи до 57 мс на единицу в CI (`unitSkipsUnreadyChunk`); строка «Strike summary»
  считает шаги лучей в воздухе, потому что миксин Sable ищет аппараты на каждом шаге в воздухе.
