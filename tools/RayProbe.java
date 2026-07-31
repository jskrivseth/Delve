import cydi.BlockFinder;

/** Exercises the DDA voxel traversal against a synthetic grid. */
public class RayProbe {

    static int failures = 0;

    // Solid slab at x==5, air elsewhere; water wall at x==2.
    static final BlockFinder.BlockLookup SLAB = (x, y, z) -> {
        if (x == 2) return 2;    // WATER
        if (x == 5) return 1;    // GRASS
        return 0;                // AIR
    };

    static void check(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name
                + "  expected=" + expected + " actual=" + actual);
    }

    static String hit(BlockFinder.RayHit h) {
        return h == null ? "null" : h.x + "," + h.y + "," + h.z + " place=" + h.placeX + "," + h.placeY + "," + h.placeZ;
    }

    public static void main(String[] args) {
        System.out.println("DDA voxel traversal");

        // Straight down +X from inside cell 0: passes through water at x=2, hits x=5.
        check("hits the slab, sees through water",
                hit(BlockFinder.raycast(0.5, 0.5, 0.5, 1, 0, 0, 16, SLAB)),
                "5,0,0 place=4,0,0");

        // Ray exactly parallel to an axis used to leave a whole plane sweep empty.
        check("axis-parallel -X",
                hit(BlockFinder.raycast(9.5, 0.5, 0.5, -1, 0, 0, 16, SLAB)),
                "5,0,0 place=6,0,0");

        // Out of reach.
        check("respects max distance",
                hit(BlockFinder.raycast(0.5, 0.5, 0.5, 1, 0, 0, 2.0, SLAB)),
                "null");

        // Negative world coordinates: floorDiv territory.
        BlockFinder.BlockLookup atMinus3 = (x, y, z) -> x == -3 ? 1 : 0;
        check("negative coordinates",
                hit(BlockFinder.raycast(-0.5, 0.5, 0.5, -1, 0, 0, 16, atMinus3)),
                "-3,0,0 place=-2,0,0");

        // Diagonal: the place cell must be face-adjacent to the hit, never diagonal.
        BlockFinder.RayHit d = BlockFinder.raycast(0.5, 0.5, 0.5, 1, 1, 0, 32, SLAB);
        int manhattan = Math.abs(d.x - d.placeX) + Math.abs(d.y - d.placeY) + Math.abs(d.z - d.placeZ);
        check("diagonal place cell is face-adjacent", manhattan, 1);

        // Standing inside a solid block targets it rather than running to max range.
        check("origin cell counts",
                hit(BlockFinder.raycast(5.5, 0.5, 0.5, 1, 0, 0, 16, SLAB)),
                "5,0,0 place=5,0,0");

        // Zero-length direction must not spin.
        check("zero direction", hit(BlockFinder.raycast(0.5, 0.5, 0.5, 0, 0, 0, 16, SLAB)), "null");

        // Every visited cell is a unit step from the last: no skipped voxels.
        BlockFinder.BlockLookup empty = (x, y, z) -> 0;
        final int[] last = {Integer.MIN_VALUE, 0, 0};
        final int[] visits = {0};
        BlockFinder.raycast(0.3, 0.7, 0.1, 0.37, 0.61, 0.7, 30, (x, y, z) -> {
            if (last[0] != Integer.MIN_VALUE) {
                int step = Math.abs(x - last[0]) + Math.abs(y - last[1]) + Math.abs(z - last[2]);
                if (step != 1) failures++;
            }
            last[0] = x; last[1] = y; last[2] = z;
            visits[0]++;
            return 0;
        });
        check("irrational ray steps one voxel at a time", failures, 0);
        System.out.println("  (visited " + visits[0] + " voxels over 30 blocks)");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
