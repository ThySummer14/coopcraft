package com.thysummer.coopcraft.civilization;

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
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
			return;
		}
		if (Math.floorMod(gameTime + settlement.id().getLeastSignificantBits(), DECAY_INTERVAL_TICKS) != 0) {
			return;
		}
		for (SettlementMember member : data.membersOf(settlement)) {
			if (Math.floorMod(gameTime + member.villagerId().getLeastSignificantBits(), DECAY_INTERVAL_TICKS) != 0) {
				continue;
			}
			if (level.getEntity(member.villagerId()) instanceof Villager villager
				&& villager.isAlive()
				&& villager.distanceToSqr(settlement.center().getCenter()) <= MEMBER_RADIUS_SQUARED) {
				data.setSatiety(settlement, member.villagerId(), decayedSatiety(data.needsOf(settlement, member.villagerId()).satiety()));
			}
		}
	}

	public static boolean hasFood(Container first, Container second) {
		return hasFood(first) || hasFood(second);
	}

	public static int eat(ServerLevel level, SettlementSavedData data, Settlement settlement, Villager villager) {
		int current = data.needsOf(settlement, villager.getUUID()).satiety();
		if (current >= FULL_AT) {
			return 0;
		}
		long requestEventId = currentCareRequest(level, data, settlement, villager);
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
			return SettlementSimulationService.eat(data, settlement, villager.getUUID(), requestEventId);
		}
		return consumeRealFood(level, data, settlement, villager, null, requestEventId).nutrition() / 5;
	}

	static CareMealResult eatAfterCare(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		Villager caregiver,
		long existingRequestEventId
	) {
		int current = data.needsOf(settlement, villager.getUUID()).satiety();
		if (current >= FULL_AT) {
			return previousCareMeal(data, settlement, villager, existingRequestEventId);
		}
		if (SettlementSimulationService.isSurvivalEnabled(data, settlement)) {
			SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
			long previousEventId = state.world().ledger().events().isEmpty()
				? 0 : state.world().ledger().events().getLast().eventId();
			int nutrition = SettlementSimulationService.eat(data, settlement, villager.getUUID(), existingRequestEventId);
			if (nutrition == 0) {
				return previousCareMeal(data, settlement, villager, existingRequestEventId);
			}
			var events = state.world().ledger().events().stream()
				.filter(event -> event.eventId() > previousEventId)
				.toList();
			String foodItem = events.stream()
				.filter(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.CONSUME)
				.flatMap(event -> event.inputs().stream())
				.findFirst()
				.map(flow -> flow.resourceType().name())
				.orElse("");
			return new CareMealResult(
				nutrition, foodItem, "common_store", events.stream().map(event -> event.eventId()).toList()
			);
		}
		CareMealResult meal = consumeRealFood(level, data, settlement, villager, caregiver, existingRequestEventId);
		return meal.nutrition() > 0 ? meal : previousCareMeal(data, settlement, villager, existingRequestEventId);
	}

	private static long currentCareRequest(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return 0;
		}
		long day = level.getDayTime() / net.minecraft.world.level.Level.TICKS_PER_DAY;
		return state.careFoodRequest(villager.getUUID(), day).orElse(0L);
	}

	private static CareMealResult consumeRealFood(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		Villager caregiver,
		long requestEventId
	) {
		int current = data.needsOf(settlement, villager.getUUID()).satiety();
		ConsumedFood consumed = consumeFood(villager.getInventory(), "resident_inventory");
		if (consumed.foodPoints() == 0 && caregiver != null) {
			consumed = consumeFood(caregiver.getInventory(), "caregiver_inventory");
		}
		if (consumed.foodPoints() == 0) {
			consumed = consumeFood(SettlementStockpile.getStockpile(level, data, settlement), "public_stockpile");
		}
		if (consumed.foodPoints() == 0) {
			return CareMealResult.empty();
		}
		data.setSatiety(settlement, villager.getUUID(), restoredSatiety(current, consumed.foodPoints()));
		long eventId = recordRealCareMeal(data, settlement, villager, consumed, requestEventId);
		return new CareMealResult(
			consumed.foodPoints() * 5, consumed.itemName(), consumed.source(),
			eventId > 0 ? java.util.List.of(eventId) : java.util.List.of()
		);
	}

	private static long recordRealCareMeal(
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		ConsumedFood consumed,
		long requestEventId
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || requestEventId <= 0) {
			return 0;
		}
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		java.util.Map<String, String> context = new java.util.LinkedHashMap<>();
		context.put("authority", "minecraft");
		context.put("mealNutrition", Integer.toString(consumed.foodPoints() * 5));
		context.put("foodItem", consumed.itemName());
		context.put("foodSource", consumed.source());
		context.put("villagerId", villager.getUUID().toString());
		long eventId = state.world().recordExternalConsumption(personId, context, java.util.List.of(requestEventId));
		data.setDirty();
		return eventId;
	}

	private static CareMealResult previousCareMeal(
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		long requestEventId
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || requestEventId <= 0) {
			return CareMealResult.empty();
		}
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		var events = state.world().ledger().events();
		for (int index = events.size() - 1; index >= 0; index--) {
			var event = events.get(index);
			if (event.eventId() < requestEventId) {
				break;
			}
			if (event.type() != com.thysummer.coopcraft.civilization.simulation.EventType.CONSUME
				|| !event.actors().equals(java.util.List.of(personId))
				|| state.world().ledger().traceAncestry(event.eventId()).stream()
					.noneMatch(cause -> cause.eventId() == requestEventId)) {
				continue;
			}
			int nutrition = event.context().containsKey("mealNutrition")
				? Integer.parseInt(event.context().get("mealNutrition"))
				: (int) Math.min(Integer.MAX_VALUE, event.inputs().stream()
					.mapToLong(flow -> Math.multiplyExact(flow.quantity(), flow.nutritionPerUnit())).sum());
			String foodItem = event.context().containsKey("foodItem") ? event.context().get("foodItem")
				: event.inputs().stream().findFirst().map(flow -> flow.resourceType().name()).orElse("");
			String foodSource = event.context().getOrDefault("foodSource", "common_store");
			return new CareMealResult(nutrition, foodItem, foodSource, java.util.List.of(event.eventId()));
		}
		return CareMealResult.empty();
	}

	static int consumeOneFood(Container inventory) {
		return consumeFood(inventory, "").foodPoints();
	}

	private static ConsumedFood consumeFood(Container inventory, String source) {
		if (inventory == null) {
			return new ConsumedFood(0, "", "");
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			Integer points = Villager.FOOD_POINTS.get(stack.getItem());
			if (points != null && points > 0) {
				String itemName = stack.getItem().getDescriptionId();
				inventory.removeItem(slot, 1);
				inventory.setChanged();
				return new ConsumedFood(points, itemName, source);
			}
		}
		return new ConsumedFood(0, "", "");
	}

	record CareMealResult(int nutrition, String foodItem, String foodSource, java.util.List<Long> eventIds) {
		static CareMealResult empty() {
			return new CareMealResult(0, "", "", java.util.List.of());
		}
	}

	private record ConsumedFood(int foodPoints, String itemName, String source) {
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
			Integer points = Villager.FOOD_POINTS.get(inventory.getItem(slot).getItem());
			if (points != null && points > 0) {
				return true;
			}
		}
		return false;
	}
}
