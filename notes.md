# Travellers 1.0.2

**Old `travellers.cfg` will be backed up and a new one with new and default values will be created in `/minecraft/config`**

### Main changes
- New sounds (hit, right click and ambient)
  - They will sing randomly when travelling
  - Sounds made with love
- Redone ranged AI (bow) to add animation and make it less buggy
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
- New sounds

## Travellers 1.0.3
- Added agressive traveller named "Raider" (travellers:traveller_raider)
  - Attacks player
  - Attacks millenaire entities by default
  - Configurable attack list
  - Configurable attack blacklist
  - still travel between villages
- Improved swimming (was broken)