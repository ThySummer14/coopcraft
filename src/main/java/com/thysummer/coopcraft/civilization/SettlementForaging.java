package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementForaging {
	private static final int SEARCH_RADIUS = 12;
	private static final int SEARCH_VERTICAL_RADIUS = 4;
	private static final int RIPE_AGE = 2;

	private SettlementForaging() {
	}

	public static boolean canForage(SettlementSavedData data, Settlement settlement) {
		return !SettlementSimulationService.isSurvivalEnabled(data, settlement)
			|| SettlementSimulationService.isProductionEnabled(data, settlement);
	}

	static boolean needsForage(boolean hasFood, boolean reserveLow, long gameTime, long cooldownUntil) {
		return (!hasFood || reserveLow) && gameTime >= cooldownUntil;
	}

	static boolean needsForage(boolean hasFood, WorkRole role, BooleanSupplier reserveLow, long gameTime, long cooldownUntil) {
		return needsForage(hasFood, role == WorkRole.FORAGER && reserveLow.getAsBoolean(), gameTime, cooldownUntil);
	}

	static boolean needsForage(boolean required, long gameTime, long cooldownUntil) {
		return required && gameTime >= cooldownUntil;
	}

	static boolean reserveLow(SettlementSavedData data, Settlement settlement, Container stockpile) {
		SettlementReserves.Snapshot snapshot = SettlementReserves.inspect(data, settlement, stockpile);
		// Preserve the existing preventive-work behavior when a physical store cannot be resolved.
		// Reporting keeps it unknown; this is not an assertion that its actual stock is zero.
		return snapshot.current().orElse(0) < snapshot.goal();
	}

	static boolean isRipe(BlockState state) {
		return state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) >= RIPE_AGE;
	}

	/** A permit is captured before world reads. Replacement/revoke-and-regrant cannot revive it. */
	private record Permission(SettlementSavedData data, Settlement settlement, UUID actor,
		SettlementForagingAreas.Area area, Object member, SettlementBerryCargoHarvest.Attempt attempt) {
		static Permission capture(SettlementSavedData data, Settlement settlement, UUID actor) {
			if (data == null || settlement == null || actor == null || !data.isMember(settlement, actor)) return null;
			SettlementForagingAreas.Area area = data.foragingAreaOf(settlement).orElse(null);
			return area == null ? null : new Permission(data, settlement, actor, area, data.memberIdentity(actor),
				SettlementBerryCargoHarvest.capture(data, settlement, actor));
		}

		boolean valid(BlockPos pos, BooleanSupplier adult) {
			return permitted(pos) && attempt.valid(data, settlement, actor, adult);
		}

		boolean validForScan(BlockPos pos, BooleanSupplier adult) {
			return permitted(pos) && attempt.validForScan(data, settlement, actor, adult);
		}

		private boolean permitted(BlockPos pos) {
			return area.contains(pos) && member != null && data.memberIdentity(actor) == member && data.isMember(settlement, actor)
				&& data.foragingAreaOf(settlement).orElse(null) == area && data.canHarvestBerries(settlement, pos)
				&& canForage(data, settlement);
		}
	}

	/** No identity-free finder: callers must supply the settlement and actual resident. */
	public static Optional<BlockPos> findRipeBush(ServerLevel level, SettlementSavedData data,
		Settlement settlement, Villager villager, BlockPos origin) {
		if (!presentAdult(level, data, villager)) return Optional.empty();
		SettlementObservationClaims claims = SettlementObservationClaims.capture(level, data);
		SettlementMember member = data.rawMember(villager.getUUID()).orElse(null);
		if (!claims.unique(data, settlement, member)) return Optional.empty();
		Optional<BlockPos> result = findRipeBush(data, settlement, villager.getUUID(), origin,
			() -> presentAdult(level, data, villager) && claims.unique(data, settlement, member), pos -> inBounds(level, pos),
			level::isLoaded, level::isPositionEntityTicking,
			pos -> data.foragingLeases().available(pos, villager.getUUID(), level.getGameTime()), level::getBlockState);
		return result.filter(pos -> liveAdult(level, data, settlement, villager));
	}

	/** Bounded live scan; observations (including UNKNOWN) are neither required nor an authorization. */
	static Optional<BlockPos> findRipeBush(SettlementSavedData data, Settlement settlement, UUID actor,
		BlockPos origin, BooleanSupplier adult, Predicate<BlockPos> inBounds, Predicate<BlockPos> loaded,
		Predicate<BlockPos> ticking, Predicate<BlockPos> available, Function<BlockPos, BlockState> block) {
		Permission permission = Permission.capture(data, settlement, actor);
		if (permission == null || origin == null) return Optional.empty();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
				if (dx * dx + dz * dz > SEARCH_RADIUS * SEARCH_RADIUS) continue;
				for (int dy = -SEARCH_VERTICAL_RADIUS; dy <= SEARCH_VERTICAL_RADIUS; dy++) {
					long x = (long) origin.getX() + dx, y = (long) origin.getY() + dy, z = (long) origin.getZ() + dz;
					if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
						|| z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
					BlockPos pos = new BlockPos((int) x, (int) y, (int) z);
					if (!permission.validForScan(pos, adult) || !inBounds.test(pos) || !loaded.test(pos)
						|| !ticking.test(pos) || !available.test(pos)) continue;
					if (isRipe(block.apply(pos)) && permission.validForScan(pos, adult)) {
						double distance = origin.distSqr(pos);
						if (distance < bestDistance) { bestDistance = distance; best = pos; }
					}
				}
			}
		}
		return best != null && permission.valid(best, adult) && inBounds.test(best)
			&& loaded.test(best) && ticking.test(best) && available.test(best) ? Optional.of(best) : Optional.empty();
	}

	/** Same lazy permission boundary used by execution and Goal continuation. */
	static boolean validTarget(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos,
		BooleanSupplier adult, BooleanSupplier loaded, BooleanSupplier ticking, Supplier<BlockState> block) {
		Permission permission = Permission.capture(data, settlement, actor);
		return permission != null && permission.validForScan(pos, adult) && loaded.getAsBoolean()
			&& ticking.getAsBoolean() && isRipe(block.get()) && permission.validForScan(pos, adult);
	}

	private static BlockState ripeState(Permission permission, BlockPos pos, BooleanSupplier adult,
		BooleanSupplier loaded, BooleanSupplier ticking, Supplier<BlockState> block) {
		if (permission == null || !permission.valid(pos, adult) || !loaded.getAsBoolean() || !ticking.getAsBoolean()) return null;
		BlockState state = block.get();
		return isRipe(state) && permission.valid(pos, adult) ? state : null;
	}

	static boolean validTarget(ServerLevel level, SettlementSavedData data, Settlement settlement,
		Villager villager, BlockPos pos) {
		if (!presentAdult(level, data, villager)) return false;
		SettlementObservationClaims claims = SettlementObservationClaims.capture(level, data);
		SettlementMember member = data.rawMember(villager.getUUID()).orElse(null);
		return validTarget(data, settlement, villager.getUUID(), pos,
			() -> presentAdult(level, data, villager) && claims.unique(data, settlement, member),
			() -> inBounds(level, pos) && level.isLoaded(pos),
			() -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos));
	}

	private static boolean inBounds(ServerLevel level, BlockPos pos) {
		return pos != null && !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos);
	}

	/** Supplied data cannot impersonate the actual dimension, and a resident must have one live claim. */
	private static boolean liveAdult(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager villager) {
		return presentAdult(level, data, villager) && SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()),
				level.dimension(), data, settlement);
	}

	private static boolean presentAdult(ServerLevel level, SettlementSavedData data, Villager villager) {
		return SettlementSavedData.get(level) == data && villager.level() == level
			&& villager.isAlive() && !villager.isRemoved() && !villager.isBaby()
			&& level.getEntity(villager.getUUID()) == villager && inBounds(level, villager.blockPosition())
			&& level.isLoaded(villager.blockPosition()) && level.isPositionEntityTicking(villager.blockPosition());
	}

	/** Production entry shared by world harvesting and server-free regression tests.
	 * Loot and world mutation stay lazy; permit identity is rechecked after loot and inventory planning. */
	static int harvest(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos, SimpleContainer inventory,
		BooleanSupplier adult, BooleanSupplier loaded, BooleanSupplier ticking, Supplier<BlockState> block,
		Supplier<List<ItemStack>> loot, Predicate<BlockState> regrow) {
		Permission permission = Permission.capture(data, settlement, actor);
		BlockState mature = ripeState(permission, pos, adult, loaded, ticking, block);
		if (mature == null) return 0;
		List<ItemStack> drops = loot.get();
		if (!permission.valid(pos, adult)) return 0;
		int berries = drops.stream().filter(SettlementBerryCargoHarvest::plainBerry).mapToInt(ItemStack::getCount).sum();
		// Stamped output must participate in capacity planning, not be attached after a plain-stack merge.
		List<ItemStack> output = drops.stream().map(permission.attempt()::outputCopy).toList();
		if (!SettlementHarvestCommit.commit(inventory, output,
			() -> mature.equals(ripeState(permission, pos, adult, loaded, ticking, block))
				&& permission.valid(pos, adult) && regrow.test(mature))) return 0;
		if (berries > 0) SettlementSimulationService.recordCarriedProduction(
			data, settlement, actor, inventory, Items.SWEET_BERRIES, berries, "forage");
		return output.stream().mapToInt(ItemStack::getCount).sum();
	}

	/** Direct callers still need an exclusive one-shot lease; active Goals pass their original lease below. */
	public static int harvest(ServerLevel level, SettlementSavedData data, Settlement settlement,
		Villager villager, BlockPos position) {
		if (!validTarget(level, data, settlement, villager, position)) return 0;
		SettlementForagingLeases registry = data.foragingLeases();
		SettlementForagingLeases.Lease lease = registry.claim(data, settlement, villager.getUUID(), position, level.getGameTime());
		try { return harvest(level, data, settlement, villager, position, registry, lease); }
		finally { registry.release(lease); }
	}

	static int harvest(ServerLevel level, SettlementSavedData data, Settlement settlement,
		Villager villager, BlockPos position, SettlementForagingLeases registry, SettlementForagingLeases.Lease lease) {
		BooleanSupplier current = () -> liveAdult(level, data, settlement, villager) && registry != null
			&& registry.valid(lease, data, settlement, villager.getUUID(), position, level.getGameTime());
		int harvested = harvest(data, settlement, villager.getUUID(), position, villager.getInventory(), current,
			() -> inBounds(level, position) && level.isLoaded(position),
			() -> level.isPositionEntityTicking(position), () -> level.getBlockState(position),
			() -> current.getAsBoolean() && data.canHarvestBerries(settlement, position)
				&& inBounds(level, position) && level.isLoaded(position) && level.isPositionEntityTicking(position)
				? Block.getDrops(level.getBlockState(position), level, position, level.getBlockEntity(position)) : List.of(),
			mature -> current.getAsBoolean() && data.canHarvestBerries(settlement, position)
				&& inBounds(level, position) && level.isLoaded(position) && level.isPositionEntityTicking(position)
				&& level.setBlock(position, mature.setValue(SweetBerryBushBlock.AGE, 1), Block.UPDATE_ALL));
		if (harvested > 0) {
			villager.swing(InteractionHand.MAIN_HAND);
			SettlementCoordination.requestReplan(data, settlement);
		}
		return harvested;
	}
}
