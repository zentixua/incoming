package ua.zentix.incoming.gametest.scenario;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;

import java.util.Comparator;
import java.util.UUID;

/**
 * Interference with flight scenarios: in each tick of the world - what other people's strikes and neighboring batch scenarios are doing in the same world.
 *  Takes an area far away and releases the taken tick earlier (vacation in {@code AreaLoader} recalculates the chunk levels in the middle of the
 *  tick of the world) and itself recalculates the chunk levels ({@code runDistanceManagerUpdates}) at the beginning of the tick world, before the mod
 *  (before {@code StrikeWorld}) and before the tick of each entity.
 * <p>
 * The flight of the script should not change from this: the reference is written without interference ({@code runScenarioRecord}), and is checked against
 * . The solution of a mod that reads levels or tick chunks, while its ticket for this tick has not yet been applied, depends
 *  on whether someone else applied them - and with a hindrance diverges from the reference immediately, and not occasionally in part CI
 *  with a different composition (22.10: “Lancet” scenario {@code loiter_rail.player} returned to the world a tick sooner or later).
 */
final class ChunkChurn {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_test_churn", Comparator.<UUID>naturalOrder());
    /** Interference area: away from the GameTest sites and scenarios (they are in the plus on both axes). */
    private static final ChunkPos CENTRE = new ChunkPos(-100_000, -100_000);

    private static int users;
    private static boolean registered;
    private static AreaLoader.Area held;

    private ChunkChurn() {}

    static synchronized void acquire() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ChunkChurn::onLevelTickPre);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ChunkChurn::onLevelTickPost);
            NeoForge.EVENT_BUS.addListener(ChunkChurn::onEntityTick);
        }
        users++;
    }

    /** The last scenario is over - let go of the interference area: the ticket should not survive the scenarios. */
    static synchronized void release(ServerLevel level) {
        if (--users == 0 && held != null) {
            StrikeWorld.get(level).areas().release(level, held);
            held = null;
        }
    }

    private static ServerLevel overworld(Level level) {
        return users > 0 && level instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD ? sl : null;
    }

    private static void onLevelTickPre(LevelTickEvent.Pre e) {
        ServerLevel level = overworld(e.getLevel());
        if (level == null) return;
        AreaLoader areas = StrikeWorld.get(level).areas();
        if (held != null) areas.release(level, held);
        held = new AreaLoader.Area(TYPE, CENTRE, 1, UUID.randomUUID());
        areas.hold(level, held);
        level.getChunkSource().runDistanceManagerUpdates();
    }

    private static void onLevelTickPost(LevelTickEvent.Post e) {
        ServerLevel level = overworld(e.getLevel());
        if (level != null) level.getChunkSource().runDistanceManagerUpdates();
    }

    private static void onEntityTick(EntityTickEvent.Pre e) {
        ServerLevel level = overworld(e.getEntity().level());
        if (level != null) level.getChunkSource().runDistanceManagerUpdates();
    }
}
