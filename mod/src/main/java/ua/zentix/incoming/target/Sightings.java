package ua.zentix.incoming.target;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.CameraLink;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reconnaissance: each side's ({@link Sides}: player or {@code /team}) sightings through eyes, projectile
 * cameras, radar. Hostile player/Sable craft is a designator target while side sees it or saw within {@code
 * sight_memory}; map likewise shows last-seen positions. Friendlies always visible.
 * <p>
 * Eyes: every {@link #SCAN_PERIOD} ticks each player scans; hostile spotted if inside FOV ({@link
 * #FIELD_DEGREES}° cone around view, screen plus margin) and {@link Sight#sees}. Projectile camera: while aboard
 * own projectile ({@link CameraLink}), same scan from projectile in camera frame. Radar/other sensors report
 * themselves ({@link #spot}). World state, unsaved.
 */
public final class Sightings {
    /** Player scan interval, ticks. */
    public static final int SCAN_PERIOD = 5;
    /** Seen within this many ticks counts as currently seen: designator targets entity, not position. */
    public static final int CURRENT = 2 * SCAN_PERIOD;
    /** FOV half-angle: screen at FOV 70–90 and 16:9 spans 50–60° horizontally from center. */
    public static final double FIELD_DEGREES = 60;
    private static final double FIELD_COS = Math.cos(Math.toRadians(FIELD_DEGREES));

    /** Spotting source. */
    public enum Source { EYES, CAMERA, RADAR }

    /** Sighted type: player, Sable craft, projectile, other. */
    public enum Kind {
        PLAYER, AIRCRAFT, PROJECTILE, OTHER;

        static Kind of(Entity e) {
            return e instanceof Player ? PLAYER : e instanceof StrikeProjectile ? PROJECTILE : OTHER;
        }
    }

    /** Sighting: identity, name (player username), kind, last-seen position/time/source. */
    public record Contact(UUID id, String name, Kind kind, Vec3 pos, long time, Source source) {
        /** Ticks since last seen. */
        public long age(long now) {
            return now - time;
        }
    }

    private final Map<String, Map<UUID, Contact>> sides = new HashMap<>();

    public Sightings() {}

    public static Sightings get(ServerLevel level) {
        return level.getData(ModAttachments.SIGHTINGS.get());
    }

    /** Sighting memory, ticks ({@code sight_memory}). */
    public static long memory() {
        return IncomingConfig.SERVER.sightMemory.get() * 20L;
    }

    // ---------------------------------------------------------------- report and query

    /** Side {@code side} sees entity {@code seen} now. */
    public static void spot(ServerLevel level, String side, Entity seen, Source source) {
        String name = seen instanceof Player p ? p.getGameProfile().getName() : seen.getName().getString();
        get(level).put(side, new Contact(seen.getUUID(), name, Kind.of(seen), seen.position(), level.getGameTime(), source));
    }

    /** Side {@code side} sees Sable craft {@code craft} now. */
    public static void spot(ServerLevel level, String side, SubLevelAccess craft, Source source) {
        get(level).put(side, new Contact(craft.getUniqueId(), Objects.requireNonNullElse(SubLevels.name(craft), ""), Kind.AIRCRAFT, SubLevels.center(craft),
                level.getGameTime(), source));
    }

    /** What {@code side} knows of {@code id}: sighting within {@link #memory} ticks, otherwise null. */
    @Nullable
    public static Contact contact(ServerLevel level, String side, UUID id) {
        Map<UUID, Contact> known = get(level).sides.get(side);
        Contact c = known == null ? null : known.get(id);
        return c != null && c.age(level.getGameTime()) <= memory() ? c : null;
    }

    /** Everything {@code side} saw within {@link #memory} ticks. */
    public static List<Contact> contacts(ServerLevel level, String side) {
        Map<UUID, Contact> known = get(level).sides.get(side);
        if (known == null) return List.of();
        long now = level.getGameTime(), memory = memory();
        List<Contact> out = new ArrayList<>(known.size());
        for (Contact c : known.values()) if (c.age(now) <= memory) out.add(c);
        return out;
    }

    private void put(String side, Contact c) {
        sides.computeIfAbsent(side, k -> new HashMap<>()).put(c.id(), c);
    }

    // ---------------------------------------------------------------- projectile camera

    /** Camera of projectile currently viewed aboard ({@link CameraLink}) and its view. */
    private record View(StrikeProjectile camera, CameraLink link) {
        Vec3 eye() {
            return camera.getEyePosition();
        }
    }

    @Nullable
    private static View view(ServerPlayer player) {
        CameraLink link = CameraLink.current(player).orElse(null);
        return link != null && player.serverLevel().getEntity(link.projectile()) instanceof StrikeProjectile p && p.isAlive()
                ? new View(p, link) : null;
    }

    // ---------------------------------------------------------------- scan

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % SCAN_PERIOD != 0) return;
        if (level.players().isEmpty() && !level.hasData(ModAttachments.SIGHTINGS.get())) return;
        scan(level, level.players());
    }

    /**
     * Scan: each living non-spectator in {@code players} records for their side hostile players from same list
     * and Sable craft currently visible ({@link #sees}, {@link #seesCraft}); remove forgotten sightings.
     */
    public static void scan(ServerLevel level, List<? extends ServerPlayer> players) {
        Sightings s = get(level);
        long now = level.getGameTime(), memory = memory();
        s.sides.values().removeIf(known -> {
            known.values().removeIf(c -> c.age(now) > memory);
            return known.isEmpty();
        });
        for (ServerPlayer viewer : players) {
            if (viewer.isSpectator() || !viewer.isAlive() || viewer.level() != level) continue;
            String side = Sides.side(viewer);
            for (ServerPlayer other : players) {
                if (other == viewer || other.isSpectator() || Sides.friendly(viewer, other)) continue;
                Source by = seenBy(viewer, other);
                if (by != null) s.put(side, new Contact(other.getUUID(), other.getGameProfile().getName(), Kind.PLAYER,
                        other.position(), now, by));
            }
            if (SubLevels.mayHaveCraftNear(level, viewer.position(), Sight.viewRange(viewer))) {
                for (SubLevelAccess craft : SubLevels.near(level, viewer.position(), Sight.viewRange(viewer))) {
                    if (seesCraft(viewer, craft)) spot(level, side, craft, Source.EYES);
                }
            }
        }
    }

    /** Player currently sees entity via eyes or own projectile camera (in FOV, unobstructed). */
    public static boolean sees(ServerPlayer viewer, Entity target) {
        return seenBy(viewer, target) != null;
    }

    @Nullable
    private static Source seenBy(ServerPlayer viewer, Entity target) {
        Vec3 eye = viewer.getEyePosition();
        if (facing(eye, viewer.getViewVector(1), target.getBoundingBox().getCenter()) && Sight.sees(viewer, eye, target)) return Source.EYES;
        View v = view(viewer);
        if (v != null && v.camera() != target && v.link().frames(v.eye(), target.getBoundingBox().getCenter())
                && Sight.sees(viewer, v.eye(), target)) return Source.CAMERA;
        return null;
    }

    /**
     * Player currently sees Sable craft: center within player render distance/FOV, view to center hits craft
     * itself or nothing (eyes or own projectile camera). Loads no chunks.
     */
    public static boolean seesCraft(ServerPlayer viewer, SubLevelAccess craft) {
        Vec3 center = SubLevels.center(craft);
        if (!Sight.within(viewer, center)) return false;
        if (facing(viewer.getEyePosition(), viewer.getViewVector(1), center) && craftInSight(viewer.serverLevel(), viewer.getEyePosition(), craft, center)) {
            return true;
        }
        View v = view(viewer);
        return v != null && v.link().frames(v.eye(), center) && craftInSight(viewer.serverLevel(), v.eye(), craft, center);
    }

    /** View from {@code eye} to craft center hits craft itself or nothing. */
    private static boolean craftInSight(ServerLevel level, Vec3 eye, SubLevelAccess craft, Vec3 center) {
        if (!Terrain.readyAlong(level, eye, center)) return false;
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return true;
        SubLevelAccess on = SubLevels.containing(level, hit.getLocation());
        return on != null && on.getUniqueId().equals(craft.getUniqueId());
    }

    /** Point {@code at} within FOV from {@code eye} along {@code look}. */
    static boolean facing(Vec3 eye, Vec3 look, Vec3 at) {
        Vec3 to = at.subtract(eye);
        double length = to.length();
        return length < 1.0e-3 || to.dot(look) >= FIELD_COS * length * look.length();
    }
}
