package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** A short-lived claim on one real personal ration. No communal or simulation fallback. */
final class SettlementPersonalMeal {
	private SettlementPersonalMeal() { }
	static boolean ordinary(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getCount() > 0 && stack.getCount() <= stack.getMaxStackSize()
			&& stack.getComponentsPatch().isEmpty()
			&& !SettlementFoodAge.isMarked(stack) && !SettlementShearingCargo.isMarked(stack)
			&& !SettlementShearsLoan.isMarked(stack) && !SettlementWorkbenchCargo.isMarked(stack)
			&& !ConstructionSupply.isMarked(stack) && !SettlementCargo.isMaterial(stack)
			&& SettlementFood.points(stack.getItem()) > 0;
	}

	static final class Ticket {
		final SettlementSavedData data;
		final Settlement camp;
		final UUID actor;
		final Object member;
		final SettlementSimulationState simulation;
		final long modeVersion;
		final SimpleContainer bag;
		final ItemStack[] identities, snapshots;
		final MemberNeeds needs;
		final int slot;
		final BooleanSupplier presence;
		boolean used, checking, cancelled;
		Ticket(SettlementSavedData data, Settlement camp, UUID actor, SimpleContainer bag, int slot, BooleanSupplier presence) {
			this.data = data; this.camp = camp; this.actor = actor; this.bag = bag; this.slot = slot; this.presence = presence;
			member = data.memberIdentity(actor); simulation = data.simulationOf(camp).orElse(null);
			modeVersion = simulation == null ? 0 : simulation.foodRequestModeVersion();
			needs = data.needsOf(camp, actor);
			identities = new ItemStack[bag.getContainerSize()]; snapshots = new ItemStack[identities.length];
			for (int i = 0; i < identities.length; i++) { identities[i] = bag.getItem(i); snapshots[i] = identities[i].copy(); }
		}
		void cancel() { cancelled = true; }
	}
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, SimpleContainer bag, BooleanSupplier presence) {
		if (data == null || camp == null || actor == null || bag == null || presence == null
			|| !data.canonicalSettlement(camp) || !data.isMember(camp, actor)
			|| SettlementSimulationService.isSurvivalEnabled(data, camp)
			|| data.needsOf(camp, actor).satiety() >= SettlementNeeds.FULL_AT) return null;
		for (int i = 0; i < bag.getContainerSize(); i++) if (ordinary(bag.getItem(i))) {
			Ticket ticket = new Ticket(data, camp, actor, bag, i, presence);
			return valid(ticket) ? ticket : null;
		}
		return null;
	}
	private static boolean contextUnchanged(Ticket t) {
		return !t.cancelled && t.data.canonicalSettlement(t.camp) && t.member != null && t.member == t.data.memberIdentity(t.actor)
			&& t.data.isMember(t.camp, t.actor) && t.simulation == t.data.simulationOf(t.camp).orElse(null)
			&& (t.simulation == null || t.modeVersion == t.simulation.foodRequestModeVersion())
			&& !SettlementSimulationService.isSurvivalEnabled(t.data, t.camp);
	}
	static boolean contextValid(Ticket t) {
		if (t == null || t.checking) return false;
		t.checking = true;
		try { return contextUnchanged(t) && t.presence.getAsBoolean() && contextUnchanged(t); }
		finally { t.checking = false; }
	}
	private static boolean unchanged(Ticket t) {
		if (t.used || !contextUnchanged(t) || t.needs != t.data.needsOf(t.camp, t.actor)
			|| t.bag.getContainerSize() != t.identities.length) return false;
		for (int i = 0; i < t.identities.length; i++)
			if (t.identities[i] != t.bag.getItem(i) || !ItemStack.matches(t.snapshots[i], t.bag.getItem(i))) return false;
		return ordinary(t.bag.getItem(t.slot));
	}

	static boolean valid(Ticket t) {
		if (t == null || t.checking) return false;
		t.checking = true;
		try { return unchanged(t) && t.presence.getAsBoolean() && unchanged(t); }
		finally { t.checking = false; }
	}
	/** Callbacks precede the final snapshot check. Mutation assumes non-reentrant vanilla SimpleContainer. */
	static boolean finish(Ticket t, BooleanSupplier arrival) {
		if (t == null || t.checking) return false;
		t.checking = true;
		try {
			if (arrival == null || !unchanged(t) || !t.presence.getAsBoolean() || !arrival.getAsBoolean() || !t.presence.getAsBoolean() || !unchanged(t)) return false;
			t.used = true;
			int restored = SettlementNeeds.restoredSatiety(t.needs.satiety(), SettlementFood.points(t.identities[t.slot].getItem()));
			// Shrink the pinned real stack, never substitute a copied stack or compensate after failure.
			t.identities[t.slot].shrink(1);
			t.data.setSatiety(t.camp, t.actor, restored);
			t.bag.setChanged();
			return true;
		} finally { t.used = true; t.checking = false; }
	}

	/** One observed frame per game tick; gaps/rewinds cancel rather than catching up. */
	static final class Clock {
		long last;
		int biteTicks, totalTicks;
		boolean begun, cancelled;
		int frame(long now) {
			if (cancelled) return -1;
			if (begun && now == last) return 0;
			if (begun && (last == Long.MAX_VALUE || now != last + 1)) { cancelled = true; return -1; }
			begun = true; last = now;
			if (++totalTicks > 800) { cancelled = true; return -1; }
			if (++biteTicks < 40) return 0;
			biteTicks = 0; return 1;
		}
	}

	static Ticket prepare(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident) {
		var place = resident.position();
		var bag = resident.getInventory();
		return prepare(data, camp, resident.getUUID(), bag, () -> resident.getInventory() == bag
			&& resident.position().equals(place) && bodyReady(level, data, camp, resident));
	}
	static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident) {
		if (level == null || data == null || camp == null || resident == null || !level.getServer().isSameThread()
			|| level.getServer().getLevel(level.dimension()) != level || SettlementSavedData.get(level) != data
			|| resident.level() != level || level.getEntity(resident.getUUID()) != resident
			|| !resident.isAlive() || resident.isRemoved() || resident.isSleeping() || resident.isTrading()
			|| resident.isNoAi() || resident.isPassenger() || resident.getTarget() != null || resident.hurtTime > 0
			|| resident.isOnFire() || resident.hasEffect(MobEffects.POISON) || resident.hasEffect(MobEffects.WITHER)
			|| resident.getBrain().isActive(Activity.PANIC) || resident.getBrain().isActive(Activity.REST)
			|| resident.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY)
			|| resident.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE)
			|| !resident.onGround() || resident.isInWaterOrBubble() || resident.isInLava()
			|| resident.distanceToSqr(camp.center().getCenter()) > 64.0 * 64.0
			|| !SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, resident.getUUID()), level.dimension(), data, camp)) return false;
		AABB body = resident.getBoundingBox();
		var border = level.getWorldBorder();
		if (!boundedBody(body) || body.minY < level.getMinBuildHeight() || body.maxY > level.getMaxBuildHeight()
			|| body.minX < border.getMinX() || body.maxX > border.getMaxX()
			|| body.minZ < border.getMinZ() || body.maxZ > border.getMaxZ()) return false;
		// Preflight every body and support cell before reading collision shapes; no world writes or path search.
		BlockPos min = BlockPos.containing(body.minX, body.minY - 0.02, body.minZ);
		BlockPos max = BlockPos.containing(body.maxX, body.maxY, body.maxZ);
		for (BlockPos cell : BlockPos.betweenClosed(min, max)) if (!SettlementTeachingScene.available(level, cell)) return false;
		AABB interior = body.deflate(0.000001);
		for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
			var state = level.getBlockState(cell);
			if (!state.getFluidState().isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) return false;
			for (AABB shape : state.getCollisionShape(level, cell).toAabbs())
				if (shape.move(cell.getX(), cell.getY(), cell.getZ()).intersects(interior)) return false;
		}
		BlockPos support = BlockPos.containing((body.minX + body.maxX) / 2, body.minY - 0.02, (body.minZ + body.maxZ) / 2);
		return supported(body, support, level.getBlockState(support).getCollisionShape(level, support).toAabbs());
	}

	static boolean boundedBody(AABB body) {
		return body != null && Double.isFinite(body.minX) && Double.isFinite(body.minY) && Double.isFinite(body.minZ)
			&& Double.isFinite(body.maxX) && Double.isFinite(body.maxY) && Double.isFinite(body.maxZ)
			&& body.getXsize() > 0 && body.getXsize() <= 2 && body.getZsize() > 0 && body.getZsize() <= 2
			&& body.getYsize() > 0 && body.getYsize() <= 3;
	}
	/** Stable collision support includes farmland/path tops and slabs without requiring a full cube. */
	static boolean supported(AABB body, BlockPos position, java.util.List<AABB> shapes) {
		double x = (body.minX + body.maxX) / 2, z = (body.minZ + body.maxZ) / 2;
		for (AABB local : shapes) {
			AABB shape = local.move(position.getX(), position.getY(), position.getZ());
			if (Math.abs(shape.maxY - body.minY) <= 0.02 && shape.minX <= x && shape.maxX >= x
				&& shape.minZ <= z && shape.maxZ >= z) return true;
		}
		return false;
	}
}
