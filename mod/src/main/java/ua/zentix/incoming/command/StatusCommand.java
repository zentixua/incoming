package ua.zentix.incoming.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.Order;
import ua.zentix.incoming.strike.OrderStatus;
import ua.zentix.incoming.strike.ServerActions;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Order results for commands and scripts: {@code status [player|tag <name>]} reports unfinished rounds by phase
 * ({@link OrderStatus}) and returns their number; {@code clear tag <name>} recalls an order group under the owner rules of
 * {@code clear}; strike commands report the admitted order ({@link #admitted}). A tag is one word, at most {@link
 * #TAG_MAX} characters, case-sensitive like vanilla {@code /tag}.
 */
final class StatusCommand {
    static final int TAG_MAX = 32;
    /** Orders listed in chat; the count covers all. */
    static final int LINES = 10;
    private static final DynamicCommandExceptionType TAG_TOO_LONG = new DynamicCommandExceptionType(
            max -> Component.translatable("incoming.tag.too_long", max));

    private StatusCommand() {}

    /** {@code status}: own rounds for a player without operator rights, all for the others. */
    static LiteralArgumentBuilder<CommandSourceStack> status() {
        return Commands.literal("status")
                .executes(ctx -> status(ctx.getSource(), null, null))
                .then(Commands.literal("tag").then(Commands.argument("tag", StringArgumentType.word())
                        .executes(ctx -> status(ctx.getSource(), null, tag(ctx)))))
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).requires(StatusCommand::seesAll)
                        .executes(ctx -> status(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player"), null)));
    }

    /** {@code clear tag <name>}, under {@code clear}. */
    static LiteralArgumentBuilder<CommandSourceStack> clearTag() {
        return Commands.literal("tag").then(Commands.argument("tag", StringArgumentType.word()).executes(ctx -> clearTag(ctx.getSource(), tag(ctx))));
    }

    /** The {@code tag} argument, checked. */
    static String tag(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String tag = StringArgumentType.getString(ctx, "tag");
        if (tag.length() > TAG_MAX) throw TAG_TOO_LONG.create(TAG_MAX);
        return tag;
    }

    /**
     * Operators, the world host, the console and command blocks see and clear every owner's rounds; other players only
     * their own (as {@code clear}).
     */
    static boolean seesAll(CommandSourceStack s) {
        return s.hasPermission(2) || s.getEntity() instanceof ServerPlayer p && NuclearKeys.trusted(p);
    }

    private static int status(CommandSourceStack s, @Nullable Collection<GameProfile> players, @Nullable String tag) {
        Set<UUID> owners = null;
        if (players != null) {
            owners = new HashSet<>();
            for (GameProfile p : players) owners.add(p.getId());
        } else if (!seesAll(s) && s.getEntity() instanceof ServerPlayer p) {
            owners = Set.of(p.getUUID());
        }
        List<OrderStatus.Line> lines = OrderStatus.lines(OrderStatus.rounds(s.getServer(), new OrderStatus.Filter(owners, tag)));
        int unfinished = 0, queued = 0, flight = 0, impact = 0;
        for (OrderStatus.Line l : lines) {
            unfinished += l.unfinished();
            queued += l.queued();
            flight += l.flight();
            impact += l.impact();
        }
        if (lines.isEmpty()) {
            s.sendSuccess(() -> tag == null ? Component.translatable("incoming.status.none") : Component.translatable("incoming.status.none.tag", tag), false);
            return 0;
        }
        Component phases = phases(queued, flight, impact);
        Component head = (tag == null ? Component.translatable("incoming.status.title", unfinished, lines.size(), phases)
                : Component.translatable("incoming.status.title.tag", tag, unfinished, lines.size(), phases)).withStyle(ChatFormatting.GOLD);
        s.sendSuccess(() -> head, false);
        for (int i = 0; i < Math.min(LINES, lines.size()); i++) {
            Component line = line(s.getServer(), lines.get(i));
            s.sendSuccess(() -> line, false);
        }
        if (lines.size() > LINES) {
            int more = lines.size() - LINES;
            s.sendSuccess(() -> Component.translatable("incoming.status.more", more).withStyle(ChatFormatting.GRAY), false);
        }
        return unfinished;
    }

    /** “queued N, in flight N, impact work N”, the phases that have rounds. */
    private static MutableComponent phases(int queued, int flight, int impact) {
        MutableComponent out = Component.empty();
        String[] keys = {"incoming.status.queued", "incoming.status.flight", "incoming.status.impact"};
        int[] counts = {queued, flight, impact};
        boolean first = true;
        for (int i = 0; i < keys.length; i++) {
            if (counts[i] == 0) continue;
            if (!first) out.append(", ");
            out.append(Component.translatable(keys[i], counts[i]));
            first = false;
        }
        return out;
    }

    /** One order: id, weapon, owner and tag, rounds by phase, arrival. */
    private static Component line(MinecraftServer server, OrderStatus.Line l) {
        Component who = l.owner() == null ? Component.translatable("incoming.status.server") : Component.literal(name(server, l.owner()));
        Component by = l.tag() == null ? who : Component.translatable("incoming.status.tagged", who, l.tag());
        MutableComponent phases = phases(l.queued(), l.flight(), l.impact());
        if (l.eta() != OrderStatus.NONE) {
            phases.append("; ").append(l.eta() == OrderStatus.UNKNOWN ? Component.translatable("incoming.status.eta.unknown")
                    : Component.translatable("incoming.status.eta", (l.eta() + 19) / 20));
        }
        return Component.translatable("incoming.status.line", id(l.order(), true), Component.translatable("incoming.weapon." + l.weapon().getSerializedName()),
                by, phases).withStyle(ChatFormatting.GRAY);
    }

    /** Player name for {@code id}: online, else the server's profile cache, else the id's first characters. */
    private static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        var cache = server.getProfileCache();
        return cache == null ? id.toString().substring(0, 8) : cache.get(id).map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    /** Order id, clicked to copy, the full id on hover; {@code shortForm}: its first eight characters. */
    private static MutableComponent id(UUID order, boolean shortForm) {
        String full = order.toString();
        return Component.literal(shortForm ? full.substring(0, 8) : full).withStyle(st -> st.withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, full))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(full))));
    }

    /**
     * A strike command's result: the rounds {@code order} admitted (0 if it was refused or not made), told with its id, which
     * is the {@code orderId} of the order events.
     */
    static int admitted(CommandSourceStack s, @Nullable Order order) {
        int n = order == null ? 0 : order.admitted();
        if (n == 0) return 0;
        Component line = order.tag() == null ? Component.translatable("incoming.order.admitted", id(order.id(), false), n)
                : Component.translatable("incoming.order.admitted.tag", id(order.id(), false), n, order.tag());
        s.sendSuccess(() -> line, false);
        return n;
    }

    /** An order deferred to a later tick ({@code strike.Orders#deferred}): its count is not known now. */
    static int deferred(CommandSourceStack s) {
        s.sendSuccess(() -> Component.translatable("incoming.order.deferred"), false);
        return 0;
    }

    private static int clearTag(CommandSourceStack s, String tag) {
        if (!seesAll(s) && s.getEntity() instanceof ServerPlayer p) {
            int n = ServerActions.recall(p.server, p.getUUID(), tag, p.getGameProfile().getName()).rounds();
            s.sendSuccess(() -> Component.translatable("incoming.recalled.tag", tag, n), false);
            return n;
        }
        int n = ServerActions.clearTag(s.getServer(), tag, s.getTextName());
        s.sendSuccess(() -> Component.translatable("incoming.cleared.tag", tag, n), true);
        return n;
    }
}
