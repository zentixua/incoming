# Not physical

English · [Русский](ru/not-physical.md)

Incoming uses physical models for blast effects, with approximations and estimates. These game rules deliberately
depart from real weapons; selecting REAL does not remove all of them.

| Part | Game rule |
|---|---|
| Launch and flight | Crafted munitions, nearby launchers and configurable approach times serve the game. Flight, guidance, turns, climb rates and target tracking use a game model. GAME uses game speeds and heights; REAL changes speeds and heights, while maneuvers stay game values. The B-2's default release height is 170 blocks rather than about 12 km. See [flight](weapons.md#how-they-fly). |
| Salvos | Shaheds, missiles and Lancets take separate lanes beside one route so the salvo can be read as a wave. Their aim points keep the same accuracy. See [flight](weapons.md#how-they-fly). |
| Grad | At game speed it can fire much closer than the real minimum range. Its visible motor trail is extended to the first 650 blocks of flight; at full scale it starts at full launch speed and the trail still ends at 650 blocks. See [trails](weapons.md#flames-and-smoke-trails). |
| Air defense | Radar/engagement ranges, target signatures, guidance, the 40 g turn limit, two-stage motor behavior, 15 s flight limit and 6-block fuze are game values. REAL raises interceptor speed toward 1000 m/s; it does not turn the SAM into a real radar or guidance simulation. See [SAM](defense.md#surface-to-air-missile-system-sam). |
| ICBM | The visible ascent ends above the world; detonation uses a timer, 90 s by default rather than a real intercontinental flight of about 30 minutes. The strike has no random miss. See [launching a nuke](nuke.md#how-to-launch). |
| Nuclear scale | GAME and REAL keep full nuclear radii. CUSTOM can set `nuclear.effects_scale` from 0.005 to 1: below 1, damage and effect distances shrink. GAME slows the distant wave to 0.20 of the speed of sound; CUSTOM can set 0.15–1. These changes affect wave arrival and ruin timing, not just the picture. See [world settings](settings.md#world-settings). |
| Long-term effects | Fallout and injury progression use game clocks; one fallout hour is 1000 ticks (50 s at 20 TPS). See [survival and radiation](nuke.md#how-to-survive). |
| Fragment marks | A hit can turn an entire block into its cracked variant instead of leaving a small pit. Fragments currently pass through Create Aeronautics craft. See [blast limits](weapons.md#what-a-blast-does). |
| Cleanup | Spent boosters disappear after 5 s on the ground or 20 s after separation, whichever comes first, with a 2 s fade. Their physical cooling can last longer than the visible body. See [trails](weapons.md#flames-and-smoke-trails). |

Presentation changes how effects read on screen: exposure, glow, contrast, billows and camera motion. It does not
change a charge or a damage radius. Model approximations are different again: for example, simplified blast
shielding and estimated fuel/casing properties are limits of the calculation, not hidden game multipliers.
