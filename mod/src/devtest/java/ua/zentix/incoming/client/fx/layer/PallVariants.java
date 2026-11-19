package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import ua.zentix.incoming.Incoming;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Measurement only (wave-proposal, not for merge): rebuild the pall programs that include {@code fx_pall.glsl} with a patched
 * copy of it, in place, so one client renders the same frozen instant with several head-noise variants. Variants are text
 * replacements on #669's {@code wallAt}/{@code wallBillows}; on a build without them only {@code V0} exists.
 */
public final class PallVariants {
    private static final ResourceLocation FX_PALL = Incoming.id("shaders/include/fx_pall.glsl");
    private static final String ROLL = "pallWallRollAngle(ph, V[1].x, size)";
    private static final String OCTAVE = "float k0 = floor(clamp(k, 0.0, 3.0)), b = k >= 3.0 ? 0.0 : smoothstep(0.35, 0.65, max(k, 0.0) - k0), size = tile * exp2(-k0);";
    private static final String TOE = "resolved = 1.0 - smoothstep(3.5, 4.0, k);";
    private static String current = "V0";

    private PallVariants() {}

    /** Variant names this build supports, in shot order. */
    public static String[] names() {
        String src = source();
        if (src == null || !src.contains(ROLL)) return new String[]{"V0"};
        return new String[]{"V0", "NOROLL", "TILE", "NOTOE", "OLD"};
    }

    public static String current() {
        return current;
    }

    private static String source() {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(FX_PALL)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String patch(String src, String v) {
        String noRoll = src.replace(ROLL, "0.0");
        String tile = src.replace(OCTAVE, "float k0 = 0.0, b = 0.0, size = tile;");
        String noToe = src.replace(TOE, "resolved = 1.0;");
        return switch (v) {
            case "NOROLL" -> noRoll;
            case "TILE" -> tile;
            case "NOTOE" -> noToe;
            case "OLD" -> src.replace(ROLL, "0.0").replace(OCTAVE, "float k0 = 0.0, b = 0.0, size = tile;").replace(TOE, "resolved = 1.0;");
            default -> src;
        };
    }

    /** Rebuild the five programs with variant {@code v}; true — done. Logs whether every replacement matched. */
    public static boolean apply(String v) {
        String src = source();
        if (src == null) return false;
        String text = patch(src, v);
        if (!"V0".equals(v) && text.equals(src)) {
            Incoming.LOG.warn("WAVERIG variant {} changed nothing", v);
            return false;
        }
        ResourceProvider base = Minecraft.getInstance().getResourceManager();
        ResourceProvider p = loc -> {
            Optional<Resource> r = base.getResource(loc);
            if (!loc.equals(FX_PALL) || r.isEmpty()) return r;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return Optional.of(new Resource(r.get().source(), () -> new ByteArrayInputStream(bytes)));
        };
        Map<String, String> programs = new LinkedHashMap<>();
        programs.put("march", "fx_pall");
        programs.put("froxel", "fx_pall_froxel");
        programs.put("froxelSum", "fx_pall_froxel_sum");
        programs.put("footLighting", "fx_pall_foot");
        programs.put("mushTaus", "fx_mush_taus");
        long t0 = System.nanoTime();
        try {
            for (var e : programs.entrySet()) {
                ShaderInstance s = new ShaderInstance(p, Incoming.id(e.getValue()), DefaultVertexFormat.POSITION);
                Field f = PallPass.class.getDeclaredField(e.getKey());
                f.setAccessible(true);
                ShaderInstance old = (ShaderInstance) f.get(null);
                f.set(null, s);
                if (old != null) old.close();
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException ex) {
            Incoming.LOG.error("WAVERIG variant {} failed", v, ex);
            return false;
        }
        current = v;
        Incoming.LOG.info("WAVERIG variant {} built in {} ms", v, (System.nanoTime() - t0) / 1_000_000);
        return true;
    }

    /** Current detail rung of the pall (ray resolution/interleave), or -1. */
    public static int rung() {
        try {
            Field f = PallPass.class.getDeclaredField("DETAIL");
            f.setAccessible(true);
            Object d = f.get(null);
            Field l = d.getClass().getDeclaredField("level");
            l.setAccessible(true);
            return l.getInt(d);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }
}
