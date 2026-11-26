package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.RecoveryClock;
import ua.zentix.incoming.util.WorldClock;

/**
 * A body's recovery clock ({@link RecoveryClock}, {@code docs/design/time.md} §8.3 rule 8): radiation sickness and burns
 * run on it. Real rate while a detonation that acted on the body is in its show, then the world's rate through the rate
 * blend; every jump of its dimension counts (a sleep heals as a night does). It moves only while the body is in the world:
 * a player offline or a mob in an unloaded chunk is not stepped, and the first step back counts nothing, so a night
 * offline brings no one to the fatal stage. Players are stepped once a second ({@link #tick}), mobs by their own sickness
 * and burn effects.
 */
public final class BodyClock {
    private BodyClock() {}

    /** The body's clock, as last stepped. */
    static RecoveryClock of(LivingEntity e) {
        return e.getData(ModAttachments.BODY_CLOCK);
    }

    /** Step the body's clock to now (once per game tick at most). */
    public static void step(LivingEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return;
        NuclearEvents events = NuclearEvents.get(level);
        step(e, id -> events.showEnd(id, level));
    }

    /** Step with detonation shows read from {@code shows}. */
    static void step(LivingEntity e, RecoveryClock.Shows shows) {
        if (!(e.level() instanceof ServerLevel level)) return;
        of(e).step(level.getGameTime(), WorldClock.rateClock(level), WorldClock.counted(level), WorldClock.unseen(level), level.dimension(), shows);
    }

    /** The body's recovery time now, world-clock ticks (1000 an hour). */
    public static double now(LivingEntity e) {
        step(e);
        return of(e).clock();
    }

    /** Recovery over the last second ({@link WorldClock#PACE_TICKS} game ticks), world-clock ticks: paces effect durations. */
    public static double lastSecond(LivingEntity e) {
        step(e);
        return of(e).over(WorldClock.PACE_TICKS);
    }

    /** Detonation {@code d} acted on the body (dose, rain, burn): it holds the body's clock at real rate during its show. */
    public static void cause(LivingEntity e, Detonation d) {
        step(e);
        of(e).strike(d.id());
    }

    /** Once a second: every player in {@code level} (spectators too: their body is in the world). */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % WorldClock.PACE_TICKS != 0) return;
        for (ServerPlayer p : level.players()) step(p);
    }
}
