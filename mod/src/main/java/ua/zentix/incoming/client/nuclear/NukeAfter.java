package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.fx.FireWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.fx.BlastEffects;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.sound.AmbientLoop;
import ua.zentix.incoming.client.sound.FireRoar;
import ua.zentix.incoming.client.sound.Sources;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Burning city (DESIGN-nuke §5): fire roar from all burning locations, wind drawn into fire, crackling and late
 * collapses in nearby ruins, secondary explosions in burning districts. All follow detonation age, not one-time events:
 * a detonation encountered on world entry or after a replay seek sounds at its age; secondary explosions follow
 * a schedule from the detonation seed ({@link FireField#secondaries}), without repeating past ones.
 * <p>
 * Roar uses {@link #QUADRANTS} loops at cardinal directions around the ear, from the source law ({@link FireRoar}): each source's heat
 * comes from the distant model storm ({@link FireStorm}: burning cell fraction × area × {@link FirePlume#BLOCK_WATTS}, as
 * drawn by the pall) and real {@link FireWatch} fire in local chunks (a fire block is the same face); sound receives the fraction
 * {@link Sources#FLAME_EFFICIENCY}, travels to the ear by ISO 9613, and uses {@code Loudness} for volume. The storm uses model trust
 * ({@link FireStorm#gate}: the server burns and local-chunk fire has not been extinguished); local chunks use real fire only.
 * Wind into fire and secondary explosions use the {@link FireField} model (server fire count), scaled by actual burning in
 * local chunks relative to the model there ({@link #trust}), and excluding fire already burning in local chunks (one fire counter per
 * detonation).
 */
final class NukeAfter {
    static final int QUADRANTS = FireRoar.QUADRANTS;
    /**
     * Storm-cell fire source height above ground, m: estimate — flame from burning houses above low-rise roofs (the
     * Hamburg storm reached tens of meters); source height only changes ground effect and daytime shadow along the path.
     */
    static final double STORM_FLAME_M = 5;
    /**
     * Pitch of directional roar loops (different: four identical copies of one recording would create comb filtering) and wind into
     * fire: recordings play at their own speed, shifted no more than ±25 % ({@code SHIFT_MAX} in sound building, {@code NukeTonesTest}).
     */
    static final float[] ROAR_TONES = {0.9f, 0.97f, 1.04f, 1.11f};
    static final float WIND_TONE = 1;
    /** Wind into fire: sink strength ∑ s / d from fires within {@link #WIND_RANGE}; full at 5000 fires 100 blocks away. */
    private static final double WIND_RANGE = 1500, WIND_REF = 5000 / 100.0, WIND_VOLUME = 0.3;
    /** Model points per ring: this many blocks apart around the circumference. */
    private static final double ARC = 64;
    /** Recalculate strength once per this many ticks (loop volume transitions smoothly between them). */
    private static final int EVERY = 5;
    /** Collapses: nearby ruin locations (maximum distance), number retained, initial rate and decay. */
    private static final double COLLAPSE_TAU = 2 * 60 * 20;
    private static final int RUINS_KEPT = 512;
    private static final double COLLAPSE_RATE = 1 / 50.0;
    /** While local chunks contain little model coverage (fewer expected fires than this), trust the model as is. */
    private static final double TRUST_MIN = 40;

    private final ClientNuclear.Active a;
    private final Detonation d;
    private final RandomSource random;
    @Nullable
    private FireField field;
    private List<FireField.Secondary> secondaries = List.of();
    private int nextSecondary = -1;
    private final AmbientLoop[] roar = new AmbientLoop[QUADRANTS];
    @Nullable
    private AmbientLoop wind;
    /** Ticks before retrying a loop start (directional roar, wind last). */
    private final int[] retry = new int[QUADRANTS + 1];
    private final float[] roarVolume = new float[QUADRANTS];
    private final double[] roarDistance = new double[QUADRANTS];
    private final FireRoar fire = new FireRoar();
    /** Current heat burning from this detonation, W: distant storm plus fire in local chunks (for the scenario line). */
    private double stormWatts, seenWatts;
    private float windVolume;
    private Vec3 windToward = Vec3.ZERO;
    private double windDistance;
    /** Model trust: actual local-chunk fire relative to the model there (1 — matches model; 0 — no fires). */
    private double trust = 1;
    private double modelLoaded, seenLoaded, modelRest = 1;
    /** Ruin locations near the detonation (from debris sent by the server), sampled to at most {@link #RUINS_KEPT}. */
    private final List<BlockPos> ruins = new ArrayList<>();
    private int ruinsSeen;
    private double firstRuin = Double.NaN;
    private int fired;

    NukeAfter(ClientNuclear.Active a) {
        this.a = a;
        this.d = a.d;
        this.random = RandomSource.create(d.seed() ^ 0xAF7E5L);
    }

    /** Fire model on first need (detonation radii are calculated from tables). */
    private FireField field() {
        if (field == null) {
            field = new FireField(d);
            secondaries = field.secondaries();
        }
        return field;
    }

    /** Whether anything from this detonation is still burning (burning-city sound). */
    boolean burning() {
        double t = a.ticks(0);
        return t > 0 && t < field().endTicks() && field().total > 0;
    }

    void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        FireField f = field();
        double t = a.ticks(0);
        if (nextSecondary < 0) {
            // detonation encountered after its start: do not repeat past explosions
            nextSecondary = 0;
            while (nextSecondary < secondaries.size() && secondaries.get(nextSecondary).ticks() <= t) nextSecondary++;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        // server fire model ended — the storm drives the roar while it burns (until pall morning)
        boolean field = f.total > 0 && t < f.endTicks();
        if (!field && !a.storm.burningAt(t)) {
            end();
            return;
        }
        if ((long) t % EVERY == 0) measure(level, f, t, ear, field);
        sound(ear);
        if (field) secondaries(level, t);
        collapses(t);
    }

    /**
     * Directional fire roar at the ear ({@link #roar}), wind into fire, and model trust. Wind is drawn toward {@link FireField} model
     * fires and local-chunk fire: the distant model contains only what is not visible there (the server also starts fires where
     * players are — their chunks first — with one counter per detonation, so the distant model excludes the share burning in local
     * chunks). {@code field} means the server fire model is still burning.
     */
    private void measure(ClientLevel level, FireField f, double t, Vec3 ear, boolean field) {
        double[] wind = new double[4];
        double modelHere = 0;
        Vec3 b = d.burst();
        double reach = d.ruinRadius() + 64;
        double[] seen = {0};
        fire.clear();
        FireWatch.forEach((x, y, z, count) -> {
            if (Math.hypot(x - b.x, z - b.z) > reach) return;
            seen[0] += count;
            fire.add(x - ear.x, y - ear.y, z - ear.z, count * FirePlume.BLOCK_WATTS);
            sink(wind, ear, x, z, count);
        });
        seenWatts = seen[0] * FirePlume.BLOCK_WATTS;
        stormWatts = storm(level, t, ear);
        roar();
        if (!field) {
            windVolume = 0;
            return;
        }
        double all = f.burning(t);
        double rest = all <= 0 ? 0 : Math.max(0, 1 - seen[0] / all);
        // distant model only if the server burns (Detonation.fires), excluding the share burning in local chunks
        double model = d.fires() ? trust * rest : 0;
        for (int i = 0; i < f.fires.length; i++) {
            double burning = f.burning(i, t);
            if (burning <= 0) continue;
            double r = FireField.radius(i);
            int n = (int) Math.max(8, Math.min(96, Math.ceil(2 * Math.PI * r / ARC)));
            double each = burning / n, offset = i * 0.618;
            for (int j = 0; j < n; j++) {
                double ang = 2 * Math.PI * (j + 0.5) / n + offset;
                double x = b.x + Math.cos(ang) * r, z = b.z + Math.sin(ang) * r;
                // local chunks show actual fire: the model there is for comparison only
                if (level.getChunkSource().getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, false) != null) {
                    modelHere += each;
                    continue;
                }
                sink(wind, ear, x, z, each * model);
            }
        }
        modelLoaded = modelHere;
        modelRest = rest;
        seenLoaded = seen[0];
        // comparison: model versus local-chunk fire, smoothly (fire starts with the flash, the wave blows some out, it does not fade immediately)
        if (modelHere >= TRUST_MIN) {
            trust += 0.2 * (Math.min(4, seen[0] / modelHere) - trust);
            // for the storm, only while fire is young: vanilla fire later burns out its own way, while the storm burns until morning
            if (t < f.lastIgnition() + FireField.RISE + 0.7 * FireField.TAU) a.storm.seen(trust);
        }
        double sink = wind[3];
        windVolume = sink <= 0 ? 0 : (float) (WIND_VOLUME * Math.min(1, Math.sqrt(sink / WIND_REF)));
        if (windVolume < 0.03f) windVolume = 0;
        windToward = sink <= 0 ? Vec3.ZERO : new Vec3(wind[0] / sink, ear.y, wind[1] / sink);
        windDistance = sink <= 0 ? 0 : wind[2] / sink;
    }

    /**
     * Distant storm into the roar ({@link #fire}): cells currently burning on the storm map ({@link FireStorm#map}: fraction of cell area
     * burning — the same flame drawn by the pall), except local chunks (real fire there), with model trust
     * ({@link FireStorm#gate}). Cell heat is burning area × {@link FirePlume#BLOCK_WATTS} (burning wood face, W/m²).
     *
     * @return distant storm heat, W
     */
    private double storm(ClientLevel level, double t, Vec3 ear) {
        FireStorm s = a.storm;
        s.refresh(t);
        double gate = s.gate();
        float[] map = s.map();
        int side = s.side();
        if (gate < 1e-3 || side == 0 || map.length < side * side * FireStorm.CHANNELS) return 0;
        double cell = s.cell(), half = s.half(), area = cell * cell, y = d.groundY() + STORM_FLAME_M, sum = 0;
        Vec3 b = d.burst();
        for (int c = 0; c < side * side; c++) {
            double flame = map[c * FireStorm.CHANNELS];
            if (flame <= 0) continue;
            double x = b.x + (c % side + 0.5) * cell - half, z = b.z + (c / side + 0.5) * cell - half;
            if (level.getChunkSource().getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, false) != null) continue;
            double watts = gate * flame * area * FirePlume.BLOCK_WATTS;
            fire.add(x - ear.x, y - ear.y, z - ear.z, watts);
            sum += watts;
        }
        return sum;
    }

    /** Directional roar-loop volume follows ear level ({@link FireRoar}); recording — {@link Sources#NUKE_FIRE}. */
    private void roar() {
        Sources.Source src = Sources.NUKE_FIRE;
        ClientLevel level = Minecraft.getInstance().level;
        fire.finish(src.spectrum(), level == null ? Propagation.Weather.NIGHT : Propagation.Weather.of(level));
        for (int q = 0; q < QUADRANTS; q++) {
            roarVolume[q] = fire.volume(q, src.fileLufs(), src.ceilingLufs());
            roarDistance[q] = fire.distance(q);
        }
    }

    /** Fire {@code s} at a point: the wind sink points toward it. */
    private static void sink(double[] wind, Vec3 ear, double x, double z, double s) {
        if (s <= 0) return;
        double dx = x - ear.x, dz = z - ear.z, flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < WIND_RANGE) {
            double w = s / Math.max(30, flat);
            wind[0] += w * x;
            wind[1] += w * z;
            wind[2] += w * flat;
            wind[3] += w;
        }
    }

    /**
     * Roar and wind loops: maintained each tick. Restart any loop the engine is not retaining (no channel, removed on sound restart
     * or device change, "Ambient" slider was zero), independently; do not start while the slider is
     * zero (like sirens, {@link NukeSounds}).
     */
    private void sound(Vec3 ear) {
        boolean ambient = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.AMBIENT) > 0;
        for (int q = 0; q < QUADRANTS; q++) {
            double az = 2 * Math.PI * q / QUADRANTS;
            Vec3 toward = ear.add(Math.cos(az) * 10, 0, Math.sin(az) * 10);
            roar[q] = loop(q, roar[q], ambient, ear, toward, roarVolume[q], roarDistance[q], ModSounds.NUKE_FIRE.get(), ROAR_TONES[q], d.seed() + q);
        }
        wind = loop(QUADRANTS, wind, ambient, ear, windToward, windVolume, windDistance, ModSounds.NUKE_FIRE_WIND.get(), WIND_TONE, d.seed() ^ 0x3D);
    }

    /** One loop: maintain if playing; otherwise start if there is something to play and it is allowed. */
    @Nullable
    private AmbientLoop loop(int i, @Nullable AmbientLoop l, boolean ambient, Vec3 ear, Vec3 toward, float volume, double distance,
                             SoundEvent event, float pitch, long seed) {
        if (retry[i] > 0) retry[i]--;
        if (l != null && l.playing()) {
            l.set(ear, toward, volume, distance);
            return l;
        }
        if (l != null) l.end();
        if (volume <= 0 || !ambient || retry[i] > 0) return null;
        AmbientLoop fresh = new AmbientLoop(event, SoundSource.AMBIENT, pitch, seed);
        fresh.set(ear, toward, volume, distance);
        Minecraft.getInstance().getSoundManager().play(fresh);
        retry[i] = 20;
        return fresh;
    }

    /** Secondary explosions whose time has come: our blast — visuals and sound with a front, without destruction. */
    private void secondaries(ClientLevel level, double t) {
        while (nextSecondary < secondaries.size() && secondaries.get(nextSecondary).ticks() <= t) {
            FireField.Secondary s = secondaries.get(nextSecondary++);
            // fires extinguished or disabled on the server — nothing left to explode
            if (!d.fires() || trust < 0.2) continue;
            double y = FarTerrain.of(level).at((int) Math.floor(s.x()), (int) Math.floor(s.z()));
            if (Double.isNaN(y) || y <= level.getMinBuildHeight() + 1) y = d.groundY() + 1;
            BlastEffects.blast(new S2C.Blast(s.big() ? S2C.Blast.MISSILE : S2C.Blast.DRONE, new Vec3(s.x(), y + 0.5, s.z()),
                    GroundMaterial.BRICK.ordinal(), (float) y, s.seed(), Optional.empty(), S2C.Blast.NO_WEAPON));
            fired++;
        }
    }

    /** Nearby demolished locations (debris from the server), for collapses. */
    void ruins(List<S2C.NukeDebris.Piece> pieces) {
        if (Double.isNaN(firstRuin)) firstRuin = a.ticks(0);
        for (S2C.NukeDebris.Piece p : pieces) {
            ruinsSeen++;
            if (ruins.size() < RUINS_KEPT) ruins.add(p.pos());
            else {
                int k = random.nextInt(ruinsSeen);
                if (k < RUINS_KEPT) ruins.set(k, p.pos());
            }
        }
    }

    /** Crackling and late collapses in nearby ruins, becoming less frequent. */
    private void collapses(double t) {
        if (ruins.isEmpty() || Double.isNaN(firstRuin) || t < firstRuin + 40) return;
        double rate = COLLAPSE_RATE * Math.exp(-(t - firstRuin) / COLLAPSE_TAU);
        if (random.nextDouble() >= rate) return;
        BlockPos p = ruins.get(random.nextInt(ruins.size()));
        Vec3 at = Vec3.atCenterOf(p);
        boolean small = random.nextFloat() < 0.35f;
        // audible reach follows collapse power and the path to the ear (Sources), not distance
        Sources.play(small ? ModSounds.DEBRIS_FALL.get() : ModSounds.NUKE_COLLAPSE.get(), at, small ? Sources.RUIN_DEBRIS : Sources.NUKE_COLLAPSE,
                0.8f + random.nextFloat() * 0.3f);
    }

    /** Loops fade (detonation removed, fire ended). */
    void end() {
        for (AmbientLoop l : roar) if (l != null) l.end();
        if (wind != null) wind.end();
    }

    String describe() {
        FireField f = field();
        double t = a.ticks(0);
        return String.format(Locale.ROOT, "fire model %.0f burning %.0f trust %.2f rest %.2f (model here %.0f seen %.0f) heat storm %.0f MW seen %.1f MW"
                        + " roar %.1f/%.1f/%.1f/%.1f dBA vol %.3f/%.3f/%.3f/%.3f wind %.2f secondary %d/%d ruins %d",
                f.total, f.burning(t), trust, modelRest, modelLoaded, seenLoaded, stormWatts / 1e6, seenWatts / 1e6,
                fire.level(0), fire.level(1), fire.level(2), fire.level(3), roarVolume[0], roarVolume[1], roarVolume[2], roarVolume[3], windVolume,
                fired, secondaries.size(), ruins.size());
    }
}
