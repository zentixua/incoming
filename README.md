# Airburst cloud audit: source and numeric checkpoint

**No current rendered reproduction or visual acceptance is claimed. No production patch is proposed at this checkpoint.**

Source commit: [476355384](https://github.com/zentixua/incoming/tree/476355384f944d094856348c31ea0dd6cab31aa9). The production JAR used by the standalone Java probe has SHA256 `03aaa3a9220fb292e7a7565852e233bdef477064ac63b1808dcf3a54dcfee854`. The artifact manifest compares selected model classes, MushroomField and volume includes against the simultaneous41c diagnostic build; these selected entries are identical. This is not whole-JAR equality. No JAR is distributed on this evidence branch.

## Source basis

[Glasstone and Dolan1977 ch2](https://atomicarchive.com/resources/documents/effects/glasstone-dolan/chapter2.html), sections2.06-2.11/2.17-2.19, describes cloud entrainment, column development and terrain-dependent surface debris. [NRC1985 p22](https://nap.nationalacademies.org/books/540/gif/22.gif) records detached dusty stems in approximately10kt balloon tests. [National Academies2025, section2.3.3/figure2-3](https://www.nationalacademies.org/read/27515/chapter/4) explicitly discusses a cap/stem air gap and condensed water within the darker stem. These sources support possible separation; they do not calibrate this implementation's exact timing, opacity or gap.

The local original source images were inspected. Complete copyrighted page images are not redistributed here. No single photograph is treated as a matched-age calibration.

## Native model data

[native-model.csv](numeric/native-model.csv), [full execution log](numeric/native-model.log), [probe](numeric/Air5ModelProbe.java). The probe invokes unmodified production model classes with Java21, one CPU/128MiB, without Minecraft or graphics. Execution completed successfully in0.49s. Reproduction: `java -XX:ActiveProcessorCount=1 -Xmx128m --class-path /path/to/pinned.jar numeric/Air5ModelProbe.java`.

Inputs:5/15kt, explicitAIR/GROUND, regular built-in AIR heights410.394/591.891m,20km visibility. Ages are model seconds, lengths metres above epicentre ground. Scale1 interpretation. No camera, weather shader or rendering is involved. The skirt is intentionally excluded from the probe; all reported cloud/stem fields are independent of that argument. Axis ground dust uses REAL-front age, as the current StemDust builder does; this is not a GAME0.25 pixel measurement.

|Yield/mode|Age s|Mean cap bottom m|Mean dusty stem top m|Raw cross-stem optical depth|
|---|---:|---:|---:|---:|
|5AIR|15|936.8|96.1|345.1|
|5AIR|30|1320.6|288.0|231.9|
|5AIR|60|1986.0|620.6|154.4|
|5AIR|120|2988.2|1121.7|102.3|
|15AIR|15|1422.7|85.3|325.5|
|15AIR|30|2014.2|381.0|227.8|
|15AIR|60|2998.3|873.1|157.8|
|15AIR|120|4365.7|1556.8|112.3|
|5GROUND|30|932.3|1118.7|281.0|
|15GROUND|30|1454.7|1745.6|261.1|

All72 rows are retained. Mean surfaces exclude billow displacement; optical depth is not screen opacity. The AIR15s stem tops lie below the reference axis ground-layer tops135.6/192.9m (5/15kt); early masking is plausible but has not yet been rendered in a controlled current scene.

## Interpretation and open checks

Current model stem matter comes solely from transported ground dust, while droplets/NO2 are assigned to cap torus+dome. The0.3 rise fraction, delayed inflow and immediate ground-touching wake filling are estimates. After roll-up, the native AIR geometry structurally preserves a gap; positive dust optical depth does not mean a tall connected visible column. A separate condensate/trailing-cloud contribution is absent, but its required optical importance in these conditions is unresolved.

An independent source review found two existing inconsistencies requiring separate discrimination: all-water source footprints still supply reference soil to StemDust; its arrival clock is physical while the supplying visible layer uses the configured game wave. Neither is established as the cause of the historical dry5/15kt observation. No AIR-only shader deletion was proven by source inspection. Current scene-depth, foreground extinction, shader state and actual frame visibility still require native capture.

## Prepared capture fixture (not executed)

The `prepared-fixture` directory contains a PRIVATE replacement for the existing devtest ArrivalScene, to be applied only in a disposable sandbox and restored afterward. It does not modify production classes/shaders. Mapping for this diagnostic only: `arrival 5 flash`=5ktAIR; `arrival 5`=5ktGROUND; likewise15. The normal carrier sequence is deliberately absent. Fixed seed1234, wind0, clear visibility20km, scale1, GAMEwave0.25; firesfalse is an explicit synthetic-source isolation. `initialAge=0` gives live time sequences, nonzero is an explicitly labelled age-seek control. `quality=OFF` also lowers mushroom sampling toLOW and therefore is not a pure foreground-only change.

Planned first series:5/15AIR/GROUND at equal age, same camera8km south/300mAGL looking2000mAGL, FOV70, same world/weather/output and verified shader state; capture0.1/0.5/1/3/5/10/15/30/60/120s plus raw WAV/log/metadata. Initial source-isolated scene does not certify full-pack world destruction. Pair15AIR pallOFF and actual-Iris AIR/GROUND only as discriminators. GPU capture awaits the shared runtime resource handoff.
