package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;

public final class SettlementStockpile {
	private static final long WORK_INTERVAL_TICKS = 40;
	private static final int BASIC_FOOD_POINTS = 12;
	private static final double MEMBER_RADIUS_SQUARED = 64.0 * 64.0;
	private static final Set<Item> COMMUNAL_RESOURCES = Set.of(Items.COBBLESTONE, Items.STICK, Items.SMOOTH_STONE);

	private SettlementStockpile() {
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement, long gameTime) {
		if (data.getStockpile(settlement).isEmpty()
			|| Math.floorMod(gameTime + settlement.id().getLeastSignificantBits(), WORK_INTERVAL_TICKS) != 0) {
			return;
		}

		Container stockpile = getStockpile(level, data, settlement);
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
		if (level.getEntity(member.villagerId()) instanceof Villager villager
			&& villager.isAlive()
			&& villager.distanceToSqr(settlement.center().getCenter()) <= MEMBER_RADIUS_SQUARED) {
			depositExcessFood(stockpile, villager.getInventory(), member.personality().foodRetentionPoints(BASIC_FOOD_POINTS));
			distributeBasicFood(stockpile, villager.getInventory(), member.personality().basicFoodPoints(BASIC_FOOD_POINTS));
			depositGatheredResources(stockpile, villager.getInventory());
		}
	}

	static Container getStockpile(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		BlockPos pos = data.getStockpile(settlement).orElse(null);
		if (pos == null) {
			return null;
		}
		if (level.getBlockState(pos).getBlock() instanceof ChestBlock chest) {
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

	static int depositExcessFood(Container stockpile, Container villagerInventory) {
		return depositExcessFood(stockpile, villagerInventory, BASIC_FOOD_POINTS);
	}

	static int depositExcessFood(Container stockpile, Container villagerInventory, int retainedFoodPoints) {
		int retained = Math.max(BASIC_FOOD_POINTS, retainedFoodPoints);
		int remainingFoodPoints = foodPoints(villagerInventory);
		int insertedTotal = 0;
		for (int slot = 0; slot < villagerInventory.getContainerSize() && remainingFoodPoints > retained; slot++) {
			ItemStack stack = villagerInventory.getItem(slot);
			Integer points = Villager.FOOD_POINTS.get(stack.getItem());
			if (points == null || points <= 0) {
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
			Integer points = Villager.FOOD_POINTS.get(stack.getItem());
			if (points == null || points <= 0) {
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
		int insertedTotal = 0;
		for (int slot = 0; slot < villagerInventory.getContainerSize(); slot++) {
			ItemStack stack = villagerInventory.getItem(slot);
			if (!stack.is(ItemTags.LOGS) && !COMMUNAL_RESOURCES.contains(stack.getItem())) {
				continue;
			}

			int inserted = insert(stockpile, stack);
			if (inserted == 0) {
				continue;
			}
			stack.shrink(inserted);
			if (stack.isEmpty()) {
				villagerInventory.setItem(slot, ItemStack.EMPTY);
			}
			insertedTotal += inserted;
		}

		if (insertedTotal > 0) {
			villagerInventory.setChanged();
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
			if (stack.is(ItemTags.LOGS) || COMMUNAL_RESOURCES.contains(stack.getItem())) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int foodPoints(Container inventory) {
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			Integer points = Villager.FOOD_POINTS.get(stack.getItem());
			if (points != null) {
				total += stack.getCount() * points;
			}
		}
		return total;
	}
}
