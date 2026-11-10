package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;

/** Loaded, stationary adult contact, also used after an independently reserved short delivery. */
final class SettlementFoodSharingScene {
	private SettlementFoodSharingScene() { }
	static final class Session {
		final ServerLevel level;
		final SettlementSavedData data;
		final Settlement camp;
		final Villager giver, recipient;
		final SettlementFoodSharing.Ticket ticket;
		SettlementFoodSharingSessions.Pair<Session> pair;
		Session(ServerLevel level, SettlementSavedData data, Settlement camp, Villager giver, Villager recipient,
			SettlementFoodSharing.Ticket ticket) {
			this.level = level; this.data = data; this.camp = camp; this.giver = giver; this.recipient = recipient;
			this.ticket = ticket;
		}
	}
	static boolean claimed(Villager resident) {
		return resident.level() instanceof ServerLevel level && level.getServer().isSameThread()
			&& SettlementSavedData.get(level).foodSharingSessions().find(resident.getUUID(), level.getGameTime()) != null;
	}
	static Session current(Villager resident, SettlementSavedData data) {
		if (!(resident.level() instanceof ServerLevel level)) return null;
		var pair = data.foodSharingSessions().find(resident.getUUID(), level.getGameTime());
		return pair != null && valid(pair.value) ? pair.value : null;
	}
	static Session poll(Villager giver, SettlementSavedData data, Settlement camp) {
		if (!(giver.level() instanceof ServerLevel level) || !bodyReady(level, data, camp, giver)
			|| !SettlementFoodDeliveryScene.hasSurplus(data, camp, giver)
			|| SettlementFoodDeliveryScene.reserved(giver, data)) return null;
		var registry = data.foodSharingSessions(); long now = level.getGameTime();
		if (registry.find(giver.getUUID(), now) != null || !registry.attempt(giver.getUUID(), now)) return null;
		int checked = 0;
		for (Villager recipient : level.getEntitiesOfClass(Villager.class, giver.getBoundingBox().inflate(2.5))) {
			if (++checked > 16) break;
			if (recipient == giver || SettlementFoodDeliveryScene.reserved(recipient, data) || registry.find(recipient.getUUID(), now) != null
				|| !registry.attempt(recipient.getUUID(), now) || !bodyReady(level, data, camp, recipient)
				|| !contact(level, giver, recipient)) continue;
			var from = giver.position(); var to = recipient.position();
			var fromBag = giver.getInventory(); var toBag = recipient.getInventory();
			var ticket = SettlementFoodSharing.prepare(data, camp, giver.getUUID(), fromBag, recipient.getUUID(), toBag,
				() -> giver.getInventory() == fromBag && recipient.getInventory() == toBag
					&& giver.position().equals(from) && recipient.position().equals(to)
					&& bodyReady(level, data, camp, giver) && bodyReady(level, data, camp, recipient)
					&& contact(level, giver, recipient));
			if (ticket == null) continue;
			Session s = new Session(level, data, camp, giver, recipient, ticket);
			s.pair = registry.reserve(giver.getUUID(), recipient.getUUID(), now, s);
			if (s.pair != null) return s;
			SettlementFoodSharing.cancel(ticket);
		}
		return null;
	}
	/** Called while delivery still owns both endpoints, never through a fresh global poll. */
	static Session receiveDelivery(Villager giver, Villager recipient, SettlementSavedData data, Settlement camp) {
		if (!(giver.level() instanceof ServerLevel level) || !bodyReady(level, data, camp, giver)
			|| !bodyReady(level, data, camp, recipient) || !contact(level, giver, recipient)) return null;
		var from = giver.position(); var to = recipient.position();
		var fromBag = giver.getInventory(); var toBag = recipient.getInventory();
		var ticket = SettlementFoodSharing.prepare(data, camp, giver.getUUID(), fromBag, recipient.getUUID(), toBag,
			() -> giver.getInventory() == fromBag && recipient.getInventory() == toBag
				&& giver.position().equals(from) && recipient.position().equals(to)
				&& bodyReady(level, data, camp, giver) && bodyReady(level, data, camp, recipient)
				&& contact(level, giver, recipient));
		if (ticket == null) return null;
		Session session = new Session(level, data, camp, giver, recipient, ticket);
		session.pair = data.foodSharingSessions().reserve(giver.getUUID(), recipient.getUUID(), level.getGameTime(), session);
		if (session.pair != null) return session;
		SettlementFoodSharing.cancel(ticket); return null;
	}

	static boolean valid(Session s) {
		if (s == null) return false;
		boolean valid = s.data.foodSharingSessions().active(s.pair, s.level.getGameTime()) && SettlementFoodSharing.valid(s.ticket);
		if (!valid) cancel(s);
		return valid;
	}
	static boolean join(Session s, Villager resident) {
		return s != null && (resident == s.giver || resident == s.recipient) && valid(s)
			&& s.data.foodSharingSessions().join(s.pair, resident.getUUID(), s.level.getGameTime());
	}
	static void pulse(Session s, Villager resident) {
		if (!valid(s)) return;
		if (VillagerWorkGoal.foodSharingSessionOf(resident) != s
			|| s.pair.joined(s.pair.speaker) && s.pair.joined(s.pair.listener) && !goalsMatch(s)) { cancel(s); return; }
		if (s.data.foodSharingSessions().pulse(s.pair, resident.getUUID(), s.level.getGameTime(), true)) {
			// Registry ownership is consumed by completion; do not require active() inside the transaction.
			if (SettlementFoodSharing.finish(s.ticket, () -> s.pair.completed() && goalsMatch(s))) {
				s.giver.swing(InteractionHand.MAIN_HAND); s.recipient.swing(InteractionHand.MAIN_HAND);
			}
		}
	}
	private static boolean goalsMatch(Session s) {
		return VillagerWorkGoal.foodSharingSessionOf(s.giver) == s && VillagerWorkGoal.foodSharingSessionOf(s.recipient) == s;
	}
	static void cancel(Session s) {
		if (s != null) { s.data.foodSharingSessions().cancel(s.pair); SettlementFoodSharing.cancel(s.ticket); }
	}
	static void cancelOnUnload(Villager resident) {
		SettlementFoodDeliveryScene.cancelResident(resident);
		if (resident.level() instanceof ServerLevel level) {
			var registry = SettlementSavedData.get(level).foodSharingSessions();
			var pair = registry.find(resident.getUUID(), level.getGameTime());
			if (pair != null) cancel(pair.value);
		}
	}
	static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident) {
		return !resident.isBaby() && !resident.isVehicle() && SettlementPersonalMeal.bodyReady(level, data, camp, resident)
			&& VillagerWorkGoal.readyForFoodSharing(resident) && resident.getNavigation().isDone();
	}
	static boolean contact(ServerLevel level, Villager giver, Villager recipient) {
		if (giver.distanceToSqr(recipient) > 2.5 * 2.5 || giver.getBoundingBox().intersects(recipient.getBoundingBox())) return false;
		BlockPos from = BlockPos.containing(giver.getEyePosition()), to = BlockPos.containing(recipient.getEyePosition());
		BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()) - 1, Math.min(from.getY(), to.getY()) - 1, Math.min(from.getZ(), to.getZ()) - 1);
		BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()) + 1, Math.max(from.getY(), to.getY()) + 1, Math.max(from.getZ(), to.getZ()) + 1);
		for (BlockPos cell : BlockPos.betweenClosed(min, max)) if (!SettlementTeachingScene.available(level, cell)) return false;
		return giver.hasLineOfSight(recipient) && recipient.hasLineOfSight(giver);
	}
}
