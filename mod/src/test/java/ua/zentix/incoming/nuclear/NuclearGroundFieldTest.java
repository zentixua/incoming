package ua.zentix.incoming.nuclear;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change 8 of time.md §9 in the saved events: a detonation forgotten at day 7 of its clock keeps its fallout as a ground field
 * while it lies above the floor, with its clocks; the field is dropped once below, cleared by {@code /incoming clear}, saved
 * and loaded; an unfinished crater keeps its progress after its detonation is forgotten; a world saved before fields loads.
 */
class NuclearGroundFieldTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    private static Detonation surface(int id) {
        return new Detonation(id, new Vec3(0.5, 64, 0.5), 64, 15, true, 0, 0f, 6.7f, 20_000, 7, 1f, true, 0.35f);
    }

    private static CompoundTag saved(NuclearEvents events) {
        return events.save(new CompoundTag(), RegistryAccess.EMPTY);
    }

    @Test
    void forgottenFalloutLiesOnUntilItIsBelowTheFloor() {
        NuclearEvents events = new NuclearEvents();
        Detonation d = surface(events.nextId());
        events.add(d, 0, 0);
        events.craterProgress(d.id(), 12, false);
        events.craterGround(d.id(), new double[]{0, 0, 63});
        // day 6: nothing forgotten
        events.prune(x -> 6 * 24 * 3600.0, x -> true);
        assertTrue(events.detonations().contains(d) && events.groundFields().isEmpty());
        // day 7: forgotten, its fallout lies on as a field that keeps the detonation's clocks
        events.prune(x -> NuclearEvents.FORGET_AFTER_S + 1, x -> true);
        assertFalse(events.detonations().contains(d));
        assertTrue(events.past().contains(d) && events.groundFields().contains(d));
        assertEquals(List.of(d), events.falloutSources());
        assertTrue(events.history(d) != null, "the field lost its clock history");
        assertEquals(12, events.craters().get(d.id()), "the crater's progress was dropped with its detonation");
        // saved and loaded: the field, its history and the crater
        NuclearEvents back = NuclearEvents.load(saved(events), RegistryAccess.EMPTY);
        assertEquals(List.of(d), back.groundFields());
        assertTrue(back.history(d) != null);
        assertEquals(12, back.craters().get(d.id()));
        // the crater finished: its progress goes
        events.craterProgress(d.id(), 40, true);
        assertFalse(events.craters().containsKey(d.id()));
        // still above the floor: kept; below it: dropped with its clocks
        events.prune(x -> 1e9, x -> true);
        assertEquals(List.of(d), events.groundFields());
        events.prune(x -> 1e9, x -> false);
        assertTrue(events.groundFields().isEmpty() && events.history(d) == null);
        assertTrue(events.past().contains(d), "destruction is still remembered");
    }

    @Test
    void aForgottenDetonationWithoutFalloutLeavesNoField() {
        NuclearEvents events = new NuclearEvents();
        Detonation d = surface(events.nextId());
        events.add(d, 0, 0);
        events.prune(x -> NuclearEvents.FORGET_AFTER_S + 1);
        assertTrue(events.groundFields().isEmpty() && events.past().contains(d) && events.history(d) == null);
    }

    @Test
    void clearRemovesTheFields() {
        NuclearEvents events = new NuclearEvents();
        Detonation d = surface(events.nextId());
        events.add(d, 0, 0);
        events.prune(x -> NuclearEvents.FORGET_AFTER_S + 1, x -> true);
        assertEquals(1, events.groundFields().size());
        events.clear();
        assertTrue(events.groundFields().isEmpty() && events.falloutSources().isEmpty());
        assertTrue(NuclearEvents.load(saved(events), RegistryAccess.EMPTY).groundFields().isEmpty());
    }

    /** A world saved before ground fields: its forgotten detonations stay forgotten (their fallout had ended then). */
    @Test
    void aWorldSavedBeforeFieldsLoads() {
        NuclearEvents events = new NuclearEvents();
        Detonation d = surface(events.nextId());
        events.add(d, 0, 0);
        events.prune(x -> NuclearEvents.FORGET_AFTER_S + 1, x -> true);
        CompoundTag old = saved(events);
        old.remove("ground_fields");
        NuclearEvents back = NuclearEvents.load(old, RegistryAccess.EMPTY);
        assertTrue(back.groundFields().isEmpty() && back.past().contains(d));
    }
}
