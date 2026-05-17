## Travellers

**Travellers** is a Millénaire-focused immersion mod that brings the world outside village borders to life. Instead of keeping Millénaire NPCs confined to their settlements, Travellers introduces roaming people who **move between villages and react to what happens along the way**. You’ll naturally encounter them while exploring the world.

**REQUIRES MILLENAIRE 8.1.2 + MIXINBOOTER TO WORK**

### A world that feels connected

Travellers are not generic wanderers. They are designed to **blend into Millénaire’s cultures and addons**:

* Their **culture is determined by where they spawn** and the surrounding Millénaire villages.
* They use **Millénaire (and addons) textures**.

### Smarter roaming and survival

Travellers are built to handle real travel rather than scripted wandering:

* They **really** travel between villages even when really far *
* They can **fight hostile mobs**, use melee or ranged combat (bows when available), and **upgrade their equipment** by picking up better items found on the ground.

"*" Applies to henchmen only (1.1.3b), normal travellers despawn after a moment

### Dynamic, Millénaire-aware spawning

Travellers uses a “soft spawner” approach that adapts to the world:

* Spawn rates and local caps scale with **nearby village count**, **village population**, and **lone buildings**.
* Many values are configurable in `/config/travellers/travellers.cfg`.

### Raiders

Not everyone is friendly. The mod also adds a hostile variant:

* **Raider** (`travellers:traveller_raider`) travels like a Traveller, but **attacks players and Millénaire villagers**.

Be aware! Raiders may look like normal travellers.

---

## Workers (Henchmen)

Travellers also adds **Workers (Henchmen)**: recruitable NPCs that turn travel into gameplay. They can spawn in established villages (population threshold configurable) and behave like autonomous couriers when not hired, travelling to villages bringing them a small portion of needed goods (configurable) for building / wall constructions to support them over time.

Once hired with deniers, Workers become **your long-distance team**, built to operate even when you are far away:

### What Workers can do

* **Follow you reliably** even when you go far from them
* **Be organized into multiple groups** by assigning **leaders**, making large crews manageable.
* **Run recruitment missions** to visit villages and bring back additional Workers (with configurable costs, distance limits, and spawn ranges).
* **Sell or donate items to Millénaire villages** as if you did it yourself.
* **Travel extremely far**, including to destinations in **unloaded areas**—they do not require the target village to be actively loaded to sell or donate.
* **Be enemy with villages or players** you can set lists of enemy villages or players they will attack
  * Hired workers will **inherit** leader enemy player list
  * Enemy villages list applies to **all workers**
* **Provide you a map of workers** (will be an item too)
* **Set homes** It's just a place where you can send them and that shows on map. Useful as a map marker.

### What you can do with them
* **Send them anywhere**
  * Right click a hired henchman
  * Go to "orders > go... and select a position"
  * Henchman will go to this position (with his followers if leader)

They will go where you told them to go


* **Attack villages**
  * Right click on him
  * Go to "Behavior>Village relations"
  * Toggle the target village enemy
  * use "go" menu to send them attack the village

WIP, VILLAGE HAS TO BE LOADED (1.1.3b)

* **Transport**
  * Right click on him
  * Go to "Transport"
  * Give him items > select a village > click validate > click sell or donate

Henchman will go to the village, sell or donate and come back to you.


GUI HAS TO BE REDONE


Don't worry they don't really pathfind when out of view. Performance impact tries to be the smallest.

### Potential incompatibilities
* **Optifine's `Smooth World` option** may make them stuck at loaded chunks border (may work now, not tested since 1.1.x)

### Big thanks
**@Aidan** from Millénaire discord for his beta tester skills, he found lots of bugs !

**@Joshy Boy** from Millénaire discord for reporting bugs !

**@Shwartz** for the actual (1.1.3b) huari, japanese and norman(default) backpacks !



Backpacks models made by @Shwartz :
[images of backpacks]


