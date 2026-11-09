package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.MeetingTestEvents;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodAccessTest {
	private static final UUID RESIDENT = new UUID(1, 2);

	@BeforeAll static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualTwoRoundMeetingReopensGoalDecisionWithoutFeedingOrDuplicatingRefusal() {
		Fixture f = fixture(20);
		exhaustAndRefuse(f);
		long origin = snapshot(f).refusalEventId();
		NormProposal proposal = NormProposal.restore("quota-four", f.camp(), Norm.NEED_FIRST_COMMON_STORE,
			f.person().id(), 0, origin, 4, 0, 0, NormProposal.Status.OPEN,
			Map.of(f.person().id(), NormProposal.Position.SUPPORT));
		f.state.world().registerProposal(proposal);
		UUID neighbor = new UUID(1, 3);
		f.data.registerMember(f.settlement, neighbor, 0);
		f.state.mapResident(neighbor, false, 0);
		Person other = f.state.world().person(f.state.residentId(neighbor).orElseThrow());
		other.observeNorm("neighbor-requests");
		other.setTrust(f.person().id(), 100);
		List<Person> people = List.of(f.person(), other);
		long proposed = MeetingTestEvents.record(f.state.world(), EventType.NORM_PROPOSED, List.of(f.person().id()),
			Map.of("proposalId", proposal.proposalId(), "normId", proposal.normId(), "cap", "4", "revision", "0"), List.of(origin));
		f.person().learnInformation(new SocialInformation("proposal-info:quota-four", SocialInformation.Kind.NORM_PROPOSAL,
			f.person().id(), proposal.proposalId(), proposed, 0, null, 1.0));
		f.person().observeNorm("neighbor-requests");
		f.person().setTrust(f.person().id(), 100);
		assertEquals("deliberating", f.state.norms().holdCommonStoreMeeting(people, people, f.camp(), "0,64,0").outcome());
		assertFalse(decision(f));
		f.state.norms().startDay(people);
		f.state.world().advanceDay();
		f.state.markSurvivalDay(1);
		f.person().setHunger(100);
		exhaustAndRefuse(f);
		assertEquals(40, f.person().hunger());
		assertFalse(decision(f));
		assertEquals("adopted", f.state.norms().holdCommonStoreMeeting(people, people, f.camp(), "0,64,0").outcome());
		assertEquals(4, f.state.dailyFoodUnitCap(f.person().id()));
		assertEquals(40, f.person().hunger());
		assertTrue(decision(f));
		long stock = f.state.world().availableNutrition(f.state.campOwner());
		assertEquals(20, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
		assertEquals(stock - 20, f.state.world().availableNutrition(f.state.campOwner()));
		assertEquals(4, f.state.allocatedFoodUnits(f.person().id()));
		assertFalse(decision(f));
		assertEquals(SettlementFoodAccess.Status.ALLOWANCE_SPENT, snapshot(f).status());
		assertEquals(0, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
		assertEquals(1, f.state.world().ledger().eventsAtDay(1).stream().filter(e -> e.type() == EventType.SHARE_REFUSED).count());
		f.state.world().assertConservation();
	}

	@Test void resumedArrivalUsesExistingFortyTickQueueAndRechecksAllowanceBeforeConsumption() {
		for (boolean lowerWhileWaiting : List.of(false, true)) {
			Fixture f = fixture(20);
			exhaustAndRefuse(f);
			UUID neighbor = new UUID(1, 3);
			f.data.registerMember(f.settlement, neighbor, 0);
			f.state.mapResident(neighbor, false, 0);
			Set<String> supporters = Set.of(f.person().id(), f.state.residentId(neighbor).orElseThrow());
			f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), 4,
				supporters, Set.of(), supporters, 100, Norm.Status.ACTIVE, List.of()));
			assertTrue(decision(f));
			assertTrue(SettlementFoodRequests.enabled(f.data, f.settlement));
			long stock = f.state.world().availableNutrition(f.state.campOwner());
			int[] calls = {0};
			var ticket = f.data.foodRequests().submit(f.data, f.settlement, RESIDENT, 100, 0, null, () -> true,
				() -> { calls[0]++; SettlementSimulationService.eat(f.data, f.settlement, RESIDENT); });
			assertNotNull(ticket);
			assertSame(ticket, f.data.foodRequests().submit(f.data, f.settlement, RESIDENT, 110, 0, ticket,
				() -> true, () -> fail("Duplicate ticket must not create another meal")));
			if (lowerWhileWaiting) f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), 3,
				supporters, Set.of(), supporters, 100, Norm.Status.ACTIVE, List.of()));
			f.data.foodRequests().flush(f.data, f.settlement, 139, 0);
			assertTrue(ticket.waiting()); assertEquals(0, calls[0]);
			assertEquals(stock, f.state.world().availableNutrition(f.state.campOwner()));
			f.data.foodRequests().flush(f.data, f.settlement, 140, 0);
			assertFalse(ticket.waiting()); assertEquals(1, calls[0]);
			assertEquals(lowerWhileWaiting ? 3 : 4, f.state.allocatedFoodUnits(f.person().id()));
			assertEquals(stock - (lowerWhileWaiting ? 0 : 20), f.state.world().availableNutrition(f.state.campOwner()));
			assertSame(ticket, f.data.foodRequests().submit(f.data, f.settlement, RESIDENT, 200, 0, ticket,
				() -> true, () -> fail("Completed ticket must not revive")));
			f.data.foodRequests().flush(f.data, f.settlement, 240, 0);
			assertEquals(1, calls[0]); assertFalse(decision(f));
			assertEquals(1, f.state.world().ledger().eventsAtDay(0).stream().filter(e -> e.type() == EventType.SHARE_REFUSED).count());
			f.state.world().assertConservation();
		}
	}

	@Test void reloadedDecisionAndFrozenEvidenceAreReadOnly() {
		Fixture f = fixture(20);
		exhaustAndRefuse(f);
		cap(f, 4);
		var expected = snapshot(f);
		for (int reload = 0; reload < 2; reload++) {
			SettlementSavedData data = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
			Settlement settlement = data.find(BlockPos.ZERO).orElseThrow();
			f = new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
			CompoundTag before = data.save(new CompoundTag(), null);
			data.setDirty(false);
			for (int i = 0; i < 10; i++) { assertTrue(decision(f)); assertEquals(expected, snapshot(f)); }
			assertFalse(data.isDirty());
			assertEquals(before, data.save(new CompoundTag(), null));
			f.state.world().assertConservation();
		}
	}

	@Test void noFoodReplenishmentAndModeDayHungerAndCapGatesUseCurrentFacts() {
		Fixture f = fixture(3);
		exhaustAndRefuse(f);
		assertEquals(SettlementFoodAccess.Status.CAP_NOT_RAISED, snapshot(f).status());
		cap(f, 4);
		assertEquals(SettlementFoodAccess.Status.NO_FOOD, snapshot(f).status());
		assertFalse(decision(f));
		f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
		assertTrue(decision(f));
		assertEquals(0, snapshot(f).refusedAvailableNutrition());
		assertFalse(SettlementFoodAccess.shouldEat(f.data, f.settlement, RESIDENT, 0, 60, false));
		for (long day : new long[] {-1, 1}) {
			assertFalse(SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, day).orElseThrow().canResume());
			assertFalse(SettlementFoodAccess.shouldEat(f.data, f.settlement, RESIDENT, day, 60, true));
		}
		f.person().setHunger(5); assertEquals(SettlementFoodAccess.Status.SATISFIED, snapshot(f).status());
		assertFalse(decision(f)); f.person().setHunger(40);
		f.state.setNormsEnabled(false); assertFalse(decision(f)); f.state.setNormsEnabled(true);
		f.state.setSurvivalEnabled(false, 0); assertFalse(decision(f)); f.state.setSurvivalEnabled(true, 0);
		assertTrue(decision(f));
		cap(f, 2); assertFalse(decision(f)); cap(f, 4);
		f.state.world().advanceDay();
		assertTrue(SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, 0).isEmpty());
		assertFalse(decision(f));
	}

	@Test void canonicalMembershipMappingAndOrdinaryThresholdStaySeparate() {
		Fixture f = fixture(20);
		assertTrue(SettlementFoodAccess.shouldEat(f.data, f.settlement, RESIDENT, 0, 35, true));
		assertFalse(SettlementFoodAccess.shouldEat(f.data, f.settlement, RESIDENT, 0, 36, true));
		exhaustAndRefuse(f); cap(f, 4);
		Settlement copy = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
		assertTrue(SettlementFoodAccess.inspect(f.data, copy, RESIDENT, 0).isEmpty());
		UUID unmapped = new UUID(9, 9); f.data.registerMember(f.settlement, unmapped, 0);
		assertTrue(SettlementFoodAccess.inspect(f.data, f.settlement, unmapped, 0).isEmpty());
		f.data.removeMember(RESIDENT);
		assertTrue(SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, 0).isEmpty());
	}

	@Test void deadResidentCannotResumeAndAnUnrecordedRefusalCannotBeInvented() {
		Fixture f = fixture(20);
		assertEquals(60, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
		cap(f, 4);
		assertFalse(decision(f));
		assertTrue(SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, 0).isEmpty());
		cap(f, 3);
		SettlementSimulationService.eat(f.data, f.settlement, RESIDENT);
		cap(f, 4);
		assertTrue(decision(f));
		com.thysummer.coopcraft.civilization.simulation.FoodAccessTestPeople.markDead(f.person(), 0);
		assertTrue(SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, 0).isEmpty());
		assertFalse(decision(f));
	}

	@Test void malformedOrLegacyEvidenceCannotAuthorizeResumption() {
		for (String variant : List.of("legacy", "wrong_reason", "wrong_basis", "negative", "overflow", "fractional",
			"not_exhausted", "hunger", "nutrition", "request_need", "wrong_actor", "wrong_camp", "no_link", "extra_link", "old_request", "wrong_type", "old_refusal", "allocation_mismatch")) {
			Fixture f = fixture(0);
			List<String> actors = List.of(f.person().id(), f.camp());
			List<String> requestActors = variant.equals("wrong_actor") ? List.of("other", f.camp())
				: variant.equals("wrong_camp") ? List.of(f.person().id(), "other-camp") : actors;
			long request = MeetingTestEvents.record(f.state.world(), variant.equals("wrong_type") ? EventType.SHARE_REFUSED : EventType.SHARE_REQUEST,
				requestActors, Map.of("neededNutrition", variant.equals("request_need") ? "36" : "35"), List.of());
			if (variant.equals("old_request")) f.state.world().advanceDay();
			Map<String, String> context = new HashMap<>(Map.of("reason", "daily-unit-cap-exhausted", "allocationBasis", "item_units",
				"cap", "3", "allocatedUnits", "3", "hunger", "40", "neededNutrition", "35", "availableNutrition", "0"));
			switch (variant) {
				case "legacy" -> context.clear();
				case "wrong_reason" -> context.put("reason", "other");
				case "wrong_basis" -> context.put("allocationBasis", "nutrition");
				case "negative" -> context.put("cap", "-3");
				case "overflow" -> context.put("cap", "2147483648");
				case "fractional" -> context.put("allocatedUnits", "3.0");
				case "not_exhausted" -> context.put("allocatedUnits", "2");
				case "hunger" -> context.put("hunger", "5");
				case "nutrition" -> context.put("availableNutrition", "-1");
				default -> { }
			}
			long second = MeetingTestEvents.record(f.state.world(), EventType.SHARE_REQUEST, actors, Map.of("neededNutrition", "35"), List.of());
			MeetingTestEvents.record(f.state.world(), EventType.SHARE_REFUSED, actors, context,
				variant.equals("no_link") ? List.of() : variant.equals("extra_link") ? List.of(request, second) : List.of(request));
			if (variant.equals("old_refusal")) f.state.world().advanceDay();
			f.state.markSurvivalDay(f.state.world().day());
			f.state.recordFoodAllocation(f.person().id(), variant.equals("allocation_mismatch") ? 2 : 3);
			cap(f, 4);
			f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
			assertFalse(SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, f.state.world().day())
				.map(SettlementFoodAccess.Snapshot::canResume).orElse(false), variant);
			assertFalse(decision(f), variant);
		}
	}

	private static Fixture fixture(int food) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Recovery", 0);
		data.registerMember(settlement, RESIDENT, 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(RESIDENT, false, 0);
		state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
		if (food > 0) state.world().importExternalBatch(ResourceType.FISH, food, state.campOwner());
		return new Fixture(data, settlement, state);
	}
	private static void exhaustAndRefuse(Fixture f) {
		f.person().setHunger(100);
		assertEquals(60, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
		assertTrue(decision(f)); // The original pending-refusal path is preserved at satiety 60.
		assertEquals(0, SettlementSimulationService.eat(f.data, f.settlement, RESIDENT));
	}
	private static void cap(Fixture f, int cap) {
		f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), cap,
			Set.of(), Set.of(), Set.of(), 100, Norm.Status.ACTIVE, List.of()));
	}
	private static SettlementFoodAccess.Snapshot snapshot(Fixture f) {
		return SettlementFoodAccess.inspect(f.data, f.settlement, RESIDENT, f.state.lastSurvivalDay()).orElseThrow();
	}
	private static boolean decision(Fixture f) {
		return SettlementFoodAccess.shouldEat(f.data, f.settlement, RESIDENT, f.state.lastSurvivalDay(),
			60, SettlementSimulationService.hasFoodFor(f.data, f.settlement, RESIDENT));
	}
	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person() { return state.world().person(state.residentId(RESIDENT).orElseThrow()); }
		String camp() { return state.campOwner().id(); }
	}
}
