package ua.zentix.incoming.defense;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.target.Sides;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Radar air alert: when a side's radar sees a new hostile target, players on that side in the same world receive
 * an “Air alert” line with what is flying, where from (compass direction from each player), and how far away,
 * plus a short signal. One target means one alert per side, however many radars see it; a target unseen by that
 * side's radars for {@link #FORGET} ticks is forgotten and triggers another alert if it reappears.
 */
public final class AirAlert {
    /** A target unseen by the side's radars for this many ticks is forgotten. */
    static final int FORGET = 200;
    /** Signal the side at most once per this many ticks (one line per new target). */
    static final int SOUND_GAP = 100;
    private static final String[] COMPASS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

    private AirAlert() {}

    /**
     * Record that side {@code side}'s radar sees {@code tracks}, and announce new hostile targets.
     *
     * @param owner whose radar: their side ({@link Sides}) receives the alert; null means unowned, with no alert
     */
    public static void sweep(ServerLevel level, @Nullable UUID owner, @Nullable String side, List<Radar.Track> tracks) {
        if (owner == null || side == null) return;
        DefenseWorld world = DefenseWorld.get(level);
        long now = level.getGameTime();
        Map<UUID, Long> seen = world.alerted(side);
        List<Radar.Track> fresh = new ArrayList<>();
        for (Radar.Track t : tracks) {
            if (t.hostile() && seen.put(t.id(), now) == null) fresh.add(t);
        }
        seen.values().removeIf(t -> now - t > FORGET);
        if (fresh.isEmpty()) return;
        boolean sound = world.alertSound(side, now, SOUND_GAP);
        MinecraftServer server = level.getServer();
        for (ServerPlayer p : level.players()) {
            if (!Sides.friendly(server, owner, p.getUUID())) continue;
            p.sendSystemMessage(message(p.position(), fresh));
            if (sound) p.playNotifySound(ModSounds.DESIGNATOR_LOCK.get(), SoundSource.AMBIENT, 1.0f, 0.7f);
        }
    }

    /** Alert line for the player at {@code listener}: the nearest new target (and their total count). */
    static Component message(Vec3 listener, List<Radar.Track> fresh) {
        Radar.Track near = fresh.getFirst();
        for (Radar.Track t : fresh) {
            if (t.position().distanceToSqr(listener) < near.position().distanceToSqr(listener)) near = t;
        }
        Vec3 d = near.position().subtract(listener);
        Component what = near.projectile().getType().getDescription();
        Component dir = Component.translatable("incoming.compass." + compass(d.x, d.z));
        String km = String.format(Locale.ROOT, "%.1f", Math.hypot(d.x, d.z) / 1000);
        MutableComponent line = fresh.size() == 1
                ? Component.translatable("incoming.sam.alert", what, dir, km)
                : Component.translatable("incoming.sam.alert_many", String.valueOf(fresh.size()), what, dir, km);
        return line.withStyle(ChatFormatting.RED);
    }

    /** Compass direction from an offset (north is −Z, east is +X): “n”, “ne” … “nw”. */
    static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        return COMPASS[Math.floorMod((int) Math.round(deg / 45), 8)];
    }
}
