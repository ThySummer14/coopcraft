package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

public final class SettlementCareService {
	private static final long REVIEW_INTERVAL_TICKS = 100;
	private static final double ACTIVE_RADIUS_SQUARED = 24.0 * 24.0;

	private SettlementCareService() {
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return;
		}
		long currentDay = currentDay(level);
		if (state.carePlanDay() == currentDay && level.getGameTime() % REVIEW_INTERVAL_TICKS != 0) {
			return;
		}
		List<CareAssignment> existing = state.carePlanDay() == currentDay ? state.careAssignments() : List.of();
		List<CareAssignment> planned = CarePlanner.plan(level, data, settlement, currentDay, existing);
		if (state.carePlanDay() != currentDay) {
			state.resetCareHours();
		}
		if (state.carePlanDay() != currentDay || !planned.equals(state.careAssignments())) {
			state.replaceCareAssignments(currentDay, planned);
			data.setDirty();
		}
		ensureCareFoodRequests(level, data, settlement, state, currentDay);
	}

	private static void ensureCareFoodRequests(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		long currentDay
	) {
		boolean survival = state.survivalEnabled();
		var stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		boolean commonFoodAvailable = survival
			? SettlementSimulationService.hasFood(data, settlement) : SettlementNeeds.hasFood(stockpile, null);
		for (SettlementMember member : data.membersOf(settlement)) {
			if (!(level.getEntity(member.villagerId()) instanceof Villager dependent)
				|| !dependent.isAlive() || dependent.distanceToSqr(settlement.center().getCenter()) > 64.0 * 64.0) {
				continue;
			}
			String personId = state.residentId(dependent.getUUID()).orElse(null);
			if (personId == null || !state.world().person(personId).needsCare()) {
				continue;
			}
			int satiety = data.needsOf(settlement, dependent.getUUID()).satiety();
			boolean foodAvailable = commonFoodAvailable || !survival && SettlementNeeds.hasFood(dependent.getInventory(), null);
			if (satiety < SettlementNeeds.FULL_AT && !foodAvailable && state.careFoodRequest(dependent.getUUID(), currentDay).isEmpty()) {
				state.ensureCareFoodRequest(dependent.getUUID(), currentDay, SettlementNeeds.FULL_AT - satiety);
				data.setDirty();
			}
		}
	}

	public static Optional<CareAssignment> activeForCaregiver(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID caregiverId
	) {
		return active(level, data, settlement, state -> state.careForCaregiver(caregiverId));
	}

	public static Optional<CareAssignment> activeForDependent(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID dependentId
	) {
		return active(level, data, settlement, state -> state.careForDependent(dependentId));
	}

	private static Optional<CareAssignment> active(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		java.util.function.Function<SettlementSimulationState, Optional<CareAssignment>> selector
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return Optional.empty();
		}
		Optional<CareAssignment> selected = selector.apply(state)
			.filter(assignment -> !assignment.completed() && assignment.day() == currentDay(level));
		if (selected.isEmpty()) {
			return Optional.empty();
		}
		CareAssignment assignment = selected.get();
		Villager caregiver = resident(level, assignment.caregiverId());
		Villager dependent = resident(level, assignment.dependentId());
		if (caregiver == null || dependent == null || !data.isMember(settlement, caregiver.getUUID())
			|| !data.isMember(settlement, dependent.getUUID()) || caregiver.distanceToSqr(dependent) > ACTIVE_RADIUS_SQUARED) {
			return Optional.empty();
		}
		return Optional.of(assignment);
	}

	public static boolean progressCare(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID caregiverId
	) {
		Optional<CareAssignment> active = activeForCaregiver(level, data, settlement, caregiverId);
		if (active.isEmpty()) {
			return true;
		}
		CareAssignment assignment = active.get().advance();
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		state.updateCareAssignment(assignment);
		if (assignment.completed()) {
			String caregiverPerson = state.residentId(caregiverId).orElseThrow();
			String dependentPerson = state.residentId(assignment.dependentId()).orElseThrow();
			Person caregiver = state.world().person(caregiverPerson);
			caregiver.recordCareHours(CareAssignment.LABOR_HOURS);
			long day = currentDay(level);
			long requestEventId = state.careFoodRequest(assignment.dependentId(), day).orElse(0L);
			Villager dependent = resident(level, assignment.dependentId());
			SettlementNeeds.CareMealResult meal = SettlementNeeds.eatAfterCare(
				level, data, settlement, dependent, resident(level, caregiverId), requestEventId
			);
			Map<String, String> context = new LinkedHashMap<>();
			context.put("source", "minecraft_villager");
			context.put("minecraftDay", Long.toString(day));
			context.put("ticks", Integer.toString(CareAssignment.DURATION_TICKS));
			context.put("villagerId", assignment.dependentId().toString());
			context.put("caregiverId", caregiverId.toString());
			if (meal.nutrition() > 0) {
				context.put("mealNutrition", Integer.toString(meal.nutrition()));
				context.put("foodItem", meal.foodItem());
				context.put("foodSource", meal.foodSource());
			}
			List<Long> causes = new ArrayList<>();
			if (requestEventId > 0) {
				causes.add(requestEventId);
			}
			for (long eventId : meal.eventIds()) {
				if (!causes.contains(eventId)) {
					causes.add(eventId);
				}
			}
			state.world().recordCareProvided(
				caregiverPerson, dependentPerson, CareAssignment.LABOR_HOURS, context, causes
			);
			data.setDirty();
		} else if (assignment.elapsedTicks() % 20 == 0) {
			data.setDirty();
		}
		return assignment.completed();
	}

	private static Villager resident(ServerLevel level, UUID villagerId) {
		return level.getEntity(villagerId) instanceof Villager villager && villager.isAlive() && !villager.isRemoved() ? villager : null;
	}

	private static long currentDay(ServerLevel level) {
		return level.getDayTime() / Level.TICKS_PER_DAY;
	}
}
