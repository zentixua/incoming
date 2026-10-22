---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/VoxyIngest.java"
  - "mod/src/main/java/ua/zentix/incoming/client/LssVoxyWatch.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/LodSections.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/CopyLight.java"
  - "mod/src/main/java/ua/zentix/incoming/client/render/LodDepth.java"
  - "mod/src/main/java/ua/zentix/incoming/client/render/VoxyDepth.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/Voxy*.java"
  - "mod/src/test/java/me/cortex/voxy/**"
---

# Voxy (через Roxy)

## Подводные камни
- На NeoForge 1.21.1 Voxy есть только через Roxy (MIT): игрок кладёт официальный jar Voxy 0.2.16 для 1.21.11, Roxy
  переводит его байт-код при загрузке; в `ModList` он — мод `voxy`, классы `me.cortex.voxy.*` грузятся тем же
  загрузчиком, что и наши. API у Voxy нет (лицензия ARR: его код не копировать, только звать): всё — отражением, только
  чтение, без миксинов; не нашлись классы или поля — одна строка в лог, и возможность выключена, игра идёт как без Voxy.
  Версия на 1.21.1 застыла на 0.2.16, читаем её устройство (исходники Voxy на GitHub — уже 0.2.20, у них другое:
  сверять с jar 0.2.16). В тестах — подделки классов Voxy тех же имён (`src/test/java/me/cortex/voxy`), только то, что
  читает мод.
- Глубина для слоя эффектов (`client/render/VoxyDepth` за `LodDepth`, рядом с DH): текстура — `pipeline.fb.getDepthTex()`
  (непрозрачные LOD), проекция — `getViewport().projection` (ближняя 8–16 блоков, дальняя 48 км, прямая, NDC −1..1 в
  0.2.16), рисовал ли кадр — `frameId`. Под шейдерпаком вершины LOD ставит сам Voxy той же проекцией (`vxProj` пака),
  поэтому чужих плоскостей, как у DH под Iris, нет. Где рисовал Minecraft, Voxy кладёт в свою глубину **ближнюю
  плоскость** (без шейдерпака; под шейдерпаком потом возвращает очистку) — это метка «здесь мир», а не LOD в 16 блоках:
  прочитанная как LOD, она закрыла бы весь дым дальше 16 блоков (`LodDepth.View.world`, `fx_depth`: `LodWorld`). Без
  шейдерпака (и у пака с `renderToVanillaDepth`) Voxy переносит LOD и в глубину Minecraft, а дальние — прижимает к её
  дальней плоскости (на шаг 24 бит ниже 1): такой пиксель — LOD, его расстояние — по глубине Voxy (`pinsWorldDepth`,
  `PINNED`). Complementary — `excludeLodsFromVanillaDepth`. LOD читать ближайшим текселем: смесь соседних на краю холма
  даёт расстояние, которого в кадре нет. Тот же кадр без глубины Voxy — `-Dincoming.lodDepth.voxy=false`. Как читается —
  строка «Voxy depth for the effects layer» в логе и `SCENARIO … depth:`; сценарий клиента с Voxy —
  `tools/client_scenario.sh <сценарий> voxy` (Sodium, Roxy, Voxy и Forgified Fabric API из каталога модов).
- Изменения мира вдали (воронки, руины и след ядерки, лампы блэкаута) — тем же путём, что в DH (`compat/DhUpdates`:
  отметки, копии `FarLods`, копии тяжёлой зоны `FrontLods`), рядом с DH: `compat/VoxyLods` шлёт чанк игрокам с Voxy
  (`C2S.LodMods` при входе) пакетом `S2C.VoxyChunk`, кроме тех, кто этот чанк видит (его Voxy перестроит сам), не
  дальше `FarLods.FAR_CHUNKS`; клиент кладёт секции статическим `VoxelIngestService.rawIngest(WorldIdentifier, section,
  x, y, z, blockLight, skyLight)` по `MethodHandle` (`client/VoxyIngest`; так же делает LOD Server Support,
  `compat/VoxyCompat`) в потоке сети. Voxy — только клиент: на выделенном сервере его нет, поэтому пакет, а не вызов на
  сервере. Свет секций — его, Voxy его не считает: null — темно (LOD чёрные); у загруженного чанка — движок света мира
  (нет слоя неба над рельефом — 15), у копии — `compat/CopyLight` по её блокам внутри чанка (свет с диска оставил бы
  гореть погашенный квартал). Все секции, и пустые: воздух снесённой башни стирает её. Темп — 256 КиБ в тик игроку,
  очередь до 48 МиБ (`VoxyLods.room` держит `FarLods` и `FrontLods` и без DH); копия тяжёлой зоны идёт DH за
  `DhUpdates.LEAD` до фронта, а Voxy кладёт сразу — ей срок «не раньше фронта» (`VoxyLods.send(…, notBefore)`).
- LOD Server Support (сервер) рассылает чанк по сохранению (хэш, раз в 200 тиков) — живым или с диска, то есть уже с
  руинами, — а клиенту при входе отвечает «не изменился» по отметке времени: копии мода он не трогает. Перепишет
  только при первой сверке клиента (или после сброса его хранилища) — чанком с диска, где руин вдали нет: клиент
  замечает это своим подписчиком LSS (`client/LssVoxyWatch`, подписка — только при Voxy: подписчик без Voxy заставил
  бы LSS качать LOD) и называет чанки серверу (`C2S.VoxyStale`), сервер шлёт копию заново, если она отличается от
  диска (`FarLods.refresh`). Игрок, которого не было в мире во время удара, дальних руин в Voxy не увидит, пока не
  подойдёт (у DH их хранит база DH сервера). В повторе Flashback пакеты не кладутся (Voxy там показывает живую базу).
