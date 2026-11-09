package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.Optional;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Per-project material budget, not a reservation or a promise that a workshop is usable. */
final class SettlementWoolSupply {
	private SettlementWoolSupply() {}

	static Optional<Snapshot> inspect(Map<Item, Integer> demand, Container stockpile) {
		int requiredBeds = demand.getOrDefault(Items.WHITE_BED, 0);
		if (requiredBeds <= 0 || stockpile == null) return Optional.empty();
		// Construction accepts named finished beds; named wool/string are not crafting ingredients.
		long missingBeds = Math.max(0L, (long) requiredBeds - stockpile.countItem(Items.WHITE_BED));
		if (missingBeds == 0) return Optional.empty();
		long missingWool = Math.max(0L, missingBeds * 3 - SettlementMaterialCrafting.countPlain(stockpile, Items.WHITE_WOOL));
		return Optional.of(new Snapshot(missingBeds, missingWool, missingWool * 4,
			SettlementMaterialCrafting.countPlain(stockpile, Items.STRING)));
	}

	record Snapshot(long missingBeds, long missingWool, long replacementString, long availableString) {}
}
