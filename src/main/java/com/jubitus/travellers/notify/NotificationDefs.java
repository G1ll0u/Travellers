package com.jubitus.travellers.notify;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static com.jubitus.travellers.notify.NotificationDef.event;
import static com.jubitus.travellers.notify.NotificationDef.reply;
import static com.jubitus.travellers.notify.NotificationType.ALERT;
import static com.jubitus.travellers.notify.NotificationType.DANGER;
import static com.jubitus.travellers.notify.NotificationType.INFO;
import static com.jubitus.travellers.notify.NotificationType.REFUSAL;
import static com.jubitus.travellers.notify.NotificationType.SUCCESS;

/**
 * Every known notification kind used by {@link NotificationService}. New notifications should
 * get a constant here rather than an inline string key, so a key/arg-count mismatch is caught
 * at the call site instead of failing silently at runtime.
 * {@code event} = happened on its own (journaled), {@code reply} = answer to a click (toast only).
 */
public final class NotificationDefs {

    private NotificationDefs() {
    }

    // --- Raids ---
    public static final NotificationDef RAID_INCOMING = event(DANGER, "travellers.notify.raid.incoming", 0);
    public static final NotificationDef RAID_WAVE = event(DANGER, "travellers.notify.raid.wave", 2);
    public static final NotificationDef RAID_REPELLED = event(SUCCESS, "travellers.notify.raid.repelled", 0);
    public static final NotificationDef RAID_SCATTERED = event(INFO, "travellers.notify.raid.scattered", 0);

    // --- Worker hiring (MsgHireWorker) ---
    public static final NotificationDef HIRE_ALREADY_HIRED_BY_OTHER = reply(REFUSAL, "travellers.notify.hire.already_hired_by_other", 0);
    public static final NotificationDef HIRE_NEEDS_VILLAGE_CHIEF = reply(REFUSAL, "travellers.notify.hire.needs_village_chief", 0);
    public static final NotificationDef HIRE_NOT_ENOUGH_MONEY = reply(REFUSAL, "travellers.notify.hire.not_enough_money", 1);
    public static final NotificationDef HIRE_PAYMENT_FAILED = reply(REFUSAL, "travellers.notify.hire.payment_failed", 0);
    public static final NotificationDef DISMISS_FOLLOWER = reply(INFO, "travellers.notify.hire.dismissed", 0);

    // --- Recruitment (MsgRecruitStart, RecruitMission) ---
    public static final NotificationDef RECRUIT_BUSY = reply(REFUSAL, "travellers.notify.recruit.busy", 0);
    public static final NotificationDef RECRUIT_NO_VILLAGES_IN_RANGE = reply(REFUSAL, "travellers.notify.recruit.no_villages_in_range", 0);
    public static final NotificationDef RECRUIT_NOT_ENOUGH_FEE = reply(REFUSAL, "travellers.notify.recruit.not_enough_fee", 2);
    public static final NotificationDef RECRUIT_PAYMENT_FAILED = reply(REFUSAL, "travellers.notify.recruit.payment_failed", 0);
    public static final NotificationDef RECRUIT_MISSION_STARTED = reply(INFO, "travellers.notify.recruit.mission_started", 1);
    public static final NotificationDef RECRUIT_RECALLED = reply(INFO, "travellers.notify.recruit.recalled", 1);
    public static final NotificationDef RECRUIT_WORKERS_RECRUITED = event(SUCCESS, "travellers.notify.recruit.henchmen_recruited", 2);
    public static final NotificationDef RECRUIT_WORKERS_RECRUITED_ROAD = event(SUCCESS, "travellers.notify.recruit.henchmen_recruited_road", 2);
    public static final NotificationDef RECRUIT_MISSION_COMPLETED = event(SUCCESS, "travellers.notify.recruit.mission_completed", 0);
    public static final NotificationDef RECRUIT_BACK = event(SUCCESS, "travellers.notify.recruit.back", 1);
    public static final NotificationDef WORKER_PURSE_NO_ROOM = reply(REFUSAL, "travellers.notify.worker.purse_no_room", 0);
    public static final NotificationDef WORKER_TAKE_PURSE_FIRST = reply(REFUSAL, "travellers.notify.worker.take_purse_first", 1);

    // --- Worker sub-GUI actions (MsgOpenWorkerSubGui) ---
    public static final NotificationDef WORKER_NOT_HIRED_TRANSPORT = reply(REFUSAL, "travellers.notify.worker.not_hired_transport", 0);
    public static final NotificationDef WORKER_NOT_HIRED_INVENTORY = reply(REFUSAL, "travellers.notify.worker.not_hired_inventory", 0);
    public static final NotificationDef WORKER_NOT_HIRED_ORDERS = reply(REFUSAL, "travellers.notify.worker.not_hired_orders", 0);
    public static final NotificationDef WORKER_NOT_HIRED_BEHAVIOR = reply(REFUSAL, "travellers.notify.worker.not_hired_behavior", 0);
    public static final NotificationDef WORKER_NOT_HIRED_RECRUIT = reply(REFUSAL, "travellers.notify.worker.not_hired_recruit", 0);
    public static final NotificationDef WORKER_NOT_HIRED_HOME = reply(REFUSAL, "travellers.notify.worker.not_hired_home", 0);
    public static final NotificationDef WORKER_NOT_HIRED_GO = reply(REFUSAL, "travellers.notify.worker.not_hired_go", 0);
    public static final NotificationDef WORKER_NOT_HIRED_FORMATION = reply(REFUSAL, "travellers.notify.worker.not_hired_formation", 0);
    public static final NotificationDef WORKER_NOT_HIRED_VILLAGE_RELATIONS = reply(REFUSAL, "travellers.notify.worker.not_hired_village_relations", 0);

    // --- Leader assignment (MsgMakeWorkerLeader) ---
    public static final NotificationDef LEADER_RELEASED_FOLLOWERS = reply(INFO, "travellers.notify.leader.released_followers", 1);
    public static final NotificationDef LEADER_NOW_LEADING = reply(INFO, "travellers.notify.leader.now_leading", 1);
    public static final NotificationDef LEADER_NO_FREE_FOLLOWERS = reply(REFUSAL, "travellers.notify.leader.no_free_followers", 0);
    public static final NotificationDef GROUP_NEW_LEADER = event(INFO, "travellers.notify.leader.succession", 1);

    // --- Hands-off player follow (PlayerFollowSessions) ---
    public static final NotificationDef FOLLOW_STOPPED_HURT = event(ALERT, "travellers.notify.follow.stopped_hurt", 0);
    public static final NotificationDef FOLLOW_STOPPED_COMBAT = event(ALERT, "travellers.notify.follow.stopped_combat", 0);
    public static final NotificationDef FOLLOW_STOPPED_HUNGRY = event(ALERT, "travellers.notify.follow.stopped_hungry", 0);
    public static final NotificationDef FOLLOW_TARGET_LOST = event(INFO, "travellers.notify.follow.target_lost", 0);

    // --- Home / barracks (MsgWorkerGoHome, ZoneRoleRescan) ---
    public static final NotificationDef HOME_NOT_YOUR_WORKER = reply(REFUSAL, "travellers.notify.home.not_your_worker", 0);
    public static final NotificationDef HOME_NONE_ASSIGNED = reply(REFUSAL, "travellers.notify.home.none_assigned", 0);
    public static final NotificationDef ZONE_BED_LOST = event(ALERT, "travellers.notify.home.lone_barrack_bed_destroyed", 2);

    // --- Order gating (a worker busy on a mission can't take Go / Get-Equipment / group orders) ---
    public static final NotificationDef ORDER_WORKER_BUSY = reply(REFUSAL, "travellers.notify.order.worker_busy", 0);
    public static final NotificationDef ORDER_WORKER_ON_DUTY = reply(REFUSAL, "travellers.notify.order.worker_on_duty", 2);
    public static final NotificationDef ORDER_SOME_BUSY_SKIPPED = reply(REFUSAL, "travellers.notify.order.some_busy_skipped", 1);

    // --- Duty zone assignment (MsgAssignWorkerDuty) and Stable work ---
    public static final NotificationDef DUTY_ASSIGNED = reply(INFO, "travellers.notify.duty.assigned", 1);
    public static final NotificationDef DUTY_NO_BED = reply(REFUSAL, "travellers.notify.duty.no_bed", 1);
    public static final NotificationDef STABLE_HORSE_TAMED = event(SUCCESS, "travellers.notify.stable.horse_tamed", 1);
    public static final NotificationDef STABLE_HORSE_SADDLED = event(SUCCESS, "travellers.notify.stable.horse_saddled", 1);
    public static final NotificationDef STABLE_FOAL_BORN = event(SUCCESS, "travellers.notify.stable.foal_born", 1);
    public static final NotificationDef STABLE_MOUNT_RECOVERED = event(SUCCESS, "travellers.notify.stable.mount_recovered", 1);
    public static final NotificationDef STABLE_POOL_TOO_SMALL_TO_EQUIP = event(ALERT, "travellers.notify.stable.pool_too_small_to_equip", 1);
    public static final NotificationDef STABLE_HORSES_RETURNED = event(SUCCESS, "travellers.notify.stable.horses_returned", 3);
    public static final NotificationDef STABLE_HORSES_TAKEN = event(SUCCESS, "travellers.notify.stable.horses_taken", 3);
    public static final NotificationDef STABLE_NO_HORSE_TO_RETURN = event(ALERT, "travellers.notify.stable.no_horse_to_return", 0);
    // Same text, refused at order time because nobody in the group has a horse.
    public static final NotificationDef STABLE_RETURN_REFUSED_NO_HORSE = reply(REFUSAL, "travellers.notify.stable.no_horse_to_return", 0);

    // --- Go-to orders (MsgWorkerGoTo, MsgWorkerGoToPos, MsgWorkerSetDestination) ---
    public static final NotificationDef GOTO_GOING_TO = reply(INFO, "travellers.notify.goto.going_to", 1);
    public static final NotificationDef GOTO_GOING_TO_POS = reply(INFO, "travellers.notify.goto.going_to_pos", 0);
    public static final NotificationDef GOTO_NOT_ENOUGH_MONEY = reply(REFUSAL, "travellers.notify.goto.not_enough_money", 1);
    public static final NotificationDef GOTO_PAYMENT_FAILED = reply(REFUSAL, "travellers.notify.goto.payment_failed", 0);
    public static final NotificationDef GOTO_DELIVERY_STARTED_FREE = reply(INFO, "travellers.notify.goto.delivery_started_free", 1);
    public static final NotificationDef GOTO_DELIVERY_STARTED_PAID = reply(INFO, "travellers.notify.goto.delivery_started_paid", 2);
    public static final NotificationDef GOTO_DELIVERY_STARTED_DONATE = reply(INFO, "travellers.notify.goto.delivery_started_donate", 2);

    // --- Mission completion (go-to arrival, get-equipment result) ---
    public static final NotificationDef GOTO_ARRIVED = event(SUCCESS, "travellers.notify.goto.arrived", 0);
    public static final NotificationDef GOTO_GROUP_ARRIVED = event(SUCCESS, "travellers.notify.goto.group_arrived", 1);
    public static final NotificationDef EQUIP_DONE = event(SUCCESS, "travellers.notify.equip.done", 2);
    public static final NotificationDef EQUIP_NOTHING = event(INFO, "travellers.notify.equip.nothing", 0);
    public static final NotificationDef DUMP_DONE = event(SUCCESS, "travellers.notify.dump.done", 2);
    public static final NotificationDef DUMP_FULL = event(ALERT, "travellers.notify.dump.full", 2);
    public static final NotificationDef DUMP_NOTHING = event(INFO, "travellers.notify.dump.nothing", 0);

    // --- Hostility toggle (MsgSetWorkerHostile) ---
    public static final NotificationDef HOSTILE_NOT_OWNER = reply(REFUSAL, "travellers.notify.hostile.not_owner", 0);
    public static final NotificationDef HOSTILE_PLAYER_NOT_FOUND = reply(REFUSAL, "travellers.notify.hostile.player_not_found", 1);
    public static final NotificationDef HOSTILE_ADDED = reply(INFO, "travellers.notify.hostile.added", 1);
    public static final NotificationDef HOSTILE_REMOVED = reply(INFO, "travellers.notify.hostile.removed", 1);
    public static final NotificationDef WAR_DECLARED_ON_YOU = event(DANGER, "travellers.notify.hostile.war_declared_on_you", 1);

    // --- Worker lifecycle (EntityTravellerWorker) ---
    public static final NotificationDef WORKER_ARRIVED_HOME = event(INFO, "travellers.notify.worker.arrived_home", 0);
    public static final NotificationDef WORKER_CARGO_ARRIVED_FULL = event(SUCCESS, "travellers.notify.worker.cargo_arrived_full", 0);
    public static final NotificationDef WORKER_CARGO_ARRIVED_PARTIAL = event(ALERT, "travellers.notify.worker.cargo_arrived_partial", 0);
    public static final NotificationDef WORKER_CARGO_HEADING_HOME = event(INFO, "travellers.notify.worker.cargo_heading_home", 1);
    public static final NotificationDef WORKER_CARGO_WAITING = event(INFO, "travellers.notify.worker.cargo_waiting", 1);
    public static final NotificationDef WORKER_CARGO_COMING_BACK = event(INFO, "travellers.notify.worker.cargo_coming_back", 1);
    public static final NotificationDef WORKER_ALREADY_ON_JOB = reply(REFUSAL, "travellers.notify.worker.already_on_job", 0);
    public static final NotificationDef WORKER_DIED = event(DANGER, "travellers.notify.worker.died", 1);
    public static final NotificationDef WORKER_RESPAWNED = event(SUCCESS, "travellers.notify.worker.respawned", 1);
    public static final NotificationDef WORKER_RESPAWN_CANCELLED = event(ALERT, "travellers.notify.worker.respawn_cancelled", 1);
    // Second line of a death notice (see WorkerLifecycleNotifications)
    public static final NotificationDef WORKER_DEATH_RESPAWN_DAYS = reply(INFO, "travellers.notify.worker.death_respawn_days", 1);
    public static final NotificationDef WORKER_DEATH_RESPAWN_TOMORROW = reply(INFO, "travellers.notify.worker.death_respawn_tomorrow", 0);
    public static final NotificationDef WORKER_DEATH_NO_BED = reply(INFO, "travellers.notify.worker.death_no_bed", 0);
    public static final NotificationDef WORKER_RETALIATION_TARGET = event(ALERT, "travellers.notify.worker.retaliation_target", 1);

    // --- Trade / cargo (EntityTravellerWorker) ---
    public static final NotificationDef TRADE_REFUSED = event(ALERT, "travellers.notify.trade.refused", 0);
    public static final NotificationDef TRADE_VILLAGE_GONE = event(ALERT, "travellers.notify.trade.village_gone", 1);
    public static final NotificationDef TRADE_SOLD_SUMMARY = event(SUCCESS, "travellers.notify.trade.sold_summary", 5);
    public static final NotificationDef TRADE_DONATED_SUMMARY = event(SUCCESS, "travellers.notify.trade.donated_summary", 4);

    // --- Mounted horse fallback (TravellerMountSupport, EntityAITravel) ---
    public static final NotificationDef MOUNT_LED_TERRAIN_BLOCKED = event(ALERT, "travellers.notify.mount.led_terrain_blocked", 1);
    public static final NotificationDef MOUNT_LED_SIDESTEP_GAVE_UP = event(ALERT, "travellers.notify.mount.led_sidestep_gave_up", 1);
    public static final NotificationDef MOUNT_LED_LOW_HEALTH = event(ALERT, "travellers.notify.mount.led_low_health", 1);

    // --- Virtual battles (VirtualBattleManager) ---
    public static final NotificationDef BATTLE_UNDER_ATTACK = event(DANGER, "travellers.notify.battle.under_attack", 0);
    public static final NotificationDef BATTLE_FALLEN = event(DANGER, "travellers.notify.battle.fallen", 2);
    public static final NotificationDef BATTLE_FALLEN_MORE = event(DANGER, "travellers.notify.battle.fallen_more", 3);
    public static final NotificationDef BATTLE_WON = event(SUCCESS, "travellers.notify.battle.won", 1);
    public static final NotificationDef BATTLE_LOST = event(DANGER, "travellers.notify.battle.lost", 1);
    public static final NotificationDef BATTLE_BROKEN_OFF = event(ALERT, "travellers.notify.battle.broken_off", 1);
    /** Login toast standing for every battle story queued while the owner was away (the journal keeps each one). */
    public static final NotificationDef BATTLE_AWAY_SUMMARY = reply(ALERT, "travellers.notify.battle.away_summary", 2);

    private static Map<String, NotificationDef> byKey;

    /** The def using this key (the journaled one when a key is shared), or null for a raw key such as a vanilla death message. */
    @Nullable
    public static synchronized NotificationDef byKey(String i18nKey) {
        if (byKey == null) {
            Map<String, NotificationDef> map = new HashMap<>();
            for (Field f : NotificationDefs.class.getFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != NotificationDef.class) continue;
                try {
                    NotificationDef def = (NotificationDef) f.get(null);
                    if (def.journaled || !map.containsKey(def.i18nKey)) map.put(def.i18nKey, def);
                } catch (IllegalAccessException ignored) {
                }
            }
            byKey = map;
        }
        return byKey.get(i18nKey);
    }
}
