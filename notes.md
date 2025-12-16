# Travellers 1.2.0
THE HENCHMAN UPDATE

This is again a really big update

This update is the one that really brings life to your world, it adds an entity that REALLY travel between villages without disappearing.


### Henchman
This update brings a new traveller variation named "henchman" (travellers:traveller_worker) this guy will spawn at villages and also travel. The difference between normal travellers is that he really travel even far from player (by loading chunks)

By default, this guy interacts with villages by giving things needed by the village for construction (1% of one of items needed, configurable in .cfg).
You can right-click on it to open the menu. You can send him sell (or donate) goods for you to a village.
You can also hire him to make him a follower. You will be able to make him a leader and :
- Making him leader makes all your followers follow him as if he was you
  - You can make multiple groups :
    - If you have multiple followers, set one of them leader and they will folloe
- Hire other people and you'll be able to make another leader

# Travellers 1.1.0
**NOW CONFIG WILL BE LOCATED IN A NEW FOLDER AT `/config/travellers/travellers.cfg`**

**This is a really big update !**

I added lots of things to make travellers way more "melt" Millénaire (like cheese fondue hmmmm...), adding culture and textures integration. You will see all cultures even modded ones and that's really cool. Culture of traveller depends on where he spawns and what village cultures surround him.

They now spawn in a flow, travellers now depend on the actual number of villages and population


Here is a detailed changelog containing things I did but not all because I don't remember.

### Culture integration

  * Wired to Millénaire + addon cultures txts
  * Uses Millénaire + addon textures (from txts)
  * Specific sounds and songs for Normans (will be improved)
  * Items given at spawn also depend on culture txts (WIP)

### New soft spawner system

  * Dynamic, Millénaire-aware spawning for travellers and raiders
  * Spawn amount depends on nearby Millénaire villages and their population
  * Travellers now spwan more or less depending on:
    * Village population
    * Number of nearby villages
    * Number of lone buildings
    
  * Added dynamic regional traveller cap per player
    * Cap depends on local village density and lone buildings
    * Adjustable scan radius
    * Controls spawns regionally instead of globally


### Despawn
* Travellers now despawn when far from player for overcrowding and performance reasons

### Boat behaviour and ocean travel

* Travellers can now **use boats** when crossing water

### Raider entity

* Added aggressive traveller named **"Raider"** (`travellers:traveller_raider`)

  * Attacks players
  * Attacks Millénaire entities by default
  * Attacks travellers from different cultures
  * Configurable attack whitelist
  * Configurable attack blacklist
  * Still travels between villages

### Fishing
  * They now sometimes go fishing (cosmetic)

### Campfire
  * They now sometimes make campfires (using millénaire firepit)

### General things
  * Improved pathfinding
  * Added debug commands (search by doing `/traveller` or `/mill_` and TAB)
    * It's more for me for debugging my mod

NEW COMMANDS :
```
/travellerinfo 
useful informations about how my mod deals with villages

/travellermap [radius] [step]
creates a map file with village weights (step = blocks per pixel)

/travellersDumpCultureItems
dump Millenaire items per culture to a txt file

/travellerconfigreload
hot-reload config (will make the game reload most of travellers.cfg values instantly)

/travellerscount
Counts actual loaded travellers

/travellersglow <seconds|off> [color]
Make travellers glow to see them from far (debug)

/mill_dump <x> <y> <z> <outputFileName>
Dumps detailed Millénaire village info my mod use around a specific Town Hall position into a text file.

/mill_nearest_village_dump [filename]
Dumps the nearest Millénaire settlement to the player into a detailed text report.
Dumps to a file in the world save folder:

Village summary (center, name, culture, type, population).

List of buildings with tags and bounds.

Town Hall chest contents (using item handler or inventory).

A trimmed list of resource needs / deficits. (WIP, probably broken)
```
  * Added `/mounttraveller` command to mount traveller nearby player (stupid)
  * Revamped config