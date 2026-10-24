# PR 428, Gaussian smoke opacity and bounded raster cost

Code c2b5df760 (functional source 6be6e321), based on main df4ee0d6. Reference 3c915c93 is main dc36fcba with only the same wind/presentation-clock fixtures. The intervening main change concerns nuclear exposure and does not change these ordinary scenes.

Raw PNGs/WAVs are unchanged. JPEG sheets are viewing aids. Main/revised share camera, game time (6000 or night), sun, source seed/effect and wind/presentation clock. The inherited review-1 raid did not pin its wind clock; its middle sheet row is a secondary reference.

Raid 20/40/55 s, drone 3/10/30 s, fire 10/20 s, and early missile 0.05/0.1/0.2/0.5/1/2 s (day/night). The high-resolution raid uses a 2560x1600 virtual window. Actual framebuffers are 1280x692 and 2560x1572; see metrics.json.

Reported power profile is performance and firmware profile balanced at the recorded checkpoints; SM did not change either. No shaderpack; production Iris comparison is the separate shared look gate.

GPU means cover the preceding second of the FX timer: heat, source probes, sprite draw, linear-colour stage copies and reconstruction. Existing scene-depth/model work precedes that timer in both versions. Raster counters bound expensive sprite fragments; composites and full-frame colour copies are additional work included in GPU time. Main/review-1 legacy estimated fill and new exact projected raster bounds are different counters.

The four-screen planner uses clipped integer raster bounds, and counts each nonoverlapping source mesh once. Individually faint source meshes approximate sampling and inter-source ordering. CPU frame tests include mesh triangles and actual raster bounds. Tests retain mean/region limits 0.03/0.15 and seed limit 0.05. World Gaussian opacity is the finite eye-to-scene ray integral; the photo supplies only lighting. Accepted PuffOptics calibration is unchanged for photos and legacy flat sprites.

The live diagnostic probe is optional and never part of the PR diff. Its model rays extend to infinity, while the target stops at scene depth; compare sky rays only. The target includes all sprite particles, so opaque fragments can also lower its T. Example development sky ray: model 0.0807, GPU 0.0776 after direct ray opacity (before, model about 0.08 versus GPU about 0.76).

Sandbox screenshots are written asynchronously. helpers/wait-png.py verifies completed PNGs before stopping the client; an earlier rejected capture was zero bytes after an immediate stop. Launch scripts are unchanged.
