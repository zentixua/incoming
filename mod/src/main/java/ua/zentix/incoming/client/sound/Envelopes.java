package ua.zentix.incoming.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
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
 * зерно взрыва; наибольшая — чтобы смесь не вышла выше потолка ни с одним). Читаются из самих файлов при каждой
 * перезагрузке ресурсов (в фоне): ресурс-пак со своими записями взрывов получает свои огибающие.
 */
public final class Envelopes extends SimplePreparableReloadListener<Map<String, float[]>> {
    public static final Envelopes INSTANCE = new Envelopes();
    /** События, которые сводит {@link Mix}: ракурсы обычного взрыва и звуки бомбы. */
    static final List<String> EVENTS = List.of("blast.near", "blast.mid", "blast.far", "blast.sub", "bomb.impact", "bomb.deep", "bomb.vent",
            "bomb.cave", "bomb.quake");
    /** Огибающей нет (запись не прочиталась): громкость файла весь срок {@link #FALLBACK_TICKS} — с запасом. */
    static final int FALLBACK_TICKS = 100;

    private volatile Map<String, float[]> envelopes = Map.of();

    private Envelopes() {}

    /** Огибающая события {@code event} (путь без пространства имён: «blast.near»). */
    static float[] of(ResourceLocation event) {
        float[] e = INSTANCE.envelopes.get(event.getPath());
        if (e != null) return e;
        float[] flat = new float[FALLBACK_TICKS];
        java.util.Arrays.fill(flat, (float) Mix.CEILING);
        return flat;
    }

    @Override
    protected Map<String, float[]> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<String, float[]> out = new HashMap<>();
        JsonObject sounds = sounds(resources);
        if (sounds == null) return out;
        for (String event : EVENTS) {
            JsonObject e = sounds.getAsJsonObject(event);
            if (e == null) continue;
            float[] max = null;
            for (JsonElement s : e.getAsJsonArray("sounds")) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                float[] env = read(resources, ResourceLocation.parse(name));
                if (env == null) continue;
                max = max == null ? env : max(max, env);
            }
            if (max != null) out.put(event, max);
        }
        return out;
    }

    @Override
    protected void apply(Map<String, float[]> prepared, ResourceManager resources, ProfilerFiller profiler) {
        envelopes = Map.copyOf(prepared);
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

    private static float @Nullable [] read(ResourceManager resources, ResourceLocation sound) {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(sound.getNamespace(), "sounds/" + sound.getPath() + ".ogg");
        Optional<Resource> r = resources.getResource(file);
        if (r.isEmpty()) return null;
        try (InputStream in = r.get().open()) {
            float[][] pcm = new float[1][];
            int rate = Envelope.read(in, pcm);
            return Envelope.of(pcm[0], rate);
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
