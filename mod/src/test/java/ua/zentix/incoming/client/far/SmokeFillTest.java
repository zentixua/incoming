package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.PuffTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цена дыма в кадре (жалоба 23.10: в дыму налёта шахедов 8 к/с, кадр 62 мс, пики 279 мс, видеокарта 91 %, без шейдеров).
 * Налёт — 14 шахедов и 2 ракеты в квадрате 240 м вокруг глаза, возраст облаков 5–150 с, ветер 4 м/с; облака раскладывает
 * тот же приёмник слоя, что в игре ({@link SmokePuffs#sink}), квадраты пишет свой приёмник. Что видно сквозь дым —
 * пропускание по лучу взгляда, e^(−Στ), а не число слоёв: с бюджетом заливки ({@link SmokePuffs#FILL_BUDGET}) кадр
 * закрывает не больше бюджета экранов, а пропускание по экрану то же, что без бюджета.
 */
class SmokeFillTest {
    @org.junit.jupiter.api.BeforeAll
    static void optics() throws IOException {
        ClubFrames.install();
    }

    /**
     * Кадр 16:9, 70° по вертикали на 1080 строк: пиксель и полудиагональ — как у кадра игры ({@code FarRenderer.view}:
     * тангенс на пиксель, край — полудиагональ).
     */
    static final double H = Math.tan(Math.toRadians(35)), W = H * 16 / 9, PIXEL = 2 * H / 1080,
            EDGE = Math.atan(0.5 * PIXEL * Math.hypot(1920, 1080));

    /** Облако модели — источник, как событие {@code FarBlasts.Event}. */
    record Blast(CloudModel model, double x, double y, double z, double born, long seed) implements SmokeGrid.Source {
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            double t = seconds - born;
            if (t < 0 || t >= model.life) return false;
            double growth = puffGrowth(seconds);
            model.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double bx, double by, double bz, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {
                    out.blob(x + bx, y + by, z + bz, sh, sv, soot, dust, water, 0.6f, 0.55f, 0.45f, kelvin, rise);
                }

                @Override
                public void tube(double ax, double ay, double az, double sa, double massA, double bx, double by, double bz, double sb, double massB,
                                 double kelvin, double rise) {
                    out.tube(x + ax, y + ay, z + az, sa, sa, 0, massA, x + bx, y + by, z + bz, sb, sb, 0, massB, 0.6f, 0.55f, 0.45f, kelvin, rise,
                            sa / Math.max(growth, 1));
                }
            });
            return true;
        }

        @Override
        public double puffGrowth(double seconds) {
            double t = Math.max(0, seconds - born);
            return model.sigmaH(t) / Math.max(model.sigmaH(0), 1e-6);
        }

        @Override
        public long seed() {
            return seed;
        }

        @Override
        public boolean drawsGlow() {
            return true;
        }
    }

    /** Квадраты кадра на экране (тангенсы): середина, полуразмеры, непрозрачность, кадр клуба; вуали. */
    static final class Screen implements SmokePuffs.Out {
        final FarView view;
        final List<double[]> quads = new ArrayList<>();
        final java.util.Map<double[],Double> matterScale = new java.util.IdentityHashMap<>();
        final List<double[]> gaussians = new ArrayList<>();
        double veil;
        float[] veilField;
        double veilWidth, veilHeight;
        float lastFlatShade = -1;

        Screen(FarView view) {
            this.view = view;
        }

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                          float b, float a, double tau) {
            if (a < 0.002f || !(tau > 0)) return;
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = dx * f.x + dy * f.y + dz * f.z;
            if (depth <= 0) return;
            depth = Math.max(depth, 0.05);
            double[] q = PlumeContinuityTest.club((dx * l.x + dy * l.y + dz * l.z) / depth, (dx * up.x + dy * up.y + dz * up.z) / depth, 0,
                    halfA / depth, halfB / depth, sprite, mix, a, tau);
            quads.add(q);
            matterScale.put(q, depth * depth * depth / Math.sqrt(dx*dx+dy*dy+dz*dz));
        }

        @Override
        public boolean gaussian(FarView view, double x,double y,double z,double sh,double sv,double ext,
                                double across,double card,double power,double detail,FxAtlas.Sprite sprite,float mix,float shade,
                                float r,float g,float b,float coverage) {
            gaussians.add(new double[]{x,y,z,sh,sv,ext,coverage,card,power,ClubFrames.frame(sprite),mix,across,detail});
            if(SmokePuffs.lies(sh,sv,y,Math.sqrt(x*x+y*y+z*z))) lastFlatShade=shade;
            // Conservative raster rectangle, used only by the CPU fill planner.
            double radius=Math.max(sh,sv)*Math.sqrt(Math.max(0,2*Math.log(Math.max(ext/(2*Math.PI*sh*Math.min(sh,sv))/1e-7,1))));
            double depth=x*view.forward().x+y*view.forward().y+z*view.forward().z;
            if(depth<=radius) {
                quads.add(new double[]{0,0,0,W,H,0});
            } else {
                double u=x*view.left().x+y*view.left().y+z*view.left().z;
                double v=x*view.up().x+y*view.up().y+z*view.up().z;
                double den=depth*depth-radius*radius;
                double ex=radius*Math.sqrt(u*u+den),ey=radius*Math.sqrt(v*v+den);
                quads.add(new double[]{u*depth/den,v*depth/den,0,ex/den,ey/den,0});
            }
            return true;
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix, float shade,
                              float r, float g, float b, float a, double tau) {
            lastFlatShade = shade;
            // лежащий квадрат: по высоте экрана — проекция диска
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            puff6(dx, dy, dz, halfA, halfB * Math.abs(dy) / d, sprite, mix, shade, r, g, b, a, tau);
        }

        @Override
        public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a, int light) {
            veil += -Math.log(1 - Math.min(a, 0.999));
        }

        @Override
        public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a) {
            veil(dx, dy, dz, half, r, g, b, a, 0);
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            return false;
        }

        @Override
        public boolean veilField(FarView view, double width, double height, float[] rgba) {
            veilField=rgba.clone(); veilWidth=width; veilHeight=height;
            return true;
        }

        /** Сколько экранов закрывают квадраты (с перекрытием). */
        double fill() {
            double sum = 0;
            for (double[] q : quads) {
                double x0 = Math.max(-W, q[0] - q[3]), x1 = Math.min(W, q[0] + q[3]), y0 = Math.max(-H, q[1] - q[4]), y1 = Math.min(H, q[1] + q[4]);
                if (x1 > x0 && y1 > y0) sum += (x1 - x0) * (y1 - y0) / (4 * W * H);
            }
            return sum;
        }

        /** Пропускание в точке экрана (u, v): квадраты — по пикселям кадра клуба, как в шейдере ({@link ClubFrames}), вуали. */
        double transmittance(double u, double v) throws IOException {
            double clear = veilField == null ? Math.exp(-veil) : 1-VeilField.alpha(veilField,u,v,veilWidth,veilHeight);
            Vector3f f=view.forward(), l=view.left(), up=view.up();
            double rx=f.x+u*l.x+v*up.x,ry=f.y+u*l.y+v*up.y,rz=f.z+u*l.z+v*up.z;
            double n=Math.sqrt(rx*rx+ry*ry+rz*rz);
            for(double[] q:gaussians) {
                double sx=rx/n/q[3],sy=ry/n/q[4],sz=rz/n/q[3], speed=Math.sqrt(sx*sx+sy*sy+sz*sz);
                sx/=speed; sy/=speed; sz/=speed;
                double tx=-l.x/q[3],ty=-l.y/q[4],tz=-l.z/q[3], dot=tx*sx+ty*sy+tz*sz;
                tx-=dot*sx;ty-=dot*sy;tz-=dot*sz;
                double norm=Math.sqrt(tx*tx+ty*ty+tz*tz);tx/=norm;ty/=norm;tz/=norm;
                double ux=up.x/q[3],uy=up.y/q[4],uz=up.z/q[3];
                double a=ux*sx+uy*sy+uz*sz,b=ux*tx+uy*ty+uz*tz;
                ux-=a*sx+b*tx;uy-=a*sy+b*ty;uz-=a*sz+b*tz;
                norm=Math.sqrt(ux*ux+uy*uy+uz*uz);ux/=norm;uy/=norm;uz/=norm;
                double px=q[0]/q[3],py=q[1]/q[4],pz=q[2]/q[3];
                double bx=-(px*tx+py*ty+pz*tz)/q[7],by=(px*ux+py*uy+pz*uz)/q[7];
                double cdf=0.5*(1+SmokeGrid.erf((px*sx+py*sy+pz*sz)/Math.sqrt(2)));
                double ratio=q[11]/(q[3]*q[4]*speed);
                double alpha=ClubFrames.at((int)q[9],q[10],false,bx,by,1,1);
                double bakeTau=-Math.log(Math.max(1-alpha,1.0/255))*q[8]*ratio*cdf;
                double modelTau=SmokePuffs.sightDepth(q[0],q[1],q[2],q[3],q[4],q[5],rx/n,ry/n,rz/n);
                clear*=1-q[6]*((1-q[12])*(-Math.expm1(-modelTau))+q[12]*(-Math.expm1(-bakeTau)));
            }
            for (double[] q : quads) {
                if(q.length==6) continue;
                double x = (u - q[0]) / q[3], y = (v - q[1]) / q[4];
                if (Math.abs(x) < 1 && Math.abs(y) < 1) clear *= 1 - PlumeContinuityTest.clubAt(q, x, y);
            }
            return clear;
        }

        double reconstructed(double u, double v, int width, int height) throws IOException {
            double px=(u+W)/(2*W)*width-0.5, py=(v+H)/(2*H)*height-0.5;
            int ix=(int)Math.floor(px),iy=(int)Math.floor(py);
            double wx=px-ix,wy=py-iy,sum=0;
            for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
                int sx=Math.clamp(ix+x,0,width-1),sy=Math.clamp(iy+y,0,height-1);
                sum+=transmittance(-W+(sx+0.5)*2*W/width,-H+(sy+0.5)*2*H/height)
                        *(x==0?1-wx:wx)*(y==0?1-wy:wy);
            }
            return sum;
        }


    }

    static FarView view(Vec3 eye, Vector3f fwd) {
        fwd.normalize();
        Vector3f left = new Vector3f(0, 1, 0).cross(fwd, new Vector3f()).normalize(), up = fwd.cross(left, new Vector3f()).normalize();
        // воздух без дымки: сверяется рисовка вещества, а не дымка перед ним (её у «истины» нет)
        return new FarView(eye, fwd, left, up, 0, 1000, PIXEL, EDGE, 1, 1, new Vector3f(1, 1, 1), 32000, 192, 192, 0, new Sight.Air(0, 0, 0));
    }

    /** Налёт: 14 шахедов и 2 ракеты в квадрате 240 м, 40 % — в крыши на 10–40 м, возраст 5–150 с, ветер 4 м/с (класс D). */
    static List<Blast> raid(double now) {
        Random rnd = new Random(7);
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, 4, 1, 0.15, 3, 0.5);
        List<Blast> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            WeaponType w = i < 2 ? WeaponType.MISSILE : WeaponType.DRONE;
            double hob = rnd.nextDouble() < 0.4 ? 10 + 30 * rnd.nextDouble() : 0;
            CloudModel m = new CloudModel(BlastSource.of(w, false), air, false, hob, GroundDust.Ground.BARE);
            double x = (rnd.nextDouble() - 0.5) * 240, z = (rnd.nextDouble() - 0.5) * 240;
            out.add(new Blast(m, x, hob, z, now - 5 - 145 * rnd.nextDouble(), rnd.nextLong()));
        }
        return out;
    }

    /**
     * Пропускание самого вещества налёта в точке экрана (u, v) из глаза кадра {@code view}: e^(−Στ), τ — толща по лучу
     * через каждый гаусс облаков модели ({@link SmokePuffs#sightDepth}; ножка — гауссами трубки), без квадратов. С ним
     * сверяются оба кадра — полный и в бюджете.
     */
    static double truth(List<Blast> raid, double now, FarView view, double u, double v) {
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z, n = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double fx = rx / n, fy = ry / n, fz = rz / n, kd = SmokeGrid.dustK(0.6f, 0.55f, 0.45f);
        double[] clear = {1};
        Vec3 eye = view.camera();
        for (Blast b : raid) {
            double t = now - b.born();
            if (t < 0 || t >= b.model().life) continue;
            b.model().blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                // светящийся газ — тело огня, не дым (как у приёмника слоя)
                double smoke = 1 - CloudModel.glowShare(kelvin, view.skyLuminance());
                // Compare the same transport quantity as the volume and source renderer: delta-Eddington removes
                // the unresolved forward-scattering lobe. The unscaled coefficient would count that lobe as loss.
                double sootK = CloudModel.SOOT_K * (1 - CloudModel.SOOT_W * CloudModel.SOOT_G * CloudModel.SOOT_G);
                double ext = sootK * soot + kd * dust + SmokeGrid.DROP_K * water;
                if (ext > 0) clear[0] *= 1-smoke*(-Math.expm1(-SmokePuffs.sightDepth(
                        b.x()+x-eye.x,b.y()+y-eye.y,b.z()+z-eye.z,sh,sv,ext,fx,fy,fz)));
            });
        }
        return clear[0];
    }

    /** Кадр налёта из глаза eye в сторону fwd с бюджетом заливки budget. */
    static Screen frame(List<Blast> raid, double now, Vec3 eye, Vector3f fwd, double budget) {
        FarView v = view(eye, new Vector3f(fwd));
        Screen s = new Screen(v);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            // два кадра: доля кучек трубок — из прошлого кадра
            SmokePuffs.resetBudget();
            for (int frame = 0; frame < 2; frame++) {
                s.quads.clear();
                s.gaussians.clear();
                s.matterScale.clear();
                s.veil = 0;
                s.veilField = null;
                for (Blast b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
                SmokePuffs.flush();
            }
        } finally {
            SmokePuffs.budget = was;
            SmokePuffs.resetBudget();
        }
        return s;
    }

    /**
     * Глаз на крыше в дыму налёта и рядом с облаками, взгляд вдоль дыма: без бюджета кадр закрывает больше двух экранных бюджетов, с ним —
     * не больше четырёх настоящих экранов пикселей уменьшенной цели (включая мелкие клубы полного разрешения).
     * Видимая толща — модели: в каждой из 18 частей кадра пропускание и полного кадра, и кадра в бюджете — у пропускания
     * самого вещества модели ({@link #truth}: гауссы облаков без квадратов) не дальше {@link #MODEL_PART}, в среднем по
     * кадру — не дальше {@link #MODEL_MEAN}. До 3.6.1 части расходились до 0,4 (полный кадр) и 0,34 (в бюджете), всегда в
     * светлую сторону: квадрат клуба был «видимым краем» гаусса с линейной непрозрачностью (тонкий дым терял до 80 %
     * вещества), кучка — комьями со случайными просветами, квадрат в плоскости экрана у края кадра — меньше шара.
     */
    @Test
    void raidSmokeStaysInTheFillBudgetAtTheSameDensity() throws IOException {
        double now = 1000;
        List<Blast> raid = raid(now);
        Vector3f along = new Vector3f(1, 0, 0);
        for (Vec3 eye : new Vec3[]{new Vec3(0, 40, 0), new Vec3(raid.get(3).x(), 25, raid.get(3).z() + 20), new Vec3(raid.get(5).x() + 5, 60, raid.get(5).z())}) {
            Screen free = frame(raid, now, eye, along, Double.POSITIVE_INFINITY), budget = frame(raid, now, eye, along, SmokePuffs.FILL_BUDGET);
            double meanFree = 0, meanBudget = 0;
            int n = 0;
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    meanFree += free.transmittance(u, v);
                    meanBudget += budget.transmittance(u, v);
                    n++;
                }
            }
            meanFree /= n;
            meanBudget /= n;
            // подробность клубов мельче части кадра — её бюджет и отдаёт, плотность — по частям; оба кадра — против вещества
            // модели (без квадратов): бюджет не дальше от него, чем полный кадр, больше чем на шум подробности
            double floor = SmokePuffs.lastFloor();
            double[] real = new double[18];
            FarView v0 = view(eye, new Vector3f(along));
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    real[i / 8 * 3 + j / 9] += truth(raid, now, v0, u, v) / 72;
                }
            }
            double worstFree = worstBlock(free, real), worst = worstBlock(budget, real);
            String got = String.format(Locale.ROOT, "глаз %s: без бюджета %d квадратов, %.1f экранов, пропускание %.3f; с бюджетом %d, %.1f (пол %.1f), %.3f; худшая часть против вещества: полный %.3f, в бюджете %.3f",
                    eye, free.quads.size(), free.fill(), meanFree, budget.quads.size(), budget.fill(), floor, meanBudget, worstFree, worst);
            System.out.println("FILL " + got);
            assertTrue(free.fill() > 2 * SmokePuffs.FILL_BUDGET, got);
            float[] bounds = new float[4 * budget.quads.size()];
            for (int k = 0; k < budget.quads.size(); k++) {
                double[] q = budget.quads.get(k);
                bounds[4 * k] = (float) Math.clamp((q[0] - q[3] + W) / (2 * W), 0, 1);
                bounds[4 * k + 1] = (float) Math.clamp((q[1] - q[4] + H) / (2 * H), 0, 1);
                bounds[4 * k + 2] = (float) Math.clamp((q[0] + q[3] + W) / (2 * W), 0, 1);
                bounds[4 * k + 3] = (float) Math.clamp((q[1] + q[4] + H) / (2 * H), 0, 1);
            }
            int step=PuffTarget.plan(bounds,budget.quads.size(),1920,1080,SmokePuffs.FILL_BUDGET);
            double raster=PuffTarget.workPixels(bounds,budget.quads.size(),1920,1080,step)/(1920.0*1080);
            System.out.printf(Locale.ROOT, "RASTER step %d, %.3f native screens%n", step, raster);
            assertTrue(raster <= SmokePuffs.FILL_BUDGET, got);
            assertTrue(budget.quads.size() >= free.quads.size() / 2, "clumps survive instead of one club per Gaussian: " + got);
            double meanReal = 0;
            for (double r : real) meanReal += r / real.length;
            assertTrue(Math.abs(meanFree - meanReal) <= MODEL_MEAN && Math.abs(meanBudget - meanReal) <= MODEL_MEAN, got + "; модель в среднем " + meanReal);
            assertTrue(worstFree <= MODEL_PART && worst <= MODEL_PART, got);
            double[] reconstructed = new double[18];
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    reconstructed[i / 8 * 3 + j / 9] += budget.reconstructed(u, v, 1920 / step, 1080 / step) / 72;
                }
            }
            double lowMean = 0;
            for (int k = 0; k < 18; k++) {
                lowMean += reconstructed[k] / 18;
                assertTrue(Math.abs(reconstructed[k] - real[k]) <= MODEL_PART, "reconstructed target vs model, region " + k + ": " + got);
            }
            assertTrue(Math.abs(lowMean - meanReal) <= MODEL_MEAN, "reconstructed target vs mean model: " + got);
        }
    }

    @Test
    void aWorldTubeAndABlobCarryTheSameOpticalMass() throws IOException {
        double sigma=10, distance=200, length=0.01;
        double mass=2*Math.PI*sigma*sigma*0.1/SmokeGrid.SOOT_K;
        SmokeGrid.Source source=(seconds,sink)->true;
        FarView view=view(Vec3.ZERO,new Vector3f(0,0,1));
        double[] measured=new double[2];
        for(int mode=0;mode<2;mode++) {
            SmokePuffs.reset();
            Screen screen=new Screen(view);
            SmokeGrid.Sink sink=SmokePuffs.sink(view,screen,source,1000);
            if(mode==0) sink.blob(0,0,distance,sigma,sigma,mass,0,0,1,1,1,300,0);
            else sink.tube(0,0,distance-length/2,sigma,sigma,mass/length,0,
                    0,0,distance+length/2,sigma,sigma,mass/length,0,1,1,1,300,0,sigma/2);
            SmokePuffs.flush();
            // Integrate emitted matter on the atlas grid, independently of screen filtering and its nonlinear log.
            for(double[] g:screen.gaussians) for(int x=0;x<ClubFrames.N;x++) for(int y=0;y<ClubFrames.N;y++) {
                double alpha=ClubFrames.at((int)g[9],g[10],false,2*(x+0.5)/ClubFrames.N-1,2*(y+0.5)/ClubFrames.N-1,1,g[8]);
                measured[mode]+=-Math.log(Math.max(1-alpha,1e-30))*4*g[7]*g[7]*g[3]*g[11]/(ClubFrames.N*ClubFrames.N);
            }
            for(double[] q:screen.quads) if(q.length>6) for(int x=0;x<ClubFrames.N;x++) for(int y=0;y<ClubFrames.N;y++)
                measured[mode]+=-Math.log(1-PlumeContinuityTest.clubAt(q,-1+(x+0.5)*2/ClubFrames.N,-1+(y+0.5)*2/ClubFrames.N))
                        *4*q[3]*q[4]*screen.matterScale.get(q)/(ClubFrames.N*ClubFrames.N);
        }
        assertTrue(Math.abs(measured[0]-measured[1])<=0.01*measured[0],
                "the source adapter must use the same extinction, not the flight-trail coefficient: "+java.util.Arrays.toString(measured));
        SmokePuffs.reset();
    }

    @Test
    void aftermathFireMatchesTheSameRayIntegral() throws IOException {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD);
        ua.zentix.incoming.client.fx.FireSmoke.Fuel fuel = new ua.zentix.incoming.client.fx.FireSmoke.Fuel(m.poolWatts,
                Math.PI*m.poolRadius*m.poolRadius, m.source.fuel().sootYield/m.source.fuel().heatJPerKg);
        List<SmokeGrid.Source> sources = new ArrayList<>();
        Random random = new Random(7);
        for (int i=0;i<16;i++) {
            double angle=2*Math.PI*random.nextDouble(), radius=120*Math.sqrt(random.nextDouble());
            double x=radius*Math.cos(angle), z=radius*Math.sin(angle), age=55-i*3;
            sources.add((seconds,sink) -> {
                ua.zentix.incoming.client.fx.FireSmoke.lay(fuel,x,0,z,1,0,2,3,2*age,0.5,
                        distance -> distance<=2*age?1:0, distance -> 16,sink);
                return true;
            });
        }
        Vec3 eye=new Vec3(0,25,30);
        FarView view=view(eye,new Vector3f(0,-0.4f,-1));
        Screen screen=new Screen(view);
        SmokePuffs.resetBudget();
        for (int pass=0;pass<2;pass++) {
            screen.quads.clear(); screen.gaussians.clear(); screen.matterScale.clear(); screen.veil=0; screen.veilField=null;
            for (SmokeGrid.Source source:sources) source.emit(1000,SmokePuffs.sink(view,screen,source,1000));
            SmokePuffs.flush();
        }
        List<double[]> gaussians=new ArrayList<>();
        for (SmokeGrid.Source source:sources) source.emit(1000,(x,y,z,ax,ay,az,sh,sv,soot,dust,water,dr,dg,db,kelvin,rise) -> {
            double ext=SmokeGrid.SOOT_K*soot+SmokeGrid.dustK(dr,dg,db)*dust+SmokeGrid.DROP_K*water;
            for(int k=0;k<32;k++) {
                double f=2*(k+0.5)/32-1;
                gaussians.add(new double[]{x+f*ax-eye.x,y+f*ay-eye.y,z+f*az-eye.z,sh,sv,ext/32});
            }
        });
        double worst=0,mean=0;
        double[] regions=new double[18]; int[] samples=new int[18];
        for(int i=0;i<24;i++) for(int j=0;j<14;j++) {
            double u=-W+(i+0.5)*2*W/24,v=-H+(j+0.5)*2*H/14;
            Vector3f ray=new Vector3f(view.forward()).fma((float)u,view.left()).fma((float)v,view.up()).normalize();
            double tau=0;
            for(double[] g:gaussians) tau+=SmokePuffs.sightDepth(g[0],g[1],g[2],g[3],g[4],g[5],ray.x,ray.y,ray.z);
            double error=screen.transmittance(u,v)-Math.exp(-tau);
            mean+=error/(24*14); worst=Math.max(worst,Math.abs(error));
            int region=(i/4)*3+(j*3/14); regions[region]+=error; samples[region]++;
        }
        System.out.printf(Locale.ROOT,"FIRE optical mean %.5f worst pixel %.5f, quads %d%n",mean,worst,screen.quads.size());
        assertTrue(Math.abs(mean)<=MODEL_MEAN,"fire optical mean: "+mean);
        for(int k=0;k<18;k++) assertTrue(Math.abs(regions[k]/samples[k])<=MODEL_PART,"fire region "+k+": "+regions[k]/samples[k]);
        SmokePuffs.resetBudget();
    }

    @Test
    void nearFieldObliqueAndFlatCloudsKeepTheirBulkRayDensity() throws IOException {
        for (double sv : new double[]{1, 10, 30}) for (double side : new double[]{0, 18})
                for (double peak : new double[]{0.001, 0.01, 0.1, 1, 10}) {
            double sh=20, ext=peak*2*Math.PI*sh*sv;
            SmokeGrid.Source source=(seconds,sink) -> {
                sink.blob(side,0,0,sh,sv,ext/SmokeGrid.SOOT_K,0,0,0.6f,0.55f,0.45f,SmokeGrid.AIR_K,0);
                return true;
            };
            Vec3 eye=new Vec3(0,8,30);
            FarView view=view(eye,new Vector3f(0,0,-1));
            Screen screen=new Screen(view);
            SmokePuffs.resetBudget();
            source.emit(0,SmokePuffs.sink(view,screen,source,0));
            SmokePuffs.flush();
            // The accepted bake has real small-scale structure; compare the same bulk regions as the raid test.
            double mean=0;
            double[] regions=new double[18];
            for(int y=0;y<18;y++) for(int x=0;x<30;x++) {
                double u=-W+(x+0.5)*2*W/30,v=-H+(y+0.5)*2*H/18;
                Vector3f ray=new Vector3f(view.forward()).fma((float)u,view.left()).fma((float)v,view.up()).normalize();
                double truth=Math.exp(-SmokePuffs.sightDepth(side,-8,-30,sh,sv,ext,ray.x,ray.y,ray.z));
                double error=screen.transmittance(u,v)-truth;
                mean+=error/(30*18); regions[x/5*3+y/6]+=error/30;
            }
            String sample="sigma-v "+sv+", offset "+side+", peak "+peak;
            assertTrue(Math.abs(mean)<=MODEL_MEAN,"near-field mean "+mean+": "+sample);
            for(double error:regions) assertTrue(Math.abs(error)<=MODEL_PART,"near-field region "+error+": "+sample);

        }
        SmokePuffs.resetBudget();
    }

    @Test
    void anisotropicSegmentsKeepTheLineIntegral() throws IOException {
        for (boolean vertical : new boolean[]{false,true}) {
            double ax=vertical?0:20, ay=vertical?10:0, sh=20, sv=1, ext=2*Math.PI*sh*sv;
            SmokeGrid.Source source=(seconds,sink) -> {
                sink.segment(0,0,0,ax,ay,0,sh,sv,ext/SmokeGrid.SOOT_K,0,0,
                        0.6f,0.55f,0.45f,SmokeGrid.AIR_K,0);
                return true;
            };
            Vec3 eye=new Vec3(0,4,50);
            FarView view=view(eye,new Vector3f(0,0,-1));
            Screen screen=new Screen(view);
            SmokePuffs.resetBudget();
            source.emit(0,SmokePuffs.sink(view,screen,source,0));
            SmokePuffs.flush();
            for(int x=-4;x<=4;x++) for(int y=-3;y<=3;y++) {
                double u=x*10.0/50, v=(y-4.0)/50;
                Vector3f ray=new Vector3f(view.forward()).fma((float)u,view.left()).fma((float)v,view.up()).normalize();
                double tau=0;
                for(int j=0;j<256;j++) {
                    double t=2*(j+0.5)/256-1;
                    tau+=SmokePuffs.sightDepth(ax*t,ay*t-eye.y,-eye.z,sh,sv,ext/256,ray.x,ray.y,ray.z);
                }
                double represented=0;
                for(double[] g:screen.gaussians) represented+=SmokePuffs.sightDepth(g[0],g[1],g[2],g[3],g[4],g[5],ray.x,ray.y,ray.z);
                org.junit.jupiter.api.Assertions.assertEquals(Math.exp(-tau),Math.exp(-represented),0.01,
                        "segment quadrature is independent of the baked microstructure");
            }
        }
        SmokePuffs.resetBudget();
    }

    @Test
    void worldTubeUsesItsBirthSigmaToChooseTheBakedGrowthStage() {
        FarView view=view(Vec3.ZERO,new Vector3f(0,0,1));
        SmokeGrid.Source source=(seconds,sink)->true;
        double[] frames=new double[2];
        for(int i=0;i<2;i++) {
            double birth=i==0?10:1;
            Screen screen=new Screen(view);
            SmokePuffs.resetBudget();
            SmokePuffs.sink(view,screen,source,0).tube(0,0,99.99,10,10,1,0,
                    0,0,100.01,10,10,1,0,0.6f,0.55f,0.45f,SmokeGrid.AIR_K,0,birth);
            SmokePuffs.flush();
            double[] g=screen.gaussians.getFirst();
            frames[i]=g[9]+g[10];
            org.junit.jupiter.api.Assertions.assertEquals(Math.max(SmokePuffs.FIRST_FRAME,FxAtlas.puffFrame(Math.sqrt(g[3]*g[4])/birth)),frames[i],1e-6);
        }
        assertTrue(frames[1]>frames[0]+1,"a grown parcel must not reuse a source-wide fixed frame");
        SmokePuffs.resetBudget();
    }

    @Test
    void opaqueCloudChangesRepresentationWithoutAnOpticalDepthCliff() throws IOException {
        double peak=10000, sigma=1, ext=2*Math.PI*peak;
        FarView view=view(Vec3.ZERO,new Vector3f(0,0,1));
        double radius=Math.sqrt(2*Math.log(peak/-Math.log1p(-Sight.THRESHOLD)));
        Screen sample=isolatedCloud(view,2*radius,sigma,ext);
        double[] g=sample.gaussians.getFirst();
        double impact=0,modelAlpha=0,bakedAlpha=0,difference=0;
        for(int i=1;i<300;i++) {
            double local=i/200.0,b=g[7]*local;
            if(b>=radius || b/(Math.sqrt(4*radius*radius-b*b))>W*0.8) continue;
            double model=-Math.expm1(-peak*Math.exp(-b*b/2));
            double baked=ClubFrames.at((int)g[9],g[10],false,local,0,1,g[8]);
            if(Math.abs(model-baked)>difference) { difference=Math.abs(model-baked);impact=b;modelAlpha=model;bakedAlpha=baked; }
        }
        assertTrue(difference>0.1,"fixture needs a real baked edge distinct from the smooth medium");
        for(double weight:new double[]{0.9,0.99,0.999,0.9999,1}) {
            double lo=radius,hi=2*radius;
            for(int i=0;i<50;i++) {
                double mid=(lo+hi)/2;
                if(SmokePuffs.detailOutside(0,0,mid,sigma,sigma,peak)<weight) lo=mid;else hi=mid;
            }
            double distance=(lo+hi)/2,u=-impact/Math.sqrt(distance*distance-impact*impact);
            Screen actual=isolatedCloud(view,distance,sigma,ext);
            double blend=actual.gaussians.getFirst()[12];
            org.junit.jupiter.api.Assertions.assertEquals((1-blend)*modelAlpha+blend*bakedAlpha,
                    1-actual.transmittance(u,0),1e-6,
                    "LOD blends display coverage; a trace of a thick medium must not keep an opaque edge");
        }
        SmokePuffs.resetBudget();
    }

    private static Screen isolatedCloud(FarView view,double distance,double sigma,double ext) {
        SmokeGrid.Source source=(seconds,sink)->true;
        Screen screen=new Screen(view);
        SmokePuffs.resetBudget();
        SmokePuffs.sink(view,screen,source,0).blob(0,0,distance,sigma,sigma,ext/SmokeGrid.SOOT_K,0,0,
                0.6f,0.55f,0.45f,SmokeGrid.AIR_K,0);
        SmokePuffs.flush();
        return screen;
    }

    @Test
    void hotOpaqueSootFadesItsCoverageInsteadOfItsMass() throws IOException {
        for(double sky:new double[]{1,0.0008}) for(double wanted:new double[]{0.01,0.1,0.5}) {
            double lo=800,hi=5000;
            for(int i=0;i<60;i++) {
                double mid=(lo+hi)/2;
                if(1-CloudModel.glowShare(mid,sky)>wanted) lo=mid; else hi=mid;
            }
            double temperature=(lo+hi)/2;
            FarView day=view(Vec3.ZERO,new Vector3f(0,0,1));
            FarView v=new FarView(day.camera(),day.forward(),day.left(),day.up(),day.partial(),day.far(),day.pixel(),day.edge(),
                    (float)Math.pow(sky,1/Sight.ADAPT),day.exposure(),day.sky(),day.range(),day.world(),day.clouds(),day.rain(),day.air());
            double[] alpha=new double[2];
            for(int pass=0;pass<2;pass++) {
                double kelvin=pass==0?SmokeGrid.AIR_K:temperature;
                SmokeGrid.Source source=new SmokeGrid.Source() {
                    public boolean drawsGlow() { return true; }
                    public boolean emit(double seconds,SmokeGrid.Sink sink) {
                        sink.blob(0,0,200,5,5,10000*2*Math.PI*25/SmokeGrid.SOOT_K,0,0,
                                0.6f,0.55f,0.45f,kelvin,0);
                        return true;
                    }
                };
                Screen screen=new Screen(v);
                SmokePuffs.resetBudget();
                source.emit(0,SmokePuffs.sink(v,screen,source,0)); SmokePuffs.flush();
                alpha[pass]=1-screen.transmittance(0,0);
            }
            double coverage=1-CloudModel.glowShare(temperature,v.skyLuminance());
            org.junit.jupiter.api.Assertions.assertEquals(coverage*alpha[0],alpha[1],1e-6,
                    "even ten thousand optical depths must remain 1% visible at 1% smoke coverage");
        }
        SmokePuffs.resetBudget();
    }

    @Test
    void hotAndColdMatterKeepTheirDistinctDisplayCoverageWhenOverlapping() throws IOException {
        double lo=800,hi=5000;
        for(int i=0;i<60;i++) {
            double mid=(lo+hi)/2;
            if(1-CloudModel.glowShare(mid,1)>0.01) lo=mid; else hi=mid;
        }
        double hot=(lo+hi)/2;
        FarView view=view(Vec3.ZERO,new Vector3f(0,0,1));
        double[] clear=new double[3];
        for(int pass=0;pass<3;pass++) {
            int mask=pass+1;
            SmokeGrid.Source source=new SmokeGrid.Source() {
                public boolean drawsGlow() { return true; }
                public boolean emit(double seconds,SmokeGrid.Sink sink) {
                    if((mask&1)!=0) sink.blob(0,0,200,5,5,0.01*2*Math.PI*25/SmokeGrid.SOOT_K,0,0,
                            0.6f,0.55f,0.45f,SmokeGrid.AIR_K,0);
                    if((mask&2)!=0) sink.blob(0,0,200,5,5,10000*2*Math.PI*25/SmokeGrid.SOOT_K,0,0,
                            0.6f,0.55f,0.45f,hot,0);
                    return true;
                }
            };
            Screen screen=new Screen(view);
            SmokePuffs.resetBudget();
            source.emit(0,SmokePuffs.sink(view,screen,source,0));SmokePuffs.flush();
            clear[pass]=screen.transmittance(0,0);
        }
        org.junit.jupiter.api.Assertions.assertEquals(clear[0]*clear[1],clear[2],1e-6,
                "coincident hot and cold Gaussians cannot be merged into one mass-scaled opacity");
        SmokePuffs.resetBudget();
    }

    @Test
    void horizontalDustRetainsItsMaterialLightingFromAboveAndBelow() {
        SmokeGrid.Source dust = (seconds, sink) -> {
            sink.blob(0, 0, 0, 20, 1, 0, 1000, 0, 0.6f, 0.55f, 0.45f, SmokeGrid.AIR_K, 0);
            return true;
        };
        float previous = -1;
        for (int side : new int[]{1, -1}) {
            Vec3 eye = new Vec3(0, side * 400, 400);
            FarView view = view(eye, new Vector3f(0, -side, -1));
            Screen screen = new Screen(view);
            SmokePuffs.resetBudget();
            dust.emit(0, SmokePuffs.sink(view, screen, dust, 0));
            SmokePuffs.flush();
            assertTrue(screen.lastFlatShade > 0.1, "horizontal cards must retain baked relief, including the upper side");
            if (previous > 0) org.junit.jupiter.api.Assertions.assertEquals(previous, screen.lastFlatShade, 1e-6,
                    "card orientation changes the light direction, not the material extinction");
            previous = screen.lastFlatShade;
        }
        SmokePuffs.resetBudget();
    }

    /**
     * Части кадра против вещества модели — не дальше этого: подробность клубов (раскладка кучки, кадр и отражение по зерну)
     * сама двигает пропускание частей до {@link #DETAIL} ({@link #clusterDetailNoise}), остальное — форма кадра клуба
     * против гаусса.
     */
    static final double MODEL_PART = 0.15, MODEL_MEAN = 0.03, DETAIL = 0.05;

    /**
     * Подробность кучек — по зерну облака (поворот раскладки, кадр, отражение и размер клубов): смена зерна меняет
     * пропускание по частям кадра не больше чем на {@link #DETAIL} — это клубы, а не плотность (на весь кадр вещество то же).
     */
    @Test
    void clusterDetailNoise() throws IOException {
        double now = 1000;
        List<Blast> raid = raid(now), other = new ArrayList<>();
        for (Blast b : raid) other.add(new Blast(b.model(), b.x(), b.y(), b.z(), b.born(), b.seed() * 31 + 7));
        Screen a = frame(raid, now, new Vec3(0, 40, 0), new Vector3f(1, 0, 0), Double.POSITIVE_INFINITY);
        Screen b = frame(other, now, new Vec3(0, 40, 0), new Vector3f(1, 0, 0), Double.POSITIVE_INFINITY);
        double worst = worstBlock(a, b);
        assertTrue(worst <= DETAIL, "зерно: худшая часть кадра " + worst);
    }

    /** Наибольшая разница среднего пропускания двух кадров по 18 частям (8 × 9 узлов сетки 48×27). */
    static double worstBlock(Screen a, Screen b) throws IOException {
        return worstBlock(a, blocks(b));
    }

    /** То же против средних по частям {@code ref}. */
    static double worstBlock(Screen a, double[] ref) throws IOException {
        double[] ba = blocks(a);
        double worst = 0;
        for (int k = 0; k < 18; k++) worst = Math.max(worst, Math.abs(ba[k] - ref[k]));
        return worst;
    }

    /** Среднее пропускание кадра по 18 частям. */
    static double[] blocks(Screen a) throws IOException {
        double[] ba = new double[18];
        for (int i = 0; i < 48; i++) {
            for (int j = 0; j < 27; j++) {
                double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                ba[i / 8 * 3 + j / 9] += a.transmittance(u, v) / 72;
            }
        }
        return ba;
    }

    /** Слияние двух равных гауссов на расстоянии σ держит массу, середину и вторые моменты; дальний остаётся отдельно. */
    @Test
    void mergeKeepsMassAndMoments() {
        int f = SmokePuffs.FIELDS;
        double[] held = new double[3 * f];
        double[][] blobs = {{0, 10, 0, 4, 4, 1, 2, 0}, {4, 10, 0, 4, 4, 1, 2, 0}, {40, 10, 0, 4, 4, 1, 2, 0}};
        for (int i = 0; i < 3; i++) {
            System.arraycopy(blobs[i], 0, held, i * f, 8);
            held[i * f + 8] = 0.6;
            held[i * f + 9] = 0.55;
            held[i * f + 10] = 0.45;
        }
        boolean[] gone = new boolean[3];
        SmokePuffs.merge(held, 0, 3, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
        assertTrue(!gone[0] && gone[1] && !gone[2], "слит только близкий");
        assertTrue(Math.abs(held[0] - 2) < 1e-9 && Math.abs(held[5] - 2) < 1e-9 && Math.abs(held[6] - 4) < 1e-9, "середина и массы");
        assertTrue(Math.abs(held[3] - Math.sqrt(16 + 0.5 * 4)) < 1e-9, "σh² = σh² + половина разноса²: " + held[3]);
        assertTrue(Math.abs(held[4] - 4) < 1e-9, "σv та же");
    }
}
