from pathlib import Path
for label in ('main','head'):
 root=Path('/mnt/data/projects/incoming/mod/run/wt-MT-B5-media-'+label)
 p=root/'mod/src/devtest/java/ua/zentix/incoming/scenario/ArrivalScene.java'
 s=p.read_text();a=s.index('    static void start(');b=s.index('\n    private static Detonation geometry',a)
 s=s[:a]+'''    static void start(Vec3 at, int count, boolean burst) {
        reset();
        var mc = Minecraft.getInstance();
        double kt = count == 1 ? 15 : count == 2 ? 100 : 1000;
        long when = mc.level.getGameTime() - 6000;
        var d = new Detonation(9900, at, at.y, kt, true, when, 0, 3, 20000,
                1234, 1, false, .25f, mc.level.getDayTime() - 6000, true);
        ua.zentix.incoming.client.nuclear.ClientNuclear.detonation(new S2C.NukeDetonation(d, 0), false);
    }
''' +s[b:];p.write_text(s)
