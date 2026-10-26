package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec.FarLook;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.ClientWeaponSpec.Stage;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** View of projectiles in the distance according to spec: in what phases there is fire and smoke (as close as {@code Exhaust}), one torch near and far. */
class FarLookTest {
    private static FarLook far(WeaponType weapon) {
        return ClientWeaponSpec.of(weapon).airframe(false).far();
    }

    private static List<FarLook> all() {
        List<FarLook> looks = new ArrayList<>();
        for (WeaponType w : WeaponType.values()) {
            looks.add(ClientWeaponSpec.of(w).airframe(false).far());
            if (ClientWeaponSpec.of(w).payload() != null) looks.add(ClientWeaponSpec.of(w).airframe(true).far());
        }
        return looks;
    }

    @Test
    void nothingOnPadOrUnderground() {
        for (FarLook look : all()) {
            assertSame(Stage.NONE, look.stage(FlightPhase.READY), "on launcher before ignition");
            assertSame(Stage.NONE, look.stage(FlightPhase.DRILL), "underground");
        }
    }

    /** Phase torch at sea level of the world. */
    private static Flame flame(FarLook look, FlightPhase ph) {
        return look.stage(ph).flame(Air.SEA);
    }

    @Test
    void rocketBurnsOnlyWhileMotorIsLit() {
        FarLook rocket = far(WeaponType.ROCKET);
        for (FlightPhase ph : List.of(FlightPhase.IGNITION, FlightPhase.BOOST)) assertNotNull(flame(rocket, ph));
        assertNull(rocket.stage(FlightPhase.IGNITION).trail(), "ignition in a pipe - mark from the front cut");
        assertNotNull(rocket.stage(FlightPhase.BOOST).trail());
        assertSame(Stage.NONE, rocket.stage(FlightPhase.CRUISE), "burned out - flies by inertia without a trace");
        assertSame(Stage.NONE, rocket.stage(FlightPhase.TERMINAL));
    }

    @Test
    void boostersBurnThenEnginesShowNoFlame() {
        FarLook drone = far(WeaponType.DRONE), missile = far(WeaponType.MISSILE);
        for (FarLook look : List.of(drone, missile)) {
            assertNotNull(flame(look, FlightPhase.IGNITION), "accelerator burns with ignition");
            assertSame(look.stage(FlightPhase.IGNITION), look.stage(FlightPhase.BOOST), "ignition and acceleration - one accelerator, the tape does not break");
            assertTrue(look.stage(FlightPhase.BOOST).booster());
            assertNull(flame(look, FlightPhase.CRUISE), "piston and turbofan engine do not light up");
            assertNull(flame(look, FlightPhase.TERMINAL), "and at its peak");
        }
    }

    @Test
    void icbmBurnsWholeFlight() {
        FarLook icbm = far(WeaponType.NUKE);
        for (FlightPhase ph : List.of(FlightPhase.IGNITION, FlightPhase.BOOST)) {
            assertNotNull(flame(icbm, ph));
            assertNotNull(icbm.stage(ph).trail());
        }
    }

    @Test
    void bomberHasOnlyItsEnginesTrailAndLoiterNothing() {
        FarLook b2 = far(WeaponType.BUNKER), bomb = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(true).far();
        assertEquals(52, b2.size(), 1e-9, "span B-2");
        for (FlightPhase ph : FlightPhase.values()) {
            assertNull(flame(b2, ph));
            assertSame(Stage.NONE, bomb.stage(ph), "a bomb without an engine - no fire, no trace");
            assertSame(Stage.NONE, far(WeaponType.LOITER).stage(ph), "catapult and electric motor - no fire or smoke");
        }
        assertNotNull(b2.stage(FlightPhase.CRUISE).trail(), "inversion - according to Schmidt-Appleman");
        assertSame(b2.stage(FlightPhase.CRUISE).trail(), b2.stage(FlightPhase.EGRESS).trail(), "trail and on the way out");
    }

    @Test
    void bodiesAreNoBiggerThanTheirSizeAndShadowFitsIt() {
        for (FarLook look : all()) {
            assertTrue(look.size() > 0 && look.area() > 0);
            assertTrue(look.area() <= look.size() * look.size() / 2, "average silhouette is smaller than square size");
        }
    }

    @Test
    void flameLightIsTheJetSeenFromAfar() {
        // the light in the distance is from the same jet as near: the circle of the area of the jet is on the side, the brightness is the average along it, the middle of the jet
        for (FarLook look : all()) {
            for (FlightPhase ph : FlightPhase.values()) {
                Flame f = flame(look, ph);
                if (f == null) continue;
                assertEquals(f.length() * (f.width() + f.spread()), Math.PI * f.lightRadius() * f.lightRadius(), 1e-9);
                double lo = Math.min(f.coreLuminance(), Math.min(f.flameLuminance(), f.tailLuminance()));
                double hi = Math.max(f.coreLuminance(), Math.max(f.flameLuminance(), f.tailLuminance()));
                assertTrue(f.brightness() >= lo && f.brightness() <= hi, "middle - between parts");
                assertTrue(f.middle() > 0 && f.middle() < f.flame(), "the light of the jet is near the flame, and not in the middle of the tail: " + f.middle());
            }
        }
    }
}
