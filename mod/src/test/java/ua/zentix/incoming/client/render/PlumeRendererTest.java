package ua.zentix.incoming.client.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The torch is a cylinder of the jet: its cross section is visible strictly behind the axis, and not a section across (3.5.0: stripes along the axis from the end
 *  went into a “stick”, the halo at the nozzle was removed). The area of ​​the torch squares from the end is not less than the cross-sectional circle of the flame
 *  (at the cross-sectional circle is its flow: {@code far/disc} covers the 0,632 square, {@link PlumeRenderer#HALO}), from the side - not less than the area of ​​the jet from the side.
 */
class PlumeRendererTest {
    /** The area of the squares of the layers on the plane across the view is view (the halo is with the portion that the picture covers). */
    private static double area(Flame f, Vector3f cam, Vector3f left, Vector3f up) {
        // look - across the screen (left × up), like a camera
        Vector3f view = new Vector3f(left).cross(up).normalize();
        double[] sum = {0};
        PlumeRenderer.layout(f, 0, cam, left, up, (layer, piece, a, b, c, d) -> {
            // quadrangle (for pieces of shell - trapezoid): two halves diagonally
            Vector3f ab = new Vector3f(b).sub(a), ac = new Vector3f(c).sub(a), ad = new Vector3f(d).sub(a);
            double projected = 0.5 * (Math.abs(new Vector3f(ab).cross(ac).dot(view)) + Math.abs(new Vector3f(ac).cross(ad).dot(view)));
            // the shell and core shine together: from the side, the area of the jet is at the shell, the core is inside it
            if (layer == PlumeRenderer.CORE) return;
            sum[0] += layer == PlumeRenderer.SHEATH ? projected : projected * 0.632;
        });
        return sum[0];
    }

    @Test
    void endOnShowsTheCrossSection() {
        int seen = 0;
        for (WeaponType w : WeaponType.values()) {
            ClientWeaponSpec spec = ClientWeaponSpec.of(w);
            for (ClientWeaponSpec.ClientAirframe frame : new ClientWeaponSpec.ClientAirframe[]{spec.airframe(), spec.payload()}) {
            if (frame == null) continue;
            for (FlightPhase phase : FlightPhase.values()) {
                Motor motor = frame.far().stage(phase).motor();
                if (motor == null) continue;
                Flame f = FlameModel.of(motor, 0);
                if (f == null || f.length() <= 0) continue;
                seen++;
                // rear axial: chamber behind the nozzle on −Z, screen — x and y
                double behind = area(f, new Vector3f(0, 0, -50), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
                double cross = Math.PI * f.widthAt(f.flame()) * f.widthAt(f.flame());
                assertTrue(behind >= 0.9 * cross, w + ": behind " + behind + " m² at flame cross section " + cross);
                // on the side: camera on +X, screen - z and y
                double side = area(f, new Vector3f(50, 0, (float) (-f.length() / 2)), new Vector3f(0, 0, 1), new Vector3f(0, 1, 0));
                assertTrue(side >= 0.9 * f.area(), w + ": side " + side + " m² with jet " + f.area());
            }
            }
        }
        assertTrue(seen > 0, "there are torches");
    }

    /**
     * Fire shines after the world, effects layer: under Iris everything that is drawn by entities or particles Minecraft passes
     *  through the shader pack, and during the day a stream of torch ten times brighter than the white wall came out as a pale fragment (look gate RV2,
     * start side). Self-luminous - neither {@code RenderType.eyes}, nor adding entities into buffers - is not present in the client.
     */
    @Test
    void fireIsNotDrawnThroughTheShaderPack() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/ua/zentix/incoming/client"))) {
            List<Path> eyes = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String src = Files.readString(p);
                    return src.contains("RenderType.eyes(") || src.contains("RenderType.energySwirl(");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
            assertEquals(List.of(), eyes, "glowing - in effects layer");
        }
        String plume = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/render/PlumeRenderer.java"));
        assertTrue(!plume.contains("MultiBufferSource") && !plume.contains("VertexConsumer"), "jet - layer squares, not entity buffer");
    }

    /**
     * Along the jet, the light is from the law of the model, and not a picture: from the nozzle to the end of the flame there is a flame (at the rocket accelerator during the day -
     *  overexposure, white), behind it the tail goes out without jumps to the Draper point and turns red.
     */
    @Test
    void sheathFadesAlongTheJet() {
        Flame f = FlameModel.of(ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).motor(), 0);
        Flame.Sheath s = f.sheath();
        assertTrue(s.size() >= 8, "there are enough pieces along the stream for a smooth tail");
        assertEquals(0, s.x()[0], 1e-12);
        assertEquals(f.length(), s.x()[s.size() - 1], 1e-9);
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.x()[i] > s.x()[i - 1]);
            assertTrue(s.luminance()[i] <= s.luminance()[i - 1] * (1 + 1e-9), "not brighter towards the tail");
            assertTrue((s.color()[i] >> 8 & 255) <= (s.color()[i - 1] >> 8 & 255), "redder towards the tail");
        }
        // during the day (addiction 1) the flame to its end is brighter than the white screen, after one and a half lengths of the flame it is already dimmer
        int atFlame = 0;
        while (s.x()[atFlame] < f.flame() - 1e-9) atFlame++;
        assertTrue(s.luminance()[atFlame] > 1, "end of the flame during the day - overexposure: " + s.luminance()[atFlame]);
        int far = s.size() - 1;
        while (s.x()[far - 1] > 1.5 * f.flame()) far--;
        assertTrue(s.luminance()[far] < 1, "beyond one and a half flame lengths - dimmer than white: " + s.luminance()[far]);
    }
}
