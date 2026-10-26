package ua.zentix.incoming.client.sound;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Эхо взрыва от рельефа у этого слушателя — у любого взрыва, ядерного ({@code client.nuclear.NukeEchoes}) и обычного
 * ({@link BlastSounds}): пока фронт идёт, лучи из уха по карте высот
 * ({@link TerrainEcho}, по {@link #RAYS_PER_TICK} за тик) находят склоны, которые вернут удар; запись прямого удара
 * читается в фоне. Когда фронт дошёл и лучи готовы — в фоне же сводятся дорожки по сторонам ({@link EchoMix}), и они
 * встают в очередь своих источников OpenAL ({@link OwnSources}) от настоящего начала прямого удара. Лучей не хватило
 * (слушатель вошёл или перенёсся к самому приходу фронта) — они доводятся по {@link #ARRIVAL_RAYS} за тик, и эхо
 * начинается позже (начало дорожек, что уже прошло, пропускается). Только вживую.
 */
public class BlastEchoes {
    /** Лучей за тик, пока фронт идёт (все {@link TerrainEcho#RAYS} — за 6 тиков; бюджет чтений рельефа). */
    static final int RAYS_PER_TICK = 12;
    /** Лучей за тик, когда фронт уже у слушателя, а лучи не готовы (бюджет). */
    static final int ARRIVAL_RAYS = 24;
    /** Слушатель ушёл дальше этого от места, откуда пущены лучи, — лучи заново (бюджет: путь эха сбит до 2×64 м). */
    private static final double MOVED = 64;
    /** Зерно — столько секунд начала записи прямого удара (подача). */
    public static final double GRAIN_SECONDS = 2.8;
    /** Посыл в хвост реверберации (подача, −12 дБ). */
    private static final float SEND = 0.25f;

    private final OwnSources.Clock clock;
    private final Vec3 burst;
    private final TerrainEcho.Blast blast;
    /** Запись прямого удара: её вариант выбирает это зерно — то же, что у прямого удара. */
    public final long seed;
    private final CompletableFuture<float[]> grain;
    private final List<TerrainEcho.Reflection> found = new ArrayList<>();
    @Nullable
    private Vec3 from;
    private int nextRay;
    @Nullable
    private CompletableFuture<EchoMix.Mix> mix;
    @Nullable
    private EchoMix.Mix done;
    @Nullable
    private OwnSources.Anchor anchor;
    /** Прямой удар, от начала которого ждёт эхо (для строки сценария: какая запись). */
    @Nullable
    private SoundInstance direct;
    /** Самая долгая порция лучей за тик, нс (для строки сценария). */
    private long traceSlowest;
    /** Где слушателя застал фронт (лучи доводятся отсюда); null — фронт ещё не дошёл. */
    @Nullable
    private Vec3 heard;
    private float volume, pitch;
    /** Взрыв в смеси взрывов ({@link #bus}); null — без смеси. */
    @Nullable
    private Mix.Blast bus;
    /** Огибающие дорожек для смеси ({@link Envelope#of}, {@link Envelope#energy}) — в фоне, вместе со сведением. */
    @Nullable
    private CompletableFuture<float[][][]> profiles;
    private int queued;
    private boolean stopped;

    /**
     * @param clock часы взрыва, по которым ждёт начало прямого удара
     * @param burst где взрыв
     * @param blast приход и давление его фронта
     * @param sound запись прямого удара: её начало — зерно эха
     * @param seed  зерно, которым выбран вариант записи прямого удара
     */
    public BlastEchoes(OwnSources.Clock clock, Vec3 burst, TerrainEcho.Blast blast, SoundEvent sound, long seed) {
        this.clock = clock;
        this.burst = burst;
        this.blast = blast;
        this.seed = seed;
        this.grain = CompletableFuture.supplyAsync(() -> read(sound, seed), Util.backgroundExecutor())
                .exceptionally(e -> {
                    Incoming.LOG.warn("Terrain echo: can't read the blast sound: {}", e.toString());
                    return new float[0];
                });
    }

    /** Фронт ещё идёт: часть лучей. */
    public void approach(Vec3 ear) {
        trace(ear, RAYS_PER_TICK);
    }

    /**
     * Прямой удар вот-вот уйдёт движку: эхо — от его настоящего начала. До {@code play}: с буфером, который движок уже
     * держит (второй подрыв за игру), звук стартует в потоке звука раньше, чем {@code play} вернётся.
     *
     * @param tick   тик по часам взрыва, когда прямой удар отдают движку
     * @param direct прямой удар (null — начало по тику)
     */
    public void expect(double tick, @Nullable SoundInstance direct) {
        if (stopped) return;
        this.direct = direct;
        this.anchor = OwnSources.anchor(this, direct, clock, tick);
    }

    /**
     * Эхо обычного взрыва — в смеси взрывов ({@link Mix}) вместе с его прямым ударом: дорожки — слои взрыва {@code blast}
     * по мощности, со своей задержкой (без них позднее эхо, когда прямой удар уже стих, взрыв считался бы тихим, и его
     * множитель гасил бы эхо среди других взрывов); громкость дорожек — × множитель взрыва. У ядерки смеси нет. Хвост
     * реверберации ({@link OwnSources#reverb}: посыл 0,25, поздние отражения 1,2) в смесь не идёт: он на ~10 дБ ниже эха,
     * а эхо — на 10–20 дБ ниже прямого удара, к сумме это меньше 0,05 дБ. Звать до {@link #arrive}.
     */
    void bus(@Nullable Mix.Blast blast) {
        this.bus = blast;
    }

    /**
     * Фронт у слушателя: недосчитанные лучи — по {@link #ARRIVAL_RAYS} за тик, дальше сведение в фоне.
     *
     * @param volume громкость прямого удара (эхо — доля её)
     * @param pitch  высота тона прямого удара (эхо — того же звука)
     */
    public void arrive(Vec3 ear, float volume, float pitch) {
        if (stopped) return;
        this.heard = ear;
        this.volume = volume;
        this.pitch = pitch;
        finish();
    }

    /** Лучи из места прихода фронта — ещё порция; все готовы — сведение в фоне. */
    private void finish() {
        Vec3 ear = heard;
        if (ear == null || mix != null) return;
        trace(ear, ARRIVAL_RAYS);
        // в измерении с потолком лучей нет
        ClientLevel level = Minecraft.getInstance().level;
        if (nextRay < TerrainEcho.RAYS && level != null && !level.dimensionType().hasCeiling()) return;
        List<TerrainEcho.Reflection> list = List.copyOf(found);
        float p = pitch;
        mix = grain.thenApplyAsync(g -> EchoMix.render(EchoMix.pitch(g, p), list), Util.backgroundExecutor())
                .exceptionally(e -> {
                    Incoming.LOG.warn("Terrain echo: can't mix the echoes: {}", e.toString());
                    return EchoMix.Mix.NONE;
                });
        if (bus != null) profiles = mix.thenApplyAsync(BlastEchoes::profiles, Util.backgroundExecutor()).exceptionally(e -> {
            Incoming.LOG.warn("Terrain echo: can't measure the echoes for the blast mix: {}", e.toString());
            return null;
        });
    }

    /** Огибающая и энергия каждой дорожки ({@link EchoMix#RATE}), для смеси. */
    private static float[][][] profiles(EchoMix.Mix m) {
        float[][][] out = new float[m.tracks().size()][][];
        for (int i = 0; i < out.length; i++) {
            short[] pcm = m.tracks().get(i).pcm();
            float[] x = new float[pcm.length];
            for (int j = 0; j < x.length; j++) x[j] = pcm[j] / 32768f;
            out[i] = new float[][]{Envelope.of(x, EchoMix.RATE), Envelope.energy(x, EchoMix.RATE)};
        }
        return out;
    }

    /** Каждый тик после удара: лучи, которых не хватило, и готовые дорожки — в очередь своих источников. */
    public void tick() {
        if (stopped) return;
        if (mix == null) finish();
        if (mix == null || anchor == null || done != null || !mix.isDone() || profiles != null && !profiles.isDone()) return;
        done = mix.getNow(EchoMix.Mix.NONE);
        if (done.tracks().isEmpty()) return;
        OwnSources.reverb(done.t60(), SEND);
        Mix.Blast blast = bus;
        OwnSources.Anchor start = anchor;
        float[][][] env = blast == null || profiles == null ? null : profiles.getNow(null);
        queued += queue(done, env, volume, blast, (t, gain) -> OwnSources.play(this, start, t.start(), t.azimuth(), gain, t.pcm(), EchoMix.RATE, false,
                false, blast == null ? null : blast::factor));
    }

    /** Дорожка эха — своему источнику: false — места нет. */
    @FunctionalInterface
    interface Player {
        boolean play(EchoMix.Track track, float gain);
    }

    /**
     * Дорожки {@code done} — своим источникам ({@code player}) с громкостью прямого удара {@code volume} × своей, принятые —
     * слоями взрыва {@code blast} в смеси (огибающие {@code env}; null — без смеси): от начала прямого удара со своей
     * задержкой, как их голоса. Сколько принято.
     */
    static int queue(EchoMix.Mix done, float @Nullable [][][] env, float volume, @Nullable Mix.Blast blast, Player player) {
        int queued = 0;
        for (int i = 0; i < done.tracks().size(); i++) {
            EchoMix.Track t = done.tracks().get(i);
            float gain = Math.min(16, volume * t.gain());
            if (!player.play(t, gain)) continue;
            queued++;
            if (blast != null && env != null && i < env.length) blast.add(Mix.Layer.own(env[i][0], env[i][1], gain, t.start()));
        }
        return queued;
    }

    /** Дорожки уже в очереди своих источников (или их нет и не будет): больше эхо ничего не готовит. */
    public boolean settled() {
        return stopped || done != null;
    }

    /** Взрыв, чьё эхо ещё не готово, кончился (фронт так и не дошёл до уха): ожидание — прочь; готовое звучит дальше. */
    public void release() {
        if (!settled()) stop();
    }

    /** Взрыв убран или стал прошлым: голоса и ожидание начала — прочь, новых нет. */
    public void stop() {
        stopped = true;
        OwnSources.stop(this);
    }

    private void trace(Vec3 ear, int rays) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimensionType().hasCeiling()) return;
        if (from == null || from.distanceTo(ear) > MOVED) {
            from = ear;
            nextRay = 0;
            found.clear();
        }
        if (nextRay >= TerrainEcho.RAYS) return;
        long t0 = System.nanoTime();
        Sightline.Heights heights = ground(level);
        Vec3 b = burst;
        for (int i = 0; i < rays && nextRay < TerrainEcho.RAYS; i++, nextRay++) {
            TerrainEcho.ray(heights, nextRay, TerrainEcho.RAYS, TerrainEcho.RANGE, from.x, from.y, from.z, b.x, b.y, b.z, blast, found);
        }
        traceSlowest = Math.max(traceSlowest, System.nanoTime() - t0);
    }

    /** Карта высот без пустоты под миром (у неё верх — дно мира). */
    private static Sightline.Heights ground(ClientLevel level) {
        Sightline.Heights h = FarTerrain.of(level);
        int floor = level.getMinBuildHeight() + 1;
        return (x, z) -> {
            double v = h.at(x, z);
            return v <= floor ? Double.NaN : v;
        };
    }

    /**
     * Начало записи прямого удара (тот вариант, что выберет зерно) — в зерно {@link EchoMix#RATE}. В фоновом потоке:
     * ресурсы читаются так же, как их читает загрузка звуков.
     */
    private static float[] read(SoundEvent direct, long seed) {
        Minecraft mc = Minecraft.getInstance();
        WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(direct.getLocation());
        if (event == null) return new float[0];
        Sound sound = event.getSound(RandomSource.create(seed));
        try (InputStream in = mc.getResourceManager().open(sound.getPath()); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int rate = (int) ogg.getFormat().getSampleRate(), channels = ogg.getFormat().getChannels();
            int limit = (int) (GRAIN_SECONDS * rate * channels) + 8192;
            FloatArrayList samples = new FloatArrayList(limit);
            while (samples.size() < limit && ogg.readChunk(samples::add)) {
                // читаем до нужной длины
            }
            return EchoMix.grain(samples.toFloatArray(), channels, rate, GRAIN_SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Строка {@code SCENARIO}: лучи, отражения и сильнейшие из них (задержка, сторона, уровень). */
    public String describe() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "echo rays %d/%d (slowest tick %.2f ms) taps %d", nextRay, TerrainEcho.RAYS,
                traceSlowest / 1e6, found.size()));
        EchoMix.Mix m = done;
        if (m != null) {
            Vec3 e = from, bu = burst;
            if (e != null) sb.append(String.format(Locale.ROOT, " ear %.0f,%.0f,%.0f burst %.0f,%.0f,%.0f", e.x, e.y, e.z, bu.x, bu.y, bu.z));
            double sum = found.stream().mapToDouble(TerrainEcho.Reflection::energy).sum();
            // начало: по старту прямого удара в движке (sound) или по тику (tick — движок его не запустил за срок)
            var sound = direct == null ? null : direct.getSound();
            sb.append(String.format(Locale.ROOT, " sum %.1fdB tracks %d queued %d t60 %.1f vol %.2f start +%.2fs by %s of %s", 10 * Math.log10(Math.max(1e-9, sum)),
                    m.tracks().size(), queued, m.t60(), volume, anchor == null ? Double.NaN : anchor.lateSeconds(),
                    anchor == null ? "-" : anchor.heard() ? "sound" : "tick", sound == null ? "-" : sound.getLocation().getPath()));
            for (EchoMix.Track t : m.tracks()) {
                sb.append(String.format(Locale.ROOT, " [az %.0f start %.2fs len %.1fs x%.2f]", Math.toDegrees(t.azimuth()), t.start() / 20, t.seconds(), t.gain()));
            }
            found.stream().sorted(Comparator.comparingDouble(TerrainEcho.Reflection::energy).reversed()).limit(6).forEach(r ->
                    sb.append(String.format(Locale.ROOT, " {d %.2fs az %.0f %.1fdB r %.0f at %.0f,%.0f,%.0f}", r.delay() / 20, Math.toDegrees(r.azimuth()),
                            10 * Math.log10(r.energy()), r.distance(), r.x(), r.y(), r.z())));
        }
        return sb.toString();
    }
}
