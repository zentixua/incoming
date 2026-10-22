package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Одно привыкание глаза на кадр ({@link Sight#exposure}, {@link FarView#exposure}): всё, что слой эффектов рисует светом
 * обычного оружия, — тело огня, блик и вуаль, свет на дыме, частицах и мире, струя у сущности — берёт привыкание кадра,
 * а не одно ночное по свету неба ({@link Sight#adapted}). Иначе в одном кадре два глаза: свет на мир привыкал к огню, а
 * блик факела в 8 м — нет, и ночной старт заливал кадр оранжевым (22.10). {@link Sight#adapted} — только у ядерной
 * картинки (её глаз — свой, ядерка этим не меняется), у самого {@link Sight} и у вспышки на экране ({@code FlashFalloff}:
 * в миг вспышки глаз ещё не привык — к ней он и привыкает).
 */
class ExposureSourceTest {
    private static final Path CLIENT = Path.of("src/main/java/ua/zentix/incoming/client");
    private static final Set<String> ALLOWED = Set.of("far/Sight.java", "fx/FlashFalloff.java", "fx/layer/PallPass.java");

    @Test
    void conventionalLightUsesTheFrameExposure() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CLIENT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = CLIENT.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("nuclear/") || ALLOWED.contains(rel)) continue;
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("//")) continue;
                    if (line.contains("Sight.adapted(") || line.contains(" adapted(") && !line.contains("double adapted(")) bad.add(rel + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), bad, "свет обычного оружия с ночным привыканием, мимо привыкания кадра");
    }

    /**
     * Клубы дыма слоя светит тот же свет неба, что блоки и частицы под открытым небом ({@link FarView#sky}, карта освещения),
     * а не {@link FarView#ambient}: с ним белый дым ночью выходил темнее освещённого луной камня рядом (ночной след ЗРК
     * клубами, 22.10).
     */
    @Test
    void puffsTakeTheWorldsSkyLight() throws IOException {
        String text = Files.readString(CLIENT.resolve("far/SmokePuffs.java"));
        assertEquals(false, text.contains("view.ambient()"), "SmokePuffs: свет неба клубов — FarView.sky, не ambient");
        assertEquals(true, text.contains("view.sky()"), "SmokePuffs: свет неба клубов — FarView.sky");
    }
}
