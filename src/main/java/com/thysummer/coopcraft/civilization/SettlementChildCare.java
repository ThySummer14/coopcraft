package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** One physical surplus item feeds a nearby child. Server-thread, standard non-reentrant containers only. */
final class SettlementChildCare {
	private SettlementChildCare() { }

	static final class Ticket {
		private final SettlementSavedData data;
		private final Settlement camp;
		private final UUID donorId, childId;
		private final Object donorMember, childMember;
		private final SettlementSimulationState simulation;
		private final long mode;
		private final SimpleContainer donorBag, childBag;
		private final BooleanSupplier presence, contact;
		private final BlockPos position;
		private final Villager donor, child;
		private boolean used, cancelled, finishing;
		private Ticket(SettlementSavedData data, Settlement camp, UUID donorId, UUID childId,
			SimpleContainer donorBag, SimpleContainer childBag, BlockPos position,
			BooleanSupplier presence, BooleanSupplier contact, Villager donor, Villager child) {
			this.data = data; this.camp = camp; this.donorId = donorId; this.childId = childId;
			this.donorBag = donorBag; this.childBag = childBag; this.position = position.immutable();
			this.presence = presence; this.contact = contact; this.donor = donor; this.child = child;
			donorMember = data.memberIdentity(donorId); childMember = data.memberIdentity(childId);
			simulation = data.simulationOf(camp).orElse(null); mode = simulation == null ? 0 : simulation.foodRequestModeVersion();
		}
		BlockPos position() { return position; }
		Villager recipient() { return child; }
	}

	private static boolean eligible(SettlementSavedData data, Settlement camp, UUID donor, UUID child,
		Container donorBag, Container childBag) {
		return data != null && camp != null && donor != null && child != null && !donor.equals(child)
			&& donorBag != null && childBag != null && donorBag != childBag && data.canonicalSettlement(camp)
			&& data.isMember(camp, donor) && data.isMember(camp, child)
			&& !SettlementSimulationService.isSurvivalEnabled(data, camp)
			&& data.needsOf(camp, donor).satiety() > SettlementNeeds.HUNGRY_AT
			&& data.needsOf(camp, child).satiety() <= SettlementNeeds.HUNGRY_AT
			&& !SettlementNeeds.hasFood(childBag, null) && offeredSlot(data, camp, donor, donorBag) >= 0;
	}
	private static int offeredSlot(SettlementSavedData data, Settlement camp, UUID donor, Container bag) {
		long surplus = (long) SettlementStockpile.communalFoodPoints(bag) - SettlementFoodCargo.retainedFoodPoints(data, camp, donor);
		for (int slot = 0; slot < bag.getContainerSize(); slot++) {
			ItemStack stack = bag.getItem(slot);
			if (SettlementFoodCargo.isFood(stack) && stack.getComponentsPatch().isEmpty()
				&& SettlementFood.points(stack) > 0 && surplus >= SettlementFood.points(stack)) return slot;
		}
		return -1;
	}
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID donor, UUID child,
		SimpleContainer donorBag, SimpleContainer childBag, BlockPos position, BooleanSupplier presence, BooleanSupplier contact) {
		return prepare(data, camp, donor, child, donorBag, childBag, position, presence, contact, null, null);
	}
	private static Ticket prepare(SettlementSavedData data, Settlement camp, UUID donor, UUID child,
		SimpleContainer donorBag, SimpleContainer childBag, BlockPos position, BooleanSupplier presence,
		BooleanSupplier contact, Villager giver, Villager recipient) {
		if (position == null || presence == null || contact == null || !eligible(data, camp, donor, child, donorBag, childBag)) return null;
		Ticket ticket = new Ticket(data, camp, donor, child, donorBag, childBag, position, presence, contact, giver, recipient);
		return valid(ticket) ? ticket : null;
	}
	private static boolean authority(Ticket t) {
		return !t.used && !t.cancelled && t.data.canonicalSettlement(t.camp) && t.donorMember != null && t.childMember != null
			&& t.donorMember == t.data.memberIdentity(t.donorId) && t.childMember == t.data.memberIdentity(t.childId)
			&& t.simulation == t.data.simulationOf(t.camp).orElse(null)
			&& (t.simulation == null || t.mode == t.simulation.foodRequestModeVersion())
			&& eligible(t.data, t.camp, t.donorId, t.childId, t.donorBag, t.childBag);
	}
	static boolean valid(Ticket ticket) {
		return ticket != null && !ticket.used && authority(ticket) && ticket.presence.getAsBoolean() && authority(ticket);
	}
	static void cancel(Ticket ticket) { if (ticket != null) { ticket.used = true; ticket.cancelled = true; } }

	/** Every final attempt is consumed, even a failed arrival; no further authorization callbacks occur between the two writes. */
	static boolean finish(Ticket t, BooleanSupplier actualArrival) {
		if (t == null || t.used || t.finishing) return false;
		t.finishing = true;
		try {
		if (actualArrival == null || !authority(t)) return false;
		List<ItemStack> bag = snapshot(t.donorBag), childBag = snapshot(t.childBag);
		int donorSatiety = t.data.needsOf(t.camp, t.donorId).satiety();
		int childSatiety = t.data.needsOf(t.camp, t.childId).satiety();
		int slot = offeredSlot(t.data, t.camp, t.donorId, t.donorBag);
		if (slot < 0 || !t.presence.getAsBoolean() || !t.contact.getAsBoolean() || !actualArrival.getAsBoolean()
			|| !t.presence.getAsBoolean() || !t.contact.getAsBoolean() || !authority(t)
			|| donorSatiety != t.data.needsOf(t.camp, t.donorId).satiety()
			|| childSatiety != t.data.needsOf(t.camp, t.childId).satiety()
			|| !unchanged(t.donorBag, bag) || !unchanged(t.childBag, childBag)) return false;
		ItemStack remainder = bag.get(slot).copy(); remainder.shrink(1);
		int restored = SettlementNeeds.restoredSatiety(childSatiety, SettlementFood.points(bag.get(slot)));
		t.donorBag.setItem(slot, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
		t.data.setSatiety(t.camp, t.childId, restored);
		t.donorBag.setChanged();
		return true;
		} finally { t.used = true; t.finishing = false; }
	}
	private static List<ItemStack> snapshot(Container bag) {
		List<ItemStack> result = new ArrayList<>();
		for (int slot = 0; slot < bag.getContainerSize(); slot++) result.add(bag.getItem(slot).copy());
		return result;
	}
	private static boolean unchanged(Container bag, List<ItemStack> original) {
		if (bag.getContainerSize() != original.size()) return false;
		for (int slot = 0; slot < original.size(); slot++) if (!ItemStack.matches(original.get(slot), bag.getItem(slot))) return false;
		return true;
	}

	static List<Villager> candidates(ServerLevel level, SettlementSavedData data, Settlement camp, Villager giver) {
		if (!bodyReady(level, data, camp, giver, false)
			|| SettlementSimulationService.isSurvivalEnabled(data, camp)
			|| data.needsOf(camp, giver.getUUID()).satiety() <= SettlementNeeds.HUNGRY_AT
			|| offeredSlot(data, camp, giver.getUUID(), giver.getInventory()) < 0) return List.of();
		// Vanilla's loaded AABB enumeration scales with local density; deep checks are limited to sixteen.
		List<Villager> candidates = level.getEntitiesOfClass(Villager.class, giver.getBoundingBox().inflate(12, 4, 12), child ->
			child != giver && child.isBaby() && !child.blockPosition().equals(giver.blockPosition()) && near(giver, child))
			.stream().sorted(Comparator.comparingDouble((Villager child) -> giver.distanceToSqr(child))
				.thenComparing(Villager::getUUID)).limit(16).toList();
		return candidates.stream().filter(child -> bodyReady(level, data, camp, child, true)
			&& eligible(data, camp, giver.getUUID(), child.getUUID(), giver.getInventory(), child.getInventory())).toList();
	}
	static Ticket prepare(ServerLevel level, SettlementSavedData data, Settlement camp, Villager giver, Villager child) {
		if (giver == null || child == null) return null;
		BlockPos initial = child.blockPosition().immutable();
		return prepare(data, camp, giver.getUUID(), child.getUUID(), giver.getInventory(), child.getInventory(), initial,
			() -> bodyReady(level, data, camp, giver, false) && bodyReady(level, data, camp, child, true)
				&& initial.equals(child.blockPosition()) && near(giver, child),
			() -> contact(level, giver, child, initial), giver, child);
	}
	static boolean valid(Ticket ticket, Villager giver) {
		return ticket != null && giver != null && ticket.donor == giver && ticket.donorBag == giver.getInventory()
			&& ticket.child != null && ticket.childBag == ticket.child.getInventory() && valid(ticket);
	}
	/** Read-only live contact check, shared by every counted Goal tick and final completion. */
	static boolean contactReady(Ticket ticket, Villager giver) {
		return valid(ticket, giver) && ticket.contact.getAsBoolean();
	}
	static boolean finish(Ticket ticket, Villager giver, BooleanSupplier actualArrival) {
		if (ticket == null) return false;
		if (!valid(ticket, giver)) { cancel(ticket); return false; }
		return finish(ticket, actualArrival);
	}
	private static boolean near(Villager giver, Villager child) {
		double dx = giver.getX() - child.getX(), dz = giver.getZ() - child.getZ();
		return Double.isFinite(dx) && Double.isFinite(dz) && dx * dx + dz * dz <= 144 && Math.abs(giver.getY() - child.getY()) <= 4;
	}
	private static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager v, boolean baby) {
		return level != null && data != null && camp != null && v != null && level.getServer().isSameThread()
			&& level.getServer().getLevel(level.dimension()) == level && SettlementSavedData.get(level) == data
			&& data.canonicalSettlement(camp) && v.level() == level && level.getEntity(v.getUUID()) == v
			&& v.isBaby() == baby && SettlementTeachingScene.residentAllowed(v.isAlive(), v.isRemoved(), false,
				v.getHealth(), v.getMaxHealth(), v.isSleeping(), v.isTrading(), v.isNoAi(), v.isPassenger(), v.getTarget() != null,
				v.isOnFire() || v.hasEffect(MobEffects.POISON) || v.hasEffect(MobEffects.WITHER)
					|| v.getBrain().isActive(Activity.PANIC) || v.getBrain().isActive(Activity.REST)
					|| v.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY) || v.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE))
			&& !v.isVehicle() && v.hurtTime == 0 && v.onGround() && !v.isInWaterOrBubble() && !v.isInLava()
			&& SettlementTeachingScene.available(level, v.blockPosition())
			&& SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, v.getUUID()), level.dimension(), data, camp)
			&& (!baby || VillagerWorkGoal.readyForChildCareRecipient(v));
	}
	private static boolean contact(ServerLevel level, Villager giver, Villager child, BlockPos initial) {
		if (!initial.equals(child.blockPosition()) || giver.distanceToSqr(child) > 6.25) return false;
		ConstructionApproach.Probe probe = new ConstructionApproach.Probe() {
			public boolean inBounds(BlockPos pos) { return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos); }
			public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
			public boolean ticking(BlockPos pos) { return level.isPositionEntityTicking(pos); }
			public BlockState state(BlockPos pos) { return level.getBlockState(pos); }
		};
		for (BlockPos cell : List.of(initial, initial.above(), initial.below()))
			if (!SettlementTeachingScene.available(level, cell)) return false;
		if (!probe.state(initial).isAir() || !probe.state(initial.above()).isAir()
			|| !ConstructionSite.safeSupport(probe.state(initial.below()))
			|| Math.abs(child.getY() - initial.getY()) > 0.125
			|| !SettlementCargoApproach.arrived(initial, giver.position(), probe)) return false;
		// Preflight the entire short eye-ray box before vanilla line-of-sight can read blocks.
		BlockPos from = BlockPos.containing(giver.getEyePosition()), to = BlockPos.containing(child.getEyePosition());
		BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()) - 1, Math.min(from.getY(), to.getY()) - 1, Math.min(from.getZ(), to.getZ()) - 1);
		BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()) + 1, Math.max(from.getY(), to.getY()) + 1, Math.max(from.getZ(), to.getZ()) + 1);
		for (BlockPos cell : BlockPos.betweenClosed(min, max)) if (!SettlementTeachingScene.available(level, cell)) return false;
		return giver.hasLineOfSight(child) && child.hasLineOfSight(giver);
	}
}
