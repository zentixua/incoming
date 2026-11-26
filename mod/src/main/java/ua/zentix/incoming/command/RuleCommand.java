package ua.zentix.incoming.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic3CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.strike.StrikeRules;

import java.util.List;
import java.util.Locale;

/**
 * {@code /incoming rule [<key> [<value>]]} (permission level 2, as {@code /gamerule}): reads and sets world rules in the
 * world's own server config, their one home ({@code serverconfig/incoming-server.toml}); a set value is saved at once, so
 * the file, the config screen and a restart see it. Only rules a change can reach just the orders made after it:
 * those {@link StrikeRules} captures with each order (its rounds carry them), and those read only when an order is
 * admitted. Returns, for {@code execute store}: the key count for the list, the value as a number for a read (true 1,
 * false 0), 1 for a set; an unknown key or a bad value fails (0) and changes nothing.
 */
public final class RuleCommand {
    /** One world rule: its config value, named by its key ({@code section.name}). */
    public record Rule(ModConfigSpec.ConfigValue<?> config) {
        public String key() {
            return String.join(".", config.getPath());
        }

        boolean bool() {
            return config instanceof ModConfigSpec.BooleanValue;
        }

        /** The value as a number: true 1, false 0, integers as they are. */
        public int number() {
            return config.get() instanceof Boolean b ? (b ? 1 : 0) : ((Number) config.get()).intValue();
        }

        String shown() {
            return String.valueOf(config.get());
        }

        /** What the key accepts, for the refusal and the list. */
        String accepts() {
            if (bool()) return "true|false";
            ModConfigSpec.Range<Integer> range = config.getSpec().getRange();
            return String.format(Locale.ROOT, "%d..%d", range.getMin(), range.getMax());
        }

        void set(Object v) {
            if (config instanceof ModConfigSpec.BooleanValue b) b.set((Boolean) v);
            else if (config instanceof ModConfigSpec.IntValue i) i.set((Integer) v);
            else throw new IllegalStateException("Rule " + key() + " is neither boolean nor integer");
        }
    }

    private static final DynamicCommandExceptionType UNKNOWN = new DynamicCommandExceptionType(
            key -> Component.translatable("incoming.rule.unknown", key, String.join(", ", keys())));
    private static final Dynamic3CommandExceptionType BAD_VALUE = new Dynamic3CommandExceptionType(
            (key, value, accepts) -> Component.translatable("incoming.rule.bad_value", key, value, accepts));

    private RuleCommand() {}

    /**
     * The exposed rules, in list order. Captured with each order ({@link StrikeRules}): the five {@code world.*} rules and
     * {@code grid.enabled}. Read only when an order is admitted: the rest.
     */
    public static List<Rule> rules() {
        IncomingConfig.Server c = IncomingConfig.SERVER;
        return List.of(rule(c.blockDamage), rule(c.shatterGlass), rule(c.fire), rule(c.debrisStay), rule(c.collapse), rule(c.gridEnabled),
                rule(c.nukeEnabled), rule(c.nukeOpsOnly), rule(c.carrierNukes), rule(c.designatorForEveryone), rule(c.maxSalvo), rule(c.maxSpread),
                rule(c.maxActivePerPlayer));
    }

    private static Rule rule(ModConfigSpec.ConfigValue<?> v) {
        return new Rule(v);
    }

    static List<String> keys() {
        return rules().stream().map(Rule::key).toList();
    }

    @Nullable
    static Rule find(String key) {
        for (Rule r : rules()) if (r.key().equalsIgnoreCase(key)) return r;
        return null;
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("rule").requires(s -> s.hasPermission(2))
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(keys(), b))
                        .executes(RuleCommand::read)
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    Rule r = find(StringArgumentType.getString(ctx, "key"));
                                    if (r == null) return b.buildFuture();
                                    return SharedSuggestionProvider.suggest(r.bool() ? List.of("true", "false")
                                            : List.of(r.shown(), String.valueOf(r.config().getDefault())), b);
                                })
                                .executes(RuleCommand::set)));
    }

    private static int list(CommandSourceStack s) {
        List<Rule> rules = rules();
        s.sendSuccess(() -> {
            var text = Component.translatable("incoming.rule.list");
            for (Rule r : rules) text.append("\n").append(Component.translatable("incoming.rule.line", r.key(), r.shown(), r.accepts()));
            return text;
        }, false);
        return rules.size();
    }

    private static Rule rule(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String key = StringArgumentType.getString(ctx, "key");
        Rule r = find(key);
        if (r == null) throw UNKNOWN.create(key);
        return r;
    }

    private static int read(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Rule r = rule(ctx);
        ctx.getSource().sendSuccess(() -> Component.translatable("incoming.rule.value", r.key(), r.shown()), false);
        return r.number();
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Rule r = rule(ctx);
        String text = StringArgumentType.getString(ctx, "value");
        Object v = parse(r, text);
        if (v == null) throw BAD_VALUE.create(r.key(), text, r.accepts());
        store(r, v);
        ctx.getSource().sendSuccess(() -> Component.translatable("incoming.rule.set", r.key(), r.shown()), true);
        return 1;
    }

    /**
     * Set and save to the world's server config file, the rule's one home. Limit: FML's file watcher reloads that file on
     * its own thread 500 ms after the last write (NightConfig debounce) and swaps the values in memory without a lock the
     * mod can take ({@code ModConfig.lock} is package-private); a set landing inside that swap, a few milliseconds, is
     * reverted in memory until its own write's reload. Rules set together in one tick give one reload of the final file.
     */
    static void store(Rule r, Object v) {
        r.set(v);
        IncomingConfig.SERVER_SPEC.save();
    }

    /**
     * Server rules gate {@code /incoming} branches (designator for everyone, nuclear on, operators only): on every reload
     * of the server config (this command's save, the config screen, a hand edit of the file) each online player gets
     * the command tree again, as {@code /op} sends it. The reload runs on FML's watcher thread; the trees go out on the
     * server thread.
     */
    public static void onConfigReload(ModConfigEvent.Reloading e) {
        if (e.getConfig().getType() != ModConfig.Type.SERVER) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            for (var player : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(player);
        });
    }

    /** {@code text} as a value of {@code r}: {@code true}/{@code false}, or a whole number in the key's range; null otherwise. */
    @Nullable
    static Object parse(Rule r, String text) {
        if (r.bool()) {
            String t = text.toLowerCase(Locale.ROOT);
            return t.equals("true") ? Boolean.TRUE : t.equals("false") ? Boolean.FALSE : null;
        }
        int n;
        try {
            n = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
        return r.config().getSpec().test(n) ? n : null;
    }
}
