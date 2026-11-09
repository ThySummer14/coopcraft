package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Production ticket tests. Navigation and real entity ticking still require an in-world check. */
class SettlementMeetingInvitationsTest {
	private static final UUID RESIDENT = new UUID(30, 1);
	private static final UUID INVITER = new UUID(30, 2);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void gateDoesNotCreateMissingSimulationOrDirtySavedData() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "No state", 0);
		data.setDirty(false);
		CompoundTag before = data.save(new CompoundTag(), null);
		assertEquals("disabled", SettlementMeetingInvitations.gate(data, settlement, RESIDENT, 0));
		assertNull(SettlementMeetingInvitations.create(data, settlement, RESIDENT, INVITER, 100, 0, () -> true));
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertEquals(before, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
	}

	@Test
	void physicalPresenceRequiresEveryProductionGateAndSettlementBoundary() {
		assertTrue(presence(-1, 4096).eligible());
		assertTrue(presence(-1, 0).eligible());
		for (int missing = 0; missing < 11; missing++) {
			assertFalse(presence(missing, 0).eligible(), "Missing presence gate " + missing);
		}
		for (double distance : new double[] {4096.00001, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertFalse(presence(-1, distance).eligible());
			assertFalse(SettlementMeetingInvitations.withinSettlement(distance));
		}
	}

	@Test
	void otherInvitersCannotStealOrExtendConsentAndOwnerCanExplicitlyCancel() {
		Fixture f = fixture();
		var ticket = f.ticket();
		for (int i = 0; i < 20; i++) {
			assertEquals("already_invited", SettlementMeetingInvitations.existing(ticket, new UUID(30, 99)));
			assertEquals("cancelled", SettlementMeetingInvitations.existing(ticket, INVITER));
			assertEquals(1300, ticket.deadline());
			assertTrue(ticket.valid(f.data, 101 + i, 0), "Routing a result itself has no cancellation side effect");
		}
		ticket.cancel();
		assertFalse(ticket.valid(f.data, 121, 0));
	}

	@Test
	void explicitInvitesNeverToggleExistingConsentOrRenewItsDeadline() {
		Fixture f = fixture();
		var ticket = f.ticket();
		CompoundTag before = f.save();
		var information = List.copyOf(f.person().information());
		var resources = f.state.world().inventoryTotals();
		var events = List.copyOf(f.state.world().ledger().events());
		var knownBy = java.util.Set.copyOf(f.proposal().knownBy());
		int socialHours = f.person().socialHours();
		f.data.setDirty(false);
		for (int i = 0; i < 20; i++) {
			for (UUID requester : List.of(INVITER, new UUID(30, 99))) {
				assertEquals("already_invited", SettlementMeetingInvitations.explicitRequest(
					ticket, f.data, 101 + i, 0, requester, false, ticket));
				assertEquals(INVITER, ticket.inviter());
				assertEquals(1300, ticket.deadline());
				assertTrue(ticket.matches(f.data, 101 + i, 0));
			}
		}
		assertEquals("ready", SettlementMeetingInvitations.explicitRequest(null, f.data, 101, 0, INVITER, false, ticket));
		assertEquals(before, f.save());
		assertFalse(f.data.isDirty());
		assertEquals(information, f.person().information());
		assertEquals(resources, f.state.world().inventoryTotals());
		assertEquals(events, f.state.world().ledger().events());
		assertEquals(knownBy, f.proposal().knownBy());
		assertEquals(socialHours, f.person().socialHours());
	}

	@Test
	void explicitCancelRequiresLiveTicketOwnerAndExactSnapshotIdentity() {
		Fixture f = fixture();
		var ticket = f.ticket();
		var replacement = f.ticket();
		assertNotSame(ticket, replacement);
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 101, 0, new UUID(30, 99), true, ticket));
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 101, 0, INVITER, true, null));
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 101, 0, INVITER, true, replacement));
		assertEquals("cancelled", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 101, 0, INVITER, true, ticket));
		assertTrue(ticket.matches(f.data, 101, 0), "Decision seam does not itself stop or cancel a goal");
		ticket.cancel();
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 102, 0, INVITER, true, ticket));
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(replacement, f.data, 102, 0, INVITER, true, ticket));
		assertTrue(replacement.matches(f.data, 102, 0), "Old snapshot cannot cancel newly issued consent");
		assertEquals("cancelled", SettlementMeetingInvitations.explicitRequest(replacement, f.data, 102, 0, INVITER, true, replacement));
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(null, f.data, 102, 0, INVITER, true, ticket));
		assertEquals(1300, replacement.deadline());
	}

	@Test
	void explicitCommandsRejectExpiredTicketsWithoutMutatingReadOnlyInspection() {
		Fixture f = fixture();
		var ticket = f.ticket();
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 1300, 0, INVITER, true, ticket));
		assertEquals("ready", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 1300, 0, INVITER, false, ticket));
		assertTrue(ticket.matches(f.data, 100, 0), "Read-only decisions do not expire the original object");
		assertEquals(1300, ticket.deadline());
		assertFalse(ticket.valid(f.data, 1300, 0), "Production refresh still permanently expires consent");
		assertEquals("stale", SettlementMeetingInvitations.explicitRequest(ticket, f.data, 100, 0, INVITER, true, ticket));
	}

	@Test
	void stoppingOrdinaryWorkPreservesPendingWhileStoppingAttendanceCancels() {
		assertFalse(SettlementMeetingInvitations.cancelOnStop(false));
		assertTrue(SettlementMeetingInvitations.cancelOnStop(true));
	}

	private static SettlementMeetingInvitations.Presence presence(int missing, double distance) {
		return new SettlementMeetingInvitations.Presence(missing != 0, missing != 1, missing != 2,
			missing != 3, missing != 4, missing != 5, missing != 6, missing != 7, missing != 8,
			missing != 9, missing != 10, distance);
	}

	@Test
	void arrivalRequiresActualFiniteNonnegativeDistanceWithinThreeBlocks() {
		assertTrue(SettlementMeetingInvitations.arrived(0));
		assertTrue(SettlementMeetingInvitations.arrived(9));
		for (double distance : new double[] {9.00001, 16, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertFalse(SettlementMeetingInvitations.arrived(distance));
		}
	}

	@Test
	void waitingMustRemainWithinThreeBlocksWhileTravelMayStillBeOutside() {
		assertEquals(400, SettlementMeetingInvitations.MAX_TRAVEL_TICKS);
		assertEquals(600, SettlementMeetingInvitations.WAIT_TICKS);
		assertEquals(1200, SettlementMeetingInvitations.LIFETIME_TICKS);
		assertTrue(SettlementMeetingInvitations.waitingPositionValid(true, 0));
		assertTrue(SettlementMeetingInvitations.waitingPositionValid(true, 9));
		for (double distance : new double[] {9.0001, 16, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertFalse(SettlementMeetingInvitations.waitingPositionValid(true, distance));
		}
		assertTrue(SettlementMeetingInvitations.waitingPositionValid(false, 9.0001));
		assertTrue(SettlementMeetingInvitations.waitingPositionValid(false, 4096));
	}

	@Test
	void onlyAnExplicitMeetingConsumesTheDayAndCancelsExistingConsent() {
		Fixture f = fixture();
		UUID second = new UUID(30, 3);
		f.data.registerMember(f.settlement, second, 0);
		f.state.mapResident(second, false, 0);
		Person other = f.state.world().person(f.state.residentId(second).orElseThrow());
		other.initializeAge(Person.AgeBand.ADULT, 0);
		var ticket = f.ticket();
		assertNotNull(ticket);
		var people = List.of(f.person(), other);
		assertEquals("held", f.state.norms().holdCommonStoreMeeting(people, people, f.state.campOwner().id(), "0,64,0").status());
		assertEquals("already_held", f.gate());
		assertNull(f.ticket());
		assertFalse(ticket.valid(f.data, 101, 0));
	}

	@Test
	void hungerGateUsesCoreSatietyEvenWithoutAvailableFoodOrAnEatingDecision() {
		Fixture f = fixture();
		for (int satiety : List.of(0, 34, 35, 36, 100)) {
			f.person().setHunger(100 - satiety);
			assertEquals(satiety <= 35 ? "hungry" : "ready", f.gate());
			assertEquals(satiety > 35, f.ticket() != null);
		}
	}

	@Test
	void everyNonDecisionAgeAndExhaustedBudgetRejectsAnInvitation() {
		Fixture f = fixture();
		for (Person.AgeBand age : Person.AgeBand.values()) {
			f.person().initializeAge(age, 0);
			boolean adult = age == Person.AgeBand.ADULT || age == Person.AgeBand.ELDER;
			assertEquals(adult ? "ready" : "unavailable", f.gate(), age.toString());
		}
		f.person().initializeAge(Person.AgeBand.ADULT, 0);
		f.person().restoreSocialState(0, 5, 4);
		assertEquals("ready", f.gate());
		f.person().restoreSocialState(0, 6, 4);
		assertEquals("exhausted", f.gate());
		assertNull(f.ticket());
	}

	@Test
	void modeDayMembershipAndProposalGatesAreSharedWithTicketCreation() {
		Fixture f = fixture();
		f.state.setNormsEnabled(false);
		assertEquals("disabled", f.gate());
		assertNull(f.ticket());
		f.state.setNormsEnabled(true);
		f.state.setSurvivalEnabled(false, 0);
		assertEquals("disabled", f.gate());
		f.state.setSurvivalEnabled(true, 0);
		assertEquals("day_pending", SettlementMeetingInvitations.gate(f.data, f.settlement, RESIDENT, 1));
		f.data.removeMember(RESIDENT);
		assertEquals("not_member", f.gate());
		Fixture noProposal = fixture(false);
		assertEquals("no_proposal", noProposal.gate());
		assertNull(noProposal.ticket());
	}

	@Test
	void ticketHasAFixedTwelveHundredTickWindowAndClockRollbackCancelsPermanently() {
		Fixture f = fixture();
		var ticket = f.ticket();
		assertNotNull(ticket);
		assertEquals(1200, SettlementMeetingInvitations.LIFETIME_TICKS);
		assertEquals(1300, ticket.deadline());
		assertEquals(INVITER, ticket.inviter());
		assertFalse(ticket.waiting());
		assertTrue(ticket.valid(f.data, 1299, 0));
		assertFalse(ticket.valid(f.data, 1300, 0));
		assertFalse(ticket.valid(f.data, 101, 0), "Expired tickets never resurrect");
		var rollback = f.ticket();
		assertFalse(rollback.valid(f.data, 99, 0));
		assertFalse(rollback.valid(f.data, 100, 0));
	}

	@Test
	void waitingExpiresAfterSixHundredTicksWithoutExtendingTheTotalWindow() {
		Fixture f = fixture();
		var early = f.ticket();
		early.arrive(150);
		assertTrue(early.waiting());
		early.arrive(700);
		assertTrue(early.valid(f.data, 749, 0));
		assertFalse(early.valid(f.data, 750, 0), "Repeated arrival does not renew waiting");
		assertFalse(early.waiting());
		var late = f.ticket();
		late.arrive(1200);
		assertEquals(1300, late.deadline());
		assertTrue(late.valid(f.data, 1299, 0));
		assertFalse(late.valid(f.data, 1300, 0), "Waiting is bounded by original total lifetime");
	}

	@Test
	void malformedClockValuesCannotCreateOrArriveTickets() {
		Fixture f = fixture();
		for (long tick : new long[] {-1, Long.MAX_VALUE, Long.MAX_VALUE - 1199}) {
			assertNull(SettlementMeetingInvitations.create(f.data, f.settlement, RESIDENT, INVITER, tick, 0, () -> true));
		}
		var ticket = f.ticket();
		ticket.arrive(99);
		assertFalse(ticket.waiting());
		ticket.arrive(1300);
		assertFalse(ticket.waiting());
	}

	@Test
	void inspectionMatchesDoesNotMutateTicketButValidationCancelsIt() {
		Fixture f = fixture();
		var ticket = f.ticket();
		assertFalse(ticket.matches(f.data, 1300, 0));
		assertTrue(ticket.matches(f.data, 100, 0), "Read-only inspection must not cancel consent");
		assertFalse(ticket.valid(f.data, 1300, 0));
		assertFalse(ticket.matches(f.data, 100, 0));
	}

	@Test
	void invitationResultsAndInteractionStatusHaveBilingualMessages() throws Exception {
		for (String locale : List.of("en_us", "zh_cn")) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + locale + ".json")) {
				assertNotNull(stream);
				var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String result : List.of("accepted", "cancelled", "already_invited", "stale", "disabled", "day_pending",
					"already_held", "no_proposal", "not_member", "unavailable", "hungry", "exhausted", "eating", "store_unavailable")) {
					String key = "coopcraft.interaction.meeting." + result;
					assertTrue(json.has(key), locale + ": " + key);
					assertEquals(1, json.get(key).getAsString().split("%s", -1).length - 1, key);
				}
				for (String status : List.of("inspect_none", "inspect_pending", "inspect_travelling", "inspect_waiting",
					"hint")) {
					String key = "coopcraft.interaction.meeting." + status;
					assertTrue(json.has(key), locale + ": " + key);
					assertFalse(json.get(key).getAsString().isBlank());
					assertFalse(json.get(key).getAsString().contains("%s"));
				}
				for (String state : List.of("attend_meeting", "meeting_wait")) {
					String key = "coopcraft.interaction.action." + state;
					assertTrue(json.has(key), locale + ": " + key);
					assertFalse(json.get(key).getAsString().isBlank());
				}
			}
		}
	}

	@Test
	void cancellationIsPermanentAndCannotBecomeWaiting() {
		Fixture f = fixture();
		var ticket = f.ticket();
		ticket.cancel();
		ticket.cancel();
		ticket.arrive(101);
		assertFalse(ticket.valid(f.data, 101, 0));
		assertFalse(ticket.waiting());
	}

	@Test
	void invalidSceneNeverCreatesTicketAndInvalidationCannotBeReversed() {
		Fixture f = fixture();
		AtomicBoolean scene = new AtomicBoolean(false);
		assertNull(SettlementMeetingInvitations.create(f.data, f.settlement, RESIDENT, INVITER, 100, 0, scene::get));
		scene.set(true);
		var ticket = SettlementMeetingInvitations.create(f.data, f.settlement, RESIDENT, INVITER, 100, 0, scene::get);
		assertNotNull(ticket);
		scene.set(false);
		assertFalse(ticket.valid(f.data, 101, 0));
		scene.set(true);
		assertFalse(ticket.valid(f.data, 102, 0), "Unload/reload, box replacement or invalid entity cannot revive consent");
	}

	@Test
	void allCapturedCoreAndBindingChangesPermanentlyInvalidateTicket() {
		List<Consumer<Fixture>> changes = List.of(
			f -> f.person().setHunger(65),
			f -> f.person().restoreSocialState(0, 6, 0),
			f -> f.person().initializeAge(Person.AgeBand.CHILD, 0),
			f -> f.state.world().advanceDay(),
			f -> { f.state.setNormsEnabled(false); f.state.setNormsEnabled(true); },
			f -> { f.state.setSurvivalEnabled(false, 0); f.state.setSurvivalEnabled(true, 0); },
			f -> f.data.removeMember(RESIDENT),
			f -> { f.data.removeMember(RESIDENT); f.data.registerMember(f.settlement, RESIDENT, 0); },
			f -> f.data.setStockpile(f.settlement, new BlockPos(2, 64, 0)),
			f -> f.data.setStockpile(f.settlement, f.data.getStockpile(f.settlement).orElseThrow()));
		for (int i = 0; i < changes.size(); i++) {
			Fixture f = fixture();
			var ticket = f.ticket();
			assertNotNull(ticket);
			changes.get(i).accept(f);
			assertFalse(ticket.valid(f.data, 101, 0), "Captured identity change " + i);
			assertFalse(ticket.valid(f.data, 102, 0));
		}
		Fixture f = fixture();
		var day = f.ticket();
		assertFalse(day.valid(f.data, 101, 1));
		assertFalse(day.valid(f.data, 102, 0));
	}

	@Test
	void invitationQueriesAndCancellationAreEntirelyReadOnlyAndDoNotConveyKnowledge() {
		Fixture f = fixture();
		f.state.recordFoodAllocation(f.person().id(), 1);
		f.person().restoreSocialState(9, 2, 3);
		CompoundTag before = f.save();
		var resources = f.state.world().inventoryTotals();
		var events = f.state.world().ledger().events();
		var information = List.copyOf(f.person().information());
		var knownBy = f.proposal().knownBy();
		f.data.setDirty(false);
		for (int i = 0; i < 20; i++) {
			assertEquals("ready", f.gate());
			var ticket = f.ticket();
			assertNotNull(ticket);
			assertTrue(ticket.valid(f.data, 101 + i, 0));
			ticket.arrive(130 + i);
			assertTrue(ticket.waiting());
			assertTrue(ticket.valid(f.data, 140 + i, 0));
			ticket.cancel();
			assertEquals(before, f.save());
			assertFalse(f.data.isDirty());
		}
		assertEquals(resources, f.state.world().inventoryTotals());
		assertEquals(events, f.state.world().ledger().events());
		assertEquals(information, f.person().information());
		assertEquals(knownBy, f.proposal().knownBy());
		assertEquals(0, f.proposal().rounds());
		assertEquals(2, f.person().socialHours());
		assertEquals(3, f.person().previousSocialHours());
		assertEquals(1, f.state.allocatedFoodUnits(f.person().id()));
		assertEquals(Long.MIN_VALUE, f.state.norms().lastMeetingDay(f.state.campOwner().id()));
		f.state.world().assertConservation();
	}

	@Test
	void reloadContainsNoTicketAndOriginalTicketCannotMigrateToEquivalentSavedState() {
		Fixture f = fixture();
		CompoundTag before = f.save();
		var ticket = f.ticket();
		assertNotNull(ticket);
		assertEquals(before, f.save(), "No ticket or invitation fields may be persisted");
		SettlementSavedData loaded = SettlementSavedData.load(f.save(), null);
		assertEquals(before, loaded.save(new CompoundTag(), null));
		assertFalse(ticket.valid(loaded, 101, 0));
		assertFalse(ticket.valid(f.data, 102, 0), "A rejected migration permanently cancels the original");
	}

	private static Fixture fixture() { return fixture(true); }

	private static Fixture fixture(boolean proposal) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Invitation tests", 0);
		data.registerMember(settlement, RESIDENT, 0);
		data.setStockpile(settlement, new BlockPos(0, 64, 0));
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(RESIDENT, false, 0);
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		Person person = state.world().person(state.residentId(RESIDENT).orElseThrow());
		person.initializeAge(Person.AgeBand.ADULT, 0);
		person.setHunger(0);
		person.restoreSocialState(1, 0, 0);
		person.observeNorm("neighbor-requests");
		state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
		if (proposal) {
			long request = state.world().recordShareRequest(person.id(), state.campOwner().id(), 40);
			state.world().recordShareRefusal(request, person.id(), state.campOwner().id(), 20);
			state.norms().observeSocialDay(List.of(person));
			assertFalse(state.world().proposalsForCamp(state.campOwner().id()).isEmpty());
		}
		return new Fixture(data, settlement, state);
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person() { return state.world().person(state.residentId(RESIDENT).orElseThrow()); }
		NormProposal proposal() { return state.world().proposalsForCamp(state.campOwner().id()).getFirst(); }
		String gate() { return SettlementMeetingInvitations.gate(data, settlement, RESIDENT, 0); }
		CompoundTag save() { return data.save(new CompoundTag(), null); }
		SettlementMeetingInvitations.Ticket ticket() {
			return SettlementMeetingInvitations.create(data, settlement, RESIDENT, INVITER, 100, 0, () -> true);
		}
	}
}
