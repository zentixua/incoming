package ua.zentix.incoming.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Area outage: districts centered within {@code radius} of the center darken in order outward from it
 * (cascade at {@code speed} blocks per tick, each district with its own ±25 % variation). Light returns either uniformly
 * (after {@code restoreAt}, in random order over {@code restoreSpread} ticks: a substation hit, a command) or, for a nuclear
 * strike, by what its blast did to each district ({@link #damageLightAt}). All are functions of district and time: both
 * the server and a chunk loaded an hour later know whether it should be dark.
 *
 * @param node      grid node whose failure caused this; -1 — not a node (nuclear strike, command)
 * @param restoreAt when to start restoring light uniformly; {@link #NEVER} — not uniformly (by damage, or by command)
 * @param dead      nuclear strike: ground range of {@link Blackouts#DEAD_PSI}, blocks — the grid there is destroyed
 * @param damaged   nuclear strike: ground range of {@link Blackouts#DAMAGED_PSI}, blocks; negative — not a nuclear
 *                  outage (no damage law)
 */
public record Outage(int id, double x, double z, double radius, long start, double speed, long restoreAt, int restoreSpread, int node,
                     double dead, double damaged) {
    public static final long NEVER = Long.MAX_VALUE;
    /** District darkening-time variation: ±25 % of cascade arrival. The same variation applies to repair times. */
    private static final double SPREAD = 0.5;
    /** Game clock: 1000 ticks are one hour (a Minecraft day is 24 hours), as for fallout and radiation. */
    public static final double TICKS_PER_HOUR = 1000;
    /**
     * Grid outside the damage (below {@link Blackouts#DAMAGED_PSI}): nothing is broken, protection tripped as the city's
     * load and lines vanished. Dispatchers bring it back section by section, the far edge first, the districts next to the
     * damaged belt last: hours, at most about a day. Reference: 14 August 2003 US–Canada blackout, 50 million people,
     * most customers back within about a day, New York City fully after 29 hours (US–Canada Power System Outage Task
     * Force, final report, 2004). Range (est.).
     */
    static final double TRIPPED_FIRST_HOURS = 2, TRIPPED_LAST_HOURS = 24;
    /**
     * Damaged belt ({@link Blackouts#DAMAGED_PSI}–{@link Blackouts#DEAD_PSI}): poles broken, lines down under debris, as after
     * a hurricane; crews rebuild the distribution network street by street: days, longest next to the destroyed zone.
     * Reference: Hurricane Sandy 2012, 8.5 million customers, about 95 % restored within two weeks (US DOE situation
     * reports). Range (est.).
     */
    static final double DAMAGED_FIRST_HOURS = 2 * 24, DAMAGED_LAST_HOURS = 14 * 24;

    /** Outage with uniform restoration only (substation, command). */
    public Outage(int id, double x, double z, double radius, long start, double speed, long restoreAt, int restoreSpread, int node) {
        this(id, x, z, radius, start, speed, restoreAt, restoreSpread, node, -1, -1);
    }

    public static final Codec<Outage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Outage::id),
            Codec.DOUBLE.fieldOf("x").forGetter(Outage::x),
            Codec.DOUBLE.fieldOf("z").forGetter(Outage::z),
            Codec.DOUBLE.fieldOf("radius").forGetter(Outage::radius),
            Codec.LONG.fieldOf("start").forGetter(Outage::start),
            Codec.DOUBLE.fieldOf("speed").forGetter(Outage::speed),
            Codec.LONG.fieldOf("restore_at").forGetter(Outage::restoreAt),
            Codec.INT.fieldOf("restore_spread").forGetter(Outage::restoreSpread),
            Codec.INT.optionalFieldOf("node", -1).forGetter(Outage::node),
            Codec.DOUBLE.optionalFieldOf("dead", -1.0).forGetter(Outage::dead),
            Codec.DOUBLE.optionalFieldOf("damaged", -1.0).forGetter(Outage::damaged)
    ).apply(i, Outage::new));

    /** District is inside the outage area. */
    public boolean covers(long district) {
        return covers(x, z, radius, district);
    }

    /** District is inside area centered at {@code (x, z)} with {@code radius} (client: lamp EMP arcs). */
    public static boolean covers(double x, double z, double radius, long district) {
        double dx = Districts.seedX(district) - x, dz = Districts.seedZ(district) - z;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** District center distance from the outage center, blocks. */
    private double distance(long district) {
        return Math.hypot(Districts.seedX(district) - x, Districts.seedZ(district) - z);
    }

    /** When district goes dark (game time). */
    public long darkAt(long district) {
        return start + Math.round(distance(district) / speed * (1 - SPREAD / 2 + SPREAD * Districts.unit(district, 3)));
    }

    /** When light returns to district; {@link #NEVER} — never. */
    public long lightAt(long district) {
        long uniform = restoreAt == NEVER ? NEVER : restoreAt + Math.round(restoreSpread * Districts.unit(district, 4));
        return Math.min(uniform, damageLightAt(district));
    }

    /** The outage follows a nuclear strike's damage ({@link #damageLightAt}). */
    public boolean byDamage() {
        return damaged >= 0;
    }

    /**
     * Repair by damage (nuclear strike): where the blast destroyed the grid ({@link #dead}), power never comes back by
     * itself — light there is what players build anew; in the damaged belt it returns after days, outside it after hours,
     * linearly with distance between the zone edges (the far edge first), each district ±25 %. {@link #NEVER} for an
     * outage without the damage law.
     */
    public long damageLightAt(long district) {
        if (!byDamage()) return NEVER;
        double d = distance(district);
        if (d <= dead) return NEVER;
        double hours;
        if (d <= damaged) {
            hours = lerp(DAMAGED_LAST_HOURS, DAMAGED_FIRST_HOURS, (d - dead) / Math.max(1, damaged - dead));
        } else {
            hours = lerp(TRIPPED_LAST_HOURS, TRIPPED_FIRST_HOURS, (d - damaged) / Math.max(1, radius - damaged));
        }
        return start + Math.round(hours * TICKS_PER_HOUR * (1 - SPREAD / 2 + SPREAD * Districts.unit(district, 4)));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * Math.clamp(t, 0, 1);
    }

    /** Earliest moment any district can get light back (restoration cascade starts then); {@link #NEVER} — never. */
    public long restoreFrom() {
        long damage = byDamage() ? start + Math.round(TRIPPED_FIRST_HOURS * TICKS_PER_HOUR * (1 - SPREAD / 2)) : NEVER;
        return Math.min(restoreAt, damage);
    }

    /** District is dark from this outage at {@code now}. */
    public boolean dark(long district, long now) {
        return covers(district) && darkAt(district) <= now && now < lightAt(district);
    }

    /** Light returned to every district: outage can be forgotten. */
    public boolean over(long now) {
        if (restoreAt != NEVER && now >= restoreAt + restoreSpread) return true;
        // by damage without a destroyed district (high burst, small yield): over once the longest repair is done
        return byDamage() && now > rerouteAt() && !anyDestroyed();
    }

    /**
     * Some covered district lies in the destroyed zone (its center within {@link #dead}): otherwise every district gets
     * light back and the outage ends. A destroyed zone smaller than a district may hold no district center at all.
     */
    boolean anyDestroyed() {
        if (dead <= 0) return false;
        // the center chunk's own district first: its center is within about a cell of the burst
        if (distance(Districts.of(Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16))) <= dead) return true;
        double cell = Districts.CELL * 16;
        int cx = (int) Math.floor(x / cell), cz = (int) Math.floor(z / cell), k = (int) Math.ceil(dead / cell) + 1;
        for (int dx = -k; dx <= k; dx++) {
            for (int dz = -k; dz <= k; dz++) {
                long d = Districts.key(cx + dx, cz + dz);
                if (distance(d) <= dead && covers(d)) return true;
            }
        }
        return false;
    }

    /**
     * End of the longest damaged-belt repair: by then supply around a destroyed substation is rerouted (est.: the same crews,
     * the same weeks), so a substation the blast destroyed does not keep its own area beyond the destroyed zone dark forever.
     */
    public long rerouteAt() {
        return start + Math.round(DAMAGED_LAST_HOURS * TICKS_PER_HOUR * (1 + SPREAD / 2));
    }

    /** Same outage with uniform light restoration starting at {@code at}, over {@code spread} ticks (overrides the damage law where sooner). */
    public Outage restoring(long at, int spread) {
        return new Outage(id, x, z, radius, start, speed, at, spread, node, dead, damaged);
    }

    /** Latest district darkening time in this area (cascade reached its edge). */
    public long lastDark() {
        return start + Math.round(radius / speed * (1 + SPREAD / 2)) + 1;
    }
}
