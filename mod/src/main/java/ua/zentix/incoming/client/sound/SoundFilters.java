package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.nuclear.Deafness;
import ua.zentix.incoming.client.nuclear.NukePall;
import ua.zentix.incoming.sound.Air;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Фильтр нижних частот OpenAL EFX на наши звуки: воздух по дороге «съедает» верха (чем дальше, тем глуше),
 * холм или дом между источником и ухом глушит ещё сильнее, а после близкой ударной волны всё глухо (оглушение);
 * в пелене пыли после ядерной волны звуки вдали глуше ({@link NukePall#muffle}).
 * <p>
 * Звук с фильтром — {@link Muffled}: разовый получает фильтр при старте ({@link PlaySoundSourceEvent}),
 * мотор снаряда — каждый тик ({@link #update}). Всё, что трогает OpenAL, идёт в звуковом потоке, где контекст
 * текущий; параметры фильтра копируются в источник при привязке, поэтому один объект фильтра на всех.
 * Путь наших звуков — наш и при Sound Physics Remastered: каждый наш звук играет в 3 блоках от уха в сторону источника
 * (или на месте, где его слышно с запаздыванием), громкость и верха по пути считает наша модель, а он видел бы только
 * эти 3 блока — дальний удар звучал бы как ближний. Поэтому прямой фильтр нашего звука — наш вместо его ({@link #direct}),
 * а сборке и игроку — строка {@code incoming:*} = 0 в его {@code sound_rates.properties} (README): так он не трогает
 * наши звуки вовсе (без своих отражений поверх нашего эха). Чужие звуки — его: на них наш фильтр оглушения и пелены
 * умножается на его ({@link Spr}), прямой фильтр у источника OpenAL один, и наш на минуты пелены снимал бы с каждого
 * звука его преграды.
 */
public final class SoundFilters {
    /**
     * Затухание верхов в воздухе, дБ на блок: ISO 9613-1 на 5 кГц — там, где фильтр EFX меряет верха (≈ 33 дБ/км при
     * 20 °C и 70%, {@link Air}).
     */
    private static final double AIR_DB_PER_BLOCK = Air.alpha(5000);

    /** Номер фильтра OpenAL (создаётся в звуковом потоке при первой нужде), −1 — EFX нет. */
    private static volatile int filter;
    private static Boolean soundPhysics;
    /**
     * Фильтр Sound Physics Remastered, каким он был при старте чужих петель (вода, маяк, петли модов): в оглушении и
     * пелене их фильтр мы ставим каждый тик ({@link #loops}), и его доля — отсюда. Только звуковой поток; ключи слабые —
     * звук забыт, запись уходит.
     */
    private static final Map<SoundInstance, float[]> SPR_AT_START = new WeakHashMap<>();
    /**
     * Чужие петли (вода и Незер вокруг, маяк, вагонетка, петли модов), которым сейчас стоит наш фильтр, и какой: его
     * ведёт {@link #loops}. Только поток клиента; ключи слабые.
     */
    private static final Map<SoundInstance, float[]> LOOPS = new WeakHashMap<>();
    /**
     * Меньше этого фильтр петли не меняется (вызовы OpenAL в каждом тике на каждую петлю — лишние). Только без Sound
     * Physics: с её {@code update_moving_sounds} она раз в несколько тиков ставит петле свой фильтр вместо нашего, и наш
     * нужен каждый тик (у порванных перепонок он не меняется неделями игры).
     */
    private static final float LOOP_STEP = 0.005f;
    /**
     * Слух вернулся разом (смерть, перемотка повтора, настройка): вернуть фильтр всем чужим петлям, и тем, что начались в
     * оглушении после прошлого {@link #loops} и в {@link #LOOPS} ещё не попали. Только поток клиента.
     */
    private static boolean restoreAll;

    private SoundFilters() {}

    /** Звук, которому нужен фильтр: общая громкость и доля верхов (0..1). */
    public interface Muffled {
        float lowpassGain();

        float lowpassHighs();
    }

    /** Воздух и преграды по пути наших звуков включены (настройка); Sound Physics их не отменяет — путь наш. */
    static boolean pathEffects() {
        return IncomingConfig.CLIENT.soundMuffling.get();
    }

    /** Стоит ли Sound Physics Remastered. */
    private static boolean soundPhysics() {
        if (soundPhysics == null) soundPhysics = ModList.get().isLoaded("sound_physics_remastered");
        return soundPhysics;
    }

    /** Доля верхов, дошедшая по воздуху за d блоков. */
    static float air(double d) {
        return pathEffects() ? (float) Math.pow(10, -AIR_DB_PER_BLOCK * d / 20) : 1;
    }

    /**
     * Есть ли между ухом и точкой твёрдые блоки (холм, дом): 1 — прямой путь, 0 — закрыто. Листва — не преграда: звук
     * она почти не держит (ISO 9613-2, прил. A: ~1 дБ на 10–20 м густой кроны), а кроны на пути к низко летящему снаряду
     * то закрывали бы, то открывали его, и мотор вдали дрожал бы громкостью. Клиентский мир: незагруженные чанки пусты,
     * ничего не грузится.
     */
    static float open(Vec3 from, Vec3 to) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (!pathEffects() || mc.level == null || player == null) return 1;
        return mc.level.clip(new ThroughLeaves(from, to, player)).getType() == HitResult.Type.MISS ? 1 : 0;
    }

    /**
     * Лучи {@link #openToward}: длина (короче ближних отражений эха, {@code TerrainEcho.NEAR}: склон, который отвечает,
     * — не преграда) и наклоны (вдоль, выше, круто вверх), рад.
     */
    private static final double TOWARD_REACH = 12;
    private static final double[] TOWARD_PITCH = {0, 0.35, 0.9};

    /**
     * Открыто ли ухо в сторону {@code azimuth} для далёкого источника там (эхо от склонов): доля трёх лучей на
     * {@link #TOWARD_REACH} блоков — вдоль, выше и круто вверх, — что не упёрлись в твёрдое (в доме — стены и крыша,
     * в окно — открыто). Для своих источников ({@link OwnSources}): их Sound Physics не трогает, поэтому — и при нём;
     * выключено настройкой — 1.
     */
    static float openToward(Vec3 ear, double azimuth) {
        return IncomingConfig.CLIENT.soundMuffling.get() ? openRays(ear, azimuth) : 1;
    }

    /**
     * Доля лучей {@link #openToward} в сторону {@code azimuth}, что не упёрлись в твёрдое, — без настройки звука (ветровая
     * тень стены у ядерной волны). Мира или игрока нет — 1.
     */
    public static float openRays(Vec3 ear, double azimuth) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (mc.level == null || player == null) return 1;
        int open = 0;
        for (double pitch : TOWARD_PITCH) {
            double c = Math.cos(pitch) * TOWARD_REACH;
            Vec3 to = ear.add(Math.cos(azimuth) * c, Math.sin(pitch) * TOWARD_REACH, Math.sin(azimuth) * c);
            if (mc.level.clip(new ThroughLeaves(ear, to, player)).getType() == HitResult.Type.MISS) open++;
        }
        return open / (float) TOWARD_PITCH.length;
    }

    /** Луч по твёрдым блокам, сквозь листву. */
    private static final class ThroughLeaves extends ClipContext {
        ThroughLeaves(Vec3 from, Vec3 to, Player player) {
            super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            return state.is(BlockTags.LEAVES) ? Shapes.empty() : super.getBlockShape(state, level, pos);
        }
    }

    /** Громкость и верха за преградой: закрытый путь — тише на 4 дБ и глухо (обход по дифракции). */
    static float blockedGain(float open) {
        return 1 - 0.37f * (1 - open);
    }

    static float blockedHighs(float open) {
        return 1 - 0.8f * (1 - open);
    }

    // ---------------------------------------------------------------- звуковой поток

    public static void onEngineLoad(SoundEngineLoadEvent e) {
        filter = 0;
        OwnSources.engineLoaded();
    }

    public static void onSound(PlaySoundSourceEvent e) {
        start(e.getSound(), e.getChannel().source);
    }

    public static void onStream(PlayStreamingSourceEvent e) {
        start(e.getSound(), e.getChannel().source);
    }

    private static void start(SoundInstance sound, int source) {
        float gain = 1, highs = 1;
        boolean own = sound instanceof Muffled;
        if (own) {
            Muffled m = (Muffled) sound;
            gain = m.lowpassGain();
            highs = m.lowpassHighs();
        }
        float m = NukePall.muffle(sound);
        gain *= Deafness.gain(sound) * (1 - 0.3f * m);
        highs *= Math.max(0.01f, Deafness.highs(sound) * (1 - 0.75f * m));
        boolean ours = gain < 0.999f || highs < 0.999f;
        // Sound Physics Remastered поставил свой фильтр этому звуку только что (в начале Channel.play, событие — сразу
        // после него, в той же задаче звукового потока)
        float[] spr = !own && (ours || sound.isLooping()) ? Spr.now() : null;
        // чужие петли ведём каждый тик в оглушении (loops): их фильтр Sound Physics нужен и потом
        if (spr != null && sound.isLooping()) SPR_AT_START.put(sound, spr);
        float[] f = direct(own, gain, highs, soundPhysics(), spr);
        if (f == null) return;
        set(source, f[0], f[1]);
        if (own) ownPath(source);
    }

    /**
     * Наш звук при Sound Physics: его поглощение воздухом по расстоянию OpenAL ({@code AL_AIR_ABSORPTION_FACTOR}, он
     * ставит его каждому звуку, и при строке 0 в {@code sound_rates} тоже) — обратно 0, как у движка Minecraft: воздух
     * по пути уже в нашем фильтре, а мотор стоит там, где его слышно, и верха вдали срезались бы дважды.
     */
    private static void ownPath(int source) {
        if (filter > 0 && soundPhysics()) AL10.alSourcef(source, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 0);
    }

    /**
     * Прямой фильтр звука при старте: громкость и доля верхов, или null — фильтр не ставить. Наш звук ({@code own}) —
     * наш фильтр вместо фильтра Sound Physics: путь наш, его фильтр видел только 3 блока до уха, и преграда у уха
     * считалась бы дважды; стоит Sound Physics ({@code soundPhysics}) — наш ставится и без глушения, чтобы снять его
     * (и тогда, когда его фильтр не прочитать). Чужой — наш поверх его ({@code spr}, null — нет или не прочитать):
     * оглушение и пелена, а путь — его.
     */
    static float @Nullable [] direct(boolean own, float gain, float highs, boolean soundPhysics, float @Nullable [] spr) {
        boolean ours = gain < 0.999f || highs < 0.999f;
        if (own) return ours || soundPhysics ? new float[]{gain, highs} : null;
        if (!ours) return null;
        return spr != null ? new float[]{gain * spr[0], highs * spr[1]} : new float[]{gain, highs};
    }

    /** Каждый тик для звука, который играет: новые параметры фильтра (мотор улетает, скрывается за холмом). */
    static void update(SoundInstance sound, float gain, float highs) {
        ChannelAccess.ChannelHandle handle = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.get(sound);
        if (handle == null) return;
        float m = NukePall.muffle(sound);
        float g = gain * Deafness.gain(sound) * (1 - 0.3f * m), h = highs * Math.max(0.01f, Deafness.highs(sound) * (1 - 0.75f * m));
        // звук наш ({@link Muffled}): его путь — наш, фильтр Sound Physics с его старта сюда не входит ({@link #direct}).
        // С её update_moving_sounds (по умолчанию выключено) она раз в несколько тиков ставит свой заново после нашего —
        // до нашего следующего тика
        handle.execute(channel -> {
            set(channel.source, g, h);
            ownPath(channel.source);
        });
    }

    /**
     * Тик клиента: фильтр оглушения и пелены на чужих петлях. Фильтр на источнике остаётся до конца звука, а петля
     * звучит, пока её не снимут: начатая до волны, она звучала бы чисто часы оглушения, а начатая в оглушении — глухо и
     * после. Ставится, пока он не 1, и один раз — когда снова 1 (фильтр Sound Physics с её старта — обратно); новые
     * оценки Sound Physics для таких петель ({@code updateMovingSounds}) он, пока стоит, перекрывает, как у наших петель.
     */
    public static void loops() {
        SoundEngine engine = Minecraft.getInstance().getSoundManager().soundEngine;
        boolean all = restoreAll;
        restoreAll = false;
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> e : engine.instanceToChannel.entrySet()) {
            SoundInstance sound = e.getKey();
            if (sound instanceof Muffled || !sound.isLooping()) continue;
            float m = NukePall.muffle(sound);
            float g = Deafness.gain(sound) * (1 - 0.3f * m), h = Math.max(0.01f, Deafness.highs(sound) * (1 - 0.75f * m));
            boolean on = g < 0.999f || h < 0.999f;
            float[] was = LOOPS.get(sound);
            if (!on && was == null && !all) continue;
            if (on && was != null && !soundPhysics() && Math.abs(g - was[0]) < LOOP_STEP && Math.abs(h - was[1]) < LOOP_STEP) continue;
            if (on) LOOPS.put(sound, new float[]{g, h});
            else LOOPS.remove(sound);
            e.getValue().execute(channel -> {
                float[] spr = SPR_AT_START.get(sound);
                if (spr != null) set(channel.source, g * spr[0], h * spr[1]);
                else if (on) set(channel.source, g, h);
                // свой фильтр ставили, только если EFX есть (без него AL_DIRECT_FILTER — ошибка, её принял бы движок)
                else if (filter > 0) AL10.alSourcei(channel.source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            });
        }
    }

    /** Слух вернулся разом: следующий {@link #loops} вернёт фильтр всем чужим петлям. Поток клиента. */
    public static void restoreLoops() {
        restoreAll = true;
    }

    /**
     * Sound Physics Remastered: его фильтр прямого звука ({@code SoundPhysics.directFilter0}, один на все звуки — перед
     * каждым он ставит в него параметры и копирует их в источник). Внутренности чужого мода, через отражение: не нашлось —
     * один раз в лог, и наш фильтр снова ставится вместо его.
     */
    private static final class Spr {
        @Nullable
        private static Field direct;
        @Nullable
        private static Object enabled;
        @Nullable
        private static Method get;
        private static boolean tried, logged;

        /** Громкость и доля верхов, которые он только что поставил звуку; null — его нет, он выключен или не прочитать. */
        @Nullable
        static float[] now() {
            if (!soundPhysics() || !find()) return null;
            try {
                if (!Boolean.TRUE.equals(get.invoke(enabled))) return null;
                int id = direct.getInt(null);
                if (id == 0 || !EXTEfx.alIsFilter(id)) return null;
                return new float[]{EXTEfx.alGetFilterf(id, EXTEfx.AL_LOWPASS_GAIN), EXTEfx.alGetFilterf(id, EXTEfx.AL_LOWPASS_GAINHF)};
            } catch (ReflectiveOperationException | RuntimeException ex) {
                fail(ex);
                return null;
            }
        }

        private static boolean find() {
            if (!tried) {
                tried = true;
                try {
                    Field f = Class.forName("com.sonicether.soundphysics.SoundPhysics").getDeclaredField("directFilter0");
                    f.setAccessible(true);
                    Object config = Class.forName("com.sonicether.soundphysics.SoundPhysicsMod").getField("CONFIG").get(null);
                    Object entry = config.getClass().getField("enabled").get(config);
                    get = Class.forName("de.maxhenkel.sound_physics_remastered.configbuilder.entry.ConfigEntry").getMethod("get");
                    enabled = entry;
                    direct = f;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    fail(ex);
                }
            }
            return direct != null;
        }

        private static void fail(Throwable ex) {
            if (!logged) {
                logged = true;
                Incoming.LOG.warn("Sound Physics Remastered filter not readable, deafness and nuclear dust muffling replace it: {}", ex.toString());
            }
            direct = null;
            tried = true;
        }
    }

    private static void set(int source, float gain, float highs) {
        if (filter == 0) filter = create();
        if (filter < 0) return;
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, Math.max(0, Math.min(1, gain)));
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0.01f, Math.min(1, highs)));
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
    }

    private static int create() {
        try {
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) return -1;
            int f = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) return -1;
            return f;
        } catch (RuntimeException | LinkageError ex) {
            Incoming.LOG.warn("OpenAL EFX unavailable — no distance muffling or deafness for sounds: {}", ex.toString());
            return -1;
        }
    }
}
