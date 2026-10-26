package ua.zentix.incoming.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blast recording envelopes ({@link Envelope}) by sound event: maximum across variants (blast seed selects the
 * variant; maximum keeps the mix under the ceiling with any variant) and each variant's energy
 * ({@link Envelope#energy}: what this exact recording plays — used by rumble, {@link Rumble}). Read from the files
 * on every resource reload (in background): a resource pack with its own blast recordings gets its own envelopes.
 */
public final class Envelopes extends SimplePreparableReloadListener<Envelopes.Prepared> {
    public static final Envelopes INSTANCE = new Envelopes();
    /** Events mixed by {@link Mix}: conventional blast perspectives, bomb sounds, crater ejecta fall. */
    static final List<String> EVENTS = List.of("blast.near", "blast.mid", "blast.far", "blast.sub", "bomb.impact", "bomb.deep", "bomb.vent",
            "bomb.cave", "bomb.quake", "debris.fall");
    /** No envelope (recording unreadable): ceiling loudness for all {@link #FALLBACK_TICKS}, longer than any blast recording (margin). */
    static final int FALLBACK_TICKS = 600;

    /** Event envelopes and variant energies (by recording name, as in {@link Sound#getLocation}). */
    record Prepared(Map<String, float[]> events, Map<ResourceLocation, float[]> energy) {}

    private volatile Prepared prepared = new Prepared(Map.of(), Map.of());

    private Envelopes() {}

    /** Envelope for {@code event} (path without namespace: “blast.near”). */
    static float[] of(ResourceLocation event) {
        float[] e = INSTANCE.prepared.events().get(event.getPath());
        if (e != null) return e;
        float[] flat = new float[FALLBACK_TICKS];
        java.util.Arrays.fill(flat, (float) Mix.CEILING);
        return flat;
    }

    /**
     * Energy of the {@code event} variant selected by {@code seed} (vanilla selects a variant with the sound
     * generator's first {@code nextInt}, as do {@link ClientSounds#blastShot} and echoes, {@link BlastEchoes});
     * no variant — event envelope (upper bound).
     */
    static float[] energy(SoundEvent event, long seed) {
        WeighedSoundEvents w = Minecraft.getInstance().getSoundManager().getSoundEvent(event.getLocation());
        Sound s = w == null ? null : w.getSound(RandomSource.create(seed));
        return energy(s == null ? null : s.getLocation(), event.getLocation());
    }

    /** Energy of recording {@code variant} (name as in {@link Sound#getLocation}); absent — {@code event} envelope. */
    static float[] energy(@Nullable ResourceLocation variant, ResourceLocation event) {
        float[] e = variant == null ? null : INSTANCE.prepared.energy().get(variant);
        return e != null ? e : of(event);
    }

    @Override
    protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<String, float[]> out = new HashMap<>();
        Map<ResourceLocation, float[]> energy = new HashMap<>();
        JsonObject sounds = sounds(resources);
        if (sounds == null) return new Prepared(out, energy);
        for (String event : EVENTS) {
            JsonObject e = sounds.getAsJsonObject(event);
            if (e == null) continue;
            float[] max = null;
            for (JsonElement s : e.getAsJsonArray("sounds")) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                ResourceLocation sound = ResourceLocation.parse(name);
                float[][] env = read(resources, sound);
                if (env == null) continue;
                max = max == null ? env[0] : max(max, env[0]);
                energy.put(sound, env[1]);
            }
            if (max != null) out.put(event, max);
        }
        return new Prepared(out, energy);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resources, ProfilerFiller profiler) {
        prepared(prepared);
    }

    Prepared prepared() {
        return prepared;
    }

    /** Use these envelopes and energies (resource reload; checks). */
    void prepared(Prepared prepared) {
        this.prepared = new Prepared(Map.copyOf(prepared.events()), Map.copyOf(prepared.energy()));
    }

    private static @Nullable JsonObject sounds(ResourceManager resources) {
        Optional<Resource> r = resources.getResource(Incoming.id("sounds.json"));
        if (r.isEmpty()) return null;
        try (InputStreamReader in = new InputStreamReader(r.get().open(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("Blast sound envelopes: sounds.json unreadable: {}", e.toString());
            return null;
        }
    }

    /** Recording envelope ({@link Envelope#of}) and energy ({@link Envelope#energy}); null — unreadable. */
    private static float @Nullable [][] read(ResourceManager resources, ResourceLocation sound) {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(sound.getNamespace(), "sounds/" + sound.getPath() + ".ogg");
        Optional<Resource> r = resources.getResource(file);
        if (r.isEmpty()) return null;
        try (InputStream in = r.get().open()) {
            float[][] pcm = new float[1][];
            int rate = Envelope.read(in, pcm);
            return new float[][]{Envelope.of(pcm[0], rate), Envelope.energy(pcm[0], rate)};
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("Blast sound envelope of {} unreadable: {}", file, e.toString());
            return null;
        }
    }

    /** Tickwise maximum of two (longer length). */
    static float[] max(float[] a, float[] b) {
        float[] out = new float[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(i < a.length ? a[i] : 0, i < b.length ? b[i] : 0);
        return out;
    }
}
