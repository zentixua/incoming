package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec.FarLook;
import ua.zentix.incoming.client.fx.plume.Air;
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

/** Вид снарядов вдали по паспорту: в каких фазах огонь и дым (как вблизи у {@code Exhaust}), один факел вблизи и вдали. */
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
            assertSame(Stage.NONE, look.stage(FlightPhase.READY), "на пусковой до поджига");
            assertSame(Stage.NONE, look.stage(FlightPhase.DRILL), "под землёй");
        }
    }

    /** Факел фазы у уровня моря мира. */
    private static Flame flame(FarLook look, FlightPhase ph) {
        return look.stage(ph).flame(Air.SEA);
    }

    @Test
    void rocketBurnsOnlyWhileMotorIsLit() {
        FarLook rocket = far(WeaponType.ROCKET);
        for (FlightPhase ph : List.of(FlightPhase.IGNITION, FlightPhase.BOOST)) assertNotNull(flame(rocket, ph));
        assertNull(rocket.stage(FlightPhase.IGNITION).trail(), "поджиг в трубе — след с переднего среза");
        assertNotNull(rocket.stage(FlightPhase.BOOST).trail());
        assertSame(Stage.NONE, rocket.stage(FlightPhase.CRUISE), "выгорел — летит по инерции без следа");
        assertSame(Stage.NONE, rocket.stage(FlightPhase.TERMINAL));
    }

    @Test
    void boostersBurnThenEnginesShowNoFlame() {
        FarLook drone = far(WeaponType.DRONE), missile = far(WeaponType.MISSILE);
        for (FarLook look : List.of(drone, missile)) {
            assertNotNull(flame(look, FlightPhase.IGNITION), "ускоритель горит с поджига");
            assertSame(look.stage(FlightPhase.IGNITION), look.stage(FlightPhase.BOOST), "поджиг и разгон — один ускоритель, лента не рвётся");
            assertTrue(look.stage(FlightPhase.BOOST).booster());
            assertNull(flame(look, FlightPhase.CRUISE), "поршень и ТРДД не светят");
            assertNull(flame(look, FlightPhase.TERMINAL), "и в пике");
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
        assertEquals(52, b2.size(), 1e-9, "размах B-2");
        for (FlightPhase ph : FlightPhase.values()) {
            assertNull(flame(b2, ph));
            assertSame(Stage.NONE, bomb.stage(ph), "бомба без двигателя — ни огня, ни следа");
            assertSame(Stage.NONE, far(WeaponType.LOITER).stage(ph), "катапульта и электромотор — без огня и дыма");
        }
        assertNotNull(b2.stage(FlightPhase.CRUISE).trail(), "инверсия — по Шмидту–Эпплману");
        assertSame(b2.stage(FlightPhase.CRUISE).trail(), b2.stage(FlightPhase.EGRESS).trail(), "след и на отходе");
    }

    @Test
    void bodiesAreNoBiggerThanTheirSizeAndShadowFitsIt() {
        for (FarLook look : all()) {
            assertTrue(look.size() > 0 && look.area() > 0);
            assertTrue(look.area() <= look.size() * look.size() / 2, "средний силуэт меньше квадрата размера");
        }
    }

    @Test
    void flameLightIsTheJetSeenFromAfar() {
        // свет вдали — из той же струи, что вблизи: круг площади струи сбоку, яркость — средняя по ней, середина струи
        for (FarLook look : all()) {
            for (FlightPhase ph : FlightPhase.values()) {
                Flame f = flame(look, ph);
                if (f == null) continue;
                assertEquals(f.length() * (f.width() + f.spread()), Math.PI * f.lightRadius() * f.lightRadius(), 1e-9);
                double lo = Math.min(f.coreLuminance(), Math.min(f.flameLuminance(), f.tailLuminance()));
                double hi = Math.max(f.coreLuminance(), Math.max(f.flameLuminance(), f.tailLuminance()));
                assertTrue(f.brightness() >= lo && f.brightness() <= hi, "средняя — между частями");
                assertEquals(f.length() / 2, f.middle(), 1e-12);
            }
        }
    }
}
