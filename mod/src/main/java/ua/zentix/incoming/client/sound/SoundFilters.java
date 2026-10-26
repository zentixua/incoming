package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
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
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.sound.Air;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * OpenAL EFX low-pass for our sounds: air along the path “absorbs” highs (farther means more muffled), a hill or
 * house between source and ear muffles further, and after a nearby shock wave everything is muffled (deafness);
 * distant sounds are more muffled in the dust pall after a nuclear wave ({@link NukePall#muffle}).
 * <p>
 * Filtered sound implements {@link Muffled}: one-shots get a filter at start ({@link PlaySoundSourceEvent}),
 * projectile engine each tick ({@link #update}). All OpenAL access runs on sound thread with current context;
 * binding copies filter parameters to source, so one filter object serves all.
 * Our sound paths remain ours with Sound Physics Remastered: each sound plays 3 blocks from ear toward source
 * (or at its retarded audible position); our model computes path volume and highs, whereas it would see only
 * these 3 blocks — distant impact would sound near. So our direct filter replaces its filter ({@link #direct});
 * modpack and player need a 0 entry for every {@code incoming:} sound in its {@code sound_rates.properties}
 * (README), preventing its reflections over our echoes. Our filter overwrites its new playing-sound evaluations
 * ({@code update_moving_sounds}) every tick ({@link #OWN}). Other sounds use its path: our deafness/pall filter
 * multiplies its filter ({@link Spr}); OpenAL source has one direct filter, and ours would otherwise remove
 * barriers from every sound for the pall's minutes.
 */
public final class SoundFilters {
    /** Frequency where EFX measures highs, Hz: air high attenuation is ISO 9613-1 at this frequency ({@link Air}). */
    private static final double HIGHS_HZ = 5000;

    /** OpenAL filter ID (created on sound thread on first need), −1 — no EFX. */
    private static volatile int filter;
    private static Boolean soundPhysics;
    /**
     * Sound Physics Remastered filter at the start of others' loops (water, beacon, mod loops): during deafness
     * and pall we set their filter each tick ({@link #loops}), taking its share from here. Sound thread only;
     * weak keys — forget sound, entry goes away.
     */
    private static final Map<SoundInstance, float[]> SPR_AT_START = new WeakHashMap<>();
    /**
     * Our filter on our sounds while Sound Physics is present: its {@code update_moving_sounds} re-evaluates each
     * playing sound every few ticks, setting its own filter and air absorption even with a 0 entry in
     * {@code sound_rates}; {@link #loops} restores ours each tick after it. Sound thread only; weak keys.
     */
    private static final Map<SoundInstance, float[]> OWN = new WeakHashMap<>();
    /**
     * Others' loops (ambient water and Nether, beacon, minecart, mod loops) currently carrying our filter, and its
     * values: managed by {@link #loops}. Client thread only; weak keys.
     */
    private static final Map<SoundInstance, float[]> LOOPS = new WeakHashMap<>();
    /**
     * Loop filter does not change below this (OpenAL calls per loop per tick are unnecessary). Only without
     * Sound Physics: its {@code update_moving_sounds} replaces our loop filter every few ticks, so ours is needed
     * every tick (with ruptured eardrums it stays unchanged for game weeks).
     */
    private static final float LOOP_STEP = 0.005f;
    /**
     * Hearing returned at once (death, replay rewind, setting): restore filters to all others' loops, including
     * those started during deafness since last {@link #loops} and not yet in {@link #LOOPS}. Client thread only.
     */
    private static boolean restoreAll;

    private SoundFilters() {}

    /** Sound needing a filter: overall gain and high fraction (0..1). */
    public interface Muffled {
        float lowpassGain();

        float lowpassHighs();
    }

    /** Air and barriers on our sound paths enabled (setting); Sound Physics does not override them — path is ours. */
    static boolean pathEffects() {
        return IncomingConfig.CLIENT.soundMuffling.get();
    }

    /** Whether Sound Physics Remastered is installed. */
    private static boolean soundPhysics() {
        if (soundPhysics == null) soundPhysics = ModList.get().isLoaded("sound_physics_remastered");
        return soundPhysics;
    }

    /**
     * High fraction surviving d blocks of air: 5 kHz attenuation at world's surface-air humidity
     * ({@link ClientAir}: clear — 33 dB/km, rain — 27).
     */
    static float air(double d) {
        if (!pathEffects()) return 1;
        return (float) Math.pow(10, -Air.alphaAt(HIGHS_HZ, ClientAir.now().surfaceHumidity()) * d / 20);
    }

    /**
     * Solid blocks between ear and point (hill, house): 1 — direct path, 0 — blocked. Foliage is not a barrier:
     * it barely blocks sound (ISO 9613-2, annex A: ~1 dB per 10–20 m dense canopy), while crowns on a path to a
     * low-flying projectile would alternately block and open it, making distant engine volume flutter. Client
     * world: unloaded chunks are empty, nothing is loaded.
     */
    static float open(Vec3 from, Vec3 to) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ClientLevel level = mc.level;
        if (!pathEffects() || level == null || player == null) return 1;
        Vec3 end = from.add(to.subtract(from).scale(reach(from, to, level::hasChunk)));
        return level.clip(new ThroughLeaves(from, end, player)).getType() == HitResult.Type.MISS ? 1 : 0;
    }

    /** Sound path from ear: barriers and air (client world and setting; substituted in checks). */
    interface Path {
        /** Open-path fraction from {@code from} to {@code to} ({@link SoundFilters#open}). */
        float open(Vec3 from, Vec3 to);

        /** High fraction air leaves over {@code distance} blocks ({@link SoundFilters#air}). */
        float air(double distance);
    }

    /** Path in client world. */
    static final Path WORLD = new Path() {
        @Override
        public float open(Vec3 from, Vec3 to) {
            return SoundFilters.open(from, to);
        }

        @Override
        public float air(double distance) {
            return SoundFilters.air(distance);
        }
    };

    /** Whether chunk column is loaded on client. */
    interface Loaded {
        boolean at(int cx, int cz);
    }

    /**
     * How far a ray from {@code from} to {@code to} passes through loaded client chunks: path fraction (0..1),
     * end of last loaded column along ray (column traversal, Amanatides–Woo). Beyond, client world is empty:
     * unloaded chunk returns air, blocking nothing; tracing to the target would give the same result with an
     * order of magnitude more steps (fire at 1.9 km: ~1900 steps, only first hundreds loaded).
     */
    static double reach(Vec3 from, Vec3 to, Loaded loaded) {
        double dx = to.x - from.x, dz = to.z - from.z;
        int cx = Mth.floor(from.x / 16), cz = Mth.floor(from.z / 16);
        int sx = dx > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        // path fraction to next column boundary in x and z, and fraction step per column
        double stepX = dx != 0 ? 16 / Math.abs(dx) : Double.POSITIVE_INFINITY, stepZ = dz != 0 ? 16 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double nextX = dx != 0 ? ((sx > 0 ? (cx + 1) * 16 - from.x : from.x - cx * 16) / Math.abs(dx)) : Double.POSITIVE_INFINITY;
        double nextZ = dz != 0 ? ((sz > 0 ? (cz + 1) * 16 - from.z : from.z - cz * 16) / Math.abs(dz)) : Double.POSITIVE_INFINITY;
        double last = 0;
        while (true) {
            double exit = Math.min(1, Math.min(nextX, nextZ));
            if (loaded.at(cx, cz)) last = exit;
            if (exit >= 1) return last;
            if (nextX < nextZ) {
                cx += sx;
                nextX += stepX;
            } else {
                cz += sz;
                nextZ += stepZ;
            }
        }
    }

    /**
     * {@link #openToward} rays: length (shorter than nearest echo reflections, {@code TerrainEcho.NEAR}: responding
     * slope is not a barrier) and inclinations (along, above, steeply upward), rad.
     */
    private static final double TOWARD_REACH = 12;
    private static final double[] TOWARD_PITCH = {0, 0.35, 0.9};

    /**
     * Whether ear is open toward {@code azimuth} for a distant source there (slope echoes): fraction of three
     * {@link #TOWARD_REACH}-block rays — along, above and steeply upward — not hitting solid (house: walls and roof;
     * window: open). For our own sources ({@link OwnSources}): Sound Physics leaves them alone, so also needed
     * with it; disabled in settings — 1.
     */
    static float openToward(Vec3 ear, double azimuth) {
        return IncomingConfig.CLIENT.soundMuffling.get() ? openRays(ear, azimuth) : 1;
    }

    /**
     * Fraction of {@link #openToward} rays toward {@code azimuth} not hitting solid, independent of sound setting
     * (wall wind shadow for nuclear wave). No world or player — 1.
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

    /** Ray through solid blocks, passing foliage. */
    private static final class ThroughLeaves extends ClipContext {
        ThroughLeaves(Vec3 from, Vec3 to, Player player) {
            super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            return state.is(BlockTags.LEAVES) ? Shapes.empty() : super.getBlockShape(state, level, pos);
        }
    }

    /** Gain and highs behind barrier: blocked path 4 dB quieter and muffled (diffraction around it). */
    static float blockedGain(float open) {
        return 1 - 0.37f * (1 - open);
    }

    static float blockedHighs(float open) {
        return 1 - 0.8f * (1 - open);
    }

    // ---------------------------------------------------------------- sound thread

    public static void onEngineLoad(SoundEngineLoadEvent e) {
        filter = 0;
        OwnSources.engineLoaded();
        BlastSounds.engineLoaded();
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
        // Sound Physics Remastered just set this sound's filter (at start of Channel.play, event immediately
        // afterward in the same sound-thread task)
        float[] spr = !own && (ours || sound.isLooping()) ? Spr.now() : null;
        // manage others' loops every tick during deafness (loops): their Sound Physics filter is needed later too
        if (spr != null && sound.isLooping()) SPR_AT_START.put(sound, spr);
        float[] f = direct(own, gain, highs, soundPhysics(), spr);
        if (f == null) return;
        set(source, f[0], f[1]);
        if (own) ownPath(sound, source, f);
    }

    /**
     * Our sound with Sound Physics: reset its OpenAL distance air absorption ({@code AL_AIR_ABSORPTION_FACTOR},
     * set on every sound, even a 0 entry in {@code sound_rates}) to 0 as Minecraft engine does: our filter already
     * includes path air, engine sits where it is heard, so distant highs would otherwise be cut twice.
     */
    private static void ownPath(SoundInstance sound, int source, float[] f) {
        if (filter <= 0 || !soundPhysics()) return;
        AL10.alSourcef(source, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 0);
        OWN.put(sound, f);
    }

    /**
     * Direct filter at sound start: gain and high fraction, or null — do not set. Our sound ({@code own}) uses
     * our filter instead of Sound Physics: path is ours, its filter saw only 3 blocks to ear, and ear barrier
     * would count twice; with Sound Physics ({@code soundPhysics}) set ours even without muffling to remove its
     * filter (including when it cannot be read). Others use ours over theirs ({@code spr}, null — absent or
     * unreadable): deafness and pall are ours, path is theirs.
     */
    static float @Nullable [] direct(boolean own, float gain, float highs, boolean soundPhysics, float @Nullable [] spr) {
        boolean ours = gain < 0.999f || highs < 0.999f;
        if (own) return ours || soundPhysics ? new float[]{gain, highs} : null;
        if (!ours) return null;
        return spr != null ? new float[]{gain * spr[0], highs * spr[1]} : new float[]{gain, highs};
    }

    /** Each tick for playing sound: new filter parameters (engine flies away, hides behind hill). */
    static void update(SoundInstance sound, float gain, float highs) {
        ChannelAccess.ChannelHandle handle = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.get(sound);
        if (handle == null) return;
        float m = NukePall.muffle(sound);
        float g = gain * Deafness.gain(sound) * (1 - 0.3f * m), h = highs * Math.max(0.01f, Deafness.highs(sound) * (1 - 0.75f * m));
        // sound is ours ({@link Muffled}): path ours, Sound Physics start filter omitted ({@link #direct});
        // {@link #loops} overwrites its new evaluations after this tick
        handle.execute(channel -> {
            set(channel.source, g, h);
            ownPath(sound, channel.source, new float[]{g, h});
        });
    }

    /**
     * Client tick (after sound tick queues Sound Physics re-evaluations on sound thread): restore our filters
     * on our sounds while it is present ({@link #OWN}), and deafness/pall filters on others' loops. Source filter
     * lasts until sound ends, loop lasts until removed: one started before wave would sound clear through hours
     * of deafness; one started in deafness would stay muffled afterward. Set while not 1, and once when 1 again
     * (restore Sound Physics start filter); while present, overwrites Sound Physics loop re-evaluations
     * ({@code updateMovingSounds}), as for our loops.
     */
    public static void loops() {
        SoundEngine engine = Minecraft.getInstance().getSoundManager().soundEngine;
        boolean all = restoreAll;
        restoreAll = false;
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> e : engine.instanceToChannel.entrySet()) {
            SoundInstance sound = e.getKey();
            if (sound instanceof Muffled) {
                if (soundPhysics()) e.getValue().execute(channel -> {
                    float[] f = OWN.get(sound);
                    if (f != null) {
                        set(channel.source, f[0], f[1]);
                        ownPath(sound, channel.source, f);
                    }
                });
                continue;
            }
            if (!sound.isLooping()) continue;
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
                // own filter set only if EFX exists (without it AL_DIRECT_FILTER errors, which engine would treat as its own)
                else if (filter > 0) AL10.alSourcei(channel.source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            });
        }
    }

    /** Hearing returned at once: next {@link #loops} restores filters to all others' loops. Client thread. */
    public static void restoreLoops() {
        restoreAll = true;
    }

    /**
     * Sound Physics Remastered direct filter ({@code SoundPhysics.directFilter0}, one for all sounds — sets values
     * then copies to source before each). Other mod internals via reflection: not found — log once, and our filter
     * again replaces its filter.
     */
    private static final class Spr {
        @Nullable
        private static Field direct;
        @Nullable
        private static Object enabled;
        @Nullable
        private static Method get;
        private static boolean tried, logged;

        /** Gain and high fraction it just set on sound; null — absent, disabled or unreadable. */
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
