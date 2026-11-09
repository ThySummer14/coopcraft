package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real transient queue and meal service without starting a game world. */
class SettlementLoadedSupervisionTest {
	private static final UUID A = new UUID(42, 1), B = new UUID(42, 2), C = new UUID(42, 3), D = new UUID(42, 4);
	private static final SettlementSupervision.Presence PRESENT = new SettlementSupervision.Presence(true, true, true, true, true, 4096);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void availableLoadedSupportersReorderAtFixedDeadlineAndChargeOnce() {
		Fixture f = fixture();
		var c = f.submit(C, 10); var d = f.submit(D, 49);
		f.flush(49); assertTrue(c.waiting()); assertTrue(d.waiting());
		f.flush(50);
		assertEquals(List.of(D, C), f.order);
		f.hours(1); assertEquals(3, f.state.allocatedFoodUnits(f.person(C).id()));
		f.state.world().assertConservation();
	}

	@Test void lostSupporterReleasesTrueArrivalOrderImmediatelyWithNoAbsentCostsAndActiveCaps() {
		Fixture f = fixture(); f.submit(C, 0); f.submit(D, 1);
		f.presence.remove(B); f.flush(2);
		assertEquals(List.of(C, D), f.order); f.hours(0);
		assertEquals(3, f.state.dailyFoodUnitCap(f.person(C).id()));
		assertEquals(3, f.state.allocatedFoodUnits(f.person(C).id()));
		assertEquals(0, f.eat(C));
		assertEquals(Norm.Status.ACTIVE, f.state.world().norm(f.state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE).orElseThrow().status());
		f.state.world().assertConservation();
	}

	@Test void everyMealReobservesButFlushOrderIsNotRecomputedAfterFirstMeal() {
		Fixture f = fixture();
		f.person(A).restoreSocialState(0, 5, 0); f.person(B).restoreSocialState(0, 5, 0);
		f.submit(C, 0);
		f.data.foodRequests().submit(f.data, f.settlement, D, 1, 0, null, () -> true,
			() -> { f.order.add(D); f.eat(D); f.presence.remove(B); }, f::candidates);
		f.flush(40);
		assertEquals(List.of(D, C), f.order); f.hours(6);
		assertEquals(3, f.state.allocatedFoodUnits(f.person(C).id()));
		f.state.world().assertConservation();
	}

	@Test void lossBetweenOrderingAndFirstMealCannotUseCapturedSupporters() {
		Fixture f = fixture(); f.submit(C, 0);
		f.data.foodRequests().submit(f.data, f.settlement, D, 1, 0, null, () -> true,
			() -> { f.presence.remove(B); f.order.add(D); f.eat(D); }, f::candidates);
		f.flush(40);
		assertEquals(List.of(D, C), f.order); f.hours(0);
		assertEquals(3, f.state.allocatedFoodUnits(f.person(C).id()));
		assertEquals(3, f.state.allocatedFoodUnits(f.person(D).id()));
		f.state.world().assertConservation();
	}

	@Test void absentThenReturnChargesOnceAndReloadRestoresCostsButNeverPresenceOrQueue() {
		Fixture f = fixture(); f.presence.remove(B); f.eat(C); f.hours(0);
		f.presence.put(B, PRESENT); f.eat(D); f.hours(1);
		f.submit(A, 100);
		CompoundTag saved = f.data.save(new CompoundTag(), null);
		SettlementSavedData loaded = SettlementSavedData.load(saved, null);
		Fixture restored = new Fixture(loaded, loaded.find(BlockPos.ZERO).orElseThrow());
		assertEquals(saved, loaded.save(new CompoundTag(), null));
		assertTrue(restored.candidates().isEmpty()); restored.flush(1000);
		assertTrue(restored.order.isEmpty()); restored.hours(1);
		assertEquals(3, restored.state.allocatedFoodUnits(restored.person(C).id()));
		assertEquals(0, restored.eat(C));
		restored.presence.put(A, PRESENT); restored.presence.put(B, PRESENT);
		restored.eat(A); restored.eat(B); restored.hours(1);
		restored.state.world().assertConservation();
	}

	@Test void physicalGateRejectsEachMissingConditionAndInvalidDistances() {
		Fixture f = fixture(); assertEquals(2, f.candidates().size());
		for (var invalid : List.of(
			new SettlementSupervision.Presence(false, true, true, true, true, 0),
			new SettlementSupervision.Presence(true, false, true, true, true, 0),
			new SettlementSupervision.Presence(true, true, false, true, true, 0),
			new SettlementSupervision.Presence(true, true, true, false, true, 0),
			new SettlementSupervision.Presence(true, true, true, true, false, 0),
			new SettlementSupervision.Presence(true, true, true, true, true, 4096.01),
			new SettlementSupervision.Presence(true, true, true, true, true, -1),
			new SettlementSupervision.Presence(true, true, true, true, true, Double.NaN),
			new SettlementSupervision.Presence(true, true, true, true, true, Double.POSITIVE_INFINITY))) {
			f.presence.put(B, invalid); assertEquals(List.of(f.person(A)), f.candidates());
			assertFalse(SettlementFoodRequests.enabled(f.data, f.settlement, f::candidates));
		}
	}

	@Test void deadAndForeignMappedResidentsAreRejectedBeforeObservationWithoutMutation() {
		Fixture f = fixture();
		FoodDayTestResidents.kill(f.person(A));
		FoodDayTestResidents.move(f.person(B), "other-camp");
		assertTrue(f.data.isMember(f.settlement, A)); assertTrue(f.data.isMember(f.settlement, B));
		assertTrue(f.state.residentId(A).isPresent()); assertTrue(f.state.residentId(B).isPresent());
		var observed = new java.util.HashSet<UUID>();
		CompoundTag before = f.data.save(new CompoundTag(), null);
		f.data.setDirty(false);
		assertTrue(SettlementSupervision.candidates(f.data, f.settlement, id -> {
			observed.add(id); return f.presence.get(id);
		}).isEmpty());
		assertEquals(Set.of(C, D), observed);
		assertFalse(f.data.isDirty());
		assertEquals(before, f.data.save(new CompoundTag(), null));
	}

	@Test void disabledModesNeverObserveEntities() {
		Fixture f = fixture();
		var observations = new java.util.concurrent.atomic.AtomicInteger();
		java.util.function.Function<UUID, SettlementSupervision.Presence> observe = id -> {
			observations.incrementAndGet(); return PRESENT;
		};
		f.state.setNormsEnabled(false);
		assertTrue(SettlementSupervision.candidates(f.data, f.settlement, observe).isEmpty());
		assertEquals(0, observations.get());
		f.state.setNormsEnabled(true); f.state.setSurvivalEnabled(false, 0);
		assertTrue(SettlementSupervision.candidates(f.data, f.settlement, observe).isEmpty());
		assertEquals(0, observations.get());
	}

	@Test void canonicalSettlementMembershipAndMappingAreRequired() {
		Fixture f = fixture();
		assertTrue(SettlementSupervision.candidates(null, f.settlement, f.presence::get).isEmpty());
		assertTrue(SettlementSupervision.candidates(f.data, null, f.presence::get).isEmpty());
		assertTrue(SettlementSupervision.candidates(f.data, f.settlement, null).isEmpty());
		Settlement copy = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
		assertTrue(SettlementSupervision.candidates(f.data, copy, f.presence::get).isEmpty());
		UUID unmapped = new UUID(42, 9); f.data.registerMember(f.settlement, unmapped, 0); f.presence.put(unmapped, PRESENT);
		assertEquals(2, f.candidates().size());
		f.data.removeMember(B);
		Settlement other = f.data.getOrCreate(new BlockPos(1000, 0, 0), "other", 0);
		f.data.registerMember(other, B, 0);
		assertEquals(List.of(f.person(A)), f.candidates());
		assertFalse(SettlementFoodRequests.enabled(f.data, f.settlement, f::candidates));
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Fixture f = new Fixture(data, data.getOrCreate(BlockPos.ZERO, "loaded camp", 0));
		for (UUID id : List.of(A, B, C, D)) { data.registerMember(f.settlement, id, 0); f.state.mapResident(id, false, 0); }
		f.state.setSurvivalEnabled(true, 0); f.state.setNormsEnabled(true);
		f.state.world().importExternalBatch(ResourceType.WILD_FRUIT, 40, f.state.campOwner());
		Set<String> supporters = Set.of(f.person(A).id(), f.person(B).id());
		f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.state.campOwner().id(), 3,
			supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		f.person(C).setHunger(65); f.person(D).setHunger(90);
		f.presence.put(A, PRESENT); f.presence.put(B, PRESENT);
		return f;
	}
	private static final class Fixture {
		final SettlementSavedData data; final Settlement settlement; final SettlementSimulationState state;
		final Map<UUID, SettlementSupervision.Presence> presence = new HashMap<>();
		final List<UUID> order = new ArrayList<>();
		Fixture(SettlementSavedData data, Settlement settlement) {
			this.data = data; this.settlement = settlement; this.state = data.getOrCreateSimulation(settlement);
		}
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
		List<Person> candidates() { return SettlementSupervision.candidates(data, settlement, presence::get); }
		int eat(UUID id) { return SettlementSimulationService.eat(data, settlement, id, candidates()); }
		SettlementFoodRequests.Ticket submit(UUID id, long tick) {
			return data.foodRequests().submit(data, settlement, id, tick, 0, null, () -> true,
				() -> { order.add(id); eat(id); }, this::candidates);
		}
		void flush(long tick) { data.foodRequests().flush(data, settlement, tick, 0); }
		void hours(int value) { assertEquals(value, person(A).socialHours()); assertEquals(value, person(B).socialHours()); }
	}
}
