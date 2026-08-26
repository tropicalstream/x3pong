# x3pong — Pong for the RayNeo X3 Pro

Free software, licensed under the GNU GPL v3 (see LICENSE). Derived from
`x3breakout`, which is GPL v3, so it stays GPL v3.

Two games in one, chosen on the opening screen.

## Controls

| gesture | menu | in play |
|---|---|---|
| **drag** | — | **moves your paddle** — your finger *is* the paddle |
| swipe | move between rows | — |
| tap | change the value / START | — |
| double tap | — | abandon the match, back to the menu |

**Dragging, not flicking.** Every other game on this chassis quantises the pad
into 90 px swipe steps. Pong cannot: a paddle that jumps a fixed distance per
flick can never be put where the ball is going. The paddle reads
`SwipeControl.pos01` — the absolute 0–1 position of your finger along a sweep
the chassis has already calibrated — and follows it directly.

## The two modes are different games, not two skins

**CLASSIC** is 1972. Constant ball speed for the whole rally, the only control
over the angle is where on the paddle you hit it, first to **11**. Drawn in one
colour, flat, with a dashed net, square paddles and a square ball, and **no
particles and no music** — the cabinet had none. It would be trivial to give it
the neon treatment and it is deliberately not given it: the point of having two
modes is that they are two different things to want.

**REMIX** keeps that skeleton and adds the two things the original could not do:
the ball **accelerates** the longer a rally survives (the heat ring around it
brightens as it goes), and a moving paddle imparts **spin** — drag as the ball
arrives and you bend its path, which costs you position to use. Rallies end
faster and more decisively, so it plays to **7**. Neon, particles, ball trail,
synthwave.

The court **leans back 22° in remix and is flat in classic**. The chassis basis
tilts the plane, which is right for a game with depth and wrong for one that
should look like a television: the lean turns a rectangular court into a
trapezoid with a narrower far wall.

## Difficulty is three numbers, not one

Making the opponent merely faster produces a wall that never misses; making it
slower produces one that never tries. What separates the levels is how much
court you have to defend, how quickly the opponent commits, and how far off its
aim is — an opponent that misjudges by a paddle-width loses in a way that feels
like a rally rather than a gift.

| | paddle | opponent speed | opponent error | ball |
|---|---|---|---|---|
| EASY | 0.26 | 0.95 | 0.26 | 0.90 |
| NORMAL | 0.20 | 1.40 | 0.12 | 1.10 |
| HARD | 0.15 | 1.95 | 0.04 | 1.35 |

The opponent only tracks the ball while it is coming toward it, and drifts back
to centre otherwise. One that mirrors the ball at all times is unbeatable and,
worse, dull to watch.

## Sound

`assets/sfx/*.wav` are generated, not sampled. Classic uses square-wave beeps in
the spirit of the original cabinet; remix uses detuned saw voices an octave
higher so they carry over the music. Every cue opens with a ~4 ms transient —
the ear locates a sound by its attack, and a cue that fades in politely under a
running track is never noticed.

## Layout notes, measured on the device

- Visible field is about **±0.144 across and ±0.117 up** in plane-local metres.
- The vector font advances about **5.4× its size parameter per character**, and
  a glyph is roughly **7.3× that parameter tall**. Both bit: `SKILL` ran into
  `NORMAL`, and the score was clipped off the top edge until it moved inside the
  court — which is where the cabinet had it anyway.
- **If nothing appears, check `batch.setBasis(...)` is still called in
  `Game.update`.** Coordinates here are game metres and the batch draws in world
  units; without it everything renders at 1/92 scale.

## Build

```
./gradlew :app:assembleDebug
adb -s <glasses-serial> install -r app/build/outputs/apk/debug/x3pong.apk
```

Two devices are usually attached (glasses and phone), so `-s` is not optional.
The glasses report `model:ARGF20`, `manufacturer:RayNeo`.
