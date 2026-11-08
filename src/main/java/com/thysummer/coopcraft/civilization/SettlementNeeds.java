package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

public final class SettlementNeeds {
	public static final int HUNGRY_AT = 35;
	public static final int FULL_AT = 95;
	private static final long DECAY_INTERVAL_TICKS = 600;
	private static final double MEMBER_RADIUS_SQUARED = 64.0 * 64.0;

	private SettlementNeeds() {
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement, long gameTime) {
		tick(data, settlement, gameTime, villagerId ->
			level.getEntity(villagerId) instanceof Villager villager
				&& villager.isAlive()
				&& villager.distanceToSqr(settlement.center().getCenter()) <= MEMBER_RADIUS_SQUARED);
	}

	static void tick(SettlementSavedData data, Settlement settlement, long gameTime, Predicate<UUID> eligibleMember) {
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement) || !isDecayTick(settlement.id(), gameTime)) {
			return;
		}
		// Stagger settlements, not their members: a second UUID gate would starve most members of decay.
		for (SettlementMember member : data.membersOf(settlement)) {
			if (eligibleMember.test(member.villagerId())) {
				data.setSatiety(settlement, member.villagerId(), decayedSatiety(data.needsOf(settlement, member.villagerId()).satiety()));
			}
		}
	}

	static boolean isDecayTick(UUID settlementId, long gameTime) {
		// Reduce before adding so UUID/time overflow cannot change the 600-tick phase.
		long phase = Math.floorMod(settlementId.getLeastSignificantBits(), DECAY_INTERVAL_TICKS);
		return (Math.floorMod(gameTime, DECAY_INTERVAL_TICKS) + phase) % DECAY_INTERVAL_TICKS == 0;
	}

	public static boolean hasFood(Container first, Container second) {
		return hasFood(first) || hasFood(second);
	}

	public static int eat(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager villager) {
		int current = data.needsOf(settlement, villager.getUUID()).satiety();
		if (current >= FULL_AT) {
			return 0;
		}
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
			return SettlementSimulationService.eat(data, settlement, villager.getUUID());
		}
		int foodPoints = consumeOneFood(villager.getInventory());
		if (foodPoints == 0) {
			foodPoints = consumeOneFood(SettlementStockpile.getStockpile(level, data, settlement));
		}
		if (foodPoints > 0) {
			data.setSatiety(settlement, villager.getUUID(), restoredSatiety(current, foodPoints));
		}
		return foodPoints;
	}

	static int consumeOneFood(Container inventory) {
		if (inventory == null) {
			return 0;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			int points = SettlementFood.points(stack.getItem());
			if (points > 0) {
				inventory.removeItem(slot, 1);
				inventory.setChanged();
				return points;
			}
		}
		return 0;
	}

	static int restoredSatiety(int current, int foodPoints) {
		return Math.min(100, current + foodPoints * 5);
	}

	static int decayedSatiety(int current) {
		return Math.max(0, current - 1);
	}

	private static boolean hasFood(Container inventory) {
		if (inventory == null) {
			return false;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			int points = SettlementFood.points(inventory.getItem(slot).getItem());
			if (points > 0) {
				return true;
			}
		}
		return false;
	}
}
