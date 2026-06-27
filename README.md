## Travellers

**Travellers** is a Millénaire-focused immersion mod that brings the world outside village borders to life. Instead of keeping Millénaire NPCs confined to their settlements, Travellers introduces roaming people who **move between villages and react to what happens along the way**. You'll naturally encounter them while exploring the world.

Basically, Travellers was just a mod aiming to add roaming travellers that travel between villages. But the more I worked on, the more ideas I got and added.

**REQUIRES MILLENAIRE 8.1.2 + MIXINBOOTER TO WORK**

### Millenaire integration

Travellers are not generic wanderers. They are designed to **blend into Millénaire's cultures and addons**:

* Their **culture is determined by where they spawn** and the surrounding Millénaire villages.
* They use **Millénaire (and addons) textures and data stucture (culture txts)**.

### Custom voice lines
Travellers have voices and voice personalities ! 
They will talk and eventually tell you when a creeper approaches.

The only culture that really has voicelines are normans, they speak french and have 3 or 4 personalities.
Other cultures speak english by default (I'm non-english speaker and don't speak other languages soooo...).

Travellers also have different voice personalities !

**I search for people with good microphones to help me make voicelines, don't hesitate to tell me if you want to add voices for a culture and I'll tell you how and judge your mic quality**
It can even be simply be a new voice personality to a culture that already has voicelines.

**Full compat with Sound Physics :D**

### Songs ?!
Travellers also are singers !
They can eventually sing in **chorus** when you ask them or when they walk together (only for 1 norman personality at the moment)

They also sing alone when they walk.

### Roaming and survival

Travellers are built to handle real travel rather than scripted wandering:

* They **really** travel between villages even when very far (Henchmen only — normal Travellers despawn after a while)
* They can **fight hostile mobs**, use melee or ranged combat (bows when available), and **upgrade their equipment** by picking up better items found on the ground.

### Real population system

All travellers, raiders and henchmen are virtualized when unloaded. Meaning their movement is simulated when they are outside view distance.
Meaning every traveller is unique and moves in the world.

### Raiders

The mod also adds a hostile variant:

* **Raider** (`travellers:traveller_raider`) travels like a Traveller, but **attacks players and Millénaire villagers**.

---

## Henchmen

This mod also adds **Henchmen**: recruitable NPCs that turn travel into gameplay. They spawn in established villages (population threshold configurable) and behave like autonomous couriers when not hired, travelling to villages bringing a small portion of needed goods for building and wall constructions (even for unloaded villages).
Henchmen travel from villages to villages **even virtually**, they travel even when the entity is unloaded and use a "virtualization mode" that simulates its movement.
That means every henchman is unique and really moves from villages to villages even when unloaded.

Once hired with Millénaire deniers, Henchmen become **your long-distance team**, built to operate even when you are far away.

### What Henchmen can do

* **Follow you reliably** even when you go far from them
* **Be organized into multiple groups** by assigning **leaders**, making large crews manageable
* **Use formations** — assign formation styles and tweak spacing via the Formation Hub
* **Run recruitment missions** to visit villages and bring back additional Henchmen (with configurable costs, distance limits, and spawn ranges)
* **Sell or donate items to Millénaire villages** as if you did it yourself
* **Travel extremely far**, including to destinations in **unloaded areas** — they do not require the target village to be actively loaded to sell or donate
* **Be set as enemies** with specific villages or players; hired Henchmen inherit their leader's enemy player list
* **Have a world map** showing all your Henchmen and their positions at a glance
* **Have an assigned Barracks** — a place for them to respawn and take equipment from chests

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

**Attack a village**
* Right-click a hired Henchman
* Go to **Behavior > Village relations**
* Toggle the target village as enemy
* Use the **Go** menu to send them to attack
Ensure the village is loaded.

**Transport goods**
* Right-click a hired Henchman
* Go to **Transport**
* Give items → select a village → click Validate → click Sell or Donate
''
The Henchman will travel to the village, complete the transaction, and return.

**Manage groups and formations** *(default key: **H**)*
* Press **H** in-game (or right-click a Henchman and open the **Formation Hub**) to open the hub
* The hub shows a world map with all your Henchmen marked and a list of current leaders
* Select Henchmen directly on the map by click-dragging a rectangle; hold **Shift** to add to the selection
* Create a group from your selection — the best-equipped Henchman in the selection becomes the leader automatically
* Adjust formation style and spacing with the sliders on the right
* Give orders to the group leaders

**World map**
* A standalone world map (showing your Henchmen) can be opened with a key you assign yourself in **Options > Controls** under the Travellers category — no key is bound by default.

Henchmen don't pathfind when out of view. Performance impact is kept to a minimum.

### Potential incompatibilities

* **Optifine's `Smooth World` option** may cause Henchmen to get stuck at loaded chunk borders

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

---

### Known problems

Travellers may sometimes cause these logs to appear :
```
[minecraft/SoundManager]: Error in class 'SourceLWJGL OpenAL'
[minecraft/SoundManager]: Channel null in method 'stop'
```

You can ignore it.

### Big thanks

**@Aidan** from the Millénaire Discord for beta testing and finding lots of bugs!
Aidan is the guy that reported the worst bugs, he helped me a lot thanks to him.

**@Joshy Boy** from the Millénaire Discord for reporting bugs!
Joshy is this guy that complains

**@Shwartz** from the Millénaire Discord for the Huari, Japanese, and Norman (default) backpack models!

### AI Disclosure

Yeah my friend Claude Code helped me doing this project in 1 year instead of never even trying doing it.
You can freely screenshot this part and tell people it's AI slop.

