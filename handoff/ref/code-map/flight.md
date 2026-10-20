# Карта кода: полёт, пуск, вне мира (снимок 18.10.2026, main после 3.3.0)

Номера строк — на момент снимка; перед работой сверить. Префиксы: M = mod/src/main/java/ua/zentix/incoming,
T = mod/src/test/java/ua/zentix/incoming, D = mod/src/devtest/java/ua/zentix/incoming.

Числа паспорта — M/strike/WeaponSpec.java: DRONE :330-336, MISSILE :346-352, BUNKER :359-367, NUKE :383-388,
ROCKET :396-401, LOITER :409-415.

## 1. Управляемый полёт

**Общее**
- Закон разгона `LaunchProfile.boost` (WeaponSpec:189-192): ускорение растёт как `boostAccel*min(1,(t+1)/6)`; после
  `railTicks` нос ведёт `holdPitch(boostEndPitch, 0.06, 1.2, 0.12)`. Зовут `StrikeProjectile.launchTick`
  (M/entity/StrikeProjectile.java:783-806) и `LaunchSite.clearAhead` (M/strike/LaunchSite.java:327-337). Пик скорости
  разгона: шахед ≈2.7, ракета ≈3.45, «Ланцет» ≈1.33 блока/тик.
- `AltitudeHold` (M/guidance/AltitudeHold.java:9-30): MAX_CLIMB 15°, MAX_DESCENT 12°, фильтр 1/5, 1.2° на блок ошибки.
- `FlightController` (M/guidance/FlightController.java): `arcPitch` усиление 3.2 (:70-73); `bankAngle` берёт g=0.08
  (значение Minecraft), а не 0.0245 (:104-108).
- `Autopilot` (M/guidance/Autopilot.java): HOLD_COURSE 8 (:25), CORRIDOR_HALF_WIDTH 1.5, TRACK_TICKS 64, TRACK_STEP 4,
  CLIMB_SHARE 0.5, REACTION_TICKS 30 (мёртвый набор = 30×скорость), REFRESH_TICKS 4 (:82-128).
- Запас поворота `TURN_MARGIN` 1.2 (WeaponSpec:294); `turnRadius = v/ω` (:300).
- `Mission`: PATH_MARGIN 1.5, RESERVE_TICKS 600, LOST_RESERVE_TICKS 200 (M/guidance/Mission.java:18-22).
- Взведение `ARM_DISTANCE` 96 (M/entity/flight/ProximityFuse.java:18).
- Длина маршрута `pathLength = cruiseSpeed × flight_seconds × 20` (M/strike/StrikeService.java:206-208); вход
  `max(finalLeg, dist+150)` (:214-219); старт издалека на `point.y + cruiseHeight` (:522).
- Время полёта в настройках: шахед 50 с, ракета 30 с, B-2 40 с (M/IncomingConfig.java:156-161).
- `etaTicks = path/max(cruise,speed) + тики пуска` (StrikeProjectile:493-497).

**Шахед** (M/entity/DroneEntity.java, M/guidance/DroneAutopilot.java)
- Паспорт: крейсер 2.1, пике 3.0, поворот 3, поворот в наборе 1.6, cruiseHeight 45, reliefLookahead 256,
  LaunchProfile(8,38,0.075,8,−9).
- Константы в коде, не в паспорте: ABOVE_TARGET 30, ABOVE_RELIEF 18, DIVE_PITCH 18°, DIVE_RATE 4.0 / DIVE_ACCEL 0.25,
  DIVE_MARGIN 1.5, LINE_RECHECK 4, PATH_AHEAD 90 (:15-40); старт в воздухе — цель+30 и рельеф+20 (:78-83); набор
  скорости CLIMB 0.04, CRUISE 0.05, пике +0.04/тик (:105, 122, 128); удержание высоты (0.10, 1.0, 0.12) и (0.12, 1.2,
  0.15), CLIMB→CRUISE после 60 тиков в 6 блоках (:107-125); поворот `Turn(0.08,…,0.12,0.12)` и `(0.15,…,0.3,0.3)` (:61-62).

**Ракета** (M/entity/CruiseMissileEntity.java, M/guidance/MissileAutopilot.java)
- Паспорт: крейсер 4, пике 5, поворот 3/2, cruiseHeight 12, visibleLeg 256, Attack(64,160,185),
  LaunchProfile(6,40,0.09,8,−14).
- В коде: горка до DIVE_PITCH 24° или POP_UP_HEIGHT 32; CLIMB_ABOVE_RELIEF 25 (:15-19); турбина +0.09/тик (:77, 95);
  CLIMB→CRUISE после 40 тиков (:80); удержание высоты (0.25,4,0.6) и (0.30,8,1.8) (:79, 97); горка
  `holdPitch(−20, 0.30, 8, 1.8)` (:99); атака `arcPitch` 16 / 3.5, +0.1/тик до скорости пике (:101-102); поворот
  (:31-32); окно воздушного ядерного подрыва `speed*2+12` (сущность :81); `LEGACY_CRUISE_SPEED` 11.5 для старых
  сохранений (сущность :39, :106-113).

**«Ланцет»** (M/entity/LoiterEntity.java)
- Паспорт: крейсер 1.6, пике 4, поворот 3/2, cruiseHeight 45, lookahead 35, LaunchProfile(4,10,0.16,4,−8).
- Всё управление — в сущности: LOITER_RADIUS 40, STRIKE_NOW 140, DIVE_TICKS 25, MISSED_BY 20 (:35-41); круг ×0.8–1.2
  (:75); высота от пусковой rail+30 (:93); пол над рельефом +15 (:181); CLIMB: 0.08, (0.12,1.4,0.15), курс
  (0.08,·,0.15), 40 тиков (:183-188); CRUISE/LOITER: 0.05, (0.12,1.2,0.15), курс (0.15,·,0.3) (:190-206); пике с
  тангажа ≥30 (перенацеливание) или ≥40 (с круга) (:198, 210); скорость пике +0.1/тик (:220).
- `Orbit` (M/guidance/Orbit.java:19-25): CONVERGENCE 2°, GAIN 0.1, MAX_RATE 4, MAX_ACCEL 0.4 (подобрано под 1.6
  блока/тик, поворот паспорта не читает), захват 4 блока / 15°.
- `Dive` (M/guidance/Dive.java:12-14): тангаж 6 / 0.6, курс 0.4 / 8 / 1.0, MAX_LEAD_TICKS 60.

## 2. «Град» (M/entity/RocketEntity.java, M/guidance/Ballistics.java)

- Дискретная парабола `p = s + n·v0 − g·n(n−1)/2`, g = 9.8/400 (Ballistics:11, 37-39). Без тяги и без сопротивления.
  `BURN_TICKS` 40 (:35, 168) только переключает фазу BOOST→CRUISE для картинки.
- Путь решается один раз при выходе из трубы (:148-154, 265-267); заново — от текущего места, если ракету сдвинули
  (:164-166) или осела высота падения (:192-200).
- Угол постоянный: возвышение пакета 50° (M/entity/LauncherRack.java:21, `LauncherEntity.elevation` :110-112).
  `Ballistics.ticksFor` ищет N при этом угле; N < MIN_FLIGHT 50 (:39) — угол +5° до 80° (Ballistics:19-29).
  `launchVelocity = Δ/N + g(N−1)/2` (:32-34): скорость у среза — следствие дальности, а не свойство. Пример 600 блоков:
  N≈243 тика (12 с), |v0|≈3.9 блока/тик (~77 м/с), апогей ≈180 блоков.
- Рассеивание: `StrikeService.scatter` (M/strike/StrikeService.java:586-589) — гауссов сдвиг один раз при пуске,
  σ = max(1, 1% дальности) (WeaponSpec:160-163; `dispersion` 0.01 :398); ракета падает точно в эту точку.
- `Route.standoff` 600 (WeaponSpec:398, javadoc :393) — только без пусковой и без места из приказа
  (StrikeService:547-551); старт на высоте `point.y`.
- `LauncherRack.ROCKET`: busyTicks 1200 — труба перезаряжается минуту (LauncherRack:20-21); шаг 8 тиков, minReady 10,
  40 мест (10×4, LauncherEntity:119).
- `Salvo(8,12,12,15)` (WeaponSpec:396): 8–12 тиков между пусками, 12 по умолчанию с пульта, разброс 15; применяется в
  M/strike/SalvoData.java:244; места — StrikeService:455-459.
- Прочее: крейсер 4 паспорта нужен только для перелёта `Mission` (OVERSHOOT_TICKS 200, :50, 282); растяжка вне мира
  HOLD_OFF 64, STRETCH_TICKS 100, RATE_SLEW 0.05 (:40-48, 215-224); `preloadDistance` = ∞ (:232-234); проверка сектора
  `LaunchSite` повторяет ту же дугу (M/strike/LaunchSite.java:293-299).

## 3. B-2 и бомба

**BomberEntity** (M/entity/BomberEntity.java)
- Высота `ALTITUDE = cruiseHeight` 170 (паспорт :362); старт на `surface.y + 170` (:122); дистанция старта
  `12 × bomber_flight_time × 20 + 85` (StrikeService:609-610), начало вне мира. Скорость постоянная 12 (:125).
- Эшелон по настоящей поверхности сброса (:139-140); тангаж ±6°, 0.15° на блок ошибки (:42-45, 163); курс только
  дальше RELEASE_DISTANCE+40 (:165-170).
- Сброс: `BombDrop.releaseNow`, lineRatio 85/170 (:144); ETA +20 тиков на падение (:106).
- Уход: 400 тиков, `holdPitch −6` и доворот 20° на 60 тиков (:47, 157-161).

**Падение** (M/guidance/BombDrop.java:13-30): DROP_SPEED 6, DROP_PITCH 10°, MAX_SPEED 12.5, ACCEL 0.3/тик (постоянный
разгон, не сила тяжести), DROP_BELOW 4, REACH_PAD 5.3, NOSE 3.1, EDGE_REACH 30, MIN_DIVE 10, FALL_PITCH 60, MAX_TICKS
200. Наведение `steer` (:42-53): `arcPitch` 7/0.8, курс 0.15/3/0.3. `miss()` повторяет те же шаги (:82-99).

**Пробитие** — см. code-map/warhead.md §5 (энергия из настроек, скорость удара не учитывается).

**Кинетический урон**: в канале 100 KINETIC в 1.8 блока (BunkerBusterEntity:241-243); на входе
(`Warheads.bunkerEntry`, M/warhead/Warheads.java:87-96) взрыв силы 4 и 60 / 22 / 7 на ≤4.5 / ≤9 / ≤14 блоках.

## 4. МБР (M/entity/IcbmEntity.java)

- `prepare`: стол+9, тангаж −89° (:56-60). Скорость `min(25, Ascent.step)` (:68); `Ascent` (WeaponSpec:210-228) =
  T/(m₀−ṁt) − g без сопротивления, м/с² ÷ 400. Фаза BOOST с 40 тиков — константа (:69). После `pitchOverTick` 60 —
  `holdPitch(−85, 0.02, 0.6, 0.05)` (:71). Убирается выше высоты мира + 320 (:75); вне мира не летает,
  `leavesTickingChunks` её снимает (:39-41, :79).
- Таймер: `NuclearStrikes.launchFrom` ставит стол в 30 блоках за игроком (M/nuclear/NuclearStrikes.java:86-88);
  `schedule` — подрыв через flightTicks (:98-104). Сущность только для вида и от таймера не зависит. flightTicks —
  настройка `flight_time`, 1800 (IncomingConfig:200-201); не оператор — не меньше MIN_WARNING 1800
  (M/strike/NuclearKeys.java:49, 268-269).
- Полоса входа `NukeRenderer.drawReentry` (M/client/nuclear/NukeRenderer.java:176-203): последние REENTRY_TICKS = 60
  (M/client/nuclear/NukeSounds.java:29) по прямой 60 000×scale. Это 60 км за 3 с = 20 км/с, а комментарий говорит
  ≈7 км/с — код и комментарий расходятся. Направление (`reentryDirection` :206-211): flat·0.34 + down·0.94, ~70° вниз,
  со стороны пуска.

## 5. Полёт вне мира: что завязано на нынешние скорости

- От скорости: чанк впереди `ChunkHold` на `max(16, 3×speed)`, держатся только свой и он (M/entity/flight/ChunkHold.java:37-39)
  — при 35 блоках/тик это 105 блоков вперёд с дырами; `VirtualFlights.clearAhead` требует `max(16, 3×speed)` готовых
  тикающих чанков до входа (M/strike/VirtualFlights.java:143-146) — при 35 блоках/тик больше, чем тикающий район ±40;
  `materialize`: просмотр рельефа `max(80, 5×speed)` (StrikeProjectile:641); окно ожидания `advanceVirtual`
  `speed+reachPad+48` (:1079), прибытие `speed+reachPad` (:1084).
- Постоянные: PRELOAD_TICKS 300 → `preloadDistance = max(400, 300×speed)` (:111, 738-740); AREA_TICK_LEAD 160 тиков
  (:116) по `etaTicks`; AREA_WAIT_LIMIT 1200 (:107); BELOW_AIM 16 (:109). `FlightTickets` (M/strike/FlightTickets.java:35-49):
  DISTANCE 4 (±40 блоков тикают), LOITER_DISTANCE 6, APPROACH_DISTANCE 3, APPROACH_STEP 32, APPROACH_LIMIT 32;
  `approach()` длиной `visibleLeg` 256 (:61-78), `watched` = обзор + полоса (:84-91) — 256 блоков это ~64 тика при 4
  блоках/тик, но ~20 тиков при 12.5. Javadoc `ChunkTickets` считает, что ракета проходит 0.7 чанка за тик
  (M/strike/ChunkTickets.java:11), уровень 31. Растяжка «Града» вне мира рассчитана на загрузку района 0.7–4.6 с
  (RocketEntity:226-234).
- Клиент и ЗРК: пакеты FarFlights раз в 2 / 4 тика (M/net/S2C.java:209) — при 34.5 блока/тик точки через 69–138 блоков;
  `ArrivalQueue` JUMP 64 блока (сумма осей) (M/entity/ArrivalQueue.java:22-23) — догоняющие пакеты на большой скорости
  сочтутся телепортом; дальности отслеживания сущностей 20–32 чанка (M/registry/ModEntities.java:28-41); зенитная
  ракета до 14 блоков/тик (M/defense/InterceptorSpec.java:32).

## 6. Столкновение за тик

- Блоки: `advance` ведёт нос от pos+dir·nose/2 до pos+dir·(speed+nose) одним `Level.clip` (StrikeProjectile:983-985,
  1013-1026) — годится для любого шага; `Terrain.readyUntil` обрывает его на первом неготовом чанке (:1016).
- Сущности: `ProximityFuse.victim` радиус speed/8+1.8 (6.2 блока при 35 блоках/тик), рамка поиска растёт со скоростью
  (ProximityFuse:36-37).
- Цель: попадание при расстоянии ≤ speed+reachPad (:999); БЧ рвётся в точке прицела, а не там, где снаряд, — при 35
  блоках/тик срабатывание до ~40 блоков раньше.
- Дыры на большой скорости: `leavesTickingChunks` проверяет только конец шага (:1148-1153), пройденные между ними
  чанки (2+ за тик при 35 блоках/тик) не проверяются и не держатся; `clearAlong` (:1203-1212) и `Autopilot`
  REACTION_TICKS×speed (375 блоков при 12.5) длиннее `reliefLookahead` 256.

## 7. Ветер

Общей атмосферы и погоды нет; на сервере у полёта нет ни ветра, ни плотности воздуха.
- Клиент: постоянные `Fx.WIND_X` 0.0022, `WIND_Z` 0.0011 (M/client/fx/particle/Fx.java:20); их читают
  M/client/fx/particle/FxParticle.java:133-134, M/client/far/FarBlasts.java:567, M/client/far/FarFlightView.java:364;
  снос следа — M/client/ClientWeaponSpec.java:196-199.
- Ядерка: у каждого подрыва случайное направление и 5–15 м/с из `level.random` (M/nuclear/NuclearWarhead.java:72-73),
  в `Detonation.windDir/windSpeed` (M/nuclear/Detonation.java:32-33, 73-74); читают FalloutModel
  (M/nuclear/model/FalloutModel.java:24-106), снос PallField (M/client/nuclear/PallField.java:508-527), NukeAsh
  (:41-42), FireStorms (:141-142), MushroomField (:280-295). `BlastModel.windSpeed` — ветер волны, не погода.
- Дождь и гроза меняют только видимость (M/client/far/Sight.java:48-49) и звук.

## 8. Тесты, которые держат числа полёта

- **WeaponSpecTest** (T/strike/WeaponSpecTest.java): радиусы поворота 40.1 / 76.4 / 687.5 (:24-29); упреждение сирены
  (:51-57), дистанция сирены на крейсере ≥ finalLeg (:110-115); стёкла (:64-72); рассеивание σ(600)=6 (:80-84); промахи
  (:91-106); reattackMin < круга поворота (:122-129); геометрия горки: атака > радиуса поворота на скорости пике ×1.2,
  ≤ finalLeg, ≤ visibleLeg (:137-144); finalLeg ≥ 2× радиуса (:148-153); захват точки маршрута 0.95r–2r (:161-169);
  место в пакете держится через зажигание + тики рельса (:173-181); порядок оружия (:185); боеприпасы (:200).
- Другие юнит: T/strike/IcbmBoostTest.java (отрыв 1.2–1.8 g; 10 с по уравнению ракеты; 8.2 блока/тик на 10 с и ниже
  25); T/guidance/BallisticsTest.java (500 блоков под 50° за 180–240 тиков); T/guidance/BombDropTest.java (сброс на
  линии 85 блоков с высоты 170 на 12 блоках/тик; сброс с любой высоты 20–490); T/guidance/AutopilotPropertiesTest.java
  (10 000 прогонов, MAX_TURN 720, по крейсерской скорости); DroneDiveTest, DiveTest, OrbitTest, MissionTest; клиентские
  FarLookTest (B-2 52), FarModelsTest, EngineSoundTest; T/defense/InterceptorFlightTest.
- **Эталон сценариев**: mod/src/devtest/resources/scenario-baseline.json, 121 запись, все с зерном 0 (bomber 4,
  drone_far 18, drone_rail 18, icbm 1, loiter_far 18, loiter_rail 18, missile_far 18, missile_rail 18, rocket_far 4,
  rocket_tube 4). В записи — как кончился полёт, тик, x/y/z и хеш мест по тикам с точностью 1/1000
  (D/gametest/scenario/FlightTrace.java); сравнение — D/gametest/scenario/Baseline.java; параметры —
  D/gametest/scenario/Scenario.java:196-247; проверки свойств в D/gametest/scenario/ScenarioRun.java (MAX_TURN 720,
  DURATION_SLACK 200, IMPACT_LIMIT 600, ожидаемая длительность из крейсера × время :83-93, 343, 441, 718-728); задачи
  Gradle mod/build.gradle:154-173 (`runScenarioSweep` 16 зёрен; `runScenarioRecord` пишет JSON заново). **Любая смена
  скорости ломает все 121 хеш** — эталон записывается заново и сдвиг объясняется в PR.
- **GameTest** (D/gametest/StrikeGameTests.java): droneFliesAndExplodes :180, droneDivesOverWallOnDiveLine :199,
  missileStrikesAfterPopUp :489; launchSector* :604-782 (закон разгона, дуга «Града»); icbmStaysInTheSkyNineSeconds
  :877 (в небе 160–220 тиков); missileFromLauncherKeepsSpeedAndEta :1405, targetAreaTicksOnlyNearArrival :1477,
  missileEntersWorldAtApproachEdge :1555, missileApproachOnlyWatchedSharedAndCapped :1595; rocketsRippleFromLauncher
  :1673 (апогей >40, шаг пусков), rocketFromAfar :1712; loiter* :1830-1958; bunkerBusterFallsWhereBombDropPredicts
  :2386, bomberReleasePointsHitInWorld :2409, bomber* :2657-2830; virtual* :2917-3285, rocketStretch* :3346-3482,
  legacy* :1321-1364. D/gametest/LifecycleGameTests.java:246, 340 (дальность = 2×крейсер).

## 9. Размеры моделей

- «Ланцет» ×1.5 зашит в геометрию: tools/gen_models.py:928-933 (`loiter()`: профиль от +1.25 до −1.24 м, т. е. 2.5 м
  вместо настоящих 1.65; и :31). Масштаба в игре нет: `WeaponModels.loiter` масштабирует только раскрытие крыльев
  (M/client/render/WeaponModels.java:267-282).
- Масштаб записи OBJ в gen_models.py: шахед 0.5 (:499), ракета 0.5 (:564), бомба 2/3 (:763), прочие 1.0.
- `FarLook` размер / площадь (M/client/ClientWeaponSpec.java:251-268): DRONE 3.6/3.6, MISSILE 6.2/3.1, BOMBER 52/250,
  BOMB 6.2/3.9, ICBM 18.3/24, ROCKET 2.9/0.28, LOITER 2.5/1.4.
- `noseLength` паспорта: 1.83 / 2.96 / 8.5 / 3.1 / 9 / 1.45 / 1.3 (шахед, ракета, B-2, бомба, МБР, «Град», «Ланцет»).
- Рамки столкновений: M/registry/ModEntities.java:28-41.
