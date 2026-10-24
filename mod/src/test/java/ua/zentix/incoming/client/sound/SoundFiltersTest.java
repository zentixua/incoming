package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Прямой фильтр при старте звука: наш путь у наших звуков и при Sound Physics Remastered, его путь — у чужих. */
class SoundFiltersTest {
    /** Фильтр Sound Physics: преграда у уха (наш звук стоит в 3 блоках от него), −4 дБ и глухо. */
    private static final float[] SPR = {0.63f, 0.2f};

    @Test
    void ownSoundKeepsOurPathAndReplacesSoundPhysics() {
        // дальний удар: верха съел воздух — наш фильтр, без умножения на фильтр Sound Physics
        assertArrayEquals(new float[]{1f, 0.3f}, SoundFilters.direct(true, 1f, 0.3f, true, SPR), 1e-6f);
        // открытый близкий: наш фильтр 1 всё равно ставится — снять его фильтр, иначе преграда считалась бы дважды
        assertArrayEquals(new float[]{1f, 1f}, SoundFilters.direct(true, 1f, 1f, true, SPR), 1e-6f);
        // Sound Physics стоит, а его фильтр не прочитать — наш всё равно ставится
        assertArrayEquals(new float[]{1f, 1f}, SoundFilters.direct(true, 1f, 1f, true, null), 1e-6f);
        // без Sound Physics открытому звуку фильтр не нужен
        assertNull(SoundFilters.direct(true, 1f, 1f, false, null));
        assertArrayEquals(new float[]{0.8f, 0.5f}, SoundFilters.direct(true, 0.8f, 0.5f, false, null), 1e-6f);
    }

    @Test
    void foreignSoundKeepsSoundPhysicsPathUnderOurDeafness() {
        // оглушение поверх его преград
        assertArrayEquals(new float[]{0.5f * 0.63f, 0.1f * 0.2f}, SoundFilters.direct(false, 0.5f, 0.1f, true, SPR), 1e-6f);
        // без оглушения его фильтр не трогаем
        assertNull(SoundFilters.direct(false, 1f, 1f, true, SPR));
        assertArrayEquals(new float[]{0.5f, 0.1f}, SoundFilters.direct(false, 0.5f, 0.1f, false, null), 1e-6f);
    }
}
