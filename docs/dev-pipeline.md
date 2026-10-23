# Конвейер разработки: как работают опытные моддеры и что из этого берём

Исследование и предложение на 23.10.2026 (сессия DL). Вопрос Артёма: как десятки тысяч моддеров работают быстро и без
мучений и как сделать так же у нас. Источники — в конце; где источника нет, стоит «оценка». Ретроспектива с 01.10 и
что уже исправлено — `docs/dev-process.md`, здесь на неё опираюсь и числа оттуда не пересчитываю.

## Коротко

- **Моддеры не ждут, потому что правят в живой игре.** Горячая замена кода на JetBrains Runtime, F3+T для ресурсов,
  клавиша R у Iris для шейдеров: правка видна через секунды. Перезапуск клиента нужен только на новую регистрацию или миксин.
- **Их CI проверяет только сборку и серверные тесты, за 2–5 мин.** Ни у одного большого мода рендера (Sodium, Iris,
  Distant Horizons, Veil, Flywheel, Flashback) нет в CI сравнения кадров. Картинку смотрит автор в своей игре, а
  остальное ловят беты и отзывы игроков.
- **Инструменты у нас не хуже.** Песочница даёт кадр через 10 с после правки, GameTest идёт шестью частями, CI — 5 мин.
- **Время уходит на другое.** Каждый видимый PR снимаем со всей сборкой под Iris (10–77 мин). Эталон каждая сессия
  снимает заново. До четырёх сессий сразу стоят в очереди к одной видеокарте, а песочница гаснет от любой чужой задачи.
  За ночь 22–23.10 это 9,9 ч видеокарты и 15 ч ожидания задач в очереди.
- **Предлагаю так.** Правка и проверка сцены — только в песочнице, рядом с хранимым эталоном выпуска: секунды и
  минуты. Полная съёмка со всей сборкой — один раз на выпуск, а не на каждый PR: `main` до игроков не доходит.
  От «готово» до влития остаются CI и ревью, ~15 мин.

## 1. Звенья: как у моддеров, как у нас, что берём

| Звено | Как у опытных моддеров (конец 2026) | Как у нас | Что берём |
|---|---|---|---|
| Проект | MDK NeoForge 1.21.1: ModDevGradle 2.0.148, Parchment 2024.11.17. Create, Mekanism, AE2, JEI, Sodium, Iris, Veil — на MDG 2.0.14x. Мультилоадер (MultiLoader-Template, Architectury, Stonecutter) — только кому нужны несколько загрузчиков или версий | MDG 2.0.147, Parchment 2024.11.17, одна версия | так и оставить; мультилоадер не нужен (решение 22.10: остаёмся на NeoForge 1.21.1) |
| Скорость сборки | MDK: `daemon`, `parallel`, `caching`, `configuration-cache` — все `true`; MDG поддерживает кэш настройки; Mekanism, AE2, JEI его включили; Create — без демона | тёплый демон для `build` (#384): компиляция 3–6 с; кэш сборки общий; кэш настройки выключен | кэш настройки — проба (часть B); выигрыш ~1 с, не главное |
| Запуск и отладка | запуски из IDE, отладчик; горячая замена тел методов; JetBrains Runtime с `-XX:+AllowEnhancedClassRedefinition` — и поля, и методы (Create ставит флаг на всех запусках, Mekanism — когда JVM от JetBrains); замена миксинов ненадёжна (Mixin #592, #408) — перезапуск | песочница: свой агент замены, JBR 21 (`sandbox.sh jbr`), `restart` ~20 с | JBR — всегда в песочнице (ставится один раз); отладчик и IDE Claude не нужны: вместо точек останова — строки `SCENARIO` и `overlay` |
| Перезагрузка в игре | F3+T — ресурсы, `/reload` — данные, R у Iris — шейдерпак; Veil следит за файлами и пересобирает шейдеры сам | `swap` делает F3+T, шейдеры слоя — наши ресурсы | так и есть; ещё — настройки: NeoForge перечитывает изменённый TOML и шлёт `ModConfigEvent.Reloading` без перезапуска |
| Подбор чисел вида | ползунки в ImGui (Veil F6, ImGuiMC), экраны настроек | число — в коде или в аргументе `/fx`, кадр — `shot` | ползунки не берём: Claude их не двигает, ему нужен кадр в заданный миг |
| Автотесты | JUnit; JUnit с игрой (`unitTest` MDG + `testframework`); серверные GameTest в CI — Create, JEI, AE2, Mekanism; клиентские тесты со снимком и сравнением с шаблоном — только Fabric (с 1.21.5); у NeoForge их нет (#3624, открыт 10.10.2026); `mc-runtime-test` — запуск клиента в CI без снимков | JUnit-законы, GameTest шестью частями, сценарии полёта, клиент без окна со снимками | своё уже сильнее среднего; клиентский тест со снимком у нас — песочница (см. п. 4) |
| Проверка картинки | глазами в своей игре; RenderDoc и Nsight — разбирать кадр (на Linux — `renderdoccmd`; RenderNurse NeoForge требует Java 25); вне Minecraft: Bevy снимает кадры примеров в CI, NVIDIA FLIP считает разницу кадров | шлагбаум `look_gate` со всей сборкой под Iris на каждый видимый PR, сравнение глазами (кадры шумят на 10–40 % пикселей) | на PR — песочница с повторяемыми кадрами и числом разницы, как у Fabric; полная сборка — раз на выпуск |
| CI | GitHub Actions + `gradle/actions/setup-gradle` (v6.4.0; кэш пишет только основная ветка). Время: Create 2–4 мин, Sodium 1,5–3, AE2 3–4, Mekanism 2–3, JEI 5–14 | 4,9 мин медиана, p90 7,1; GameTest шестью частями; `setup-gradle@v5` | оставить; v6 — мелкая правка |
| Выпуск | `mod-publish-plugin` 2.2.1 (Create, Sodium), `mc-publish` 3.3.2, Minotaur; журнал изменений — Keep a Changelog, git-cliff | `mc-publish` 3.3.1, `docs/releases/<версия>.md`, `release=true`, PR сборки | оставить, работает за минуты |
| Беты и отзывы | Sodium: 39 бет и 11 альф на Modrinth, Distant Horizons — 191 бета, Iris — 12; ночные сборки из CI; формы задач с логом на mclo.gs; мод Crash Assistant | друзья получают только выпуски через сборку; `logscan.py` после игры | бета у друзей — решение Артёма (п. 7) |
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
| Съёмка со всей сборкой на каждый PR | 9,9 ч видеокарты, 130 запусков; PR с картинкой — 1,5–5,5 ч до влития | ворота «не хуже выпуска» стоят на каждом видимом PR, а не на выпуске |
| Эталон снимают заново | 2,65 ч из 9,9 | кадры эталона не хранятся (п. 5.2 `dev-process.md` не сделан) |
| Очередь видеокарты | 15 ч ожидания задач | до четырёх сессий с картинкой сразу, каждой нужны часы видеокарты |
| Песочница не живёт | ночью ни одного полезного запуска | гаснет от любой задачи на видеокарте |
| Нужный миг ядерки | 342 с на запуск ради кадра «через 5 с у камеры» | время эффекта не промотать; кадр — только прожив сцену |
| Сравнение кадров | 2 прогона `main` на сессию, глазами | кадры не повторяются от прогона к прогону |
| Ожидание в сессиях | 1–2 ч на сессию | ждут очередь и CI циклами |
| CI | 5 мин, до 16 прогонов на PR | строгое «обновлено до main» при пачке влитий |

## 3. Чем мы честно отличаемся от моддера

- **Смотрит Claude, а не человек.** Моддер крутит камеру и двигает ползунок, пока не понравится. Claude видит только
  кадр файлом, поэтому каждое «посмотреть» — это кадр в заданный миг с заданной точки и числа модели рядом. Это и есть
  `sandbox.sh shot --at`. Отладчик, ImGui и ползунки ему бесполезны, повторяемый кадр — нужен.
- **Несколько сессий на одной видеокарте.** У моддера одна игра на одну видеокарту. У нас до четырёх сессий сразу на одну
  RTX 4060. Видеокарту надо отдавать короткими кусками (кадр — секунды), а не задачами на час.
- **Бет нет.** Артём с друзьями играют в выпуски и не хотят «проверь в игре». Поэтому ворота «не хуже прошлого
  выпуска» нужны (урок 3.5.0), но не обязательно на каждом PR.
- **`main` до игроков не доходит.** Сборка Open Skies берёт только выпуски. Значит, полная проверка нужна перед
  выпуском, а между выпусками хватает проверки в песочнице.

## 4. Новый конвейер

### 4.1. Целевые времена

| Шаг | Где | Цель | Сейчас |
|---|---|---|---|
| Правка тела метода, числа или шейдера → кадр | песочница, `shot` (замена и F3+T) | ≤ 10 с | 10 с (замер 22.10), но ночью песочница не жила |
| Правка, которую замена не берёт (регистрация, миксин, статическое поле) | `sandbox.sh restart` | ≤ 40 с с Iris | 20–39 с |
| Кадр ядерки в миг t (волна у камеры, пелена через минуты) | песочница + промотка или повтор Flashback (п. 4.2, г) | ≤ 1 мин | 342 с полного сценария на каждый круг |
| Сцена рядом с эталоном выпуска | песочница; эталон — из хранилища | 1–3 мин на сцену | 10–77 мин на версию |
| От «готово» до влития | CI 5 мин + ревью свежим агентом | ≤ 20 мин | 1,5–5,5 ч у PR с картинкой |
| Полная проверка со всей сборкой под Iris | один раз на кандидата выпуска, одной задачей | 1–1,5 ч на выпуск (оценка: сцены `look_gate` и ядерки подряд) | на каждый видимый PR |
| Регрессия, найденная на выпуске | `git bisect` по влитым PR кадрами песочницы | минуты на шаг | — |

### 4.2. Как это устроено

**(а) Песочница — единственное место правки картинки и звука.** Она уже есть (#379). Чего не хватает:
- **Не гаснуть от чужих задач.** Песочница уступает только игре Артёма и съёмке выпуска.
- **Брать видеокарту на время кадра, а не на всю жизнь.** `shot` держит замок видеокарты секунды. Между кадрами
  клиент стоит на паузе или с низким пределом кадров, чтобы не мешать другим.
- **Две-три песочницы сразу**, если поместятся. По журналу песочница берёт 1,2–3,4 ГБ ОЗУ, клиент со всей сборкой —
  4,6–6,8. Видеопамять не замерена (часть B).

**(б) Эталон снимается один раз на выпуск и хранится.** Кадры песочницы и сцен `look_gate` кладутся по ключу: тег
выпуска (или хэш кода), сцена, размер, шейдерпак. Есть кадры с таким ключом — сцена не снимается. Это п. 5.2
`dev-process.md` с песочницей в придачу.

**(в) Кадры должны повторяться, и сравнивать их надо числом.**
- **Что уже одинаково:** зерно эффекта (`blast <оружие> [зерно]`), мир песочницы, время по тикам.
- **Что проверить в части B:** два прогона одной правки должны дать почти один кадр.
- **Если шум есть:** найти его источник — случайность без зерна, кадр не того тика — и закрыть тестом. Замазывать
  шум порогом нельзя.
- **Число разницы:** как у Fabric (средний квадрат разницы, порог 0,005) или NVIDIA FLIP, рядом — лист кадров для
  глаз.
- **Кто смотрит:** ревьюер по записке.

**(г) Миг ядерки — без проживания сцены.** Два пути, выбор — по пробе в части B:
- **Повтор Flashback** (мод уже в сборке): подрыв записан один раз, перемотка к нужному тику, кадр.
  - **Что даст сразу.** Ядерку клиент рисует по часам подрыва, поэтому пелена, гриб и фронт в миг t видны сразу после
    перемотки.
  - **Чего не даст.** Разовые события перемотка не повторяет (`Replay.event`, `replay.md`), поэтому частицы с историей
    (обломки N3) — только проиграв кусок до t.
  - **Что уже есть.** Перемотку через удары и ядерку проверяет `prod_client.py replay`.
- **Промотка модели.** Эффекты, чьё состояние — функция возраста (фронт `FrontProfile`, `CloudModel`), можно поставить
  сразу на возраст t. Частицы с историей так тоже не промотать.

**(д) Полная сборка под Iris — раз на кандидата выпуска.**
- **Что снимаем:** все сцены `look_gate` и ядерки одной задачей против прошлого выпуска, когда сессий с картинкой нет
  (ночью или между пакетами).
- **Если хуже:** PR-исправление; виновный PR находит `git bisect` кадрами песочницы.
- **Риск:** регрессия поживёт в `main` до выпуска. Игроки её не увидят, но сессии будут строить на ней. Поэтому
  песочница рядом с эталоном на каждом PR остаётся обязательной.

**(е) Видеокарта — короткими кусками.** Когда сессии нужны минуты вместо часов, четыре сессии с картинкой умещаются
на одной видеокарте. Правило «одна область — одна сессия» остаётся.

**(ж) Остальное.**
- Ждать фоновой задачей с уведомлением, а не `sleep` (#382, #383 — уже есть).
- Новые сессии — только `tools/sessions/go.sh`.
- Бриф короче (п. 5.6 `dev-process.md`: `context.md` всё ещё 64 КБ).

## 5. Что берём и что бросаем

**Берём готовое:**
- JetBrains Runtime 21 с `-XX:+AllowEnhancedClassRedefinition` (как Create) — уже в песочнице, ставить всегда;
- F3+T и R у Iris;
- слежение NeoForge за файлом настроек: подбор настроек без перезапуска;
- Flashback для мига ядерки (проба);
- NVIDIA FLIP (BSD-3, `pip install flip-evaluator`) или средний квадрат, как у Fabric, — для числа разницы кадров;
- `renderdoccmd` — разбирать кадр, если проход ведёт себя странно;
- кэш настройки Gradle (проба), `setup-gradle@v6`, `mc-publish` 3.3.2 — мелочи.

**Своё — с причиной:**
- **Песочница со снимком в заданный миг.** У NeoForge клиентских тестов со снимками нет (#3624 открыт без ответа).
  Клиентские тесты Fabric — только для Fabric и не для живой правки.
- **Хранилище эталона и очередь кадров видеокарты на несколько сессий.** У моддеров одна игра на одного человека, и
  такой задачи у них нет.

**Бросаем:**
- полную съёмку под Iris на каждом видимом PR — переносим на выпуск;
- съёмку эталона каждой сессией — эталон из хранилища;
- второй прогон `main` «чтобы видеть шум» — повторяемые кадры;
- «песочница гаснет от любой задачи на видеокарте»;
- сравнение только глазами, без числа;
- ожидание циклами `sleep`.

**Не берём:**
- ImGui и ползунки: Claude не двигает ползунки;
- IDE и отладчик;
- мультилоадер и Stonecutter: у нас одна версия;
- замену миксинов: ошибки Mixin #592 и #408 — для миксинов `restart`;
- HeadlessMC MCP: экран у него JSON, без картинки;
- RenderNurse: ей нужна Java 25.

## 6. План внедрения (по выигрышу на усилие)

1. **Правило: полная съёмка — раз на выпуск, на PR — песочница рядом с эталоном.**
   - Этот PR правит CLAUDE.md, `client-scenario.md` и `tools.md`; бриф — `DL-context-patch.md`.
   - Выигрыш: ночь 22–23.10 — ~9 ч видеокарты и 15 ч очереди превращаются в минуты на PR; «готово → влито» —
     с 1,5–5,5 ч до ~20 мин (оценка).
   - Цена: решение Артёма.
2. **Песочница не гаснет от чужих задач; кадр берёт видеокарту на секунды.**
   - Цена: ~2 ч (оценка) + замер в части B: две песочницы и съёмка рядом, память, видеопамять 8 ГБ.
3. **Эталон по ключу** — для песочницы и `look_gate`. Цена: ~2 ч.
4. **Повторяемые кадры песочницы и число разницы (FLIP).** Цена: 2–3 ч + проба.
5. **Миг ядерки:** проба повтора Flashback в песочнице против промотки модели (часть B), потом — выбранное.
6. **Съёмка кандидата выпуска.**
   - Все сцены одной задачей против прошлого выпуска и `bisect` в песочнице.
   - Цена: ~1 ч, скрипт поверх `look_gate`.
7. **Мелочи:**
   - кэш настройки Gradle (проба);
   - `setup-gradle@v6`, `mc-publish` 3.3.2;
   - бриф сессий короче;
   - новые сессии — через `tools/sessions/go.sh`.

**Часть B (замеры на ноутбуке после выпуска, по ним правится этот отчёт):**
- старт песочницы и правка в ней;
- замена с JBR: что берёт и сколько секунд;
- две песочницы и съёмка сразу — память и видеопамять;
- повторяемость кадра песочницы (два прогона, число разницы);
- повтор Flashback ядерки в песочнице: сколько от команды до кадра «через 60 с»;
- `./gradlew build` с кэшем настройки.

## 7. Источники

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

Не нашлось: опубликованных замеров «правка → игра» у моддеров (секунды на замену и 30–90 с на перезапуск — оценка);
постов на Reddit 2025–2026 (сайт не открылся); руководства Iris по RenderDoc.
