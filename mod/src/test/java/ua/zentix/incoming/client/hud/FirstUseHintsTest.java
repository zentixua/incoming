package ua.zentix.incoming.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class FirstUseHintsTest {
    /** A hint is recorded once: claiming it again finds it in the list saved in incoming-client.toml. */
    @Test
    void shownOnce() {
        List<String> after = FirstUseHints.Ledger.claim(List.of(), "console");
        assertEquals(List.of("console"), after);
        assertNull(FirstUseHints.Ledger.claim(after, "console"));
    }

    /** Recording one hint keeps the others already shown and leaves other hints due. */
    @Test
    void hintsAreIndependent() {
        assertEquals(List.of("other", "console"), FirstUseHints.Ledger.claim(List.of("other"), "console"));
        assertEquals(List.of("console", "other"), FirstUseHints.Ledger.claim(List.of("console"), "other"));
    }
}
