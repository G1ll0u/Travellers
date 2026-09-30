package com.jubitus.travellers.notify;

import static com.jubitus.travellers.notify.NotificationType.ERROR;
import static com.jubitus.travellers.notify.NotificationType.EVENT;
import static com.jubitus.travellers.notify.NotificationType.INFO;
import static com.jubitus.travellers.notify.NotificationType.WARNING;

/**
 * Every known notification kind used by {@link NotificationService}. New notifications should
 * get a constant here rather than an inline string key, so a key/arg-count mismatch is caught
 * at the call site instead of failing silently at runtime.
 */
public final class NotificationDefs {

    private NotificationDefs() {
    }

    // --- Worker hiring (MsgHireWorker) ---
    public static final NotificationDef HIRE_SUCCESS = new NotificationDef(INFO, "travellers.notify.hire.success", 0);
    public static final NotificationDef HIRE_ALREADY_HIRED_BY_OTHER = new NotificationDef(ERROR, "travellers.notify.hire.already_hired_by_other", 0);
    public static final NotificationDef HIRE_NEEDS_VILLAGE_CHIEF = new NotificationDef(ERROR, "travellers.notify.hire.needs_village_chief", 0);
    public static final NotificationDef HIRE_NOT_ENOUGH_MONEY = new NotificationDef(ERROR, "travellers.notify.hire.not_enough_money", 1);
    public static final NotificationDef HIRE_PAYMENT_FAILED = new NotificationDef(ERROR, "travellers.notify.hire.payment_failed", 0);
    public static final NotificationDef DISMISS_FOLLOWER = new NotificationDef(INFO, "travellers.notify.hire.dismissed", 0);

    // --- Recruitment (MsgRecruitWorkers, MsgRecruitStartSelectedVillages, GuiWorkerRecruit) ---
    public static final NotificationDef RECRUIT_NOT_YOUR_WORKER = new NotificationDef(ERROR, "travellers.notify.recruit.not_your_worker", 0);
    public static final NotificationDef RECRUIT_BUSY = new NotificationDef(ERROR, "travellers.notify.recruit.busy", 0);
    public static final NotificationDef RECRUIT_NO_VILLAGES_IN_RANGE = new NotificationDef(ERROR, "travellers.notify.recruit.no_villages_in_range", 0);
    public static final NotificationDef RECRUIT_NOT_ENOUGH_FEE = new NotificationDef(ERROR, "travellers.notify.recruit.not_enough_fee", 2);
    public static final NotificationDef RECRUIT_ROUTE_NOT_ENOUGH_FEE = new NotificationDef(ERROR, "travellers.notify.recruit.route_not_enough_fee", 1);
    public static final NotificationDef RECRUIT_PAYMENT_FAILED = new NotificationDef(ERROR, "travellers.notify.recruit.payment_failed", 0);
    public static final NotificationDef RECRUIT_MISSION_STARTED = new NotificationDef(INFO, "travellers.notify.recruit.mission_started", 1);
    public static final NotificationDef RECRUIT_WORKERS_RECRUITED = new NotificationDef(EVENT, "travellers.notify.recruit.workers_recruited", 2);
    public static final NotificationDef RECRUIT_MISSION_COMPLETED = new NotificationDef(EVENT, "travellers.notify.recruit.mission_completed", 0);
    public static final NotificationDef RECRUIT_TOO_CLOSE = new NotificationDef(ERROR, "travellers.worker.recruit.too_close", 1);
    public static final NotificationDef RECRUIT_VILLAGE_TOO_CLOSE = new NotificationDef(ERROR, "travellers.notify.recruit.village_too_close", 1);

    // --- Worker sub-GUI actions (MsgOpenWorkerSubGui) ---
    public static final NotificationDef WORKER_NOT_HIRED_TRANSPORT = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_transport", 0);
    public static final NotificationDef WORKER_NOT_HIRED_INVENTORY = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_inventory", 0);
    public static final NotificationDef WORKER_NOT_HIRED_ORDERS = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_orders", 0);
    public static final NotificationDef WORKER_NOT_HIRED_BEHAVIOR = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_behavior", 0);
    public static final NotificationDef WORKER_NOT_HIRED_RECRUIT = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_recruit", 0);
    public static final NotificationDef WORKER_NOT_HIRED_HOME = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_home", 0);
    public static final NotificationDef WORKER_NOT_HIRED_GO = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_go", 0);
    public static final NotificationDef WORKER_NOT_HIRED_FORMATION = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_formation", 0);
    public static final NotificationDef WORKER_NOT_HIRED_VILLAGE_RELATIONS = new NotificationDef(ERROR, "travellers.notify.worker.not_hired_village_relations", 0);

    // --- Leader assignment (MsgMakeWorkerLeader) ---
    public static final NotificationDef LEADER_RELEASED_FOLLOWERS = new NotificationDef(INFO, "travellers.notify.leader.released_followers", 1);
    public static final NotificationDef LEADER_NOW_LEADING = new NotificationDef(INFO, "travellers.notify.leader.now_leading", 1);
    public static final NotificationDef LEADER_NO_FREE_FOLLOWERS = new NotificationDef(INFO, "travellers.notify.leader.no_free_followers", 0);

    // --- Home / barracks (MsgWorkerGoHome, MsgAssignWorkerBarrack) ---
    public static final NotificationDef HOME_NOT_YOUR_WORKER = new NotificationDef(ERROR, "travellers.notify.home.not_your_worker", 0);
    public static final NotificationDef HOME_NONE_ASSIGNED = new NotificationDef(ERROR, "travellers.notify.home.none_assigned", 0);
    public static final NotificationDef HOME_LONE_BARRACK_NOT_FOUND = new NotificationDef(ERROR, "travellers.notify.home.lone_barrack_not_found", 0);
    public static final NotificationDef HOME_LONE_BARRACK_ASSIGNED = new NotificationDef(INFO, "travellers.notify.home.lone_barrack_assigned", 1);
    public static final NotificationDef HOME_LONE_BARRACK_BED_DESTROYED = new NotificationDef(WARNING, "travellers.notify.home.lone_barrack_bed_destroyed", 2);

    // --- Order gating (a worker busy on a mission can't take Go / Get-Equipment / group orders) ---
    public static final NotificationDef ORDER_WORKER_BUSY = new NotificationDef(ERROR, "travellers.notify.order.worker_busy", 0);
    public static final NotificationDef ORDER_SOME_BUSY_SKIPPED = new NotificationDef(WARNING, "travellers.notify.order.some_busy_skipped", 1);

    // --- Duty zone assignment (MsgAssignWorkerDuty) ---
    public static final NotificationDef DUTY_WORKER_GROUPED = new NotificationDef(ERROR, "travellers.notify.duty.worker_grouped", 0);
    public static final NotificationDef DUTY_ASSIGNED = new NotificationDef(INFO, "travellers.notify.duty.assigned", 1);
    public static final NotificationDef DUTY_NO_BED = new NotificationDef(ERROR, "travellers.notify.duty.no_bed", 1);
    public static final NotificationDef STABLE_BED_DESTROYED = new NotificationDef(WARNING, "travellers.notify.stable.bed_destroyed", 2);
    public static final NotificationDef STABLE_HORSE_TAMED = new NotificationDef(EVENT, "travellers.notify.stable.horse_tamed", 1);
    public static final NotificationDef STABLE_HORSE_SADDLED = new NotificationDef(EVENT, "travellers.notify.stable.horse_saddled", 1);
    public static final NotificationDef STABLE_FOAL_BORN = new NotificationDef(EVENT, "travellers.notify.stable.foal_born", 1);
    public static final NotificationDef STABLE_MOUNT_RECOVERED = new NotificationDef(EVENT, "travellers.notify.stable.mount_recovered", 1);
    public static final NotificationDef STABLE_POOL_TOO_SMALL_TO_EQUIP = new NotificationDef(WARNING, "travellers.notify.stable.pool_too_small_to_equip", 1);

    // --- Go-to orders (MsgWorkerGoTo, MsgWorkerGoToPos, MsgWorkerSetDestination) ---
    public static final NotificationDef GOTO_GOING_TO = new NotificationDef(INFO, "travellers.notify.goto.going_to", 1);
    public static final NotificationDef GOTO_GOING_TO_POS = new NotificationDef(INFO, "travellers.notify.goto.going_to_pos", 0);
    public static final NotificationDef GOTO_NOT_ENOUGH_MONEY = new NotificationDef(ERROR, "travellers.notify.goto.not_enough_money", 1);
    public static final NotificationDef GOTO_PAYMENT_FAILED = new NotificationDef(ERROR, "travellers.notify.goto.payment_failed", 0);
    public static final NotificationDef GOTO_DELIVERY_STARTED_FREE = new NotificationDef(INFO, "travellers.notify.goto.delivery_started_free", 1);
    public static final NotificationDef GOTO_DELIVERY_STARTED_PAID = new NotificationDef(INFO, "travellers.notify.goto.delivery_started_paid", 2);
    public static final NotificationDef GOTO_DELIVERY_STARTED_DONATE = new NotificationDef(INFO, "travellers.notify.goto.delivery_started_donate", 2);

    // --- Mission completion (go-to arrival, get-equipment result) ---
    public static final NotificationDef GOTO_ARRIVED = new NotificationDef(INFO, "travellers.notify.goto.arrived", 0);
    public static final NotificationDef GOTO_GROUP_ARRIVED = new NotificationDef(INFO, "travellers.notify.goto.group_arrived", 1);
    public static final NotificationDef EQUIP_DONE = new NotificationDef(INFO, "travellers.notify.equip.done", 2);
    public static final NotificationDef EQUIP_NOTHING = new NotificationDef(INFO, "travellers.notify.equip.nothing", 0);

    // --- Hostility toggle (MsgSetWorkerHostile) ---
    public static final NotificationDef HOSTILE_NOT_OWNER = new NotificationDef(ERROR, "travellers.notify.hostile.not_owner", 0);
    public static final NotificationDef HOSTILE_PLAYER_NOT_FOUND = new NotificationDef(ERROR, "travellers.notify.hostile.player_not_found", 1);
    public static final NotificationDef HOSTILE_ADDED = new NotificationDef(INFO, "travellers.notify.hostile.added", 1);
    public static final NotificationDef HOSTILE_REMOVED = new NotificationDef(INFO, "travellers.notify.hostile.removed", 1);
    public static final NotificationDef WAR_DECLARED_ON_YOU = new NotificationDef(EVENT, "travellers.notify.hostile.war_declared_on_you", 1);

    // --- Worker lifecycle (EntityTravellerWorker) ---
    public static final NotificationDef WORKER_ARRIVED_HOME = new NotificationDef(EVENT, "travellers.notify.worker.arrived_home", 0);
    public static final NotificationDef WORKER_CARGO_ARRIVED_FULL = new NotificationDef(EVENT, "travellers.notify.worker.cargo_arrived_full", 0);
    public static final NotificationDef WORKER_CARGO_ARRIVED_PARTIAL = new NotificationDef(EVENT, "travellers.notify.worker.cargo_arrived_partial", 0);
    public static final NotificationDef WORKER_ALREADY_ON_JOB = new NotificationDef(INFO, "travellers.notify.worker.already_on_job", 0);
    public static final NotificationDef WORKER_DIED = new NotificationDef(EVENT, "travellers.notify.worker.died", 0);
    public static final NotificationDef WORKER_RETALIATION_TARGET = new NotificationDef(WARNING, "travellers.notify.worker.retaliation_target", 1);

    // --- Trade / cargo (EntityTravellerWorker) ---
    public static final NotificationDef TRADE_REFUSED = new NotificationDef(WARNING, "travellers.notify.trade.refused", 0);
    public static final NotificationDef TRADE_SOLD_SUMMARY = new NotificationDef(EVENT, "travellers.notify.trade.sold_summary", 5);
    public static final NotificationDef TRADE_DONATED_SUMMARY = new NotificationDef(EVENT, "travellers.notify.trade.donated_summary", 4);

    // --- Mounted horse fallback (TravellerMountSupport, EntityAITravel) ---
    public static final NotificationDef MOUNT_LED_TERRAIN_BLOCKED = new NotificationDef(WARNING, "travellers.notify.mount.led_terrain_blocked", 1);
    public static final NotificationDef MOUNT_LED_SIDESTEP_GAVE_UP = new NotificationDef(WARNING, "travellers.notify.mount.led_sidestep_gave_up", 1);
    public static final NotificationDef MOUNT_LED_LOW_HEALTH = new NotificationDef(WARNING, "travellers.notify.mount.led_low_health", 1);

    // --- Virtual battles (VirtualBattleManager) ---
    // Both are also referenced as fallback i18n keys when a death's vanilla-forwarded message
    // (see VanillaDeathMessage) isn't available - the argCount below matches that fallback usage.
    public static final NotificationDef BATTLE_UNDER_ATTACK = new NotificationDef(EVENT, "travellers.notify.battle.under_attack", 0);
    public static final NotificationDef BATTLE_WORKER_KILLED = new NotificationDef(EVENT, "travellers.notify.battle.worker_killed", 1);
}
