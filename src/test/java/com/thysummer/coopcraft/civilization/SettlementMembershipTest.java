package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.WorkType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementSavedData.MemberRegistrationResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shared registration boundary tests, not a substitute for real entity, naming or bell checks. */
class SettlementMembershipTest {
	private static final UUID RESIDENT = new UUID(32, 1);
	private static final UUID COMPANION = new UUID(32, 2);
	private static final UUID FOREIGN = new UUID(32, 3);
	private static final UUID ORPHAN = new UUID(32, 4);
	private static final UUID NEWCOMER = new UUID(32, 5);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void registrationResultsDistinguishNewExistingForeignAndInvalidWithoutRepairingState() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Membership", 0);
		Settlement other = data.getOrCreate(new BlockPos(128, 0, 0), "Other", 0);
		CompoundTag empty = save(data);
		data.setDirty(false);
		assertEquals(ADDED, data.memberRegistrationResult(settlement, RESIDENT, 4));
		assertEquals(empty, save(data), "Preflight must not enroll or allocate simulation state");
		assertFalse(data.isDirty());
		assertEquals(ADDED, data.registerMember(settlement, RESIDENT, 4));
		assertTrue(data.isDirty());
		Object identity = data.memberIdentity(RESIDENT);
		CompoundTag before = save(data);
		data.setDirty(false);
		assertEquals(ALREADY_REGISTERED, data.registerMember(settlement, RESIDENT, Long.MAX_VALUE));
		assertEquals(OTHER_SETTLEMENT, data.registerMember(other, RESIDENT, 8));
		Settlement stale = new Settlement(settlement.id(), settlement.center(), "Stale name", settlement.foundedDay());
		Settlement unknown = new Settlement(new UUID(32, 90), new BlockPos(256, 0, 0), "Unknown", 0);
		assertEquals(INVALID, data.registerMember(null, RESIDENT, 0));
		assertEquals(INVALID, data.registerMember(settlement, null, 0));
		assertEquals(INVALID, data.registerMember(settlement, RESIDENT, -1));
		assertEquals(INVALID, data.registerMember(settlement, NEWCOMER, -1));
		assertEquals(INVALID, data.registerMember(stale, RESIDENT, 0));
		assertEquals(INVALID, data.registerMember(unknown, NEWCOMER, 0));
		assertTrue(ADDED.accepted());
		assertTrue(ALREADY_REGISTERED.accepted());
		assertFalse(OTHER_SETTLEMENT.accepted());
		assertFalse(INVALID.accepted());
		assertFalse(data.isRegistered(null));
		assertTrue(data.isRegistered(RESIDENT));
		assertFalse(data.isRegistered(NEWCOMER));
		assertSame(identity, data.memberIdentity(RESIDENT));
		assertEquals(4, data.membersOf(settlement).getFirst().joinedDay());
		assertEquals(before, save(data));
		assertFalse(data.isDirty());
		assertTrue(data.simulationOf(settlement).isEmpty());
		assertTrue(data.simulationOf(other).isEmpty());
	}

	@Test
	void repeatedAcceptedScansPreserveIdentityCoreHistoryAllocationAndLiveTickets() {
		Fixture f = fixture();
		Object identity = f.data.memberIdentity(RESIDENT);
		Person person = f.person(RESIDENT);
		var personality = f.data.personalityOf(f.settlement, RESIDENT);
		AtomicInteger meals = new AtomicInteger();
		var queue = queue(f, 100, meals);
		var invitation = invitation(f, 100);
		assertNotNull(queue);
		assertTrue(queue.waiting());
		assertNotNull(invitation);
		CompoundTag before = save(f.data);
		f.data.setDirty(false);
		for (int repeat = 0; repeat < 20; repeat++) {
			assertEquals(List.of(RESIDENT, COMPANION), SettlementMembership.registerCandidates(f.data, f.settlement,
				List.of(candidate(RESIDENT, 0), candidate(COMPANION, 4096), candidate(RESIDENT, 1)), 90 + repeat));
			assertSame(identity, f.data.memberIdentity(RESIDENT));
			assertSame(person, f.person(RESIDENT));
			assertSame(personality, f.data.personalityOf(f.settlement, RESIDENT));
			assertTrue(queue.waiting(), "A scan must not cancel an existing food request");
			assertTrue(invitation.valid(f.data, 101 + repeat, 0));
			assertEquals(before, save(f.data));
			assertFalse(f.data.isDirty());
		}
		assertEquals(4, f.data.membersOf(f.settlement).getFirst().joinedDay());
		assertEquals(61, f.data.needsOf(f.settlement, RESIDENT).satiety());
		assertEquals(RESIDENT, f.data.leaderOf(f.settlement).orElseThrow());
		assertEquals(83, person.trustIn(f.person(COMPANION).id()));
		assertEquals(1, f.state.allocatedFoodUnits(person.id()));
		assertEquals(2, person.socialHours());
		assertEquals(1.5, person.skill(WorkType.GATHER));
		f.data.foodRequests().flush(f.data, f.settlement, 140, 0);
		assertEquals(1, meals.get(), "The original queue entry remains executable exactly once");
		assertFalse(queue.waiting());
		assertEquals(before, save(f.data));
		f.state.world().assertConservation();
	}

	@Test
	void foreignClaimCannotBeOverwrittenOrMappedIntoAnotherCamp() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement home = data.getOrCreate(BlockPos.ZERO, "Home", 0);
		Settlement visitorCamp = data.getOrCreate(new BlockPos(128, 0, 0), "Visitor", 0);
		data.registerMember(home, RESIDENT, 4);
		data.setLeader(home, RESIDENT);
		data.setSatiety(home, RESIDENT, 37);
		SettlementSimulationState homeState = data.getOrCreateSimulation(home);
		homeState.mapResident(RESIDENT, false, 37);
		Object identity = data.memberIdentity(RESIDENT);
		CompoundTag before = save(data);
		data.setDirty(false);
		assertEquals(OTHER_SETTLEMENT, data.registerMember(visitorCamp, RESIDENT, 12));
		assertTrue(SettlementMembership.registerCandidates(data, visitorCamp, List.of(candidate(RESIDENT, 0)), 12).isEmpty());
		assertEquals(home, data.findSettlementForVillager(RESIDENT).orElseThrow());
		assertSame(identity, data.memberIdentity(RESIDENT));
		assertEquals(1, data.registeredCount(home));
		assertEquals(0, data.registeredCount(visitorCamp));
		assertTrue(data.simulationOf(visitorCamp).isEmpty());
		assertEquals(before, save(data));
		assertFalse(data.isDirty());
	}

	@Test
	void orphanClaimRemainsClaimedEvenWhenItsSettlementCannotBeResolved() {
		Fixture f = withOrphan(fixture());
		assertTrue(f.data.isRegistered(ORPHAN));
		assertTrue(f.data.findSettlementForVillager(ORPHAN).isEmpty());
		Object identity = f.data.memberIdentity(ORPHAN);
		CompoundTag before = save(f.data);
		f.data.setDirty(false);
		assertEquals(OTHER_SETTLEMENT, f.data.memberRegistrationResult(f.settlement, ORPHAN, 9));
		assertEquals(OTHER_SETTLEMENT, f.data.registerMember(f.settlement, ORPHAN, 9));
		assertTrue(SettlementMembership.registerCandidates(f.data, f.settlement, List.of(candidate(ORPHAN, 0)), 9).isEmpty());
		assertTrue(SettlementMembership.unclaimedFounders(f.data, List.of(candidate(ORPHAN, 0))).isEmpty());
		assertSame(identity, f.data.memberIdentity(ORPHAN));
		assertTrue(f.state.residentId(ORPHAN).isEmpty());
		assertEquals(before, save(f.data));
		assertFalse(f.data.isDirty());
	}

	@Test
	void absentRemoteDeadUnloadedAndInvalidCandidatesNeverRemoveKnownMembers() {
		Fixture f = fixture();
		Object identity = f.data.memberIdentity(RESIDENT);
		CompoundTag before = save(f.data);
		f.data.setDirty(false);
		assertTrue(SettlementMembership.registerCandidates(f.data, f.settlement, List.of(), 7).isEmpty());
		assertTrue(SettlementMembership.registerCandidates(null, f.settlement, List.of(candidate(RESIDENT, 0)), 7).isEmpty());
		assertTrue(SettlementMembership.registerCandidates(f.data, f.settlement, null, 7).isEmpty());
		assertTrue(SettlementMembership.registerCandidates(f.data, null, List.of(candidate(NEWCOMER, 0)), 7).isEmpty());
		List<SettlementMembership.Candidate> invalid = invalidCandidates(RESIDENT);
		invalid.addAll(invalidCandidates(NEWCOMER));
		assertTrue(SettlementMembership.registerCandidates(f.data, f.settlement, invalid, 7).isEmpty());
		assertSame(identity, f.data.memberIdentity(RESIDENT));
		assertTrue(f.data.isMember(f.settlement, RESIDENT));
		assertTrue(f.data.isMember(f.settlement, COMPANION), "An absent resident remains enrolled");
		assertFalse(f.data.isRegistered(NEWCOMER));
		assertEquals(2, f.data.registeredCount(f.settlement));
		assertEquals(before, save(f.data));
		assertFalse(f.data.isDirty());
	}

	@Test
	void acceptedCandidatesAreUniqueActualMembersWithAnInclusiveSixtyFourBlockBoundary() {
		Fixture f = fixture();
		Settlement foreignCamp = f.data.getOrCreate(new BlockPos(128, 0, 0), "Foreign", 0);
		f.data.registerMember(foreignCamp, FOREIGN, 2);
		List<UUID> accepted = SettlementMembership.registerCandidates(f.data, f.settlement, List.of(
			candidate(RESIDENT, 0), candidate(NEWCOMER, 4096), candidate(NEWCOMER, 1),
			candidate(FOREIGN, 0), candidate(new UUID(32, 99), Math.nextUp(4096.0))), 12);
		assertEquals(List.of(RESIDENT, NEWCOMER), accepted);
		assertTrue(accepted.stream().allMatch(id -> f.data.isMember(f.settlement, id)));
		assertEquals(3, f.data.registeredCount(f.settlement), "Duplicates and foreign residents do not inflate population");
		assertEquals(1, f.data.registeredCount(foreignCamp));
		assertThrows(UnsupportedOperationException.class, () -> accepted.add(COMPANION));
		assertTrue(f.state.residentId(FOREIGN).isEmpty());
		assertTrue(f.data.simulationOf(foreignCamp).isEmpty());
	}

	@Test
	void foundersRequireUnclaimedValidUniqueCandidatesInsideThirtyTwoBlocks() {
		Fixture f = withOrphan(fixture());
		Settlement other = f.data.getOrCreate(new BlockPos(128, 0, 0), "Foreign", 0);
		f.data.registerMember(other, FOREIGN, 2);
		UUID boundary = new UUID(32, 10);
		List<SettlementMembership.Candidate> candidates = invalidCandidates(new UUID(32, 11));
		candidates.addAll(List.of(candidate(RESIDENT, 0), candidate(FOREIGN, 0), candidate(ORPHAN, 0),
			candidate(NEWCOMER, 0), candidate(NEWCOMER, 16), candidate(boundary, 1024),
			candidate(new UUID(32, 12), Math.nextUp(1024.0)), candidate(new UUID(32, 13), 4096)));
		CompoundTag before = save(f.data);
		f.data.setDirty(false);
		List<UUID> founders = SettlementMembership.unclaimedFounders(f.data, candidates);
		assertEquals(List.of(NEWCOMER, boundary), founders);
		assertTrue(SettlementMembership.unclaimedFounders(null, candidates).isEmpty());
		assertTrue(SettlementMembership.unclaimedFounders(f.data, null).isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> founders.add(FOREIGN));
		assertFalse(f.data.isRegistered(NEWCOMER), "Founding eligibility is read-only");
		assertEquals(before, save(f.data));
		assertFalse(f.data.isDirty());
	}

	@Test
	void missingLegacyPersonalityAndSimulationDoNotReplaceMembersOrEnrollRejectedCandidates() {
		Fixture original = fixture();
		CompoundTag legacy = save(original.data);
		legacy.remove("Simulations");
		ListTag members = legacy.getList("Members", ListTag.TAG_COMPOUND);
		for (int i = 0; i < members.size(); i++) members.getCompound(i).remove("Personality");
		SettlementSavedData data = SettlementSavedData.load(legacy, null);
		Settlement settlement = data.find(BlockPos.ZERO).orElseThrow();
		Object identity = data.memberIdentity(RESIDENT);
		var personality = data.personalityOf(settlement, RESIDENT);
		CompoundTag before = save(data);
		data.setDirty(false);
		List<UUID> accepted = SettlementMembership.registerCandidates(data, settlement,
			List.of(candidate(RESIDENT, 4096), candidate(NEWCOMER, 4097)), 99);
		assertEquals(List.of(RESIDENT), accepted);
		assertSame(identity, data.memberIdentity(RESIDENT));
		assertSame(personality, data.personalityOf(settlement, RESIDENT));
		assertEquals(VillagerPersonality.fromVillager(RESIDENT), personality);
		assertTrue(data.simulationOf(settlement).isEmpty(), "Registration alone does not invent core history");
		assertEquals(before, save(data));
		assertFalse(data.isDirty());
		// Exercise the accepted-list mapping contract without pretending to invoke an entity scan.
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		accepted.forEach(id -> state.mapResident(id, false, data.needsOf(settlement, id).satiety()));
		assertTrue(state.residentId(RESIDENT).isPresent());
		assertTrue(state.residentId(COMPANION).isEmpty(), "An absent old member is not mapped by this scan");
		assertTrue(state.residentId(NEWCOMER).isEmpty());
		assertEquals(2, data.registeredCount(settlement));
		assertTrue(state.world().ledger().events().isEmpty());
	}

	@Test
	void explicitRemovalAndRejoinInvalidatesOldTicketsButPreservesHistoricalPerson() {
		Fixture f = fixture();
		Object identity = f.data.memberIdentity(RESIDENT);
		Person person = f.person(RESIDENT);
		CompoundTag coreBefore = f.state.save();
		AtomicInteger meals = new AtomicInteger();
		var oldQueue = queue(f, 100, meals);
		var oldInvitation = invitation(f, 100);
		assertNotNull(oldQueue);
		assertNotNull(oldInvitation);
		f.data.removeMember(RESIDENT);
		assertFalse(oldQueue.waiting());
		assertEquals(ADDED, f.data.registerMember(f.settlement, RESIDENT, 12));
		assertNotSame(identity, f.data.memberIdentity(RESIDENT));
		assertFalse(f.state.mapResident(RESIDENT, false, 100));
		assertSame(person, f.person(RESIDENT));
		assertEquals(coreBefore, f.state.save());
		assertFalse(oldInvitation.valid(f.data, 101, 0));
		f.data.foodRequests().flush(f.data, f.settlement, 140, 0);
		assertEquals(0, meals.get(), "An obsolete queue entry cannot revive after reenrollment");
		assertEquals(12, f.data.membersOf(f.settlement).getFirst().joinedDay());
		assertEquals(MemberNeeds.DEFAULT, f.data.needsOf(f.settlement, RESIDENT));
		assertTrue(f.data.leaderOf(f.settlement).isEmpty());
		var newQueue = queue(f, 200, meals);
		var newInvitation = invitation(f, 200);
		assertNotNull(newQueue);
		assertNotNull(newInvitation);
		assertTrue(newInvitation.valid(f.data, 201, 0));
		assertFalse(oldInvitation.valid(f.data, 201, 0));
		f.data.foodRequests().flush(f.data, f.settlement, 240, 0);
		assertEquals(1, meals.get());
		assertEquals(coreBefore, f.state.save());
	}

	@Test
	void repeatedRoundTripsAndRescansPreserveWholeNbtWithoutDirtyingOrReplayingHistory() {
		Fixture f = fixture();
		CompoundTag canonical = save(f.data);
		for (int pass = 0; pass < 2; pass++) {
			CompoundTag input = save(f.data);
			CompoundTag untouched = input.copy();
			SettlementSavedData loaded = SettlementSavedData.load(input, null);
			Settlement settlement = loaded.find(BlockPos.ZERO).orElseThrow();
			f = new Fixture(loaded, settlement, loaded.simulationOf(settlement).orElseThrow());
			Object identity = loaded.memberIdentity(RESIDENT);
			loaded.setDirty(false);
			assertEquals(List.of(RESIDENT, COMPANION), SettlementMembership.registerCandidates(loaded, settlement,
				List.of(candidate(RESIDENT, 0), candidate(COMPANION, 4096)), 100 + pass));
			assertSame(identity, loaded.memberIdentity(RESIDENT));
			assertEquals(untouched, input);
			assertEquals(canonical, save(loaded));
			assertEquals(22, save(loaded).getInt("DataVersion"));
			assertFalse(loaded.isDirty());
			assertEquals(83, f.person(RESIDENT).trustIn(f.person(COMPANION).id()));
			assertEquals(1, f.state.allocatedFoodUnits(f.person(RESIDENT).id()));
			f.state.world().assertConservation();
		}
	}

	@Test
	void birthRejectsForeignOrOrphanChildClaimsAndInvalidParentsBeforeAnyCoreWrite() {
		Fixture f = withOrphan(fixture());
		Settlement foreignCamp = f.data.getOrCreate(new BlockPos(128, 0, 0), "Foreign", 0);
		f.data.registerMember(foreignCamp, FOREIGN, 2);
		CompoundTag before = save(f.data);
		f.data.setDirty(false);
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, COMPANION, FOREIGN, 9));
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, COMPANION, ORPHAN, 9));
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, RESIDENT, NEWCOMER, 9));
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, FOREIGN, NEWCOMER, 9));
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, null, NEWCOMER, 9));
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, -1));
		assertFalse(f.data.isRegistered(NEWCOMER));
		assertTrue(f.state.residentId(FOREIGN).isEmpty());
		assertTrue(f.state.residentId(ORPHAN).isEmpty());
		assertTrue(f.data.simulationOf(foreignCamp).isEmpty());
		assertEquals(before, save(f.data));
		assertFalse(f.data.isDirty());
	}

	@Test
	void alreadyRegisteredUnmappedChildNeverAcquiresInventedBirthHistory() {
		Fixture f = fixture();
		f.data.registerMember(f.settlement, NEWCOMER, 2);
		f.data.setSatiety(f.settlement, NEWCOMER, 41);
		Object identity = f.data.memberIdentity(NEWCOMER);
		var personality = f.data.personalityOf(f.settlement, NEWCOMER);
		CompoundTag before = save(f.data);
		f.data.setDirty(false);
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, 9));
		assertSame(identity, f.data.memberIdentity(NEWCOMER));
		assertSame(personality, f.data.personalityOf(f.settlement, NEWCOMER));
		assertEquals(2, f.data.membersOf(f.settlement).stream().filter(m -> m.villagerId().equals(NEWCOMER)).findFirst().orElseThrow().joinedDay());
		assertTrue(f.state.residentId(NEWCOMER).isEmpty());
		assertEquals(before, save(f.data));
		assertFalse(f.data.isDirty());
	}

	@Test
	void unclaimedNewbornGetsOneMembershipAndBirthMappingWithoutReplay() {
		Fixture f = fixture();
		int eventsBefore = f.state.world().ledger().events().size();
		assertTrue(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, 9));
		assertTrue(f.data.isMember(f.settlement, NEWCOMER));
		assertEquals(9, f.data.membersOf(f.settlement).stream().filter(m -> m.villagerId().equals(NEWCOMER)).findFirst().orElseThrow().joinedDay());
		assertEquals(List.of(f.person(RESIDENT).id(), f.person(COMPANION).id()), f.person(NEWCOMER).parents());
		assertEquals(f.person(RESIDENT).householdId(), f.person(NEWCOMER).householdId());
		assertEquals(100 - MemberNeeds.DEFAULT.satiety(), f.person(NEWCOMER).hunger());
		assertEquals(eventsBefore + 1, f.state.world().ledger().events().size());
		CompoundTag beforeRepeat = save(f.data);
		f.data.setDirty(false);
		assertFalse(SettlementSimulationService.recordBirth(f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, 99));
		assertEquals(beforeRepeat, save(f.data));
		assertFalse(f.data.isDirty());
	}

	@Test
	void globalBirthPreflightRejectsConflictingParentsAndClaimedChildWithoutCoreWrites() {
		Fixture f = fixture();
		SettlementSavedData remote = new SettlementSavedData();
		Settlement other = remote.getOrCreate(BlockPos.ZERO, "Remote", 0);
		var sources = List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, f.data),
			new SettlementMembershipClaims.Source(Level.NETHER, remote));
		for (UUID conflict : List.of(RESIDENT, COMPANION, NEWCOMER)) {
			remote.registerMember(other, conflict, 2);
			CompoundTag localBefore = save(f.data);
			CompoundTag remoteBefore = save(remote);
			f.data.setDirty(false);
			remote.setDirty(false);
			assertFalse(SettlementSimulationService.recordBirth(sources, Level.OVERWORLD,
				f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, 9));
			assertEquals(localBefore, save(f.data));
			assertEquals(remoteBefore, save(remote));
			assertFalse(f.data.isDirty());
			assertFalse(remote.isDirty());
			remote.removeMember(conflict);
		}
		Object identity = f.data.memberIdentity(RESIDENT);
		AtomicInteger meals = new AtomicInteger();
		var queue = queue(f, 100, meals);
		var invitation = invitation(f, 100);
		assertEquals(List.of(RESIDENT), SettlementMembership.registerCandidates(sources, Level.OVERWORLD,
			f.data, f.settlement, List.of(candidate(RESIDENT, 0), candidate(RESIDENT, 10)), 99));
		assertSame(identity, f.data.memberIdentity(RESIDENT));
		assertTrue(queue.waiting());
		assertTrue(invitation.valid(f.data, 101, 0));
		assertTrue(SettlementSimulationService.recordBirth(sources, Level.OVERWORLD,
			f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, 9));
		CompoundTag after = save(f.data);
		f.data.setDirty(false);
		assertFalse(SettlementSimulationService.recordBirth(sources, Level.OVERWORLD,
			f.data, f.settlement, RESIDENT, COMPANION, NEWCOMER, 99));
		assertEquals(after, save(f.data));
		assertFalse(f.data.isDirty());
	}

	private static SettlementMembership.Candidate candidate(UUID id, double distanceSquared) {
		return new SettlementMembership.Candidate(id, true, false, true, true, distanceSquared);
	}

	private static List<SettlementMembership.Candidate> invalidCandidates(UUID id) {
		List<SettlementMembership.Candidate> result = new ArrayList<>();
		result.add(null);
		result.add(candidate(null, 0));
		result.add(new SettlementMembership.Candidate(id, false, false, true, true, 0));
		result.add(new SettlementMembership.Candidate(id, true, true, true, true, 0));
		result.add(new SettlementMembership.Candidate(id, true, false, false, true, 0));
		result.add(new SettlementMembership.Candidate(id, true, false, true, false, 0));
		for (double distance : new double[] {Math.nextUp(4096.0), -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			result.add(candidate(id, distance));
		}
		return result;
	}

	private static Fixture fixture() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Stable members", 0);
		data.registerMember(settlement, RESIDENT, 4);
		data.registerMember(settlement, COMPANION, 6);
		data.setLeader(settlement, RESIDENT);
		data.setSatiety(settlement, RESIDENT, 61);
		data.setStockpile(settlement, BlockPos.ZERO.east());
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		state.mapResident(RESIDENT, false, 80);
		state.mapResident(COMPANION, false, 70);
		state.setSurvivalEnabled(true, 0);
		state.setNormsEnabled(true);
		Fixture f = new Fixture(data, settlement, state);
		Person resident = f.person(RESIDENT);
		Person companion = f.person(COMPANION);
		resident.setTrust(companion.id(), 83);
		resident.restoreSocialState(7, 2, 1);
		resident.setSkill(WorkType.GATHER, 1.5);
		state.recordFoodAllocation(resident.id(), 1);
		state.world().importExternalBatch(ResourceType.FISH, 12, state.campOwner());
		Set<String> supporters = Set.of(resident.id(), companion.id());
		state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, state.campOwner().id(), 3,
			supporters, Set.of(), supporters, 70, Norm.Status.ACTIVE, List.of()));
		long request = state.world().recordShareRequest(resident.id(), state.campOwner().id(), 40);
		long refusal = state.world().recordShareRefusal(request, resident.id(), state.campOwner().id(), 20);
		state.world().registerProposal(NormProposal.restore("membership-care", state.campOwner().id(), Norm.CARE_FIRST_COMMON_STORE,
			resident.id(), 0, refusal, 1, 0, 0, NormProposal.Status.OPEN, Map.of(resident.id(), NormProposal.Position.SUPPORT)));
		return f;
	}

	private static Fixture withOrphan(Fixture f) {
		CompoundTag tag = save(f.data);
		tag.getList("Members", ListTag.TAG_COMPOUND).add(new SettlementMember(ORPHAN, new UUID(32, 404), 3).save());
		SettlementSavedData loaded = SettlementSavedData.load(tag, null);
		Settlement settlement = loaded.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(loaded, settlement, loaded.simulationOf(settlement).orElseThrow());
	}

	private static SettlementFoodRequests.Ticket queue(Fixture f, long tick, AtomicInteger meals) {
		return f.data.foodRequests().submit(f.data, f.settlement, RESIDENT, tick, 0, null, () -> true, meals::incrementAndGet);
	}

	private static SettlementMeetingInvitations.Ticket invitation(Fixture f, long tick) {
		return SettlementMeetingInvitations.create(f.data, f.settlement, RESIDENT, new UUID(32, 100), tick, 0, () -> true);
	}

	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }

	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
	}
}
