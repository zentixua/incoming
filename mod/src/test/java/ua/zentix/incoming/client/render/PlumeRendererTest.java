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
 * Факел — цилиндр струи: строго сзади по оси видно его сечение, а не отрезок поперёк (3.5.0: полосы вдоль оси с торца
 * сходили в «палку», ореол у сопла был убран). Площадь квадратов факела с торца — не меньше круга сечения пламени
 * (у круга сечения — его поток: {@code far/disc} закрывает 0,632 квадрата, {@link PlumeRenderer#HALO}), сбоку — не меньше площади струи сбоку.
 */
class PlumeRendererTest {
    /** Площадь квадратов слоёв на плоскости поперёк взгляда view (ореол — с долей, что закрывает его картинка). */
    private static double area(Flame f, Vector3f cam, Vector3f left, Vector3f up) {
        // взгляд — поперёк экрана (left × up), как у камеры
        Vector3f view = new Vector3f(left).cross(up).normalize();
        double[] sum = {0};
        PlumeRenderer.layout(f, 0, cam, left, up, (layer, piece, a, b, c, d) -> {
            // четырёхугольник (у кусков оболочки — трапеция): две половины по диагонали
            Vector3f ab = new Vector3f(b).sub(a), ac = new Vector3f(c).sub(a), ad = new Vector3f(d).sub(a);
            double projected = 0.5 * (Math.abs(new Vector3f(ab).cross(ac).dot(view)) + Math.abs(new Vector3f(ac).cross(ad).dot(view)));
            // оболочка и ядро светят вместе: сбоку площадь струи — у оболочки, ядро внутри неё
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

    /**
     * Огонь светит после мира, слоем эффектов: под Iris всё, что нарисовано путём сущностей или частиц Minecraft, проходит
     * через шейдерпак, и днём струя факела в десятки раз ярче белой стены выходила бледным обрывком (шлагбаум RV2,
     * старт сбоку). Самосветящегося — ни {@code RenderType.eyes}, ни сложения в буферы сущностей — в клиенте нет.
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
            assertEquals(List.of(), eyes, "светящееся — в слое эффектов");
        }
        String plume = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/render/PlumeRenderer.java"));
        assertTrue(!plume.contains("MultiBufferSource") && !plume.contains("VertexConsumer"), "струя — квадратами слоя, а не буфером сущности");
    }

    /**
     * Вдоль струи свет — из закона модели, а не картинкой: от сопла до конца пламени пламя (у ускорителя ракеты днём —
     * пересвет, белое), за ним хвост гаснет без скачков к точке Дрейпера и краснеет.
     */
    @Test
    void sheathFadesAlongTheJet() {
        Flame f = FlameModel.of(ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).motor(), 0);
        Flame.Sheath s = f.sheath();
        assertTrue(s.size() >= 8, "кусков вдоль струи хватает на плавный хвост");
        assertEquals(0, s.x()[0], 1e-12);
        assertEquals(f.length(), s.x()[s.size() - 1], 1e-9);
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.x()[i] > s.x()[i - 1]);
            assertTrue(s.luminance()[i] <= s.luminance()[i - 1] * (1 + 1e-9), "к хвосту не ярче");
            assertTrue((s.color()[i] >> 8 & 255) <= (s.color()[i - 1] >> 8 & 255), "к хвосту краснее");
        }
        // днём (привыкание 1) пламя до своего конца — ярче белого экрана, через полторы длины пламени — уже тусклее
        int atFlame = 0;
        while (s.x()[atFlame] < f.flame() - 1e-9) atFlame++;
        assertTrue(s.luminance()[atFlame] > 1, "конец пламени днём — пересвет: " + s.luminance()[atFlame]);
        int far = s.size() - 1;
        while (s.x()[far - 1] > 1.5 * f.flame()) far--;
        assertTrue(s.luminance()[far] < 1, "за полутора длинами пламени — тусклее белого: " + s.luminance()[far]);
    }
}
