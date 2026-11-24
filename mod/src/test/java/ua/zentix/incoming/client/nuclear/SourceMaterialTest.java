package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialInbox;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SourceMaterialTest {
    @Test void firstLegacyCellAnswerSurvivesNewTerrainAndModernQueryWaits() {
        var d = new Detonation(1, Vec3.ZERO, 0, 15, true, 0, 0, 0, 20000, 7, 1f, false, 0.35f);
        var local = new AtomicReference<>(DustMaterial.SAND);
        var legacy = new SourceMaterial(d, null, (x, z) -> local.get());
        var first = legacy.material(-64, -1);
        assertEquals(DustMaterial.SAND, first.value().orElseThrow());
        local.set(DustMaterial.WATER);
        assertEquals(first, legacy.material(-1, -63));
        assertEquals(DustMaterial.WATER, legacy.material(1, -63).value().orElseThrow());
        assertFalse(first.committed());
        var current = new SourceMaterial(d, new MaterialInbox(), (x, z) -> { throw new AssertionError(); });
        assertTrue(current.material(0, 0).value().isEmpty());
        assertTrue(current.wet().isEmpty());
        var hidden = new SourceMaterial(d, new MaterialInbox(), DustMaterial.WATER.ordinal(), (x, z) -> { throw new AssertionError(); });
        assertEquals(true, hidden.wet().orElseThrow());
        assertEquals(DustMaterial.WATER, hidden.material(0, 0).value().orElseThrow());
        assertFalse(hidden.material(0, 0).committed());
        assertTrue(hidden.material(1_000_000, 0).value().isEmpty());
        var inbox = new MaterialInbox();
        var map = ua.zentix.incoming.nuclear.material.MaterialMap.build(
                new ua.zentix.incoming.nuclear.material.DustGrid.Bounds(0, 0, 128, 512),
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(), 2, 12);
        inbox.receive(new ua.zentix.incoming.net.S2C.NukeMaterial(java.util.UUID.randomUUID(), 0, 0, map.encode()));
        var ready = new SourceMaterial(d, inbox, (x, z) -> { throw new AssertionError(); });
        assertTrue(ready.material(32, 0).committed());
        assertTrue(ready.material(2048, 0).value().isEmpty());
        assertFalse(ready.material(2048, 0).committed());
    }
}
