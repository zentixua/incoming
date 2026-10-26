package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Human-readable dose rate: model R/h → µSv/h … Sv/h (1 R ≈ 10 mSv), three significant digits. */
public final class GeigerFormat {
    /** Natural background, µSv/h: counter never falls entirely silent. */
    public static final double BACKGROUND_USV = 0.15;

    private GeigerFormat() {}

    /** Dose rate, µSv/h, including background. */
    public static double microSievertsPerHour(double roentgenPerHour) {
        return roentgenPerHour * 10_000 + BACKGROUND_USV;
    }

    public static Component rate(double roentgenPerHour) {
        double usv = microSievertsPerHour(roentgenPerHour);
        if (usv < 1000) return Component.translatable("incoming.unit.usv_h", number(usv));
        if (usv < 1_000_000) return Component.translatable("incoming.unit.msv_h", number(usv / 1000));
        return Component.translatable("incoming.unit.sv_h", number(usv / 1_000_000));
    }

    private static String number(double v) {
        if (v >= 100) return String.format(Locale.ROOT, "%.0f", v);
        if (v >= 10) return String.format(Locale.ROOT, "%.1f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
