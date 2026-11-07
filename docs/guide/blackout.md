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
- A nuclear strike cuts power without a substation over its whole damage reach, as far as it breaks glass or burns
  skin (at most 16384 blocks). Both `grid.enabled` and `grid.nuke_blackout` must be on. Hit substations can also
  cause outages in their own districts.
- After a nuclear strike, power comes back by what the blast did to each district, on the game clock (an hour is
  50 seconds, a Minecraft day is 24 hours):
  - where nothing broke (below 1 psi), the grid only tripped: power returns within 2 to 24 hours, the far edge first;
  - where lines are down (1 to 5 psi), crews rebuild them: 2 to 14 days, longest next to the destroyed zone;
  - where the grid is destroyed (5 psi and more), power never comes back by itself. Only fires, torches and the lamps
    you place yourself give light there. For a 250 kt ground burst that is about 3 km around ground zero, with
    lines down to about 9 km.
- A lamp you place in a dark district shines: Minecraft lamps have no wire to the grid, so it runs on your own supply.
  An outage darkens only the lamps that stood there before it. Break a dark lamp and place it again to light it.
- `/incoming grid restore` brings power back at once everywhere, the destroyed zone included.
- The world is saved with ordinary lamps and opens without the mod. Only Create contraptions assembled in a dark
  district are better disassembled before you remove the mod.
