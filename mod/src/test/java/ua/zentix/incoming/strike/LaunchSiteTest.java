package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Place the launcher in the area of the launch site from the order ({@link LaunchSite#nearest}): the crew places the vehicle on the nearest suitable
 *  site, and does not launch it without a launcher. Places where there was no Newisle launcher (#394, frames VXC4): shore (usable in 7
 *  blocks, between the ring of former places 13–38 blocks), lake (land in 50), forest on the slope (clearing in 72).
 */
class LaunchSiteTest {
    private static final int Y = 64;

    /** The ground in height without foliage and with foliage and in the water underfoot. */
    private record Field(IntBinaryOperator ground, IntBinaryOperator top, IntBinaryOperator water) implements LaunchSite.Ground {
        @Override
        public boolean ready(int x, int z) {
            return true;
        }

        @Override
        public int ground(int x, int z) {
            return ground.applyAsInt(x, z);
        }

        @Override
        public int top(int x, int z) {
            return top.applyAsInt(x, z);
        }

        @Override
        public boolean firm(int x, int y, int z) {
            return water.applyAsInt(x, z) == 0;
        }

        @Override
        public boolean dry(int x, int y, int z) {
            return water.applyAsInt(x, z) == 0;
        }

        @Override
        public int minY() {
            return -64;
        }
    }

    private static double dist(Vec3 site, int x, int z) {
        return Math.hypot(site.x - 0.5 - x, site.z - 0.5 - z);
    }

    @Test
    void areaGoesFromThePlaceOutwards() {
        int[][] area = LaunchSite.area(LaunchSite.PLACE_AREA, LaunchSite.PLACE_STEP);
        assertEquals(0, area[0][0]);
        assertEquals(0, area[0][1]);
        double prev = 0;
        for (int[] o : area) {
            double d = Math.hypot(o[0], o[1]);
            assertTrue(d >= prev && d <= LaunchSite.PLACE_AREA, "by range, in the area: " + d);
            prev = d;
        }
        double expected = Math.PI * Math.pow(LaunchSite.PLACE_AREA / LaunchSite.PLACE_STEP, 2);
        assertEquals(expected, area.length, expected * 0.02, "places in the circle");
        assertEquals(LaunchSite.PLACE_AREA + 9, LaunchSite.PLACE_REACH, 1e-9, "ready - area and gap to the neighboring launcher");
        assertTrue(LaunchPlaces.DISTANCE * 16 >= LaunchSite.PLACE_REACH, "the salvo area covers the launcher location at any point in the location chunk");
    }

    /** Place in the lake: land in 50 blocks - launcher on the shore, not a launch outside the world. */
    @Test
    void lakeSendsTheCrewToTheShore() {
        Field lake = new Field((x, z) -> Y, (x, z) -> Y, (x, z) -> Math.hypot(x, z) < 50 ? 1 : 0);
        Vec3 site = LaunchSite.nearest(lake, 0, 0, new LaunchSite.Budget(), s -> true);
        assertNotNull(site, "shore in the area");
        // the entire square 5×5 - on land: the middle is no closer than 2 blocks from the water
        assertTrue(dist(site, 0, 0) >= 52 && dist(site, 0, 0) < 55, "nearest shore: " + dist(site, 0, 0));
    }

    /** Forest: crowns and trunks everywhere, clearing 8×8 in 72 blocks - launcher in the clearing. */
    @Test
    void forestSendsTheCrewToAClearing() {
        IntBinaryOperator clearing = (x, z) -> x >= 68 && x < 76 && z >= -4 && z < 4 ? 1 : 0;
        // trunks through 3 block: top jumps without foliage; crowns - on 8 above the ground
        Field forest = new Field((x, z) -> clearing.applyAsInt(x, z) == 1 || Math.floorMod(x, 3) != 0 || Math.floorMod(z, 3) != 0 ? Y : Y + 6,
                (x, z) -> clearing.applyAsInt(x, z) == 1 ? Y : Y + 8, (x, z) -> 0);
        Vec3 site = LaunchSite.nearest(forest, 0, 0, new LaunchSite.Budget(), s -> true);
        assertNotNull(site, "clearing in the area");
        assertTrue(site.x >= 70 && site.x <= 74 && Math.abs(site.z) <= 2, "in the clearing: " + site);
    }

    /** Shore: suitable in 7 blocks, between the places of the previous ring (13–38 blocks) - we take it. */
    @Test
    void shoreTakesTheNearestFlatSpot() {
        Field shore = new Field((x, z) -> x >= 5 ? Y : Y - 3 + Math.floorMod(x * 7 + z * 13, 5), (x, z) -> x >= 5 ? Y : Y + 2, (x, z) -> 0);
        Vec3 site = LaunchSite.nearest(shore, 0, 0, new LaunchSite.Budget(), s -> true);
        assertNotNull(site);
        assertTrue(dist(site, 0, 0) < 9, "nearest flat: " + dist(site, 0, 0));
    }

    /**
     * The sector is closed - the next place is no closer than the gap to the closed one (its sector abuts the same trees); place
     *  is not suitable at first, then it is suitable - the first suitable one is taken.
     */
    @Test
    void closedSectorClosesItsNeighbours() {
        Field flat = new Field((x, z) -> Y, (x, z) -> Y, (x, z) -> 0);
        List<Vec3> tried = new ArrayList<>();
        Vec3 site = LaunchSite.nearest(flat, 0, 0, new LaunchSite.Budget(), s -> {
            tried.add(s);
            return tried.size() == 3;
        });
        assertNotNull(site);
        assertEquals(3, tried.size());
        for (int i = 0; i < tried.size(); i++) {
            for (int j = 0; j < i; j++) assertTrue(tried.get(i).distanceTo(tried.get(j)) >= 9, "neighbors of the closed one: " + tried);
        }
    }

    /** Nowhere (sea for the entire area) - null, and the price is no more than a place to check and no more than the limit. */
    @Test
    void openSeaHasNoSiteAndStaysInBudget() {
        Field sea = new Field((x, z) -> Y, (x, z) -> Y, (x, z) -> 1);
        LaunchSite.Budget budget = new LaunchSite.Budget();
        assertNull(LaunchSite.nearest(sea, 0, 0, budget, s -> true));
        assertTrue(budget.spent() <= 3 * LaunchSite.area(LaunchSite.PLACE_AREA, LaunchSite.PLACE_STEP).length && !budget.out(),
                "search price: " + budget.spent());
        LaunchSite.Budget small = new LaunchSite.Budget(100);
        assertNull(LaunchSite.nearest(sea, 0, 0, small, s -> true));
        assertTrue(small.out(), "the limit has been reached - the search has stopped");
    }
}
