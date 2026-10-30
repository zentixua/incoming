package ua.zentix.incoming.scenario;

import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command of the effects sandbox ({@link Sandbox}, {@code /fx …} in chat or a line of the file {@code fx/inbox/*.cmd}), without
 * Minecraft: parsing a line into an action. Words:
 * <ul>
 *   <li>{@code blast <weapon> [seed] [height]} — an explosion on the client only: the explosion packet at the sandbox point, as from
 *       the server (picture and sound; no server craters, fire or debris — the ground stays, the explosion can be repeated); height —
 *       above the ground, m (a strike on a roof — at the height of the {@code house}, otherwise — a point in the air above the grass); the last word
 *       {@code water} — a strike into a pond {@link #POND}×{@link #POND} blocks, depth {@link #POND_DEPTH} (water on the server and
 *       client, the ground in the packet — water; {@code clear} returns the grass);</li>
 *   <li>{@code scenery grass|paved|forest|roof} — the surface and the same forest for comparing dust (first remove the house); roof —
 *   a plank roof a meter above an empty room 21×21 and 3 m high (walls and floor of stone brick): a strike {@code blast <weapon> 1 5} — on the roof,
 *   a slab over the void;</li>
 *   <li>{@code house <height> [half-width] [top]} — a stone brick house under the point (height 0 — remove), by
 *       default 21×21 blocks; top — the roof block ({@code coarse_dirt} — a garden roof with loose earth);</li>
 *   <li>{@code real <weapon> [deck <m> | houses | roof]} — a real warhead explosion on the server (crater, fires, debris)
 *   at a new place; {@code deck} — a strike at the middle of a bridge: a stone deck that many meters above a riverbed with water (slab
 *   breach); {@code houses} — at the plank house of the {@code debris} scene (houses and oaks nearby): what catches fire and how it smokes;
 *   {@code roof} — the same houses, a strike at the middle of the plank roof: where it falls and where the fuel burns; {@code masonry} — the same houses,
 *   a strike at the middle of the concrete roof of a stone brick house, with a paved road around (city, ±48 m): dust of the breached slab;
 *   {@code town} — a stone brick block on paving (houses 10×10×12 m, streets 6 m wide along X and Z, ±48 m), a strike at
 *   the intersection: wind at ground level in the street ({@code LocalWind});</li>
 *   <li>{@code nuke <kt> [air|ground] [near [meters]]} — a nuclear detonation without flight ({@code /incoming nuke now}) at a new
 *       place; with {@code near} — the view from the camera: the houses and oaks of the {@code debris} scene at the point, the detonation that many meters to the north
 *       (by default {@link #NEAR_RANGE}), effect time — from the arrival of the front at the houses (frames also before it, from −10 s));</li>
 *   <li>{@code airburst [seed]} — the burst of an anti-aircraft missile in the air above the point;</li>
 *   <li>{@code sam} — a SAM at the point (loaded) and a Shahed flying through it from the north; time — from the launch of the anti-aircraft missile
 *       (trail, launch cloud, engine sound — like the {@code sam-side} scene of the probe);</li>
 *   <li>{@code raid [count] [radius] [seconds between] [weapon] [water]} — a raid: explosions (by default Shaheds) only at the
 *       client (like {@code blast}) in a circle of the radius around the point, one after another (by default 16 in 45 s at 120 m) — the cost of
 *       the smoke of many strikes in the frame; {@code water} — into the pond, as with {@code blast} (a Grad salvo on the water by the shore: the radius —
 *       no more than half the pond);</li>
 *   <li>{@code launch <weapon>} — a launch from a launcher at the point; time — from the first movement of the projectile;</li>
 *   <li>{@code flight <weapon> [from, blocks]} — a projectile passes over the point in a straight line: a launch that many blocks to the south
 *       (by default {@link #FLIGHT_FROM}), the target — as many to the north; for a weapon with an operator route (Shahed, missile,
 *       Lancet) the route point is the point itself ({@link #order}); time — from the nearest place to it; for {@code launch} and
 *       {@code flight} the last word {@code own} — a launch by the player (own projectiles: the list and marks on screen);</li>
 *   <li>{@code spent <weapon> [height]} — a spent booster ({@code SpentBoosterEntity}) at the height above the point (by
 *       default 20 m): it falls tumbling and lies; time — from the end of its engine's work (hot nozzle, {@code NozzleGlow});</li>
 *   <li>{@code fire [side]} — a fire: a square of netherrack with fire at the point;</li>
 *   <li>{@code cam <distance> [height] [azimuth°] [look]} — where the camera is: distance from the point, height above the ground, azimuth
 *       from the point (0 — south, 90 — west, as the player's turn), height of the look point above the ground;</li>
 *   <li>{@code stance stand|fly} — a standing player (body sway) or a flying observer (by default);</li>
 *   <li>{@code weather clear|rain} — weather for checking condensation in humid air;</li>
 *   <li>{@code time day|night|<tick>}, {@code hud on|off}, {@code overlay on|off} — light, interface, model numbers;</li>
 *   <li>{@code track on|off} — the view keeps the effect's projectile ({@code launch}/{@code flight}) in the middle of the frame (as a player
 *       who follows it with zoom);</li>
 *   <li>{@code turn <deg/s> [from s]} — the view turns at this rate from that effect second, by the effect clock (camera-turn
 *       clips; {@code turn 0} stops);</li>
 *   <li>{@code zoom <k>} — zoom ×k (1–256; 1 — no zoom): the field of view k times narrower (tangent of the half-angle), as in Ok Zoomer, —
 *       last, after all who change it;</li>
 *   <li>{@code shots <name> <seconds, comma-separated> [wav]} — frames and sound of the next effect: frames at those seconds from
 *       its start, sound — that many seconds ({@code wav}, 0 — no sound) in {@code fx/out/<name>/};</li>
 *   <li>{@code again} — the last effect once more (after a hot code swap), {@code clear} — remove all effects,
 *       {@code reload} — reload resources (like F3+T: shaders, textures, sounds, language), {@code wait <ticks>}.</li>
 * </ul>
 */
record SandboxCommand(Verb verb, List<String> args) {
    /** {@code nuke … near}: detonation distance from the houses by default — as in the {@code debris} scene (2 km). */
    static final double NEAR_RANGE = 2000;
    /** {@code launch}: the target — that many blocks north of the point (−Z). */
    static final int LAUNCH_RANGE = 3000;
    /** {@code flight}: the launch — that many blocks south of the point (+Z), the target — as many to the north (−Z). */
    static final int FLIGHT_FROM = 600;
    /** {@code blast … water}: side and depth of the pond, blocks (the pond is wider than the Shahed's column and spray ring). */
    static final int POND = 64, POND_DEPTH = 4;

    enum Verb { BLAST, REAL, NUKE, AIRBURST, LAUNCH, FLIGHT, FIRE, SPENT, CAM, STANCE, WEATHER, TIME, HUD, OVERLAY, ZOOM, TRACK, SHOTS, AGAIN, CLEAR, RELOAD, WAIT, RAID, SAM, HOUSE, SCENERY, SURFACE, TURN }

    /** An effect (repeated by {@code again} and captured by {@code shots}). */
    boolean effect() {
        return switch (verb) {
            case BLAST, REAL, NUKE, AIRBURST, LAUNCH, FLIGHT, FIRE, SPENT, RAID, SAM, SURFACE -> true;
            default -> false;
        };
    }

    /** A launch by the player ({@code own} as the last word): the player owns the projectile — the list and marks of projectiles on screen, as in the game. */
    boolean own() {
        return (verb == Verb.LAUNCH || verb == Verb.FLIGHT) && args.size() > 1 && args.getLast().equals("own");
    }

    static SandboxCommand parse(String line) {
        String s = line.strip();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("fx ")) s = s.substring(3).strip();
        String[] w = s.split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) throw new IllegalArgumentException("empty command");
        Verb verb;
        try {
            verb = Verb.valueOf(w[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("no command " + w[0]);
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
                if (w == WeaponType.NUKE) throw new IllegalArgumentException("for a nuke use nuke <kt>");
                if (verb == Verb.REAL) {
                    if (houses() || roof() || masonry() || town() || paved() ? args.size() != 2
                            : args.size() > 1 && (args.size() != 3 || !List.of("deck", "concrete").contains(args.get(1)) || number(2) < 1 || number(2) > 6)) {
                        throw new IllegalArgumentException("real <weapon> [deck|concrete <1–6 m> | houses | roof | masonry | town | paved]");
                    }
                    break;
                }
                int n = args.size() - (water() ? 1 : 0);
                if (n > 1) seedAt(1);
                if (n > 2 && (number(2) < 0 || number(2) > 200)) throw new IllegalArgumentException("height 0–200 m");
            }
            case LAUNCH, FLIGHT -> {
                need(1, own() ? 3 : 2);
                weapon();
                if (args.size() > (own() ? 2 : 1) && !(number(1) >= 50 && number(1) <= 5000)) throw new IllegalArgumentException("from the point 50–5000 blocks");
            }
            case NUKE -> {
                need(1, 4);
                int kt = (int) number(0);
                if (kt < 1 || kt > 15) throw new IllegalArgumentException("yield 1–15 kt");
                int i = 1;
                if (args.size() > i && List.of("air", "ground").contains(args.get(i))) i++;
                if (args.size() > i && args.get(i).equals("near")) {
                    i++;
                    if (args.size() > i && (number(i) < 100 || number(i) > 10000)) throw new IllegalArgumentException("near distance 100–10000 m");
                    if (args.size() > i) i++;
                }
                if (args.size() > i) throw new IllegalArgumentException("nuke <kt> [air|ground] [near [meters]]");
            }
            case RAID -> {
                need(0, water() ? 5 : 4);
                if (args.size() > 3 && !(args.size() == 4 && water())) weaponAt(3);
                if (water() && number(1, 120) > POND / 2.0) throw new IllegalArgumentException("into the pond — radius up to " + POND / 2 + " m");
                if (number(0, 16) < 1 || number(0, 16) > 40) throw new IllegalArgumentException("explosions 1–40");
                if (number(1, 120) < 10 || number(1, 120) > 400) throw new IllegalArgumentException("radius 10–400 m");
                if (number(2, 3) < 0 || number(2, 3) > 30) throw new IllegalArgumentException("between explosions 0–30 s");
            }
            case SAM -> need(0, 0);
            case AIRBURST -> {
                need(0, 1);
                if (!args.isEmpty()) seed();
            }
            case SPENT -> {
                need(1, 2);
                var booster = weapon().spec().airframe().article().booster();
                if (booster == null || booster.jettison() <= 0) throw new IllegalArgumentException("this weapon has no jettisonable booster");
                if (args.size() > 1 && (number(1) < 0 || number(1) > 200)) throw new IllegalArgumentException("height 0–200 m");
            }
            case SURFACE -> {
                need(1, 2);
                if (!(number(0) > 0 && number(0) <= 1)) throw new IllegalArgumentException("surface depth: 0 < depth <= 1");
                if (args.size() > 1 && !List.of("black", "gray").contains(args.get(1))) throw new IllegalArgumentException("surface <depth> [black|gray]");
            }
            case FIRE -> {
                need(0, 1);
                if (!args.isEmpty() && (number(0) < 1 || number(0) > 32)) throw new IllegalArgumentException("fire side 1–32");
            }
            case CAM -> {
                need(1, 4);
                for (int i = 0; i < args.size(); i++) number(i);
            }
            case TIME -> {
                need(1, 1);
                // a tick, as in vanilla time set: an integer from 0
                if (!List.of("day", "night").contains(args.getFirst()) && (number(0) < 0 || number(0) > Integer.MAX_VALUE
                        || number(0) != Math.rint(number(0)))) throw new IllegalArgumentException("time — day, night or a tick (integer from 0)");
            }
            case STANCE -> {
                need(1, 1);
                if (!List.of("stand", "fly").contains(args.getFirst())) throw new IllegalArgumentException("stand or fly");
            }
            case WEATHER -> {
                need(1, 1);
                if (!List.of("clear", "rain").contains(args.getFirst())) throw new IllegalArgumentException("clear or rain");
            }
            case HUD, OVERLAY, TRACK -> {
                need(1, 1);
                if (!List.of("on", "off").contains(args.getFirst())) throw new IllegalArgumentException("on or off");
            }
            case ZOOM -> {
                need(1, 1);
                if (number(0) < 1 || number(0) > 256) throw new IllegalArgumentException("zoom 1–256");
            }
            case TURN -> {
                need(1, 2);
                if (Math.abs(number(0)) > 720) throw new IllegalArgumentException("turn rate -720..720 degrees per second");
                if (args.size() > 1 && number(1) < 0) throw new IllegalArgumentException("turn start >= 0 s");
            }
            case SHOTS -> {
                need(2, 3);
                if (!args.getFirst().matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("name — letters, digits, _ . -");
                times();
                if (args.size() > 2 && (number(2) < 0 || number(2) > 120)) throw new IllegalArgumentException("sound 0–120 s");
            }
            case WAIT -> {
                need(1, 1);
                number(0);
            }
            case HOUSE -> {
                need(1, 3);
                if (args.size() > 2 && !args.get(2).matches("[a-z0-9_]+")) throw new IllegalArgumentException("top — a minecraft block: " + args.get(2));
                if (number(0) < 0 || number(0) > 50) throw new IllegalArgumentException("house height 0–50");
                if (number(1, 10) < 1 || number(1, 10) > 12) throw new IllegalArgumentException("half-width 1–12");
            }
            case SCENERY -> {
                need(1, 1);
                if (!List.of("grass", "paved", "forest", "roof").contains(args.getFirst())) throw new IllegalArgumentException("scenery grass|paved|forest|roof");
            }
            case AGAIN, CLEAR, RELOAD -> need(0, 0);
        }
    }

    /** {@code blast … water}, {@code raid … water}: a strike into the pond. */
    boolean water() {
        return (verb == Verb.BLAST && args.size() > 1 || verb == Verb.RAID) && !args.isEmpty() && args.getLast().equals("water");
    }

    /** {@code raid}: the raid's weapon (by default Shahed). */
    WeaponType raidWeapon() {
        return args.size() > 3 && !args.get(3).equals("water") ? weaponAt(3) : WeaponType.DRONE;
    }

    /** {@code nuke}: an air burst. */
    boolean air() {
        return args.contains("air");
    }

    /** {@code nuke … near}: the view from the camera. */
    boolean near() {
        return verb == Verb.NUKE && args.contains("near");
    }

    /** {@code nuke … near [meters]}: detonation distance from the houses. */
    double nearRange() {
        int i = args.indexOf("near");
        return i >= 0 && args.size() > i + 1 ? number(i + 1) : NEAR_RANGE;
    }

    /**
     * Order to the server for {@code launch}/{@code flight} at the point (x, z), without "execute as" ({@code own} adds it itself).
     * A flyover — in a straight line past the camera: without the operator's points a Shahed and a missile fly a loop around and
     * approach from behind the shooter ({@code StrikeService.Loop}), and pass the point at 200–350 blocks, farther than the sandbox
     * expects. Therefore for a weapon that flies through points ({@link WeaponSpec.Route#waypoints}), the route point is the point
     * itself, on one straight line between the launch place and the target: the projectile passes over it at its own height.
     * Grad and B-2 take no points (the server rejects an order with {@code via}) and go straight on their own: ballistics and
     * the release course from the launch place to the target.
     */
    String order(int x, int z) {
        WeaponType w = weapon();
        if (verb == Verb.LAUNCH) return String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - LAUNCH_RANGE, x, z);
        if (verb != Verb.FLIGHT) throw new IllegalStateException("not a launch: " + this);
        int from = flightFrom();
        String s = String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - from, x, z + from);
        return w.spec().route().waypoints() ? s + String.format(Locale.ROOT, " via %d %d", x, z) : s;
    }

    /** {@code flight}: the launch and the target — that many blocks from the point. */
    int flightFrom() {
        return args.size() > 1 && !args.get(1).equals("own") ? (int) number(1) : FLIGHT_FROM;
    }

    /** {@code real … deck <m>}: thickness of the bridge deck, m; 0 — a strike on the ground. */
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

    /** {@code real <weapon> houses}: a strike at the plank house of the {@code debris} scene. */
    boolean houses() {
        return verb == Verb.REAL && args.size() > 1 && args.get(1).equals("houses");
    }

    /** {@code real <weapon> roof}: a strike at the middle of the plank roof of the house in the {@code debris} scene. */
    boolean roof() {
        return verb == Verb.REAL && args.size() > 1 && args.get(1).equals("roof");
    }

    /** {@code real … masonry}: the same houses on paving, a strike at the middle of the concrete roof of a stone brick house. */
    boolean masonry() {
        return verb == Verb.REAL && args.size() > 1 && args.get(1).equals("masonry");
    }

    /** {@code real … town}: a block on paving (houses 10×10×12 m, streets 6 m wide), a strike at the intersection. */
    boolean town() {
        return verb == Verb.REAL && args.size() > 1 && args.get(1).equals("town");
    }

    WeaponType weapon() {
        return weaponAt(0);
    }

    WeaponType weaponAt(int i) {
        String name = args.get(i).toLowerCase(Locale.ROOT);
        for (WeaponType t : WeaponType.values()) {
            if (t.getSerializedName().equals(name)) return t;
        }
        throw new IllegalArgumentException("no weapon " + name + " (drone, missile, bunker, rocket, loiter)");
    }

    long seed() {
        return seedAt(args.size() - 1);
    }

    /** Seed — word i. */
    long seedAt(int i) {
        try {
            return Long.parseLong(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("seed — an integer: " + args.get(i));
        }
    }

    double number(int i) {
        try {
            return Double.parseDouble(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + args.get(i));
        }
    }

    double number(int i, double otherwise) {
        return args.size() > i ? number(i) : otherwise;
    }

    /** {@code shots}: frame seconds in ascending order. */
    double[] times() {
        String[] parts = args.get(1).split(",");
        double[] t = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                t[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("frame time — seconds separated by commas: " + args.get(1));
            }
            // before the effect starts (−10…0 s) — only for an effect whose start is known in advance (nuke … near: the front's arrival)
            if (t[i] < -10 || t[i] > 600) throw new IllegalArgumentException("frame time −10…600 s");
            if (i > 0 && t[i] < t[i - 1]) throw new IllegalArgumentException("frame times must ascend");
        }
        return t;
    }

    private void need(int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalArgumentException(verb.name().toLowerCase(Locale.ROOT) + ": arguments " + min + (max > min ? "–" + max : ""));
        }
    }

    @Override
    public String toString() {
        return (verb.name().toLowerCase(Locale.ROOT) + " " + String.join(" ", args)).strip();
    }
}
