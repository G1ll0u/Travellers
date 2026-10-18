## Travellers

**Travellers** is a mod for Minecraft 1.12.2 that adds travellers outside village borders. Instead of keeping Millénaire NPCs confined to their settlements, Travellers introduces roaming people who **move between villages and react to what happens along the way**. You'll naturally encounter them while exploring the world.
Basically, Travellers was just a mod aiming to add roaming travellers that travel between villages. But the more it evolved, the more ideas I got and added.

**REQUIRES MILLENAIRE 8.1.2 + MIXINBOOTER TO WORK**

### Millenaire integration

Travellers are not generic wanderers. They are designed to **blend into Millénaire's cultures and addons system**
You'll see norman, japanese, inuits, byzantines travelling the world.

### Custom voice lines
Travellers have voices and voice personalities ! 
They will talk and eventually tell you when a creeper approaches.

The only culture that really has voicelines are normans, they speak french and have 4 personalities.
Other cultures speak english by default (I'm non-english speaker and don't speak other languages soooo...).

Travellers also have different voice personalities !

**I search for people with good microphones to help me make voicelines, don't hesitate to tell me if you want to add voices for a culture and I'll tell you how and judge your mic quality**
It can even be simply be a new voice personality to a culture that already has voicelines.

### Songs ?!
Travellers also are singers !
They can eventually sing in **chorus** when you ask them or when they walk together (still in development: only a few songs are available at the moment)

They also sing alone when they walk.

### They notice you (and each other)
Travellers look around like real people: on the road they watch the way ahead and glance at whoever they pass — you, another traveller, a villager, a wolf crossing their path. Stay close to one and it stops, turns to face you (a rider swings its horse round, keeping it a little to the side so it can see you) and keeps an eye on you, with the odd look away, until you move on.

They also **say hello**: to you when they pass you or stop for you, and to other travellers and groups they meet on the road. One hello per group, not a chorus — the other members just nod along, and someone from the group being greeted answers (one of your own Henchmen answers for you). Once a traveller has greeted you, right-clicking it gets you its chatter rather than a second hello. In villages they only greet you, raiders greet nobody, and it can all be tuned or turned off in the `Gaze` config.

### Dialogue feature
Right-click a traveller that isn't hired and isn't hostile, and instead of a scripted line you get a real conversation: type whatever you want, and it answers in character. This runs entirely on your own computer through **Ollama** or an OpenAI-compatible local server such as **llama.cpp** (free, separate programs you install yourself) — the server never contacts any AI service on your behalf, so this only works for players who set it up. It's off by default; ask whoever runs your server to turn it on in the `Dialogue` config (in singleplayer, that's you).

They stay grounded in the actual world instead of making things up: their own personality, trade, culture and home village, why they're actually on the road, roughly how far away things are (in vague words — "a good walk south", never exact coordinates), what's going on around them, and whether they've met you before. Turn on tool use in the Advanced settings screen and, if your own model supports it, they can also look a few things up mid-conversation instead of only working from what they already know. You can also let them remember things across separate conversations later (off by default too) — a traveller remembers its own life, not just what you personally told it, so it can recall something a different player once said to it too.

Since this depends entirely on the AI model running on your own machine, expect it to be a bit rough around the edges — a slow reply from a large model, an odd answer from a small one, or just the canned fallback line if nothing is configured on your end.

### Roaming and survival

Travellers are built to handle real travel rather than scripted wandering:

* They **really** travel between villages even when very far — travellers, raiders and henchmen alike keep going when you're nowhere near (see *Virtualization* below)
* They **visit every village on their way** for a minute or three: they tie their horse at the edge of the village, then stroll the streets, go into the tavern, the market or the temple (the exact spot a villager would go to), stop to read the information panels, and chat with villagers or other travellers — face to face, or in a small circle, nodding along. They step aside for people instead of walking into them, glance at you as you pass and stop to face you if you stay close. Visitors spread over the whole village instead of piling up in one spot, and a group splits up to visit on its own before meeting again at the leader's horse. It keeps going while you're away: come back and the visitors are already scattered around the village, each horse still at its post. Raiders only walk the streets. A wandering henchman leaves a small gift of building materials when it leaves. Everything is tunable in the `VillageVisit` config category.
* They can **fight hostile mobs**, use melee or ranged combat (bows when available), and **upgrade their equipment** by picking up better items found on the ground.
* They **open doors and fence gates** as they reach them (vanilla and most modded ones) and **close them behind them** — a group passing through keeps it open until the last one is through, and a gate out of a pen is shut again once they've left. A door you propped open yourself is left alone.
* Leaving or entering a **walled village**, they head for the **gate** instead of trudging into a wall corner and getting stuck (which used to end in a teleport over the wall). It only kicks in when the wall is genuinely in the way — an unfinished wall or a village without one is walked through as before. Toggle in `Travel` config (`routeThroughVillageGates`).
* Where they go next is **regionalized**, not a blind dice roll: a traveller leans toward villages of **its own culture** and ones its **home village is on good terms with** (Millénaire's own inter-village relations), avoids anyone it's at open war with, and **larger villages pull more traffic**. Distance still dominates — it's a soft nudge, fully tunable (`Destinations` category, `regionalizationStrength` 0–1), and travellers on roads tend to stay on roads. **Raiders invert it**: they head for foreign and hostile regions instead of their own. Travellers and workers who have wandered far from home slowly drift back toward it (`homePullWeight`); raiders don't.

### Horse companions

Travellers, raiders and henchmen can show up with a **bonded horse** — a permanent companion, not a disposable ride. Once bonded, that pairing lasts for good: it follows them through virtualization (captured and restored exactly, gear and all, never re-rolled), through combat (they fight from the saddle, no need to dismount), and through your formations (mounted followers keep pace and catch up like anyone else, no special handling needed on your side). Nobody can steal it — not even you: right-clicking a bonded horse simply does nothing, for anyone, including its own henchman's owner, while that henchman is alive. The bond only ever ends when the henchman does.

They don't stay in the saddle regardless of what they're doing. When water too deep to wade is in the way they get down at the bank without dithering, lead the horse in, and it swims alongside on a lead — including behind a boat, towed across the open sea — and they climb back on once both are dry on the far side, never in the water and never while the horse is still on the bank they left. Arriving in a village, they get down and leave the horse at the edge rather than riding it through the alleys, and pick it up again on their way out. Camping does the same. And where a horse simply doesn't fit — a narrow doorway, a thin wall, a tight gully — they step down and lead it through on foot rather than standing there, then climb back on once past it. A led horse tucks into single file right behind its owner, treading the same ground they just walked, and is brought back up if it ever snags on something and drops too far behind.

They ride like riders, not like people who happen to be sitting on a horse: they cut diagonally across a slope instead of climbing it in steps, slow down for a bend and stop to swing the horse around for a hard turn instead of drifting wide, and hop an obstacle their own horse is strong enough to clear — a weak jumper won't try what a good one can.

Short of one of them dying, the pair is never separated: a horse that falls behind is brought back, and one that somehow disappears without dying comes back exactly as it was. Riders defend their horse: attack it and its rider reacts exactly as if you had struck them. If the rider is the one who dies, their horse is simply left behind, right where it happened, as an ordinary loose horse (or, if you turn on the `Mounts` option for it, dies with its rider). It doesn't wait forever unclaimed, though — any other unmounted henchman with nothing else to do can notice it nearby and walk over to claim it as its own new mount, the same way you can walk up and tame it yourself.

How often travellers, raiders and henchmen show up mounted, how much faster a horse makes them — with separate paces for open-road travel and for riding in formation behind you, where they match your speed instead of racing ahead — and whether they bother dismounting in villages at all, is entirely up to you in the config GUI (`Mounts` category).

### Elytra flight

Give a traveller, raider or henchman an **Elytra** and they will actually use it. Standing at the top of a cliff with a long walk ahead, they stop, turn to face where they're going, and throw themselves off — gliding over the canyon, the river or the ravine they would otherwise have spent minutes walking around, then landing and carrying on on foot.

They fly on exactly the same physics you do, with the same limitation: an elytra trades height for distance and can't climb, so they only take off when the ground ahead genuinely falls away enough for the glide to pay off. On flat terrain they simply keep walking. In the air they read the terrain ahead, climbing over or banking around anything in the way, and flare to bleed off speed before touching down.

Escorting henchmen take off **with you**: step off a ledge and start gliding, and your crew launches a moment later and fans out behind you. They can't follow you upwards on wings alone — but a henchman carrying **firework rockets** will spend them to climb back up to you, out of their own inventory (so give them rockets if you want them to keep up on long flights).

The whole thing can be turned off, and everything is tunable, in the config GUI (`Elytra` category): whether they take off on their own, whether escorts follow you into the air, how far a glide has to be worth before they bother, glide ratio, turn and dive rates, and firework use.

### Real population system

All travellers, raiders and henchmen are virtualized when unloaded. Meaning their movement is simulated when their chunk isn't loaded.
Meaning every traveller is unique and moves in the world.

They appear (materialize) and disappear (turn virtual) based on whether the game is actually simulating the ground they stand on — kept loaded by your own view distance, or by anything else (another player, a Millénaire village, a chunk loader). A henchman sent far away on a "go" order will pop into existence the moment it reaches a loaded village, even while you're nowhere near it. The mod itself never forces chunks to stay loaded to move anyone around: everything outside loaded terrain is simulated instead, so a thousand travellers cost no chunk loading at all. Tunable in config (`Materialization` category: `materializeChunkStableTicks` / `virtualizeChunkUnneededTicks` / `rematerializeGraceTicks` — how long things must stay settled before they appear or disappear, to avoid flicker).

Minecraft itself stops updating creatures entirely in a world where nobody is connected, which used to strand travellers in the permanently-loaded chunks around world spawn: they walked in, turned real, and then stood frozen there because the game was no longer running them — the whole world's population slowly piling up on the spawn until somebody logged back in. They now correctly go back off-screen instead and carry on with their journeys.

You can also decide what happens when nobody is connected (`Offscreen` category): by default the whole off-screen simulation is **put on hold** and picks up exactly where it left off when someone logs in (`pauseWhenServerEmpty`). If you prefer a world that keeps living without you, turn that off — and optionally have it run slowly instead (`slowWhenServerEmpty` + `emptyServerSpeedMultiplier`, e.g. 0.25 for four times slower).

The hand-off in both directions is all-or-nothing: if the game refuses to place someone where they were about to appear, they stay offscreen and try again a moment later rather than going missing, and nobody can ever end up existing twice (once as a real body, once as an offscreen record).

### Raiders

The mod also adds a hostile variant:
* **Raider** (`travellers:traveller_raider`) travels like a Traveller, but **attacks players and Millénaire villagers**. Like travellers, they eat to heal, even while offscreen.

### Raids

From time to time a **raid** comes for you: several warbands of raiders appear far away, walk toward you and attack you and your henchmen first, fighting whatever else they meet as usual. A red notice tells you which direction they come from.

* Raids are sized on **your henchmen** (their number and their gear) and grow slower and slower the more you have, so a huge army does not mean an endless horde.
* You choose how often they come, whether they come by night or by day, how hard they hit, how many raiders at most, how far away they appear, and **which cultures may raid you** (one, a few, or all).
* A new player gets a few days of peace first. A raid never catches you "on login", and it ends when the raiders are all dead or after twenty minutes.
* Off by default; turn it on in the config (`RAIDS`). Server admins can start one at once with `/travellersdebug raid now`.

### Virtual battles

The simulated world isn't peaceful. When hostile parties cross paths **anywhere in the world — even thousands of blocks away, in unloaded chunks** — a real battle breaks out and plays out blow by blow: every fighter is positioned individually, closes on its target, and attacks at the speed of its actual weapon. Armor absorbs damage, archers stand off and shoot, and passing groups can pile into an ongoing fight as reinforcements. Battles show up live on the world map (red cross markers with fighter counts, arrows flying and fighters lunging — this animation can be turned off in the config), and camps can be assaulted too — a wiped-out camp is left as a ruin with a dying fire.

The fallen's gear and cargo go into that fight's loot pool (per-type toggles let you decide who drops anything at all — hired henchmen do by default, everyone else doesn't). On a clean victory, the winning side's surviving henchmen/travellers/raiders get first pick and upgrade their own equipment from the pool before anything hits the ground; whatever's left (or the whole pool, on a draw/timeout) appears on the ground when you travel there. If your hired henchmen get attacked, you're **notified in chat with coordinates** (even offline — the message waits for your next login) so you can send reinforcements Age-of-Empires style, watch the fight progress on the map, and hover your workers to see their live HP. Raiders won't jump your henchmen instantly: a group just passing through is left alone, only sustained contact turns into a fight. And if you declare another player hostile, your henchman armies will fight each other wherever they meet.

Walk into a battle and it seamlessly turns physical around you — same fighters, same health, same positions. Walk away and the simulation takes over again. Everything is configurable (or can be disabled) in the config GUI.

Your worldmap and formation hub also have a fog of war: foreign travellers, raiders, other players' henchmen, and battles only show up once one of your own henchmen gets close enough (a configurable vision radius) — no more seeing every fight on the server from your armchair. Other players' hired henchmen show up tinted in their owner's color, and their HP stays hidden unless you turn that on in the config.


### Notifications

You're kept informed of what happens to your Henchmen, even when they're far away:

* A Henchman dies (and whether it will come back beside its bed, and when) or is back
* A Go order arrives, after a long trip
* A sale or donation to a village is completed, or equipment was collected
* A Henchman's horse can't get through and is led on foot instead
* One of your Stables tames a horse, saddles one or has a foal
* Your Henchmen are attacked (with coordinates)

### Camps (WIP)

Travellers and raiders don't just walk between villages — they'll occasionally **stop and set up camp** out in the wild.

If a whole camp gets wiped out (raiders happen...), the abandoned campfire **burns out on its own** after a few minutes, leaving a cold firepit and half-built ruins behind until another group moves in and relights it.

This all keeps happening even when you're not there to watch — camps quietly progress in the background and pick up right where they left off (mid-build, mid-teardown, whatever) the next time you wander back.


---

## Henchmen

This mod also adds **Henchmen**: recruitable NPCs that turn travel into gameplay. They spawn in established villages (how many depends on the village's population) and behave like autonomous couriers when not hired, travelling to villages bringing a small portion of needed goods for building and wall constructions (even for unloaded villages).
Henchmen travel from villages to villages **even virtually**, they travel even when the entity is unloaded and use the "virtualization mode" that simulates its movement and behavior on unloaded chunks.
That means every henchman is unique and really moves from villages to villages even when unloaded.

Once hired with Millénaire deniers, Henchmen become **your long-distance team**, built to operate even when you are far away.

### What Henchmen can do when hired

* **Follow you reliably** even when you go far from them
They use the virtualization system to move on unloaded chunks
* **Be organized into multiple groups** by assigning **leaders**, making large crews manageable.
Open formations menu with the `H` key (by default)
* **Use formations** — assign formation styles and tweak spacing via the Formation Hub; a group always keeps a tight formation for its current size, closing ranks automatically when it loses members
* **Command from the Formation Hub map** — select a group or Henchmen on the map and right-click to give orders (details below)
* **Set a group's Attitude** — Attack (chase freely), Defend (hold ground, only chase threats that get close) or Hold Position (never move to fight, great for a line of archers), applied instantly to the selected group
* **Run recruitment missions** to visit villages and bring back additional Henchmen (with configurable costs, distance limits, and spawn ranges)
* **Sell or donate items to Millénaire villages** as if you did it yourself
* **Travel extremely far**, including to destinations in **unloaded areas** — they do not require the target village to be actively loaded to sell or donate
* **Be set as enemies** with specific villages or players — declaring a player hostile applies to your whole crew at once, not just one Henchman
Note that henchmen are better fighters than millagers, leading to non-fair village destruction.
* **Follow you into the air** — an Elytra-equipped Henchman takes off with you when you start gliding, and spends firework rockets from its own inventory to keep up when you climb
* **Have a world map** showing all your Henchmen and their positions at a glance
* **Have an assigned Barracks** — a place for them to respawn and take equipment from chests when ordered
* **Be put on duty at a Stable** — a dedicated crew that tames, saddles and breeds horses for the rest of your Henchmen

### Player zones: Barracks and Stables

You can create two kinds of zone yourself, anywhere in the world: a **Barracks** (housing) and a **Stable** (horse ranch). Both work the same way to set up:

* **To create one**: hold Millénaire's Summoning Wand, **sneak + right-click** any block. A prompt lets you pick the type, name it, and set the zone dimensions — the panel on the right explains what each type needs and does, and updates live as you choose.
* The zone is scanned for the blocks that type needs; you can freely add or remove them at any time and the mod re-scans automatically, no need to reconfigure anything.
* Assigning Henchmen to either one happens from the same **Zones** screen (off the Henchman menu or the Formation Hub): one list of all your Barracks and Stables, each with its own icon in your colour. Drag a Henchman onto a Barracks to make it a resident, or onto a Stable to put it on duty. The unassigned list is sorted closest-first when you open the screen.

**Barracks** need at least one **bed** and one **chest**. Each bed is a capacity slot for one Henchman; by default, Henchmen **respawn beside their assigned bed** after dying (configurable: on/off and delay in days), so a proper barracks with rooms and beds gets your Henchmen back a few days after they die.

**Stables** need at least one **chest**, one **fence** (to hitch horses to), and one **bed per palfrenier** — the crew sleeps there at night, same as a Barracks resident, and **that bed is also its home**: a palfrenier respawns beside it after dying, exactly like a Barracks resident. Assign any Henchman to Stable duty — if it was leading or following a group, it leaves that group first, and it claims a free bed (if there are none, the assignment is refused) — and it travels there on its own, all the way across the world if need be, turning virtual and back along the route without ever forgetting where it was headed. If it already had its own horse, that horse goes straight into the pool it's now tending instead of it staying mounted the whole time — and that happens whether it arrives with you watching or completely offscreen. It gets to work as a palfrenier: it walks up to an untamed horse and feeds it wheat (visibly carried in hand), takes it for a ride between feedings and gets thrown off — pure showmanship, the actual taming odds are the wheat and time, not the ride — until it's tame; walks to the chest, takes a saddle and carries it over to saddle a tamed horse; and lets a pair breed with golden carrots or apples from the same chest. When there's nothing urgent to do it doesn't just stand there — it'll go check on a horse or take a breather and look around now and then (it never opens the chest unless it's actually taking something out of it). At nightfall it walks to its bed and sleeps until dawn. A palfrenier at work takes no orders — to send it anywhere, take it off the Stable first from the Zones screen (you're told so if you try). A palfrenier on duty also stops picking fights — it defends itself if attacked but won't go hunting everything nearby, exactly like a Henchman with Follow turned off. Horses in a Stable never wander off, and nobody but you can tame, lead or ride them out. **You can, though** — hop on any ready horse in your own Stable and it leaves the pool as an ordinary mount of yours; ride a tame horse of yours back inside the Stable's area and get off, and it joins the pool. Once a Stable has a few horses ready, send another Henchman there with a **Get Equipment** order to pick one up as its own permanent mount — send a group leader and its whole group gets outfitted in one visit, one horse each, for as many as the Stable has ready (it always keeps at least 2 in reserve, configurable, so it's never emptied out). **A Stable keeps evolving whether you're standing there or on the other side of the world** — while you're away, taming, saddling, breeding and a foal's growth all keep going on their own from the wheat, saddles and golden carrots in its chest, and you come back to the horses it grew; while you're there, the palfrenier does all of it by hand. The pace can differ a little between the two, but where a Stable ends up is the same. You get a notification each time one of your Stables tames a horse, saddles one, or has a foal (each toggleable in the config), so you can keep track of a distant one without visiting it. Each Stable's pool is capped at twice your total number of Henchmen, and separately by its own pen size (a tiny pen can't hold dozens of horses even if you have the Henchmen to justify it) — so breeding tapers off well before things get crowded. Horses in a Stable you're far from are kept safely "on paper" and reappear exactly where they stood when you come back — a horse is never duplicated and never pops in right in front of you. Delete a Stable and it lets go cleanly, even from the other side of the world: every horse in its pool turns back into an ordinary tame horse of yours where it stands, and any palfreniers come off duty and walk back to you; build a new Stable over them and it takes them back in. Deleting a Barracks works the same way: its residents lose that home and come back to you. **Beds, in both kinds of zone:** break one and its Henchman moves to a free bed in the same zone if there is one; otherwise it leaves the zone, comes back to you, and you're told. Adding or breaking beds and chests is noticed right away. A Henchman belongs to one zone at a time — take it off its current one before assigning it elsewhere.

This mechanic is subject to evolve.

### What you can do with them

**The Henchman menu**

Right-clicking a hired Henchman opens its menu: a live portrait of that Henchman in the middle —
it follows your cursor with its eyes — with its name, culture, health and home village underneath,
and everything you can ask of it laid out around it as a tree.

* Entries marked **+** open in place: click **Orders** and its own entries appear in a new column,
  linked back to it by a line. Nothing already on screen moves.
* Entries marked **>** leave for their own screen. Coming back from one lands you on the menu
  exactly as you left it; closing the menu for real resets it.
* Clicking the portrait folds everything back to the root.
* Under the portrait, outside the tree: your **colour mark and armband style**, and the **Map**.
* On a Henchman you have not hired, the tree is replaced by its details and a **Hire** button.

The menu sizes itself to your screen, so it works at any GUI scale. If you would rather keep several
branches open at once, turn on `Keep several menu branches open at the same time` in the config
(`Worker hub` category) — it needs a lot more room, and falls back on its own when it does not fit.

**Send them somewhere**
* Right-click a hired Henchman
* Go to **Orders > Missions > Travel** and select a position
* The Henchman (and their followers if they are a leader) will travel to that position

> A Henchman already busy on a task (transporting goods, recruiting, or fetching equipment) won't accept a new order — Go, Get Equipment, or being grouped/regrouped — until it finishes. You'll get a short notice, and if you were grouping several at once the busy ones are simply left on their task while the rest are grouped.

**Attack a village**
* Right-click a hired Henchman
* Go to **Management > Relations > Villages**
* Toggle the target village as enemy
* Use the **Go** menu to send them to attack
**Ensure the village is loaded.**

**Transport goods**
* Right-click a hired Henchman
* Go to **Orders > Missions > Transport**
* Give items → select a village → click Validate → click Sell or Donate

The Henchman will travel to the village, complete the transaction, and return to you.

**Manage groups and formations** *(default key: **H**)*
* Press **H** in-game (or right-click a Henchman and open the **Formation Hub**) to open the hub
* The hub shows a world map with all your Henchmen marked, a list of your groups on the left and the selected group's settings on the right. Palfreniers on Stable duty have their own map icon
* **Left click** a leader (on the map or in the list) to select its group; click-drag a rectangle to select several Henchmen; hold **Shift** to add followers to your selection. **Right-drag** moves the map
* **Right click** on the map to give an order to what you selected — the menu shows only what makes sense for what you clicked:
  * on the ground: **Go here** · on a Barracks, Stable or village of yours: **Go here**, **Get equipment** (then come back, or stay there)
  * on yourself: **Follow me**, or **Merge into my escort** · on another leader: **Follow this group** (a convoy that keeps its own formation) or **Merge with this group**
  * on the selected group itself: **Halt here** / **Resume marching**
  * with loose followers selected: **Form a group** (the best-equipped becomes leader), **Form a group and go here**, **Join this group** (on a leader) or **Follow me** (on yourself)
  * a group busy on a mission shows its orders greyed out, with the reason when you hover them
* Adjust formation style, spacing and Attitude (Attack/Defend/Hold Position) on the right
* The **event history** at the bottom (recruits, trades, arrivals, battles, deaths...) — click an entry to center the map on where it happened, or on the Henchman it is about
* You're only told a Go order arrived when the trip was long (far, or took a while) — moving a group a few blocks stays quiet
* When a Henchman dies you're told whether it will come back beside its bed (and in how many days), and told again when it's back

**World map**
* A standalone world map (showing your Henchmen) can be opened with a key you assign yourself in **Options > Controls** under the Travellers category — no key is bound by default.
* The map starts out hidden under fog: the terrain is revealed chunk by chunk as you walk into it, separately for each player.
* The map only shows the Overworld.
* With **Ultreïa** installed, its roads, trails and bridges are drawn on the map, only where you have explored.


### Potential incompatibilities

* **Optifine's `Smooth World` option** changes how chunks are kept loaded in singleplayer, which this mod keys off. If travellers behave oddly around the edge of your view distance, try disabling it. (Travellers gathering at that border was a mod bug in its own right and is fixed — they now go virtual before ever reaching it.)

### Special compatibilities
- Compatibility with **Ultreïa**, my mod that generates real paths between villages
Travellers will navigate using the Ultreïa path network.
- Compatibility with **SoundPhysics**

---

## Configuration

This mod is very configurable. You can customize way too much things, so much that you can easily break the mod.
All mod values are configurable without restarting:

* **In-game GUI**: run `/travellersconfig` or use the Forge **Mod Options** button.
* Changes apply live
* Changes done on this GUI affect server or client
* A client-side **entity render LOD** option skips cosmetic overlays (clothes/backpack/armband) and the held-item render for distant Henchmen/Travellers/Raiders, to keep frame rate up with many on screen at once — configurable distances, can be disabled

---

## Big thanks

**@Aidan** from the Millénaire Discord for beta testing and finding lots of bugs!
Aidan is the guy that reported the worst bugs, he helped me a lot thanks to him.

**@Joshy Boy** from the Millénaire Discord for reporting bugs!
Joshy is this guy that complains

**@Shwartz** from the Millénaire Discord for the Huari, Japanese, and Norman (default) backpack models!

## AI agents Disclosure
Yeah my friend Claude Code helped me doing this project in 1 year instead of never even trying doing it.
You can freely screenshot this part and tell people it's AI slop.

## AI Art disclosure

NO GEN AI HAS BEEN USED FOR ART OR ANYTHING ELSE, ALL PIECES OF ART FROM THIS MOD BELONGS TO ME, @Shwartz from Millénaire Discord and Millénaire base assets.
Every resource, from audio to textures are hand-made with love.

## Problem reporting
Do not hesitate to report issues in GitHub issues
