package ua.zentix.incoming.strike;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One authorization policy for every strike entry: the designator packets, player commands, queued orders (deferred
 * entries rerun whole; a nuclear order waiting for its second key asks again when the key turns), the fixed launcher
 * (its owner, possibly offline), launcher linking and the second key. An entry names the {@link Action} and the
 * effective {@link Actor}; the answer is null (allowed) or the {@link Denial} with the gate that refused.
 * <p>
 * Rights are NeoForge permission nodes ({@link PermissionAPI}): {@code incoming.launch}, {@code incoming.launch.<weapon>}
 * (the weapon's command name), {@code incoming.nuclear}, {@code incoming.launcher.link}. Their default resolvers are
 * the world's switches ({@code launch.designator_for_everyone}, {@code nuclear.ops_only}, operator rights), so without
 * a permission mod every entry answers as those switches do. Hard gates stay outside the nodes: the nuclear feature
 * flag refuses before {@code incoming.nuclear}; operator and host trust ({@link NuclearKeys#trusted}: second key, warning,
 * nuclear abort and retarget, map launch site) is never granted by a node.
 * <p>
 * The actor is who gave the order: the player at the designator, the player who typed a command (also through
 * {@code /execute as}; {@code CommandSourceStack.source}), a fixed launcher's owner. Automation (console, command blocks,
 * functions they run, an ownerless launcher) asks nothing here, as with the switches. A nuclear order made for a
 * player also asks that player ({@code ServerActions.nuclearRefusal}), as the switches always did.
 * <p>
 * An offline owner never reaches the handler on the server tick: NeoForge does not promise that a handler's offline
 * lookup avoids storage, so {@link PermissionAPI#getOfflinePermission} is never called. The default handler's answer is
 * the node's own resolver (profile cache and op list). Under any other handler the resolver answers too, narrowed by
 * what the handler refused while the owner was last online in this server run ({@link #remember}, at logout): a kept
 * answer only takes rights away, never grants beyond the default, so a stale grant cannot open a launcher.
 */
public final class Authority {
    /** Use the designator at all; launch anything. Default: {@code designator_for_everyone} or operator. */
    public static final PermissionNode<Boolean> LAUNCH = node("launch", Authority::designatorDefault);
    /** Launch one weapon, {@code incoming.launch.<command name>}. Default: allowed (no per-weapon switch exists). */
    private static final Map<WeaponType, PermissionNode<Boolean>> WEAPONS = weaponNodes();
    /** Order nuclear warheads. Default: {@code nuclear.ops_only} off, or operator or world host. */
    public static final PermissionNode<Boolean> NUCLEAR = node("nuclear", (p, id, ctx) -> !IncomingConfig.SERVER.nukeOpsOnly.get() || trusted(p, id));
    /** Link fixed launchers to a designator and give them missions. Default: as {@link #LAUNCH}. */
    public static final PermissionNode<Boolean> LINK = node("launcher.link", Authority::designatorDefault);

    private Authority() {}

    /** What an entry asks to do. */
    public record Action(Kind kind, @Nullable WeaponType weapon, boolean nuclear) {
        /** The designator, its map, abort, retarget, {@code /incoming} itself. */
        public static final Action USE = new Action(Kind.USE, null, false);
        /** Link a fixed launcher or give linked launchers a mission. */
        public static final Action LINK = new Action(Kind.LINK, null, false);
        /** Turn the second key for another player's nuclear order. */
        public static final Action SECOND_KEY = new Action(Kind.SECOND_KEY, null, false);

        /** Launch {@code weapon} (null: any), with a nuclear warhead if {@code nuclear}. */
        public static Action launch(@Nullable WeaponType weapon, boolean nuclear) {
            return new Action(Kind.LAUNCH, weapon, nuclear);
        }
    }

    public enum Kind { USE, LAUNCH, LINK, SECOND_KEY }

    /** Who the order acts for. */
    public sealed interface Actor {
        /** A player online, acting with their own rights. */
        static Actor of(ServerPlayer player) {
            return new Player(player);
        }

        /** Player {@code id} (a fixed launcher's owner): online as {@link #of}, otherwise {@link Offline}. */
        static Actor owner(MinecraftServer server, UUID id) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            return online != null ? new Player(online) : new Offline(server, id);
        }
    }

    public record Player(ServerPlayer player) implements Actor {}

    public record Offline(MinecraftServer server, UUID id) implements Actor {}

    /** The gate that refused. */
    public enum Gate { USE, WEAPON, NUCLEAR_OFF, NUCLEAR, LINK }

    /**
     * A refusal: its gate, the weapon for {@link Gate#WEAPON}, and whether the default handler answered (its lines are
     * the switches' own, which point to the settings).
     */
    public record Denial(Gate gate, @Nullable WeaponType weapon, boolean byDefault) {
        /** The line the player reads ({@link ServerActions#refuse}). */
        public MutableComponent line() {
            return switch (gate) {
                case USE -> Component.translatable(byDefault ? "incoming.no_permission" : "incoming.no_permission.handler");
                case WEAPON -> Component.translatable("incoming.no_permission.weapon", weapon == null ? "" : weapon.displayName());
                case NUCLEAR_OFF -> Component.translatable("incoming.nuke.disabled");
                case NUCLEAR -> Component.translatable(byDefault ? "incoming.nuke.ops_only" : "incoming.no_permission.nuclear");
                case LINK -> Component.translatable("incoming.no_permission.link");
            };
        }
    }

    /** Why {@code actor} may not do {@code action}; null if allowed. */
    @Nullable
    public static Denial check(Action action, Actor actor) {
        boolean byDefault = defaultHandler();
        Gate gate = decide(action, IncomingConfig.SERVER.nukeEnabled.get(), node -> has(node, actor, byDefault));
        return gate == null ? null : new Denial(gate, action.weapon(), byDefault);
    }

    /** {@link #check} for a player online: allowed. */
    public static boolean allows(Action action, ServerPlayer player) {
        return check(action, Actor.of(player)) == null;
    }

    /**
     * The policy's order of gates, the first refusal wins: the designator ({@code incoming.launch}); for a launch, the
     * weapon, then for a nuclear warhead the feature flag (hard) and {@code incoming.nuclear}; the second key asks the
     * flag and {@code incoming.nuclear}; linking asks the designator and {@code incoming.launcher.link}.
     *
     * @param granted the actor's answer for a node
     * @return the refusing gate, or null
     */
    @Nullable
    static Gate decide(Action action, boolean nukeEnabled, Predicate<PermissionNode<Boolean>> granted) {
        if (action.kind() == Kind.SECOND_KEY) {
            if (!nukeEnabled) return Gate.NUCLEAR_OFF;
            return granted.test(NUCLEAR) ? null : Gate.NUCLEAR;
        }
        if (!granted.test(LAUNCH)) return Gate.USE;
        switch (action.kind()) {
            case LINK -> {
                return granted.test(LINK) ? null : Gate.LINK;
            }
            case LAUNCH -> {
                if (action.weapon() != null && !granted.test(weapon(action.weapon()))) return Gate.WEAPON;
                if (!action.nuclear()) return null;
                if (!nukeEnabled) return Gate.NUCLEAR_OFF;
                return granted.test(NUCLEAR) ? null : Gate.NUCLEAR;
            }
            default -> {
                return null;
            }
        }
    }

    /** The node for launching {@code w}. */
    public static PermissionNode<Boolean> weapon(WeaponType w) {
        return WEAPONS.get(w);
    }

    /** Every node, for registration and the logout snapshot. */
    public static List<PermissionNode<Boolean>> nodes() {
        List<PermissionNode<Boolean>> all = new ArrayList<>();
        all.add(LAUNCH);
        all.addAll(WEAPONS.values());
        all.add(NUCLEAR);
        all.add(LINK);
        return all;
    }

    /** {@link PermissionGatherEvent.Nodes}: Incoming's nodes. */
    public static void onGatherNodes(PermissionGatherEvent.Nodes e) {
        e.addNodes(new ArrayList<>(nodes()));
    }

    /** A player leaves: keep the active handler's answers for their offline launchers. Never interrupts the logout. */
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        try {
            remember(player);
        } catch (RuntimeException x) {
            Incoming.LOG.warn("Permission answers of {} not kept for offline launchers", player.getGameProfile().getName(), x);
        }
    }

    /**
     * Keep {@code player}'s answers for every node as they leave: an offline owner's launcher narrows the node default
     * by them ({@link Offline}) without asking the handler. Only for a handler other than the default one, whose offline
     * answer is its resolver. The answers carry the context of the moment (the dimension the player left from).
     */
    public static void remember(ServerPlayer player) {
        if (defaultHandler()) return;
        Map<String, Boolean> answers = new HashMap<>();
        for (PermissionNode<Boolean> node : nodes()) answers.put(node.getNodeName(), online(player, node));
        player.server.overworld().getData(ModAttachments.PERMISSION_MEMORY).put(player.getUUID(), answers);
    }

    /**
     * Vanilla permission level of player {@code id}: online value, otherwise the server's cached profile (operators;
     * 0 when unknown).
     */
    public static int level(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        GameProfileCache cache = server.getProfileCache();
        GameProfile profile = online != null ? online.getGameProfile() : cache == null ? null : cache.get(id).orElse(null);
        return profile == null ? 0 : server.getProfilePermissions(profile);
    }

    /** Last answers of players who left, per node name (non-persistent overworld attachment). */
    public static final class Memory {
        private final Map<UUID, Map<String, Boolean>> answers = new HashMap<>();

        void put(UUID id, Map<String, Boolean> got) {
            answers.put(id, Map.copyOf(got));
        }

        @Nullable
        Boolean get(UUID id, PermissionNode<Boolean> node) {
            Map<String, Boolean> got = answers.get(id);
            return got == null ? null : got.get(node.getNodeName());
        }
    }

    private static boolean has(PermissionNode<Boolean> node, Actor actor, boolean byDefault) {
        return switch (actor) {
            // before the server registers nodes (no handler yet) the node's own default answers
            case Player(ServerPlayer p) -> online(p, node);
            case Offline(MinecraftServer server, UUID id) -> {
                // the default handler's offline answer is exactly this resolver call
                boolean byResolver = node.getDefaultResolver().resolve(null, id);
                Boolean kept = byDefault ? null : server.overworld().getData(ModAttachments.PERMISSION_MEMORY).get(id, node);
                yield byResolver && (kept == null || kept);
            }
        };
    }

    /**
     * The active handler's answer for a player online; the node's own default before the server has a handler, or when
     * the handler does not carry the node (a handler that skipped registration would otherwise throw from the command
     * tree and logout).
     */
    private static boolean online(ServerPlayer p, PermissionNode<Boolean> node) {
        if (PermissionAPI.getActivePermissionHandler() == null || !PermissionAPI.getRegisteredNodes().contains(node)) {
            return node.getDefaultResolver().resolve(p, p.getUUID());
        }
        return PermissionAPI.getPermission(p, node);
    }

    /** The active handler is NeoForge's default (or none yet): node answers are the switches. */
    private static boolean defaultHandler() {
        ResourceLocation id = PermissionAPI.getActivePermissionHandler();
        return id == null || id.equals(DefaultPermissionHandler.IDENTIFIER);
    }

    private static boolean designatorDefault(@Nullable ServerPlayer p, UUID id, PermissionDynamicContext<?>... ignored) {
        return IncomingConfig.SERVER.designatorForEveryone.get() || operator(p, id);
    }

    /** Operator: online by the player's own rights, offline by the cached profile. */
    private static boolean operator(@Nullable ServerPlayer p, UUID id) {
        if (p != null) return p.hasPermissions(2);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && level(server, id) >= 2;
    }

    /** Operator or world host ({@link NuclearKeys#trusted}); an offline player is never the host of a running world. */
    private static boolean trusted(@Nullable ServerPlayer p, UUID id) {
        return p != null ? NuclearKeys.trusted(p) : operator(null, id);
    }

    private static Map<WeaponType, PermissionNode<Boolean>> weaponNodes() {
        Map<WeaponType, PermissionNode<Boolean>> nodes = new EnumMap<>(WeaponType.class);
        for (WeaponType w : WeaponType.values()) nodes.put(w, node("launch." + w.getSerializedName(), (p, id, ctx) -> true));
        return nodes;
    }

    private static PermissionNode<Boolean> node(String name, PermissionNode.PermissionResolver<Boolean> resolver) {
        return new PermissionNode<>(Incoming.MOD_ID, name, PermissionTypes.BOOLEAN, resolver);
    }
}
