package ua.zentix.incoming.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Area outage: districts centered within {@code radius} of the center darken in order outward from it
 * (cascade at {@code speed} blocks per tick, each district with its own ±25 % variation), then after
 * {@code restoreAt} relight in random order over {@code restoreSpread} ticks. All are functions of district and time:
 * both the server and a chunk loaded an hour later know whether it should be dark.
 *
 * @param node      grid node whose failure caused this; -1 — not a node (nuclear strike, command)
 * @param restoreAt when to start restoring light; {@link #NEVER} — until restored by command
 */
public record Outage(int id, double x, double z, double radius, long start, double speed, long restoreAt, int restoreSpread, int node) {
    public static final long NEVER = Long.MAX_VALUE;
    /** District darkening-time variation: ±25 % of cascade arrival. */
    private static final double SPREAD = 0.5;

    public static final Codec<Outage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Outage::id),
            Codec.DOUBLE.fieldOf("x").forGetter(Outage::x),
            Codec.DOUBLE.fieldOf("z").forGetter(Outage::z),
            Codec.DOUBLE.fieldOf("radius").forGetter(Outage::radius),
            Codec.LONG.fieldOf("start").forGetter(Outage::start),
            Codec.DOUBLE.fieldOf("speed").forGetter(Outage::speed),
            Codec.LONG.fieldOf("restore_at").forGetter(Outage::restoreAt),
            Codec.INT.fieldOf("restore_spread").forGetter(Outage::restoreSpread),
            Codec.INT.optionalFieldOf("node", -1).forGetter(Outage::node)
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

    /** When district goes dark (game time). */
    public long darkAt(long district) {
        double d = Math.hypot(Districts.seedX(district) - x, Districts.seedZ(district) - z);
        return start + Math.round(d / speed * (1 - SPREAD / 2 + SPREAD * Districts.unit(district, 3)));
    }

    /** When light returns to district; {@link #NEVER} — never. */
    public long lightAt(long district) {
        return restoreAt == NEVER ? NEVER : restoreAt + Math.round(restoreSpread * Districts.unit(district, 4));
    }

    /** District is dark from this outage at {@code now}. */
    public boolean dark(long district, long now) {
        return covers(district) && darkAt(district) <= now && now < lightAt(district);
    }

    /** Light returned to every district: outage can be forgotten. */
    public boolean over(long now) {
        return restoreAt != NEVER && now >= restoreAt + restoreSpread;
    }

    /** Same outage with light restoration starting at {@code at}, over {@code spread} ticks. */
    public Outage restoring(long at, int spread) {
        return new Outage(id, x, z, radius, start, speed, at, spread, node);
    }

    /** Latest district darkening time in this area (cascade reached its edge). */
    public long lastDark() {
        return start + Math.round(radius / speed * (1 + SPREAD / 2)) + 1;
    }
}
