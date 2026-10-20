# Shared change: the blast packet carries the weapon

Sessions 2 (explosion picture) and 4 (blast sound) both need to know, on the client, which weapon exploded: its charge
(kg TNT, `WeaponSpec.charge`) and the fuel left at impact. Today `S2C.Blast` carries only `kind` (0 drone, 1 missile,
2 bunker, 3 rocket); the Lancet sends `DRONE` and therefore looks and sounds like a Shahed.

## The change (make it exactly like this, so two sessions produce the same diff)

1. `net/S2C.Blast` gets one more field right after `kind`: `int weapon` — `WeaponType.id` of the weapon that exploded
   (`WeaponType.values()[id]` on the client, as other packets already do), encoded with `ByteBufCodecs.VAR_INT`.
   `kind` stays: it still says how the blast happens (surface, underground).
2. Every place that builds `S2C.Blast` on the server passes the weapon it already knows.
3. `WeaponSpec` gets one more component after `charge`: `double fuelAtImpact` — kilograms of fuel left at impact, a
   **marked estimate** with its source in the Javadoc (`ref/passport.md`: Shahed 10–50 kg of petrol depending on the
   route, take the middle and say so; Tomahawk "hundreds of kg" of JP-10, ~200–350 kg by the fireball estimate there;
   Lancet 0 (battery); Grad 0 (the motor burns out within a second or so of launch); bomb 0; nuke 0). It is data, not flight logic: nothing in flight reads it.
4. `IncomingNetwork.PROTOCOL` goes up by one.
5. A unit test checks the round trip of the codec and that every conventional `WeaponType` has a charge and a fuel
   value with a source.

## Who does it

Whichever of sessions 2 and 4 gets there first. Before starting, `git fetch origin main` and look: if `main` already
has `weapon` in `S2C.Blast`, use it and do not touch the packet. If both sessions add it in parallel, the one that
merges later keeps `main`'s version when resolving and bumps nothing twice.

## What the client derives from it (one place)

A small pure class (for example `client/fx/BlastSource`, unit-tested, no Minecraft) turns the packet into what the
picture and the sound need: charge in kg TNT, fuel kg and its kind (petrol, JP-10, none), explosive composition
(TNT-like sooty or RDX/HMX-based grey, from the passport). Both sessions read this, nothing else reads `kind` for size.
