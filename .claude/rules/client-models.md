---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/render/**"
  - "tools/gen_models.py"
  - "mod/src/main/resources/assets/incoming/models/**"
---

# Projectile and launcher models

## Pitfalls
- Projectile models are OBJ (`neoforge:obj` loader), additional models (`ModelEvent.RegisterAdditional`), with textures
  in `textures/block/weapon`, hence in the block atlas; they use the entity rendering path, so world light and Iris
  shaders (and their shadows) work as for entities. OBJ faces and block-based launcher models (`PartModel`: one entity
  layer per block layer, with block tint, like `renderSingleBlock`) are parsed once into `QuadMesh` (again after model
  reload); each frame only writes vertices. Custom layer buffers (`RegisterRenderBuffersEvent`) do nothing on the host:
  with Iris (even without shaders), entity buffers are its `FullyBufferedMultiBufferSource`, which accumulates all frame
  layers itself and ignores `endBatch(layer)`. Do not upload custom VBOs for entities: Iris appends its own attributes
  to entity vertices and also draws them in the shadow pass. Glowing parts use the `glow` material (`Ka 1 1 1`, baked
  light). Moving-part pivots use the same numbers in `gen_models.py` and `WeaponModels`. The `models` scenario places
  client projectile copies in the required phase (via reflection), captures them from three sides, then each launcher
  with a full rack and a villager beside it (scale); particles are suppressed. At distance, the same model is drawn into
  the `FarModels` atlas with its own shader in `AFTER_LEVEL` (Iris replaces shaders only while rendering the world; it
  has no shaderpack shadow); animations share one code path (`WeaponModels.Look` passes parts to `Parts`: nearby as
  vertices into the layer buffer, far away as a part and its matrices). Distant part meshes live in VRAM (`VertexBuffer`,
  like vanilla sky and chunks: this is not the entity path), uploaded on world entry (`FarRenderer` warmup); each frame
  submits part matrices and a draw call (CPU vertices cost up to 1 ms for a full B-2 in frame on the laptop). Add new
  parts to `FarModelsTest` (the entire model fits in the tile sphere `TILE_RADIUS × FarLook.size`). The `far-models`
  scenario captures “entity / packet path” pairs in the same pose and rows of all projectiles at 300–3000 blocks.
- Models are life-size (block = meter); `gen_models.py` builds some models in its own units and scales them down on
  output (`Model(scale=…)`). When resizing, update together: pivots in `WeaponModels`, nozzles, exhaust flames and the
  dropped booster in `ClientWeaponSpec`, `noseLength` in `WeaponSpec`, hitbox size in `ModEntities`, slots in
  `LauncherRack.slotOffset`; for the SAM rack, numbers in `SamBlockEntity` (render bounds and missile position in the tube).
