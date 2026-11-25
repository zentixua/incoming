package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ruin write ({@link RuinPlan} {@code write}) stays in small phases. A single write method compiles to a 100-135 kB
 * unit and recompiles on each branch first taken at the front (1 Mt over a city at 3 km: six C2 recompiles, 3.85 CPU-s in
 * the 4 s of the front, on cores the client's render thread waits for). Bytecode size stands in for compiled size: with
 * small phases the largest compile of a write phase at that front is 35 kB. Read from the class files without loading Minecraft.
 */
class RuinWriteShapeTest {
    /** Bytecode bytes a write phase may have: under a quarter of the 2,550-byte single method. */
    private static final int PHASE_BYTES = 600;
    /** {@code RuinPlan.write}'s descriptor start: other methods named write (serializers) are not the ruin write. */
    private static final String WRITE = "write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;";

    @Test
    void writeAndEachPhaseStaySmall() throws IOException {
        Map<String, Integer> plan = codeLengths("RuinPlan");
        Map.Entry<String, Integer> write = plan.entrySet().stream().filter(e -> e.getKey().startsWith(WRITE)).findFirst().orElse(null);
        assertNotNull(write, "RuinPlan ruin write not found: " + plan.keySet());
        assertTrue(write.getValue() <= PHASE_BYTES, "RuinPlan." + write.getKey() + " is " + write.getValue() + " bytecode bytes");
        Map<String, Integer> phases = codeLengths("RuinPlan$Write");
        assertFalse(phases.isEmpty(), "RuinPlan$Write not found");
        for (Map.Entry<String, Integer> e : phases.entrySet()) {
            assertTrue(e.getValue() <= PHASE_BYTES, "RuinPlan$Write." + e.getKey() + " is " + e.getValue() + " bytecode bytes");
        }
    }

    /** Overloads are separate entries (review r1 probe): a large serializer named write is not held to the bound. */
    @Test
    void overloadsAreKeptApart() throws IOException {
        Map<String, Integer> twin = codeLengths("RuinWriteShapeTest$Twin");
        assertEquals(2, twin.get("write(I)I"), "write(int) " + twin);
        assertTrue(twin.get("write(JJJ)J") > 2, "write(long, long, long) " + twin);
    }

    static final class Twin {
        int write(int a) {
            return a;
        }

        long write(long a, long b, long c) {
            long s = a;
            for (int i = 0; i < 8; i++) s = s * 31 + b ^ c + i;
            return s;
        }
    }

    /** Code length per method, keyed name + descriptor, of a class in this package (JVMS 4.7.3 Code attribute). */
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
                String name = utf8[in.readUnsignedShort()] + utf8[in.readUnsignedShort()];
                for (int a = in.readUnsignedShort(); a > 0; a--) {
                    String attribute = utf8[in.readUnsignedShort()];
                    int length = in.readInt();
                    if ("Code".equals(attribute)) {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        int code = in.readInt();
                        out.put(name, code);
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
