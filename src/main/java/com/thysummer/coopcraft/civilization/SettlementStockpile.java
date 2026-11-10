package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;

public final class SettlementStockpile {
	private static final long WORK_INTERVAL_TICKS = 40;
	private static final int BASIC_FOOD_POINTS = 12;
	private static final double MEMBER_RADIUS_SQUARED = 64.0 * 64.0;

	private SettlementStockpile() {
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement, long gameTime) {
		tick(data, settlement, gameTime, () -> getStockpile(level, data, settlement), villagerId -> {
			if (level.getEntity(villagerId) instanceof Villager villager
				&& villager.isAlive()
				&& villager.distanceToSqr(settlement.center().getCenter()) <= MEMBER_RADIUS_SQUARED) {
				return villager.getInventory();
			}
			return null;
		});
	}

	/** The supplied inventory is only for a currently loaded, living, nearby member. */
	static void tick(SettlementSavedData data, Settlement settlement, long gameTime,
		Supplier<Container> stockpileAccess, Function<UUID, Container> eligibleInventory) {
		if (data.getStockpile(settlement).isEmpty()
			|| Math.floorMod(gameTime + settlement.id().getLeastSignificantBits(), WORK_INTERVAL_TICKS) != 0) {
			return;
		}

		Container stockpile = stockpileAccess.get();
		if (stockpile == null) {
			return;
		}

		List<SettlementMember> members = data.membersOf(settlement);
		if (members.isEmpty()) {
			return;
		}
		long cycle = Math.floorDiv(gameTime, WORK_INTERVAL_TICKS) + settlement.id().getLeastSignificantBits();
		int index = (int) Math.floorMod(cycle, (long) members.size());
		SettlementMember member = members.get(index);
		Container inventory = eligibleInventory.apply(member.villagerId());
		if (inventory != null) {
			// Survival rations belong solely to the abstract common store. Legacy food handoffs
			// would hide pending imports in personal inventories without feeding the resident.
			if (!SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
				depositExcessFood(stockpile, inventory, member.personality().foodRetentionPoints(BASIC_FOOD_POINTS),
					stack -> !SettlementFoodCargo.isFood(stack));
				distributeBasicFood(stockpile, inventory, member.personality().basicFoodPoints(BASIC_FOOD_POINTS));
			}
			// Materials and berry/potato surplus require arrival through SettlementCargoDelivery.
		}
	}

	static Container getStockpile(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		BlockPos pos = data.getStockpile(settlement).orElse(null);
		if (pos == null || !level.isLoaded(pos)) {
			return null;
		}
		if (level.getBlockState(pos).getBlock() instanceof ChestBlock chest) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				if (!level.isLoaded(pos.relative(direction))) {
					return null;
				}
			}
			return ChestBlock.getContainer(chest, level.getBlockState(pos), level, pos, false);
		}
		return level.getBlockEntity(pos) instanceof Container container ? container : null;
	}

	static int insert(Container container, ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}

		int inserted = 0;
		int remaining = stack.getCount();
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			ItemStack existing = container.getItem(slot);
			if (!container.canPlaceItem(slot, stack)) {
				continue;
			}

			int count;
			if (existing.isEmpty()) {
				count = Math.min(remaining, container.getMaxStackSize(stack));
				if (count <= 0) {
					continue;
				}
				ItemStack added = stack.copy();
				added.setCount(count);
				container.setItem(slot, added);
			} else {
				if (!ItemStack.isSameItemSameComponents(existing, stack)) {
					continue;
				}
				count = Math.min(remaining, container.getMaxStackSize(existing) - existing.getCount());
				if (count <= 0) {
					continue;
				}
				existing.grow(count);
				container.setItem(slot, existing);
			}
			remaining -= count;
			inserted += count;
		}

		if (inserted > 0) {
			container.setChanged();
		}
		return inserted;
	}

	/**
	 * Moves real stacks between distinct, non-overlapping standard containers on the server thread.
	 * Insert copies first, then deduct only accepted items; unaccepted components stay in their source slot.
	 * As with insert, this relies on normal Container mutation semantics, without reentrant inventory edits.
	 */
	static int transferItem(Container source, Container target, Item item, int count) {
		if (source == target || count <= 0) {
			return 0;
		}
		int transferred = 0;
		for (int slot = 0; slot < source.getContainerSize() && transferred < count; slot++) {
			ItemStack stack = source.getItem(slot);
			if (stack.isEmpty() || !stack.is(item) || SettlementShearingCargo.isMarked(stack)) {
				continue;
			}
			ItemStack offered = stack.copy();
			offered.setCount(Math.min(stack.getCount(), count - transferred));
			int inserted = insert(target, offered);
			if (inserted == 0) {
				continue;
			}
			stack.shrink(inserted);
			source.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
			transferred += inserted;
		}
		if (transferred > 0) {
			source.setChanged();
		}
		return transferred;
	}

	static int depositExcessFood(Container stockpile, Container villagerInventory) {
		return depositExcessFood(stockpile, villagerInventory, BASIC_FOOD_POINTS);
	}

	static int depositExcessFood(Container stockpile, Container villagerInventory, int retainedFoodPoints) {
		return depositExcessFood(stockpile, villagerInventory, retainedFoodPoints, stack -> true);
	}

	private static int depositExcessFood(Container stockpile, Container villagerInventory, int retainedFoodPoints,
		java.util.function.Predicate<ItemStack> eligible) {
		int retained = Math.max(BASIC_FOOD_POINTS, retainedFoodPoints);
		int remainingFoodPoints = foodPoints(villagerInventory);
		int insertedTotal = 0;
		for (int slot = 0; slot < villagerInventory.getContainerSize() && remainingFoodPoints > retained; slot++) {
			ItemStack stack = villagerInventory.getItem(slot);
			if (!eligible.test(stack)) continue;
			int points = SettlementFood.points(stack);
			if (points <= 0) {
				continue;
			}

			int available = (remainingFoodPoints - retained) / points;
			if (available <= 0) {
				continue;
			}
			ItemStack deposit = stack.copy();
			deposit.setCount(Math.min(stack.getCount(), available));
			int inserted = insert(stockpile, deposit);
			if (inserted == 0) {
				continue;
			}

			stack.shrink(inserted);
			if (stack.isEmpty()) {
				villagerInventory.setItem(slot, ItemStack.EMPTY);
			}
			remainingFoodPoints -= inserted * points;
			insertedTotal += inserted;
		}

		if (insertedTotal > 0) {
			villagerInventory.setChanged();
		}
		return insertedTotal;
	}

	static int distributeBasicFood(Container stockpile, Container villagerInventory) {
		return distributeBasicFood(stockpile, villagerInventory, BASIC_FOOD_POINTS);
	}

	static int distributeBasicFood(Container stockpile, Container villagerInventory, int basicFoodPoints) {
		int missingFoodPoints = Math.max(BASIC_FOOD_POINTS, basicFoodPoints) - foodPoints(villagerInventory);
		int insertedTotal = 0;
		for (int slot = 0; slot < stockpile.getContainerSize() && missingFoodPoints > 0; slot++) {
			ItemStack stack = stockpile.getItem(slot);
			int points = SettlementFood.points(stack);
			if (points <= 0) {
				continue;
			}

			int count = Math.min(stack.getCount(), (missingFoodPoints + points - 1) / points);
			ItemStack removed = stockpile.removeItem(slot, count);
			int inserted = insert(villagerInventory, removed);
			if (inserted < removed.getCount()) {
				ItemStack remainder = removed.copy();
				remainder.setCount(removed.getCount() - inserted);
				insert(stockpile, remainder);
			}
			missingFoodPoints -= inserted * points;
			insertedTotal += inserted;
			if (inserted < removed.getCount()) {
				break;
			}
		}

		if (insertedTotal > 0) {
			stockpile.setChanged();
		}
		return insertedTotal;
	}

	static int depositGatheredResources(Container stockpile, Container villagerInventory) {
		if (stockpile == null || villagerInventory == null || stockpile == villagerInventory) return 0;
		int insertedTotal = 0;
		for (int slot = 0; slot < villagerInventory.getContainerSize(); slot++) {
			ItemStack stack = villagerInventory.getItem(slot);
			if (!SettlementCargo.isMaterial(stack)) {
				continue;
			}

			int inserted = insert(stockpile, stack);
			if (inserted == 0) {
				continue;
			}
			stack.shrink(inserted);
			villagerInventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
			insertedTotal += inserted;
		}

		if (insertedTotal > 0) {
			villagerInventory.setChanged();
		}
		return insertedTotal;
	}

	static int depositItem(Container stockpile, Container inventory, Item item) {
		int insertedTotal = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(item) || SettlementFoodAge.isMarked(stack) || SettlementShearingCargo.isMarked(stack)) {
				continue;
			}
			int inserted = insert(stockpile, stack);
			if (inserted == 0) {
				continue;
			}
			stack.shrink(inserted);
			if (stack.isEmpty()) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
			insertedTotal += inserted;
		}
		if (insertedTotal > 0) {
			inventory.setChanged();
		}
		return insertedTotal;
	}

	static int communalFoodPoints(Container stockpile) {
		return stockpile == null ? 0 : foodPoints(stockpile);
	}

	static int communalResourceCount(Container stockpile) {
		if (stockpile == null) {
			return 0;
		}
		int total = 0;
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			ItemStack stack = stockpile.getItem(slot);
			if (SettlementCargo.isMaterial(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int foodPoints(Container inventory) {
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			int points = SettlementFood.points(stack);
			if (points > 0) {
				total += stack.getCount() * points;
			}
		}
		return total;
	}
}
