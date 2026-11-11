package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodDayHistoryTest {
	private static final UUID RESIDENT = new UUID(37, 1);
	private static final UUID OTHER = new UUID(37, 2);
	@BeforeAll static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void realClosingDaysCreatePersonalShortageThenProposalAfterAnotherEpisodeWithoutMeeting() {
		Fixture f = fixture();
		f.data.registerMember(f.settlement, OTHER, 0);
		f.state.mapResident(OTHER, false, 100);
		assertTrue(f.close(1));
		assertEquals(1, f.person().hungerStreak());
		assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
		assertTrue(f.close(2));
		assertEquals(1, f.events(EventType.SHORTAGE_CONFLICT));
		assertEquals(3, f.person().grievance());
		assertTrue(f.person().information().stream().anyMatch(i -> i.kind() == SocialInformation.Kind.SHORTAGE));
		assertTrue(f.state.world().proposalsForCamp(f.state.campOwner().id()).isEmpty());
		Person absent = f.state.world().person(f.state.residentId(OTHER).orElseThrow());
		assertFalse(absent.hasFoodDayHistory());
		assertTrue(absent.information().isEmpty());
		f.person().setHunger(5); f.close(3);
		f.person().setHunger(65); f.close(4); f.close(5);
		assertEquals(2, f.events(EventType.SHORTAGE_CONFLICT));
		assertEquals(6, f.person().grievance());
		var proposal = f.state.world().proposalsForCamp(f.state.campOwner().id()).stream().findFirst().orElseThrow();
		assertEquals(java.util.Set.of(f.person().id()), proposal.knownBy());
		assertEquals(0, f.events(EventType.NORM_DELIBERATED));
		assertTrue(absent.information().isEmpty());
		f.state.world().assertConservation();
	}

	@Test void actualMealResetsClosingStreakBeforeNextDaysHungerAndKeepsResourcesConserved() {
		Fixture f = fixture(); f.close(1);
		f.state.world().importExternalBatch(ResourceType.FISH, 3, f.state.campOwner());
		assertEquals(60, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
		assertEquals(40, f.person().hunger());
		f.close(2);
		assertEquals(75, f.person().hunger());
		assertEquals(0, f.person().hungerStreak());
		assertEquals(1, f.person().hungerDays());
		assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
		assertEquals(0, f.state.world().availableNutrition(f.state.campOwner()));
		f.state.world().assertConservation();
	}

	@Test void activeMealThresholdIsInclusiveSixtyFiveEvenIfFoodRemainsInWarehouse() {
		for (int hunger : List.of(10, 11, 55, 64, 65)) {
			Fixture f = fixture();
			f.state.world().importExternalBatch(ResourceType.FISH, 10, f.state.campOwner());
			for (int day = 1; day <= 2; day++) { f.person().setHunger(hunger); f.close(day); }
			assertEquals(hunger == 65 ? 2 : 0, f.person().hungerStreak(), "hunger=" + hunger);
			assertEquals(hunger == 65 ? 1 : 0, f.events(EventType.SHORTAGE_CONFLICT));
			assertEquals(200, f.state.world().availableNutrition(f.state.campOwner()));
			f.state.world().assertConservation();
		}
	}

	@Test void twoRoundTripsAndRepeatedSameDayNeverDoubleCountOrReplayEffects() {
		Fixture f = fixture(); f.close(1);
		for (int i = 0; i < 2; i++) {
			CompoundTag before = f.save(); f = load(before);
			assertEquals(before, f.save());
			assertFalse(f.close(1)); assertEquals(1, f.person().observedFoodDays());
		}
		f.close(2);
		for (int i = 0; i < 2; i++) {
			f = load(f.save());
			assertFalse(f.close(2));
			f.state.norms().observeSocialDay(List.of(f.person()));
			assertEquals(2, f.person().observedFoodDays());
			assertEquals(1, f.events(EventType.SHORTAGE_CONFLICT));
			assertEquals(3, f.person().grievance());
		}
	}

	@Test void missingDaysAndInactiveResidentsNeverBecomeConsecutiveObservedDays() {
		for (boolean emptyDay : List.of(false, true)) {
			Fixture f = fixture(); f.close(1);
			if (emptyDay) assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.settlement, f.state, 2, Map.of()));
			f.close(3);
			assertEquals(2, f.person().observedFoodDays());
			assertEquals(1, f.person().hungerStreak());
			assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
			f.close(4); assertEquals(1, f.events(EventType.SHORTAGE_CONFLICT));
		}
	}

	@Test void staleStateAndCopiedSettlementCannotCloseTheCanonicalDay() {
		Fixture f = fixture();
		Settlement copy = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
		assertFalse(SettlementSimulationService.closeObservedDay(f.data, copy, f.state, 1, Map.of(RESIDENT, f.person())));
		Fixture restored = load(f.save());
		assertFalse(SettlementSimulationService.closeObservedDay(restored.data, restored.settlement, f.state, 1, Map.of(RESIDENT, f.person())));
		assertFalse(f.person().hasFoodDayHistory());
	}

	@Test void absentResidentDoesNotInheritNeighborsObservedDay() {
		Fixture f = fixture(); f.close(1);
		f.data.registerMember(f.settlement, OTHER, 0); f.state.mapResident(OTHER, false, 0);
		Person neighbor = f.state.world().person(f.state.residentId(OTHER).orElseThrow());
		SettlementSimulationService.closeObservedDay(f.data, f.settlement, f.state, 2, Map.of(OTHER, neighbor));
		f.close(3);
		assertEquals(1, f.person().hungerStreak()); assertEquals(2, f.person().observedFoodDays());
		assertEquals(1, neighbor.observedFoodDays()); assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
	}

	@Test void missingCoreDayBreaksContinuityEvenWithConsecutiveGameDays() {
		Fixture f = fixture(); f.close(1); f.state.world().advanceDay(); f.close(2);
		assertEquals(1, f.person().hungerStreak());
		assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
	}

	@Test void normsOffStillRecordsHistoryWithoutRetroactiveReactionWhenEnabled() {
		Fixture f = fixture(); f.state.setNormsEnabled(false); f.close(1); f.close(2);
		assertEquals(2, f.person().hungerStreak()); assertEquals(0, f.person().grievance());
		f.state.setNormsEnabled(true);
		f.state.norms().observeSocialDay(List.of(f.person()));
		f.close(3);
		assertEquals(3, f.person().hungerStreak()); assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
		Fixture g = fixture(); g.state.setNormsEnabled(false); g.close(1); g.state.setNormsEnabled(true); g.close(2);
		assertEquals(1, g.events(EventType.SHORTAGE_CONFLICT));
	}

	@Test void survivalOffOnAndLongGapDoNotSynthesizeHistory() {
		Fixture f = fixture(); f.close(1); f.state.setSurvivalEnabled(false, 1);
		assertFalse(f.close(2)); assertEquals(1, f.person().observedFoodDays());
		f.state.setSurvivalEnabled(true, 10); assertFalse(f.close(10)); f.close(11);
		assertEquals(2, f.person().observedFoodDays()); assertEquals(1, f.person().hungerStreak());
		assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
	}

	@Test void sharedBoundaryRejectsStaleMembershipMappingCampDeadAndForeignPerson() {
		for (String invalid : List.of("member", "mapping", "camp", "dead", "foreign")) {
			Fixture f = fixture(); Person original = f.person(); Map<UUID, Person> active = Map.of(RESIDENT, original);
			switch (invalid) {
				case "member" -> f.data.removeMember(RESIDENT);
				case "mapping" -> active = Map.of(OTHER, original);
				case "camp" -> FoodDayTestResidents.move(original, "elsewhere");
				case "dead" -> FoodDayTestResidents.kill(original);
				case "foreign" -> active = Map.of(RESIDENT, new Person(original.id(), original.campId(), original.householdId(), Person.AgeBand.ADULT, 100, 0));
			}
			assertTrue(SettlementSimulationService.closeObservedDay(f.data, f.settlement, f.state, 1, active));
			assertFalse(original.hasFoodDayHistory(), invalid); assertEquals(0, f.state.world().day());
		}
	}

	@Test void missingPartialWrongTypeImpossibleAndFutureHistoryStartsUnknownAfterTwoLoads() {
		List<Consumer<CompoundTag>> mutations = List.of(
			r -> r.remove("FoodDayHistory"),
			r -> r.putString("FoodDayHistory", "invalid"),
			r -> r.getCompound("FoodDayHistory").remove("ObservedDays"),
			r -> r.getCompound("FoodDayHistory").remove("HungerDays"),
			r -> r.getCompound("FoodDayHistory").remove("HungerStreak"),
			r -> r.getCompound("FoodDayHistory").remove("LastCoreDay"),
			r -> r.getCompound("FoodDayHistory").remove("LastGameDay"),
			r -> r.getCompound("FoodDayHistory").putString("LastCoreDay", "0"),
			r -> r.getCompound("FoodDayHistory").putInt("ObservedDays", 1),
			r -> r.getCompound("FoodDayHistory").putLong("LastCoreDay", 1),
			r -> r.getCompound("FoodDayHistory").putLong("LastGameDay", 2),
			r -> r.getCompound("FoodDayHistory").putLong("HungerDays", -1),
			r -> r.getCompound("FoodDayHistory").putLong("HungerDays", 2),
			r -> r.getCompound("FoodDayHistory").putInt("HungerStreak", 2),
			r -> r.getCompound("FoodDayHistory").putInt("HungerStreak", 0),
			r -> r.getCompound("FoodDayHistory").putLong("ObservedDays", 0));
		for (Consumer<CompoundTag> mutate : mutations) {
			Fixture f = fixture(); f.close(1); CompoundTag saved = f.save();
			mutate.accept(saved.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0)
				.getList("Residents", ListTag.TAG_COMPOUND).getCompound(0));
			f = load(saved); f = load(f.save());
			assertFalse(f.person().hasFoodDayHistory()); assertEquals(0, f.person().hungerStreak());
			f.close(2); assertEquals(1, f.person().hungerStreak());
			assertEquals(0, f.events(EventType.SHORTAGE_CONFLICT));
		}
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Observed days", 0);
		data.registerMember(settlement, RESIDENT, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(RESIDENT, false, 0); state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
		return new Fixture(data, settlement, state);
	}
	private static Fixture load(CompoundTag saved) {
		SettlementSavedData data = SettlementSavedData.load(saved, null);
		Settlement settlement = data.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}
	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person() { return state.world().person(state.residentId(RESIDENT).orElseThrow()); }
		boolean close(long day) { return SettlementSimulationService.closeObservedDay(data, settlement, state, day, Map.of(RESIDENT, person())); }
		long events(EventType type) { return state.world().ledger().events().stream().filter(e -> e.type() == type).count(); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
	}
}
