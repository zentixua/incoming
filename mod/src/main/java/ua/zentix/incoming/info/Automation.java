package ua.zentix.incoming.info;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.WeaponType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a computer attached to a fixed launcher or a SAM reads and does (CC:Tweaked, {@code compat/computercraft}), as
 * plain tables (string keys; strings, numbers, booleans, nested tables). Called on the server thread. The launcher's
 * {@link #fire} is the redstone signal's own entry; nothing here changes a mission. A computer has no viewer: anyone can
 * attach one, so it reads what {@link Readout} gives a viewer without rights (the rack's weapon, stock, readiness, a
 * refusal only when it tells nothing about the owner or the mission); owner and mission never reach it.
 */
public final class Automation {
    private Automation() {}

    /** Reason code of a refusal that names the owner or the mission: the computer learns only that it cannot fire. */
    public static final String PRIVATE = "private";

    /**
     * {@code weapon} (the mission's, shown on the rack; absent without a mission), {@code ready}, {@code busy}, and for a
     * launcher a signal would not fire, {@code reason} ({@link #code}, or {@link #PRIVATE}) and, for a public reason,
     * {@code message} (its English text).
     */
    public static Map<String, Object> status(ServerLevel level, FixedLauncherBlockEntity be) {
        Map<String, Object> t = new LinkedHashMap<>();
        Mission m = be.mission();
        if (m != null) t.put("weapon", m.weapon().getSerializedName());
        Component problem = LauncherOrders.problem(level, be);
        t.put("ready", problem == null);
        t.put("busy", LauncherOrders.busy(level, be));
        if (problem != null) {
            t.put("reason", reason(problem));
            if (Readout.publicRefusal(problem)) t.put("message", problem.getString());
        }
        return t;
    }

    /** {@link #code} of a public refusal, otherwise {@link #PRIVATE}. */
    static String reason(Component refusal) {
        return Readout.publicRefusal(refusal) ? code(refusal) : PRIVATE;
    }

    /** Rounds held per weapon the launcher accepts, by command name (a Grad pack counts its rockets). */
    public static Map<String, Object> stock(FixedLauncherBlockEntity be) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (WeaponType w : WeaponType.values()) {
            if (!Mission.accepts(w)) continue;
            t.put(w.getSerializedName(), Munitions.held(be.store().items(), w.spec().munition().item().get(), w.spec().munition().rounds()));
        }
        return t;
    }

    /**
     * Fire the launcher's existing mission, exactly as a redstone signal's tick does ({@link FixedLauncherBlockEntity#fire}):
     * the owner's rights and limit, map range, busy rack, route, the magazine paid per round as it takes the rail, the
     * launch's work budget, order and round events, the click and the status line on refusal.
     *
     * @return {@code {true}} when ordered, {@code {false, reason, message}} for a public refusal, {@code {false, "private"}}
     *         for one that names the owner or the mission
     */
    public static Object[] fire(ServerLevel level, FixedLauncherBlockEntity be) {
        Component problem = be.fire(level);
        if (problem == null) return new Object[]{true};
        return Readout.publicRefusal(problem) ? new Object[]{false, code(problem), problem.getString()} : new Object[]{false, PRIVATE};
    }

    /** {@code ready} (on rails), {@code rails}, {@code stored}. */
    public static Map<String, Object> samStock(SamBlockEntity be) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ready", be.ready());
        t.put("rails", SamBlockEntity.rails());
        t.put("stored", be.stockCount());
        return t;
    }

    /** The latest sweep: {@code tracking} (a hostile track: the alarm), {@code hostile}, {@code tracks}, {@code covered}. */
    public static Map<String, Object> tracking(SamBlockEntity be) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tracking", be.hostile() > 0);
        t.put("hostile", be.hostile());
        t.put("tracks", be.tracks());
        t.put("covered", be.covered());
        return t;
    }

    /**
     * Stable reason code for scripts: the refusal's language key without the {@code incoming.} prefix
     * ({@code fixed_launcher.busy}, {@code munitions.short}); {@code failed} for a line that is not a translated one.
     */
    public static String code(Component refusal) {
        String key = Readout.key(refusal);
        if (key == null) return "failed";
        return key.startsWith("incoming.") ? key.substring("incoming.".length()) : key;
    }

    /** {@code incoming_alert} arguments after the attachment name: hostile count, nearest hostile distance in whole blocks. */
    public static Object[] alert(int hostile, double nearest) {
        return new Object[]{hostile, Double.isFinite(nearest) ? (int) Math.round(nearest) : -1};
    }

    /** Name of the computer event a SAM's attached computers receive on each sweep that tracks a hostile target. */
    public static final String ALERT_EVENT = "incoming_alert";

    /** Peripheral types. */
    public static final String LAUNCHER_TYPE = "incoming_launcher", SAM_TYPE = "incoming_sam";
}
