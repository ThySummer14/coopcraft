package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Explicitly marked potato plots only. All mutations run synchronously on the server thread. */
public final class SettlementCropHarvest {
	private SettlementCropHarvest() {}

	static boolean isRipe(BlockState state) {
		return state.is(Blocks.POTATOES) && ((CropBlock) Blocks.POTATOES).isMaxAge(state);
	}

	static boolean ordinaryPotato(ItemStack stack) {
		return !stack.isEmpty() && stack.is(Items.POTATO) && stack.getComponentsPatch().isEmpty();
	}

	/** Keep the caller's marked-plot authority and the real dimension's independent protection veto. */
	static boolean validTarget(ServerLevel level, SettlementSavedData data, Settlement settlement, BlockPos pos) {
		return validTarget(data, settlement, pos,
			() -> !SettlementSavedData.get(level).isResidentModificationBlocked(pos) && level.isLoaded(pos),
			() -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos));
	}

	/** Lazy world reads let the loaded/authority gates be tested without starting a server. */
	static boolean validTarget(SettlementSavedData data, Settlement settlement, BlockPos pos,
		BooleanSupplier loaded, BooleanSupplier entityTicking, Supplier<BlockState> block) {
		return pos != null && !data.isResidentModificationBlocked(pos)
			&& data.resourcesOf(settlement).contains(pos)
			&& data.find(pos).filter(settlement::equals).isPresent()
			&& SettlementForaging.canForage(data, settlement)
			&& loaded.getAsBoolean() && entityTicking.getAsBoolean() && isRipe(block.get())
			&& !data.isResidentModificationBlocked(pos);
	}

	static Optional<BlockPos> findRipe(ServerLevel level, SettlementSavedData data, Settlement settlement, BlockPos origin) {
		return findRipe(data, settlement, origin,
			pos -> !SettlementSavedData.get(level).isResidentModificationBlocked(pos) && level.isLoaded(pos),
			level::isPositionEntityTicking, level::getBlockState);
	}

	static Optional<BlockPos> findRipe(SettlementSavedData data, Settlement settlement, BlockPos origin,
		Predicate<BlockPos> loaded, Predicate<BlockPos> ticking, Function<BlockPos, BlockState> block) {
		return data.resourcesOf(settlement).stream()
			.filter(pos -> Math.abs(pos.getY() - origin.getY()) <= 4)
			.filter(pos -> { long dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ(); return dx * dx + dz * dz <= 144; })
			.filter(pos -> validTarget(data, settlement, pos, () -> loaded.test(pos),
				() -> ticking.test(pos), () -> block.apply(pos)))
			.min(java.util.Comparator.comparingDouble(origin::distSqr));
	}

	/** Copies loot and reserves exactly one ordinary seed; byproducts/components remain untouched. */
	static Optional<List<ItemStack>> outputAfterSeed(List<ItemStack> loot) {
		List<ItemStack> output = new ArrayList<>();
		boolean reserved = false;
		for (ItemStack original : loot) {
			ItemStack stack = original.copy();
			if (!reserved && ordinaryPotato(stack)) {
				stack.shrink(1);
				reserved = true;
			}
			if (!stack.isEmpty()) output.add(stack);
		}
		return reserved ? Optional.of(output) : Optional.empty();
	}

	/** Shared production entry: reserve the new seed before stamping or planning the complete cargo. */
	static int harvest(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos,
		SimpleContainer inventory, BooleanSupplier adult, BooleanSupplier loaded, BooleanSupplier ticking,
		Supplier<BlockState> block, Supplier<List<ItemStack>> loot, Predicate<BlockState> replant) {
		var attempt = SettlementBerryCargoHarvest.capture(data, settlement, actor);
		if (!attempt.valid(data, settlement, actor, adult)
			|| !validTarget(data, settlement, pos, loaded, ticking, block)) return 0;
		BlockState mature = block.get();
		var reserved = outputAfterSeed(loot.get());
		if (reserved.isEmpty() || data.isResidentModificationBlocked(pos)
			|| !attempt.valid(data, settlement, actor, adult)) return 0;
		int potatoes = reserved.get().stream().filter(SettlementCropHarvest::ordinaryPotato)
			.mapToInt(ItemStack::getCount).sum();
		List<ItemStack> output = reserved.get().stream().map(attempt::potatoOutputCopy).toList();
		if (!SettlementHarvestCommit.commit(inventory, output,
			() -> validTarget(data, settlement, pos, loaded, ticking, block)
				&& block.get().equals(mature) && attempt.valid(data, settlement, actor, adult)
				&& !data.isResidentModificationBlocked(pos) && replant.test(mature))) return 0;
		if (potatoes > 0) SettlementSimulationService.recordCarriedProduction(
			data, settlement, actor, inventory, Items.POTATO, potatoes, "harvest_crop");
		return potatoes;
	}

	static int harvest(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager villager, BlockPos pos) {
		int potatoes = harvest(data, settlement, villager.getUUID(), pos, villager.getInventory(),
			() -> villager.isAlive() && !villager.isRemoved() && !villager.isBaby(),
			() -> !SettlementSavedData.get(level).isResidentModificationBlocked(pos) && level.isLoaded(pos),
			() -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos),
			() -> Block.getDrops(level.getBlockState(pos), level, pos, level.getBlockEntity(pos)),
			mature -> {
				if (SettlementSavedData.get(level).isResidentModificationBlocked(pos)
					|| !level.setBlock(pos, ((CropBlock) Blocks.POTATOES).getStateForAge(0), Block.UPDATE_ALL)) return false;
				villager.swing(InteractionHand.MAIN_HAND);
				return true;
			});
		return potatoes;
	}
}
