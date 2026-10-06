# Mount forest fixes — keep the real horse, fix its defects

**Status (2026-10-06)**: approved by the user (D1, D2, D4 as recommended; D3 after measuring). Steps 1-2 done;
step 4 being measured.

## Goal

Riders on a real 1.4-wide horse cross forests reliably: wedges against low foliage, trunks and branches become
rare and short, and a wedge never becomes permanent. Causes and "before" numbers: `docs/mount-system-review.md`
sections 3 and 8.

## Decisions taken with the user

- Keep the realistic model (1.4 × 1.6 horse + rider). The narrow-horse redesign (review section 5) is rejected.
- Instruments first, then one fix per cause (3.1, 3.2, 3.3, 3.4), each validated by a 10-min `ab` plus the forest
  scenario. Runs of 10 min need no approval; longer ones do.
- Out of scope, only reported: duplicate horses ("Keeping entity minecraft:horse"), horse teleport recalls. Their
  counters are still read after each fix to see whether the fixes already lower them.
- The margin trade-off (wider margin = fewer wedges, but low woodland may become unpathable) is the user's call,
  made on measurements.

- D1: forest scenario = per route 4 solo riders + 1 mounted group (leader + 3 followers), 5-min budget.
- D2: for the horse only, leaves are never ground nor a jump target; still something to ride under.
- D3: margin value chosen by the user from measurements (still open).
- D4: each fix is a commit (needed by `ab`), holding only this work's files, never the unrelated local changes.

## Steps

### 1. Obstacle instruments in the trace — done
- One shared classifier: given a wedged horse and its heading, name the block it presses against and its class:
  `WOOD` (logs, Dynamic Trees branches), `LEAVES`, `TERRAIN` (anything else solid), `HEADROOM_AT_STEP` (the
  floor ahead is higher and the horse's box, raised in place by that rise, hits something above it — cause 3.1).
- Written into the existing `mount` trace events: sidestep, jump rejected, give-up LED, and the stall incident.
- Terrain snapshot: a third grid giving, per column, a one-letter class of the first blocking block above the
  floor (`L` leaves, `W` wood, `S` other solid, `.` none) plus a legend of the block names seen.
- Profiler: wedge episodes counted by obstacle class (new metric lines, so `compare` sees them), and mounted
  A* searches that end with no full path (needed to watch the "low woodland unpathable" trap of step 6).

### 2. Forest scenario — done
- `/travellersdebug autotest forest <x1> <z1> <x2> <z2>` (console-usable): spawns mounted riders at point 1
  with point 2 as their travel goal, through the real travel AI (same setup as `path test --mount`), zones
  forced on the route, traces on.
- Ends when all arrive or after a time budget; one report line per rider (arrived yes/no, time, wedge ticks,
  longest wedge, obstacle classes, LEDs, recalls) plus a totals line.
- `servertest.py forest` runs it on 3 routes of 112 blocks chosen from the region files (no water, ~17 % of
  columns with wood/leaves 2-3 blocks above ground): extreme_hills (-1928,-152)->(-1816,-152), forest_hills
  (-2200,-520)->(-2088,-520), forest (-1832,296)->(-1720,296). `ab` runs it before each soak.

### 3. "Before" measurement — done
- Forest, two runs of the same jar (route: arrived, median s, wedged ticks, longest wedge s, obstacles, LED):
  - extreme_hills: 7/7, 28/26, 164/187, 2.9/2.3, leaves 4/6 + step headroom 3/5 + wood 2/0, LED 7/6
  - forest_hills: 8/8, 20/21, 90/72, 2.3/2.2, leaves 2/1 + step headroom 3/2, LED 4/5
  - forest: 8/8, 19/19, 4/20, 0.2/0.7, wood 0/1, LED 4/6
  Everyone arrives; wedges are short (under 5 s). The permanent wedge of the review is not reproduced here.
- Soak 10 min (d220e4b5, `soak-20261006-143900`): 427 units loaded, stalls 105 (9 over 60 s), mounted efficiency
  0.65 (on foot 0.79, leading 0.54); wedges 140 = terrain 108, wood 11, leaves 9, step headroom 11 (9 over 2 s);
  mounted A* without a full path 4901 / 11583 (42 %); recalls 380 (remount 303); `LED_HORSE_JAMMED` 129;
  sidesteps 103; A* 5.5 ms/tick. Most terrain wedges sit in one place: plains at (-1632, 224), 32 wedges,
  28 sidesteps, 9 give-up LEDs.

### 4. Fix 3.1 — headroom on the way up a step — in progress (measuring)
- When a mounted move rises (a step up), require the horse's box at the takeoff spot, raised by that rise, to
  be free — in the planner (`findMountedWalkOptions`), the straight-line check (`corridorClear`) and the jump's
  own takeoff test already does it. Flat moves are unchanged, so riding under a low canopy on level ground
  stays allowed (this is what keeps the old "43 % of a route with no path" problem away: that came from
  demanding the rider's full height everywhere, not only at steps).
- Validation: `ab` + forest; the `HEADROOM_AT_STEP` class must drop, mounted no-path count must not rise.

### 5. Fix 3.2 — leaves are not ground for a horse — todo
- D2, my recommendation: for the horse only, a leaf block is never a standing surface and never a jump
  target (planner, corridor, jump scan). It stays something to ride under (cost, as today). Riders on foot
  are unchanged. Risk: a floor of leaf bushes becomes something to go round; watched with the no-path count.
- Validation: `ab` + forest; repeated "jump into foliage" and `LEAVES` wedges must drop.

### 6. Fix 3.3 — a margin against trunks — todo
- Add a lateral margin to the straight-line checks only (direct ride and mounted follower steering), and give
  `steerDirect` the same turn limit the path shortcut already has. The planner's 2×2 cells already leave 0.3
  per side and stay as they are.
- D3: measured at 2 or 3 margin values (e.g. 0.1 / 0.2 / 0.3) in the forest scenario: wedges on `WOOD` vs
  mounted no-path count and travel time. I bring the table and a recommendation; the user picks.

### 7. Fix 3.4 — recovery layers stop cancelling each other — todo
- Follow AI: when the horse is wedged, keep steering into the obstacle instead of skipping the tick, so the
  wedge stays visible and sidestep/jump/escalation can fire (today it flickers 0 / 0.31 every tick).
- Travel AI: on leaving the task, undo only an LED the travel task caused itself, not the sidestep give-up LED.
- Validation: `ab` + forest; the flicker signature disappears from auto-traces, no stall over 30 s in a group.

### 8. Wrap-up — todo
- Read recalls and duplicate-horse warnings across the runs and report them (no fix).
- Rewrite the `mount/CLAUDE.md` "Movement" rules touched (proposed to the user first); move this plan to
  `docs/archive/`; list what needs an in-game look.
