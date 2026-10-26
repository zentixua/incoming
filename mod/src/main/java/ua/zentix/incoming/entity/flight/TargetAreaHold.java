package ua.zentix.incoming.entity.flight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.strike.FlightTickets;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Target area preloaded by projectile ({@link FlightTickets}) and its approach lane
 * ({@link FlightTickets#approach}). Transfers whole when mission changes entity, without releasing tickets or lane references.
 * Unsaved, like tickets: after load, projectile reacquires the area on approach.
 * <p>
 * Area initially held without ticking, load-only: ticking needed only for world reentry and detonation,
 * while a ticking area also means mobs, block entities and spawning. Otherwise a map-wide salvo would keep dozens of areas
 * ticking for each projectile's 15 s approach. Acquire ticking area when projectile says ({@code ticks}: final
 * approach seconds), retaining it afterward; release load-only area after acquisition — no chunk-level drop even for a tick.
 */
public final class TargetAreaHold {
    /** Chunk around which target area is held (null — not held). */
    @Nullable
    private ChunkPos area;
    /** Size of area held in {@link #area}: {@link FlightTickets} ticket level. */
    private int size;
    /** Area acquired with ticking rather than load-only. */
    private boolean ticking;
    /** Projectile's held approach lane; empty — none held. */
    private List<ChunkPos> approach = List.of();

    /** New flight form does not own the area or lane yet. */
    public boolean isEmpty() {
        return area == null && approach.isEmpty();
    }

    /**
     * Hold area around {@code aim} once within {@code preload}: if target moves at least 2 chunks, area
     * follows it. Ticking once {@code ticks}. Approach lane of {@code visibleLeg} length (0 — none)
     * when area acquired and once per second.
     *
     * @param size  area size: {@link FlightTickets} ticket level
     * @param ticks area should tick: projectile in final approach seconds
     * @param route projectile route (lane follows it backward from target), or null
     * @param age   projectile age, ticks: used for once-per-second updates
     */
    public void hold(ServerLevel level, UUID owner, Vec3 pos, Vec3 aim, double preload, int size, boolean ticks, double visibleLeg,
                     @Nullable Route route, int age) {
        if (area != null && area.getChessboardDistance(new ChunkPos(BlockPos.containing(aim))) >= 2) release(level, owner);
        boolean taken = false;
        if (area == null && pos.distanceTo(aim) <= preload) {
            area = new ChunkPos(BlockPos.containing(aim));
            this.size = size;
            ticking = ticks;
            FlightTickets.hold(level, area, size, owner, ticking, true);
            taken = true;
        } else if (area != null && ticks && !ticking) {
            // Ticking first, then load-only: no chunk-level drop even for one tick.
            FlightTickets.hold(level, area, this.size, owner, true, true);
            FlightTickets.hold(level, area, this.size, owner, false, false);
            ticking = true;
        }
        if (area != null && visibleLeg > 0 && (taken || age % 20 == 0)) updateApproach(level, owner, pos, aim, visibleLeg, route);
    }

    /**
     * Approach lane while target area is held: acquire when someone can watch at target ({@link FlightTickets#watched}),
     * release when no watchers remain. No world capacity ({@link FlightTickets#APPROACH_LIMIT}):
     * retry in one second.
     */
    private void updateApproach(ServerLevel level, UUID owner, Vec3 pos, Vec3 aim, double visibleLeg, @Nullable Route route) {
        if (!FlightTickets.watched(level, aim, visibleLeg)) {
            FlightTickets.releaseApproach(level, approach, owner);
            approach = List.of();
            return;
        }
        if (!approach.isEmpty()) return;
        // Backward along projectile path: target, waypoints from last (entry point) to nearest, projectile itself.
        List<Vec3> path = new ArrayList<>();
        path.add(aim);
        if (route != null) {
            List<Vec3> pts = route.points();
            for (int i = pts.size() - 1; i >= route.index(); i--) path.add(pts.get(i));
        }
        path.add(pos);
        List<ChunkPos> centres = FlightTickets.approach(path, visibleLeg);
        if (FlightTickets.holdApproach(level, centres, owner)) approach = centres;
    }

    /** Target area acquired with ticking: projectile in final approach seconds. */
    public boolean ticking() {
        return area != null && ticking;
    }

    /** Release area and lane (projectile removed/retargeted — new area acquired on approach). */
    public void release(ServerLevel level, UUID owner) {
        if (area != null) FlightTickets.hold(level, area, size, owner, ticking, false);
        FlightTickets.releaseApproach(level, approach, owner);
        area = null;
        approach = List.of();
    }
}
