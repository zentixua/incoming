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
    }
}
