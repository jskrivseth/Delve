import cydi.PerlinNoiseGenerator;
import cydi.World;

/** Prints a hash of generated terrain heights for a seed, to compare across JVM runs. */
public class SeedProbe {
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        World.reset(seed);
        long hash = 1469598103934665603L;
        for (int x = -64; x < 64; x++) {
            for (int z = -64; z < 64; z++) {
                int h = (int) World.getHeightAt(x, z);
                hash = (hash ^ h) * 1099511628211L;
            }
        }
        System.out.println("seed=" + seed + " heightHash=" + Long.toHexString(hash));
        System.out.println("sample=" + World.getHeightAt(0, 0) + "," + World.getHeightAt(37, 91)
                + "," + World.getHeightAt(-12, 55));
        World.shutdown();
        System.exit(0);
    }
}
