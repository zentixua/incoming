package ua.zentix.incoming.scenario;

import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Команда песочницы эффектов ({@link Sandbox}, {@code /fx …} в чате или строка файла {@code fx/inbox/*.cmd}), без
 * Minecraft: разбор строки в действие. Слова:
 * <ul>
 *   <li>{@code blast <оружие> [seed] [высота]} — взрыв только у клиента: пакет взрыва в той точке песочницы, как от
 *       сервера (картинка и звук; воронки, огня и обломков сервера нет — земля остаётся, взрыв можно повторять); высота —
 *       над землёй, м (удар в крышу — на высоте дома {@code house}, иначе — точка в воздухе над травой); последнее слово
 *       {@code water} — удар в пруд {@link #POND}×{@link #POND} блоков глубиной {@link #POND_DEPTH} (вода у сервера и
 *       клиента, земля в пакете — вода; {@code clear} возвращает траву);</li>
 *   <li>{@code scenery grass|paved|forest|roof} — поверхность и одинаковый лес для сравнения пыли (сначала убрать дом); roof —
 *   дощатая крыша в метр над пустым помещением 21×21 высотой 3 м (стены и пол из каменного кирпича): удар {@code blast <оружие> 1 5} — в крышу,
 *   плита над пустотой;</li>
 *   <li>{@code house <высота> [полуширина] [верх]} — дом из каменного кирпича под точкой (высота 0 — убрать), по
 *       умолчанию 21×21 блок; верх — блок крыши ({@code coarse_dirt} — крыша-сад с рыхлой землёй);</li>
 *   <li>{@code real <оружие> [deck <м> | houses | roof]} — настоящий взрыв боевой части на сервере (воронка, пожары, обломки)
 *   на новом месте; {@code deck} — удар в середину моста: настил из камня в столько метров над руслом с водой (пробой
 *   плиты); {@code houses} — у дощатого дома сцены {@code debris} (дома и дубы рядом): что загорится и как дымит;
 *   {@code roof} — те же дома, удар в середину дощатой крыши: куда падает и где горит топливо;</li>
 *   <li>{@code nuke <кт> [air|ground] [near [метров]]} — ядерный подрыв без полёта ({@code /incoming nuke now}) на новом
 *       месте; с {@code near} — вид у камеры: дома и дубы сцены {@code debris} в точке, подрыв в стольких метрах к северу
 *       (по умолчанию {@link #NEAR_RANGE}), время эффекта — от прихода фронта к домам (кадры и до него, с −10 с);</li>
 *   <li>{@code airburst [seed]} — разрыв зенитной ракеты в воздухе над точкой;</li>
 *   <li>{@code sam} — ЗРК в точке (заряжен) и шахед, который идёт через неё с севера; время — от схода зенитной ракеты
 *       (след, облако схода, звук мотора — как сцена {@code sam-side} пробы);</li>
 *   <li>{@code raid [число] [радиус] [секунд между] [оружие] [water]} — налёт: взрывы (по умолчанию шахедов) только у
 *       клиента (как {@code blast}) в круге радиуса вокруг точки один за другим (по умолчанию 16 за 45 с в 120 м) — цена
 *       дыма многих ударов в кадре; {@code water} — в пруд, как у {@code blast} (залп «Града» по воде у берега: радиус —
 *       не больше полупруда);</li>
 *   <li>{@code launch <оружие>} — пуск с пусковой в точке; время — от первого движения снаряда;</li>
 *   <li>{@code flight <оружие> [от, блоков]} — снаряд проходит над точкой по прямой: пуск в стольких блоках к югу
 *       (по умолчанию {@link #FLIGHT_FROM}), цель — столько же к северу, у оружия с маршрутом оператора (шахед, ракета,
 *       «Ланцет») точка маршрута — сама точка ({@link #order}); время — от ближайшего к ней места; у {@code launch} и
 *       {@code flight} последнее слово {@code own} — пуск от игрока (свои снаряды: список и метки на экране);</li>
 *   <li>{@code spent <оружие> [высота]} — отработавший ускоритель ({@code SpentBoosterEntity}) в высоте над точкой (по
 *       умолчанию 20 м): падает, кувыркаясь, и лежит; время — от конца работы его двигателя (горячее сопло, {@code NozzleGlow});</li>
 *   <li>{@code fire [сторона]} — пожар: квадрат незерака с огнём в точке;</li>
 *   <li>{@code cam <дальность> [высота] [азимут°] [взгляд]} — где камера: дальность от точки, высота над землёй, азимут
 *       от точки (0 — юг, 90 — запад, как поворот игрока), высота точки взгляда над землёй;</li>
 *   <li>{@code stance stand|fly} — стоящий игрок (качание тела) или летающий наблюдатель (по умолчанию);</li>
 *   <li>{@code weather clear|rain} — погода для проверки конденсации во влажном воздухе;</li>
 *   <li>{@code time day|night|<тик>}, {@code hud on|off}, {@code overlay on|off} — свет, интерфейс, числа модели;</li>
 *   <li>{@code track on|off} — взгляд держит снаряд эффекта {@code launch}/{@code flight} в середине кадра (как игрок,
 *       который ведёт его с зумом);</li>
 *   <li>{@code zoom <k>} — зум ×k (1–256; 1 — без зума): поле зрения в k раз уже (тангенс половины угла), как у Ok Zoomer, —
 *       последним, после всех, кто его меняет;</li>
 *   <li>{@code shots <имя> <секунды через запятую> [wav]} — кадры и звук следующего эффекта: кадры на эти секунды от
 *       его начала, звук — столько секунд ({@code wav}, 0 — без звука) в {@code fx/out/<имя>/};</li>
 *   <li>{@code again} — последний эффект ещё раз (после горячей замены кода), {@code clear} — снять все эффекты,
 *       {@code reload} — перезагрузка ресурсов (как F3+T: шейдеры, текстуры, звуки, язык), {@code wait <тиков>}.</li>
 * </ul>
 */
record SandboxCommand(Verb verb, List<String> args) {
    /** {@code nuke … near}: дальность подрыва от домов по умолчанию — как в сценарии {@code debris} (2 км). */
    static final double NEAR_RANGE = 2000;
    /** {@code launch}: цель — на столько блоков к северу от точки (−Z). */
    static final int LAUNCH_RANGE = 3000;
    /** {@code flight}: пуск — на столько блоков к югу от точки (+Z), цель — на столько же к северу (−Z). */
    static final int FLIGHT_FROM = 600;
    /** {@code blast … water}: сторона и глубина пруда, блоков (пруд шире столба и кольца брызг шахеда). */
    static final int POND = 64, POND_DEPTH = 4;

    enum Verb { BLAST, REAL, NUKE, AIRBURST, LAUNCH, FLIGHT, FIRE, SPENT, CAM, STANCE, WEATHER, TIME, HUD, OVERLAY, ZOOM, TRACK, SHOTS, AGAIN, CLEAR, RELOAD, WAIT, RAID, SAM, HOUSE, SCENERY, SURFACE }

    /** Эффект (его повторяет {@code again} и снимает {@code shots}). */
    boolean effect() {
        return switch (verb) {
            case BLAST, REAL, NUKE, AIRBURST, LAUNCH, FLIGHT, FIRE, SPENT, RAID, SAM, SURFACE -> true;
            default -> false;
        };
    }

    /** Пуск от игрока ({@code own} последним словом): он владелец снаряда — список и метки снарядов на экране, как в игре. */
    boolean own() {
        return (verb == Verb.LAUNCH || verb == Verb.FLIGHT) && args.size() > 1 && args.getLast().equals("own");
    }

    static SandboxCommand parse(String line) {
        String s = line.strip();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("fx ")) s = s.substring(3).strip();
        String[] w = s.split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) throw new IllegalArgumentException("пустая команда");
        Verb verb;
        try {
            verb = Verb.valueOf(w[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("нет команды " + w[0]);
        }
        List<String> args = new ArrayList<>(List.of(w).subList(1, w.length));
        SandboxCommand c = new SandboxCommand(verb, List.copyOf(args));
        c.check();
        return c;
    }

    private void check() {
        switch (verb) {
            case BLAST, REAL -> {
                need(1, verb == Verb.BLAST && water() ? 4 : 3);
                WeaponType w = weapon();
                if (w == WeaponType.NUKE) throw new IllegalArgumentException("ядерка — nuke <кт>");
                if (verb == Verb.REAL) {
                    if (houses() || roof() || paved() ? args.size() != 2
                            : args.size() > 1 && (args.size() != 3 || !List.of("deck", "concrete").contains(args.get(1)) || number(2) < 1 || number(2) > 6)) {
                        throw new IllegalArgumentException("real <оружие> [deck|concrete <1–6 м> | houses | roof | paved]");
                    }
                    break;
                }
                int n = args.size() - (water() ? 1 : 0);
                if (n > 1) seedAt(1);
                if (n > 2 && (number(2) < 0 || number(2) > 200)) throw new IllegalArgumentException("высота 0–200 м");
            }
            case LAUNCH, FLIGHT -> {
                need(1, own() ? 3 : 2);
                weapon();
                if (args.size() > (own() ? 2 : 1) && !(number(1) >= 50 && number(1) <= 5000)) throw new IllegalArgumentException("от точки 50–5000 блоков");
            }
            case NUKE -> {
                need(1, 4);
                int kt = (int) number(0);
                if (kt < 1 || kt > 15) throw new IllegalArgumentException("мощность 1–15 кт");
                int i = 1;
                if (args.size() > i && List.of("air", "ground").contains(args.get(i))) i++;
                if (args.size() > i && args.get(i).equals("near")) {
                    i++;
                    if (args.size() > i && (number(i) < 100 || number(i) > 10000)) throw new IllegalArgumentException("дальность near 100–10000 м");
                    if (args.size() > i) i++;
                }
                if (args.size() > i) throw new IllegalArgumentException("nuke <кт> [air|ground] [near [метров]]");
            }
            case RAID -> {
                need(0, water() ? 5 : 4);
                if (args.size() > 3 && !(args.size() == 4 && water())) weaponAt(3);
                if (water() && number(1, 120) > POND / 2.0) throw new IllegalArgumentException("в пруд — радиус до " + POND / 2 + " м");
                if (number(0, 16) < 1 || number(0, 16) > 40) throw new IllegalArgumentException("взрывов 1–40");
                if (number(1, 120) < 10 || number(1, 120) > 400) throw new IllegalArgumentException("радиус 10–400 м");
                if (number(2, 3) < 0 || number(2, 3) > 30) throw new IllegalArgumentException("между взрывами 0–30 с");
            }
            case SAM -> need(0, 0);
            case AIRBURST -> {
                need(0, 1);
                if (!args.isEmpty()) seed();
            }
            case SPENT -> {
                need(1, 2);
                var booster = weapon().spec().airframe().article().booster();
                if (booster == null || booster.jettison() <= 0) throw new IllegalArgumentException("у оружия нет сбрасываемого ускорителя");
                if (args.size() > 1 && (number(1) < 0 || number(1) > 200)) throw new IllegalArgumentException("высота 0–200 м");
            }
            case SURFACE -> {
                need(1, 2);
                if (!(number(0) > 0 && number(0) <= 1)) throw new IllegalArgumentException("surface depth: 0 < depth <= 1");
                if (args.size() > 1 && !List.of("black", "gray").contains(args.get(1))) throw new IllegalArgumentException("surface <depth> [black|gray]");
            }
            case FIRE -> {
                need(0, 1);
                if (!args.isEmpty() && (number(0) < 1 || number(0) > 32)) throw new IllegalArgumentException("сторона пожара 1–32");
            }
            case CAM -> {
                need(1, 4);
                for (int i = 0; i < args.size(); i++) number(i);
            }
            case TIME -> {
                need(1, 1);
                // тик, как у ванильного time set: целое от 0
                if (!List.of("day", "night").contains(args.getFirst()) && (number(0) < 0 || number(0) > Integer.MAX_VALUE
                        || number(0) != Math.rint(number(0)))) throw new IllegalArgumentException("время — day, night или тик (целое от 0)");
            }
            case STANCE -> {
                need(1, 1);
                if (!List.of("stand", "fly").contains(args.getFirst())) throw new IllegalArgumentException("stand или fly");
            }
            case WEATHER -> {
                need(1, 1);
                if (!List.of("clear", "rain").contains(args.getFirst())) throw new IllegalArgumentException("clear или rain");
            }
            case HUD, OVERLAY, TRACK -> {
                need(1, 1);
                if (!List.of("on", "off").contains(args.getFirst())) throw new IllegalArgumentException("on или off");
            }
            case ZOOM -> {
                need(1, 1);
                if (number(0) < 1 || number(0) > 256) throw new IllegalArgumentException("зум 1–256");
            }
            case SHOTS -> {
                need(2, 3);
                if (!args.getFirst().matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("имя — буквы, цифры, _ . -");
                times();
                if (args.size() > 2 && (number(2) < 0 || number(2) > 120)) throw new IllegalArgumentException("звук 0–120 с");
            }
            case WAIT -> {
                need(1, 1);
                number(0);
            }
            case HOUSE -> {
                need(1, 3);
                if (args.size() > 2 && !args.get(2).matches("[a-z0-9_]+")) throw new IllegalArgumentException("верх — блок minecraft: " + args.get(2));
                if (number(0) < 0 || number(0) > 50) throw new IllegalArgumentException("высота дома 0–50");
                if (number(1, 10) < 1 || number(1, 10) > 12) throw new IllegalArgumentException("полуширина 1–12");
            }
            case SCENERY -> {
                need(1, 1);
                if (!List.of("grass", "paved", "forest", "roof").contains(args.getFirst())) throw new IllegalArgumentException("scenery grass|paved|forest|roof");
            }
            case AGAIN, CLEAR, RELOAD -> need(0, 0);
        }
    }

    /** {@code blast … water}, {@code raid … water}: удар в пруд. */
    boolean water() {
        return (verb == Verb.BLAST && args.size() > 1 || verb == Verb.RAID) && !args.isEmpty() && args.getLast().equals("water");
    }

    /** {@code raid}: оружие налёта (по умолчанию шахед). */
    WeaponType raidWeapon() {
        return args.size() > 3 && !args.get(3).equals("water") ? weaponAt(3) : WeaponType.DRONE;
    }

    /** {@code nuke}: воздушный подрыв. */
    boolean air() {
        return args.contains("air");
    }

    /** {@code nuke … near}: вид у камеры. */
    boolean near() {
        return verb == Verb.NUKE && args.contains("near");
    }

    /** {@code nuke … near [метров]}: дальность подрыва от домов. */
    double nearRange() {
        int i = args.indexOf("near");
        return i >= 0 && args.size() > i + 1 ? number(i + 1) : NEAR_RANGE;
    }

    /**
     * Приказ серверу для {@code launch}/{@code flight} в точке (x, z), без «execute as» ({@code own} добавляет его сам).
     * Пролёт — по прямой мимо камеры: без точек оператора шахед и ракета летят петлёй в обход и заходят из-за спины
     * стреляющего ({@code StrikeService.Loop}), и у точки проходили в 200–350 блоках, дальше, чем ждёт песочница. Поэтому
     * у оружия, которое летает по точкам ({@link WeaponSpec.Route#waypoints}), точка маршрута — сама точка, на одной
     * прямой между местом пуска и целью: снаряд проходит над ней на своей высоте. «Град» и B-2 точек не берут (приказ с
     * {@code via} сервер отклоняет) и идут по прямой сами: баллистика и курс сброса от места пуска на цель.
     */
    String order(int x, int z) {
        WeaponType w = weapon();
        if (verb == Verb.LAUNCH) return String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - LAUNCH_RANGE, x, z);
        if (verb != Verb.FLIGHT) throw new IllegalStateException("не пуск: " + this);
        int from = flightFrom();
        String s = String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - from, x, z + from);
        return w.spec().route().waypoints() ? s + String.format(Locale.ROOT, " via %d %d", x, z) : s;
    }

    /** {@code flight}: пуск и цель — на столько блоков от точки. */
    int flightFrom() {
        return args.size() > 1 && !args.get(1).equals("own") ? (int) number(1) : FLIGHT_FROM;
    }

    /** {@code real … deck <м>}: толщина настила моста, м; 0 — удар в землю. */
    int deck() {
        return verb == Verb.REAL && args.size() == 3 ? (int) number(2) : 0;
    }

    /** A metre of concrete resting on the existing earth, for supported-surface comparisons. */
    boolean paved() {
        return args.size() == 2 && args.get(1).equals("paved");
    }

    boolean concrete() {
        return args.size() == 3 && args.get(1).equals("concrete");
    }

    /** {@code real <оружие> houses}: удар у дощатого дома сцены {@code debris}. */
    boolean houses() {
        return verb == Verb.REAL && args.size() > 1 && args.get(1).equals("houses");
    }

    /** {@code real <оружие> roof}: удар в середину дощатой крыши дома сцены {@code debris}. */
    boolean roof() {
        return verb == Verb.REAL && args.size() > 1 && args.get(1).equals("roof");
    }

    WeaponType weapon() {
        return weaponAt(0);
    }

    WeaponType weaponAt(int i) {
        String name = args.get(i).toLowerCase(Locale.ROOT);
        for (WeaponType t : WeaponType.values()) {
            if (t.getSerializedName().equals(name)) return t;
        }
        throw new IllegalArgumentException("нет оружия " + name + " (drone, missile, bunker, rocket, loiter)");
    }

    long seed() {
        return seedAt(args.size() - 1);
    }

    /** Зерно — слово i. */
    long seedAt(int i) {
        try {
            return Long.parseLong(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("зерно — целое: " + args.get(i));
        }
    }

    double number(int i) {
        try {
            return Double.parseDouble(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("не число: " + args.get(i));
        }
    }

    double number(int i, double otherwise) {
        return args.size() > i ? number(i) : otherwise;
    }

    /** {@code shots}: секунды кадров по возрастанию. */
    double[] times() {
        String[] parts = args.get(1).split(",");
        double[] t = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                t[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("время кадра — секунды через запятую: " + args.get(1));
            }
            // до начала эффекта (−10…0 с) — только у эффекта, чьё начало известно заранее (nuke … near: приход фронта)
            if (t[i] < -10 || t[i] > 600) throw new IllegalArgumentException("время кадра −10…600 с");
            if (i > 0 && t[i] < t[i - 1]) throw new IllegalArgumentException("время кадров — по возрастанию");
        }
        return t;
    }

    private void need(int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalArgumentException(verb.name().toLowerCase(Locale.ROOT) + ": аргументов " + min + (max > min ? "–" + max : ""));
        }
    }

    @Override
    public String toString() {
        return (verb.name().toLowerCase(Locale.ROOT) + " " + String.join(" ", args)).strip();
    }
}
