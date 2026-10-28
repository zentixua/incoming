import java.util.Locale;
import java.util.Scanner;
import ua.zentix.incoming.nuclear.model.ArrivalTable;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.FrontProfile;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;

/** Read-only source-file probe of the shipped jar's Minecraft-free model. No copied cloud-rise law. */
class PhaseProbe {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        double yield = Double.parseDouble(args[0]), hob = Double.parseDouble(args[1]);
        boolean surface = Boolean.parseBoolean(args[2]);
        double scale = Double.parseDouble(args[3]), wave = Double.parseDouble(args[4]);
        double wind = Double.parseDouble(args[5]), direction = Double.parseDouble(args[6]);
        double x = Double.parseDouble(args[7]), ground = Double.parseDouble(args[8]), z = Double.parseDouble(args[9]);
        double event = Double.parseDouble(args[10]);
        double radius = FireballModel.maxRadius(yield, surface) * scale;
        // Same front law as Detonation; fixed integration extent makes this an approximate arrival diagnostic.
        // The capture gate keeps a +/-1 s window and requires the actual visible wave in independent review.
        double edge = Math.hypot(HobBlast.rangeFor(0.3, hob, yield), hob) * scale;
        FrontProfile front = FrontProfile.of(ArrivalTable.of(yield, hob, 12000 / scale), scale,
                FrontProfile.slowFrom(radius), edge, 12000, wave * FrontProfile.SOUND_SPEED);
        System.out.println("tick,x,y,z,top_y,bottom_y,radius,ground_x,ground_y,ground_z,wave_s,pulse_s,rollup_s");
        try (Scanner input = new Scanner(System.in).useLocale(Locale.ROOT)) {
            while (input.hasNextDouble()) {
                double tick = input.nextDouble(), distance = input.nextDouble();
                double seconds = Math.max(1e-6, (tick - event) / (20 * scale));
                MushroomModel.Shape shape = MushroomModel.shape(seconds, yield, hob, surface, 0, null);
                if (shape == null) throw new IllegalArgumentException("No model cloud at " + seconds);
                double drift = wind * seconds * scale * MushroomModel.driftShare(shape.capY(), shape.endTop());
                System.out.printf("%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        tick, x + Math.cos(direction) * drift, ground + shape.capY() * scale,
                        z + Math.sin(direction) * drift, ground + shape.top() * scale,
                        ground + shape.capBottom() * scale, shape.capR() * scale, x, ground, z,
                        front.arrivalTicks(Math.hypot(distance, hob * scale)) / 20,
                        FireballModel.pulseSeconds(yield) * scale, MushroomModel.rollUpSeconds(yield, surface) * scale);
            }
        }
    }
}
