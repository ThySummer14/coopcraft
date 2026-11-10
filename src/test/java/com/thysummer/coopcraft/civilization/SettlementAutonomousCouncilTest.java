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

/** No player or server: real review, shared executor, ledger, people, and ephemeral clock gate. */
class SettlementAutonomousCouncilTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static UUID id(int index) { return new UUID(182, index); }
	private record Fixture(SettlementSavedData data, Settlement camp, SettlementSimulationState state, NormProposal proposal) {
		List<Person> people() { return state.world().people().stream().toList(); }
		Person person(int index) { return state.world().person(state.residentId(id(index)).orElseThrow()); }
		SettlementMeetings.Inspection inspect(List<Person> present) {
			return SettlementAutonomousMeetings.inspectPeople(state, people(), present, "minecraft:overworld 0,64,0");
		}
		SettlementMeetings.SceneIdentity scene() {
			return new SettlementMeetings.SceneIdentity(data, null, data.stockpileBindingToken(camp), BlockPos.ZERO, state, null,
				data.membersOf(camp).stream().map(m -> (Object) new SettlementMeetings.ResidentIdentity(m, person((int)m.villagerId().getLeastSignificantBits()))).toList(), 0);
		}
		CompoundTag save() { return data.save(new CompoundTag(), null); }
	}
	private static Fixture fixture(int count) { return fixture(count, false, true); }
	private static Fixture fixture(int count, boolean allKnown, boolean attachInformation) {
		var data = new SettlementSavedData(); var camp = data.getOrCreate(BlockPos.ZERO, "Council", 0);
		var state = data.getOrCreateSimulation(camp);
		for (int i = 1; i <= count; i++) { data.registerMember(camp, id(i), 0); state.mapResident(id(i), false, 0); }
		state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true); state.setAutonomousMeetingsEnabled(true);
		state.world().importExternalBatch(ResourceType.FISH, 30, state.campOwner());
		Person first = state.world().person(state.residentId(id(1)).orElseThrow());
		long request = state.world().recordShareRequest(first.id(), state.campOwner().id(), 40);
		long refusal = state.world().recordShareRefusal(request, first.id(), state.campOwner().id(), 20);
		var proposal = NormProposal.restore("council-proposal", state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE,
			first.id(), 0, refusal, 4, 0, 0, NormProposal.Status.OPEN,
			allKnown ? Map.of(first.id(), NormProposal.Position.SUPPORT,
				state.residentId(id(2)).orElseThrow(), NormProposal.Position.UNDECIDED)
				: Map.of(first.id(), NormProposal.Position.SUPPORT));
		state.world().registerProposal(proposal);
		long event = MeetingTestEvents.record(state.world(), EventType.NORM_PROPOSED, List.of(first.id()),
			Map.of("proposalId", proposal.proposalId(), "normId", proposal.normId(), "cap", "4", "revision", "0"), List.of(refusal));
		if (attachInformation) first.learnInformation(new SocialInformation("council-info", SocialInformation.Kind.NORM_PROPOSAL,
			first.id(), proposal.proposalId(), event, 0, null, 1.0));
		return new Fixture(data, camp, state, proposal);
	}
	@Test void realDiscussionChangesOnlyAfterAllTwoHundredTicksAndUsesActualParticipants() {
		var f = fixture(5); var present = List.of(f.person(1), f.person(2));
		var inspection = f.inspect(present); assertTrue(SettlementAutonomousMeetings.reviewReady(inspection, 2));
		assertEquals(5, inspection.result().discussion().adultCount());
		var preview = SettlementMeetings.preview(f.data, f.camp, inspection, f.scene());
		var before = f.save(); var totals = f.state.world().inventoryTotals(); f.data.setDirty(false);
		var registry = f.data.councilSessions(); var group = registry.reserve(f.camp.id(), List.of(id(1), id(2)), 5, 0, null);
		registry.join(group, id(1), 0); registry.join(group, id(2), 0);
		for (int tick = 1; tick <= 200; tick++) {
			assertEquals(before, f.save()); assertFalse(f.data.isDirty());
			assertFalse(f.proposal.knownBy().contains(f.person(2).id()));
			assertFalse(registry.pulse(group, id(1), tick, true));
			if (registry.pulse(group, id(2), tick, true)) {
				assertEquals(200, tick); assertFalse(registry.claimed(id(1)));
				assertTrue(SettlementMeetings.holdReviewed(f.data, f.camp, f.inspect(present), f.scene(), preview).held());
			}
		}
		assertTrue(f.data.isDirty()); assertTrue(f.proposal.knownBy().contains(f.person(2).id()));
		assertFalse(f.proposal.knownBy().contains(f.person(3).id()));
		assertEquals(1, f.person(1).socialHours()); assertEquals(1, f.person(2).socialHours()); assertEquals(0, f.person(3).socialHours());
		assertEquals(totals, f.state.world().inventoryTotals()); f.state.world().assertConservation();
		assertEquals("already_held", SettlementMeetings.holdReviewed(f.data, f.camp, f.inspect(present), f.scene(), preview).status());
	}
	@Test void knownByFlagsWithoutVerifiedInformationCannotStartAutonomousMeetingButManualStillWorks() {
		for (boolean evicted : List.of(false, true)) {
			var f = fixture(2, true, evicted); var present = f.people();
			assertEquals(2, f.proposal.knownBy().size());
			if (evicted) {
				assertTrue(f.inspect(present).review().hasVerifiedSource());
				long event = f.person(1).information("council-info").orElseThrow().sourceEventId();
				for (int i = 0; i < 32; i++) f.person(1).learnInformation(new SocialInformation("other-news-" + i,
					SocialInformation.Kind.SHARE_REFUSAL, f.person(1).id(), "other-request-" + i, event, 0, null, 1.0));
				assertFalse(f.person(1).knowsInformation("council-info"));
			}
			var before = f.save(); f.data.setDirty(false);
			var inspection = f.inspect(present);
			assertEquals("ready", inspection.result().status());
			assertEquals(2, inspection.result().discussion().participants());
			assertFalse(inspection.review().hasVerifiedSource());
			assertFalse(SettlementAutonomousMeetings.reviewReady(inspection, 2));
			assertEquals(before, f.save()); assertFalse(f.data.isDirty());
			assertEquals(0, f.person(1).socialHours()); assertEquals(0, f.person(2).socialHours());
			var preview = SettlementMeetings.preview(f.data, f.camp, inspection, f.scene());
			assertTrue(SettlementMeetings.holdReviewed(f.data, f.camp, f.inspect(present), f.scene(), preview).held());
			assertEquals(1, f.person(1).socialHours()); assertEquals(1, f.person(2).socialHours());
		}
	}
	@Test void missingInformedSourceAndInsufficientFullPopulationQuorumDoNotStart() {
		var f = fixture(6);
		assertFalse(SettlementAutonomousMeetings.reviewReady(f.inspect(List.of(f.person(1), f.person(2))), 2));
		assertFalse(SettlementAutonomousMeetings.reviewReady(f.inspect(List.of(f.person(2), f.person(3), f.person(4))), 3));
		assertTrue(SettlementAutonomousMeetings.reviewReady(f.inspect(List.of(f.person(1), f.person(2), f.person(3))), 3));
		var large = fixture(12);
		assertFalse(SettlementAutonomousMeetings.reviewReady(large.inspect(large.people().subList(0, 4)), 4));
	}
	@Test void cancellationAndPersistenceNeverLeakOwnershipInformationOrPartialHours() {
		var f = fixture(2); var before = f.save(); f.data.setDirty(false);
		var group = f.data.councilSessions().reserve(f.camp.id(), List.of(id(1), id(2)), 2, 0, null);
		f.data.councilSessions().join(group, id(1), 0); f.data.councilSessions().join(group, id(2), 0);
		for (int tick = 1; tick < 200; tick++) {
			f.data.councilSessions().pulse(group, id(1), tick, true); f.data.councilSessions().pulse(group, id(2), tick, true);
		}
		var loaded = SettlementSavedData.load(f.save(), null);
		assertTrue(loaded.councilSessions().groups().isEmpty());
		assertFalse(f.data.councilSessions().pulse(group, id(2), 200, false));
		assertEquals(before, f.save()); assertFalse(f.data.isDirty()); assertEquals(0, f.person(1).socialHours());
	}
	@Test void manualMeetingWinningTheDailyRacePreventsAutonomousSettlement() {
		var f = fixture(3); var present = List.of(f.person(1), f.person(2));
		var preview = SettlementMeetings.preview(f.data, f.camp, f.inspect(present), f.scene());
		assertTrue(f.state.norms().holdCommonStoreMeeting(f.people(), present, f.state.campOwner().id(), "manual-store").held());
		var afterManual = f.save();
		assertEquals("already_held", SettlementMeetings.holdReviewed(f.data, f.camp, f.inspect(present), f.scene(), preview).status());
		assertEquals(afterManual, f.save()); assertEquals(1, f.person(1).socialHours());
	}
	@Test void modeAndMembershipIdentityChangesInvalidateSharedReviewWithoutMutation() {
		for (boolean membership : List.of(false, true)) {
			var f = fixture(2); var present = f.people();
			var preview = SettlementMeetings.preview(f.data, f.camp, f.inspect(present), f.scene());
			if (membership) { f.data.removeMember(id(2)); f.data.registerMember(f.camp, id(2), 0); }
			else { f.state.setNormsEnabled(false); f.state.setNormsEnabled(true); }
			var before = f.save();
			assertEquals("review_changed", SettlementMeetings.holdReviewed(f.data, f.camp, f.inspect(present), f.scene(), preview).status());
			assertEquals(before, f.save());
		}
	}
	@Test void sightPreflightRejectsUnloadedDiagonalChunksBeforeAnyRayRead() {
		var from = new net.minecraft.world.phys.Vec3(14.5, 65, 14.5);
		var to = new net.minecraft.world.phys.Vec3(17.5, 65, 17.5);
		var visited = new java.util.HashSet<BlockPos>();
		assertTrue(SettlementAutonomousMeetings.sightLoaded(from, to, pos -> { visited.add(pos); return true; }));
		assertEquals(4, visited.size());
		// The endpoints occupy (0,0) and (1,1), but the ray can cross either intervening chunk.
		assertFalse(SettlementAutonomousMeetings.sightLoaded(from, to, pos -> !(pos.getX() == 0 && pos.getZ() == 16)));
		assertFalse(SettlementAutonomousMeetings.sightLoaded(from, to, pos -> !(pos.getX() == 16 && pos.getZ() == 0)));
		assertFalse(SettlementAutonomousMeetings.sightLoaded(from, new net.minecraft.world.phys.Vec3(100, 65, 100), pos -> fail("No distant scan")));
		assertFalse(SettlementAutonomousMeetings.sightLoaded(from, new net.minecraft.world.phys.Vec3(Double.NaN, 65, 1), pos -> fail("No invalid scan")));
	}
	@Test void adapterBindsPhysicalStoreAndIndependentOptionVersionAtEveryValidation() throws java.io.IOException {
		String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementAutonomousMeetings.java"));
		assertTrue(source.indexOf("!registry.attempt(camp.id(), now)") > source.indexOf("!reviewReady(inspection, participants.size())"));
		assertTrue(source.indexOf("!registry.attempt(camp.id(), now)") > source.indexOf("!candidate(level, data, camp, store, initiator)"));
		assertTrue(source.contains("physicalStore = SettlementCraftingStore.capture(level, store)"));
		assertTrue(source.contains("s.physicalStore == null || !s.physicalStore.valid()"));
		assertTrue(source.contains("s.state.autonomousMeetingsVersion() != s.optionVersion"));
		assertTrue(source.contains("s.state.foodRequestModeVersion() != s.modeVersion"));
		assertTrue(source.contains("ConstructionSite.safeSupport(level.getBlockState(resident.blockPosition().below()))"));
		assertTrue(source.contains("sightLoaded(first.getEyePosition(), second.getEyePosition(), level::isLoaded)"));
		assertTrue(source.contains("sightLoaded(resident.getEyePosition(), store.getCenter(), level::isLoaded)"));
	}

}
