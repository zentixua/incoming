# Front passage +20..+28 s (run-2): render-thread sample split for thread A

Source: hitch probe 5 ms render-thread samples (`hitch-samples.txt`), window +20..+28 s after the client received the detonation; 1374 samples. A sample counts for a class when any frame of that class is on its stack. Leaf = the next frame toward the leaf below the class's leaf-most frame. Scripts: `split.py`, `chain.py` (lead scratch).

```
window +20..+28 s, render-thread 5 ms samples: 1374

== Fragments: 71 samples (5.2 %)
  by own method -> next frame toward the leaf:
      36  client.fx.Fragments$Fragment.tick  <- leaf-side: net.minecraft.client.particle.Particle.tick
       9  client.fx.Fragments$Fragment.<init>  <- leaf-side: net.minecraft.client.color.block.BlockColors.getColor
       7  client.fx.Fragments$Fragment.tick  <- leaf-side: net.minecraft.world.level.CollisionGetter.noCollision
       5  client.fx.Fragments$Fragment.tick  <- leaf-side: ua.zentix.incoming.client.nuclear.BlastGust.at
       4  client.fx.Fragments$Fragment.getLightColor  <- leaf-side: net.minecraft.client.renderer.LevelRenderer.getLightColor
       3  client.fx.Fragments$Fragment.<init>  <- leaf-side: 
       2  client.fx.Fragments$Fragment.vertex  <- leaf-side: com.mojang.blaze3d.vertex.VertexConsumer.setColor
       1  client.fx.Fragments.torn  <- leaf-side: 
       1  client.fx.Fragments.tick  <- leaf-side: ua.zentix.incoming.client.fx.FarthestPool.purge
       1  client.fx.Fragments.torn  <- leaf-side: ua.zentix.incoming.client.fx.FarthestPool.admit
       1  client.fx.Fragments.drag  <- leaf-side: 
       1  client.fx.Fragments$Fragment.<init>  <- leaf-side: net.minecraft.client.renderer.block.BlockModelShaper.getTexture
  callers (frame above the outermost):
      48  net.minecraft.client.particle.ParticleEngine.mixinextras$bridge$tick$82
      15  client.nuclear.NukeDust.debris
       6  net.minecraft.client.particle.ParticleEngine.render
       1  client.IncomingClient.tick
       1  client.nuclear.NukeDust.front

== SoundFilters: 69 samples (5.0 %)
  by own method -> next frame toward the leaf:
      33  client.sound.SoundFilters.open  <- leaf-side: net.minecraft.world.level.BlockGetter.clip
      26  client.sound.SoundFilters$ThroughLeaves.getBlockShape  <- leaf-side: net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.is
       3  client.sound.SoundFilters$ThroughLeaves.getBlockShape  <- leaf-side: net.minecraft.world.level.ClipContext.getBlockShape
       2  client.sound.SoundFilters.loops  <- leaf-side: team.creative.ambientsounds.sound.AmbientSound$SoundStream.isLooping
       2  client.sound.SoundFilters.loops  <- leaf-side: 
       1  client.sound.SoundFilters.update  <- leaf-side: ua.zentix.incoming.client.nuclear.Deafness.highs
       1  client.sound.SoundFilters.update  <- leaf-side: net.minecraft.client.sounds.ChannelAccess$ChannelHandle.execute
       1  client.sound.SoundFilters.loops  <- leaf-side: java.lang.invoke.MethodHandleNatives.linkCallSite
  callers (frame above the outermost):
      61  client.sound.Occlusion.open
       5  client.nuclear.ClientNuclear.tick
       1  client.sound.AmbientLoop.tick
       1  client.sound.EngineSound.tick
       1  client.sound.ClientSounds.shot

== FireRoar: 29 samples (2.1 %)
  by own method -> next frame toward the leaf:
      29  client.sound.FireRoar.add  <- leaf-side: java.lang.Math.atan2
  callers (frame above the outermost):
      29  client.nuclear.NukeAfter.storm

== FxParticle: 35 samples (2.5 %)
  by own method -> next frame toward the leaf:
      23  client.fx.particle.FxParticle.move  <- leaf-side: net.minecraft.world.entity.Entity.collideBoundingBox
       8  client.fx.particle.FxParticle.light  <- leaf-side: net.minecraft.client.renderer.LevelRenderer.getLightColor
       2  client.fx.particle.FxParticle.light  <- leaf-side: 
       1  client.fx.particle.FxParticle.sprite  <- leaf-side: java.lang.ClassLoader.loadClass
       1  client.fx.particle.FxParticle.move  <- leaf-side: 
  callers (frame above the outermost):
      34  client.fx.particle.FxPool.tick
       1  client.fx.particle.FxPool.collect

== Occlusion.open callers (4 frames up)
    61  client.sound.Occlusion.open <- client.sound.EngineSound.tick <- net.minecraft.client.sounds.SoundEngine.tickNonPaused <- net.minecraft.client.sounds.SoundEngine.tick

== Fragment.tick inner (4 frames toward leaf)
    30  net.minecraft.client.particle.Particle.tick > net.minecraft.client.particle.Particle.move > net.minecraft.client.particle.Particle.wrapMethod$hml000$sable$moveWithSubLevels > net.minecraft.client.particle.Particle.mixinextras$bridge$move$mixinextras$wrapped$86$87
     5  net.minecraft.client.particle.Particle.tick > net.minecraft.client.particle.Particle.move > net.minecraft.client.particle.Particle.wrapMethod$hml000$sable$moveWithSubLevels > it.unimi.dsi.fastutil.objects.ObjectOpenHashSet.<init>
     3  net.minecraft.world.level.CollisionGetter.noCollision > net.minecraft.world.level.Level.noCollision > net.caffeinemc.mods.lithium.common.entity.LithiumEntityCollisions.doesBoxCollideWithHardEntities > com.google.common.collect.AbstractIterator.hasNext
     3  client.nuclear.BlastGust.at > client.nuclear.BlastGust.lambda$at$0 > client.nuclear.ClientNuclear.ticksOf > client.nuclear.ClientNuclear$Active.ticks
     2  client.nuclear.BlastGust.at > client.nuclear.BlastGust.lambda$at$0 > client.nuclear.ClientNuclear.ticksOf > java.util.LinkedHashMap.get
     2  net.minecraft.world.level.CollisionGetter.noCollision > net.minecraft.world.level.Level.noCollision > net.caffeinemc.mods.lithium.common.entity.LithiumEntityCollisions.doesBoxCollideWithBlocks > net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper.<init>
     1  net.minecraft.world.level.CollisionGetter.noCollision
     1  net.minecraft.world.level.CollisionGetter.noCollision > net.minecraft.world.level.Level.noCollision > net.caffeinemc.mods.lithium.common.entity.LithiumEntityCollisions.doesBoxCollideWithBlocks > net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper.computeNext
     1  net.minecraft.client.particle.Particle.tick > net.minecraft.client.particle.Particle.move

== FireRoar.add chain
    29  client.sound.FireRoar.add <- client.nuclear.NukeAfter.storm <- client.nuclear.NukeAfter.measure
```

Answers:
1. Fragments (71 samples, 5.2 %): `Fragment.tick` → `super.tick` → `Particle.move` (Sable's `moveWithSubLevels` wrapper, then vanilla collision) 36–39; `noCollision` (Lithium hard-entity and block collision) 7; `BlastGust.at` 5 (its `ClientNuclear.ticksOf` map lookup per fragment); `<init>` 12 (BlockColors.getColor, texture lookup); `getLightColor` 4; `torn`/`FarthestPool.admit/purge` 3. Callers: ParticleEngine tick 48, `NukeDust.debris` 15 (construction), render 6.
2. SoundFilters (69, 5.0 %): all 61 ray samples come from `Occlusion.open` ← `EngineSound.tick` ← `SoundEngine.tickNonPaused`: per playing sound per tick, `BlockGetter.clip` with `ThroughLeaves.getBlockShape` (`BlockState.is` tag test) 26–29. Not ClientSounds.shot / NukeSounds clatter / BlastHit (1 sample from `ClientSounds.shot`). Rays per tick are not logged by the probe.
3. FireRoar (29, 2.1 %): only `add`, leaf `Math.atan2`, caller `NukeAfter.storm` ← `NukeAfter.measure` (storm cells), none under `finish`/`Propagation.level`, none from FireWatch.
4. FxParticle (35, 2.5 %): `move` 24 (`Entity.collideBoundingBox`), `light` 10 (`LevelRenderer.getLightColor`), from `FxPool.tick`. FxPool's own count is not in the probe's state lines; in the window `fragments` = 1200 (cap) in 44 state lines, vanilla `particles` 106–1386.
