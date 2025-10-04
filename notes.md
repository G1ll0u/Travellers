# Travellers 1.0.2 (will be probably be renamed "Voyageurs" in future update)

**Old `travellers.cfg` will be backed up and a new one with new and default values will be created in `/minecraft/config`**

**If you update and have a world with travellers, use /travellerConfigReload to apply eventual new config values to all loaded travellers**
**Since it is my second mod, 
### Main changes
- Refactored ranged AI (bow) to add animation and make it less buggy
- More control for natural spawns in config
- Better pathfinding, especially obstacle avoidance
- Fixed visual bugs


### More detailed changes

#### Traveller spawn
- Adjusted spawn behaviour to spawn less travellers
- spawnWeight now defaults to 2 (instead of 10)
- Added a traveller limit

#### Commands
- Hot config reload
    - Use `/travellerConfigReload` to hot-reload `travellers.cfg` (avoids to restart the game after editing config, should work for most config values)
- added command /travellersCount
    - counts actually loaded travellers 
- added command `/travellersglow [time in seconds]`
     - Applies glow effect to debug entities (make them highlighted to know position)
- Added command `/followtraveller`
    - makes the camera follow a nearby traveller (WIP, buggy and also a debug thing)

#### Combat
- Travellers now handle multiple attack targets better

#### Pathfinding
- Improved pathfinding performance to limit chunkloads (thanks @Skrode)
- Improved pathfinding especially obstacle avoiding and behaviour if stuck somewhere too long
- Improved pathfinding in water (was inexistent lol)
- They now avoid walking on lilypad block type

#### Movement
- Improved movement by enabling back diagonal walking

#### Eating behaviour
- Improved eating frequency to be more organic / player-like
- Added eat animation (particles)
- Suppressed heal particles

#### Rendering
- Fixed armor not following arm when doing "SwingArm" animation

#### Internals
- Added some translation for entity name
- Improved village recognition code to use actual millenaire API
    - Fixed loottables **(won't depend on polishmod anymore)**