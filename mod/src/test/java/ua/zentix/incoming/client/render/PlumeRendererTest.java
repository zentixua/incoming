package ua.zentix.incoming.client.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Факел — цилиндр струи: строго сзади по оси видно его сечение, а не отрезок поперёк (3.5.0: полосы вдоль оси с торца
 * сходили в «палку», ореол у сопла был убран). Площадь квадратов факела с торца — не меньше круга сечения пламени
 * (у ореола — его поток, {@link PlumeRenderer#HALO}), сбоку — не меньше площади струи сбоку.
 */
class PlumeRendererTest {
    /** Площадь квадратов слоёв на плоскости поперёк взгляда view (ореол — с долей, что закрывает его картинка). */
    private static double area(Flame f, Vector3f cam, Vector3f left, Vector3f up) {
        // взгляд — поперёк экрана (left × up), как у камеры
        Vector3f view = new Vector3f(left).cross(up).normalize();
        double[] sum = {0};
        PlumeRenderer.layout(f, 0, cam, left, up, (layer, a, b, c, d) -> {
            Vector3f ab = new Vector3f(b).sub(a), ad = new Vector3f(d).sub(a);
            double projected = Math.abs(new Vector3f(ab).cross(ad).dot(view));
            sum[0] += layer < 3 ? projected : projected * 0.086;
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
                // сзади по оси: камера за соплом на −Z, экран — x и y
                double behind = area(f, new Vector3f(0, 0, -50), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
                double cross = Math.PI * f.widthAt(f.flame()) * f.widthAt(f.flame());
                assertTrue(behind >= 0.9 * cross, w + ": сзади " + behind + " м² при сечении пламени " + cross);
                // сбоку: камера по +X, экран — z и y
                double side = area(f, new Vector3f(50, 0, (float) (-f.length() / 2)), new Vector3f(0, 0, 1), new Vector3f(0, 1, 0));
                assertTrue(side >= 0.9 * f.area(), w + ": сбоку " + side + " м² при струе " + f.area());
            }
            }
        }
        assertTrue(seen > 0, "факелы есть");
    }
}
