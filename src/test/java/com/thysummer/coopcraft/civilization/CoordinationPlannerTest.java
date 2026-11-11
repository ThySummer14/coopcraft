package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationPlannerTest {
	private static final UUID SETTLEMENT = UUID.randomUUID();
	private static final UUID FORAGER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID BUILDER = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void foodEmergencyPausesExistingBuilderWithoutReplacingCommitment() {
		NeedSignal food = signal(NeedSignal.Type.COMMON_FOOD_SHORTAGE, NeedSignal.Severity.URGENT, FORAGER, BUILDER);
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, FORAGER, BUILDER);
		WorkReservation building = WorkReservation.commit(4, BUILDER, shelter).activate();

		CoordinationPlan plan = CoordinationPlanner.plan(
			4, List.of(resident(FORAGER, WorkRole.FORAGER, 50, 90, 80), resident(BUILDER, WorkRole.BUILDER, 90, 60, 80)),
			food, shelter, List.of(building)
		);

		WorkReservation paused = plan.reservationFor(BUILDER).orElseThrow();
		assertEquals(building.id(), paused.id());
		assertEquals(WorkReservation.Status.PAUSED, paused.status());
		assertEquals("food_shortage", paused.cause());
		assertEquals(WorkReservation.Task.FORAGE, plan.reservationFor(FORAGER).orElseThrow().task());
	}

	@Test
	void foodRecoveryResumesTheSameBuilder() {
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, BUILDER);
		WorkReservation previous = WorkReservation.commit(4, BUILDER, shelter).pause("food_shortage");

		CoordinationPlan plan = CoordinationPlanner.plan(
			4, List.of(resident(BUILDER, WorkRole.BUILDER, 90, 60, 80)), null, shelter, List.of(previous)
		);

		WorkReservation resumed = plan.reservationFor(BUILDER).orElseThrow();
		assertEquals(previous.id(), resumed.id());
		assertEquals(WorkReservation.Status.COMMITTED, resumed.status());
		assertTrue(resumed.cause().isEmpty());
	}

	@Test
	void lowReserveDoesNotForceEveryoneOffConstruction() {
		NeedSignal food = signal(NeedSignal.Type.COMMON_FOOD_SHORTAGE, NeedSignal.Severity.WATCH, FORAGER, BUILDER);
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, FORAGER, BUILDER);
		WorkReservation building = WorkReservation.commit(4, BUILDER, shelter).activate();

		CoordinationPlan plan = CoordinationPlanner.plan(
			4, List.of(resident(FORAGER, WorkRole.FORAGER, 50, 90, 80), resident(BUILDER, WorkRole.BUILDER, 90, 60, 80)),
			food, shelter, List.of(building)
		);

		assertEquals(WorkReservation.Status.ACTIVE, plan.reservationFor(BUILDER).orElseThrow().status());
		assertEquals(WorkReservation.Status.COMMITTED, plan.reservationFor(FORAGER).orElseThrow().status());
	}

	@Test
	void unwillingOrUninformedResidentsAreNotCommanded() {
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH);
		CoordinationPlanner.Resident reluctant = resident(BUILDER, WorkRole.GENERALIST, 10, 10, 80);
		CoordinationPlanner.Resident uninformed = new CoordinationPlanner.Resident(
			BUILDER, WorkRole.BUILDER, 100, 100, 80, false, false, false, true, true
		);

		assertTrue(CoordinationPlanner.plan(4, List.of(reluctant), null, shelter, List.of()).reservations().isEmpty());
		assertTrue(CoordinationPlanner.plan(4, List.of(uninformed), null, shelter, List.of()).reservations().isEmpty());
	}

	@Test
	void careAndPersonalHungerPreemptNonUrgentBuilding() {
		NeedSignal food = signal(NeedSignal.Type.COMMON_FOOD_SHORTAGE, NeedSignal.Severity.URGENT, BUILDER);
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, BUILDER);
		WorkReservation building = WorkReservation.commit(4, BUILDER, shelter).activate();
		CoordinationPlanner.Resident caregiver = new CoordinationPlanner.Resident(
			BUILDER, WorkRole.BUILDER, 90, 80, 80, true, true, true, true, true
		);
		CoordinationPlanner.Resident hungry = resident(BUILDER, WorkRole.BUILDER, 90, 80, 10);

		assertEquals("care_commitment", CoordinationPlanner.plan(
			4, List.of(caregiver), food, shelter, List.of(building)
		).reservationFor(BUILDER).orElseThrow().cause());
		assertEquals("personal_hunger", CoordinationPlanner.plan(
			4, List.of(hungry), food, shelter, List.of(building)
		).reservationFor(BUILDER).orElseThrow().cause());
	}

	@Test
	void materialShortagePausesInsteadOfInventingACompletedProject() {
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, BUILDER);
		CoordinationPlanner.Resident resident = new CoordinationPlanner.Resident(
			BUILDER, WorkRole.BUILDER, 90, 80, 80, false, true, false, true, false
		);

		CoordinationPlan plan = CoordinationPlanner.plan(4, List.of(resident), null, shelter, List.of());

		assertEquals(WorkReservation.Status.PAUSED, plan.reservationFor(BUILDER).orElseThrow().status());
		assertEquals("construction_blocked", plan.reservationFor(BUILDER).orElseThrow().cause());
	}

	@Test
	void oneActorCannotHoldTwoWorkReservations() {
		NeedSignal food = signal(NeedSignal.Type.COMMON_FOOD_SHORTAGE, NeedSignal.Severity.WATCH, BUILDER);
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, BUILDER);

		assertThrows(IllegalArgumentException.class, () -> new CoordinationPlan(
			4, List.of(food, shelter),
			List.of(WorkReservation.commit(4, BUILDER, food), WorkReservation.commit(4, BUILDER, shelter))
		));
	}

	@Test
	void reservationTransitionsAreIdempotentAndCannotRestartCompletion() {
		NeedSignal shelter = signal(NeedSignal.Type.SHELTER_GAP, NeedSignal.Severity.WATCH, BUILDER);
		WorkReservation reservation = WorkReservation.commit(4, BUILDER, shelter);
		WorkReservation active = reservation.activate();
		assertEquals(active, active.activate());
		assertEquals(WorkReservation.Status.PAUSED, active.pause("food_shortage").status());
		assertEquals(WorkReservation.Status.COMMITTED, active.pause("food_shortage").resume().status());
		assertEquals(WorkReservation.Status.COMMITTED, active.rest().status());
		assertThrows(IllegalStateException.class, () -> active.complete().activate());
	}

	@Test
	void unavailableForageStaysPausedAcrossPeriodicPlans() {
		NeedSignal food = signal(NeedSignal.Type.COMMON_FOOD_SHORTAGE, NeedSignal.Severity.WATCH, FORAGER);
		WorkReservation previous = WorkReservation.commit(4, FORAGER, food).pause("forage_unavailable");
		List<CoordinationPlanner.Resident> residents = List.of(resident(FORAGER, WorkRole.FORAGER, 50, 90, 80));

		CoordinationPlan first = CoordinationPlanner.plan(4, residents, food, null, List.of(previous));
		CoordinationPlan second = CoordinationPlanner.plan(4, residents, food, null, first.reservations());

		assertEquals(first, second);
		assertEquals(WorkReservation.Status.PAUSED, second.reservationFor(FORAGER).orElseThrow().status());
		assertEquals("forage_unavailable", second.reservationFor(FORAGER).orElseThrow().cause());
	}

	private static CoordinationPlanner.Resident resident(UUID id, WorkRole role, int diligence, int sociability, int satiety) {
		return new CoordinationPlanner.Resident(id, role, diligence, sociability, satiety,
			true, true, false, true, true);
	}

	private static NeedSignal signal(NeedSignal.Type type, NeedSignal.Severity severity, UUID... observers) {
		return NeedSignal.observed(SETTLEMENT, type, severity, 4, BlockPos.ZERO, 1, Set.of(observers));
	}
}
