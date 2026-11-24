package ua.zentix.incoming.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.api.NuclearDetonationEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.nuclear.radiation.RadiationDose;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The Almighty game-master note ({@code data/incoming/almighty/notes/strikes.md}) against the mod: every command it
 * shows parses for the game master's source (permission level 4, no entity), the radiation result it states holds,
 * every event class and field it names is what Almighty's rules see (its {@code EventTypes.resolve} and {@code
 * Json.fields}), and both rule recipes are valid {@code rule.add} parameters whose Groovy scripts compile and behave as
 * the note says under Almighty's Groovy. The raid recipe's end condition on a real server: {@code AlmightyNoteGameTests}.
 */
class AlmightyNoteTest {
    private static final String NOTE = "/data/incoming/almighty/notes/strikes.md";
    /** Almighty {@code Rules.parse}: the parameters {@code rule.add} takes. */
    private static final Set<String> RULE_KEYS = Set.of("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist");
    private static final String API = "ua.zentix.incoming.api.";
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void bootstrap() {
        CommandFixture.bootstrap();
    }

    @BeforeEach
    void register() throws ReflectiveOperationException {
        CommandFixture.loadConfig();
        dispatcher = CommandFixture.dispatcher();
    }

    @AfterEach
    void unloadConfig() {
        CommandFixture.unloadConfig();
    }

    private static String note() throws IOException {
        try (InputStream in = AlmightyNoteTest.class.getResourceAsStream(NOTE)) {
            assertNotNull(in, NOTE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Almighty's {@code CommandRunner.source}: level 4, no entity, at world spawn (here the origin). */
    private static CommandSourceStack gameMaster(MinecraftServer server) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, server == null ? null : mock(ServerLevel.class), 4, "Almighty",
                Component.literal("Almighty"), server, null);
    }

    private void parses(String command) {
        String text = command.startsWith("/") ? command.substring(1) : command;
        var parsed = dispatcher.parse(text, gameMaster(null));
        assertFalse(parsed.getReader().canRead(), () -> command + ": " + parsed.getExceptions());
        assertNotNull(parsed.getContext().getCommand(), command);
    }

    /** Every command the note shows in backticks parses for the game master. */
    @Test
    void everyCommandInTheNoteParsesForTheGameMaster() throws IOException {
        Matcher m = Pattern.compile("`(incoming [^`]+)`").matcher(note());
        int n = 0;
        while (m.find()) {
            parses(m.group(1));
            n++;
        }
        // the grid, radiation and status examples are there
        assertTrue(n >= 13, "commands in the note: " + n);
    }

    /** {@code radiation <player>} returns the dose in hundredths of a gray, {@code radiation clear} the players cleared. */
    @Test
    void radiationReturnsTheDoseInHundredthsOfAGray() throws CommandSyntaxException {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList list = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(list);
        ServerPlayer steve = mock(ServerPlayer.class);
        when(list.getPlayerByName("Steve")).thenReturn(steve);
        try (var radiation = mockStatic(RadiationTicker.class)) {
            radiation.when(() -> RadiationTicker.dose(steve)).thenReturn(new RadiationDose(1.5f, 0.2f, 0, 0.01f));
            assertEquals(150, dispatcher.execute("incoming radiation Steve", gameMaster(server)));
            assertEquals(1, dispatcher.execute("incoming radiation clear Steve", gameMaster(server)));
            radiation.verify(() -> RadiationTicker.clear(steve));
            radiation.verify(() -> RadiationTicker.clear(any()), org.mockito.Mockito.times(1));
        }
    }

    // ---------------------------------------------------------------- events

    /** Almighty {@code EventTypes.resolve}: a class name with {@code $} or dots between nested classes. */
    private static Class<?> resolve(String name) {
        String dotted = name.replace('$', '.');
        String[] parts = dotted.split("\\.");
        for (int nested = 0; nested < parts.length; nested++) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) b.append(i >= parts.length - nested ? '$' : '.');
                b.append(parts[i]);
            }
            try {
                Class<?> c = Class.forName(b.toString(), false, AlmightyNoteTest.class.getClassLoader());
                assertTrue(Event.class.isAssignableFrom(c), name + " is not an event");
                return c;
            } catch (ClassNotFoundException e) {
                // try with one more nested class
            }
        }
        return fail("no event class " + name);
    }

    /** Almighty {@code Json.fields}: public parameterless {@code getX()}/{@code isX()} (boolean), not of Object or Event. */
    private static Set<String> fields(Class<?> event) {
        Set<String> out = new TreeSet<>();
        for (Method m : event.getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.getReturnType() == void.class) continue;
            if (m.getDeclaringClass() == Object.class || m.getDeclaringClass() == Event.class) continue;
            String n = m.getName();
            String bare = n.startsWith("get") && n.length() > 3 ? n.substring(3)
                    : n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class ? n.substring(2) : null;
            if (bare == null || !Character.isUpperCase(bare.charAt(0))) continue;
            out.add(Character.toLowerCase(bare.charAt(0)) + bare.substring(1));
        }
        return out;
    }

    /** The note's bullet that starts with {@code head}, on one line. */
    private static String bullet(String note, String head) {
        int start = note.indexOf("\n- " + head);
        assertTrue(start >= 0, "no bullet " + head);
        int end = note.indexOf("\n- ", start + 1);
        return note.substring(start + 3, end < 0 ? note.length() : end).replaceAll("\\s+", " ");
    }

    /**
     * Every event class the note names resolves as Almighty resolves it, and each field list is exactly the set of fields
     * Almighty emits for that class: a getter added to or removed from {@code api/} fails here until the note follows.
     */
    @Test
    void eventClassesAndFieldsAreWhatAlmightySees() throws IOException {
        String events = bullet(note(), "Events for rules:");
        Matcher named = Pattern.compile("ua\\.zentix\\.incoming\\.api\\.[A-Z][\\w.]*[A-Za-z]").matcher(events);
        Set<String> classes = new LinkedHashSet<>();
        while (named.find()) classes.add(named.group());
        for (String c : classes) resolve(c);
        for (String nested : List.of("Request", "Rejected", "Accepted")) resolve(API + "StrikeOrderEvent." + nested);

        Pattern list = Pattern.compile("((?:ua\\.zentix\\.incoming\\.api\\.[\\w.]+?)(?: and ua\\.zentix\\.incoming\\.api\\.[\\w.]+?)*)(?: \\([^)]*\\))?: ([^.]+)\\.");
        Map<String, Set<String>> listed = new LinkedHashMap<>();
        Matcher m = list.matcher(events);
        while (m.find()) {
            Set<String> names = Arrays.stream(m.group(2).replaceAll("\\([^)]*\\)", "").split(","))
                    .map(String::strip).collect(Collectors.toCollection(TreeSet::new));
            for (String c : m.group(1).split(" and ")) listed.put(c, names);
        }
        assertEquals(Set.of(API + "StrikeRoundEvent.Released", API + "StrikeRoundEvent.Ended", API + "NuclearDetonationEvent",
                API + "GridOutageEvent", API + "GridRestoreEvent"), listed.keySet(), "classes with field lists");
        for (var e : listed.entrySet()) assertEquals(fields(resolve(e.getKey())), e.getValue(), e.getKey());

        // the outcomes as the feed writes them (Json.from: an enum's serialized name)
        Matcher outcomes = Pattern.compile("outcome \\(([^)]*)\\)").matcher(events);
        assertTrue(outcomes.find(), "no outcome list");
        assertEquals(Arrays.stream(StrikeRoundEvent.Outcome.values()).map(StrikeRoundEvent.Outcome::getSerializedName).toList(),
                Arrays.stream(outcomes.group(1).split(",")).map(String::strip).toList());
    }

    // ---------------------------------------------------------------- rule recipes

    private record Recipe(JsonObject params, String script) {}

    /** The note's recipes: a JSON block of {@code rule.add} parameters followed by its Groovy script. */
    private static Map<String, Recipe> recipes() throws IOException {
        Matcher m = Pattern.compile("```json\\n(.*?)\\n```\\n```groovy\\n(.*?)\\n```", Pattern.DOTALL).matcher(note());
        Map<String, Recipe> out = new LinkedHashMap<>();
        while (m.find()) {
            JsonObject params = JsonParser.parseString(m.group(1)).getAsJsonObject();
            out.put(params.get("name").getAsString(), new Recipe(params, m.group(2)));
        }
        return out;
    }

    /** Both recipes are parameters {@code rule.add} accepts (Almighty {@code Rules.parse}) on events it resolves. */
    @Test
    void recipesAreValidRuleParameters() throws IOException {
        Map<String, Recipe> recipes = recipes();
        assertEquals(Set.of("raid", "nuke-watch"), recipes.keySet());
        for (var r : recipes.values()) {
            JsonObject p = r.params();
            assertTrue(RULE_KEYS.containsAll(p.keySet()), "not rule.add parameters: " + p.keySet());
            assertFalse(p.has("script"), "the script is the Groovy block");
            String name = p.get("name").getAsString();
            assertTrue(!name.isBlank() && name.length() <= 64, name);
            if (p.has("every")) assertTrue(p.get("every").getAsInt() >= 0 && p.get("every").getAsInt() <= 72_000);
            String event = p.get("event").getAsString();
            if (!event.equalsIgnoreCase("tick")) resolve(event);
        }
        assertEquals("tick", recipes.get("raid").params().get("event").getAsString());
        assertEquals(100, recipes.get("raid").params().get("every").getAsInt());
        assertEquals(NuclearDetonationEvent.class, resolve(recipes.get("nuke-watch").params().get("event").getAsString()));
        assertTrue(recipes.get("nuke-watch").params().get("persist").getAsBoolean());
    }

    /**
     * Almighty's {@code gm} in a script: {@code command} returns one result per command ({@code CommandRunner}: command,
     * ok, result, output, errors), {@code emit} writes to the feed. Every command a script runs must parse for the game
     * master; {@code answer} plays the server's result.
     */
    public static final class Gm {
        final List<String> commands = new ArrayList<>();
        final List<Object> emitted = new ArrayList<>();
        private final AlmightyNoteTest test;
        private final Function<String, Map<String, Object>> answer;

        Gm(AlmightyNoteTest test, Function<String, Map<String, Object>> answer) {
            this.test = test;
            this.answer = answer;
        }

        public Object command(String... commands) {
            return command(Arrays.asList(commands));
        }

        public Object command(List<String> commands) {
            List<Object> out = new ArrayList<>();
            for (String c : commands) {
                test.parses(c);
                this.commands.add(c);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("command", c);
                r.putAll(answer.apply(c));
                out.add(r);
            }
            return out;
        }

        public void emit(Object data) {
            emitted.add(data);
        }
    }

    private static Map<String, Object> result(int result) {
        return Map.of("ok", true, "result", result, "output", List.of("…"));
    }

    private static Map<String, Object> entry(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static Script compile(String code, Map<String, Object> variables) {
        Script s = new GroovyShell().parse(code);
        s.setBinding(new Binding(new HashMap<>(variables)));
        return s;
    }

    /**
     * The raid recipe, run as its tick rule would run it: wave 1 starts at once; while {@code status tag} returns more than
     * 0 nothing happens; at 0 it reports the wave over and starts the next; after the last it reports the raid over once
     * and stays quiet. A refused wave (result 0) reports its errors and stops the raid.
     */
    @Test
    void raidRecipeStartsTheNextWaveWhenStatusReturnsZero() throws IOException {
        String code = recipes().get("raid").script();
        Map<String, Integer> left = new HashMap<>();
        Gm gm = new Gm(this, c -> {
            Matcher salvo = Pattern.compile("incoming salvo \\w+ (\\d+) .* tag (raid\\d)").matcher(c);
            if (salvo.matches()) {
                left.put(salvo.group(2), Integer.parseInt(salvo.group(1)));
                return result(Integer.parseInt(salvo.group(1)));
            }
            Matcher status = Pattern.compile("incoming status tag (raid\\d)").matcher(c);
            assertTrue(status.matches(), c);
            return result(left.getOrDefault(status.group(1), 0));
        });
        Map<String, Object> state = new HashMap<>();
        Script rule = compile(code, Map.of("gm", gm, "state", state, "server", mock(MinecraftServer.class), "rule", 1L));

        rule.run();
        assertEquals(List.of(entry("raid", "wave started", "wave", 1, "rounds", 12)), gm.emitted);
        assertEquals("incoming salvo grad 12 30 at 200 -150 tag raid1", gm.commands.getLast());
        for (int rounds : new int[]{5, 1}) {
            left.put("raid1", rounds);
            rule.run();
            assertEquals(1, gm.emitted.size(), "emitted with " + rounds + " rounds unfinished");
            assertEquals("incoming status tag raid1", gm.commands.getLast());
        }
        left.put("raid1", 0);
        rule.run();
        assertEquals(List.of(entry("raid", "wave over", "wave", 1), entry("raid", "wave started", "wave", 2, "rounds", 6)),
                gm.emitted.subList(1, 3));
        left.put("raid2", 0);
        rule.run();
        left.put("raid3", 0);
        rule.run();
        assertEquals(List.of(entry("raid", "wave over", "wave", 3), entry("raid", "over", "waves", 3)),
                gm.emitted.subList(gm.emitted.size() - 2, gm.emitted.size()));
        int commands = gm.commands.size(), emits = gm.emitted.size();
        rule.run();
        rule.run();
        assertEquals(commands, gm.commands.size(), "commands after the raid is over");
        assertEquals(emits, gm.emitted.size(), "feed entries after the raid is over");

        Gm refusing = new Gm(this, c -> Map.of("ok", false, "result", 0, "errors", List.of("Strike order refused")));
        Map<String, Object> fresh = new HashMap<>();
        Script refused = compile(code, Map.of("gm", refusing, "state", fresh, "server", mock(MinecraftServer.class), "rule", 2L));
        refused.run();
        refused.run();
        assertEquals(List.of(entry("raid", "refused", "wave", 1, "errors", List.of("Strike order refused"), "output", null)), refusing.emitted);
        assertEquals(1, refusing.commands.size(), "commands after a refused wave");
    }

    /** The detonation recipe: one feed entry with the burst and the online players within 3000 blocks in its dimension. */
    @Test
    void detonationRecipeEmitsTheBurstAndThePlayersNearIt() throws IOException {
        String code = recipes().get("nuke-watch").script();
        Vec3 at = new Vec3(100, 70, -200);
        ServerLevel overworld = mock(ServerLevel.class), nether = mock(ServerLevel.class);
        when(overworld.dimension()).thenReturn(Level.OVERWORLD);
        when(nether.dimension()).thenReturn(Level.NETHER);
        List<ServerPlayer> players = List.of(player("Near", overworld, at.add(1000, 0, 0)), player("Far", overworld, at.add(0, 0, 3500)),
                player("Nether", nether, at));
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList list = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(list);
        when(list.getPlayers()).thenReturn(players);
        NuclearDetonationEvent event = new NuclearDetonationEvent(Level.OVERWORLD, 3, at, 15, false, null, UUID.randomUUID(), UUID.randomUUID(), "raid3");
        Gm gm = new Gm(this, c -> fail("the recipe runs no command: " + c));

        compile(code, Map.of("gm", gm, "state", new HashMap<>(), "server", server, "event", event, "rule", 3L)).run();
        assertEquals(List.of(entry("nuke", 15.0, "air", false, "at", at, "dimension", Level.OVERWORLD, "tag", "raid3", "near", Map.of("Near", 1000L))),
                gm.emitted);
    }

    private static ServerPlayer player(String name, ServerLevel level, Vec3 pos) {
        ServerPlayer p = mock(ServerPlayer.class);
        when(p.level()).thenReturn(level);
        when(p.position()).thenReturn(pos);
        when(p.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), name));
        return p;
    }
}
