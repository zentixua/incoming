package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused order shows its reason in red on the action bar through {@link ServerActions#refuse}, the one channel
 * the player sees while aiming or right after the console closes. A red line sent any other way in the order code
 * would land in chat or lose the style. Notices about orders already accepted (second-key expiry, refunds) are chat
 * lines on purpose and are not red action-bar messages.
 */
class RefusalChannelTest {
    private static Stream<Path> list(String pkg) {
        try {
            return Files.list(Path.of("src/main/java/ua/zentix/incoming", pkg));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The channel itself: red, on the action bar (overlay), never chat. */
    @Test
    void refuseIsRedOnTheActionBar() throws Exception {
        String src = Files.readString(Path.of("src/main/java/ua/zentix/incoming/strike/ServerActions.java"));
        assertTrue(src.contains("player.displayClientMessage(reason.copy().withStyle(ChatFormatting.RED), true);"), "ServerActions.refuse changed channel");
    }

    @Test
    void redActionBarLinesGoThroughRefuse() throws Exception {
        Pattern red = Pattern.compile("displayClientMessage\\([^;]*ChatFormatting\\.RED[^;]*;");
        TreeSet<String> stray = new TreeSet<>();
        try (Stream<Path> files = Stream.of("strike", "launcher").flatMap(RefusalChannelTest::list)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = red.matcher(src);
                while (m.find()) {
                    // the channel itself
                    if (f.getFileName().toString().equals("ServerActions.java") && m.group().contains("reason.copy()")) continue;
                    // the launch confirmation is red as an alert, not a refusal
                    if (f.getFileName().toString().equals("StrikeService.java") && m.group().contains("\"incoming.launched.\"")) continue;
                    stray.add(f.getFileName() + ": " + m.group());
                }
            }
        }
        assertEquals(new TreeSet<>(), stray, "refusals outside ServerActions.refuse");
    }
}
