package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Physical surplus becomes one planted crop. No new food, imports, seed custody or saved jobs. */
final class SettlementSowing {
	private SettlementSowing() { }

	interface Probe {
		boolean inBounds(BlockPos pos);
		boolean loaded(BlockPos pos);
		boolean ticking(BlockPos pos);
		BlockState state(BlockPos pos);
		boolean canGrow(BlockPos pos);
		boolean plant(BlockPos pos, BlockState expectedAir, BlockState expectedSoil);
	}

	static final class Ticket {
		private final SettlementSavedData data;
		private final Settlement camp;
		private final UUID actor;
		private final Object member;
		private final SettlementSowingPlots.Permit permit;
		private final SettlementSimulationState simulation;
		private final long modeVersion;
		private final SimpleContainer inventory;
		private final BooleanSupplier identityAndPresence;
		private final Probe probe;
		private final Villager resident;
		private boolean used;
		private Ticket(SettlementSavedData data, Settlement camp, UUID actor,
			SettlementSowingPlots.Permit permit, SimpleContainer inventory,
			BooleanSupplier identityAndPresence, Probe probe, Villager resident) {
			this.data = data; this.camp = camp; this.actor = actor; this.permit = permit;
			member = data.memberIdentity(actor); simulation = data.simulationOf(camp).orElse(null);
			modeVersion = simulation == null ? 0 : simulation.foodRequestModeVersion();
			this.inventory = inventory; this.identityAndPresence = identityAndPresence;
			this.probe = probe; this.resident = resident;
		}
		BlockPos position() { return permit.position(); }
	}

	/** Preserve the same personality-dependent ordinary-food floor used by real surplus returns. */
	static boolean hasSeed(SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		if (data == null || camp == null || actor == null || inventory == null || !data.canonicalSettlement(camp)
			|| !data.isMember(camp, actor) || SettlementSimulationService.isSurvivalEnabled(data, camp)) return false;
		int points = SettlementFood.points(Items.POTATO);
		if (points <= 0 || (long) SettlementStockpile.communalFoodPoints(inventory) - points
			< SettlementFoodCargo.retainedFoodPoints(data, camp, actor)) return false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++)
			if (SettlementCropHarvest.ordinaryPotato(inventory.getItem(slot))) return true;
		return false;
	}

	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, BlockPos pos,
		SimpleContainer inventory, BooleanSupplier identityAndPresence, Probe probe) {
		return prepare(data, camp, actor, pos, inventory, identityAndPresence, probe, null);
	}
	private static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, BlockPos pos,
		SimpleContainer inventory, BooleanSupplier identityAndPresence, Probe probe, Villager resident) {
		if (identityAndPresence == null || probe == null || pos == null
			|| !hasSeed(data, camp, actor, inventory) || !identityAndPresence.getAsBoolean()) return null;
		var permit = data.sowingPermitOf(camp, pos).orElse(null);
		if (permit == null) return null;
		Ticket ticket = new Ticket(data, camp, actor, permit, inventory, identityAndPresence, probe, resident);
		return valid(ticket) ? ticket : null;
	}
	private static boolean authority(Ticket t) {
		return t != null && !t.used && t.data.canonicalSettlement(t.camp)
			&& t.member != null && t.member == t.data.memberIdentity(t.actor) && t.data.isMember(t.camp, t.actor)
			&& t.data.sowingPermitOf(t.camp, t.position()).orElse(null) == t.permit
			&& t.data.canUseSowingPlot(t.camp, t.position())
			&& t.simulation == t.data.simulationOf(t.camp).orElse(null)
			&& (t.simulation == null || t.simulation.foodRequestModeVersion() == t.modeVersion)
			&& hasSeed(t.data, t.camp, t.actor, t.inventory) && t.identityAndPresence.getAsBoolean();
	}
	static boolean valid(Ticket ticket) {
		return authority(ticket) && siteReady(ticket.position(), ticket.probe) && authority(ticket);
	}
	/** All immediate update/light neighbours are preflighted before reading any block or survival rule. */
	static boolean siteReady(BlockPos pos, Probe probe) {
		if (pos == null || pos.getY() <= Integer.MIN_VALUE + 1 || pos.getY() >= Integer.MAX_VALUE - 1) return false;
		List<BlockPos> cells = new ArrayList<>(); cells.add(pos); cells.add(pos.below());
		for (Direction direction : Direction.values()) cells.add(pos.relative(direction));
		for (BlockPos cell : cells)
			if (!probe.inBounds(cell) || !probe.loaded(cell) || !probe.ticking(cell)) return false;
		return probe.state(pos).isAir() && probe.state(pos.below()).is(Blocks.FARMLAND) && probe.canGrow(pos);
	}

	/** Standard synchronous, non-reentrant SimpleContainer/world contract, not crash atomicity. */
	static boolean finish(Ticket ticket, BooleanSupplier actualArrival) {
		if (actualArrival == null || !valid(ticket) || !actualArrival.getAsBoolean()) return false;
		List<ItemStack> original = snapshot(ticket.inventory);
		int seedSlot = -1;
		for (int i = 0; i < original.size(); i++) if (SettlementCropHarvest.ordinaryPotato(original.get(i))) { seedSlot = i; break; }
		if (seedSlot < 0) return false;
		BlockState air = ticket.probe.state(ticket.position()), soil = ticket.probe.state(ticket.position().below());
		ItemStack remainder = original.get(seedSlot).copy(); remainder.shrink(1);
		// Arrival/permission callbacks are allowed to invalidate the plan, never to lend stale inventory.
		if (!valid(ticket) || !actualArrival.getAsBoolean() || !unchanged(ticket.inventory, original)
			|| !authority(ticket) || !air.equals(ticket.probe.state(ticket.position()))
			|| !soil.equals(ticket.probe.state(ticket.position().below()))) return false;
		ticket.used = true; // An attempted world write cannot be repeated using this old ticket.
		if (!ticket.probe.plant(ticket.position(), air, soil)) return false;
		ticket.inventory.setItem(seedSlot, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
		ticket.inventory.setChanged();
		return true;
	}
	private static List<ItemStack> snapshot(Container inventory) {
		List<ItemStack> result = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
		return result;
	}
	private static boolean unchanged(Container inventory, List<ItemStack> original) {
		if (inventory.getContainerSize() != original.size()) return false;
		for (int i = 0; i < original.size(); i++) if (!ItemStack.matches(original.get(i), inventory.getItem(i))) return false;
		return true;
	}

	static List<BlockPos> candidates(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident) {
		if (!hasSeed(data, camp, resident.getUUID(), resident.getInventory()) || !bodyReady(level, data, camp, resident)) return List.of();
		BlockPos origin = resident.blockPosition(); Probe probe = worldProbe(level, data);
		return data.sowingPositions(camp).stream().filter(pos -> near(origin, pos))
			.filter(pos -> data.canUseSowingPlot(camp, pos))
			.filter(pos -> data.materialLeases().available(pos, resident.getUUID(), level.getGameTime()))
			.filter(pos -> siteReady(pos, probe))
			.sorted(Comparator.comparingDouble((BlockPos pos) -> origin.distSqr(pos)).thenComparingLong(BlockPos::asLong)).toList();
	}
	static Ticket prepare(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident, BlockPos pos) {
		if (resident == null) return null;
		return prepare(data, camp, resident.getUUID(), pos, resident.getInventory(),
			() -> bodyReady(level, data, camp, resident) && near(resident.blockPosition(), pos), worldProbe(level, data), resident);
	}
	static boolean valid(Ticket ticket, Villager resident) {
		return ticket != null && ticket.resident == resident && ticket.inventory == resident.getInventory() && valid(ticket);
	}
	static boolean finish(Ticket ticket, Villager resident, BooleanSupplier actualArrival) {
		return valid(ticket, resident) && finish(ticket, actualArrival);
	}
	private static boolean near(BlockPos from, BlockPos to) {
		if (to == null) return false;
		long dx = (long) from.getX() - to.getX(), dz = (long) from.getZ() - to.getZ();
		return Math.abs(dx) <= 12 && Math.abs(dz) <= 12 && dx * dx + dz * dz <= 144
			&& Math.abs((long) from.getY() - to.getY()) <= 4;
	}
	private static boolean bodyReady(ServerLevel level, SettlementSavedData data, Settlement camp, Villager resident) {
		return level != null && data != null && level.getServer().isSameThread()
			&& level.getServer().getLevel(level.dimension()) == level && SettlementSavedData.get(level) == data
			&& resident.level() == level && level.getEntity(resident.getUUID()) == resident
			&& SettlementTeachingScene.residentAllowed(resident.isAlive(), resident.isRemoved(), resident.isBaby(),
				resident.getHealth(), resident.getMaxHealth(), resident.isSleeping(), resident.isTrading(),
				resident.isNoAi(), resident.isPassenger(), resident.getTarget() != null,
				resident.isOnFire() || resident.hasEffect(MobEffects.POISON) || resident.hasEffect(MobEffects.WITHER)
					|| resident.getBrain().isActive(Activity.PANIC) || resident.getBrain().isActive(Activity.REST)
					|| resident.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY)
					|| resident.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE))
			&& SettlementTeachingScene.available(level, resident.blockPosition())
			&& !resident.isInWaterOrBubble() && !resident.isInLava()
			&& data.needsOf(camp, resident.getUUID()).satiety() > SettlementNeeds.HUNGRY_AT
			&& SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, resident.getUUID()), level.dimension(), data, camp);
	}
	private static Probe worldProbe(ServerLevel level, SettlementSavedData data) {
		return new Probe() {
			public boolean inBounds(BlockPos pos) { return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos); }
			public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
			public boolean ticking(BlockPos pos) { return level.isPositionEntityTicking(pos); }
			public BlockState state(BlockPos pos) { return level.getBlockState(pos); }
			public boolean canGrow(BlockPos pos) { return Blocks.POTATOES.defaultBlockState().canSurvive(level, pos); }
			public boolean plant(BlockPos pos, BlockState air, BlockState soil) {
				return !data.isResidentModificationBlocked(pos) && !data.isResidentModificationBlocked(pos.below())
					&& siteReady(pos, this) && state(pos).equals(air) && state(pos.below()).equals(soil)
					&& level.setBlock(pos, ((CropBlock) Blocks.POTATOES).getStateForAge(0), Block.UPDATE_ALL);
			}
		};
	}
}
