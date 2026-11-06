# Sound, voices, choir

Audio on disk is organised by `language/personality/type`; run `./gradlew generateDynamicSounds` after adding files.

- **One pipeline for every important voice sound**: `TravellerVoice.play` → `MsgPlayTravellerVoice` → `TravellerVoiceClientBus.play()` → `EntityLinkedSound`. Footsteps and swings stay on vanilla one-shots. Category is `NEUTRAL`.
- Global cues (no language or personality): `sounds/<group>/<cue>/*.ogg` → event `<group>.<cue>` (raid, snack); their `ModSounds` field stays null until clips ship and callers fall back.
- `ModSounds` registers a `SoundEvent` only for kinds a personality has real clips for (`voices/index.json`). A sound-less personality's voice set is never empty: `resolveClipSourcePersonality` → own clips → `voice_overrides.json` → language default → first voiced personality of the language. Never "first id alphabetically".

## `EntityLinkedSound`
A tracking `PositionedSound` + `ITickableSound` (not `MovingSound`, which SoundPhysics forces to RECORDS).
- Seed `volume` with the real final value in the constructor (vanilla fixes the audible range from it at construction).
- Never call `stopSound()` before `stopNow()` (kills the fade).
- Use `entity.isDead`, not `!isEntityAlive()`, for "entity gone".
- With SoundPhysics: engine LINEAR attenuation; without: NONE + a manual inverse-distance curve.

## Volumes
Every `SOUND.*Volume` is a no-mods baseline; `soundPhysicsVolumeBoost` applies only when SoundPhysics is detected — never pre-multiply defaults. Detection uses `Launch.classLoader.getResource`, not `Class.forName`.

## Choir
`ChoirIndex`/`ChoirRegistry` resolve songs by language + personality (with aliases); `ChoirCoordinator` (per dimension, every 20 ticks) owns `ChoirSession`s, one stem per singer. Audio: `sounds/travellers/<lang>/<personality>/choir/<song>/stemN.ogg`. Never recruits an on-duty worker.
