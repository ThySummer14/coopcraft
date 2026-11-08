package com.thysummer.coopcraft.civilization;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Food points for the real-inventory settlement loop, without changing vanilla breeding. */
public final class SettlementFood {
	private SettlementFood() {
	}

	public static int points(Item item) {
		// Foraged berries must feed residents even when the optional simulation is disabled.
		// Keep existing vanilla ration values; one berry restores five settlement satiety.
		return item == Items.SWEET_BERRIES ? 1 : Villager.FOOD_POINTS.getOrDefault(item, 0);
	}
}
