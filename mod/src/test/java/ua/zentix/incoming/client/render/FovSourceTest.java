package ua.zentix.incoming.client.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Размер и место на экране того, что привязано к миру, — только из матрицы кадра ({@link ScreenProjection}): моды зума
 * (Ok Zoomer — на выходе {@code GameRenderer.getFov}) меняют поле зрения после события {@code ComputeFov} и мимо
 * {@code options.fov}, и размер, взятый оттуда, при зуме врёт (жалоба 23.10: метки при зуме). Поле зрения из настроек
 * или события читают только места из списка ниже — у каждого причина, почему зум им не нужен.
 */
class FovSourceTest {
    private static final Path CLIENT = Path.of("src/main/java/ua/zentix/incoming/client");
    /** Чтение поля зрения в обход матрицы кадра: настройка, событие {@code ComputeFov}, сам {@code getFov}. */
    private static final Pattern FOV = Pattern.compile("options\\.fov\\(\\)|\\.getFOV\\(\\)|gameRenderer\\.getFov|\\.fov\\(\\)\\.get\\(\\)");

    /**
     * Разрешённые места: файл → сколько раз.
     * <ul>
     * <li>{@code nuclear/NukeFlash.java} — сетчатка игрока: послеобраз, когда кадр не его глазами (камера снаряда, вид от
     * третьего лица), и ожоги сетчатки, которые лежат на ней самой, — поле зрения его глаза из настроек; от его глаз
     * послеобраз берёт матрицу кадра.</li>
     * <li>{@code cam/ProjectileCamera.java} — камера снаряда задаёт поле зрения (в событии), а не меряет им экран.</li>
     * </ul>
     */
    private static final Map<String, Integer> ALLOWED = Map.of("nuclear/NukeFlash.java", 2, "cam/ProjectileCamera.java", 3);

    @Test
    void screenSizesTakeTheFieldOfViewOnlyFromTheFrameMatrix() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(CLIENT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                Matcher m = FOV.matcher(Files.readString(p));
                int n = 0;
                while (m.find()) n++;
                if (n > 0) found.put(CLIENT.relativize(p).toString().replace('\\', '/'), n);
            }
        }
        assertEquals(new TreeMap<>(ALLOWED), found, "поле зрения не из матрицы кадра: размер на экране при зуме разойдётся с миром");
    }
}
