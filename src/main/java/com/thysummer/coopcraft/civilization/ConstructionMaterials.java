package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Construction draws from the real stockpile, exporting a missing unit only when needed. */
public final class ConstructionMaterials {
	private ConstructionMaterials() {
	}

	public static long available(Container stockpile, SettlementSimulationState state, Item item) {
		if (stockpile == null) {
			return 0;
		}
		long real = realAvailable(stockpile, item);
		ResourceType type = resourceType(state, item);
		if (type == null) {
			return real;
		}
		return real + state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == type)
			.mapToLong(batch -> batch.quantity()).sum();
	}

	/** The same read-only real-supply count used by target selection, diagnostics and material totals. */
	static long realAvailable(Container stockpile, Item item) {
		if (stockpile == null) return 0;
		if (ConstructionSupply.supported(item)) return ConstructionSupply.countAvailable(stockpile, item);
		long real = 0;
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			ItemStack stack = stockpile.getItem(slot);
			if (usable(stack, item)) real += stack.getCount();
		}
		return real;
	}

	public static boolean hasAll(Container stockpile, SettlementSimulationState state, ConstructionPlan plan) {
		return stockpile != null && plan.materials().entrySet().stream()
			.allMatch(entry -> available(stockpile, state, entry.getKey()) >= entry.getValue());
	}

	/**
	 * Caller must validate the job, loaded position, air and entities before entering this operation.
	 * Standard synchronous, non-reentrant containers are required; placement must not mutate inventory.
	 */
	static boolean place(
		Container stockpile, SettlementSimulationState state, Item item, boolean consume,
		ConstructionJob job, UUID villagerId, Runnable markDirty, BooleanSupplier placement
	) {
		return place(stockpile, state, item, consume, job, villagerId, markDirty, () -> true, placement);
	}

	/** Recheck the live veto before exporting material and immediately before world placement. */
	static boolean place(
		Container stockpile, SettlementSimulationState state, Item item, boolean consume,
		ConstructionJob job, UUID villagerId, Runnable markDirty, BooleanSupplier authorized, BooleanSupplier placement
	) {
		if (authorized == null || placement == null || !authorized.getAsBoolean()) return false;
		// The paired first half has already paid; readiness is checked by the caller.
		if (!consume) {
			return authorized.getAsBoolean() && placement.getAsBoolean();
		}
		if (stockpile == null) {
			return false;
		}
		int source = firstUsableSlot(stockpile, item);
		if (source < 0) {
			ResourceType type = resourceType(state, item);
			if (type == null) {
				return false;
			}
			var batch = state.world().batchesAt(state.campOwner()).stream()
				.filter(candidate -> candidate.resourceType() == type && candidate.quantity() > 0)
				.findFirst().orElse(null);
			if (batch == null) {
				return false;
			}
			List<String> actors = state.residentId(villagerId).map(List::of).orElse(List.of());
			if (!authorized.getAsBoolean()) return false;
			int exported = MinecraftStockpileAdapter.exportToContainer(
				state.world(), state.campOwner(), batch.batchId(), 1, stockpile, actors,
				Map.of("activity", "build", "blueprint", job.blueprintId().toString(),
					"origin", job.origin().toShortString(), "index", Integer.toString(job.nextIndex()),
					"villagerId", villagerId.toString())
			);
			if (exported == 0) {
				return false;
			}
			// Export is committed even if placement fails: the unit remains in the real stockpile.
			markDirty.run();
			source = firstUsableSlot(stockpile, item);
		}
		// A marked lookalike must never authorize an unpaid placement or be the item charged afterward.
		if (source < 0 || !authorized.getAsBoolean() || !placement.getAsBoolean()) {
			return false;
		}
		stockpile.removeItem(source, 1);
		stockpile.setChanged();
		return true;
	}

	private static int firstUsableSlot(Container stockpile, Item item) {
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			if (usable(stockpile.getItem(slot), item)) return slot;
		}
		return -1;
	}

	private static boolean usable(ItemStack stack, Item item) {
		// Unknown construction markers are quarantined for every item, including legacy wood/stone.
		// Other custom materials retain the legacy behavior; supported physical supply remains plain-only.
		return !stack.isEmpty() && stack.is(item) && !ConstructionSupply.isMarked(stack)
			&& !SettlementWorkbenchCargo.isMarked(stack)
			&& (!ConstructionSupply.supported(item) || ConstructionSupply.ordinary(stack));
	}

	private static ResourceType resourceType(SettlementSimulationState state, Item item) {
		if (state == null || !state.survivalEnabled() || !state.productionEnabled()) {
			return null;
		}
		return MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
	}
}
