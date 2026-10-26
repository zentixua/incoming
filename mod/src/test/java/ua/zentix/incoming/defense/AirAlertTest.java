package ua.zentix.incoming.defense;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AirAlertTest {
    /** The cardinal directions are in alarm: north Minecraft - −Z, east - +X; eight sectors of 45° around each. */
    @Test
    void compassFollowsMinecraftAxes() {
        assertEquals("n", AirAlert.compass(0, -100));
        assertEquals("e", AirAlert.compass(100, 0));
        assertEquals("s", AirAlert.compass(0, 100));
        assertEquals("w", AirAlert.compass(-100, 0));
        assertEquals("ne", AirAlert.compass(70, -70));
        assertEquals("se", AirAlert.compass(70, 70));
        assertEquals("sw", AirAlert.compass(-70, 70));
        assertEquals("nw", AirAlert.compass(-70, -70));
        // sector boundary - 22,5° from the axis
        assertEquals("n", AirAlert.compass(Math.tan(Math.toRadians(22)), -1));
        assertEquals("ne", AirAlert.compass(Math.tan(Math.toRadians(23)), -1));
        assertEquals("n", AirAlert.compass(-Math.tan(Math.toRadians(22)), -1));
    }
}
