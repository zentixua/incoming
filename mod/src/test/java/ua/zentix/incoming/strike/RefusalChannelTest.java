package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A refused order shows its reason in red on the action bar through {@link ServerActions#refuse}, the one channel
 * the player sees while aiming or right after the console closes. A red line sent any other way in the order code
 * would land in chat or lose the style. Notices about orders already accepted (second-key expiry, refunds) are chat
 * lines on purpose and are not red action-bar messages.
 */
class RefusalChannelTest {
    @Test
    void redActionBarLinesGoThroughRefuse() throws Exception {
        Pattern red = Pattern.compile("displayClientMessage\\([^;]*ChatFormatting\\.RED[^;]*;");
        TreeSet<String> stray = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/ua/zentix/incoming/strike"))) {
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
