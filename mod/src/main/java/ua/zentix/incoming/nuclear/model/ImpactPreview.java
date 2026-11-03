package ua.zentix.incoming.nuclear.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/** Flat-ground reference only: the same pressure, fireball and exposed-skin laws as the event. No world access. */
public record ImpactPreview(double yieldKt, boolean air, double height, double visibility, double scale,
                            List<Contour> contours) {
    public record Contour(String key, double radius, int color, int stride) {}

    public static ImpactPreview of(double kt, boolean air, double visibility, double scale) {
        double h = air ? Yield.optimalBurstHeight(kt) : 0;
        return new ImpactPreview(kt, air, h * scale, visibility, scale, List.of(
                new Contour(air ? "ball_air" : "ball_ground", FireballModel.maxRadius(kt, !air) * scale, 0xFFFFD060, 2),
                new Contour("psi5", HobBlast.rangeFor(5, h, kt) * scale, 0xFFFF8050, 1),
                new Contour("psi1", HobBlast.rangeFor(1, h, kt) * scale, 0xFFFFD0A0, 3),
                new Contour("glass", HobBlast.rangeFor(0.5, h, kt) * scale, 0xFF80D8FF, 4),
                new Contour("burn", ground(ThermalModel.rangeForFluence(BurnInjury.median(1, kt), kt, !air, visibility), h) * scale,
                        0xFFFF80B0, 5)));
    }

    private static double ground(double slant, double height) {
        return Math.sqrt(Math.max(0, slant * slant - height * height));
    }

    public double extent(boolean extra) {
        return contours.subList(0, extra ? contours.size() : 3).stream().mapToDouble(Contour::radius).max().orElse(1);
    }

    /** Integer kilotons; invalid and out-of-cap input is rejected, never silently shrunk. */
    public static OptionalInt parseYield(String text, int cap) {
        if (text.isEmpty() || text.length() > 7 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) return OptionalInt.empty();
        try {
            int kt = Integer.parseInt(text);
            return kt >= 1 && kt <= cap ? OptionalInt.of(kt) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    public static int[] suggestions(int cap) {
        return Arrays.stream(new int[] {1, 5, 15, 50, 100, 300, 1000}).filter(n -> n <= cap).toArray();
    }

    public static String yieldText(int kt) {
        return kt == 1000 ? "1 Mt (1000 kt)" : kt + " kt";
    }

    public static String distance(double blocks) {
        return blocks >= 1000 ? String.format(Locale.ROOT, "%.2f km", blocks / 1000) : String.format(Locale.ROOT, "%.0f m", blocks);
    }

    public static double fitScale(double radius, int width, int height) {
        return Math.min(8, Math.max(1.0 / 1024, Math.min(width, height) * 0.42 / Math.max(1, radius)));
    }
}
