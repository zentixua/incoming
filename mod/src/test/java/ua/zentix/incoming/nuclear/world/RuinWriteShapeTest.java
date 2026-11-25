package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ruin write ({@link RuinPlan} {@code write}) stays in small phases. As one 2,550-byte method it compiled to a 100-135 kB
 * unit, and the branches a city chunk first takes at the front (fire layers, block entities) deoptimised it: six C2
 * recompiles, 3.85 CPU-s in the 4 s a 1 Mt front crossed a city viewer, on cores the client's render thread was waiting
 * for (passage-city-2, Zearth Silo at 3 km). Each phase now compiles on its own, and a new branch recompiles only its
 * phase. The bound is on bytecode, read from the class files without loading Minecraft.
 */
class RuinWriteShapeTest {
    /** Bytecode bytes a write phase may have: about a fifth of the old single method. */
    private static final int PHASE_BYTES = 600;

    @Test
    void writeAndEachPhaseStaySmall() throws IOException {
        Map<String, Integer> plan = codeLengths("RuinPlan");
        Integer write = plan.get("write");
        assertNotNull(write, "RuinPlan.write not found: " + plan.keySet());
        assertTrue(write <= PHASE_BYTES, "RuinPlan.write is " + write + " bytecode bytes");
        Map<String, Integer> phases = codeLengths("RuinPlan$Write");
        assertFalse(phases.isEmpty(), "RuinPlan$Write not found");
        for (Map.Entry<String, Integer> e : phases.entrySet()) {
            assertTrue(e.getValue() <= PHASE_BYTES, "RuinPlan$Write." + e.getKey() + " is " + e.getValue() + " bytecode bytes");
        }
    }

    /** Largest code length per method name of a class in this package (JVMS 4.7.3 Code attribute). */
    private static Map<String, Integer> codeLengths(String simpleName) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (InputStream raw = RuinWriteShapeTest.class.getResourceAsStream(simpleName + ".class")) {
            if (raw == null) return out;
            DataInputStream in = new DataInputStream(raw);
            in.readInt(); // magic
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("constant tag " + tag);
                }
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.skipNBytes(2L * in.readUnsignedShort());
            for (int f = in.readUnsignedShort(); f > 0; f--) {
                in.skipNBytes(6);
                skipAttributes(in);
            }
            for (int m = in.readUnsignedShort(); m > 0; m--) {
                in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                in.readUnsignedShort();
                for (int a = in.readUnsignedShort(); a > 0; a--) {
                    String attribute = utf8[in.readUnsignedShort()];
                    int length = in.readInt();
                    if ("Code".equals(attribute)) {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        int code = in.readInt();
                        out.merge(name, code, Math::max);
                        in.skipNBytes(length - 8L);
                    } else {
                        in.skipNBytes(length);
                    }
                }
            }
        }
        return out;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        for (int a = in.readUnsignedShort(); a > 0; a--) {
            in.readUnsignedShort();
            in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
        }
    }
}
