package ua.zentix.incoming.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse the commands scenario ({@code incoming.commands}, steps separated by ";") into client-tick steps
 * without Minecraft, for unit testing ({@code CommandPlanTest}). Step semantics: {@code ClientScenario.planCommands}.
 *
 * @param steps steps in order
 * @param nukeGate tick of {@code wait:nuke} (subsequent steps wait for a detonation packet), −1 if absent
 * @param blastGates ticks of {@code wait:blast} in order (each gates steps on the next server explosion)
 * @param end tick of "SCENARIO done"
 * @param warnings skipped steps to log
 */
record CommandPlan(List<Step> steps, int nukeGate, List<Integer> blastGates, int end, List<String> warnings) {
    /** The longest {@code wait:N} is an hour of play, tick. */
    static final int MAX_WAIT = 72_000;
    /** At what tick do the steps begin and how long after the last one to wait until “SCENARIO done”. */
    static final int START = 100, TAIL = 100;

    enum Kind { COMMAND, SHOT, HUD_OFF, HUD_ON, SKIP, FRAMES, STORM, BURNABLE, MAX_YIELD }

    record Step(int tick, Kind kind, String arg) {}

    static CommandPlan parse(String spec) {
        return parse(spec, 40);
    }

    /** {@code gap} — tick after the command ({@code incoming.commands.gap}). */
    static CommandPlan parse(String spec, int gap) {
        List<Step> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Integer> blasts = new ArrayList<>();
        int t = START, gate = -1;
        for (String c : ScenarioCommands.split(spec)) {
            // "wait:nuke" - before the generic "wait:N": otherwise it would be parsed as a number and skipped
            if (c.equals("wait:nuke")) {
                if (gate >= 0) warnings.add("the second “wait:nuke” is skipped - you can wait for the detonation once");
                else gate = t;
            } else if (c.equals("wait:blast")) {
                blasts.add(t);
            } else if (c.startsWith("wait:")) {
                int ticks;
                try {
                    ticks = Integer.parseInt(c.substring("wait:".length()).strip());
                } catch (NumberFormatException e) {
                    ticks = -1;
                }
                if (ticks < 0 || ticks > MAX_WAIT) warnings.add("«" + c + "» missing - you need an integer tick from 0 to " + MAX_WAIT + " or nuke");
                else t += ticks;
            } else if (c.startsWith("shot:")) {
                steps.add(new Step(t, Kind.SHOT, c.substring("shot:".length()).strip()));
                t += 20;
            } else if (c.equals("storm:on") || c.equals("storm:off")) {
                steps.add(new Step(t, Kind.STORM, c.substring("storm:".length())));
                t += 1;
            } else if (c.equals("burnable")) {
                // to the most flammable land within 4 km (scenario firestorm's choice), 200 blocks up
                steps.add(new Step(t, Kind.BURNABLE, ""));
                t += 200;
            } else if (c.startsWith("maxyield:")) {
                steps.add(new Step(t, Kind.MAX_YIELD, c.substring("maxyield:".length()).strip()));
                t += 1;
            } else if (c.startsWith("skip:")) {
                // time skip to that many seconds after the last detonation (server clock and client rewind, as scenario firestorm)
                steps.add(new Step(t, Kind.SKIP, c.substring("skip:".length()).strip()));
                t += 60;
            } else if (c.startsWith("frames:")) {
                // frames:<name>:<count>:<yaw per frame>:<blocks forward per frame> — consecutive rendered frames while turning/moving
                String arg = c.substring("frames:".length()).strip();
                String[] f = arg.split(":");
                int count = f.length > 1 ? Integer.parseInt(f[1].strip()) : 8;
                steps.add(new Step(t, Kind.FRAMES, arg));
                t += 12 * count + 20;
            } else if (c.equals("hud:off") || c.equals("hud:on")) {
                steps.add(new Step(t, c.equals("hud:off") ? Kind.HUD_OFF : Kind.HUD_ON, ""));
                t += 1;
            } else {
                steps.add(new Step(t, Kind.COMMAND, c));
                t += gap;
            }
        }
        return new CommandPlan(steps, gate, List.copyOf(blasts), t + TAIL, warnings);
    }
}
