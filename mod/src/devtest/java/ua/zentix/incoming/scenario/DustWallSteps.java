package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import ua.zentix.incoming.Incoming;

/**
 * The dust wave's diagnostics ({@link ua.zentix.incoming.client.fx.layer.DustWallDiag}) as {@code key=value} steps: the client
 * scenario's {@code dw:} steps and the dev client's {@code dw} line (DevHands) set the same switches.
 */
final class DustWallSteps {
    private DustWallSteps() {}

    /** Applies one {@code key=value} diagnostic; throws IllegalArgumentException on an unknown key. */
    static void apply(String arg) {
        String[] kv = arg.split("=", 2);
        String v = kv.length > 1 ? kv[1].strip() : "";
        switch (kv[0].strip()) {
            case "rung" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.rung = Integer.parseInt(v);
            case "history" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.history = !v.equals("0");
            case "interleave" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.interleave = !v.equals("0");
            case "freeze" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.freeze = v.equals("1");
            case "freezeage" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.freezeAge = v.equals("1");
            case "terrain" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.terrain = !v.equals("0");
            case "material" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.material = !v.equals("0");
            case "openair" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.openAir = !v.equals("0");
            case "clamp" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.clampWidth = Float.parseFloat(v);
            case "probe" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.probe = Integer.parseInt(v);
            case "sweep" -> {
                String[] nk = v.split(":");
                if (nk.length > 1) ua.zentix.incoming.client.fx.layer.DustWallDiag.sweepSamples = Integer.parseInt(nk[1]);
                ua.zentix.incoming.client.fx.layer.DustWallDiag.sweep =
                        Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(nk[0]).toString();
            }
            case "freezeat" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.freezeAt = Float.parseFloat(v);
            case "div" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.div = Integer.parseInt(v);
            case "reprojection" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.reprojection = !v.equals("0");
            case "ground" -> {
                String base = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(v).toString();
                Incoming.LOG.info("SCENARIO dw {}", ua.zentix.incoming.client.nuclear.DustGround.describe());
                int i = 0;
                for (var a : ua.zentix.incoming.client.nuclear.ClientNuclear.detonations()) {
                    try {
                        a.writeGroundDiag(base + "_" + i++);
                    } catch (java.io.IOException e) {
                        Incoming.LOG.warn("SCENARIO dw ground: not written", e);
                    }
                }
            }
            case "dump" -> ua.zentix.incoming.client.fx.layer.DustWallDiag.dump =
                    Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(v).toString();
            default -> throw new IllegalArgumentException("dw: " + arg);
        }
        Incoming.LOG.info("SCENARIO dw {}", arg);
    }
}
