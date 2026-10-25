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
 * Огибающие ({@link Envelope}) записей взрывов по событию звука — наибольшая по вариантам (какой вариант сыграет, решает
 * зерно взрыва; наибольшая — чтобы смесь не вышла выше потолка ни с одним), и энергия каждого варианта
 * ({@link Envelope#energy}: столько звучит именно эта запись — по ней гул, {@link Rumble}). Читаются из самих файлов при
 * каждой перезагрузке ресурсов (в фоне): ресурс-пак со своими записями взрывов получает свои огибающие.
 */
public final class Envelopes extends SimplePreparableReloadListener<Envelopes.Prepared> {
    public static final Envelopes INSTANCE = new Envelopes();
    /** События, которые сводит {@link Mix}: ракурсы обычного взрыва, звуки бомбы, осыпь выброса воронки. */
    static final List<String> EVENTS = List.of("blast.near", "blast.mid", "blast.far", "blast.sub", "bomb.impact", "bomb.deep", "bomb.vent",
            "bomb.cave", "bomb.quake", "debris.fall");
    /** Огибающей нет (запись не прочиталась): громкость потолка весь срок {@link #FALLBACK_TICKS} — длиннее любой записи взрыва. */
    static final int FALLBACK_TICKS = 600;

    /** Огибающие событий и энергия вариантов (по имени записи, как у {@link Sound#getLocation}). */
    record Prepared(Map<String, float[]> events, Map<ResourceLocation, float[]> energy) {}

    private volatile Prepared prepared = new Prepared(Map.of(), Map.of());

    private Envelopes() {}

    /** Огибающая события {@code event} (путь без пространства имён: «blast.near»). */
    static float[] of(ResourceLocation event) {
        float[] e = INSTANCE.prepared.events().get(event.getPath());
        if (e != null) return e;
        float[] flat = new float[FALLBACK_TICKS];
        java.util.Arrays.fill(flat, (float) Mix.CEILING);
        return flat;
    }

    /**
     * Энергия того варианта события {@code event}, который выберет зерно {@code seed} (ваниль берёт вариант первым
     * {@code nextInt} генератора звука — как {@link ClientSounds#blastShot} и эхо, {@link BlastEchoes}); варианта нет —
     * огибающая события (верхняя оценка).
     */
    static float[] energy(SoundEvent event, long seed) {
        WeighedSoundEvents w = Minecraft.getInstance().getSoundManager().getSoundEvent(event.getLocation());
        Sound s = w == null ? null : w.getSound(RandomSource.create(seed));
        return energy(s == null ? null : s.getLocation(), event.getLocation());
    }

    /** Энергия записи {@code variant} (имя, как у {@link Sound#getLocation}); её нет — огибающая события {@code event}. */
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

    /** Огибающие и энергии — эти (перезагрузка ресурсов; проверки). */
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

    /** Огибающая ({@link Envelope#of}) и энергия ({@link Envelope#energy}) записи; null — не прочиталась. */
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

    /** Наибольшая из двух по тикам (длина — большая). */
    static float[] max(float[] a, float[] b) {
        float[] out = new float[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(i < a.length ? a[i] : 0, i < b.length ? b[i] : 0);
        return out;
    }
}
