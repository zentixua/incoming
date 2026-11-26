package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.NuclearEvents;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A survey opened for a strike that is called off before its birth is recorded closes at the next survey tick: it would
 * otherwise hold its reserved bytes and its share of the read window forever. A survey of a live strike stays open.
 */
class MaterialSurveysCloseTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void surveyOfCancelledStrikeCloses() throws ReflectiveOperationException {
        NuclearEvents events = new NuclearEvents();
        Vec3 target = new Vec3(0.5, 64, 0.5);
        var live = new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, false, 0, 1800, target, Optional.empty(), false);
        var cancelled = new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, false, 0, 1800, target, Optional.empty(), false);
        events.schedule(live);
        events.schedule(cancelled);
        MaterialSurveys surveys = new MaterialSurveys();
        Map<Integer, MaterialSurvey> open = open(surveys);
        MaterialSurvey a = mock(MaterialSurvey.class), b = mock(MaterialSurvey.class);
        open.put(live.id(), a);
        open.put(cancelled.id(), b);
        events.cancel(cancelled);
        surveys.closeDone(events);
        verify(b).close();
        verify(a, never()).close();
        assertEquals(1, open.size());
        assertTrue(open.containsKey(live.id()));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, MaterialSurvey> open(MaterialSurveys surveys) throws ReflectiveOperationException {
        Field f = MaterialSurveys.class.getDeclaredField("open");
        f.setAccessible(true);
        return (Map<Integer, MaterialSurvey>) f.get(surveys);
    }
}
