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

The only culture that really has voicelines are normans, they speak french and have 3 or 4 personalities.
Other cultures speak english by default (I'm non-english speaker and don't speak other languages soooo...).

Travellers also have different voice personalities !

**I search for people with good microphones to help me make voicelines, don't hesitate to tell me if you want to add voices for a culture and I'll tell you how and judge your mic quality**
It can even be simply be a new voice personality to a culture that already has voicelines.

### Songs ?!
Travellers also are singers !
They can eventually sing in **chorus** when you ask them or when they walk together (only for 1 norman personality at the moment)

They also sing alone when they walk.

### Roaming and survival

Travellers are built to handle real travel rather than scripted wandering:

* They **really** travel between villages even when very far (Henchmen only — normal Travellers despawn after a while)
* They can **fight hostile mobs**, use melee or ranged combat (bows when available), and **upgrade their equipment** by picking up better items found on the ground.

### Horse companions

Travellers, raiders and henchmen can show up with a **bonded horse** — a permanent companion, not a disposable ride. Once bonded, that pairing lasts for good: it follows them through virtualization (captured and restored exactly, gear and all, never re-rolled), through combat (they fight from the saddle, no need to dismount), and through your formations (mounted followers keep pace and catch up like anyone else, no special handling needed on your side). Nobody can steal it either — a bonded horse won't respond to another player trying to tame or ride it.

They don't stay in the saddle regardless of what they're doing. The moment the horse is out of its depth they get down, and it swims alongside on a lead — including behind a boat, towed across the open sea. Arriving in a village, they get down and leave the horse at the edge rather than riding it through the alleys, and pick it up again on their way out. Camping does the same.

Short of one of them dying, the pair is never separated: a horse that falls behind is brought back, and one that somehow disappears without dying comes back exactly as it was. If the rider is the one who dies, their horse is left behind as an ordinary tamed horse — yours to claim.

How often travellers, raiders and henchmen show up mounted, how much faster a horse makes them, and whether they bother dismounting in villages at all, is entirely up to you in the config GUI (`Mounts` category).

### Elytra flight

Give a traveller, raider or henchman an **Elytra** and they will actually use it. Standing at the top of a cliff with a long walk ahead, they stop, turn to face where they're going, and throw themselves off — gliding over the canyon, the river or the ravine they would otherwise have spent minutes walking around, then landing and carrying on on foot.

They fly on exactly the same physics you do, with the same limitation: an elytra trades height for distance and can't climb, so they only take off when the ground ahead genuinely falls away enough for the glide to pay off. On flat terrain they simply keep walking. In the air they read the terrain ahead, climbing over or banking around anything in the way, and flare to bleed off speed before touching down.

Escorting henchmen take off **with you**: step off a ledge and start gliding, and your crew launches a moment later and fans out behind you. They can't follow you upwards on wings alone — but a henchman carrying **firework rockets** will spend them to climb back up to you, out of their own inventory (so give them rockets if you want them to keep up on long flights).

The whole thing can be turned off, and everything is tunable, in the config GUI (`Elytra` category): whether they take off on their own, whether escorts follow you into the air, how far a glide has to be worth before they bother, glide ratio, turn and dive rates, and firework use.

### Real population system

All travellers, raiders and henchmen are virtualized when unloaded. Meaning their movement is simulated when their chunk isn't loaded.
Meaning every traveller is unique and moves in the world.

They appear (materialize) and disappear (turn virtual) based on whether the game is actually simulating the ground they stand on — kept loaded by your own view distance, or by anything else (another player, a Millénaire village, a chunk loader). A henchman sent far away on a "go" order will pop into existence the moment it reaches a loaded village, even while you're nowhere near it. The mod itself never forces chunks to stay loaded to move anyone around: everything outside loaded terrain is simulated instead, so a thousand travellers cost no chunk loading at all. Tunable in config (`Materialization` category: `materializeChunkStableTicks` / `virtualizeChunkUnneededTicks` / `rematerializeGraceTicks` — how long things must stay settled before they appear or disappear, to avoid flicker).

### Raiders

The mod also adds a hostile variant:
* **Raider** (`travellers:traveller_raider`) travels like a Traveller, but **attacks players and Millénaire villagers**.

### Virtual battles

The simulated world isn't peaceful. When hostile parties cross paths **anywhere in the world — even thousands of blocks away, in unloaded chunks** — a real battle breaks out and plays out blow by blow: every fighter is positioned individually, closes on its target, and attacks at the speed of its actual weapon. Armor absorbs damage, archers stand off and shoot, and passing groups can pile into an ongoing fight as reinforcements. Battles show up live on the world map (red cross markers with fighter counts), and camps can be assaulted too — a wiped-out camp is left as a ruin with a dying fire.

The fallen's gear and cargo go into that fight's loot pool (per-type toggles let you decide who drops anything at all — hired henchmen do by default, everyone else doesn't). On a clean victory, the winning side's surviving henchmen/travellers/raiders get first pick and upgrade their own equipment from the pool before anything hits the ground; whatever's left (or the whole pool, on a draw/timeout) appears on the ground when you travel there. If your hired henchmen get attacked, you're **notified in chat with coordinates** (even offline — the message waits for your next login) so you can send reinforcements Age-of-Empires style, watch the fight progress on the map, and hover your workers to see their live HP. Raiders won't jump your henchmen instantly: a group just passing through is left alone, only sustained contact turns into a fight. And if you declare another player hostile, your henchman armies will fight each other wherever they meet.

Walk into a battle and it seamlessly turns physical around you — same fighters, same health, same positions. Walk away and the simulation takes over again. Everything is configurable (or can be disabled) in the config GUI.

Your worldmap and formation hub also have a fog of war: foreign travellers, raiders, other players' henchmen, and battles only show up once one of your own henchmen gets close enough (a configurable vision radius) — no more seeing every fight on the server from your armchair. Other players' hired henchmen show up tinted in their owner's color, and their HP stays hidden unless you turn that on in the config.


### Camps (WIP)

Travellers and raiders don't just walk between villages — they'll occasionally **stop and set up camp** out in the wild.

If a whole camp gets wiped out (raiders happen...), the abandoned campfire **burns out on its own** after a few minutes, leaving a cold firepit and half-built ruins behind until another group moves in and relights it.

This all keeps happening even when you're not there to watch — camps quietly progress in the background and pick up right where they left off (mid-build, mid-teardown, whatever) the next time you wander back.


---

## Henchmen

This mod also adds **Henchmen**: recruitable NPCs that turn travel into gameplay. They spawn in established villages (population threshold configurable) and behave like autonomous couriers when not hired, travelling to villages bringing a small portion of needed goods for building and wall constructions (even for unloaded villages).
Henchmen travel from villages to villages **even virtually**, they travel even when the entity is unloaded and use the "virtualization mode" that simulates its movement and behavior on unloaded chunks.
That means every henchman is unique and really moves from villages to villages even when unloaded.

Once hired with Millénaire deniers, Henchmen become **your long-distance team**, built to operate even when you are far away.

### What Henchmen can do when hired

* **Follow you reliably** even when you go far from them
They use the virtualization system to move on unloaded chunks
* **Be organized into multiple groups** by assigning **leaders**, making large crews manageable.
Open formations menu with the `H` key (by default)
* **Use formations** — assign formation styles and tweak spacing via the Formation Hub; a group always keeps a tight formation for its current size, closing ranks automatically when it loses members
* **Set a group's Attitude** — Attack (chase freely), Defend (hold ground, only chase threats that get close) or Hold Position (never move to fight, great for a line of archers), applied instantly to the selected group
* **Run recruitment missions** to visit villages and bring back additional Henchmen (with configurable costs, distance limits, and spawn ranges)
* **Sell or donate items to Millénaire villages** as if you did it yourself
* **Travel extremely far**, including to destinations in **unloaded areas** — they do not require the target village to be actively loaded to sell or donate
* **Be set as enemies** with specific villages or players; hired Henchmen inherit their leader's enemy player list
Note that henchmen are better fighters than millagers, leading to non-fair village destruction.
* **Follow you into the air** — an Elytra-equipped Henchman takes off with you when you start gliding, and spends firework rockets from its own inventory to keep up when you climb
* **Have a world map** showing all your Henchmen and their positions at a glance
* **Have an assigned Barracks** — a place for them to respawn and take equipment from chests when ordered

### Barracks

Henchmen can be assigned a **Barracks** — a zone you define yourself anywhere in the world, used for storage and Henchmen respawns.

* **To create one**: hold Millénaire's Summoning Wand, **sneak + right-click** any block. A prompt lets you name the barrack and set the zone dimensions.
* The zone is scanned for **beds** — each bed becomes a slot for one Henchman. Henchman capacity equals the number of beds found inside the zone.
* You can freely add or remove beds inside the zone at any time; the mod re-scans automatically. No need to reconfigure anything.
* By default, Henchmen **respawn beside their assigned bed** after dying (configurable: on/off and delay in days). This means you can build proper barracks with rooms and beds and your Henchmen will come back there three days after dying.

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
* Under the portrait, outside the tree: your **colour mark** and the **Map**.
* On a Henchman you have not hired, the tree is replaced by its details and a **Hire** button.

The menu sizes itself to your screen, so it works at any GUI scale. If you would rather keep several
branches open at once, turn on `Keep several menu branches open at the same time` in the config
(`Worker hub` category) — it needs a lot more room, and falls back on its own when it does not fit.

**Send them somewhere**
* Right-click a hired Henchman
* Go to **Orders > Go... > Travel** and select a position
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
* Go to **Orders > Transport**
* Give items → select a village → click Validate → click Sell or Donate

The Henchman will travel to the village, complete the transaction, and return to you.

**Manage groups and formations** *(default key: **H**)*
* Press **H** in-game (or right-click a Henchman and open the **Formation Hub**) to open the hub
* The hub shows a world map with all your Henchmen marked and a list of current leaders
* Select Henchmen directly on the map by click-dragging a rectangle; hold **Shift** to select only followers / add to existing selection
* Create a group from your selection — the best-equipped Henchman in the selection becomes the leader automatically
* Adjust formation style, spacing and Attitude (Attack/Defend/Hold Position) with the controls on the right, plus a Player Cohesion slider for how tightly escorts hug a moving leader instead of trailing behind
* Give orders to the group leaders: send them somewhere (**Go to…**), have a whole group **Follow…** another leader or you while keeping its own formation (a convoy), toggle a group's **Follow: ON/OFF** to make it follow you or hold position, **Merge…** one group into another (merge into yourself to dismiss it), or fetch equipment
* A scrollable **event history** panel (recruits, trades, arrivals, battles...) is shown above/below the hub — click a battle entry to center the map on it

**World map**
* A standalone world map (showing your Henchmen) can be opened with a key you assign yourself in **Options > Controls** under the Travellers category — no key is bound by default.


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
* Changes apply live; saving writes to `config/travellers/travellers_overrides.properties`
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
