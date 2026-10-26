# Blackout

English · [Русский](ru/blackout.md)

A Transformer Substation powers an area with a radius of 512 blocks. Knock it out with a blast and the electric lights
of the area go out district by district. Power starts coming back after 15 minutes and returns district by district over the next 90 seconds by default. While a substation is knocked out,
its transformer oil burns: a column of thick black smoke, flames about 4.5 blocks high that light the area at night, and
the crackle of the broken arc. A working substation hums.

![Transformer Substation recipe](img/recipe-substation.png)

Ingredients: 3 lightning rods, 2 copper ingots, 1 redstone block and 3 iron ingots. Makes 1 substation.

- Lights on the grid go out: glowstone, lanterns, sea lanterns, redstone lamps, copper bulbs, shroomlights, froglights,
  end rods. Torches, candles and campfires keep burning.
- A nuclear strike cuts power without a substation, up to its damage radius or `grid.max_radius`, whichever is
  smaller (4096 blocks by default). Both `grid.enabled` and `grid.nuke_blackout` must be on. Hit substations can also
  cause outages in their own districts.
- The world is saved with ordinary lamps and opens without the mod. Only Create contraptions assembled in a dark
  district are better disassembled before you remove the mod.
