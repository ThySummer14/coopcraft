package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production services with simulated attendance callbacks, not a Minecraft-world test. */
class SettlementProposalBaselineTest {
	private static final UUID A = new UUID(38, 1), B = new UUID(38, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void naturalRefusalProposalAndTwoMeetingsOpenOneRealQueuedMeal() {
		Fixture f = fixture();
		assertNotEquals(f.person(A).householdId(), f.person(B).householdId());
		assertTrue(f.person(A).information().isEmpty());
		assertEquals(0, f.person(A).grievance());
		assertEquals(60, f.eat(A));
		assertEquals(0, f.eat(A));
		long origin = f.refusals(0).getFirst().eventId();
		var stock = f.state.world().inventoryTotals();
		f.close(1);
		NormProposal proposal = f.state.world().proposalsForCamp(f.camp()).getFirst();
		assertEquals(4, proposal.allocationUnitCap());
		assertEquals(origin, proposal.originEventId());
		assertTrue(f.hold().held());
		assertEquals(1, proposal.rounds());
		assertEquals(NormProposal.Status.OPEN, proposal.status());
		assertEquals(stock, f.state.world().inventoryTotals());
		f.close(2);
		assertEquals(100, f.person(A).hunger());
		assertEquals(60, f.eat(A));
		assertEquals(0, f.eat(A));
		stock = f.state.world().inventoryTotals();
		assertTrue(f.hold().held());
		assertEquals(NormProposal.Status.ADOPTED, proposal.status());
		assertEquals(4, f.state.dailyFoodUnitCap(f.person(A).id()));
		assertEquals(stock, f.state.world().inventoryTotals());
		assertTrue(SettlementFoodAccess.shouldEat(f.data, f.settlement, A, 2, 60, true));
		int[] nutrition = {0};
		var ticket = f.data.foodRequests().submit(f.data, f.settlement, A, 100, 2, null,
			() -> true, () -> nutrition[0] = f.eat(A));
		assertNotNull(ticket);
		f.data.foodRequests().flush(f.data, f.settlement, 139, 2);
		assertTrue(ticket.waiting());
		assertEquals(stock, f.state.world().inventoryTotals());
		f.data.foodRequests().flush(f.data, f.settlement, 140, 2);
		assertFalse(ticket.waiting());
		assertEquals(20, nutrition[0]);
		assertEquals(stock.get(ResourceType.FISH) - 1, f.state.world().inventoryTotals().get(ResourceType.FISH));
		assertEquals(4, f.state.allocatedFoodUnits(f.person(A).id()));
		assertEquals(20, f.person(A).hunger());
		assertEquals(0, f.eat(A));
		assertEquals(1, f.refusals(0).size());
		assertEquals(1, f.refusals(2).size());
		f.state.world().assertConservation();
	}

	@Test void legacyOpenCapTwoSurvivesTwoRoundTripsAndIsStillAdoptable() {
		Fixture f = fixture();
		assertEquals(60, f.eat(A)); assertEquals(0, f.eat(A));
		long origin = f.refusals(0).getFirst().eventId();
		// Model a historical save's OPEN cap-2 proposal, not a newly generated proposal.
		NormProposal old = NormProposal.restore("legacy", f.camp(), Norm.NEED_FIRST_COMMON_STORE,
			f.person(A).id(), 0, origin, 2, 0, 0, NormProposal.Status.OPEN,
			Map.of(f.person(A).id(), NormProposal.Position.SUPPORT));
		f.state.world().registerProposal(old);
		long proposalEvent = MeetingTestEvents.record(f.state.world(), EventType.NORM_PROPOSED,
			List.of(f.person(A).id()), Map.of("proposalId", "legacy", "normId", old.normId(),
				"cap", "2", "revision", "0"), List.of(origin));
		f.person(A).learnInformation(new SocialInformation("proposal-info:legacy",
			SocialInformation.Kind.NORM_PROPOSAL, f.person(A).id(), "legacy", proposalEvent, 0, null, 1.0));
		for (int i = 0; i < 2; i++) {
			CompoundTag before = f.data.save(new CompoundTag(), null);
			f = reload(f);
			assertEquals(before, f.data.save(new CompoundTag(), null));
			assertEquals(old.positions(), f.state.world().proposal("legacy").orElseThrow().positions());
			assertEquals(2, f.state.world().proposal("legacy").orElseThrow().allocationUnitCap());
			assertEquals(origin, f.state.world().proposal("legacy").orElseThrow().originEventId());
		}
		f.close(1); assertTrue(f.hold().held());
		f.close(2); assertTrue(f.hold().held());
		assertEquals(NormProposal.Status.ADOPTED, f.state.world().proposal("legacy").orElseThrow().status());
		assertEquals(2, f.state.dailyFoodUnitCap(f.person(A).id()));
		f.state.world().assertConservation();
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Proposal baseline", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (UUID id : List.of(A, B)) { data.registerMember(settlement, id, 0); state.mapResident(id, false, 0); }
		state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
		return new Fixture(data, settlement, state);
	}
	private static Fixture reload(Fixture f) {
		var data = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
		var settlement = data.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
	}
	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		String camp() { return state.campOwner().id(); }
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
		int eat(UUID id) { return SettlementSimulationService.eat(data, settlement, id); }
		void close(long day) { assertTrue(SettlementSimulationService.closeObservedDay(data, settlement, state,
			day, Map.of(A, person(A), B, person(B)))); }
		NormSystem.MeetingResult hold() { var people = List.of(person(A), person(B));
			return state.norms().holdCommonStoreMeeting(people, people, camp(), "0,0,0"); }
		List<SimulationEvent> refusals(long day) { return state.world().ledger().eventsAtDay(day).stream()
			.filter(e -> e.type() == EventType.SHARE_REFUSED && e.actors().getFirst().equals(person(A).id())).toList(); }
	}
}
