package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

public final class SettlementCoordination {
	static final int FOOD_RESERVE_POINTS_PER_MEMBER = 8;
	private static final int EMERGENCY_FOOD_POINTS_PER_MEMBER = 2;
	private static final long REVIEW_INTERVAL_TICKS = 100;
	private static final double STOCKPILE_KNOWLEDGE_RADIUS_SQUARED = 16.0 * 16.0;
	private static final double PROJECT_KNOWLEDGE_RADIUS_SQUARED = 24.0 * 24.0;

	private SettlementCoordination() {
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return;
		}
		long day = currentDay(level);
		CoordinationPlan previous = state.coordinationPlan();
		long reviewTime = level.getGameTime() + Math.floorMod(settlement.id().getLeastSignificantBits(), REVIEW_INTERVAL_TICKS);
		if (previous.day() == day && !state.coordinationDirty() && Math.floorMod(reviewTime, REVIEW_INTERVAL_TICKS) != 0) {
			return;
		}
		List<WorkReservation> oldReservations = previous.day() == day ? previous.reservations() : List.of();
		List<Villager> adults = data.membersOf(settlement).stream()
			.map(member -> level.getEntity(member.villagerId()))
			.filter(entity -> entity instanceof Villager)
			.map(entity -> (Villager) entity)
			.filter(villager -> villager.isAlive() && !villager.isRemoved() && !villager.isBaby())
			.filter(villager -> state.residentId(villager.getUUID()).isPresent())
			.toList();

		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		int registered = data.registeredCount(settlement);
		int foodPoints = SettlementStockpile.communalFoodPoints(stockpile);
		BlockPos foodSource = data.getStockpile(settlement).orElse(settlement.center());
		Set<UUID> foodObservers = new HashSet<>();
		boolean hungryWithoutFood = false;
		for (Villager adult : adults) {
			boolean hungry = data.needsOf(settlement, adult.getUUID()).satiety() <= SettlementNeeds.HUNGRY_AT
				&& !hasFood(level, data, settlement, adult, stockpile);
			hungryWithoutFood |= hungry;
			if (hungry || adult.distanceToSqr(foodSource.getCenter()) <= STOCKPILE_KNOWLEDGE_RADIUS_SQUARED) {
				foodObservers.add(adult.getUUID());
			}
		}
		boolean foodShortage = stockpile != null && registered > 0 && foodPoints < registered * FOOD_RESERVE_POINTS_PER_MEMBER;
		boolean foodEmergency = hungryWithoutFood || foodShortage && foodPoints <= registered * EMERGENCY_FOOD_POINTS_PER_MEMBER;
		NeedSignal food = !foodShortage ? null : NeedSignal.observed(
			settlement.id(), NeedSignal.Type.COMMON_FOOD_SHORTAGE,
			foodEmergency ? NeedSignal.Severity.URGENT : NeedSignal.Severity.WATCH,
			day, foodSource, registered, foodObservers
		);

		ConstructionJob job = data.getConstructionJob(settlement).orElse(null);
		boolean shelterGap = job != null || ConstructionPlanner.housingPlan(data, settlement).needsHousing();
		BlockPos projectSource = job == null ? settlement.center() : job.origin();
		Set<UUID> projectObservers = new HashSet<>();
		for (Villager adult : adults) {
			if (adult.distanceToSqr(projectSource.getCenter()) <= PROJECT_KNOWLEDGE_RADIUS_SQUARED) {
				projectObservers.add(adult.getUUID());
			}
		}
		for (WorkReservation reservation : oldReservations) {
			if (reservation.task() == WorkReservation.Task.BUILD) {
				projectObservers.add(reservation.actorId());
			}
		}
		NeedSignal shelter = !shelterGap ? null : NeedSignal.observed(
			settlement.id(), NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH,
			day, projectSource, Math.max(1, ConstructionPlanner.housingPlan(data, settlement).missingBeds()), projectObservers
		);
		boolean constructionAvailable = shelterGap && !foodEmergency
			&& ConstructionSystem.nextBuildTarget(level, data, settlement).isPresent();
		Map<UUID, WorkRole> roles = LaborAllocator.allocate(adults.stream()
			.map(villager -> new LaborAllocator.Worker(
				villager.getUUID(), data.personalityOf(settlement, villager.getUUID())
			))
			.toList());
		List<CoordinationPlanner.Resident> residents = new ArrayList<>();
		for (Villager adult : adults) {
			UUID id = adult.getUUID();
			VillagerPersonality personality = data.personalityOf(settlement, id);
			residents.add(new CoordinationPlanner.Resident(
				id,
				roles.getOrDefault(id, WorkRole.GENERALIST),
				personality.diligence(),
				personality.sociability(),
				data.needsOf(settlement, id).satiety(),
				food != null && food.knownTo(id),
				shelter != null && shelter.knownTo(id),
				state.careForCaregiver(id)
					.filter(assignment -> assignment.day() == day && !assignment.completed()).isPresent(),
				SettlementForaging.canForage(data, settlement),
				constructionAvailable
			));
		}
		CoordinationPlan planned = CoordinationPlanner.plan(day, residents, food, shelter, oldReservations);
		if (!planned.equals(previous)) {
			recordTransitions(level, state, previous, planned);
			state.replaceCoordinationPlan(planned);
			data.setDirty();
		}
		state.clearCoordinationDirty();
	}

	public static void requestReplan(SettlementSavedData data, Settlement settlement) {
		data.simulationOf(settlement).ifPresent(SettlementSimulationState::requestCoordinationReplan);
	}

	public static Optional<WorkReservation> availableFor(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID actorId
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || state.coordinationPlan().day() != currentDay(level) || !data.isMember(settlement, actorId)) {
			return Optional.empty();
		}
		return state.coordinationPlan().reservationFor(actorId)
			.filter(reservation -> reservation.status() == WorkReservation.Status.COMMITTED
				|| reservation.status() == WorkReservation.Status.ACTIVE
				|| reservation.status() == WorkReservation.Status.PAUSED && reservation.cause().equals("forage_unavailable"))
			.filter(reservation -> level.getEntity(actorId) instanceof Villager villager
				&& villager.isAlive() && !villager.isRemoved() && !villager.isBaby());
	}

	public static WorkReservation begin(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID actorId
	) {
		WorkReservation current = availableFor(level, data, settlement, actorId).orElseThrow();
		WorkReservation activated = current.activate();
		if (current.status() == WorkReservation.Status.PAUSED) {
			SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
			record(data, state, activated, EventType.WORK_RESUMED);
		}
		return update(data, settlement, activated);
	}

	public static void pause(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID actorId,
		String cause
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || state.coordinationPlan().day() != currentDay(level)) {
			return;
		}
		state.coordinationPlan().reservationFor(actorId).ifPresent(reservation -> {
			WorkReservation paused = reservation.pause(cause);
			if (!paused.equals(reservation)) {
				record(data, state, paused, EventType.WORK_PAUSED);
				update(data, settlement, paused);
			}
		});
	}

	public static void rest(ServerLevel level, SettlementSavedData data, Settlement settlement, UUID actorId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || state.coordinationPlan().day() != currentDay(level)) {
			return;
		}
		state.coordinationPlan().reservationFor(actorId).ifPresent(reservation -> update(data, settlement, reservation.rest()));
	}

	public static void complete(ServerLevel level, SettlementSavedData data, Settlement settlement, UUID actorId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || state.coordinationPlan().day() != currentDay(level)) {
			return;
		}
		state.coordinationPlan().reservationFor(actorId).ifPresent(reservation -> {
			WorkReservation completed = reservation.complete();
			if (!completed.equals(reservation)) {
				record(data, state, completed, EventType.WORK_COMPLETED);
				update(data, settlement, completed);
				requestReplan(data, settlement);
			}
		});
	}

	public static Optional<String> pausedCause(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		UUID actorId
	) {
		return data.simulationOf(settlement)
			.filter(state -> state.coordinationPlan().day() == currentDay(level))
			.flatMap(state -> state.coordinationPlan().reservationFor(actorId))
			.filter(reservation -> reservation.status() == WorkReservation.Status.PAUSED)
			.map(WorkReservation::cause);
	}

	public static List<String> publicAffairs(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || state.coordinationPlan().day() != currentDay(level)) {
			return List.of();
		}
		CoordinationPlan plan = state.coordinationPlan();
		List<String> affairs = new ArrayList<>();
		Optional<WorkReservation> builder = plan.reservations().stream()
			.filter(reservation -> reservation.task() == WorkReservation.Task.BUILD).findFirst();
		boolean pausedForFood = builder
			.filter(reservation -> reservation.status() == WorkReservation.Status.PAUSED)
			.filter(reservation -> reservation.cause().equals("food_shortage") || reservation.cause().equals("personal_hunger"))
			.isPresent() && plan.need(NeedSignal.Type.COMMON_FOOD_SHORTAGE)
			.filter(need -> need.severity() == NeedSignal.Severity.URGENT).isPresent();
		if (!pausedForFood) {
			plan.need(NeedSignal.Type.COMMON_FOOD_SHORTAGE).ifPresent(need -> affairs.add(
				need.severity() == NeedSignal.Severity.URGENT ? "food_emergency" : "common_food_shortage"
			));
		}
		if (builder.filter(reservation -> reservation.status() == WorkReservation.Status.PAUSED).isPresent()) {
			affairs.add(builder.get().cause().equals("construction_blocked")
				? "construction_blocked" : "construction_paused_food");
		} else if (plan.need(NeedSignal.Type.SHELTER_GAP).isPresent() && builder.isEmpty()) {
			affairs.add("construction_waiting_volunteers");
		}
		return List.copyOf(affairs);
	}

	private static WorkReservation update(
		SettlementSavedData data,
		Settlement settlement,
		WorkReservation updated
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		state.updateWorkReservation(updated);
		data.setDirty();
		return updated;
	}

	private static void recordTransitions(
		ServerLevel level,
		SettlementSimulationState state,
		CoordinationPlan previous,
		CoordinationPlan planned
	) {
		Map<UUID, WorkReservation> before = new HashMap<>();
		if (previous.day() == planned.day()) {
			previous.reservations().forEach(reservation -> before.put(reservation.actorId(), reservation));
		}
		for (WorkReservation reservation : planned.reservations()) {
			WorkReservation old = before.get(reservation.actorId());
			if (old == null) {
				record(state, reservation, EventType.WORK_COMMITTED);
			} else if (reservation.status() == WorkReservation.Status.PAUSED
				&& (old.status() != WorkReservation.Status.PAUSED || !old.cause().equals(reservation.cause()))) {
				record(state, reservation, EventType.WORK_PAUSED);
			} else if (old.status() == WorkReservation.Status.PAUSED && reservation.status() != WorkReservation.Status.PAUSED) {
				record(state, reservation, EventType.WORK_RESUMED);
			}
		}
	}

	private static void record(
		SettlementSavedData data,
		SettlementSimulationState state,
		WorkReservation reservation,
		EventType type
	) {
		record(state, reservation, type);
		data.setDirty();
	}

	private static void record(SettlementSimulationState state, WorkReservation reservation, EventType type) {
		state.residentId(reservation.actorId()).ifPresent(actor -> {
			String reason = reservation.cause().isEmpty()
				? reservation.task() == WorkReservation.Task.FORAGE ? "food_shortage" : "shelter_gap"
				: reservation.cause();
			state.world().recordWorkCoordination(actor, type, Map.of(
				"task", "coopcraft.coordination.task." + reservation.task().name().toLowerCase(java.util.Locale.ROOT),
				"reason", reason,
				"reservation", reservation.id().toString()
			));
		});
	}

	private static boolean hasFood(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		Container stockpile
	) {
		return SettlementSimulationService.isSurvivalEnabled(data, settlement)
			? SettlementSimulationService.hasFoodFor(data, settlement, villager.getUUID())
			: SettlementNeeds.hasFood(villager.getInventory(), stockpile);
	}

	private static long currentDay(ServerLevel level) {
		return level.getDayTime() / Level.TICKS_PER_DAY;
	}
}
