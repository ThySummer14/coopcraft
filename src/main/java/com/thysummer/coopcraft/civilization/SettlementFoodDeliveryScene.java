package com.thysummer.coopcraft.civilization;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/** An optional approach only. Only the initiating mover acquires a movement goal; food direction never changes. */
final class SettlementFoodDeliveryScene {
	private SettlementFoodDeliveryScene() { }
	static final class Delivery {
		final ServerLevel level;
		final SettlementSavedData data;
		final Settlement camp;
		final Villager giver, recipient, mover, stationary;
		final BlockPos origin, stationaryCell;
		final List<BlockPos> nodes;
		final Path path;
		final SettlementFoodSharing.Ticket ticket;
		SettlementFoodDeliveryReservations.Lease<Delivery> lease;
		boolean started, settling;
		net.minecraft.world.phys.Vec3 settledPosition;
		long settledSince;
		Delivery(ServerLevel level, SettlementSavedData data, Settlement camp, Villager giver, Villager recipient,
			Villager mover, Villager stationary, List<BlockPos> nodes, SettlementFoodSharing.Ticket ticket) {
			this.level = level; this.data = data; this.camp = camp; this.giver = giver; this.recipient = recipient;
			this.mover = mover; this.stationary = stationary;
			this.origin = mover.blockPosition().immutable(); this.stationaryCell = stationary.blockPosition().immutable();
			this.nodes = List.copyOf(nodes); this.ticket = ticket;
			this.path = new Path(new java.util.ArrayList<>(nodes.stream().map(p -> new Node(p.getX(), p.getY(), p.getZ())).toList()), nodes.getLast(), true);
		}
	}
	static boolean hasSurplus(SettlementSavedData data, Settlement camp, Villager giver) {
		return data.isMember(camp, giver.getUUID()) && !SettlementSimulationService.isSurvivalEnabled(data, camp)
			&& data.needsOf(camp, giver.getUUID()).satiety() > SettlementNeeds.HUNGRY_AT
			&& SettlementFoodSharing.hasSpareRation(data, camp, giver.getUUID(), giver.getInventory());
	}

	static boolean reserved(Villager resident, SettlementSavedData data) {
		return data.foodDeliveryReservations().find(resident.getUUID(), resident.level().getGameTime()) != null;
	}
	static Delivery poll(Villager giver, SettlementSavedData data, Settlement camp) {
		if (!(giver.level() instanceof ServerLevel level) || !hasSurplus(data, camp, giver)
			|| !SettlementFoodSharingScene.bodyReady(level, data, camp, giver)) return null;
		long now = level.getGameTime(); var registry = data.foodDeliveryReservations();
		if (!registry.attempt(giver.getUUID(), now) || data.foodSharingSessions().find(giver.getUUID(), now) != null) return null;
		int checked = 0;
		for (Villager recipient : level.getEntitiesOfClass(Villager.class, giver.getBoundingBox().inflate(8, 0, 8))) {
			if (++checked > 16) break;
			if (recipient == giver || !registry.attempt(recipient.getUUID(), now)
				|| data.foodSharingSessions().find(recipient.getUUID(), now) != null
				|| giver.blockPosition().getY() != recipient.blockPosition().getY() || giver.distanceToSqr(recipient) > 64
				|| !SettlementFoodSharingScene.bodyReady(level, data, camp, recipient)
				|| !visible(level, giver, recipient)) continue;
			Delivery delivery = prepare(level, data, camp, giver, recipient, giver, recipient);
			if (delivery != null) return delivery;
		}
		return null;
	}
	/** Only remembered, personally experienced donors are considered, never a village-wide search. */
	static Delivery pollRequest(Villager recipient, SettlementSavedData data, Settlement camp) {
		if (!(recipient.level() instanceof ServerLevel level) || !requestReady(level, data, camp, recipient)
			|| !SettlementFoodSharingScene.bodyReady(level, data, camp, recipient)) return null;
		long now = level.getGameTime(); var registry = data.foodDeliveryReservations();
		if (!registry.attempt(recipient.getUUID(), now) || data.foodSharingSessions().find(recipient.getUUID(), now) != null) return null;
		for (var id : SettlementFoodSharingMemories.rememberedGivers(data, camp, recipient.getUUID())) {
			if (!(level.getEntity(id) instanceof Villager giver) || !registry.attempt(id, now)
				|| data.foodSharingSessions().find(id, now) != null || !hasSurplus(data, camp, giver)
				|| !SettlementFoodSharingScene.bodyReady(level, data, camp, giver) || !visible(level, giver, recipient)) continue;
			Delivery delivery = prepare(level, data, camp, giver, recipient, recipient, giver);
			if (delivery != null) return delivery;
		}
		return null;
	}
	static boolean requestReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager recipient) {
		return requestEligible(data, camp, recipient.getUUID(), recipient.getInventory(), SettlementStockpile.getStockpile(level, data, camp));
	}
	/** Pure selection gate; explicit camp and current containers, no world or goal state. */
	static boolean requestEligible(SettlementSavedData data, Settlement camp, java.util.UUID recipient,
		net.minecraft.world.SimpleContainer bag, net.minecraft.world.Container stockpile) {
		if (!data.isMember(camp, recipient) || SettlementSimulationService.isSurvivalEnabled(data, camp)
			|| data.needsOf(camp, recipient).satiety() > SettlementNeeds.HUNGRY_AT
			|| SettlementFoodSharing.ordinaryFoodPoints(bag) != 0
			|| SettlementNeeds.hasFood(bag, stockpile)) return false;
		for (int slot = 0; slot < bag.getContainerSize(); slot++)
			if (bag.getItem(slot).isEmpty()) return true;
		return false;
	}
	private static Delivery prepare(ServerLevel level, SettlementSavedData data, Settlement camp,
		Villager giver, Villager recipient, Villager mover, Villager stationary) {
		var fromBag = giver.getInventory(); var toBag = recipient.getInventory(); var fixed = stationary.position();
		// The original real-food ticket pins both inventories throughout either direction of approach.
		var ticket = SettlementFoodSharing.prepare(data, camp, giver.getUUID(), fromBag, recipient.getUUID(), toBag,
			() -> giver.getInventory() == fromBag && recipient.getInventory() == toBag && stationary.position().equals(fixed)
				&& movingBodyReady(level, data, camp, mover) && SettlementFoodSharingScene.bodyReady(level, data, camp, stationary)
				&& (mover == giver || requestReady(level, data, camp, recipient)) && visible(level, giver, recipient));
		if (ticket == null) return null;
		var nodes = SettlementFoodDeliveryRoute.find(mover.blockPosition(), stationary.blockPosition(), probe(level, data, camp));
		if (nodes.isEmpty()) { SettlementFoodSharing.cancel(ticket); return null; }
		Delivery delivery = new Delivery(level, data, camp, giver, recipient, mover, stationary, nodes, ticket);
		delivery.lease = data.foodDeliveryReservations().reserve(giver.getUUID(), recipient.getUUID(), level.getGameTime(), delivery);
		if (delivery.lease != null) return delivery;
		SettlementFoodSharing.cancel(ticket); return null;
	}
	private static boolean movingBodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager giver) {
		return !giver.isBaby() && !giver.isVehicle() && SettlementPersonalMeal.bodyReady(level, data, camp, giver)
			&& VillagerWorkGoal.readyForFoodSharing(giver);
	}
	private static boolean visible(ServerLevel level, Villager giver, Villager recipient) {
		if (giver.distanceToSqr(recipient) > 64 || giver.blockPosition().getY() != recipient.blockPosition().getY()) return false;
		BlockPos from = BlockPos.containing(giver.getEyePosition()), to = BlockPos.containing(recipient.getEyePosition());
		for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(Math.min(from.getX(), to.getX()) - 1, Math.min(from.getY(), to.getY()) - 1, Math.min(from.getZ(), to.getZ()) - 1),
			new BlockPos(Math.max(from.getX(), to.getX()) + 1, Math.max(from.getY(), to.getY()) + 1, Math.max(from.getZ(), to.getZ()) + 1)))
			if (!SettlementTeachingScene.available(level, pos)) return false;
		return giver.hasLineOfSight(recipient) && recipient.hasLineOfSight(giver);
	}
	private static SettlementMaterialScouting.Probe probe(ServerLevel level, SettlementSavedData data, Settlement camp) {
		return new SettlementMaterialScouting.Probe() {
			public boolean inBounds(BlockPos p) { return SettlementMaterialScouting.withinCamp(p, camp)
				&& !level.isOutsideBuildHeight(p) && level.getWorldBorder().isWithinBounds(p); }
			public boolean loaded(BlockPos p) { return level.isLoaded(p); }
			public boolean ticking(BlockPos p) { return level.isPositionEntityTicking(p); }
			public boolean blocked(BlockPos p) { return data.isResidentModificationBlocked(p); }
			public BlockState state(BlockPos p) { return level.getBlockState(p); }
		};
	}
	static boolean valid(Delivery d) {
		if (d == null) return false;
		boolean valid = d.data.foodDeliveryReservations().active(d.lease, d.level.getGameTime())
			&& SettlementFoodSharing.valid(d.ticket)
			&& d.mover.blockPosition().getY() == d.origin.getY() && d.nodes.contains(d.mover.blockPosition())
			&& SettlementFoodDeliveryRoute.valid(d.nodes, d.origin, d.stationaryCell, probe(d.level, d.data, d.camp))
			&& pathUnchanged(d) && (d.settling ? d.mover.getNavigation().isDone()
				: !d.started || d.mover.getNavigation().getPath() == d.path);
		if (!valid) cancel(d);
		return valid;
	}
	private static boolean pathUnchanged(Delivery d) {
		if (!d.path.canReach() || d.path.getNodeCount() != d.nodes.size()) return false;
		for (int i = 0; i < d.nodes.size(); i++) if (!d.path.getNodePos(i).equals(d.nodes.get(i))) return false;
		return true;
	}

	/** Stop first and let residual movement settle before pinning the stationary exchange ticket. */
	static boolean settled(Delivery d) {
		if (!valid(d) || !d.mover.blockPosition().equals(d.nodes.getLast())) return false;
		long now = d.level.getGameTime();
		if (!d.settling) {
			d.mover.getNavigation().stop(); d.settling = true;
			d.settledPosition = d.mover.position(); d.settledSince = now; return false;
		}
		if (!d.mover.position().equals(d.settledPosition)) {
			d.settledPosition = d.mover.position(); d.settledSince = now; return false;
		}
		return now > d.settledSince;
	}

	/** Returns the exact new sharing session; neither endpoint is ever offered to a global poll. */
	static SettlementFoodSharingScene.Session arrive(Delivery d) {
		if (!valid(d) || !d.settling || !d.mover.position().equals(d.settledPosition)
			|| d.level.getGameTime() <= d.settledSince || !d.mover.blockPosition().equals(d.nodes.getLast()) || !SettlementFoodSharingScene.contact(d.level, d.giver, d.recipient)) return null;
		d.mover.getNavigation().stop();
		final SettlementFoodSharingScene.Session[] result = new SettlementFoodSharingScene.Session[1];
		try {
			boolean transferred = d.data.foodDeliveryReservations().transfer(d.lease, d.level.getGameTime(), () -> {
				if (!SettlementFoodSharing.valid(d.ticket)) return false;
				result[0] = SettlementFoodSharingScene.receiveDelivery(d.giver, d.recipient, d.data, d.camp);
				return result[0] != null;
			});
			if (!transferred) { SettlementFoodSharingScene.cancel(result[0]); return null; }
			return result[0];
		} catch (RuntimeException | Error failure) {
			SettlementFoodSharingScene.cancel(result[0]); throw failure;
		} finally { SettlementFoodSharing.cancel(d.ticket); }
	}
	static boolean start(Delivery d) {
		if (!valid(d)) return false;
		d.started = true;
		return d.mover.getNavigation().moveTo(d.path, d.data.personalityOf(d.camp, d.mover.getUUID()).movementSpeed()) && valid(d);
	}
	static void cancel(Delivery d) {
		if (d == null) return;
		d.data.foodDeliveryReservations().cancel(d.lease); SettlementFoodSharing.cancel(d.ticket);
		if (d.started && d.mover.getNavigation().getPath() == d.path) d.mover.getNavigation().stop();
	}
	static void cancelResident(Villager resident) {
		if (!(resident.level() instanceof ServerLevel level)) return;
		var pair = SettlementSavedData.get(level).foodDeliveryReservations().find(resident.getUUID(), level.getGameTime());
		if (pair != null) cancel(pair.value);
	}
}
