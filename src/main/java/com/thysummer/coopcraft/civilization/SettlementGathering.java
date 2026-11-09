package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Marked wood/stone collection. Authority is checked before any world or loot reads. */
public final class SettlementGathering {
	private SettlementGathering() {}

	static boolean authorized(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos,
		BooleanSupplier activeAdult) {
		if (settlement == null || pos == null || actor == null
			|| data.all().stream().noneMatch(candidate -> candidate == settlement)
			|| !data.isMember(settlement, actor) || !activeAdult.getAsBoolean()
			|| !data.resourcesOf(settlement).contains(pos)
			|| data.find(pos).filter(owner -> owner == settlement).isEmpty()) return false;
		var simulation = data.simulationOf(settlement).orElse(null);
		if (simulation != null && simulation.survivalEnabled()) {
			var person = simulation.residentId(actor).flatMap(id -> simulation.world().allPeople().stream()
				.filter(candidate -> candidate.id().equals(id)).findFirst()).orElse(null);
			if (person == null || !person.isAlive() || !person.campId().equals(simulation.campOwner().id())) return false;
		}
		return SettlementSimulationService.hasGatherTime(data, settlement, actor);
	}

	static boolean gatherable(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.LOGS);
	}

	static boolean accessible(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos,
		BooleanSupplier activeAdult, BooleanSupplier loaded, BooleanSupplier ticking) {
		return authorized(data, settlement, actor, pos, activeAdult) && loaded.getAsBoolean() && ticking.getAsBoolean();
	}

	static boolean validTarget(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos,
		BooleanSupplier activeAdult, BooleanSupplier loaded, BooleanSupplier ticking, Supplier<BlockState> block) {
		return accessible(data, settlement, actor, pos, activeAdult, loaded, ticking) && gatherable(block.get());
	}

	static boolean validTarget(ServerLevel level, SettlementSavedData data, Settlement settlement,
		Villager villager, BlockPos pos) {
		return validTarget(data, settlement, villager.getUUID(), pos,
			() -> villager.isAlive() && !villager.isRemoved() && !villager.isBaby(),
			() -> level.isLoaded(pos), () -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos));
	}

	/** Shared execution seam: real inventory and production, lazy world adapters for server-free tests.
	 * Retains the original per-stack capacity check and world-spawn policy for aggregate overflow. */
	static int harvest(SettlementSavedData data, Settlement settlement, UUID actor, BlockPos pos,
		SimpleContainer inventory, BooleanSupplier activeAdult, BooleanSupplier loaded, BooleanSupplier ticking,
		Supplier<BlockState> block, BooleanSupplier belowLoaded, Supplier<BlockState> below,
		Function<BlockState, List<ItemStack>> loot, Predicate<BlockState> destroy, Consumer<ItemStack> overflow) {
		if (!accessible(data, settlement, actor, pos, activeAdult, loaded, ticking)) return 0;
		BlockState planned = block.get();
		if (!gatherable(planned)) {
			// Preserve planted crops, bare farmland plots, and unknown/unloaded ground.
			if (!planned.is(Blocks.POTATOES) && !planned.is(Blocks.FARMLAND) && belowLoaded.getAsBoolean()
				&& !below.get().is(Blocks.FARMLAND)
				&& accessible(data, settlement, actor, pos, activeAdult, loaded, ticking)
				&& planned.equals(block.get())) data.removeResource(settlement, pos);
			return 0;
		}
		List<ItemStack> drops = loot.apply(planned).stream().map(ItemStack::copy).toList();
		for (ItemStack drop : drops) if (!inventory.canAddItem(drop)) return 0;
		if (!validTarget(data, settlement, actor, pos, activeAdult, loaded, ticking, block)
			|| !planned.equals(block.get()) || !destroy.test(planned)) return 0;
		int harvested = 0;
		for (ItemStack drop : drops) {
			int produced = drop.getCount();
			var item = drop.getItem();
			ItemStack remainder = inventory.addItem(drop);
			int inserted = produced - remainder.getCount();
			if (!remainder.isEmpty()) overflow.accept(remainder);
			if (inserted > 0) SettlementSimulationService.recordProduction(data, settlement, actor, inventory, item, inserted, "gather");
			harvested += produced;
		}
		data.removeResource(settlement, pos);
		return harvested;
	}

	static int harvest(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager villager, BlockPos pos) {
		return harvest(data, settlement, villager.getUUID(), pos, villager.getInventory(),
			() -> villager.isAlive() && !villager.isRemoved() && !villager.isBaby(),
			() -> level.isLoaded(pos), () -> level.isPositionEntityTicking(pos), () -> level.getBlockState(pos),
			() -> level.isLoaded(pos.below()), () -> level.getBlockState(pos.below()),
			state -> Block.getDrops(state, level, pos, level.getBlockEntity(pos)),
			state -> level.destroyBlock(pos, false, villager), stack -> villager.spawnAtLocation(stack));
	}
}
