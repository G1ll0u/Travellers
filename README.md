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

### Real population system

All travellers, raiders and henchmen are virtualized when unloaded. Meaning their movement is simulated when their chunk isn't loaded.
Meaning every traveller is unique and moves in the world.

They appear (materialize) and disappear (turn virtual) based on whether the ground chunk they're standing on is actually loaded — by your own view distance, or by anything else keeping it loaded (another player, a Millénaire village, a chunk loader). A henchman sent far away on a "go" order will pop into existence the moment it reaches a loaded village, even while you're nowhere near it. Tunable in config (`Materialization` category: `materializeChunkStableTicks` / `virtualizeChunkUnneededTicks` — how long a chunk must stay loaded/unloaded before they appear/disappear, to avoid flicker from very brief chunk loads).

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
* **Run recruitment missions** to visit villages and bring back additional Henchmen (with configurable costs, distance limits, and spawn ranges)
* **Sell or donate items to Millénaire villages** as if you did it yourself
* **Travel extremely far**, including to destinations in **unloaded areas** — they do not require the target village to be actively loaded to sell or donate
* **Be set as enemies** with specific villages or players; hired Henchmen inherit their leader's enemy player list
Note that henchmen are better fighters than millagers, leading to non-fair village destruction.
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

**Send them somewhere**
* Right-click a hired Henchman
* Go to **Orders > Go...** and select a position
* The Henchman (and their followers if they are a leader) will travel to that position

> A Henchman already busy on a task (transporting goods, recruiting, or fetching equipment) won't accept a new order — Go, Get Equipment, or being grouped/regrouped — until it finishes. You'll get a short notice, and if you were grouping several at once the busy ones are simply left on their task while the rest are grouped.

**Attack a village**
* Right-click a hired Henchman
* Go to **Behavior > Village relations**
* Toggle the target village as enemy
* Use the **Go** menu to send them to attack
**Ensure the village is loaded.**

**Transport goods**
* Right-click a hired Henchman
* Go to **Transport**
* Give items → select a village → click Validate → click Sell or Donate

The Henchman will travel to the village, complete the transaction, and return to you.

**Manage groups and formations** *(default key: **H**)*
* Press **H** in-game (or right-click a Henchman and open the **Formation Hub**) to open the hub
* The hub shows a world map with all your Henchmen marked and a list of current leaders
* Select Henchmen directly on the map by click-dragging a rectangle; hold **Shift** to select only followers / add to existing selection
* Create a group from your selection — the best-equipped Henchman in the selection becomes the leader automatically
* Adjust formation style and spacing with the sliders on the right
* Give orders to the group leaders: send them somewhere (**Go to…**), have a whole group **Follow…** another leader or you while keeping its own formation (a convoy), toggle a group's **Follow: ON/OFF** to make it follow you or hold position, **Merge…** one group into another (merge into yourself to dismiss it), or fetch equipment
* A scrollable **event history** panel (recruits, trades, arrivals, battles...) is shown above/below the hub — click a battle entry to center the map on it

**World map**
* A standalone world map (showing your Henchmen) can be opened with a key you assign yourself in **Options > Controls** under the Travellers category — no key is bound by default.


### Potential incompatibilities

* **Optifine's `Smooth World` option** may cause Henchmen to get stuck at loaded chunk borders in singleplayer.
If you get any travellers stuck at view distance border, try disabling Optifine `Smooth World`

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
