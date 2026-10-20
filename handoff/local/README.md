# Обычное оружие по физике: полёт, цели, заряды, разрушения (локальные сессии Артёма)

Эту часть ведут **локальные сессии Артёма на ноутбуке**, когда у него сбросится лимит. Облачные сессии сюда не
заходят: они делают картинку и звук (`../cloud/`, `../README.md`). Папка живёт только в ветке
`claude/project-thread-izso6n` репозитория zentixua/incoming: читать её, не вливать в `main`.

```sh
git fetch origin claude/project-thread-izso6n
git worktree add /tmp/handoff origin/claude/project-thread-izso6n   # файлы — в /tmp/handoff/handoff/
```

## Задача

Пуск остаётся игровым (скрафтил боеприпас и пусковую, нажал «пуск», маршрут короче настоящего). Всё, что от начала
движения до поражения, — по ТТХ и законам: настоящие скорости и высоты, полёт с тягой и сопротивлением, баллистика
«Града», падение и пробитие бомбы, заряд каждого изделия в кг ТНТ вместо ванильной силы, разрушения по давлению и
импульсу, воронки, осколки, поджиг от топлива. Метод тот же, что у ядерки: каждое число из закона и ТТХ с источником.
Ракета — «Томагавк» (решение Артёма 18.10).

## Что уже сделано до тебя

- **Ядерка закончена** (Incoming 3.4.1): `BlastModel` (Кинни–Грэхем для любой массы), `HobBlast` (давление у земли по
  высоте подрыва), `BlastInjury` (лёгкие, бросок, удар, стёкла, завал), `DustModel`, `BlockResponse`/пороги. Шаг 5
  берёт их, а не пишет заново. Ядерка при этом не меняется.
- **Облачные сессии (если влиты)** сделали картинку и звук из паспорта: пакет взрыва несёт оружие
  (`S2C.Blast.weapon`), в паспорте есть `fuelAtImpact` (оценка), факелы, следы, объёмный дым, звук двигателей и
  взрывов, слух и толчок — всё выводится из скорости сущности, `WeaponSpec.charge` и данных двигателя. Поэтому, когда
  ты делаешь скорости и заряды настоящими, картинка и звук следуют сами. Если видишь, что что-то из них держится за
  старое число, — это ошибка облачной части, её чинить, а не подгонять полёт.
- Проверь `git log origin/main` перед началом: какие облачные PR влиты (заголовки про flames, smoke grid, flight sound,
  blast sound).

## Файлы

| Файл | Что это |
|---|---|
| `analysis.md` | исходный разбор: где мод расходится с физикой и почему |
| `decisions.md` | решения Артёма и умолчания, которые он подтверждает на старте |
| `steps/00-passport.md` | паспорт по ТТХ (первым: его поля берут все) |
| `steps/01-atmosphere.md` | атмосфера и погода (ISA, ветер по высоте, устойчивость): одна на полёт, дым и звук |
| `steps/02-flight.md` | полёт: тяга, сопротивление, настоящие скорости и высоты, баллистика «Града» |
| `steps/05-blast-core.md` | ядро взрыва: давление–импульс, воронка, помещения, урон, заряды вместо ванильной силы |
| `steps/06-fragments.md` | осколки |
| `steps/09-bomb.md` | бомба: падение, пробитие (Янг), запертый взрыв |
| `steps/10-fires.md` | поджиг от топлива и обломков (дым пожаров — облачная часть) |
| `tools/kg.py`, `tools/flight.py` | расчёты, из которых взяты числа |
| `../ref/passport.md`, `../ref/formulas.md`, `../ref/sources.md`, `../ref/feel.md` | общие ТТХ, законы, источники, целевая картина |
| `../ref/code-map/flight.md`, `warhead.md` | карты кода (строки на 18.10 — искать по имени) |
| `../nuke/*.md` | как сделана ядерка, проверенные числа, что помечено «не по физике» |

В шагах ссылки `code-map/…` и `formulas.md` указывают на `../ref/`.

## Порядок

| Шаг | Зависит от | Можно параллельно с |
|---|---|---|
| 0 Паспорт | — | — (первым) |
| 1 Атмосфера | 0 | 5 |
| 2 Полёт | 0, 1 | 5, 6 |
| 5 Ядро взрыва | 0 | 1, 2 |
| 6 Осколки | 0, 5 | 2 |
| 9 Бомба | 2, 5 | 6 |
| 10 Поджиг | 5 | 9 |

Атмосфера шага 1 — общий источник ветра и устойчивости: облачные сессии взяли ветер ядерки как временный общий; шаг 1
становится его источником, и дым со звуком переходят на него без своих чисел.

## Правила (те же, что у облачных сессий, плюс ноутбук)

1. Сначала `CLAUDE.md` и `.claude/rules/` своей области (`strike.md`, `entity.md`, `warhead.md`, `nuclear.md`,
   `work.md`, `gametest.md`, `server-chunks.md`); они главнее пакета.
2. Каждое число — из ТТХ или закона, источник рядом. Формулы с «⚠ сверить» — сверить по источнику до кода.
3. Один закон на класс; число, которым оружие отличается, — в паспорт (`WeaponSpec`), не константой и не `switch`.
4. Ядерку не менять: её законы — частный случай; тесты ядерки зелёные без правок.
5. Ничего не читать в незагруженных чанках из тика (`util/Terrain`, `getChunkNow`); массовое — единицами работы.
6. Шаг — свой PR черновиком сразу; сборка, юнит, GameTest, сценарии полёта (`runScenarioSweep`; сдвиг эталона —
   объяснить в PR); формат пакетов или данных сущностей → `IncomingNetwork.PROTOCOL`; заметное игроку — `docs/guide` на
   обоих языках.
7. Тяжёлое на ноутбуке — через `tools/laptop_job.sh`, клиенты — `tools/nested_kwin.sh`, ничего на экран и в колонки
   Артёма; игра Артёма открыта — видеокарту не трогать.
8. Без миксинов в ваниль и чужие моды; GPL-3.0-only. Готово = зелёный «Итог» + чистое независимое ревью, влить самому.
   Релиз и прочие публичные шаги — только по слову Артёма.

## Промпт локальной сессии (на шаг)

```
Read the handoff first: git fetch origin claude/project-thread-izso6n && git worktree add /tmp/handoff origin/claude/project-thread-izso6n, then read /tmp/handoff/handoff/local/README.md and /tmp/handoff/handoff/local/steps/<NN-step>.md, with ../ref/passport.md and ../ref/formulas.md as it points. Never merge the handoff branch.

Task: do step <NN> of the conventional weapons physics package exactly as its spec says (flight, targets, charges and damage by real data and laws; picture and sound are already derived from the passport by the cloud sessions and must follow by themselves). Rules: CLAUDE.md and .claude/rules of the area first; first principles, fix causes, one law per class, numbers with sources, the nuke unchanged, no mixins into vanilla or other mods. One draft PR into main right away; done = CI gate «Итог» green + clean independent review (fresh subagent or /code-review high), then merge it yourself. No release or public steps.
```
