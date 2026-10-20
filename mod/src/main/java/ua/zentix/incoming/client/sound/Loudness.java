package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;

/**
 * Громкость в игре по уровню звука у уха — одна мерка на весь звук моторов мода. Петли и разовые звуки старта собраны
 * к одной громкости ({@code tools/build_sounds.py}: {@code ENGINE_LUFS}), поэтому громкость в игре задаёт только
 * уровень у уха.
 * <ul>
 * <li>Громкость — половина децибел уровня (10<sup>ΔL/40</sup>), как у далёких взрывов ({@code Outdoor}) и ближних
 * ({@code BlastMix}: −3 дБ на удвоение расстояния): у колонок дома ~60 дБ от шума комнаты до громкого, а снаружи —
 * ~110 дБ от тихой ночи до рёва ракеты на старте. Это отступление от физики — перенос в колонки, а не закон
 * распространения: само распространение ({@link Propagation}) считается в полных децибелах.</li>
 * <li>Мерка — фон тихой ночи ({@link Propagation#NIGHT}): звук на уровне фона звучит громкостью, с которой слой берёт
 * канал ({@link VoiceBudget#AUDIBLE}), — порог слышимости в игре там же, где в жизни; отсюда громкость 1 — у
 * {@link #REF} дБА (тише рёва старта ракеты вблизи — тот упирается в 1).</li>
 * <li>Звук тонет в фоне за последние {@link Propagation#FADE} дБ над ним в своей октаве (частичная маскировка): днём и
 * в дождь фон выше, и тот же звук слышно ближе.</li>
 * </ul>
 */
final class Loudness {
    /** A-уровень у уха, который звучит громкостью 1, дБА. */
    static final double REF = Propagation.NIGHT + 40 * Math.log10(1 / VoiceBudget.AUDIBLE);

    private Loudness() {}

    /**
     * Громкость в игре звука с A-уровнем {@code level} у уха, который громче фона на {@code margin} дБ в самой выгодной
     * октаве ({@link Propagation#margin}).
     */
    static double gain(double level, double margin) {
        if (!(margin > 0)) return 0;
        return Math.min(1, Math.pow(10, (level - REF) / 40)) * Propagation.smoothstep(0, Propagation.FADE, margin);
    }
}
