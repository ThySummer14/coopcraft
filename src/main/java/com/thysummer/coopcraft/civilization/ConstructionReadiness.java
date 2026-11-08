package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** A diagnostic snapshot, not a work-claim gate: the builder itself can obstruct its target. */
public record ConstructionReadiness(ResourceLocation blueprintId, int nextIndex, int total, BlockPos position, String blocker) {
	static ConstructionReadiness inspect(ConstructionJob job, ConstructionPlan plan, Probe probe) {
		int total = plan == null ? 0 : plan.instructions().size();
		String compatibility = job.compatibilityBlocker(plan);
		if (!compatibility.equals("ready")) {
			return result(job, total, null, compatibility);
		}
		ConstructionInstruction instruction = plan.instructions().get(job.nextIndex());
		BlockPos position = job.origin().offset(instruction.offset());
		// Do not probe block states, entities or containers before the target is known to be ticking.
		if (!probe.ticking(position)) {
			return result(job, total, position, "target_not_ticking");
		}
		if (!probe.air(position)) {
			return result(job, total, position, "target_occupied");
		}
		if (probe.entityObstructed(position)) {
			return result(job, total, position, "entity_obstructed");
		}
		Container stockpile = probe.stockpile();
		if (stockpile == null) {
			return result(job, total, position, "stockpile_unavailable");
		}
		if (instruction.consume()) {
			if (ConstructionMaterials.available(stockpile, probe.simulation(), instruction.item()) < 1) {
				return result(job, total, position, "missing_material");
			}
			if (stockpile.countItem(instruction.item()) < 1 && !canInsertOne(stockpile, new ItemStack(instruction.item()))) {
				return result(job, total, position, "stockpile_full");
			}
		}
		return result(job, total, position, "ready");
	}

	/** Mirrors the acceptance/capacity checks in SettlementStockpile.insert without inserting even temporarily. */
	static boolean canInsertOne(Container container, ItemStack stack) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack existing = container.getItem(slot);
			if (!container.canPlaceItem(slot, stack)) {
				continue;
			}
			if (existing.isEmpty()) {
				if (container.getMaxStackSize(stack) > 0) {
					return true;
				}
			} else if (ItemStack.isSameItemSameComponents(existing, stack)
				&& existing.getCount() < container.getMaxStackSize(existing)) {
				return true;
			}
		}
		return false;
	}

	private static ConstructionReadiness result(ConstructionJob job, int total, BlockPos position, String blocker) {
		return new ConstructionReadiness(job.blueprintId(), job.nextIndex(), total, position, blocker);
	}

	/** Read operations only, allowing the ordering and short-circuit behavior to be tested without a level. */
	interface Probe {
		boolean ticking(BlockPos position);
		boolean air(BlockPos position);
		boolean entityObstructed(BlockPos position);
		Container stockpile();
		SettlementSimulationState simulation();
	}
}
