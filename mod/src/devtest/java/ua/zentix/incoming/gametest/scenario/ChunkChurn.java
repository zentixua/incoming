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
 * Помеха сценариям полёта: в каждом тике мира — то, что в том же мире делают чужие удары и соседние сценарии партии.
 * Берёт район вдали и отпускает взятый тиком раньше (отпуск в {@code AreaLoader} пересчитывает уровни чанков посреди
 * тика мира) и сам пересчитывает уровни чанков ({@code runDistanceManagerUpdates}) в начале тика мира, перед модом
 * (до {@code StrikeWorld}) и перед тиком каждой сущности.
 * <p>
 * Полёт сценария от этого меняться не должен: эталон пишется без помехи ({@code runScenarioRecord}), а сверяется
 * с ней. Решение мода, которое читает уровни или тик чанков, пока его же тикеты этого тика ещё не применены, зависит
 * от того, применил ли их кто-то посторонний, — и с помехой расходится с эталоном сразу, а не изредка в части CI
 * с другим составом (22.10: «Ланцет» сценария {@code loiter_rail.player} возвращался в мир на тик раньше или позже).
 */
final class ChunkChurn {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_test_churn", Comparator.<UUID>naturalOrder());
    /** Район помехи: вдали от площадок GameTest и сценариев (они — в плюсе по обеим осям). */
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

    /** Последний сценарий кончился — район помехи отпустить: тикет не должен пережить сценарии. */
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
