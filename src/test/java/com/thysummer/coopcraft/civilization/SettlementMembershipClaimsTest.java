package com.thysummer.coopcraft.civilization;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementMembershipClaims.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementMembershipClaimsTest {
	private static final UUID RESIDENT = new UUID(33, 1);
	private static final UUID NEWCOMER = new UUID(33, 2);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void rawClaimsAreDimensionQualifiedAndReadsPreserveAllNbtAndObjects() {
		var local = new SettlementSavedData();
		var remote = new SettlementSavedData();
		var third = new SettlementSavedData();
		Settlement home = remote.getOrCreate(BlockPos.ZERO, "Same", 0);
		Settlement duplicateId = third.getOrCreate(BlockPos.ZERO, "Same", 0);
		assertEquals(home.id(), duplicateId.id());
		remote.registerMember(home, RESIDENT, 4);
		var sources = List.of(new Source(Level.OVERWORLD, local), new Source(Level.NETHER, remote), new Source(Level.END, third));
		Object identity = remote.memberIdentity(RESIDENT);
		assertEquals(Status.NONE, resolve(sources, Level.OVERWORLD, NEWCOMER).status());
		var unique = resolve(sources, Level.OVERWORLD, RESIDENT);
		assertEquals(Status.UNIQUE, unique.status());
		assertSame(remote, unique.uniqueClaim().orElseThrow().data());
		assertSame(identity, unique.uniqueClaim().orElseThrow().member());
		assertEquals(Level.NETHER, unique.uniqueClaim().orElseThrow().dimension());
		assertSame(home, unique.uniqueClaim().orElseThrow().settlement());
		assertEquals(Status.UNIQUE, resolve(sources, Level.NETHER, RESIDENT).status());
		List<CompoundTag> before = sources.stream().map(source -> save(source.data())).toList();
		sources.forEach(source -> source.data().setDirty(false));
		assertEquals(unique, resolve(List.of(sources.get(2), sources.get(1), sources.get(0)), Level.OVERWORLD, RESIDENT));
		assertEquals(before, sources.stream().map(source -> save(source.data())).toList());
		assertTrue(sources.stream().noneMatch(source -> source.data().isDirty()));
		third.registerMember(duplicateId, RESIDENT, 8);
		assertEquals(Status.CONFLICT, resolve(sources, Level.OVERWORLD, RESIDENT).status());
		assertEquals(resolve(sources, Level.OVERWORLD, RESIDENT),
			resolve(List.of(sources.get(2), sources.get(0), sources.get(1)), Level.OVERWORLD, RESIDENT));
		assertTrue(resolve(sources, Level.OVERWORLD, RESIDENT).uniqueClaim().isEmpty());
		assertSame(identity, remote.memberIdentity(RESIDENT));
	}

	@Test
	void orphanAndConflictClaimsSurviveRoundTripsAndRejections() {
		var local = new SettlementSavedData();
		Settlement target = local.getOrCreate(BlockPos.ZERO, "Target", 0);
		CompoundTag orphanTag = save(new SettlementSavedData());
		orphanTag.getList("Members", ListTag.TAG_COMPOUND).add(new SettlementMember(RESIDENT, new UUID(33, 404), 7).save());
		var orphan = SettlementSavedData.load(orphanTag, null);
		var sources = List.of(new Source(Level.OVERWORLD, local), new Source(Level.NETHER, orphan));
		List<CompoundTag> before = sources.stream().map(source -> save(source.data())).toList();
		sources.forEach(source -> source.data().setDirty(false));
		var result = resolve(sources, Level.OVERWORLD, RESIDENT);
		assertEquals(Status.ORPHAN, result.status());
		assertTrue(result.uniqueClaim().isEmpty());
		assertNull(result.claims().getFirst().settlement());
		assertTrue(SettlementMembership.unclaimedFoundersGlobally(sources, Level.OVERWORLD, List.of(candidate(RESIDENT))).isEmpty());
		assertTrue(SettlementMembership.registerCandidates(sources, Level.OVERWORLD, local, target, List.of(candidate(RESIDENT)), 10).isEmpty());
		assertEquals(before, sources.stream().map(source -> save(source.data())).toList());
		assertTrue(sources.stream().noneMatch(source -> source.data().isDirty()));
		assertTrue(local.simulationOf(target).isEmpty());
		local.registerMember(target, RESIDENT, 9);
		assertEquals(Status.CONFLICT, resolve(sources, Level.OVERWORLD, RESIDENT).status());
		var reloaded = SettlementSavedData.load(save(orphan), null);
		assertEquals(save(orphan), save(reloaded));
		assertEquals(Status.ORPHAN, resolve(List.of(new Source(Level.NETHER, reloaded)), Level.NETHER, RESIDENT).status());
	}

	@Test
	void unavailableSourcesNeverAuthorizeAbsenceOrLocalMutation() {
		var data = new SettlementSavedData();
		Settlement target = data.getOrCreate(BlockPos.ZERO, "Target", 0);
		Source current = new Source(Level.OVERWORLD, data);
		List<List<Source>> invalid = Arrays.asList(null, List.of(), List.of(new Source(Level.NETHER, data)),
			List.of(current, current), Arrays.asList(current, null), List.of(current, new Source(Level.END, null)),
			List.of(new Source(null, data)), new AbstractList<Source>() {
				@Override public Source get(int index) { throw new IllegalStateException("source iteration failed"); }
				@Override public int size() { return 1; }
			});
		CompoundTag before = save(data);
		data.setDirty(false);
		for (List<Source> sources : invalid) {
			assertEquals(Status.UNAVAILABLE, resolve(sources, Level.OVERWORLD, NEWCOMER).status());
			assertTrue(SettlementMembership.unclaimedFoundersGlobally(sources, Level.OVERWORLD, List.of(candidate(NEWCOMER))).isEmpty());
			assertTrue(SettlementMembership.registerCandidates(sources, Level.OVERWORLD, data, target, List.of(candidate(NEWCOMER)), 8).isEmpty());
		}
		assertEquals(Status.UNAVAILABLE, resolve(List.of(current), null, NEWCOMER).status());
		assertEquals(Status.UNAVAILABLE, resolve(List.of(current), Level.OVERWORLD, null).status());
		assertEquals(Status.UNAVAILABLE, resolve((ServerLevel) null, NEWCOMER).status());
		assertEquals(before, save(data));
		assertFalse(data.isDirty());
	}

	@Test
	void foreignClaimsCannotFoundOrEnterScanAcceptedRosterAndWrongWriteTargetIsRejected() {
		var local = new SettlementSavedData();
		var remote = new SettlementSavedData();
		Settlement target = local.getOrCreate(BlockPos.ZERO, "Target", 0);
		Settlement home = remote.getOrCreate(BlockPos.ZERO, "Home", 0);
		remote.registerMember(home, RESIDENT, 4);
		var sources = List.of(new Source(Level.OVERWORLD, local), new Source(Level.NETHER, remote));
		var candidates = List.of(candidate(RESIDENT), candidate(NEWCOMER), candidate(NEWCOMER));
		assertEquals(List.of(NEWCOMER), SettlementMembership.unclaimedFoundersGlobally(sources, Level.OVERWORLD, candidates));
		CompoundTag remoteBefore = save(remote);
		remote.setDirty(false);
		assertTrue(SettlementMembership.registerCandidates(sources, Level.OVERWORLD, remote, home, candidates, 8).isEmpty());
		assertEquals(List.of(NEWCOMER), SettlementMembership.registerCandidates(sources, Level.OVERWORLD, local, target, candidates, 8));
		Object identity = local.memberIdentity(NEWCOMER);
		CompoundTag localBefore = save(local);
		local.setDirty(false);
		assertEquals(List.of(NEWCOMER), SettlementMembership.registerCandidates(sources, Level.OVERWORLD, local, target, candidates, 99));
		assertSame(identity, local.memberIdentity(NEWCOMER));
		assertEquals(localBefore, save(local));
		assertEquals(remoteBefore, save(remote));
		assertFalse(local.isDirty());
		assertFalse(remote.isDirty());
		assertTrue(local.simulationOf(target).isEmpty());
		assertEquals(1, local.registeredCount(target));
	}

	@Test
	void observedDeathRoutesOnlyToSingleOwnerIncludingOrphanAndNeverChangesCoreHistory() {
		var local = new SettlementSavedData();
		var remote = new SettlementSavedData();
		Settlement localCamp = local.getOrCreate(BlockPos.ZERO, "Local", 0);
		Settlement home = remote.getOrCreate(BlockPos.ZERO, "Home", 0);
		remote.registerMember(home, RESIDENT, 4);
		remote.setLeader(home, RESIDENT);
		SettlementSimulationState state = remote.getOrCreateSimulation(home);
		state.mapResident(RESIDENT, false, 70);
		CompoundTag core = state.save();
		CompoundTag localBefore = save(local);
		local.setDirty(false);
		var sources = List.of(new Source(Level.OVERWORLD, local), new Source(Level.NETHER, remote));
		assertTrue(SettlementSavedData.removeObservedDeadMember(sources, Level.OVERWORLD, RESIDENT));
		assertFalse(remote.isRegistered(RESIDENT));
		assertTrue(remote.leaderOf(home).isEmpty());
		assertEquals(core, state.save());
		assertEquals(localBefore, save(local));
		assertFalse(local.isDirty());
		remote.registerMember(home, RESIDENT, 6);
		local.registerMember(localCamp, RESIDENT, 6);
		List<CompoundTag> conflict = sources.stream().map(source -> save(source.data())).toList();
		sources.forEach(source -> source.data().setDirty(false));
		assertFalse(SettlementSavedData.removeObservedDeadMember(sources, Level.OVERWORLD, RESIDENT));
		assertFalse(SettlementSavedData.removeObservedDeadMember(List.of(sources.get(1)), Level.OVERWORLD, RESIDENT));
		assertFalse(SettlementSavedData.removeObservedDeadMember(sources, Level.OVERWORLD, NEWCOMER));
		assertEquals(conflict, sources.stream().map(source -> save(source.data())).toList());
		assertTrue(sources.stream().noneMatch(source -> source.data().isDirty()));
		CompoundTag orphanTag = save(new SettlementSavedData());
		orphanTag.getList("Members", ListTag.TAG_COMPOUND).add(new SettlementMember(NEWCOMER, new UUID(33, 404), 7).save());
		var orphan = SettlementSavedData.load(orphanTag, null);
		assertTrue(SettlementSavedData.removeObservedDeadMember(List.of(new Source(Level.OVERWORLD, local),
			new Source(Level.END, orphan)), Level.OVERWORLD, NEWCOMER));
		assertFalse(orphan.isRegistered(NEWCOMER));
		assertEquals(conflict.getFirst(), save(local));
	}

	private static SettlementMembership.Candidate candidate(UUID id) {
		return new SettlementMembership.Candidate(id, true, false, true, true, 0);
	}
	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }
}
