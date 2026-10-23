package ua.zentix.incoming.scenario;

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
 *       над землёй, м: удар в крышу или стену (земля в пакете — трава под точкой);</li>
 *   <li>{@code real <оружие>} — настоящий взрыв боевой части на сервере (воронка, пожары, обломки) на новом месте;</li>
 *   <li>{@code nuke <кт> [air|ground]} — ядерный подрыв без полёта ({@code /incoming nuke now}) на новом месте;</li>
 *   <li>{@code airburst [seed]} — разрыв зенитной ракеты в воздухе над точкой;</li>
 *   <li>{@code raid [число] [радиус] [секунд между]} — налёт: взрывы шахедов только у клиента (как {@code blast}) в круге
 *       радиуса вокруг точки один за другим (по умолчанию 16 за 45 с в 120 м) — цена дыма многих ударов в кадре;</li>
 *   <li>{@code launch <оружие>} — пуск с пусковой в точке; время — от первого движения снаряда;</li>
 *   <li>{@code flight <оружие> [от, блоков]} — снаряд проходит над точкой; время — от ближайшего к ней места;</li>
 *   <li>{@code fire [сторона]} — пожар: квадрат незерака с огнём в точке;</li>
 *   <li>{@code cam <дальность> [высота] [азимут°] [взгляд]} — где камера: дальность от точки, высота над землёй, азимут
 *       от точки (0 — юг, 90 — запад, как поворот игрока), высота точки взгляда над землёй;</li>
 *   <li>{@code time day|night|<тик>}, {@code hud on|off}, {@code overlay on|off} — свет, интерфейс, числа модели;</li>
 *   <li>{@code shots <имя> <секунды через запятую> [wav]} — кадры и звук следующего эффекта: кадры на эти секунды от
 *       его начала, звук — столько секунд ({@code wav}, 0 — без звука) в {@code fx/out/<имя>/};</li>
 *   <li>{@code again} — последний эффект ещё раз (после горячей замены кода), {@code clear} — снять все эффекты,
 *       {@code reload} — перезагрузка ресурсов (как F3+T: шейдеры, текстуры, звуки, язык), {@code wait <тиков>}.</li>
 * </ul>
 */
record SandboxCommand(Verb verb, List<String> args) {
    enum Verb { BLAST, REAL, NUKE, AIRBURST, LAUNCH, FLIGHT, FIRE, CAM, TIME, HUD, OVERLAY, SHOTS, AGAIN, CLEAR, RELOAD, WAIT, RAID }

    /** Эффект (его повторяет {@code again} и снимает {@code shots}). */
    boolean effect() {
        return switch (verb) {
            case BLAST, REAL, NUKE, AIRBURST, LAUNCH, FLIGHT, FIRE, RAID -> true;
            default -> false;
        };
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
                need(1, verb == Verb.BLAST ? 3 : 1);
                WeaponType w = weapon();
                if (w == WeaponType.NUKE) throw new IllegalArgumentException("ядерка — nuke <кт>");
                if (verb == Verb.BLAST && args.size() > 1) seedAt(1);
                if (args.size() > 2 && (number(2) < 0 || number(2) > 200)) throw new IllegalArgumentException("высота 0–200 м");
            }
            case LAUNCH, FLIGHT -> {
                need(1, 2);
                weapon();
                if (args.size() > 1) number(1);
            }
            case NUKE -> {
                need(1, 2);
                int kt = (int) number(0);
                if (kt < 1 || kt > 15) throw new IllegalArgumentException("мощность 1–15 кт");
                if (args.size() > 1 && !List.of("air", "ground").contains(args.get(1))) throw new IllegalArgumentException("air или ground");
            }
            case RAID -> {
                need(0, 3);
                if (number(0, 16) < 1 || number(0, 16) > 40) throw new IllegalArgumentException("взрывов 1–40");
                if (number(1, 120) < 10 || number(1, 120) > 400) throw new IllegalArgumentException("радиус 10–400 м");
                if (number(2, 3) < 0 || number(2, 3) > 30) throw new IllegalArgumentException("между взрывами 0–30 с");
            }
            case AIRBURST -> {
                need(0, 1);
                if (!args.isEmpty()) seed();
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
                if (!List.of("day", "night").contains(args.getFirst())) number(0);
            }
            case HUD, OVERLAY -> {
                need(1, 1);
                if (!List.of("on", "off").contains(args.getFirst())) throw new IllegalArgumentException("on или off");
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
            case AGAIN, CLEAR, RELOAD -> need(0, 0);
        }
    }

    WeaponType weapon() {
        String name = args.getFirst().toLowerCase(Locale.ROOT);
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
            if (t[i] < 0 || t[i] > 600) throw new IllegalArgumentException("время кадра 0–600 с");
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
