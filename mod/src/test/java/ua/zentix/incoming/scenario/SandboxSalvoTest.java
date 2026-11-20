package ua.zentix.incoming.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Review #716 probe (Codex): `real … town|field <count>` parses apart from the bridge deck. */
final class SandboxSalvoTest {
    @Test
    void salvoCountDoesNotBecomeABridgeDeck() {
        assertAll(
                () -> assertEquals(0, SandboxCommand.parse("real bunker field 12").deck()),
                () -> assertEquals(0, SandboxCommand.parse("real bunker town 12").deck()),
                () -> assertEquals(12, SandboxCommand.parse("real bunker field 12").rounds()),
                () -> assertEquals(12, SandboxCommand.parse("real bunker town 12").rounds()),
                () -> assertEquals(1, SandboxCommand.parse("real bunker field 1").rounds()),
                () -> assertEquals(4, SandboxCommand.parse("real bunker deck 4").deck()),
                () -> assertEquals(4, SandboxCommand.parse("real bunker concrete 4").deck()),
                () -> assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("real bunker town NaN")),
                () -> assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("real bunker town 2.9")));
    }
}
