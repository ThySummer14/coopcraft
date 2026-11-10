package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.MeetingTestEvents;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same NormSystem meeting operation used by the real settlement adapter. */
class SettlementCommonStoreMeetingTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void observationCreatesOnlyProposerKnowledgeAndNeverAnAutomaticMeeting() {
		Fixture f = fixture(3);
		refusal(f);
		var stock = f.state.world().inventoryTotals();

		f.state.norms().observeSocialDay(f.people());

		NormProposal proposal = f.state.world().proposalsForCamp(f.camp()).getFirst();
		assertEquals(Set.of(f.person(1).id()), proposal.knownBy());
		assertEquals(0, proposal.rounds());
		assertEquals(NormProposal.Status.OPEN, proposal.status());
		assertTrue(events(f, EventType.NORM_DELIBERATED).isEmpty());
		assertTrue(f.people().stream().allMatch(person -> person.socialHours() == 0));
		assertEquals(stock, f.state.world().inventoryTotals());
		f.state.world().assertConservation();
	}

	@Test
	void previewCanPlanTheFirstIntroductionButIsEntirelyReadOnly() {
		Fixture f = fixture(3);
		NormProposal proposal = initialProposal(f);
		CompoundTag before = save(f);

		for (int query = 0; query < 20; query++) {
			NormSystem.MeetingResult preview = inspect(f, f.people(1, 2));
			assertEquals("ready", preview.status());
			assertEquals(2, preview.participants());
			assertEquals(3, preview.adultCount());
			assertEquals(proposal.proposalId(), preview.proposalId());
			assertEquals(before, save(f));
		}
		assertEquals(Set.of(f.person(1).id()), proposal.knownBy());
	}

	@Test
	void firstSuccessfulMeetingIntroducesOnlyPresentResidentsAndChargesEachOnce() {
		Fixture f = fixture(3);
		NormProposal proposal = initialProposal(f);
		var stock = f.state.world().inventoryTotals();
		int cap = f.state.dailyFoodUnitCap(f.person(1).id());

		NormSystem.MeetingResult result = hold(f, f.people(2, 1, 2));

		assertEquals("held", result.status());
		assertEquals(2, result.participants());
		assertEquals(3, result.adultCount());
		assertEquals(1, proposal.rounds());
		assertEquals(Set.of(f.person(1).id(), f.person(2).id()), proposal.knownBy());
		assertEquals(1, f.person(1).socialHours());
		assertEquals(1, f.person(2).socialHours());
		assertEquals(0, f.person(3).socialHours());
		assertTrue(f.person(2).information().stream().anyMatch(info -> info.objectId().equals(proposal.proposalId())));
		assertTrue(f.person(3).information().isEmpty());
		assertEquals(cap, f.state.dailyFoodUnitCap(f.person(1).id()));
		assertEquals(stock, f.state.world().inventoryTotals());
		assertEquals(List.of(f.person(1).id(), f.person(2).id()), events(f, EventType.NORM_DELIBERATED).getFirst().actors());
		f.state.world().assertConservation();
	}

	@Test
	void noProposalDoesNotObserveRefusalsCreateAProposalOrSpendTheDay() {
		Fixture f = fixture(2);
		refusal(f);
		CompoundTag before = save(f);

		assertEquals("no_proposal", hold(f, f.people()).status());
		assertEquals(before, save(f));
		assertTrue(f.state.world().proposalsForCamp(f.camp()).isEmpty());
		initialProposalFromRecordedRefusal(f);
		assertEquals("held", hold(f, f.people()).status());
	}

	@Test
	void emptySingletonAndUninformedCrowdCannotLearnChargeAdvanceOrOccupyTheDay() {
		Fixture f = fixture(3);
		NormProposal proposal = initialProposal(f);
		CompoundTag before = save(f);

		for (List<Person> onsite : List.of(List.<Person>of(), f.people(1), f.people(2, 3))) {
			assertNotEquals("held", hold(f, onsite).status());
			assertEquals(before, save(f));
		}
		assertEquals(0, proposal.rounds());
		assertEquals("held", hold(f, f.people(1, 2)).status());
	}

	@Test
	void exhaustedProposerCannotIntroduceProposalForFree() {
		Fixture f = fixture(3);
		initialProposal(f);
		f.person(1).restoreSocialState(f.person(1).grievance(), 6, 4);
		CompoundTag before = save(f);

		assertNotEquals("held", hold(f, f.people()).status());
		assertEquals(before, save(f));
		assertEquals(4, f.person(1).previousSocialHours(), "hold must never call startDay");
	}

	@Test
	void exhaustedKnownResidentIsNotAFreeVoterAndDoesNotHaveTheirPositionUpdated() {
		Fixture f = fixture(3);
		NormProposal proposal = seededProposal(f, "proposal-budget", 0, Map.of(
			1, NormProposal.Position.SUPPORT, 2, NormProposal.Position.SUPPORT, 3, NormProposal.Position.OPPOSE));
		f.person(3).restoreSocialState(0, 6, 0);
		var information = f.person(3).information();

		NormSystem.MeetingResult result = hold(f, f.people());

		assertEquals("held", result.status());
		assertEquals(2, result.participants());
		assertEquals(3, result.adultCount());
		assertEquals(6, f.person(3).socialHours());
		assertEquals(NormProposal.Position.OPPOSE, proposal.positions().get(f.person(3).id()));
		assertEquals(information, f.person(3).information());
		assertFalse(events(f, EventType.NORM_DELIBERATED).getFirst().actors().contains(f.person(3).id()));
	}

	@Test
	void onlyOneEligibleKnownResidentCannotUseAnExhaustedSecondResidentToAdvance() {
		Fixture f = fixture(2);
		seededProposal(f, "proposal-budget", 0, Map.of(1, NormProposal.Position.SUPPORT, 2, NormProposal.Position.SUPPORT));
		f.person(2).restoreSocialState(0, 6, 0);
		CompoundTag before = save(f);

		assertEquals("insufficient_participants", hold(f, f.people()).status());
		assertEquals(before, save(f));
	}

	@Test
	void twoOnsiteAdultsOutOfTenNeverBecomeOneHundredPercentParticipation() {
		Fixture f = fixture(10);
		NormProposal proposal = initialProposal(f);

		for (int round = 1; round <= 2; round++) {
			NormSystem.MeetingResult result = hold(f, f.people(1, 2));
			assertEquals("held", result.status());
			assertEquals(10, result.adultCount());
			assertEquals(2, result.participants());
			assertEquals(round, proposal.rounds());
			assertEquals(NormProposal.Status.OPEN, proposal.status());
			assertTrue(f.state.world().normsForCamp(f.camp()).isEmpty());
			assertEquals("deliberating", result.outcome());
			assertEquals("insufficient-participation", events(f, EventType.NORM_DELIBERATED).getLast().context().get("reason"));
			if (round == 1) nextCoreDay(f);
		}
	}

	@Test
	void absentPriorKnowledgeDoesNotUpdateVotesOrDeliverTheAdoptionResult() {
		Fixture f = fixture(3);
		NormProposal proposal = seededProposal(f, "proposal-absent", 1, Map.of(
			1, NormProposal.Position.SUPPORT, 2, NormProposal.Position.SUPPORT, 3, NormProposal.Position.OPPOSE));
		var absentInformation = f.person(3).information();

		assertEquals("held", hold(f, f.people(1, 2)).status());

		assertEquals(NormProposal.Status.ADOPTED, proposal.status());
		assertEquals(NormProposal.Position.OPPOSE, proposal.positions().get(f.person(3).id()));
		assertEquals(absentInformation, f.person(3).information());
		assertFalse(f.person(3).knowsNorm(Norm.NEED_FIRST_COMMON_STORE));
		Norm norm = f.state.world().norm(f.camp(), Norm.NEED_FIRST_COMMON_STORE).orElseThrow();
		assertEquals(Set.of(f.person(1).id(), f.person(2).id()), norm.knownBy());
		assertFalse(norm.supporters().contains(f.person(3).id()));
		assertFalse(norm.opponents().contains(f.person(3).id()));
		assertFalse(events(f, EventType.NORM_ADOPTED).getFirst().actors().contains(f.person(3).id()));
	}

	@Test
	void oneMeetingDiscussesOnlyOneDeterministicallyChosenOpenProposal() {
		Fixture f = fixture(2);
		NormProposal later = seededProposal(f, "proposal-z", 0, Map.of(1, NormProposal.Position.SUPPORT));
		NormProposal first = seededProposal(f, "proposal-a", 0, Map.of(1, NormProposal.Position.SUPPORT));
		String chosen = inspect(f, f.people()).proposalId();

		assertEquals("held", hold(f, f.people(2, 1)).status());

		assertEquals(chosen, events(f, EventType.NORM_DELIBERATED).getFirst().context().get("proposalId"));
		assertEquals(1, first.rounds() + later.rounds());
		assertEquals(1, events(f, EventType.NORM_DELIBERATED).size());
		assertEquals(1, f.person(1).socialHours());
		assertEquals(1, f.person(2).socialHours());
		NormProposal untouched = first.proposalId().equals(chosen) ? later : first;
		assertEquals(Set.of(f.person(1).id()), untouched.knownBy());
	}

	@Test
	void successfulDayHoursEventsAndResourcesSurviveTwoExactSaveReloads() {
		Fixture f = fixture(2);
		initialProposal(f);
		assertEquals("held", hold(f, f.people()).status());
		var inventory = f.state.world().inventoryTotals();
		var events = f.state.world().ledger().events();
		long meetingDay = f.state.world().day();

		for (int reload = 0; reload < 2; reload++) {
			CompoundTag before = save(f);
			f = reload(f, before);
			assertEquals(before, save(f));
			assertEquals(meetingDay, f.state.norms().lastMeetingDay(f.camp()));
			assertEquals("already_held", hold(f, f.people()).status());
			assertEquals(before, save(f));
			assertEquals(events, f.state.world().ledger().events());
			assertEquals(inventory, f.state.world().inventoryTotals());
			assertEquals(1, f.person(1).socialHours());
			assertEquals(1, f.person(2).socialHours());
			f.state.world().assertConservation();
		}
	}

	@Test
	void survivalAndNormModeTogglesCannotRefreshTheSuccessfulDay() {
		Fixture f = fixture(2);
		initialProposal(f);
		hold(f, f.people());
		f.state.setNormsEnabled(false);
		f.state.setSurvivalEnabled(false, 0);
		f.state.setSurvivalEnabled(true, 0);
		f.state.setNormsEnabled(true);
		CompoundTag before = save(f);

		assertEquals("already_held", hold(f, f.people()).status());
		assertEquals(before, save(f));
	}

	@Test
	void nextCoreDayAllowsOneNewMeetingWithoutReobservingOldRefusals() {
		Fixture f = fixture(2);
		NormProposal proposal = initialProposal(f);
		hold(f, f.people());
		int grievance = f.person(1).grievance();
		nextCoreDay(f);

		assertEquals("held", hold(f, f.people()).status());

		assertEquals(2, proposal.rounds());
		assertEquals(grievance, f.person(1).grievance());
		assertEquals(1, f.person(1).previousSocialHours());
		assertEquals(1, f.person(1).socialHours());
	}

	@Test
	void legacyMissingDayFieldRecoversCurrentSameCampDeliberationWithoutChargingAgain() {
		Fixture f = fixture(2);
		NormProposal proposal = initialProposal(f);
		MeetingTestEvents.record(f.state.world(), EventType.NORM_DELIBERATED, List.of(f.person(1).id()),
			Map.of("proposalId", proposal.proposalId(), "round", "1", "participants", "1"), List.of(proposal.originEventId()));
		CompoundTag old = withoutMeetingDay(save(f));
		var events = f.state.world().ledger().events();
		Fixture loaded = reload(f, old);

		assertEquals("already_held", hold(loaded, loaded.people()).status());
		assertEquals(events, loaded.state.world().ledger().events());
		assertEquals(0, loaded.person(1).socialHours());
		assertEquals(0, loaded.person(2).socialHours());
		assertTrue(SettlementMeetingDetails.project(events.getLast()).isEmpty(), "Legacy events are not onsite evidence");
	}

	@Test
	void legacyPreviousDayDeliberationDoesNotOccupyTodaysMeeting() {
		Fixture f = fixture(2);
		NormProposal proposal = initialProposal(f);
		MeetingTestEvents.record(f.state.world(), EventType.NORM_DELIBERATED, List.of(f.person(1).id()),
			Map.of("proposalId", proposal.proposalId()), List.of(proposal.originEventId()));
		nextCoreDay(f);
		Fixture loaded = reload(f, withoutMeetingDay(save(f)));

		assertEquals("held", hold(loaded, loaded.people()).status());
	}

	@Test
	void legacyOtherCampOrUnknownProposalEventDoesNotOccupyLocalMeeting() {
		Fixture f = fixture(2);
		NormProposal local = initialProposal(f);
		NormProposal foreign = NormProposal.restore("foreign-proposal", "foreign-camp", Norm.NEED_FIRST_COMMON_STORE,
			f.person(1).id(), 0, local.originEventId(), 2, 0, 1, NormProposal.Status.OPEN,
			Map.of(f.person(1).id(), NormProposal.Position.SUPPORT));
		f.state.world().registerProposal(foreign);
		MeetingTestEvents.record(f.state.world(), EventType.NORM_DELIBERATED, List.of(f.person(1).id()),
			Map.of("proposalId", foreign.proposalId()), List.of(local.originEventId()));
		MeetingTestEvents.record(f.state.world(), EventType.NORM_DELIBERATED, List.of(f.person(1).id()),
			Map.of("proposalId", "missing-proposal"), List.of(local.originEventId()));
		CompoundTag old = withoutMeetingDay(save(f));
		ListTag savedProposals = old.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0)
			.getList("NormProposals", ListTag.TAG_COMPOUND);
		CompoundTag savedForeign = savedProposals.getCompound(0).copy();
		savedForeign.putString("ProposalId", foreign.proposalId());
		savedForeign.putString("CampId", foreign.campId());
		savedProposals.add(savedForeign);
		Fixture loaded = reload(f, old);
		assertTrue(loaded.state.world().proposal(foreign.proposalId()).isPresent());

		assertEquals("held", hold(loaded, loaded.people()).status());
	}


	@Test
	void malformedMeetingDayFallsBackToCurrentEvidenceWithoutLosingInventoryOrEvents() {
		Fixture f = fixture(2);
		initialProposal(f);
		hold(f, f.people());
		CompoundTag canonical = save(f);
		for (int badValue = 0; badValue < 5; badValue++) {
			CompoundTag malformed = canonical.copy();
			CompoundTag simulation = malformed.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0);
			switch (badValue) {
				case 0 -> simulation.putLong("LastMeetingDay", -1);
				case 1 -> simulation.putLong("LastMeetingDay", f.state.world().day() + 1);
				case 2 -> simulation.putLong("LastMeetingDay", Long.MAX_VALUE);
				case 3 -> simulation.putString("LastMeetingDay", "0");
				default -> simulation.putInt("LastMeetingDay", 0);
			}
			Fixture loaded = reload(f, malformed);
			assertEquals(canonical, save(loaded), "Malformed optional day must not discard the simulation: " + badValue);
			assertEquals("already_held", hold(loaded, loaded.people()).status());
			assertEquals(canonical, save(loaded));
			loaded.state.world().assertConservation();
		}
	}

	@Test
	void explicitMinimumDaySentinelRoundTripsWithoutInventingAMeeting() {
		Fixture f = fixture(2);
		initialProposal(f);
		CompoundTag before = save(f);
		assertEquals(Long.MIN_VALUE, before.getList("Simulations", ListTag.TAG_COMPOUND).getCompound(0).getLong("LastMeetingDay"));
		Fixture loaded = reload(f, before);
		assertEquals(before, save(loaded));
		assertEquals("held", hold(loaded, loaded.people()).status());
	}

	@Test
	void twoPriorKnowersCanDiscussWithoutRebroadcastingMissingSourceInformation() {
		Fixture f = fixture(2);
		seededProposal(f, "proposal-known", 0,
			Map.of(1, NormProposal.Position.SUPPORT, 2, NormProposal.Position.SUPPORT), false);

		assertEquals("held", hold(f, f.people()).status());

		assertTrue(events(f, EventType.INFORMATION_SHARED).isEmpty());
		assertEquals(1, f.person(1).socialHours());
		assertEquals(1, f.person(2).socialHours());
	}

	@Test
	void invalidProposalInformationCannotTeachAnyoneOrStartAnOtherwiseEmptyMeeting() {
		for (int invalidSource = 0; invalidSource < 3; invalidSource++) {
			Fixture f = fixture(2);
			NormProposal proposal = seededProposal(f, "proposal-target", 0, Map.of(1, NormProposal.Position.SUPPORT), false);
			long source = invalidSource == 0 ? proposal.originEventId() : invalidSource == 1 ? 999999L
				: MeetingTestEvents.record(f.state.world(), EventType.NORM_PROPOSED, List.of(f.person(1).id()),
					Map.of("proposalId", "different-proposal"), List.of(proposal.originEventId()));
			f.person(1).learnInformation(new SocialInformation("bad-source", SocialInformation.Kind.NORM_PROPOSAL,
				f.person(1).id(), proposal.proposalId(), source, f.state.world().day(), null, 1.0));
			CompoundTag before = save(f);

			assertEquals("insufficient_participants", hold(f, f.people()).status());
			assertEquals(before, save(f));
		}
	}

	@Test
	void unreadyOlderAgendaCannotBlockTheNextReadyProposal() {
		Fixture f = fixture(2);
		NormProposal blocked = seededProposal(f, "proposal-a", 0, Map.of(1, NormProposal.Position.SUPPORT), false);
		NormProposal ready = seededProposal(f, "proposal-b", 0,
			Map.of(1, NormProposal.Position.SUPPORT, 2, NormProposal.Position.SUPPORT), false);

		assertEquals(ready.proposalId(), inspect(f, f.people()).proposalId());
		assertEquals(ready.proposalId(), hold(f, f.people()).proposalId());
		assertEquals(0, blocked.rounds());
		assertEquals(1, ready.rounds());
	}

	@Test
	void meetingHourFlowsThroughExistingGatherAndCraftWorkDurationsAtNextReset() {
		Fixture f = fixture(2);
		initialProposal(f);
		hold(f, f.people());
		assertEquals(100, SettlementSimulationService.gatherWorkDuration(f.data, f.settlement, id(1), 100));
		assertTrue(SettlementSimulationService.hasGatherTime(f.data, f.settlement, id(1)));

		nextCoreDay(f);

		assertEquals(1, f.person(1).previousSocialHours());
		assertEquals(120, SettlementSimulationService.gatherWorkDuration(f.data, f.settlement, id(1), 100));
		assertEquals(120, SettlementSimulationService.craftingWorkDuration(f.data, f.settlement, id(1), 100));
		assertTrue(SettlementSimulationService.hasGatherTime(f.data, f.settlement, id(1)));
	}

	@Test
	void finalAvailableHourSpentInMeetingDisablesGatheringAfterNextReset() {
		Fixture f = fixture(2);
		initialProposal(f);
		for (Person person : f.people()) person.restoreSocialState(person.grievance(), 5, 0);

		assertEquals("held", hold(f, f.people()).status());
		nextCoreDay(f);

		assertEquals(6, f.person(1).previousSocialHours());
		assertFalse(SettlementSimulationService.hasGatherTime(f.data, f.settlement, id(1)));
		assertEquals(600, SettlementSimulationService.gatherWorkDuration(f.data, f.settlement, id(1), 100));
	}


	@Test
	void earlierMeetingProjectionSurvivesLaterMutablePositionChanges() {
		Fixture f = fixture(2);
		NormProposal proposal = initialProposal(f);
		hold(f, f.people());
		SimulationEvent old = events(f, EventType.NORM_DELIBERATED).getFirst();
		var before = Civilization.toSimulationEventInfo(null, f.data, f.settlement, old);
		assertEquals(6, before.details().size());
		assertEquals(NormProposal.Position.SUPPORT, proposal.positions().get(f.person(2).id()));
		f.person(2).setHunger(0);
		f.state.world().importExternalBatch(ResourceType.FISH, 100, StorageOwner.household(f.person(2).householdId()));
		nextCoreDay(f);

		assertEquals("held", hold(f, f.people()).status());

		assertEquals(NormProposal.Position.OPPOSE, proposal.positions().get(f.person(2).id()));
		assertEquals(before, Civilization.toSimulationEventInfo(null, f.data, f.settlement, old));
		assertEquals("2/2; 2/0", before.details().get(3).value());
		f.state.world().assertConservation();
	}

	@Test
	void invalidProposalOriginCannotSpendTimeOrAdvanceBeforeLedgerValidationFails() {
		Fixture f = fixture(2);
		NormProposal proposal = NormProposal.restore("bad-origin", f.camp(), Norm.NEED_FIRST_COMMON_STORE,
			f.person(1).id(), 0, 999999L, 2, 0, 0, NormProposal.Status.OPEN,
			Map.of(f.person(1).id(), NormProposal.Position.SUPPORT, f.person(2).id(), NormProposal.Position.SUPPORT));
		f.state.world().registerProposal(proposal);
		CompoundTag before = save(f);

		assertEquals("invalid_proposal", hold(f, f.people()).status());
		assertEquals(before, save(f));
	}

	@Test
	void holdReturnsItsExactDeliberationEventWhileQueriesAndFailuresNeverClaimOne() {
		Fixture f = fixture(2);
		NormProposal proposal = initialProposal(f);
		CompoundTag before = save(f);
		for (int i = 0; i < 10; i++) {
			assertEquals(0, inspect(f, f.people()).deliberationEventId());
			assertEquals(0, hold(f, f.people(1)).deliberationEventId());
			assertEquals(before, save(f));
		}
		NormSystem.MeetingResult result = hold(f, f.people());
		SimulationEvent event = events(f, EventType.NORM_DELIBERATED).getLast();
		assertEquals(event.eventId(), result.deliberationEventId());
		assertEquals(proposal.proposalId(), event.context().get("proposalId"));
		assertEquals(Integer.toString(result.round()), event.context().get("round"));
		assertEquals(f.state.world().day(), event.day());
		assertEquals(0, inspect(f, f.people()).deliberationEventId());
		assertEquals(0, hold(f, f.people()).deliberationEventId());
		nextCoreDay(f);
		NormSystem.MeetingResult second = hold(f, f.people());
		assertTrue(second.deliberationEventId() > result.deliberationEventId());
		assertEquals(events(f, EventType.NORM_DELIBERATED).getLast().eventId(), second.deliberationEventId());
		assertEquals("adopted", second.outcome());
		assertNotEquals(events(f, EventType.NORM_ADOPTED).getLast().eventId(), second.deliberationEventId());
		f.state.world().assertConservation();
	}

	@Test
	void feedbackQueriesNeverCreateStateOrMutateAnyPersistedMeetingOrAllocationData() {
		SettlementSavedData empty = new SettlementSavedData();
		Settlement settlement = empty.getOrCreate(BlockPos.ZERO, "No simulation", 0);
		CompoundTag emptyBefore = empty.save(new CompoundTag(), null);
		var noMeeting = new NormSystem.MeetingResult("disabled", "", 0, 0, 0, "");
		for (int i = 0; i < 10; i++) {
			assertTrue(SettlementMeetingFeedback.inspect(empty.simulationOf(settlement).orElse(null), noMeeting).agenda().isEmpty());
			assertTrue(empty.simulationOf(settlement).isEmpty());
			assertEquals(emptyBefore, empty.save(new CompoundTag(), null));
		}
		Fixture f = fixture(3);
		initialProposal(f);
		f.state.recordFoodAllocation(f.person(1).id(), 1);
		CompoundTag before = save(f);
		f.data.setDirty(false);
		for (int i = 0; i < 20; i++) {
			var preview = inspect(f, f.people(1, 2));
			var feedback = SettlementMeetingFeedback.inspect(f.state, preview);
			assertTrue(feedback.completedDetails().isEmpty(), "No predicted votes before an actual meeting");
			assertEquals(3, feedback.agenda().orElseThrow().currentCap());
			assertEquals(4, feedback.agenda().orElseThrow().proposedCap());
			assertTrue(feedback.agenda().orElseThrow().defaultValue());
			SettlementMeetingFeedback.messages(new SettlementMeetings.Result("ready", f.state.world().day(),
				Long.MIN_VALUE, "0,64,0", preview, feedback));
			assertEquals(before, save(f));
			assertFalse(f.data.isDirty());
		}
		assertEquals(1, f.state.allocatedFoodUnits(f.person(1).id()));
		assertEquals(0, f.person(1).socialHours());
		f.state.world().assertConservation();
	}

	@Test
	void feedbackSeparatesActiveQuotaFromSupervisionAndCareFromTheBaseAllowance() {
		Fixture f = fixture(2);
		f.person(2).initializeAge(Person.AgeBand.ELDER, 0);
		for (Norm.Status needStatus : Norm.Status.values()) {
			for (Norm.Status careStatus : Norm.Status.values()) {
				f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), 4,
					Set.of(f.person(1).id()), Set.of(), Set.of(f.person(1).id()), 50, needStatus, List.of()));
				f.state.world().registerNorm(new Norm(Norm.CARE_FIRST_COMMON_STORE, f.camp(), 2,
					Set.of(), Set.of(), Set.of(), 50, careStatus, List.of()));
				for (boolean enabled : List.of(true, false)) {
					f.state.setNormsEnabled(enabled);
					CompoundTag before = save(f);
					var effects = SettlementMeetingFeedback.effects(f.state);
					int base = enabled && needStatus == Norm.Status.ACTIVE ? 4 : 3;
					int care = enabled && careStatus == Norm.Status.ACTIVE ? 2 : 0;
					assertEquals(base, effects.baseCap());
					assertEquals(care, effects.careExtraCap());
					assertFalse(effects.needFirstEnforced(), "One supervisor cannot authorize need-first ordering");
					assertEquals(base, f.state.dailyFoodUnitCap(f.person(1).id()));
					assertEquals(base + care, f.state.dailyFoodUnitCap(f.person(2).id()));
					assertEquals(before, save(f));
				}
			}
		}
		f.state.setNormsEnabled(true);
		f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), 4,
			Set.of(f.person(1).id(), f.person(2).id()), Set.of(), Set.of(f.person(1).id(), f.person(2).id()), 50, Norm.Status.ACTIVE, List.of()));
		assertTrue(SettlementMeetingFeedback.effects(f.state).needFirstEnforced());
		f.person(2).restoreSocialState(0, 6, 0);
		CompoundTag exhausted = save(f);
		assertFalse(SettlementMeetingFeedback.effects(f.state).needFirstEnforced());
		assertEquals(4, SettlementMeetingFeedback.effects(f.state).baseCap());
		assertEquals(exhausted, save(f));
		f.state.setSurvivalEnabled(false, 0);
		assertFalse(SettlementMeetingFeedback.effects(f.state).survivalEnabled());
	}

	@Test
	void frozenFeedbackUsesExactResultEvidenceAndSurvivesReloadWithoutReconstructingVotes() {
		Fixture f = fixture(2);
		initialProposal(f);
		NormSystem.MeetingResult held = hold(f, f.people());
		var expected = SettlementMeetingFeedback.inspect(f.state, held);
		assertEquals(6, expected.completedDetails().size());
		assertEquals("2/2; 2/0", expected.completedDetails().get(3).value());
		CompoundTag before = save(f);
		for (int i = 0; i < 2; i++) {
			f = reload(f, save(f));
			assertEquals(expected, SettlementMeetingFeedback.inspect(f.state, held));
			assertEquals(before, save(f));
			f.state.world().assertConservation();
		}
		var old = new NormSystem.MeetingResult("held", held.proposalId(), held.adultCount(), held.participants(), held.round(), held.outcome());
		assertTrue(SettlementMeetingFeedback.completed(f.state, old).isEmpty(), "Legacy or ambiguous results have no exact event identity");
		for (var mismatch : List.of(
			new NormSystem.MeetingResult("ready", held.proposalId(), 2, 2, 1, held.outcome(), held.deliberationEventId()),
			new NormSystem.MeetingResult("held", "another-proposal", 2, 2, 1, held.outcome(), held.deliberationEventId()),
			new NormSystem.MeetingResult("held", held.proposalId(), 3, 2, 1, held.outcome(), held.deliberationEventId()),
			new NormSystem.MeetingResult("held", held.proposalId(), 2, 3, 1, held.outcome(), held.deliberationEventId()),
			new NormSystem.MeetingResult("held", held.proposalId(), 2, 2, 2, held.outcome(), held.deliberationEventId()),
			new NormSystem.MeetingResult("held", held.proposalId(), 2, 2, 1, "adopted", held.deliberationEventId()),
			new NormSystem.MeetingResult("held", held.proposalId(), 2, 2, 1, held.outcome(), 999999))) {
			assertTrue(SettlementMeetingFeedback.completed(f.state, mismatch).isEmpty(), mismatch.toString());
		}
		f.person(2).setHunger(0);
		f.state.world().importExternalBatch(ResourceType.FISH, 100, StorageOwner.household(f.person(2).householdId()));
		nextCoreDay(f);
		assertTrue(SettlementMeetingFeedback.completed(f.state, held).isEmpty(), "An old day is not the current hold");
		var second = hold(f, f.people());
		assertEquals("2/2; 1/1", SettlementMeetingFeedback.completed(f.state, second).get(3).value());
		assertTrue(SettlementMeetingFeedback.completed(f.state, held).isEmpty());
		assertEquals("2/2; 2/0", expected.completedDetails().get(3).value());
		f.state.world().assertConservation();
	}

	@Test
	void completedFeedbackRejectsUnknownMarkersAndMalformedFrozenEvidenceEvenWithLivePositions() {
		Fixture f = fixture(2);
		NormProposal proposal = initialProposal(f);
		var held = hold(f, f.people());
		SimulationEvent valid = events(f, EventType.NORM_DELIBERATED).getLast();
		for (String mutation : List.of("meeting", "supportCount", "reason", "normId")) {
			var context = new LinkedHashMap<>(valid.context());
			context.put(mutation, "unknown-or-invalid");
			long id = MeetingTestEvents.record(f.state.world(), EventType.NORM_DELIBERATED, valid.actors(), context, valid.causalLinks());
			var forged = new NormSystem.MeetingResult("held", proposal.proposalId(), 2, 2, 1, held.outcome(), id);
			CompoundTag before = save(f);
			assertTrue(SettlementMeetingFeedback.completed(f.state, forged).isEmpty(), mutation);
			assertEquals(before, save(f));
		}
		MeetingTestEvents.record(f.state.world(), EventType.NORM_DELIBERATED, valid.actors(), valid.context(), valid.causalLinks());
		var ambiguous = new NormSystem.MeetingResult("held", proposal.proposalId(), 2, 2, 1, held.outcome());
		assertTrue(SettlementMeetingFeedback.completed(f.state, ambiguous).isEmpty(), "Multiple matching records cannot supply a missing exact receipt");
		assertEquals(6, SettlementMeetingFeedback.completed(f.state, held).size(), "Exact identity remains valid despite unrelated malformed records");
	}

	@Test
	void careAgendaIsAnExtraAllowanceAndFirstNeedAdoptionUsesEffectiveDefaultBase() {
		Fixture care = fixture(2);
		care.person(1).initializeAge(Person.AgeBand.ELDER, 0);
		assertEquals(Norm.CARE_FIRST_COMMON_STORE, initialProposal(care).normId());
		var agenda = SettlementMeetingFeedback.agenda(care.state, inspect(care, care.people()));
		assertNotNull(agenda);
		assertEquals(0, agenda.currentCap());
		assertEquals(1, agenda.proposedCap());
		assertTrue(agenda.defaultValue());
		assertEquals(3, SettlementMeetingFeedback.effects(care.state).baseCap());

		Fixture need = fixture(2);
		initialProposal(need);
		assertEquals(3, SettlementMeetingFeedback.effects(need.state).baseCap());
		hold(need, need.people());
		nextCoreDay(need);
		var adopted = hold(need, need.people());
		assertEquals("adopted", adopted.outcome());
		var feedback = SettlementMeetingFeedback.inspect(need.state, adopted);
		assertEquals(4, feedback.effects().baseCap());
		assertEquals(0, feedback.effects().careExtraCap());
		assertEquals("0 → 4", feedback.completedDetails().getLast().value(), "Historical norm establishment is separate from the actual 3-to-4 quota change");
		assertEquals(4, need.state.dailyFoodUnitCap(need.person(1).id()));
		assertEquals(30L, need.state.world().inventoryTotals().get(ResourceType.FISH).longValue());
		need.state.world().assertConservation();
	}

	@Test
	void feedbackAgendaUsesTheSameSelectedReadyProposalAsTheMeetingPlanner() {
		Fixture f = fixture(2);
		NormProposal blocked = seededProposal(f, "proposal-a", 0, Map.of(1, NormProposal.Position.SUPPORT), false);
		NormProposal ready = seededProposal(f, "proposal-b", 0,
			Map.of(1, NormProposal.Position.SUPPORT, 2, NormProposal.Position.SUPPORT), false);
		CompoundTag before = save(f);
		var preview = inspect(f, f.people());
		assertEquals(ready.proposalId(), preview.proposalId());
		var agenda = SettlementMeetingFeedback.agenda(f.state, preview);
		assertNotNull(agenda);
		assertEquals(ready.normId(), agenda.normId());
		assertEquals(ready.allocationUnitCap(), agenda.proposedCap());
		assertEquals(before, save(f));
		assertEquals(ready.proposalId(), hold(f, f.people()).proposalId());
		assertEquals(0, blocked.rounds());
	}

	@Test
	void unknownForeignMissingAndInvalidProposalsNeverInventAnAgenda() {
		Fixture f = fixture(2);
		long origin = refusal(f);
		for (String kind : List.of("unknown", "foreign", "future", "invalid-origin")) {
			NormProposal proposal = NormProposal.restore(kind, kind.equals("foreign") ? "foreign-camp" : f.camp(),
				kind.equals("unknown") ? "future-rule" : Norm.NEED_FIRST_COMMON_STORE, f.person(1).id(),
				kind.equals("future") ? f.state.world().day() + 1 : f.state.world().day(),
				kind.equals("invalid-origin") ? 999999 : origin, 2, 0, 0, NormProposal.Status.OPEN,
				Map.of(f.person(1).id(), NormProposal.Position.SUPPORT));
			f.state.world().registerProposal(proposal);
			CompoundTag before = save(f);
			assertNull(SettlementMeetingFeedback.agenda(f.state, new NormSystem.MeetingResult("ready", kind, 2, 2, 0, "")), kind);
			assertEquals(before, save(f));
		}
		assertNull(SettlementMeetingFeedback.agenda(f.state, new NormSystem.MeetingResult("ready", "missing", 2, 2, 0, "")));
		Fixture normal = fixture(2);
		var valid = initialProposal(normal);
		assertNull(SettlementMeetingFeedback.agenda(normal.state, new NormSystem.MeetingResult("invalid_proposal", valid.proposalId(), 2, 2, 0, "")));
	}

	private static Fixture fixture(int residents) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Local meetings", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (int index = 1; index <= residents; index++) {
			data.registerMember(settlement, id(index), 0);
			state.mapResident(id(index), false, 0);
			state.world().person(state.residentId(id(index)).orElseThrow()).observeNorm("neighbor-requests");
		}
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
		return new Fixture(data, settlement, state);
	}

	private static long refusal(Fixture f) {
		long request = f.state.world().recordShareRequest(f.person(1).id(), f.camp(), 40);
		return f.state.world().recordShareRefusal(request, f.person(1).id(), f.camp(), 20);
	}

	private static NormProposal initialProposal(Fixture f) {
		refusal(f);
		return initialProposalFromRecordedRefusal(f);
	}

	private static NormProposal initialProposalFromRecordedRefusal(Fixture f) {
		f.state.norms().observeSocialDay(f.people());
		return f.state.world().proposalsForCamp(f.camp()).getFirst();
	}

	private static NormProposal seededProposal(Fixture f, String id, int rounds, Map<Integer, NormProposal.Position> positions) {
		return seededProposal(f, id, rounds, positions, true);
	}

	private static NormProposal seededProposal(Fixture f, String id, int rounds,
		Map<Integer, NormProposal.Position> positions, boolean attachInformation) {
		long origin = refusal(f);
		Map<String, NormProposal.Position> personPositions = new LinkedHashMap<>();
		positions.forEach((resident, position) -> personPositions.put(f.person(resident).id(), position));
		NormProposal proposal = NormProposal.restore(id, f.camp(), Norm.NEED_FIRST_COMMON_STORE, f.person(1).id(),
			f.state.world().day(), origin, 4, 0, rounds, NormProposal.Status.OPEN, personPositions);
		f.state.world().registerProposal(proposal);
		long proposalEvent = MeetingTestEvents.record(f.state.world(), EventType.NORM_PROPOSED, List.of(f.person(1).id()),
			Map.of("proposalId", id, "normId", proposal.normId(), "cap", "4", "revision", "0"), List.of(origin));
		if (attachInformation) f.person(1).learnInformation(new SocialInformation("proposal-info:" + id, SocialInformation.Kind.NORM_PROPOSAL,
			f.person(1).id(), id, proposalEvent, f.state.world().day(), null, 1.0));
		return proposal;
	}

	private static NormSystem.MeetingResult inspect(Fixture f, List<Person> onsite) {
		return f.state.norms().inspectCommonStoreMeeting(f.people(), onsite, f.camp());
	}

	private static NormSystem.MeetingResult hold(Fixture f, List<Person> onsite) {
		return f.state.norms().holdCommonStoreMeeting(f.people(), onsite, f.camp(), "0, 64, 0");
	}

	private static void nextCoreDay(Fixture f) {
		f.state.norms().startDay(f.people());
		f.state.world().advanceDay();
	}

	private static List<SimulationEvent> events(Fixture f, EventType type) {
		return f.state.world().ledger().events().stream().filter(event -> event.type() == type).toList();
	}

	private static CompoundTag save(Fixture f) {
		return f.data.save(new CompoundTag(), null);
	}

	private static CompoundTag withoutMeetingDay(CompoundTag tag) {
		for (var entry : tag.getList("Simulations", ListTag.TAG_COMPOUND)) ((CompoundTag) entry).remove("LastMeetingDay");
		return tag;
	}

	private static Fixture reload(Fixture original, CompoundTag tag) {
		SettlementSavedData loaded = SettlementSavedData.load(tag, null);
		Settlement settlement = loaded.find(original.settlement.center()).orElseThrow();
		return new Fixture(loaded, settlement, loaded.simulationOf(settlement).orElseThrow());
	}

	private static UUID id(int index) { return new UUID(28, index); }

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		String camp() { return state.campOwner().id(); }
		Person person(int index) { return state.world().person(state.residentId(id(index)).orElseThrow()); }
		List<Person> people() { return state.world().people().stream().toList(); }
		List<Person> people(Integer... indices) { return java.util.Arrays.stream(indices).map(this::person).toList(); }
	}
}
