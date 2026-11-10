package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Per-project material budget, not a reservation or a promise that a workshop is usable. */
final class SettlementWoolSupply {
	private SettlementWoolSupply() {}

	/** A live work gate for one bed, independent of string already in the worker's custody. */
	static boolean nextBedNeedsWool(List<Map<Item, Integer>> demands, Container stockpile,
		SettlementSimulationState state) {
		if (stockpile == null || demands.stream().noneMatch(demand -> demand.getOrDefault(Items.WHITE_BED, 0) > 0))
			return false;
		long beds = ConstructionMaterials.available(stockpile, state, Items.WHITE_BED);
		return demands.stream().anyMatch(demand -> demand.getOrDefault(Items.WHITE_BED, 0) > beds)
			&& SettlementMaterialCrafting.countPlain(stockpile, Items.WHITE_WOOL)
				< SettlementMaterialCrafting.Recipe.BED.ingredients().get(Items.WHITE_WOOL);
	}

	static Optional<Snapshot> inspect(Map<Item, Integer> demand, Container stockpile) {
		int requiredBeds = demand.getOrDefault(Items.WHITE_BED, 0);
		if (requiredBeds <= 0 || stockpile == null) return Optional.empty();
		// Match the real pickup policy: named or already assigned beds cannot satisfy new demand.
		long missingBeds = Math.max(0L, (long) requiredBeds - ConstructionSupply.countAvailable(stockpile, Items.WHITE_BED));
		if (missingBeds == 0) return Optional.empty();
		long missingWool = Math.max(0L, missingBeds * 3 - SettlementMaterialCrafting.countPlain(stockpile, Items.WHITE_WOOL));
		return Optional.of(new Snapshot(missingBeds, missingWool, missingWool * 4,
			SettlementMaterialCrafting.countPlain(stockpile, Items.STRING)));
	}

	record Snapshot(long missingBeds, long missingWool, long replacementString, long availableString) {}
}
