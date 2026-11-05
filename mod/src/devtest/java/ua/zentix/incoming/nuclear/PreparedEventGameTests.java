package ua.zentix.incoming.nuclear;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.UUID;

/** Event binding and native NBT controls, not a complete strike or process-restart acceptance. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedEventGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_event", skyAccess = true)
    public static void preparationOrderDoesNotOwnEventChronologyOrMoveOffsets(GameTestHelper h) {
        var events = new NuclearEvents();
        UUID preparedFirst = UUID.randomUUID(), preparedSecond = UUID.randomUUID();
        var firstActual = event(events.nextId(), 600_000);
        rejects(h, () -> events.bindPrepared(firstActual, preparedSecond), "Unregistered event was bound");
        events.add(firstActual);
        var secondActual = event(events.nextId(), 600_100);
        events.add(secondActual);
        events.bindPrepared(firstActual, preparedSecond);
        events.bindPrepared(secondActual, preparedFirst);
        h.assertTrue(events.bindPrepared(firstActual, preparedSecond).age(600_150) == 150,
                "Idempotent binding changed origin or converted relative material time");
        h.assertTrue(events.prepared(secondActual.id()).orElseThrow().age(600_099) == -1,
                "Pre-event world time became a due material offset");
        rejects(h, () -> events.bindPrepared(firstActual, preparedFirst), "Event was rebound to another prepared output");
        var third = event(events.nextId(), 600_200);
        events.add(third);
        rejects(h, () -> events.bindPrepared(third, preparedFirst), "One prepared output was bound to two detonations");
        var restored = NuclearEvents.load(events.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(restored.prepared(firstActual.id()).orElseThrow().preparation().equals(preparedSecond)
                        && restored.prepared(secondActual.id()).orElseThrow().preparation().equals(preparedFirst),
                "Save reordered preparation identity by event ID");
        restored.prune(600_100 + NuclearEvents.FORGET_AFTER + 1);
        h.assertTrue(restored.isPast(secondActual.id()) && restored.prepared(secondActual.id()).orElseThrow().origin() == 600_100,
                "Fading visuals retired a cold material binding");
        restored.clear();
        var cancelled = NuclearEvents.load(restored.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(cancelled.detonations().isEmpty() && cancelled.past().isEmpty()
                        && cancelled.prepared(secondActual.id()).orElseThrow().age(600_200) == 100,
                "Cancellation orphaned already removed material or reset its clock");
        h.assertTrue(cancelled.prepared(third.id()).isEmpty(), "Legacy/unbound event acquired a prepared owner");
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_prepared_event_invalid", skyAccess = true)
    public static void unreadableBindingIsPreservedAndCannotSelectLegacyDestruction(GameTestHelper h) {
        var events = new NuclearEvents();
        var event = event(events.nextId(), 1200);
        events.add(event);
        events.bindPrepared(event, UUID.randomUUID());
        var good = events.save(new CompoundTag(), h.getLevel().registryAccess());
        for (int fault = 0; fault < 4; fault++) {
            var bad = good.copy();
            var row = bad.getCompound("prepared").getCompound(Integer.toString(event.id()));
            if (fault == 0) row.putIntArray("plan", new int[]{1, 2});
            if (fault == 1) row.putInt("origin", 1200); // Exact long, never numeric coercion.
            if (fault == 2) row.putLong("origin", 1201);
            if (fault == 3) bad.putString("prepared", "unreadable authoritative owner");
            var loaded = NuclearEvents.load(bad, h.getLevel().registryAccess());
            rejects(h, () -> loaded.prepared(event.id()), "Malformed binding selected the legacy execution path");
            rejects(h, () -> loaded.bindPrepared(event, UUID.randomUUID()), "Malformed binding was replaced by a fresh owner");
            loaded.clear();
            var preserved = loaded.save(new CompoundTag(), h.getLevel().registryAccess());
            h.assertTrue(preserved.get("prepared").equals(bad.get("prepared")), "Save or cancellation discarded unreadable ownership");
        }
        h.succeed();
    }

    private static Detonation event(int id, long origin) {
        return new Detonation(id, new Vec3(0.5, 64, 0.5), 64, 15, false, origin,
                0, 0, 20_000, 17, 1, false, .25f);
    }

    private static void rejects(GameTestHelper h, Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        h.assertTrue(rejected, message);
    }

    private PreparedEventGameTests() {}
}
