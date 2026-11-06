# Travellers

Roaming people for **Millénaire**: travellers, raiders and recruitable henchmen who **really travel between villages**, even far away from you, and react to what happens along the way.

Basically, Travellers was just a mod aiming to add roaming travellers that travel between villages. But the more it evolved, the more ideas I got and added.

> **Requires:** Minecraft 1.12.2 (Forge) · **Millénaire 8.1.2** · **MixinBooter**

This readme contains lots of details about the mod, I suggest just reading the CurseForge description to not spoil yourself.
**Contents:** [A living world](#a-living-world) · [Life on the road](#life-on-the-road) · [Conflict](#conflict) · [Henchmen](#henchmen) · [Configuration](#configuration) · [Compatibility](#compatibility) · [Credits](#big-thanks)

Curseforge Link : https://www.curseforge.com/minecraft/mc-mods/travellers
I, G1ll0u, (aka Jubitus) am the author of this mod.
I fully authorize Jubitus (me) of publishing this mod on Curseforge, Modrinth and any other platform.

Curseforge link : https://www.curseforge.com/minecraft/mc-mods/travellers

Modrinth link : https://modrinth.com/mod/travellers-jubitus

---

## A living world

### Millénaire travellers

Travellers are not generic wanderers: they **blend into Millénaire's cultures and addons**. You'll see Normans, Japanese, Inuits, Byzantines travelling the world.

### A real population

- Every traveller, raider and henchman is **unique** and keeps moving when you're not there: their travel is simulated while their chunks are unloaded.
- They appear and disappear depending on whether the ground they stand on is loaded (by you, another player, a Millénaire village, a chunk loader...).
- The mod **never forces chunks to stay loaded**: a thousand travellers cost no chunk loading at all.

<details>
<summary>Details</summary>

A henchman sent far away on a "go" order will pop into existence the moment it reaches a loaded village, even while you're nowhere near it. How long things must stay settled before they appear or disappear (to avoid flicker) is tunable in the `Materialization` config category.

</details>

### Travel

- They travel between villages, picking where to go next by **region**: their own culture, villages their home is on good terms with, bigger villages. Distance still matters most.
- **Raiders invert it**: they head for foreign and hostile regions.
- They **open doors and fence gates** and close them behind them, and use the **gate** of walled villages.

<details>
<summary>Details</summary>

- **Destinations**: a traveller avoids anyone its home village is at open war with (Millénaire's own village relations). It's a soft nudge, fully tunable (`Destinations` category), and travellers on roads tend to stay on roads. Travellers and henchmen who wandered far from home slowly drift back toward it; raiders don't.
- **Doors and gates**: vanilla and most modded ones. A group passing through keeps it open until the last one is through, and a gate out of a pen is shut again once they've left. A door you propped open yourself is left alone.
- **Walled villages**: they head for the gate instead of trudging into a wall corner. It only kicks in when the wall is genuinely in the way: an unfinished wall or a village without one is walked through as before. Toggle in the `Travel` config.

</details>

### Village visits

They **visit every village on their way** for a minute or three: tie their horse at the edge, stroll the streets, go into the tavern, the market or the temple, read the information panels and chat.

<details>
<summary>Details</summary>

- They go to the exact spot a villager would go to, and chat face to face or in a small circle.
- They step aside for people instead of walking into them, glance at you as you pass and stop to face you if you stay close.
- Visitors spread over the whole village instead of piling up in one spot. A group splits up to visit on its own, then meets again at the leader's horse.
- It keeps going while you're away: come back and the visitors are already scattered around the village, each horse still at its post.
- Raiders only walk the streets. A wandering henchman leaves a small gift of building materials when it leaves.
- Everything is tunable in the `VillageVisit` config category.

</details>

### Horse companions

Travellers, raiders and henchmen can show up with a **bonded horse**: a permanent companion, not a disposable ride.

<details>
<summary>Details</summary>

- The bond follows them through the simulation (gear and all, never re-rolled), through combat (they fight from the saddle) and through your formations (mounted followers keep pace like anyone else).
- Nobody can steal it, not even you: right-clicking a bonded horse does nothing, for anyone, including its own henchman's owner, while that henchman is alive.
- The bond only ever ends when the rider does.

</details>

### Elytra flight (fun slop feature)

Give a traveller, raider or henchman an **Elytra** and they will actually use it: at the top of a cliff with a long walk ahead, they jump and glide over the canyon, river or ravine, then carry on on foot.

<details>
<summary>Details</summary>

- Same physics as you: an elytra trades height for distance and can't climb, so they only take off when the ground ahead falls away enough for the glide to pay off. On flat terrain they keep walking.
- In the air they read the terrain ahead, climb over or bank around obstacles, and flare to slow down before landing.
- **Escorting henchmen take off with you**: step off a ledge and your crew launches a moment later and fans out behind you. A henchman carrying **firework rockets** spends them to climb back up to you (give them rockets for long flights).
- Everything can be turned off or tuned in the `Elytra` config category: self take-off, escorts following you, minimum glide worth it, glide ratio, turn and dive rates, firework use.

</details>

### Camps (WIP, disabled by default)

Travellers and raiders occasionally **stop and set up camp** in the wild. Camps keep progressing in the background (mid-build, mid-teardown...) and pick up where they left off when you come back. If a whole camp gets wiped out (raiders happen...), the fire burns out on its own, leaving a cold firepit and half-built ruins until another group moves in.

---

## Life on the road

### Voices

Travellers have voices and **voice personalities**! They talk, and will tell you when a creeper approaches.

- Only Normans really have voice lines: they speak French, with 4 personalities.
- Other cultures speak English by default (I'm a non-English speaker and don't speak other languages soooo...).

**I'm looking for people with good microphones to make voice lines!** Tell me if you want to add voices for a culture, or simply a new personality to one that already has some: I'll tell you how and judge your mic quality.

### Songs?!

Travellers are singers too! They sing alone while walking, and in **chorus** when you ask them or when they walk together (still in development: only a few songs for normans with the default personality).

I spent lot of time singing and making voicelines for them, I hope you'll enjoy it :D

### They notice you (and each other)

They look around like real people: on the road they watch the way ahead and glance at whoever they pass. Stay close to one and it stops, turns to face you and keeps an eye on you until you move on.

They also **say hello**, to you and to other travellers and groups they meet.

### Dialogue (optional, local AI)

Right-click a traveller that isn't hired or hostile and have a **real conversation**: type whatever you want, it answers in character.

- Runs entirely on **your own computer** through **Ollama** or an OpenAI-compatible local server such as **llama.cpp** (free programs you install yourself). The server never contacts any AI service on your behalf.
- **Off by default**: ask whoever runs your server to turn it on in the `Dialogue` config (in singleplayer, that's you).

<details>
<summary>Details</summary>

- They stay grounded in the actual world instead of making things up: their own personality, trade, culture and home village, why they're actually on the road, roughly how far away things are (in vague words, "a good walk south", never coordinates), what's going on around them, and whether they've met you before.
- **Tool use** (Advanced settings screen): if your model supports it, they can look things up mid-conversation.
- **Memory across conversations** (off by default): a traveller remembers its own life, not just what you told it, so it can recall something another player once said to it.
- Expect it to be a bit rough around the edges: a slow reply from a large model, an odd answer from a small one, or the canned fallback line if nothing is configured.

</details>

---

## Conflict

### Combat

Travellers **fight hostile mobs** in melee or at range (bows when available), and **upgrade their equipment** by picking up better items found on the ground.

### Raiders

**Raiders** (`travellers:traveller_raider`) travel like travellers, but **attack players and Millénaire villagers**. Like travellers, they eat to heal, even offscreen.

### Raids

From time to time a **raid** comes for you: raiders appear far away, walk toward you and attack you and your henchmen first.

- It comes in **waves**, each a bit stronger than the last.
- No two raids are alike: from **one side, two sides or all around**; **horde** (many, lightly armed), **elite** (few, heavy armour) or **cavalry** waves, with some archers; a later wave may come from **another culture** that joined in.
- A **bar at the top of the screen** shows the wave and how many raiders still stand.

<details>
<summary>Details</summary>

- The next wave sets out when the current one is nearly beaten, or after a few minutes anyway.
- A red notice says where each wave comes from, and another tells you when the raid is **repelled** or when the raiders give up and scatter.
- Raids are sized on **your henchmen near you** (number and gear) and grow slower the more you have: a huge army doesn't mean an endless horde, and a lone trip far from your army brings a small raid.
- You choose how often they come, by night or by day, how hard they hit, the maximum raiders and waves, how often each approach and kind of wave comes, the armour, weapons, archers and shields of each kind, how far away they appear, and **which cultures may raid you**.
- A new player gets a few days of peace first. A raid never catches you "on login". If a wave isn't beaten after twenty minutes, the raiders give up.
- Turn it on in the `RAIDS` config. Server admins can start one at once with `/travellersdebug raid now`.

</details>

### Virtual battles

The simulated world isn't peaceful: when hostile parties meet **anywhere in the world, even thousands of blocks away in unloaded chunks**, a real battle plays out blow by blow. Walk into it and it seamlessly turns physical around you: same fighters, same health, same positions.

<details>
<summary>Details</summary>

- Every fighter is positioned individually, closes on its target and attacks at the speed of its actual weapon. Armour absorbs damage, archers stand off and shoot, passing groups can join in as reinforcements. Camps can be assaulted too: a wiped-out camp is left as a ruin with a dying fire.
- Battles show up **live on the world map** (red crosses with fighter counts, arrows flying, fighters lunging; the animation can be turned off).
- **Loot**: the fallen's gear and cargo go into the fight's loot pool (hired henchmen drop by default, others don't; per-type toggles). The winners' survivors pick upgrades first; the rest (or everything, on a draw) appears on the ground when you travel there.
- If your henchmen are attacked, you're **notified in chat with coordinates** (even offline: the message waits for your next login), so you can send reinforcements Age-of-Empires style and hover your henchmen on the map to see their live HP.
- Raiders won't jump your henchmen instantly: a group just passing through is left alone, only sustained contact turns into a fight.
- Declare another player hostile and your henchman armies fight each other wherever they meet.
- **Fog of war**: foreign units, other players' henchmen and battles only show on your map once one of your henchmen gets close enough. Other players' henchmen are tinted in their owner's colour, their HP hidden unless enabled in the config.
- Everything is configurable, or can be disabled.

</details>

---

## Henchmen

**Henchmen** are recruitable NPCs that turn travel into gameplay. They spawn in established villages (more in bigger ones) and, when not hired, travel as couriers bringing a bit of the goods villages need for building and walls (even to unloaded villages).

Once hired with Millénaire deniers, they become **your long-distance team**, built to operate even when you're far away.

### What hired henchmen can do

- **Follow you** reliably, even far away.
- **Be organized into groups** with leaders and **formations** that close ranks when they lose members.
- **Take an Attitude**: Attack (chase freely), Defend (hold ground, only chase close threats) or Hold Position (never move to fight, great for a line of archers).
- **Travel anywhere**, including unloaded areas.
- **Sell or donate items** to Millénaire villages as if you did it yourself, even unloaded ones.
- **Recruit** more henchmen from other villages (configurable cost, distance and spawn range).
- **Go to war** against villages or players: declaring a player hostile applies to your whole crew. Note that henchmen are better fighters than millagers, leading to non-fair village destruction.
- **Follow you into the air** with an Elytra (see [Elytra flight](#elytra-flight-fun-slop-feature)).
- **Live in a Barracks** and **work in a Stable** (see below).

### The henchman menu

Right-click a hired henchman: its live portrait in the middle (it follows your cursor with its eyes), its name, culture, health and home village underneath, and everything you can ask of it laid out around it as a tree.

<details>
<summary>Details</summary>

- Entries marked **+** open in place, in a new column linked by a line. Nothing already on screen moves.
- Entries marked **>** open their own screen. Coming back lands you on the menu exactly as you left it; closing the menu for real resets it.
- Clicking the portrait folds everything back. Under it: your **colour mark and armband style**, and the **Map**.
- On a henchman you haven't hired, the tree is replaced by its details and a **Hire** button.
- The menu sizes itself to your screen. To keep several branches open at once, turn on `Keep several menu branches open at the same time` (`Worker hub` config); it falls back on its own when it doesn't fit.

</details>

### Orders

| To... | Go to |
|---|---|
| Send them somewhere | **Orders > Missions > Travel**, then pick a position. A leader brings its followers. |
| Sell or donate goods | **Orders > Missions > Transport**: give items, pick a village, click Sell or Donate (greyed out while the trip can't start; hover to see why). |
| Attack a village | **Management > Relations > Villages**: mark it as enemy, then send them there with **Travel**. **Make sure the village is loaded.** |

- Back from a transport trip, the henchman stays by your side: talk to it to take the coins and unsold goods. It's free for new orders once its pack is empty.
- A henchman busy on a task (transport, recruiting, fetching equipment) refuses new orders until it's done; you get a short notice. When grouping several, the busy ones are left on their task.

### Formation Hub *(default key: **H**)*

A world map with all your henchmen, your groups on the left and the selected group's settings (formation style, spacing, Attitude) on the right.

- **Left click** a leader to select its group, drag a rectangle to select several henchmen, **Shift** to add followers. **Right-drag** moves the map.
- **Right click** on the map to give an order; the menu only shows what makes sense where you clicked.
- The **event history** at the bottom (recruits, trades, arrivals, battles, deaths...): click an entry to center the map on it.

<details>
<summary>All map orders</summary>

- On the ground: **Go here**. On a Barracks, Stable or village of yours: **Go here**, **Get equipment** (then come back, or stay there).
- On yourself: **Follow me**, or **Merge into my escort**. On another leader: **Follow this group** (a convoy that keeps its own formation) or **Merge with this group**.
- On the selected group itself: **Halt here** / **Resume marching**.
- With loose followers selected: **Form a group** (the best-equipped becomes leader), **Form a group and go here**, **Join this group** (on a leader) or **Follow me** (on yourself).
- A group busy on a mission shows its orders greyed out, with the reason on hover.
- Stable hands have their own map icon.

</details>

### World map

- A standalone map of your henchmen, on a key you assign yourself in **Options > Controls** (Travellers category; none by default).
- Starts under **fog**: terrain is revealed chunk by chunk as you explore, separately for each player.
- You only know the villages you've seen: lists, orders and recruiting tours ignore the others (configurable).
- Overworld only. With **Ultreïa** installed, its roads, trails and bridges are drawn where you've explored.

### Barracks and Stables

Two kinds of zone you create anywhere: a **Barracks** (housing) and a **Stable** (horse ranch).

- **Create one**: hold Millénaire's Wand of Summoning and **sneak + right-click** a block. Pick the type, name it, set its size; a side panel explains what each type needs.
- Add or remove blocks any time: the zone re-scans itself.
- **Assign henchmen** from the **Zones** screen (Henchman menu or Formation Hub): drag a henchman onto a Barracks to make it a resident, onto a Stable to put it on duty.
- A henchman belongs to one zone at a time.

**Barracks** need at least one **bed** and one **chest**. Each bed houses one henchman, who **respawns beside it** a few days after dying (configurable on/off and delay).

**Stables** need a **chest**, a **fence** and **one bed per stable hand**. Stable hands tame, saddle and breed horses for the rest of your henchmen, sleep in their bed at night and respawn beside it.

<details>
<summary>How a Stable works</summary>

- **Assigning a stable hand**: it leaves its group, claims a free bed (no free bed: assignment refused) and travels there on its own, across the world if needed. If it had its own horse, that horse joins the Stable's pool.
- **At work**: it feeds wheat (visibly in hand) to an untamed horse and rides it between feedings, getting thrown off (pure showmanship: the taming odds are the wheat and time). It takes a saddle from the chest to saddle a tamed horse, and breeds pairs with golden carrots or apples from the same chest. With nothing urgent, it checks on a horse or takes a breather. At nightfall it goes to bed until dawn.
- **On duty** it takes no orders (take it off the Stable first, from the Zones screen) and stops picking fights: it only defends itself.
- **Horses** in a Stable never wander off, and nobody but you can tame, lead or ride them out. Ride a ready horse out and it becomes your ordinary mount; ride a tame horse of yours into the Stable and get off, and it joins the pool.
- **Equipping henchmen**: send a henchman with **Get Equipment** to pick up a horse as its permanent mount. Send a leader and its whole group gets one each, as long as the Stable has some ready (it always keeps 2 in reserve, configurable).
- **It keeps going while you're away**: taming, saddling, breeding and foals growing all continue from the chest's wheat, saddles and golden carrots. The pace can differ a little, but where the Stable ends up is the same. You get a notification for each tame, saddle and foal (each toggleable).
- **Size**: the pool is capped at twice your number of henchmen, and by its pen size, so breeding tapers off before it gets crowded.
- Horses in a distant Stable are kept "on paper" and reappear exactly where they stood: never duplicated, never popping in front of you.
- **Deleting a zone**: a Stable's horses become ordinary tame horses of yours where they stand, and its stable hands come back to you (build a new Stable over them and it takes them back). A Barracks' residents lose their home and come back to you.
- **Beds**: break one and its henchman moves to a free bed in the same zone; otherwise it leaves the zone, comes back to you, and you're told.

This mechanic is subject to evolve.

</details>

### Notifications

You're kept informed even when your henchmen are far away:

- a henchman dies (and whether and when it comes back beside its bed), or is back
- a Go order arrives after a long trip (short moves stay quiet)
- a sale or donation is completed, or equipment was collected
- a henchman's horse can't get through and is led on foot instead
- one of your Stables tames a horse, saddles one or has a foal
- your henchmen are attacked (with coordinates)

---

## Configuration

This mod is very configurable. You can customize way too many things, so much that you can easily break the mod.

- In-game GUI: `/travellersconfig` or Forge's **Mod Options** button.
- Changes apply live, no restart for most settings, on the server or the client.

## Compatibility

- **Ultreïa**, my mod that generates real paths between villages: travellers navigate using its path network.
- **SoundPhysics**: slopped compatibility for songs and streamed sounds.

---

## Big thanks

**@Aidan** from the Millénaire Discord for beta testing and finding lots of bugs! Aidan is the guy that reported the worst bugs, he helped me a lot.

**@Joshy Boy** from the Millénaire Discord for reporting bugs! Joshy is the guy that complains.

**@Shwartz** from the Millénaire Discord for the Huari, Japanese and Norman (default) backpack models!

And thanks to the Millénaire developers that did the best mod to ever exist. <3
@Kinniken
@MoonCutter2B
Between others

## AI agents disclosure

Yeah, my friend Claude Code helped me do this project in 1 year instead of never even trying.
You can freely screenshot this part and tell people it's AI slop.

Translations other than French are made with a local LLM, so expect some rough wording in the other languages.
Feel free to help me translate things correctly.

You can look at how I work with llms, I didn't exclude CLAUDE.md and the plans made by it.

## AI art disclosure

NO GENERATIVE AI HAS BEEN USED FOR ART OR ANYTHING ELSE, ALL PIECES OF ART FROM THIS MOD BELONG TO ME, @Shwartz from the Millénaire Discord and Millénaire base assets.
Every resource, from audio to textures, is hand-made with love.

## Problem reporting

Don't hesitate to report issues in [GitHub issues](https://github.com/G1ll0u/Travellers/issues).

## License

All rights reserved, with an exception for modpacks: see [LICENSE](LICENSE).
You can contribute, ask me things, forks aren't forbidden but just ask me.

I'm available in pretty all Millénaire and 1.12.2 related servers.

- Cleanroom Discord https://discord.gg/sgQxDJdrnY
- Millénaire Discord https://discord.gg/nCXjd4s
- 1.12.2 Discord https://discord.com/invite/wyWDHZb8zq
- Millénaire website https://www.millenaire.org/
