# Travellers 1.1.0
**NOW CONFIG WILL BE LOCATED IN A NEW FOLDER AT `/config/travellers/travellers.cfg`**
**OLD /config/travellers.cfg FILE WILL BE DELETED AT LAUNCH TO AVOID CONFUSION**

New big feature update of my Travellers mod.

I added lots of things to make travellers way more "melt" Millénaire (like cheese fondue hmmmm...), adding culture and textures integration. You will see all cultures even modded ones and that's really cool. Culture of traveller depends on where he spawns and what village cultures surround him.

They now spawn in a flow, travellers now depend on the actual number of villages and population


Here is a detailed changelog containing things I did but not all because I don't remember.

### Added cultures integration

  * Wired to Millénaire + addon cultures txts
  * Uses Millénaire + addon textures (from txts)
  * Specific sounds and songs for Normans (will be improved)

### New soft spawner system

  * Dynamic, Millénaire-aware spawning for travellers and raiders
  * Spawn amount depends on nearby Millénaire villages and their population
  * Travellers now choose travel targets based on:

    * Village population
    * Number of nearby villages
    * Number of lone buildings
  * Added dynamic regional traveller cap per player

    * Cap depends on local village density and lone buildings
    * Adjustable scan radius
    * Controls spawns regionally instead of globally

* Items also depend on culture txts

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
  * They now sometimes make campfires

### General things
  * Improved pathfinding
  * Added debug commands (search by doing `/traveller` or `/mill_` and TAB)
    * Find by yourself
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
count actual loaded travellers

/travellersglow <seconds|off> [color]
make travellers glow to see them from far

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