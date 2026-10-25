# Конвейер разработки: стандарт сообщества вместо своего

Исследование и предложение на 23.10.2026 (сессия DL), переделано по решению Артёма 23.10 07:40–07:41.
- **07:40.** Конвейер — стандартный конвейер сообщества (NeoForge, Fabric, проверенные инструменты). Отличия — только
  временные разовые шаги вроде сравнения с 3.4.1.
- **07:41.** Своё можно только там, где есть настоящая нужда, которую стандарт не покрывает. Нельзя, если своё обходит
  архитектурную ошибку, которую мышление от базовых принципов убрало бы в корне.

Поэтому у каждого нашего инструмента и правила здесь один вердикт:
- **(а)** заменить стандартом сообщества;
- **(б)** следствие корневой ошибки: чинить корень, инструмент убрать;
- **(в)** настоящая нужда, стандарта нет: оставить, с причиной.

Источники — в конце; где источника нет, стоит «оценка». Ретроспектива с 01.10 — `docs/dev-process.md`.

## Коротко

- **Как работают моддеры.**
  - Правят в запущенной dev-игре: горячая замена кода на JetBrains Runtime, F3+T для ресурсов, R у Iris для
    шейдеров. Правка видна через секунды.
  - CI проверяет сборку и серверные GameTest, 2–5 мин.
  - Картинку автор смотрит сам, пока делает. Сравнения кадров в CI нет ни у одного большого мода рендера (Sodium,
    Iris, Distant Horizons, Veil, Flywheel, Flashback).
  - Ошибки ловят беты и игроки.
- **У нас 24 своих инструмента и правила** (п. 3): 10 заменяет готовое (а), 8 — следствия корневых ошибок (б),
  8 — настоящая нужда (в); три строки смешанные. Главные корни — два.
  - **Первая ошибка:** до четырёх сессий с картинкой сразу на одной видеокарте. Отсюда очередь, замки, песочница,
    которая гаснет от чужих задач, и 15 ч ожидания за ночь 22–23.10.
  - **Вторая ошибка:** картинку смотрели после работы, а не во время. Отсюда съёмка всей сборки под Iris на каждый PR:
    130 запусков за ночь, 9,9 ч видеокарты.
- **Предложение.** Перейти на стандарт и убрать своё, где стандарт есть.
  - Картинку — одна сессия за раз на видеокарте.
  - Кадр смотреть в dev-клиенте во время работы.
  - Сравнение со всей сборкой — только разовое, с 3.4.1, пока не достигнут его уровень.
  - Своё остаётся там, где у NeoForge стандарта нет: кадр в заданный миг для Claude, клиент без окна на чужом
    рабочем столе, защита игры Артёма.

## 1. Звенья: как у моддеров, как у нас, что берём

| Звено | Как у опытных моддеров (конец 2026) | Как у нас | Что берём |
|---|---|---|---|
| Проект | MDK NeoForge 1.21.1: ModDevGradle 2.0.148, Parchment 2024.11.17. Create, Mekanism, AE2, JEI, Sodium, Iris, Veil — на MDG 2.0.14x. Мультилоадер (MultiLoader-Template, Architectury, Stonecutter) — только кому нужны несколько загрузчиков или версий | MDG 2.0.147, Parchment 2024.11.17, одна версия | так и оставить; мультилоадер не нужен (решение 22.10: остаёмся на NeoForge 1.21.1) |
| Скорость сборки | MDK: `daemon`, `parallel`, `caching`, `configuration-cache` — все `true`; MDG поддерживает кэш настройки; Mekanism, AE2, JEI его включили; Create — без демона | тёплый демон для `build` (#384): компиляция 3–6 с; кэш сборки общий; кэш настройки выключен | настройки MDK как есть: демон и кэш настройки включены (проверка — часть B) |
| Запуск и отладка | запуски из IDE, отладчик; горячая замена тел методов; JetBrains Runtime с `-XX:+AllowEnhancedClassRedefinition` — и поля, и методы (Create ставит флаг на всех запусках, Mekanism — когда JVM от JetBrains); замена миксинов ненадёжна (Mixin #592, #408) — перезапуск | песочница: свой агент замены, JBR 21 (`sandbox.sh jbr`), `restart` ~20 с | JBR + HotswapAgent (`autoHotswap`) вместо своего агента; отладчик и IDE Claude не нужны: вместо точек останова — строки `SCENARIO` и `overlay` |
| Перезагрузка в игре | F3+T — ресурсы, `/reload` — данные, R у Iris — шейдерпак; Veil следит за файлами и пересобирает шейдеры сам | `swap` делает F3+T, шейдеры слоя — наши ресурсы | так и есть; ещё — настройки: NeoForge перечитывает изменённый TOML и шлёт `ModConfigEvent.Reloading` без перезапуска |
| Подбор чисел вида | ползунки в ImGui (Veil F6, ImGuiMC), экраны настроек | число — в коде или в аргументе `/fx`, кадр — `shot` | ползунки не берём: Claude их не двигает, ему нужен кадр в заданный миг |
| Автотесты | JUnit; JUnit с игрой (`unitTest` MDG + `testframework`); серверные GameTest в CI — Create, JEI, AE2, Mekanism; клиентские тесты со снимком и сравнением с шаблоном — только Fabric (с 1.21.5); у NeoForge их нет (#3624, открыт 10.10.2026); `mc-runtime-test` — запуск клиента в CI без снимков | JUnit-законы, GameTest шестью частями, сценарии полёта, клиент без окна со снимками | стандарт уже есть; клиентских тестов со снимком у NeoForge нет — кадр берём из dev-клиента (п. 3) |
| Проверка картинки | глазами в своей игре; RenderDoc и Nsight — разбирать кадр (на Linux — `renderdoccmd`; RenderNurse NeoForge требует Java 25); вне Minecraft: Bevy снимает кадры примеров в CI, NVIDIA FLIP считает разницу кадров | шлагбаум `look_gate` со всей сборкой под Iris на каждый видимый PR, сравнение глазами (кадры шумят на 10–40 % пикселей) | как у всех: автор смотрит кадр во время работы в dev-клиенте, кадры — в PR; полная сборка — только разовое сравнение с 3.4.1 |
| CI | GitHub Actions + `gradle/actions/setup-gradle` (v6.4.0; кэш пишет только основная ветка). Время: Create 2–4 мин, Sodium 1,5–3, AE2 3–4, Mekanism 2–3, JEI 5–14 | 4,9 мин медиана, p90 7,1; GameTest шестью частями; `setup-gradle@v5` | оставить; v6 — мелкая правка |
| Выпуск | `mod-publish-plugin` 2.2.1 (Create, Sodium), `mc-publish` 3.3.2, Minotaur; журнал изменений — Keep a Changelog, git-cliff | `mc-publish` 3.3.1, `docs/releases/<версия>.md`, `release=true`, PR сборки | оставить, работает за минуты |
| Беты и отзывы | Sodium: 39 бет и 11 альф на Modrinth, Distant Horizons — 191 бета, Iris — 12; ночные сборки из CI; формы задач с логом на mclo.gs; мод Crash Assistant | друзья получают только выпуски через сборку; `logscan.py` после игры | бета у друзей — решение Артёма (п. 6) |
| ИИ-инструменты | HeadlessMC MCP (клиент из командной строки; экран — JSON без картинки), minecraft-mod-mcp, mcpfabric — молодые проекты; у NeoForge и Fabric про агентов ничего | песочница: команды `/fx`, кадр и WAV файлом | не берём: у нас то же, и кадр точнее |

Вывод по таблице: у моддеров нет волшебного инструмента, которого нет у нас. Разница — в том, **где** они проверяют
картинку (в живой игре автора) и **как часто** гоняют тяжёлое (CI — только сборка и тесты, картинка — бетой).

## 2. Наши потери 22–23.10 (числа из данных)

Данные:
- PR #379–#406 (`gh`) и 172 запуска CI `build` с 22.10 14:00 UTC;
- журнал задач ноутбука: 146 задач с 22.10 17:51 до 23.10 07:28 UTC (раньше журнал пуст — была перезагрузка);
- транскрипты сессий X, F4, N1–N4, LFX, F5, VXG, LNB, H, PK и итоги `mod/run/incoming-sessions/*.json`.

`sessions.log` нет: ночные сессии шли старым `mod/run/incoming-sessions/go.sh`, а не `tools/sessions/go.sh`
(п. 5.1 `dev-process.md` не внедрён).

### 2.1. Ночь 22.10 19:51 – 23.10 09:28 (13,6 ч, местное время)

- **Сколько сессий.** С картинкой за ночь шли одиннадцать: F4, VXC4, VXG, N1, N4, LFX, F5, N2, к утру — N3, LNB и PK;
  одновременно — до четырёх (по транскриптам: N1, N4, LFX и VXG в 20:30 UTC, N4, LFX, F5 и N2 в 22:05).
- **Видеокарта.** 130 запусков клиента со всей сборкой; видеокарта занята 9,9 ч из 13,6 (73 %).
- **Очередь.** Задачи ждали её 15,0 ч в сумме: N4 — 186 мин, LFX — 179, F5 — 162, N2 — 140. Время работы и ожидания
  разделено по замку очереди (тяжёлые задачи идут по одной), это расчёт по журналу, а не прямой замер.
- **Эталон.** `main` и 3.4.1 сняли 43 раза — 2,65 ч видеокарты из 9,9. Одни и те же сцены ядерки на `main` сняли N1,
  N2, N3 и N4, каждый для себя; N2 и LFX сняли `main` по три раза.
- **Круги своей ветки.** LFX снял свою ветку со всей сборкой 11 раз (от 4 до 74 мин каждый), F5 — 7 раз.
- **Песочница.** Её пробовали N1, N2 (дважды) и F5. Она прожила 5 с и 16 с, а у N2 не стартовала: её гасит любая
  задача на видеокарте (`tools/sandbox.sh`, `run_client`). N2: «песочницу для крупного плана шара запустить не
  удалось: очередь видеокарты всё время была занята другими сессиями». F5: «отдельная песочница не пригодилась».
- **Ожидание в сессиях.** В `sleep` и опросах: N2 — 101 мин из 214, N1 — 75 из 108, LFX — 72 из 353 (и ещё 163 мин в
  задачах ноутбука).

### 2.2. N3 (#404): код за 18 минут, проверка — 2,5 часа

Сессия шла 04:36–07:24 UTC, 2,8 ч (в брифе было «~6 ч»; по транскрипту и журналу — 2,8).
- **Код.** Код и черновик PR готовы за 18 мин: PR открыт в 04:54.
- **Проверка.** Остальные 2,5 ч — 39 запусков сцен со всей сборкой под Iris: 4 круга ветки и 2 — `main`. Это 100 мин
  видеокарты и 34 мин очереди.
- **Длина сцен.** Сцена `nuke` — 342 с на запуск, `mushroom` и `firestorm` — по 5 мин, `wall` и `debris` — 1–1,5 мин.
- **Песочница.** N3 её не запускал. Ядерка там есть (`nuke <кт>`), но нужный миг — волна у камеры через секунды
  после прихода фронта, пелена через минуты — можно увидеть, только прожив сцену целиком в реальном времени.
- **Цена находок.** Каждая находка съёмки стоила нового круга полных сцен: пыль от стволов, бежевая вуаль, лимит
  частиц, лист меньше пикселя.
- **Сравнение кадров.** Числом сравнить нельзя. N1: «кадры ядерки шумят от прогона к прогону, 10–40 % пикселей
  различаются и без правки». Поэтому `main` снимают дважды, чтобы видеть шум, и сравнивают глазами.

### 2.3. PR #379–#406

- **Влито** 27 PR.
- **От открытия до влития:**
  - инструменты процесса (#380–#388) — до 45 мин;
  - картинка ночью — 1,5–5,5 ч: #398 1,5 ч, #401 1,7, #404 2,5, #403 2,75, #400 3,9, #402 4,6, #399 5,5.
- **CI `build`:** медиана 4,9 мин, p90 7,1.
- **Повторы CI.** Правило «ветка обновлена до свежего `main`» требует нового прогона на каждое влитие в пачке: у LFX
  было 16 прогонов, у N3 — 12, у F5 и VXG — по 8. Каждый прогон — 5 мин ожидания, а не часы.

### 2.4. Потери по звеньям

| Звено | Потеря 22–23.10 | Причина |
|---|---|---|
| Съёмка со всей сборкой на каждый PR | 9,9 ч видеокарты, 130 запусков; PR с картинкой — 1,5–5,5 ч до влития | К2: проверка после работы вместо кадра во время работы |
| Эталон снимают заново | 2,65 ч из 9,9 | К1 и К2: каждая параллельная сессия снимала свой эталон к своей съёмке |
| Очередь видеокарты | 15 ч ожидания задач | К1: до четырёх сессий с картинкой сразу на одной видеокарте |
| Песочница не живёт | ночью ни одного полезного запуска | К1: гаснет от любой чужой задачи на видеокарте |
| Нужный миг ядерки | 342 с на запуск ради кадра «через 5 с у камеры» | не взят стандартный повтор Flashback |
| Сравнение кадров | 2 прогона `main` на сессию, глазами | К2: сравнение всей сборки вместо кадра во время работы; кадры ещё и не повторяются |
| Ожидание в сессиях | 1–2 ч на сессию | К1: ждут очередь видеокарты |
| CI | 5 мин, до 16 прогонов на PR | строгое «обновлено до main» (стандарт GitHub) при пачке влитий — растёт от числа параллельных PR (К1) |

## 3. Наше своё: вердикт по каждому инструменту и правилу

Корни (подробно — п. 4): **К1** — несколько сессий с картинкой на одной видеокарте; **К2** — картинку смотрят
после работы, а не во время; **К3** — нестабильные GameTest; **К4** — моды для запусков берутся из инстанса Артёма, а
не из Gradle.

| Своё | Что делает | Вердикт | Чем заменить или почему оставить |
|---|---|---|---|
| `tools/look_gate` + `ProbeScenario`: съёмка всей сборки под Iris на каждый видимый PR | кадры и звук рядом с 3.4.1 | **(б) К2**, и разовое | Стандарт: автор смотрит кадр в dev-клиенте, пока работает, кадры — в PR. Сравнение с 3.4.1 — временный разовый шаг (слово Артёма 07:40): довести все сцены до уровня 3.4.1 одним заходом, потом `look_gate` и `ProbeScenario` убрать |
| Сравнение кадров рядом с эталоном на каждый PR (правило «Проверяет не автор … кадры рядом с выпуском») | ловить регрессию картинки до влития | **(б) К2** | У моддеров рендера этого нет. Правило заменено в этом PR: ревьюер смотрит кадры и WAV dev-клиента из PR по моментам записки, как скриншоты в PR у всех (CLAUDE.md, шаблон PR) |
| Эталон 3.4.1, снимаемый каждой сессией | база сравнения | **разовый шаг**, его пересъёмка — **(б) К1** | снять один раз, пока идёт сравнение с 3.4.1; потом не нужен |
| Песочница `tools/sandbox.sh` | dev-клиент с модом в плоском мире, эффект командой `/fx`, кадр и WAV в заданный миг (`shot --at`) | **(в)** в ядре, **(б) К1** в обвязке | Ядро — то же, что стандартный `runClient` MDG с dev-набором исходников (так JEI держит свой `clientGameTest`). Своё в нём по нужде: Claude не видит экран и не печатает в чат, ему нужен кадр файлом в точный тик; у NeoForge клиентских тестов со снимками нет (NeoForge #3624). Обвязка «ждать очередь, гаснуть от чужой задачи» — следствие К1, убрать |
| Свой агент горячей замены `src/sandboxAgent` (`HotswapAgent` в `ua.zentix.hotswap`) | подменяет классы, когда Gradle их дописал | **(а)** | JetBrains Runtime + HotswapAgent (HotswapProjects, `-XX:HotswapAgent=fatjar`, `autoHotswap=true`: следит за `.class` и меняет их сам). Если он не видит классы мода в загрузчике NeoForge — стандартный путь IDE: JDWP (`-agentlib:jdwp`) и `redefine`. Проба — часть B |
| `tools/client_scenario.sh` + `ClientScenario` (сценарии клиента без окна) | кадры руководства, трейлера, сцены ядерки | **(в)**, слить с песочницей | Стандарта для NeoForge нет (у Fabric — клиентские GameTest). Но держать два своих клиента (сценарии и песочница) — лишнее: сценарий = список команд песочницы (`commands` уже есть). Один dev-клиент |
| Миг ядерки (сцена `nuke` 342 с ради кадра через 5 с) | кадр в момент t | **(а)** | Flashback (в сборке): записать один раз, перемотать, снять. Перемотка не повторяет разовые события (`replay.md`), но ядерку рисует по часам подрыва. Ванильный `/tick` не поможет: клиент не тикает чаще 20 раз в секунду (`Minecraft.getTickTargetMillis`: максимум из 50 мс и шага `TickRateManager`) |
| `tools/prod_client.py` (клиент со всей сборкой, копия инстанса) | «как у игрока»: сборка грузится, вид под Iris | **(а)** + разовый шаг | «Сборка грузится» — `mc-runtime-test` в CI open-skies (запускает настоящий клиент со сборкой). Вид под Iris — только разовое сравнение с 3.4.1 |
| `tools/x11_record.py` (видео окна для `prod_client.py --video`) | видео клиента со всей сборкой в реальном времени | **(а)** | Уходит вместе с `prod_client.py --video`. Ролики по повторам уже идут выводом видео Flashback (`tools/clips`); трейлер пишет свои кадры покадрово (`trailer/record.sh`) и `x11_record.py` не берёт |
| `tools/nested_kwin.sh --virtual` | клиент на видеокарте без окна и звука на рабочем столе Артёма | **(в)** | Стандарт CI — `xvfb-run` (Fabric Loom, Bevy), но он рисует на процессоре (llvmpipe, 3–10 кадров/с, без шейдеров). Нужда настоящая: ноутбук — рабочий стол Артёма. Сам KWin с `--virtual` — штатный режим KDE; запасной штатный — `gamescope --backend headless` (стоит на ноутбуке) |
| `tools/laptop_job.sh` | своя служба systemd, замок очереди, ждать закрытой игры Артёма | очередь — **(б) К1**; служба и игра — **(в)** | Своя служба: `systemd-run --user` — стандарт, обёртка тонкая. Защита игры Артёма — настоящая нужда: машина общая. Замок очереди убрать вместе с К1 |
| `tools/gpu_busy.sh` | чужая задача держит видеокарту | **(б) К1** | убрать вместе с К1 |
| `tools/sessions/go.sh`, `supervise.sh` | фоновая сессия claude2 своей службой, сама продолжается по концу задач | **(в)** | У Claude Code нет штатного «сессия службой, переживает родителя»; `claude -p`, `--resume` и `systemd-run` — штатные, обёртка тонкая. Число сессий и их длину ограничивает К1 |
| `live.md` (вне репозитория: `mod/run/incoming-sessions/`) | новые решения для идущих сессий | **(б) К1**, убран 25.10 | Долгие параллельные сессии не видят новых решений. Решения идут в задание перед запуском, сессии короткие |
| `mod/run/incoming-sessions/go.sh` (старый, без git) | то же | **(а)** | уже заменён `tools/sessions/go.sh` (#382), но ночью 22–23.10 шли через старый — убрать |
| `tools/pr_ci.sh` | ждать «Итог», конфликт — код 3 | **(а)** | «Записка» убрана 25.10, обязательна одна «Итог». Ещё `pr_ci.sh` берёт итог задачи, которую GitHub оставил «идущей» после конца, по её `completedAt`, а `--watch` ждал бы такую задачу до своего предела. Замена — `gh pr checks <PR> --watch --fail-fast --required` (gh 2.102 на ноутбуке), конфликт — `gh pr view --json mergeable`; зависшую задачу тогда видно по времени ожидания, перезапуск — `gh run rerun` |
| `tools/ci_repeat.sh` и ручной CI «повторить партии N раз» | ловить флак | **(б) К3** | Чинить нестабильные тесты (корень); до починки — стандарт GitHub `gh run rerun --failed` и `@GameTest(required = false)` у известного флака |
| Проверка «Записка» (`note.yml`, `pr_note.py`) | PR с видимым несёт раздел ожиданий | **(а)**, убрана 25.10 | Стандарт — шаблон PR (`.github/PULL_REQUEST_TEMPLATE.md`) и ревью. План большой правки — несколько строк в описании PR, проверки CI на это нет |
| `copyRuntimeMods_*` из `mods/` инстанса (`build.gradle`:53–65, 325–338) + `tools/fetch_runtime_mods.py` для CI | моды в запусках: Create, Sable, Aeronautics; по флагам — Sodium и Iris (`INCOMING_SHADERS`), Distant Horizons (`INCOMING_DH`), Voxy, Roxy и Forgified Fabric API (`INCOMING_VOXY`), LOD Server Support (`INCOMING_LSS`), Lithium (`-PwithLithium`). Рядом от инстанса зависят ещё шейдерпак хоста (копируют `sandbox.sh shaders`, `trailer/record.sh`, `client_scenario.sh`) и переопределение зависимостей FML (`build.gradle`:271 — включается, если в `mcModsDir` есть Aeronautics) | **(б) К4** → **(а)** | Стандарт MDK: зависимости в Gradle — `localRuntime "maven.modrinth:<мод>:<версия>"` (Modrinth Maven), каждая группа — за своим флагом, как сейчас. Один источник для ноутбука, облака и CI; уходят копирование из инстанса и правило «выровнять пины в `fetch_runtime_mods.py`» (`live.md` 07:18, вне репозитория). Sable и Aeronautics `fetch_runtime_mods.py` уже качает с Modrinth. Переопределение FML — по тому же флагу, а не по каталогу. Шейдерпак — не мод, а ресурс: его копия из инстанса остаётся (в) |
| `org.gradle.daemon=false` + `--daemon` только для `build` и «`gradlew --stop` не звать» | игра из `run*` не в группе демона; демоны сессий не гасят друг друга | `run*` без демона — **(в)**; остальное — **(а)** | Причина `daemon=false` (`mod/gradle.properties`:2–4, #384): игра, запущенная задачей `run*` через демон, жила бы в cgroup демона (часто — приложения Claude), а не в службе `laptop_job.sh`; это нужда общей машины, у MDK её нет — оставить. Для `build` и тестов тёплый демон уже есть (`--daemon`). Из настроек MDK взять `configuration-cache=true` (проба — часть B). «`gradlew --stop` не звать» остаётся и после К1: сессии без видеокарты по-прежнему идут параллельно, и `--stop` гасит демоны всех |
| Части GameTest (`GameTestShards`, `gametest_durations.py`) | CI 5 мин вместо ~20 | **(в)** | Своё ради скорости: у MDG и NeoForge деления GameTest на части нет; матрица — штатная у GitHub |
| «Что изменилось» в `build.yml` | не собирать мод на правку `docs/` | **(а)** | `dorny/paths-filter` — штатный фильтр путей на уровне задач: задача «Итог» идёт всегда и отчитывается. `on.pull_request.paths` не подходит: без запуска обязательная «Итог» не отчитается, и PR не влить |
| `tools/stress.sh`, `mp_scenario.sh`, `rig_procs.sh`, `free_port.py`, `rig_config.py` | мультиплеер и нагрузка без окон | **(в)** | Для автоматического мультиплеера у NeoForge стандарта нет; вручную — `runServer` и `runClient`. Оставить, запускать по нужде |
| `tools/deploy.sh`, `logscan.py`, `paths.py`, `chunk_status.py` | jar в инстанс, выжимка лога, пути | **(в)** | мелкие помощники; выпуск к игрокам и так идёт стандартно (выпуск → packwiz-сборка) |
| `build_sounds.py`, `gen_*.py`, `bake_fx/`, `freesound.py`, `guide_images.sh` | ресурсы мода | **(в)**, вне конвейера | Генераторы ресурсов этого мода, не конвейер. Модели из кода (`gen_models.py`) против Blockbench/Blender — отдельный вопрос, не здесь |
| `replayscan/`, `clips/`, `trailer/` | ролики | вне конвейера | производство роликов, не разработка мода |
| Мой прошлый вариант (хранилище эталона, очередь кадров видеокарты, число разницы FLIP, съёмка каждого кандидата выпуска) | — | **снято** | всё это своё поверх К1 и К2; по решению 07:40–07:41 не предлагаю |
| Правила «Сначала готовое», «Сначала ожидание», «Инвариант — тестом», ревью свежим контекстом, «Сверх этого проверок нет» | — | стандарт | готовые библиотеки, тесты и ревью — обычная практика; «сначала ожидание» — шаблон PR |

## 4. Корневые ошибки (от базовых принципов)

**К1. Несколько сессий с картинкой на одной видеокарте.**
- **Ресурс.** Видеокарта у нас одна, как у любого моддера. Работа с картинкой упирается в неё: кадр, съёмка, Blender.
- **Что было.** Ночью 22–23.10 шли до четырёх таких сессий сразу.
- **Что пришлось городить.** Очередь `laptop_job.sh`, проверку `gpu_busy.sh`, песочницу, которая гаснет от чужой
  задачи, повторные эталоны и `live.md` для долгих сессий. Цена — 15 ч ожидания задач за ночь.
- **Корень.** Параллелить работу на одном общем ресурсе.
- **Как чинить.** Картинку делает одна сессия за раз — так работает моддер. Параллельно идут только сессии без
  видеокарты: сервер, полёт, ПВО, инструменты, тексты.
- **Что уходит.** Очередь, `gpu_busy.sh`, обвязка песочницы, пересъёмка эталона и `live.md` (вне репозитория).
  Правило «`gradlew --stop` не звать» остаётся: сессии без видеокарты идут параллельно.

**К2. Картинку смотрели после работы, а не во время.**
- **Что было в 3.5.0.** PR проверяли тесты законов и ревью кода, а кадра никто не видел (`dev-process.md`, 2.1).
- **Что поставили сверху.** Шлагбаум со всей сборкой на каждый PR и проверку «Записка». Это проверка после факта, и
  стоит она часы на PR.
- **Корень.** Автор не смотрел на результат, пока делал.
- **Как чинить.** Как у всех: правка → кадр в dev-клиенте через секунды. Кадры кладутся в PR, ревьюер смотрит их
  по записке.
- **Что уходит.** Съёмка всей сборки на PR и проверка «Записка» в CI. Сравнение с 3.4.1 — разовое, пока все сцены не
  дотянут до его уровня.

**К3. Нестабильные GameTest.**
- **Сколько.** 10 % полных прогонов падали (`dev-process.md`, 2.7).
- **Что поставили сверху.** Повтор партий N раз на шести машинах — это поиск флака, а не починка.
- **Корень.** Тест зависит от времени, порядка или общей статики.
- **Как чинить.** Починить каждый тест. До починки — стандартные `@GameTest(required = false)` и
  `gh run rerun --failed`.

**К4. Моды для запусков — из инстанса Артёма.**
- **Как сейчас.** `build.gradle` копирует из `mods/` его инстанса Create, Sable и Aeronautics, а по флагам — Sodium,
  Iris, Distant Horizons, Voxy, Roxy, Forgified Fabric API, LOD Server Support и Lithium. Переопределение зависимостей
  FML включается, если в этом каталоге есть Aeronautics. CI и облако берут Create, Sable и Aeronautics своим скриптом
  `fetch_runtime_mods.py` с Modrinth.
- **Что выходит.** Два источника одной зависимости. При обновлении сборки пины приходится выравнивать вручную
  (`live.md` 07:18, вне репозитория).
- **Как чинить.** Как в MDK: `localRuntime "maven.modrinth:<мод>:<версия>"` в Gradle, каждая группа — за своим флагом,
  одна версия для всех запусков. Шейдерпак хоста — ресурс, а не мод: его копия остаётся.

## 5. Конвейер по стандарту и целевые времена

| Шаг | Как (стандарт; своё — с вердиктом (в)) | Цель | Сейчас |
|---|---|---|---|
| Правка тела метода, числа или шейдера → кадр | dev-клиент (песочница) на JBR + HotswapAgent, F3+T; кадр — `shot --at` | ≤ 10 с | 10 с (замер 22.10), но ночью песочница не жила (К1) |
| Правка, которую замена не берёт (регистрация, миксин, статическое поле) | перезапуск dev-клиента, как у всех (замена миксинов ненадёжна: Mixin #592, #408) | ≤ 40 с с Iris | 20–39 с |
| Кадр ядерки в момент t | повтор Flashback: записать один раз, перемотать | ≤ 1 мин (оценка, проба — часть B) | 342 с полного сценария на круг |
| От «готово» до влития | CI (`setup-gradle`, GameTest частями) + ревью свежим агентом по кадрам из PR; ждать — `pr_ci.sh`, после удаления «Записки» — `gh pr checks --watch --required` | ≤ 20 мин | 1,5–5,5 ч у PR с картинкой |
| Сборка грузится у игрока | `mc-runtime-test` в CI open-skies | минуты, на каждый PR сборки | проверки запуском клиента нет |
| Вид со всей сборкой под Iris | только разовое сравнение с 3.4.1, одним заходом, одной сессией | один раз | на каждый видимый PR |
| Выпуск и отзывы | `mc-publish`, заметки, PR сборки; беты — по слову Артёма (п. 6) | минуты | минуты |

Что остаётся своим и почему — только (в) из таблицы п. 3:
- **Кадр файлом в заданный тик и команды в dev-клиент без чата.** Claude не видит экран, а у NeoForge клиентских
  тестов со снимками нет.
- **Клиент на видеокарте без окна.** Ноутбук — рабочий стол Артёма, а `xvfb` рисует на процессоре.
- **Защита игры Артёма.** Машина общая.
- **Фоновые сессии службой и части GameTest в CI.**

## 6. Чем мы честно отличаемся от моддера

- **Смотрит Claude, а не человек.** Ползунки, отладчик и ImGui ему не помогут, ему нужен кадр файлом в заданный тик.
  Это единственная причина держать своё ядро песочницы.
- **Машина — рабочий стол Артёма.** Отсюда клиент без окна и защита его игры.
- **Бет нет.** Друзья играют в выпуски. Стандарт сообщества — беты (у Sodium их 39 на Modrinth, у Distant Horizons —
  191). Выпускать беты — публичный шаг, это решение Артёма.
- **Несколько сессий.** Это не отличие, которое надо обслуживать инструментами: с картинкой работает одна сессия за
  раз (К1).

## 7. План: перейти на стандарт и убрать своё (по выигрышу на усилие)

1. **К1: картинка — одна сессия за раз на видеокарте.**
   - Правило в CLAUDE.md (этот PR), порядок пакетов у координатора (`mod/run/incoming-sessions/DL-context-patch.md`,
     вне репозитория).
   - Выигрыш: 15 ч очереди за ночь → 0, песочница живёт.
   - После этого убрать замок очереди из `laptop_job.sh`, `gpu_busy.sh` и обвязку «гаснуть» в `sandbox.sh`. Правило
     «`gradlew --stop` не звать» остаётся.
   - Цена: правило сейчас, ~1 ч на чистку скриптов.
2. **К2: кадр — во время работы, в PR — кадры dev-клиента; съёмку всей сборки на PR убрать.**
   - Правило и шаблон PR заменены в этом PR: в PR — кадры и WAV dev-клиента по моментам записки, без «рядом с выпуском».
   - Разовое сравнение с 3.4.1 закончить одним заходом. После него убрать `look_gate`, `ProbeScenario`,
     `prod_client.py --video` с `x11_record.py`. Проверка «Записка» в CI убрана 25.10.
   - Выигрыш: 9,9 ч видеокарты за ночь → минуты на PR; «готово → влито» — с 1,5–5,5 ч до ~20 мин (оценка).
3. **Простые замены (а), по часу и меньше каждая:**
   - `pr_ci.sh` → `gh pr checks --watch --fail-fast --required` (обязательная проверка уже одна);
   - «Что изменилось» → `dorny/paths-filter` на уровне задач (не `on.pull_request.paths`);
   - `gradle.properties` → `configuration-cache=true` из MDK (проба — часть B); `daemon=false` для `run*` остаётся;
   - старый `mod/run/incoming-sessions/go.sh` (вне репозитория) → `tools/sessions/go.sh`;
   - `mc-publish` 3.3.1 → 3.3.2 (вышел 22.10.2026), `setup-gradle@v5` → `v6`.
4. **К4: моды запусков — `localRuntime` из Modrinth Maven.**
   - Всё, что сейчас копируется из инстанса: Create, Sable, Aeronautics; за флагами — Sodium и Iris, Distant Horizons,
     Voxy, Roxy и Forgified Fabric API, LOD Server Support, Lithium.
   - Флаги — те же (`INCOMING_SHADERS`, `INCOMING_DH`, `INCOMING_VOXY`, `INCOMING_LSS`, `-PwithLithium`); песочница —
     по-прежнему без Create и Sable.
   - Переопределение зависимостей FML (`build.gradle`:271) — по флагу Aeronautics, а не по каталогу инстанса.
   - Убрать `copyRuntimeMods_*` и `fetch_runtime_mods.py`; Sable и Aeronautics он уже берёт с Modrinth, а Modrinth Maven
     отдаёт любой проект Modrinth.
   - Шейдерпак хоста (`sandbox.sh shaders`, `trailer/record.sh`, `client_scenario.sh`) — ресурс, его копия остаётся.
   - Цена: ~2–3 ч (оценка).
5. **Свой агент замены → HotswapAgent (`autoHotswap`) на JBR.**
   - `hotswap-agent.jar` кладут в `lib/hotswap` самой JBR (по README HotswapAgent), флаги —
     `-XX:+AllowEnhancedClassRedefinition -XX:HotswapAgent=fatjar`.
   - Проба в части B: видит ли он классы мода в загрузчике NeoForge. Если нет — JDWP `redefine`, как IDE.
   - Потом удалить `src/sandboxAgent`.
6. **Один dev-клиент: сценарии `client_scenario.sh` — списками команд песочницы.**
   - Цена: большая (оценка: день), выигрыш — один свой клиент вместо двух. Делать после пп. 1–5.
7. **Миг ядерки — повтор Flashback** (проба в части B).
8. **К3: чинить флаки по одному.** Потом убрать `ci_repeat.sh` и ручной CI повторов.
9. **open-skies (другой репозиторий): `mc-runtime-test` в CI сборки.** Это проверка «сборка грузится», которую сейчас
   заменяет запуск на ноутбуке.

**Часть B (замеры на ноутбуке после выпуска, по ним правится этот отчёт):**
- HotswapAgent `autoHotswap` с JBR в песочнице: что берёт и за сколько секунд;
- повтор Flashback ядерки в песочнице: время от команды до кадра «через 60 с»;
- `./gradlew build` с настройками MDK (демон, кэш настройки);
- старт и правка в песочнице без очереди;
- `gamescope --backend headless` против вложенного KWin — только если KWin начнёт мешать.

## 8. Источники

Проект и сборка:
- MDK NeoForge 1.21.1: https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle (`build.gradle`, `gradle.properties`)
- ModDevGradle: https://github.com/neoforged/ModDevGradle , https://docs.neoforged.net/toolchain/docs/plugins/mdg/ ,
  https://plugins.gradle.org/plugin/net.neoforged.moddev , https://neoforged.net/news/moddevgradle2/
- Fabric Loom: https://docs.fabricmc.net/develop/loom ; Architectury Loom: https://github.com/architectury/architectury-loom ;
  Stonecutter: https://github.com/stonecutter-versioning/stonecutter-template-neoforge ;
  MultiLoader-Template: https://github.com/jaredlll08/MultiLoader-Template/tree/1.21.1
- Сборка больших модов: Create https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/build.gradle ,
  Mekanism https://github.com/mekanism/Mekanism/blob/26.3/build.gradle ,
  AE2 https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/main/settings.gradle ,
  JEI https://github.com/mezz/JustEnoughItems/blob/26.3/build.gradle.kts ,
  Sodium https://github.com/CaffeineMC/sodium/blob/dev/neoforge/build.gradle.kts ,
  Iris https://github.com/IrisShaders/Iris/blob/26.1/neoforge/build.gradle.kts ,
  Veil https://github.com/FoundryMC/Veil/blob/1.21/build.gradle ,
  Distant Horizons https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/main/buildSrc/build.gradle

Запуск, замена, перезагрузка:
- Fabric, запуск и горячая замена: https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game ;
  отладка, F3+T и `/reload`: https://docs.fabricmc.net/develop/debugging ; замена миксинов: https://wiki.fabricmc.net/tutorial:mixin_hotswaps
- MDG выбирает JVM запуска только по версии языка: https://github.com/neoforged/ModDevGradle/blob/main/src/main/java/net/neoforged/moddevgradle/internal/ModDevRunWorkflow.java
- JetBrains Runtime: https://github.com/JetBrains/JetBrainsRuntime/releases ; HotswapAgent: https://github.com/HotswapProjects/HotswapAgent
- Ошибки замены миксинов: https://github.com/SpongePowered/Mixin/issues/592 , https://github.com/SpongePowered/Mixin/issues/408
- Клавиши Iris (R, K, O): https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/Iris.java
- Veil, редактор и слежение за файлами: https://github.com/FoundryMC/Veil/blob/1.21/common/src/main/java/foundry/veil/impl/client/imgui/VeilImGuiCompat.java ,
  https://github.com/FoundryMC/Veil/blob/1.21/common/src/main/java/foundry/veil/impl/resource/VeilResourceManagerImpl.java ;
  ImGuiMC: https://github.com/FoundryMC/imguimc
- Слежение NeoForge за файлами настроек: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/config/ConfigWatcher.java ,
  https://docs.neoforged.net/docs/misc/config/

Тесты и проверка картинки:
- NeoForge GameTest 1.21.1: https://docs.neoforged.net/docs/1.21.1/misc/gametest
- Клиентские тесты Fabric со снимками: https://fabricmc.net/2025/03/24/1215.html , https://docs.fabricmc.net/develop/automatic-testing ,
  https://github.com/FabricMC/fabric/blob/HEAD/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.java ,
  https://github.com/FabricMC/fabric/blob/HEAD/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/screenshot/TestScreenshotComparisonAlgorithm.java
- У NeoForge клиентских тестов со снимками нет: https://github.com/neoforged/NeoForge/issues/3624
- mc-runtime-test: https://github.com/headlesshq/mc-runtime-test ; HeadlessMC: https://github.com/headlesshq/headlessmc/blob/HEAD/headlessmc-lwjgl/README.md
- CI модов рендера (только сборка): Sodium https://github.com/CaffeineMC/sodium/blob/dev/.github/workflows/build-pull-request.yml ,
  Iris https://github.com/IrisShaders/Iris/blob/26.1/.github/workflows/build.yml ,
  Veil https://github.com/FoundryMC/Veil/blob/1.21/.github/workflows/build.yml ,
  Flywheel https://github.com/Engine-Room/Flywheel/blob/26.1.2/dev/.github/workflows/build.yml ,
  Distant Horizons https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/main/.gitlab-ci.yml ,
  Flashback https://github.com/Moulberry/Flashback
- RenderDoc: RenderNurse https://github.com/neoforged/RenderNurse , GFX-Debuggers https://modrinth.com/mod/gfx-debuggers ,
  shaderLABS https://shaderlabs.org/wiki/Attaching_Graphics_Debuggers_to_Minecraft
- Вне Minecraft: NVIDIA FLIP https://github.com/NVlabs/flip ; Bevy https://github.com/bevyengine/bevy/blob/main/.github/workflows/example-run.yml ;
  Mesa CI https://docs.mesa3d.org/ci/index.html

CI и выпуск:
- setup-gradle: https://github.com/gradle/actions/blob/main/docs/setup-gradle.md , https://github.com/gradle/actions/releases
- CI с GameTest: Create https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/.github/workflows/build.yml ,
  JEI https://github.com/mezz/JustEnoughItems/blob/26.3/.github/workflows/tests.yml ,
  AE2 https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/main/.github/workflows/build.yml ,
  Mekanism https://github.com/mekanism/Mekanism/blob/26.3/.github/workflows/gametests.yml
- Публикация: https://modmuss50.github.io/mod-publish-plugin/ , https://github.com/Kir-Antipov/mc-publish ,
  https://github.com/modrinth/minotaur ; журнал изменений: https://keepachangelog.com/en/1.1.0/ , https://github.com/orhun/git-cliff
- Беты на Modrinth: https://api.modrinth.com/v2/project/sodium/version?loaders=%5B%22neoforge%22%5D ;
  формы задач Create: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/.github/ISSUE_TEMPLATE/crash.yml ;
  Crash Assistant: https://modrinth.com/mod/crash-assistant
- NeoForge, советы: https://docs.neoforged.net/docs/1.21.1/gettingstarted/ , https://docs.neoforged.net/docs/1.21.1/gettingstarted/structuring
- ИИ-инструменты: https://github.com/headlesshq/headlessmc-mcp , https://github.com/langyo/minecraft-mod-mcp , https://github.com/denfry/mcpfabric

Стандартные замены (п. 3):
- HotswapAgent, `autoHotswap`: https://github.com/HotswapProjects/HotswapAgent
- `localRuntime` в MDK: https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/blob/main/build.gradle
- `gh pr checks --watch --fail-fast`: https://cli.github.com/manual/gh_pr_checks
- Фильтр путей: https://github.com/dorny/paths-filter , https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions#onpushpull_requestpull_request_targetpathspaths-ignore
- Ванильный `/tick`: https://minecraft.wiki/w/Commands/tick ; клиент не тикает чаще 20 раз в секунду — `Minecraft.getTickTargetMillis`
  в исходниках NeoForge 21.1 (`Math.max(defaultValue, tickratemanager.millisecondsPerTick())`)
- gamescope, `--backend headless`: https://github.com/ValveSoftware/gamescope (список бэкендов — `gamescope --help`)
- `mc-runtime-test`: https://github.com/headlesshq/mc-runtime-test

Не нашлось: опубликованных замеров «правка → игра» у моддеров (секунды на замену и 30–90 с на перезапуск — оценка);
постов на Reddit 2025–2026 (сайт не открылся); руководства Iris по RenderDoc.
