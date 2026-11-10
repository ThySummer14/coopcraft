package com.thysummer.coopcraft.civilization;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationConfig;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SurvivalSystem;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementMealEvidencePersistenceTest {
	private static final UUID REQUESTER = new UUID(2, 1);
	private static final UUID SUPERVISOR = new UUID(2, 2);
	private static final UUID OTHER = new UUID(2, 3);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void mixedMealTracesBothSourcesAndMatchesLegacyPhysicalEffects() {
		Fixture current = fixture(false);
		Fixture legacy = fixture(false);
		List<Long> origins = stock(current, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		stock(legacy, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		int before = current.events().size();
		assertEquals(68, current.eat(REQUESTER));
		SurvivalSystem.MealResult oldMeal = legacyMeal(legacy, REQUESTER);
		assertEquals(68, oldMeal.nutrition());
		assertEquals(before + 7, current.events().size()); // Two physical triples, one norm practice.
		assertEquals(legacy.events().size(), current.events().size());
		assertEquals(physicalEvents(legacy), physicalEvents(current));
		assertEquals(legacy.state().world().inventoryTotals(), current.state().world().inventoryTotals());
		assertEquals(legacy.person(REQUESTER).hunger(), current.person(REQUESTER).hunger());
		assertEquals(32, current.person(REQUESTER).hunger());
		assertEquals(3, current.state().allocatedFoodUnits(current.person(REQUESTER).id()));
		assertEquals(68, current.data().needsOf(current.settlement(), REQUESTER).satiety());
		assertEvidence(current, current.normEvents().getFirst(), origins, 2, 3);
		assertHours(current, 1);
		for (int i = 0; i < 3; i++) {
			current = reloadUnchanged(current);
			assertEvidence(current, current.normEvents().getFirst(), origins, 2, 3);
			assertHours(current, 1);
		}
	}

	@Test
	void careAndNeedFirstBothTraceTheCompleteMeal() {
		Fixture f = fixture(true);
		List<Long> origins = stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		int before = f.events().size();
		assertEquals(68, f.eat(REQUESTER));
		assertEquals(before + 8, f.events().size());
		assertEquals(2, f.normEvents().size());
		assertEquals(Set.of(Norm.NEED_FIRST_COMMON_STORE, Norm.CARE_FIRST_COMMON_STORE),
			f.normEvents().stream().map(event -> event.context().get("normId")).collect(java.util.stream.Collectors.toSet()));
		for (SimulationEvent event : f.normEvents()) {
			assertEvidence(f, event, origins, 2, 3);
			assertEquals("1", event.context().get("careBonus"));
		}
		assertHours(f, 1);
		reloadUnchanged(reloadUnchanged(f));
	}

	@Test
	void sameResourceFromIndependentSourcesAndSingleBatchKeepTheirExactOrigins() {
		Fixture f = fixture(false);
		List<Long> origins = stock(f, ResourceType.FISH, 1, ResourceType.FISH, 2);
		assertEquals(60, f.eat(REQUESTER));
		assertEvidence(f, f.normEvents().getFirst(), origins, 2, 3);
		f = reloadUnchanged(f);
		f.state().world().importExternalBatch(ResourceType.TUBERS, 3, f.state().campOwner());
		long origin = f.events().getLast().eventId();
		assertEquals(72, f.eat(OTHER));
		assertEvidence(f, f.normEvents().getLast(), List.of(origin), 1, 3);
		assertHours(f, 1);
		reloadUnchanged(f);
	}

	@Test
	void zeroMealsAndDisabledNormsDoNotInventPractices() {
		Fixture f = fixture(false);
		List<SimulationEvent> before = f.events();
		assertEquals(0, f.eat(REQUESTER));
		assertEquals(before, f.events());
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		f.person(REQUESTER).setHunger(5);
		before = f.events();
		assertEquals(0, f.eat(REQUESTER));
		assertEquals(before, f.events());
		f.person(REQUESTER).setHunger(100);
		f.state().setNormsEnabled(false);
		int count = f.events().size();
		assertEquals(68, f.eat(REQUESTER));
		assertEquals(count + 6, f.events().size());
		assertTrue(f.normEvents().isEmpty());
		assertEquals(3, f.state().allocatedFoodUnits(f.person(REQUESTER).id()));
		assertHours(f, 0);
		reloadUnchanged(f);
	}

	@Test
	void allowanceStopsBeforeUnconsumedSourceAndRefusalDoesNotAddMealEvidence() {
		Fixture f = fixture(false);
		List<Long> origins = stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 5);
		// Preserve expiry-first consumption order: this independent source expires last.
		f.state().world().createInitialBatch(ResourceType.WILD_FRUIT, 4, 50, f.state().campOwner(), 0, 20);
		long untouched = f.events().getLast().eventId();
		assertEquals(68, f.eat(REQUESTER));
		assertEvidence(f, f.normEvents().getFirst(), origins, 2, 3);
		assertFalse(f.state().world().ledger().traceOrigins(f.normEvents().getFirst().eventId()).stream()
			.anyMatch(event -> event.eventId() == untouched));
		assertEquals(3L, f.state().world().inventoryTotals().get(ResourceType.TUBERS).longValue());
		assertEquals(4L, f.state().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
		int count = f.events().size();
		assertEquals(0, f.eat(REQUESTER));
		assertEquals(count + 2, f.events().size());
		assertEquals(EventType.SHARE_REFUSED, f.events().getLast().type());
		assertEquals(1, f.normEvents().size());
		assertEquals(0, f.eat(REQUESTER));
		assertEquals(count + 2, f.events().size());
		reloadUnchanged(f);
	}

	@Test
	void reloadDoesNotRechargeSameDayButChargesAgainOnNextDay() {
		Fixture f = fixture(false);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		assertEquals(68, f.eat(REQUESTER));
		for (int i = 0; i < 3; i++) {
			f = reloadUnchanged(f);
			assertHours(f, 1);
		}
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		assertEquals(68, f.eat(OTHER));
		assertHours(f, 1);
		f.state().world().advanceDay();
		f = reloadUnchanged(f);
		assertHours(f, 1); // Loading must not reset or recompute saved social hours.
		f.person(REQUESTER).setHunger(100);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		assertEquals(68, f.eat(REQUESTER));
		assertHours(f, 2);
		reloadUnchanged(f);
	}

	@Test
	void legacyAndNewPracticesCoexistWithoutBackfillingOldMealsOrOldDays() {
		Fixture f = fixture(false);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		legacyMeal(f, REQUESTER);
		SimulationEvent legacy = f.normEvents().getFirst();
		assertFalse(legacy.context().containsKey("allocationEvidence"));
		assertEquals(2, legacy.causalLinks().size());
		f = reloadUnchanged(f);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		f.eat(OTHER);
		assertHours(f, 1);
		assertEquals(legacy, f.state().world().ledger().event(legacy.eventId()).orElseThrow());
		List<SimulationEvent> history = f.events();
		f.state().world().advanceDay();
		f = reloadUnchanged(reloadUnchanged(f));
		assertEquals(history, f.events());
		assertEquals(legacy, f.normEvents().getFirst());
		f.person(REQUESTER).setHunger(100);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		f.eat(REQUESTER);
		assertHours(f, 2); // Yesterday's receipts must not suppress today's charge.
		assertEquals(history, f.events().subList(0, history.size()));
		reloadUnchanged(f);
	}

	@Test
	void partialExportedFoodRemainsEdibleAndKeepsEvidenceAcrossReloads() {
		Fixture f = fixture(false);
		List<Long> origins = stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 6);
		var batch = f.state().world().batchesAt(f.state().campOwner()).stream()
			.filter(b -> b.resourceType() == ResourceType.TUBERS).findFirst().orElseThrow();
		f.state().world().exportExternalBatch(f.state().campOwner(), batch.batchId(), 1);
		f = reloadUnchanged(f);
		assertEquals(68, f.eat(REQUESTER));
		assertEquals(68, f.data().needsOf(f.settlement(), REQUESTER).satiety());
		assertEquals(3, f.state().allocatedFoodUnits(f.person(REQUESTER).id()));
		assertEvidence(f, f.normEvents().getFirst(), origins, 2, 3);
		assertHours(f, 1);
		f = reloadUnchanged(reloadUnchanged(f));
		assertEquals(72, f.eat(OTHER));
		assertHours(f, 1);
		assertEquals(0, f.state().world().availableNutrition(f.state().campOwner()));
		reloadUnchanged(f);
	}

	@Test
	void failedNormRecordingStillMarksAlreadyConsumedFoodForSaving() {
		Fixture f = fixture(false);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		// Fail candidate traversal inside norm recording, after the real meal and quota update.
		// Missing supporter IDs are safely ignored and must not be used to inject this fault.
		var failingCandidates = new AbstractCollection<Person>() {
			@Override public int size() { return 1; }
			@Override public Iterator<Person> iterator() {
				throw new IllegalStateException("controlled candidate traversal failure");
			}
		};
		int before = f.events().size();
		f.data().setDirty(false);
		assertFalse(f.data().isDirty());
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> SettlementSimulationService.eat(f.data(), f.settlement(), REQUESTER, failingCandidates));
		assertEquals("controlled candidate traversal failure", error.getMessage());
		assertTrue(f.data().isDirty());
		assertEquals(before + 6, f.events().size());
		assertEquals(2, f.events().stream().filter(event -> event.type() == EventType.CONSUME).count());
		assertTrue(f.normEvents().isEmpty());
		assertEquals(32, f.person(REQUESTER).hunger());
		assertEquals(3, f.state().allocatedFoodUnits(f.person(REQUESTER).id()));
		assertEquals(0, f.state().world().availableNutrition(f.state().campOwner()));
		f.state().world().assertConservation();
		// This is persistence protection, not transactional rollback or completion of syncSatiety.
	}

	@Test
	void missingSupporterDoesNotExecuteOrInterruptConsumedMealPersistence() {
		Fixture f = fixture(false);
		stock(f, ResourceType.FISH, 1, ResourceType.TUBERS, 2);
		Set<String> supporters = Set.of(f.person(SUPERVISOR).id(), "missing-meal-supervisor");
		f.state().world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.state().campOwner().id(), 3,
			supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		f.data().setDirty(false);
		assertEquals(68, f.eat(REQUESTER));
		assertTrue(f.data().isDirty());
		assertTrue(f.normEvents().isEmpty());
		assertEquals(0, f.person(SUPERVISOR).socialHours());
		assertEquals(50, f.state().world().norm(f.state().campOwner().id(), Norm.NEED_FIRST_COMMON_STORE)
			.orElseThrow().stability());
		assertEquals(68, f.data().needsOf(f.settlement(), REQUESTER).satiety());
		assertEquals(32, f.person(REQUESTER).hunger());
		assertEquals(3, f.state().allocatedFoodUnits(f.person(REQUESTER).id()));
		assertEquals(2, f.events().stream().filter(event -> event.type() == EventType.CONSUME).count());
		assertEquals(0, f.state().world().availableNutrition(f.state().campOwner()));
		f.state().world().assertConservation();
		reloadUnchanged(f);
	}

	private static Fixture fixture(boolean child) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Meal evidence", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (UUID resident : List.of(REQUESTER, SUPERVISOR, OTHER)) {
			data.registerMember(settlement, resident, 0);
			state.mapResident(resident, child && resident.equals(REQUESTER), 0);
		}
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		Set<String> supporters = Set.of(state.residentId(SUPERVISOR).orElseThrow(), state.residentId(OTHER).orElseThrow());
		state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, state.campOwner().id(), child ? 2 : 3,
			supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		if (child) {
			state.world().registerNorm(new Norm(Norm.CARE_FIRST_COMMON_STORE, state.campOwner().id(), 1,
				supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		}
		return new Fixture(data, state);
	}

	private static List<Long> stock(Fixture f, ResourceType first, int firstUnits, ResourceType second, int secondUnits) {
		f.state().world().importExternalBatch(first, firstUnits, f.state().campOwner());
		long firstOrigin = f.events().getLast().eventId();
		f.state().world().importExternalBatch(second, secondUnits, f.state().campOwner());
		return List.of(firstOrigin, f.events().getLast().eventId());
	}

	private static SurvivalSystem.MealResult legacyMeal(Fixture f, UUID resident) {
		Person person = f.person(resident);
		SurvivalSystem.MealResult meal = new SurvivalSystem(f.state().world(), SimulationConfig.defaults())
			.eatFromCommon(person, f.state().remainingFoodUnits(person.id()));
		f.state().recordFoodAllocation(person.id(), meal.units());
		f.state().norms().recordCommonAllocation(person, meal.units(), meal.requestEventId(), meal.allocationEventId());
		f.data().setSatiety(f.settlement(), resident, 100 - person.hunger());
		return meal;
	}

	private static List<SimulationEvent> physicalEvents(Fixture f) {
		return f.events().stream().filter(event -> event.type() != EventType.NORM_ENFORCED).toList();
	}

	private static void assertEvidence(Fixture f, SimulationEvent norm, List<Long> origins, int batches, int units) {
		assertEquals("meal-consumption-v1", norm.context().get("allocationEvidence"));
		assertEquals(Integer.toString(batches), norm.context().get("allocationBatchCount"));
		assertEquals(Integer.toString(units), norm.context().get("allocatedUnits"));
		assertEquals(origins, f.state().world().ledger().traceOrigins(norm.eventId()).stream()
			.map(SimulationEvent::eventId).toList());
		List<SimulationEvent> causes = norm.causalLinks().stream()
			.map(id -> f.state().world().ledger().event(id).orElseThrow()).toList();
		assertEquals(1, causes.stream().filter(event -> event.type() == EventType.SHARE_REQUEST).count());
		assertEquals(1, causes.stream().filter(event -> event.type() == EventType.SHARE).count());
		assertEquals(batches, causes.stream().filter(event -> event.type() == EventType.CONSUME).count());
		for (SimulationEvent consumption : causes.stream().filter(event -> event.type() == EventType.CONSUME).toList()) {
			assertEquals(1, consumption.causalLinks().size());
			assertEquals(EventType.SHARE, f.state().world().ledger().event(consumption.causalLinks().getFirst()).orElseThrow().type());
		}
	}

	private static Fixture reloadUnchanged(Fixture f) {
		CompoundTag saved = f.data().save(new CompoundTag(), null);
		assertEquals(24, saved.getInt("DataVersion"));
		List<SimulationEvent> events = f.events();
		SettlementSavedData loaded = SettlementSavedData.load(saved, null);
		Fixture restored = new Fixture(loaded, loaded.simulationOf(loaded.find(BlockPos.ZERO).orElseThrow()).orElseThrow());
		assertEquals(saved, loaded.save(new CompoundTag(), null));
		assertEquals(events, restored.events()); // Includes exact context and causal link order.
		restored.state().norms().restoreChargedSupervision();
		assertEquals(saved, loaded.save(new CompoundTag(), null));
		restored.state().world().assertConservation();
		return restored;
	}

	private static void assertHours(Fixture f, int expected) {
		assertEquals(expected, f.person(SUPERVISOR).socialHours());
		assertEquals(expected, f.person(OTHER).socialHours());
	}

	private record Fixture(SettlementSavedData data, SettlementSimulationState state) {
		Settlement settlement() {
			return data.find(BlockPos.ZERO).orElseThrow();
		}
		Person person(UUID id) {
			return state.world().person(state.residentId(id).orElseThrow());
		}
		int eat(UUID id) {
			return SettlementSimulationService.eat(data, settlement(), id);
		}
		List<SimulationEvent> events() {
			return state.world().ledger().events();
		}
		List<SimulationEvent> normEvents() {
			return events().stream().filter(event -> event.type() == EventType.NORM_ENFORCED
				|| event.type() == EventType.NORM_VIOLATION).toList();
		}
	}
}
