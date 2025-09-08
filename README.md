# TRAVELLERS MOD
A traveller that travels between villages...

This traveller entity simply travels from villages to villages to improve immersion. You will cross some of them on your journey. They are friendly but will attack you if you attack them. They will sometimes follow each others, fight mobs, help each others in combat, and take a break arrived to a village.


## Traveller entity features
### Equipment
- They now spawn with some random equipment and use it (customizable with loot tables)
- They gather items on ground if better than what they have and equip it

### Attack/defense
- They defend themselves
- They can shoot arrows if they have or find a bow

### Pathfinding
- Improved pathfinding (still subject to improvements)

### Config
- Configurable values like health or attack damage

### Aesthetic
- They will tell you what they do when you right click them
- 7 textures available and randomly assigned at spawn.

Traveller has a lot of config tweaks to customize health, time to stay in village, how much chance it has to start follow each others when they meet,  etc.
Checkout in `config/travellers.cfg`

There is also a mob blacklist. Entities on this list won't ever attack traveller, works for modded entities :
 ```
    S:TravellerAggroBlacklistIDs <
        minecraft:creeper
     >
```


### Current limitations
- They sometimes get stuck in a "detour loop" if a too big obstacle separates the traveller from its target village. To mitigate that, I made the traveller able to know it's stuck and try a detour but if the obstacle is too large, it will do a "detour loop"
- Fighting has to be improved, they are bad managing multiple enemies.

### Things probably going to be improved
- Textures
- Pathfinding (the biggest part of this mod)
- Combat
- Animations (only swingArm animation works for sword hit)
- Mod compat