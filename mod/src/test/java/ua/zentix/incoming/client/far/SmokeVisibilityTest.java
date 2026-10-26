package ua.zentix.incoming.client.far;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeBounds;
import static org.junit.jupiter.api.Assertions.*;

class SmokeVisibilityTest {
    @Test
    void raidCloudThreeKilometresBehindTheEyeIsRejectedBeforeEmission() {
        SmokeVisibility.begin(new Matrix4f().perspective((float)Math.toRadians(90),1,.05f,128));
        for (var weapon : new ua.zentix.incoming.strike.WeaponType[]{ua.zentix.incoming.strike.WeaponType.MISSILE,
                ua.zentix.incoming.strike.WeaponType.DRONE, ua.zentix.incoming.strike.WeaponType.ROCKET,
                ua.zentix.incoming.strike.WeaponType.LOITER}) {
            var model = new ua.zentix.incoming.client.fx.smoke.CloudModel(ua.zentix.incoming.client.fx.BlastSource.of(weapon, false),
                    new ua.zentix.incoming.client.fx.smoke.CloudModel.Air(.01, 0, 3, .2, 3, .5));
            var b = new SmokeBounds(); model.bounds(300, -64, 320, b); b.move(0, 0, 3000);
            assertFalse(SmokeVisibility.visible(view(Sight.CLEAR), b), weapon + ": outer dust ring must not inflate every puff");
        }
    }

    private static FarView view(double range) {
        return new FarView(Vec3.ZERO, new Vector3f(0,0,-1), new Vector3f(-1,0,0), new Vector3f(0,1,0),
                0, 128, .001, 1, 1, 1, new Vector3f(1), range, 128, Double.NaN, 0, Sight.Air.at(0, range));
    }
    private static SmokeBounds box(double x, double y, double z, double r) {
        var b = new SmokeBounds(); b.box(x-r,y-r,z-r,x+r,y+r,z+r); return b;
    }

    @Test void frustumUsesCloudExtentAndIgnoresVanillaFarPlane() {
        var view = view(Sight.CLEAR);
        SmokeVisibility.begin(new Matrix4f().perspective((float)Math.toRadians(90),1,.05f,128));
        assertTrue(SmokeVisibility.visible(view, box(0,0,-3000,10)), "distant effects are folded in front of far plane");
        assertFalse(SmokeVisibility.visible(view, box(0,0,3000,10)), "whole cloud behind eye");
        assertFalse(SmokeVisibility.visible(view, box(200,0,-100,5)));
        assertTrue(SmokeVisibility.visible(view, box(130,0,-100,40)), "centre outside, cloud edge in frame");
        assertTrue(SmokeVisibility.visible(view, box(0,0,0,100)), "eye inside cloud");
        SmokeVisibility.begin(new Matrix4f().perspective((float)Math.toRadians(20),1,.05f,128).rotateY((float)Math.PI));
        assertTrue(SmokeVisibility.visible(view, box(0,0,3000,10)), "actual rotated camera matrix");
        assertFalse(SmokeVisibility.visible(view, box(1000,0,3000,10)), "zoom narrows frustum");
    }

    @Test void hazeUsesNearestAndHighestCloudExtent() {
        SmokeVisibility.begin(new Matrix4f().perspective((float)Math.toRadians(90),1,.05f,128));
        assertFalse(SmokeVisibility.visible(view(Sight.THUNDER), box(0,0,-20_000,100)));
        assertTrue(SmokeVisibility.visible(view(Sight.CLEAR), box(0,5000,-5000,1000)), "high cloud above haze remains visible");
    }
}
