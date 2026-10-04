package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.gaze.Greetings;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.sound.ModSounds;
import com.jubitus.travellers.sound.net.MsgPlayTravellerVoice;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.culture.VoiceSet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A unit's voice: one channel (a new line waits for the lock unless it preempts), the barks (attack,
 * kill, death, creeper, talk, hello) and the voice personality picked once at spawn.
 * Server authoritative; clients get each line as a moving sound.
 */
public final class TravellerVoice {

    public enum Kind {NONE, AMBIENT, TALK, HURT, CHOIR}

    // One shout per 32-block zone per cooldown window: zone key -> world time of last shout
    private static final ConcurrentHashMap<Long, Long> ATTACK_ZONE_COOLDOWN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> KILL_ZONE_COOLDOWN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> DEATH_ZONE_COOLDOWN = new ConcurrentHashMap<>();

    private final EntityTraveller unit;
    private int lockUntilTick = 0;
    private Kind kindNow = Kind.NONE;
    private int attackCooldownUntilTick = 0;
    private int killCooldownUntilTick = 0;

    TravellerVoice(EntityTraveller unit) {
        this.unit = unit;
    }

    /** Server tick: the channel's kind clears once its lock has run out. */
    void tick() {
        if (kindNow != Kind.NONE && unit.ticksExisted >= lockUntilTick) kindNow = Kind.NONE;
    }

    // ---- channel ----

    public boolean play(SoundEvent evt, float vol, float pitch, int durationTicks, boolean preempt, Kind kind) {
        if (!preempt && unit.ticksExisted < lockUntilTick) return false;
        send(evt, vol, pitch, 48.0D);
        lockUntilTick = Math.max(lockUntilTick, unit.ticksExisted + Math.max(1, durationTicks));
        kindNow = kind;
        return true;
    }

    public boolean isLocked() {
        return unit.ticksExisted < lockUntilTick;
    }

    public boolean isAmbientPlaying() {
        return isLocked() && kindNow == Kind.AMBIENT;
    }

    public int getLockUntilTick() {
        return lockUntilTick;
    }

    /** Frees the channel at once (the line itself keeps playing on clients unless stopped). */
    void release() {
        lockUntilTick = unit.ticksExisted;
        kindNow = Kind.NONE;
    }

    /** Frees the channel and fades out whatever line clients are playing. */
    void stopNow() {
        release();
        if (unit.world.isRemote) return;
        com.jubitus.travellers.sound.net.MsgStopTravellerVoice stop =
                new com.jubitus.travellers.sound.net.MsgStopTravellerVoice(unit);
        TravellersNetwork.CHANNEL.sendToAllAround(stop, new NetworkRegistry.TargetPoint(
                unit.dimension, unit.posX, unit.posY, unit.posZ, 96.0D));
    }

    private void send(SoundEvent evt, float vol, float pitch, double radius) {
        if (unit.world.isRemote || evt == null) return;
        // The remaining lock is the clip's duration hint; ~2s otherwise so short barks still work.
        int remaining = Math.max(1, lockUntilTick - unit.ticksExisted);
        MsgPlayTravellerVoice msg = new MsgPlayTravellerVoice(unit, evt, vol, pitch, remaining);
        TravellersNetwork.CHANNEL.sendToAllAround(msg, new NetworkRegistry.TargetPoint(
                unit.dimension, unit.posX, unit.posY, unit.posZ, radius));
    }

    // ---- barks ----

    public void tryPlayAttack() {
        if (!TravellersConfig.SOUND.attackSoundEnabled) return;
        if (unit.world.isRemote) return;
        if (unit.ticksExisted < attackCooldownUntilTick) return;
        if (kindNow == Kind.HURT && unit.ticksExisted < lockUntilTick) return;

        long worldTime = unit.world.getTotalWorldTime();
        long zoneKey = zoneKey();
        Long lastZone = ATTACK_ZONE_COOLDOWN.get(zoneKey);
        if (lastZone != null && worldTime - lastZone < TravellersConfig.SOUND.attackSoundZoneCooldownTicks) return;

        VoiceSet v = unit.resolveVoiceSetForAttack();
        if (v == null) return;
        SoundEvent snd = v.pickAttack(unit.getRNG(), null);
        if (snd == null) return;

        if (play(snd, TravellersConfig.SOUND.attackSoundVolume, jitteredPitch(0.04F), 60 + unit.getRNG().nextInt(40), true, Kind.TALK)) {
            attackCooldownUntilTick = unit.ticksExisted + TravellersConfig.SOUND.attackSoundEntityCooldownTicks;
            ATTACK_ZONE_COOLDOWN.put(zoneKey, worldTime);
        }
    }

    public void playCreeperWarning() {
        if (!TravellersConfig.SOUND.creeperWarnEnabled) return;
        if (unit.world.isRemote) return;
        VoiceSet v = activeVoiceSet();
        if (v == null) return;
        SoundEvent snd = v.pickCreeper(unit.getRNG(), null);
        if (snd == null) return;
        play(snd, TravellersConfig.SOUND.creeperWarnVolume, jitteredPitch(0.04F), 60 + unit.getRNG().nextInt(40), true, Kind.TALK);
    }

    public void tryPlayKill() {
        if (!TravellersConfig.SOUND.killSoundEnabled) return;
        if (unit.world.isRemote || unit.isDead) return;
        if (unit.ticksExisted < killCooldownUntilTick) return;

        long worldTime = unit.world.getTotalWorldTime();
        long zoneKey = zoneKey();
        Long lastZone = KILL_ZONE_COOLDOWN.get(zoneKey);
        if (lastZone != null && worldTime - lastZone < TravellersConfig.SOUND.killSoundZoneCooldownTicks) return;

        VoiceSet v = unit.resolveVoiceSetForKillDeath();
        if (v == null) return;
        SoundEvent snd = v.pickKill(unit.getRNG(), null);
        if (snd == null) return;

        if (play(snd, TravellersConfig.SOUND.killSoundVolume, jitteredPitch(0.04F), 60 + unit.getRNG().nextInt(40), true, Kind.TALK)) {
            killCooldownUntilTick = unit.ticksExisted + TravellersConfig.SOUND.killSoundEntityCooldownTicks;
            KILL_ZONE_COOLDOWN.put(zoneKey, worldTime);
        }
    }

    void tryPlayDeath() {
        if (!TravellersConfig.SOUND.deathSoundEnabled) return;
        if (unit.world.isRemote) return;

        long worldTime = unit.world.getTotalWorldTime();
        long zoneKey = zoneKey();
        Long lastZone = DEATH_ZONE_COOLDOWN.get(zoneKey);
        if (lastZone != null && worldTime - lastZone < TravellersConfig.SOUND.deathSoundZoneCooldownTicks) return;

        choosePersonalityIfNeeded();
        VoiceSet v = unit.resolveVoiceSetForKillDeath();
        if (v == null) return;
        SoundEvent snd = v.pickDeath(unit.getRNG(), null);
        if (snd == null) return;

        play(snd, TravellersConfig.SOUND.deathSoundVolume, jitteredPitch(0.04F), 60, true, Kind.TALK);
        DEATH_ZONE_COOLDOWN.put(zoneKey, worldTime);
    }

    public void playTalk() {
        VoiceSet v = activeVoiceSet();
        SoundEvent talk = (v != null) ? v.pickTalk(unit.getRNG(), ModSounds.TRAVELLER_TALK) : ModSounds.TRAVELLER_TALK;
        play(talk, TravellersConfig.SOUND.travellerTalkVolume, jitteredPitch(0.02F), 40 + unit.getRNG().nextInt(20), true, Kind.TALK);
        unit.scheduleNextTravelAmbient();
    }

    public void playHello() {
        VoiceSet v = activeVoiceSet();
        SoundEvent hello = (v != null) ? v.pickHello(unit.getRNG(), ModSounds.TRAVELLER_HELLO) : ModSounds.TRAVELLER_HELLO;
        play(hello, TravellersConfig.SOUND.travellerTalkVolume, jitteredPitch(0.02F), 30 + unit.getRNG().nextInt(20), true, Kind.TALK);
    }

    /** Whether a hello is still due to this player: not if this unit's party greeted theirs lately. */
    public boolean shouldHelloFor(EntityPlayer p) {
        UUID me = Greetings.partyOf(unit);
        return me == null || !Greetings.hasGreeted(unit.world, me, p.getUniqueID());
    }

    public void markHello(EntityPlayer p) {
        UUID me = Greetings.partyOf(unit);
        if (me != null) Greetings.record(unit.world, me, p.getUniqueID());
    }

    private float jitteredPitch(float spread) {
        return 1.0F + (unit.getRNG().nextFloat() - 0.5F) * spread;
    }

    private long zoneKey() {
        int zx = (int) Math.floor(unit.posX / 32.0);
        int zz = (int) Math.floor(unit.posZ / 32.0);
        return ((long) unit.dimension * 1_000_003L) ^ ((long) (zx & 0xFFFF) << 16) ^ (long) (zz & 0xFFFF);
    }

    // ---- personality ----

    public String getPersonalityId() {
        if (unit.getDataManager() == null) return "";
        String v = unit.getDataManager().get(EntityTraveller.VOICE_PERSONALITY_ID);
        return (v == null) ? "" : v;
    }

    void setPersonalityId(String id) {
        if (unit.world.isRemote) return;
        unit.getDataManager().set(EntityTraveller.VOICE_PERSONALITY_ID, id == null ? "" : id);
    }

    /** Server-only. Picks ONE personality (never changed again); "" means no variants, culture voices are used. */
    void choosePersonalityIfNeeded() {
        if (unit.world.isRemote) return;
        if (!getPersonalityId().isEmpty()) return;
        setPersonalityId(unit.pickVoicePersonalityId(unit.getCultureId()));
    }

    /** The personality's voice set if valid, else the culture's. */
    @Nullable
    VoiceSet activeVoiceSet() {
        choosePersonalityIfNeeded();
        return CultureRegistry.resolveTravellerVoiceSet(unit.getCultureId(), getPersonalityId());
    }
}
