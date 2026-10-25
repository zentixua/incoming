package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Петля окружения, которую ведёт хозяин (гул горящего города, ветер в огонь): каждый тик он ставит, откуда она
 * слышна, громкость и расстояние до источника (воздух съедает верха, {@link SoundFilters}); громкость меняется
 * плавно. Хозяин перестал её вести (подрыв убран, повтор перемотан) — петля сама стихает и уходит.
 */
public class AmbientLoop extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** На столько громкость подходит к цели за тик (за секунду — из тишины в полную). */
    private static final float SLEW = 0.05f;
    /** Хозяин молчит столько тиков — петля уходит. */
    private static final int ORPHAN = 10;

    private float slew;
    private float target, highs = 1, open = 1;
    private int quiet;
    private boolean ending;

    public AmbientLoop(SoundEvent event, SoundSource source, float pitch, long seed) {
        this(event, source, pitch, seed, SLEW);
    }

    /** {@code slew} — на столько громкость подходит к цели за тик (1 — сразу: порыв ветра за фронтом волны). */
    public AmbientLoop(SoundEvent event, SoundSource source, float pitch, long seed, float slew) {
        super(event, source, RandomSource.create(seed));
        this.slew = slew;
        looping = true;
        delay = 0;
        volume = 0.001f;
        this.pitch = pitch;
        relative = false;
        attenuation = Attenuation.NONE;
    }

    /**
     * Хозяин: звучать с направления {@code toward} от уха (в 3 блоках от него), с громкостью {@code volume}, источник
     * за {@code distance} блоков.
     */
    public void set(Vec3 ear, Vec3 toward, float volume, double distance) {
        set(ear, toward, volume, distance, 1);
    }

    /** As above, with the current openness of the path (walls/terrain); 1 leaves the path unobstructed. */
    public void set(Vec3 ear, Vec3 toward, float volume, double distance, float open) {
        Vec3 dir = toward.subtract(ear);
        Vec3 at = dir.lengthSqr() < 9 ? toward : ear.add(dir.normalize().scale(3));
        x = at.x;
        y = at.y;
        z = at.z;
        target = Math.max(0, Math.min(1, volume));
        highs = SoundFilters.air(distance);
        this.open = open;
        quiet = 0;
    }

    /**
     * Как {@link #set(Vec3, Vec3, float, double)}, но верха пути уже посчитал хозяин ({@code highs}: доля верхов, 1 —
     * все) и тон задаёт он же (гул взрывов: средние по его членам, {@link Rumble}).
     */
    public void set(Vec3 ear, Vec3 toward, float volume, float highs, float pitch) {
        set(ear, toward, volume, 0);
        this.highs = highs;
        this.pitch = pitch;
    }

    /** Громкость — сразу цель, без подхода (петля, что встаёт посреди звучащего: её громкость уже посчитана). */
    public void prime() {
        volume = target;
    }

    /** Стихнуть и уйти: не быстрее обычного, даже у петли с быстрым подходом (без щелчка). */
    public void end() {
        ending = true;
        target = 0;
        slew = Math.min(slew, SLEW);
    }

    /**
     * Звучит ли: движок мог её не взять (нет каналов) или снять (перезапуск звука, ползунок категории в 0). Не
     * {@code SoundManager.isActive}: снятый ползунком канал ({@code SoundEngine.tickNonPaused}) он по записи о запуске
     * и дальше считает звучащим (как у {@link SirenSound#playing}).
     */
    public boolean playing() {
        return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
    }

    @Override
    public void tick() {
        if (++quiet > ORPHAN) end();
        volume += Math.max(-slew, Math.min(slew, target - volume));
        if (ending && volume <= 0.002f) {
            stop();
            return;
        }
        SoundFilters.update(this, lowpassGain(), lowpassHighs());
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float lowpassGain() {
        return SoundFilters.blockedGain(open);
    }

    @Override
    public float lowpassHighs() {
        return highs * SoundFilters.blockedHighs(open);
    }
}
