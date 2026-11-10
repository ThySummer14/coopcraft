package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Physical surplus only; the resident's ordinary ration is never communal cargo. */
public final class SettlementFoodCargo {
	private static final int BASIC_FOOD_POINTS = 12;
	private SettlementFoodCargo() { }

	public static boolean isFood(ItemStack stack) {
		return stack != null && !stack.isEmpty() && !SettlementFoodAge.isMarked(stack)
			&& !SettlementShearingCargo.isMarked(stack)
			&& (stack.is(Items.SWEET_BERRIES) || stack.is(Items.POTATO));
	}

	public static int retainedFoodPoints(SettlementSavedData data, Settlement camp, UUID actor) {
		if (data == null || camp == null || actor == null || !data.isMember(camp, actor)) return Integer.MAX_VALUE;
		return Math.max(BASIC_FOOD_POINTS, data.personalityOf(camp, actor).foodRetentionPoints(BASIC_FOOD_POINTS));
	}

	private static boolean eligible(SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		return data != null && camp != null && actor != null && inventory != null
			&& data.isMember(camp, actor) && !SettlementSimulationService.isSurvivalEnabled(data, camp);
	}

	/** Shared point budget and per-stack calculation for the Goal predicate and the final transfer. */
	private static int surplusPoints(SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		return Math.max(0, SettlementStockpile.communalFoodPoints(inventory) - retainedFoodPoints(data, camp, actor));
	}

	private static int offeredCount(ItemStack stack, int surplus) {
		if (!isFood(stack)) return 0;
		int points = SettlementFood.points(stack);
		return points <= 0 ? 0 : Math.min(stack.getCount(), surplus / points);
	}

	public static boolean hasSurplus(SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		if (!eligible(data, camp, actor, inventory)) return false;
		int surplus = surplusPoints(data, camp, actor, inventory);
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (offeredCount(inventory.getItem(slot), surplus) > 0) return true;
		}
		return false;
	}

	/** Called only after arrival authorization, with distinct standard, non-reentrant containers. */
	static int depositSurplus(SettlementSavedData data, Settlement camp, UUID actor, Container inventory, Container store) {
		if (!eligible(data, camp, actor, inventory) || store == null || inventory == store) return 0;
		int surplus = surplusPoints(data, camp, actor, inventory);
		int moved = 0;
		for (int slot = 0; slot < inventory.getContainerSize() && surplus > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			int count = offeredCount(stack, surplus);
			if (count == 0) continue;
			int accepted = SettlementStockpile.insert(store, stack.copyWithCount(count));
			if (accepted == 0) continue;
			surplus -= accepted * SettlementFood.points(stack);
			stack.shrink(accepted);
			inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
			moved += accepted;
		}
		if (moved > 0) inventory.setChanged();
		return moved;
	}
}
