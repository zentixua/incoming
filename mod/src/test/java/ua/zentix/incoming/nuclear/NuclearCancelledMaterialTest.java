package ua.zentix.incoming.nuclear;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.material.LoadedSurfaces;
import ua.zentix.incoming.nuclear.material.MaterialBirth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scheduled ICBM cancelled after its dust-material birth was committed ({@code MaterialSurveys.tick} records it about
 * 10 s before the burst; {@code NuclearStrikes.cancelTagged} cancels it) forgets that birth: it is no longer saved with
 * the world or listed in warned viewers' manifests.
 */
class NuclearCancelledMaterialTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    private static NuclearEvents.ScheduledStrike committedStrike(NuclearEvents events) {
        Vec3 target = new Vec3(0.5, 64, 0.5);
        var s = new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, false, 0, 1800, target, Optional.empty(), false);
        events.schedule(s);
        // what MaterialSurveys.tick commits for a strike in flight (MaterialSurvey.begin)
        Detonation geometry = new Detonation(-1, target, 64, 15, true, 0, 0, 0, 20_000, 7, 1, false, 1);
        MaterialBirth birth = MaterialBirth.prepare(UUID.randomUUID(), geometry);
        Long2IntOpenHashMap votes = new Long2IntOpenHashMap();
        votes.put(0L, 4 << 8); // four soil columns in chunk 0,0
        birth.begin(geometry, s.detonateTime(), 1600, 2, votes, new LoadedSurfaces().snapshot());
        events.material(s.id(), birth);
        return s;
    }

    @Test
    void cancelledStrikeForgetsItsMaterial() {
        NuclearEvents events = new NuclearEvents();
        var s = committedStrike(events);
        events.cancel(s);
        events.prune(1_000_000, 1_000_000);
        assertTrue(events.scheduled().isEmpty());
        assertNull(events.material(s.id()), "cancelled strike's ground material is still held by the world's saved data");
        assertTrue(events.currentMaterials().isEmpty(), "cancelled strike's ground material is still in every warned viewer's manifest");
    }

    @Test
    void cancelledStrikeMaterialIsNotSaved() {
        NuclearEvents events = new NuclearEvents();
        var s = committedStrike(events);
        events.cancel(s);
        events.prune(1_000_000, 1_000_000);
        CompoundTag saved = events.save(new CompoundTag(), RegistryAccess.EMPTY);
        int entries = saved.getList("materials", Tag.TAG_COMPOUND).size();
        assertEquals(0, entries, "cancelled strike's material entry (observations journal) is written to disk on every save");
    }
}
