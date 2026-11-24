package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialInbox;
import ua.zentix.incoming.nuclear.material.MaterialMap;
import ua.zentix.incoming.nuclear.model.FireballModel;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's material map reaches a live client after the detonation packet (encoded on a worker, delivered in parts), so the
 * first fills hold ground zero's cells as the provisional seed. When the map arrives the tiles ask again from ground zero
 * outward: a committed cell near ground zero takes its value within two frames, before the front lofts dust there (a slow
 * re-ask drew a 15 kt shore band 200-350 blocks wide with the seed's material, then flipped it in one frame).
 */
class MaterialRecommitLatencyTest {
    @Test
    void aCommittedCellIsReadWithinTwoFramesOfTheMap() {
        int worst = 0;
        for (double kt : new double[] {15, 1000}) {
            Detonation d = new Detonation(1, Vec3.ZERO, 0, kt, true, 0, 0, 0, 20000, 7, 1f, false, 0.35f);
            MaterialInbox inbox = new MaterialInbox();
            SourceMaterial source = new SourceMaterial(d, inbox, DustMaterial.SOIL.ordinal(), (x, z) -> { throw new AssertionError(); });
            DustMaterialTiles tiles = new DustMaterialTiles(d, source);
            tiles.fill(); // the first frame: no map yet (it is still being encoded on the server)
            DustGrid.Bounds bounds = DustGrid.Bounds.of(d);
            Long2IntOpenHashMap chunks = new Long2IntOpenHashMap();
            int reach = bounds.reach();
            for (int cx = -reach / 16 - 1; cx <= reach / 16 + 1; cx++)
                for (int cz = -reach / 16 - 1; cz <= reach / 16 + 1; cz++)
                    chunks.put(ChunkPos.asLong(cx, cz), 15 << (4 * DustMaterial.WATER.ordinal()));
            inbox.receive(new S2C.NukeMaterial(UUID.randomUUID(), 0, 0, MaterialMap.build(bounds, chunks, DustMaterial.WATER.ordinal(), 0).encode()));
            int side = DustMaterialTiles.SIDE, center = (side / 2) * side + side / 2, frames = 0;
            while ((tiles.rgba[4 * center] & 255) != 255 && frames < 100_000) {
                tiles.fill();
                frames++;
            }
            worst = Math.max(worst, frames);
            StringBuilder b = new StringBuilder();
            for (double fps : new double[] {60, 20}) {
                double s = (frames + 1) / fps;
                double front = d.frontRadius(s * 20 * d.scale()), ball = 0;
                for (double t = 0.01; t <= s; t += 0.01) ball = Math.max(ball, d.blocks(FireballModel.groundRadius(t, 0, kt, true)));
                b.append(String.format(Locale.ROOT, "; at %.0f fps %.2f s: front %.0f blocks, fireball footprint %.0f, seed-drawn dust band %.0f blocks",
                        fps, s, front, ball, Math.max(0, front - ball)));
            }
            System.out.printf(Locale.ROOT, "REVIEW735R2 %.0f kt: ground zero's cell re-read %d frames after the map%s%n", kt, frames, b);
        }
        assertTrue(worst <= 2, "a committed cell near ground zero keeps the provisional seed for " + worst + " frames after the map arrives");
    }
}
