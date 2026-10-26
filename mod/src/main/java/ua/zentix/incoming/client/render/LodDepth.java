package ua.zentix.incoming.client.render;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * This frame's terrain depth from a distant rendering mod (LOD), for rendering after the world (effects layer:
 * distant projectiles, flashes and smoke, {@code fx/layer/SceneDepth}): Minecraft hardware depth testing cannot see
 * LODs (Distant Horizons renders into its own depth texture, Voxy into its own, pinning LODs in Minecraft depth at
 * its far plane). Source is the mod that rendered this frame: {@link DhDepth} (public DH API) or {@link VoxyDepth}
 * (Voxy via Roxy: no API, read fields by reflection only). Both may be installed: take first that rendered.
 * Without a LOD mod or with an unknown version, no source; only Minecraft terrain is visible after the world.
 */
public final class LodDepth {
    private static final List<Source> SOURCES = new ArrayList<>();

    private LodDepth() {}

    /** Frame LOD depth: texture, inverse projection, value decoding. */
    public static final class View {
        /** OpenGL depth texture. */
        public int texture;
        /** Depth → view coordinates (inverse of the projection that wrote depth). */
        public final Matrix4f inverseProjection = new Matrix4f();
        /** Depth in [0, 1] maps NDC −1 to 1 (ordinary OpenGL), otherwise NDC 0 to 1. */
        public boolean negativeOneToOne;
        /** Depth of pixel without LOD (cleared). */
        public float empty;
        /**
         * Depth used to mark pixels where Minecraft drew and no LOD exists (Voxy: near plane, preventing LOD over
         * the world); −1 if no such marker. Pixel distance comes from Minecraft depth, rather than LOD at the near
         * plane 8–16 blocks away, which would hide everything farther away.
         */
        public float world = -1;
        /**
         * Mod also writes LODs to Minecraft depth, pinning distant ones to its far plane (Voxy without shader pack):
         * a world pixel at the far plane is a LOD, rather than a wall hundreds of blocks away; distance from LOD depth.
         */
        public boolean pinsWorldDepth;
        /** Depth source and decoding, for the scenario line. */
        String source = "", how = "";

        public String describe() {
            return source + ": " + how;
        }
    }

    /** LOD mod able to provide frame depth. */
    interface Source {
        /** This frame's depth into out; false if the mod did not render this frame. */
        boolean current(View out);

        /** End of frame: mod must render again next frame, otherwise its depth is from another frame. */
        void endFrame();
    }

    /** At client startup: installed LOD mods providing depth. */
    public static void init() {
        Source dh = DhDepth.init();
        if (dh != null) SOURCES.add(dh);
        Source voxy = VoxyDepth.init();
        if (voxy != null) SOURCES.add(voxy);
    }

    /** This frame's LOD depth into out, from the first source that rendered; false if none. */
    public static boolean current(View out) {
        for (Source s : SOURCES) {
            out.pinsWorldDepth = false;
            out.world = -1;
            if (s.current(out)) return true;
        }
        return false;
    }

    public static void endFrame() {
        for (Source s : SOURCES) s.endFrame();
    }

    /** Source depth decoding description. */
    static String describe(boolean forward, boolean negativeOneToOne, float empty, float nearest, String more) {
        return String.format(Locale.ROOT, "%s Z, NDC %s, empty %.1f, nearest LOD %.1f blocks%s",
                forward ? "forward" : "reverse", negativeOneToOne ? "-1..1" : "0..1", empty, nearest, more);
    }
}
