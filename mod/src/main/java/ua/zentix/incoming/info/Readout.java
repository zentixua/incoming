package ua.zentix.incoming.info;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.Substations;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.target.Sides;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Overlay lines (Jade) built on the server from what the client does not have. Owner and mission go only to a
 * {@linkplain #privileged privileged} viewer: they are not in the data a viewer without rights receives at all. Stock,
 * SAM radar and substation state are for everyone: the rack, the SAM's alarm and the substation's sparks already show
 * them to anyone nearby.
 */
public final class Readout {
    /**
     * Refusals that tell nothing about the owner or the mission (whose numbers, rights and route stay private): shown to
     * every viewer; any other refusal reads only "cannot fire" to a viewer without rights.
     */
    static final Set<String> PUBLIC_REFUSALS = Set.of("incoming.fixed_launcher.no_mission.short", "incoming.fixed_launcher.on_craft",
            "incoming.fixed_launcher.busy", "incoming.fixed_launcher.sector", "incoming.fixed_launcher.failed");

    private Readout() {}

    /** {@code refusal} tells nothing about the owner or the mission: any viewer, and any computer, may read it. */
    public static boolean publicRefusal(Component refusal) {
        return PUBLIC_REFUSALS.contains(key(refusal));
    }

    /**
     * {@code viewer} may see the owner and the mission of something owned by {@code owner}: an operator (permission
     * level 2), or the owner's side by the side rule ({@link Sides#friendly}: the owner, the owner's vanilla team). Null
     * owner (placed or launched by command): operators only.
     */
    public static boolean privileged(MinecraftServer server, @Nullable UUID owner, ServerPlayer viewer) {
        return viewer.hasPermissions(2) || owner != null && Sides.friendly(server, owner, viewer.getUUID());
    }

    /** Fixed launcher: owner and mission (privileged), stock, and whether a signal would fire it now and why not. */
    public static List<Component> launcher(ServerLevel level, FixedLauncherBlockEntity be, ServerPlayer viewer) {
        List<Component> lines = new ArrayList<>();
        boolean rights = privileged(level.getServer(), be.owner(), viewer);
        Mission m = be.mission();
        if (rights) {
            lines.add(owner(level.getServer(), be.owner()));
            if (m != null) {
                lines.add(Component.translatable("incoming.info.mission", m.weapon().displayName(), m.count(), Math.round(m.point().x),
                        Math.round(m.point().z), m.spread()));
            }
        }
        // the mission's weapon is on the rack for everyone to see; its count is not
        lines.add(m == null ? Component.translatable("incoming.info.stock.items", be.stockCount())
                : Component.translatable("incoming.info.stock.rounds", rounds(be, m), m.weapon().displayName()));
        Component problem = LauncherOrders.problem(level, be);
        if (problem == null) lines.add(Component.translatable("incoming.info.ready").withStyle(ChatFormatting.GREEN));
        else if (rights || publicRefusal(problem)) lines.add(Component.translatable("incoming.info.cannot", problem).withStyle(ChatFormatting.RED));
        else lines.add(Component.translatable("incoming.info.cannot.private").withStyle(ChatFormatting.RED));
        return lines;
    }

    /** Rounds of the mission's weapon in stock (a Grad pack counts its rockets). */
    static int rounds(FixedLauncherBlockEntity be, Mission m) {
        return Munitions.held(be.store().items(), m.weapon().spec().munition().item().get(), m.weapon().spec().munition().rounds());
    }

    /** SAM: missiles on rails and in stock, and the latest sweep: under a roof, tracking hostile targets, or clear. */
    public static List<Component> sam(SamBlockEntity be) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("incoming.info.sam.missiles", be.ready(), SamBlockEntity.rails(), be.stockCount()));
        if (be.covered()) lines.add(Component.translatable("incoming.info.sam.covered").withStyle(ChatFormatting.GOLD));
        else if (be.hostile() > 0) lines.add(Component.translatable("incoming.info.sam.tracking", be.hostile(), be.tracks()).withStyle(ChatFormatting.RED));
        else lines.add(Component.translatable("incoming.info.sam.clear", be.tracks()));
        return lines;
    }

    /** Substation: the grid's state of its node; nothing for a block the grid has no node for. */
    public static List<Component> substation(ServerLevel level, BlockPos pos) {
        Substations.Supply s = Substations.supply(level, pos);
        if (s == null) return List.of();
        return List.of(switch (s.state()) {
            case WORKING -> Component.translatable("incoming.info.substation.working").withStyle(ChatFormatting.GREEN);
            case DESTROYED -> Component.translatable("incoming.info.substation.destroyed").withStyle(ChatFormatting.RED);
            case KNOCKED_OUT -> s.repairIn() == Outage.NEVER
                    ? Component.translatable("incoming.info.substation.knocked_out.never").withStyle(ChatFormatting.RED)
                    : Component.translatable("incoming.info.substation.knocked_out", hours(s.repairIn())).withStyle(ChatFormatting.RED);
        });
    }

    /** World-clock hours, one decimal, at least 0.1 (dot in every locale: the number goes into a translated line). */
    static String hours(long ticks) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0.1, Math.ceil(ticks / Outage.TICKS_PER_HOUR * 10) / 10));
    }

    /** Projectile: its owner (privileged only; the client shows the weapon from the entity itself). */
    public static List<Component> projectile(MinecraftServer server, StrikeProjectile p, ServerPlayer viewer) {
        return privileged(server, p.ownerId(), viewer) ? List.of(owner(server, p.ownerId())) : List.of();
    }

    /** "Owner: name": an online player's name or the server's cached one; never a storage lookup. */
    static Component owner(MinecraftServer server, @Nullable UUID owner) {
        if (owner == null) return Component.translatable("incoming.info.owner.none");
        String name = Sides.names(server).apply(owner);
        return Component.translatable("incoming.info.owner", name != null ? Component.literal(name) : Component.translatable("incoming.info.owner.unknown"));
    }

    /** Language key of a refusal; null — not a translated line. */
    @Nullable
    static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }
}
