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
		long real = stockpile.countItem(item);
		ResourceType type = resourceType(state, item);
		if (type == null) {
			return real;
		}
		return real + state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == type)
			.mapToLong(batch -> batch.quantity()).sum();
	}

	public static boolean hasAll(Container stockpile, SettlementSimulationState state, ConstructionPlan plan) {
		return stockpile != null && plan.materials().entrySet().stream()
			.allMatch(entry -> available(stockpile, state, entry.getKey()) >= entry.getValue());
	}

	/** Caller must validate the job, loaded position, air and entities before entering this operation. */
	static boolean place(
		Container stockpile, SettlementSimulationState state, Item item, boolean consume,
		ConstructionJob job, UUID villagerId, Runnable markDirty, BooleanSupplier placement
	) {
		// The paired first half has already paid; readiness is checked by the caller.
		if (!consume) {
			return placement.getAsBoolean();
		}
		if (stockpile == null) {
			return false;
		}
		if (consume && stockpile.countItem(item) < 1) {
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
		}
		if (!placement.getAsBoolean()) {
			return false;
		}
		if (consume) {
			for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
				if (stockpile.getItem(slot).is(item)) {
					stockpile.removeItem(slot, 1);
					stockpile.setChanged();
					break;
				}
			}
		}
		return true;
	}

	private static ResourceType resourceType(SettlementSimulationState state, Item item) {
		if (state == null || !state.survivalEnabled() || !state.productionEnabled()) {
			return null;
		}
		return MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
	}
}
