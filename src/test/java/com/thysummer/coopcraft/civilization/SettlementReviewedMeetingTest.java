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

/** Reviews exercise the production pre-mutation gate and real NormSystem execution without a server. */
class SettlementReviewedMeetingTest {
	@BeforeAll
	static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static SettlementMeetings.SceneIdentity scene(Fixture f) {
		return new SettlementMeetings.SceneIdentity(f.data, f.settlement, f.data.stockpileBindingToken(f.settlement),
			BlockPos.ZERO, f.state, null, f.data.membersOf(f.settlement).stream()
				.map(member -> (Object) new SettlementMeetings.ResidentIdentity(member, f.state)).toList(), 0);
	}
	private static SettlementMeetings.Inspection inspection(Fixture f, List<Person> present) {
		var review = f.state.norms().reviewCommonStoreMeeting(f.people(), present, f.camp());
		var result = new SettlementMeetings.Result(review.result().status(), f.state.world().day(),
			f.state.norms().lastMeetingDay(f.camp()), "0,64,0", review.result(),
			SettlementMeetingFeedback.inspect(f.state, review.result()));
		return new SettlementMeetings.Inspection(result, f.state, f.people(), present, review);
	}
	private static SettlementMeetings.Preview preview(Fixture f, List<Person> present) {
		return SettlementMeetings.preview(f.data, f.settlement, inspection(f, present), scene(f));
	}
	private static SettlementMeetings.Result reviewed(Fixture f, List<Person> present, SettlementMeetings.Preview expected) {
		return SettlementMeetings.holdReviewed(f.data, f.settlement, inspection(f, present), scene(f), expected);
	}
	private static void rejectedUnchanged(Fixture f, List<Person> present, SettlementMeetings.Preview expected) {
		CompoundTag before = save(f);
		f.data.setDirty(false);
		assertEquals("review_changed", reviewed(f, present, expected).status());
		assertEquals(before, save(f));
		assertFalse(f.data.isDirty());
	}

	@Test void previewIsReadOnlyAndMissingOrUnreadyReviewCannotExecute() {
		Fixture f = fixture(3);
		var unready = preview(f, f.people());
		initialProposal(f);
		CompoundTag before = save(f);
		var stock = f.state.world().inventoryTotals();
		f.data.setDirty(false);
		for (int i = 0; i < 10; i++) {
			assertEquals("ready", preview(f, f.people(1, 2)).result().status());
			assertEquals(before, save(f));
			assertEquals(stock, f.state.world().inventoryTotals());
			assertFalse(f.data.isDirty());
		}
		rejectedUnchanged(f, f.people(), null);
		rejectedUnchanged(f, f.people(), unready);
	}

	@Test void readyReviewExecutesRealMeetingOnceAndDuplicateReportsAlreadyHeld() {
		Fixture f = fixture(3);
		var proposal = initialProposal(f);
		var expected = preview(f, f.people(1, 2));
		assertTrue(reviewed(f, f.people(1, 2), expected).held());
		assertEquals(1, proposal.rounds());
		assertEquals(1, f.person(1).socialHours());
		assertEquals(0, f.person(3).socialHours());
		CompoundTag before = save(f);
		assertEquals("already_held", reviewed(f, f.people(1, 2), expected).status());
		assertEquals(before, save(f));
		f.state.world().assertConservation();
	}

	@Test void switchedProposalAndSameIdReplacementBothRejectBeforeMutation() {
		for (boolean sameId : List.of(false, true)) {
			Fixture f = fixture(3);
			var old = seededProposal(f, "proposal-z", 0, Map.of(1, NormProposal.Position.SUPPORT));
			var expected = preview(f, f.people());
			if (sameId) {
				f = reload(f, save(f));
				assertNotSame(old, f.state.world().proposal(old.proposalId()).orElseThrow());
			} else {
				seededProposal(f, "proposal-a", 0, Map.of(1, NormProposal.Position.SUPPORT));
			}
			rejectedUnchanged(f, f.people(), expected);
		}
	}

	@Test void equalHeadcountDifferentPeopleAndChangedTimeEligibilityReject() {
		Fixture f = fixture(4);
		initialProposal(f);
		var expected = preview(f, f.people(1, 2));
		rejectedUnchanged(f, f.people(1, 3), expected);
		var all = preview(f, f.people());
		f.person(4).restoreSocialState(0, 6, 0);
		rejectedUnchanged(f, f.people(), all);
	}

	@Test void modeOffOnAndRebindingBackToSamePositionInvalidateReview() {
		for (String change : List.of("norms", "survival", "binding")) {
			Fixture f = fixture(2);
			initialProposal(f);
			f.data.setStockpile(f.settlement, BlockPos.ZERO);
			var expected = preview(f, f.people());
			switch (change) {
				case "norms" -> { f.state.setNormsEnabled(false); f.state.setNormsEnabled(true); }
				case "survival" -> { f.state.setSurvivalEnabled(false, 0); f.state.setSurvivalEnabled(true, 0); }
				default -> f.data.setStockpile(f.settlement, BlockPos.ZERO);
			}
			rejectedUnchanged(f, f.people(), expected);
		}
	}

	@Test void physicalContainerPartnerOrganizerAndGameDayChangesReject() {
		Fixture f = fixture(2);
		initialProposal(f);
		var expected = preview(f, f.people());
		var original = scene(f);
		for (var changed : List.of(
			new SettlementMeetings.SceneIdentity(original.level(), new Object(), original.binding(), BlockPos.ZERO, f.state, null, original.residents(), 0),
			new SettlementMeetings.SceneIdentity(original.level(), original.organizer(), original.binding(), BlockPos.ZERO, new Object(), null, original.residents(), 0),
			new SettlementMeetings.SceneIdentity(original.level(), original.organizer(), original.binding(), BlockPos.ZERO, f.state, new Object(), original.residents(), 0),
			new SettlementMeetings.SceneIdentity(original.level(), original.organizer(), original.binding(), BlockPos.ZERO, f.state, null, original.residents(), 1))) {
			CompoundTag before = save(f);
			assertEquals("review_changed", SettlementMeetings.holdReviewed(f.data, f.settlement, inspection(f, f.people()), changed, expected).status());
			assertEquals(before, save(f));
		}
	}

	@Test void coreDayAndReloadInvalidateReview() {
		Fixture f = fixture(2);
		initialProposal(f);
		var expected = preview(f, f.people());
		Fixture loaded = reload(f, save(f));
		rejectedUnchanged(loaded, loaded.people(), expected);
		nextCoreDay(f);
		rejectedUnchanged(f, f.people(), expected);
	}

	@Test void removedAndReregisteredSameResidentInvalidatesReviewEvenWhenAllValuesMatch() {
		Fixture f = fixture(3);
		initialProposal(f);
		var expected = preview(f, f.people());
		var oldMember = f.data.membersOf(f.settlement).getLast();
		var oldPerson = f.person(3);
		f.data.removeMember(oldMember.villagerId());
		f.data.registerMember(f.settlement, oldMember.villagerId(), oldMember.joinedDay());
		var replacement = f.data.membersOf(f.settlement).getLast();
		assertNotSame(oldMember, replacement);
		assertEquals(oldMember, replacement);
		assertSame(oldPerson, f.person(3));
		rejectedUnchanged(f, f.people(), expected);
	}

	@Test void changedDisplayedAllowanceRejectsBeforeAnyDiscussion() {
		Fixture f = fixture(2);
		initialProposal(f);
		var expected = preview(f, f.people());
		f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), 4,
			Set.of(), Set.of(), Set.of(), 50, Norm.Status.ACTIVE, List.of()));
		rejectedUnchanged(f, f.people(), expected);
	}

	@Test void changedOpinionsAreNotFrozenOrRejected() {
		Fixture f = fixture(2);
		initialProposal(f);
		var expected = preview(f, f.people());
		f.person(2).setHunger(0);
		f.state.world().importExternalBatch(ResourceType.FISH, 100, StorageOwner.household(f.person(2).householdId()));
		assertTrue(reviewed(f, f.people(), expected).held());
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
