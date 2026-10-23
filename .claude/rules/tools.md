---
paths:
  - "tools/**"
  - "mod/scripts/**"
  - ".github/**"
  - "mod/build.gradle"
  - "mod/gradle.properties"
---

# Где что лежит — подробно

Краткая раскладка — в корневом CLAUDE.md; здесь — что умеет каждый скрипт.

```
/mnt/data/projects/airstrike/            ← этот проект (git)
  mod/                                   ← исходники мода (ModDevGradle 2, NeoForge 21.1.264, Parchment, Java 21)
    src/main/java/ua/zentix/incoming/   ← код (пакеты — в корневом CLAUDE.md)
    src/main/resources/                  ← assets (звуки, текстуры, lang ru/en), data (теги, типы урона), AT
    src/devtest/                         ← GameTest, шаблоны площадок, сценарий клиента (в jar не входят)
      …/gametest/scenario/               ← сценарии полёта: оружие × цель × возмущение × зерно, свойства полёта,
                                           эталон траекторий src/devtest/resources/scenario-baseline.json
    src/test/                            ← юнит-тесты JUnit (звук, модель ядерного взрыва)
    scripts/gen_test_structures.py       ← шаблоны GameTest (pad, range, runway)
    run/<client|server|gametest|scenario>/  ← папки запусков (в .gitignore)
  tools/
    paths.py                             ← все пути к игре (единственное место)
    fetch_runtime_mods.py                ← Create/Sable/Aeronautics/Lithium с Modrinth (sha512) — для CI и облака без инстанса
    deploy.sh                            ← сборка → mods/ инстанса и dist/ (--test, --dry; --jar F — готовый jar CI/релиза);
                                           инстанс с автообновлением сборки не трогает: packwiz-installer вернул бы jar сборки
    logscan.py                           ← выжимка из logs/latest.log
    client_scenario.sh <сценарий> [shaders] [dh] [voxy] [lss] ← клиент без окна (KWin virtual + Xwayland), кадры и звук в WAV;
                                           сценарии и что снимает каждый — в шапке скрипта
    sandbox.sh start|fx|swap|shot|restart|stop|jbr ← песочница эффектов: клиент только с модом (+ Iris с шейдерпаком, DH) в
                                           плоском мире, эффект командой /fx за секунды, горячая замена кода и
                                           перезагрузка ресурсов без нового клиента, кадры и WAV одной командой (`shot`);
                                           ядерка у камеры — `nuke 15 near`; как работать — client-scenario.md,
                                           «Песочница эффектов»
    nested_kwin.sh                       ← вложенный KWin для клиента: без окна и без звука хоста, своя шина D-Bus без запуска служб
                                           (nested_kwin_bus.conf) и каталоги XDG
    laptop_job.sh [--gpu|--light] <имя> -- <команда> ← тяжёлая задача на ноутбуке хоста: своя временная служба systemd
                                           (не в группе Claude), ноутбук не засыпает, по выходу гасится всё её; очереди нет;
                                           --gpu [--wait] берёт аренду видеокарты своей сессии (у другой — отказ 75, с
                                           --wait — ждать) и ждёт, пока игра Артёма закрыта; в конце — «laptop_job <имя>:
                                           exit N»; --status — что идёт и у кого видеокарта
    gpu_lease.sh status|take [--wait]|release ← аренда видеокарты: с картинкой — одна сессия (владелец — по дереву
                                           wt-<S>…); песочница и задачи --gpu другой сессии отказывают «видеокарта у …, с …»;
                                           идущая работа продлевает, без неё истекает за 10 мин; тест — test_gpu_lease.sh
    sessions/go.sh <S> [id сообщение]    ← фоновая сессия claude2 своей службой systemd (incoming-claude-<S>): задание
                                           <S>.txt и бриф context.md из mod/run/incoming-sessions; итог хода — строкой в
                                           sessions.log; закончила ход, пока её задачи ноутбука (из дерева wt-<S>, wt-<S>-*)
                                           идут, — продолжается сама по их концу; live.md — новые решения для идущих
                                           сессий; --wait <S>, --status
    laptop-jobs/<имя>.md                 ← задания ноутбуку: координатор отправляет файл по SHA коммита, ноутбук сверяет sha256;
                                           отработанные удаляются (история — в git)
    logtime.py                           ← время строк лога Minecraft для выжимок заданий ноутбука
    free_port.py                         ← свободный порт на 127.0.0.1 для серверов проверок (stress.sh, mp_scenario.sh)
    rig_procs.sh                         ← процессы проверок в своих сессиях (source из stress.sh, mp_scenario.sh): выход скрипта гасит их с JVM
    test_rig_procs.sh                    ← проверка rig_procs.sh (CI): Ctrl+C, TERM, конец скрипта, чужая сессия, fd, срок RIG_GRACE — без сирот
    look_gate/run.sh <имя> <дерево> [сцены] ← шлагбаум картинки и звука: сцены ProbeScenario под Iris со всей сборкой (README.md
                                           там) — только разовое сравнение с 3.4.1, потом убрать (docs/dev-pipeline.md); слух всегда без оглушения, выборка — в порядке эталона и в воздухе его времени игры;
                                           годность прогона — check.py по логу (код 1), тест — test_check.py (CI «Шлагбаум»)
    pr_ci.sh <PR>                        ← (план: `gh pr checks --watch --fail-fast --required` — после удаления note.yml, когда
                                           обязательная проверка одна; docs/dev-pipeline.md) ждать «Итог» и «Записку» PR
                                           одной командой (в фоне), итог задачи, зависшей «идущей», — по её completedAt:
                                           0 — зелёные, 1 — упало (задачи и хвост лога), 3 — конфликт с базой (GitHub
                                           тогда проверки не запускает вовсе)
    pr_note.py < файлы PR                ← проверка «Записка» (CI, .github/workflows/note.yml): PR, который меняет то, что
                                           игрок видит или слышит (пути — PLAYER_FACING там же), несёт в описании раздел
                                           `## Записка`; описание — PR_BODY; тест — test_pr_note.py
    gametest_durations.py <лог>...       ← время партий GameTest по логам (latest.log или лог задачи CI) → таблица, по которой
                                           CI делит GameTest на части (src/devtest/resources/gametest-durations.json)
    mp_scenario.sh                       ← мультиплеер без окон: сервер и два клиента (Alpha бьёт, Bravo — цель), выходы и входы посреди удара
    prod_client.py <сценарий> [--world …] ← клиент со всей сборкой хоста, как у игрока (копия инстанса, без Prism): сценарий из
                                           ./gradlew scenarioJar (build/scenario-libs, в релиз не попадает); nuke-profile — замер подрыва;
                                           strike-profile — тики сервера вокруг ударов и телепортов (--prop incoming.profile.steps=…);
                                           leak — кто держит мир после выхода в меню: заходы в мир с командами (incoming.leak.*),
                                           в меню — гистограмма классов и выборка JFR с путями до корней (нужен -XX:VMThreadStackSize=8192:
                                           со стеком по умолчанию поиск путей роняет JVM), в лог — строки `SCENARIO leak … holder`;
                                           replay — повтор Flashback в каталоге tools/pack_dir.py сборки (zentixua/open-skies) с модами записи и quicksave: сохранился, открылся и проигрался — кадры снаряда и места, где он пропал; перемотки назад и вперёд через удары и ядерку — без живых взрывов и вспышек
    replayscan/                          ← разбор повтора Flashback самой игрой в таблицы: мод клиента без окна (события, пути,
                                           видимость глазами игрока или заданных камер, замеры земли у взрывов) и replaydb.py —
                                           база DuckDB с запросами под планы роликов (пуски, пролёты, удары по земле); съёмка планов
                                           видео Flashback с проверкой каждого кадра (-Dreplayscan.film); README.md там
    clips/                               ← короткие ролики по данным повторов: moments.py (кандидаты), plan.py (планы съёмки),
                                           edit.py (монтаж, подписи, проверка вспышек по кадрам); README.md там
    guide_images.sh [кадры]              ← картинки руководства docs/guide/img из кадров `client_scenario.sh guide`: рецепты
                                           по месту окна верстака, остальное — анимации из серий: экраны GUI — GIF (экран
                                           пульта — вырезом окна), мир — анимированный WebP (втрое-вчетверо легче GIF);
                                           первый кадр и длина каждой — числами в скрипте, нет серии — анимация не трогается
    x11_record.py                        ← окно клиента во вложенном KWin — в видео в реальном времени (ffmpeg x11grab) с отметками
                                           времени для звука audio.wav (prod_client.py --video, --size)
    stress.sh                            ← стенд нагрузки (облако, xvfb): выделенный сервер с режиссёром (src/devtest/.../stress) и три игрока
                                           без окна; залпы по 30, выход/вход, Незер, ядерка, «Отбой»; сводка — строки STRESS в логе
    build_sounds.py [разделы]            ← все звуки: записи CC0/CC BY с Freesound (кэш tools/.sound-cache) + синтез
                                           synth_mod_sounds.py; пишет sounds.json и SOUND-CREDITS.md (numpy, scipy, soundfile);
                                           взрывы и петли моторов — из оригиналов без потерь, громкость по EBU R128 (ракурсы
                                           взрывов — ровно на `BlastMix`, петли — тише взрыва своего снаряда, ровность проверяется)
    freesound.py login|fetch             ← оригиналы Freesound (OAuth2): вход вне репозитория (~/.config/incoming), файлы —
                                           tools/.sound-cache/orig; скачанные вход не требуют
    gen_textures.py                      ← текстуры (Pillow), фиксированный сид; у каждой группы свой генератор
                                           случайных чисел, новая — в конец (прежние файлы не меняются)
    gen_particles.py                     ← текстуры частиц эффектов (textures/fx/particle), факела и дальних спрайтов (numpy + Pillow)
    gen_models.py                        ← модели снарядов: сетки OBJ + текстуры (numpy + Pillow) и упрощённые копии
                                           деталей `_lod1` для моделей вдали, не править OBJ руками
    gen_grid_assets.py                   ← модели и состояния двойников ламп блэкаута из ванильного jar, не править руками
    trailer/record.sh [shaders]          ← трейлер: сценарий клиента trailer снимает планы покадрово в 60 fps
                                           (INCOMING_SIZE=1920x1080, кадры и журнал звуков — mod/run/scenario/trailer/)
    trailer/edit.py [--lang en|ru] [--draft] [--rec …] ← монтаж под музыку (Kevin MacLeod, CC BY), титры, звук из журнала →
                                           dist/incoming-trailer.mp4, -lite.mp4 и -credits.txt (строки для описания ролика)
    trailer/icon_from_frames.py <кадры> <папка> ← иконка мода из плана «icon» (шахед на фоне неба): 512 и малый вариант
  docs/DESIGN-nuke.md                    ← проект ядерного удара
  docs/guide/                            ← руководство игрока по-английски (страницы по темам, картинки в img/),
                                           docs/guide/ru/ — то же по-русски
  docs/store/                            ← тексты страниц Modrinth и CurseForge
  docs/media/                            ← иконка (она же логотип мода incoming.png), петля hero.webp для README и страниц,
                                           кадры для галереи
  .github/workflows/build.yml            ← CI: что изменилось → сборка и юнит-тесты, GameTest частями, итог; jar в артефактах; релиз
  .github/workflows/note.yml             ← CI: проверка «Записка» описания PR (tools/pr_note.py); шаблон описания —
                                           .github/PULL_REQUEST_TEMPLATE.md
  .github/workflows/publish.yml          ← страницы Modrinth и CurseForge, только ручным запуском: draft (скрытый черновик
                                           Modrinth), submit, release; поля — docs/store/modrinth.json
  docs/releases/<версия>.md              ← заметки к релизу
```

## Запуски и CI
- Моды для запусков (`run/*/mods`) копируются из инстанса задачами `copyRuntimeMods_*`; путь — `MC_DIR` или по
  умолчанию. Без инстанса (облако, CI) — `tools/fetch_runtime_mods.py` (Java 21 в облаке есть, сеть к
  NeoForge/Mojang/Parchment/Modrinth открыта).
- «Что изменилось» в `build.yml`: правка только `tools/` (кроме `fetch_runtime_mods.py`, `paths.py`), `docs/`, `*.md`
  мод не собирает и GameTest не гоняет (`tools/replayscan` — своя сборка и юнит-тесты, `tools/look_gate` — тест
  годности прогона); `mod/`, CI и незнакомые пути —
  проверка целиком, ручной запуск (релиз) — всегда целиком. jar — артефакт `incoming-jar`.
- Релиз: поднять `mod_version`, написать `docs/releases/<версия>.md` (по-английски), влить в `main` и запустить `build`
  вручную на `main` с `release=true` — после всех проверок workflow выпускает `v<версия>` с jar (оттуда его берут
  друзья). Если `main` ушёл вперёд от проверенного в игре коммита — ветка `claude/release-…` от этого коммита с одними
  заметками, запуск на ней. После выпуска — PR сборки с jar в open-skies: игроки с автообновлением ставят её при
  следующем запуске.
