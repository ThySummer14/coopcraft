package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementMaterialScoutingTest {
	private static final UUID ACTOR = new UUID(981, 1);
	private static final BlockPos START = new BlockPos(0, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(START, "Home", 0);
		Fixture() {
			data.registerMember(camp, ACTOR, 0);
			data.permitGatheringArea(camp, ACTOR, new BlockPos(40, 64, 0), 8, SettlementGatheringAreas.Mode.BOTH);
		}
		SettlementMaterialScouting.Session session() { return new SettlementMaterialScouting.Session(data, camp, ACTOR, START, 100); }
	}
	private static class Probe implements SettlementMaterialScouting.Probe {
		boolean bounds = true, loaded = true, ticking = true, blocked;
		int reads;
		BlockState support = Blocks.STONE.defaultBlockState(), feet = Blocks.AIR.defaultBlockState();
		public boolean inBounds(BlockPos pos) { return bounds; }
		public boolean loaded(BlockPos pos) { return loaded; }
		public boolean ticking(BlockPos pos) { return ticking; }
		public boolean blocked(BlockPos pos) { return blocked; }
		public BlockState state(BlockPos pos) { reads++; return pos.getY() == 63 ? support : feet; }
	}
	@Test void physicalModeNeedsNoSimulationAndReadOnlyTripDoesNotPersistOrClaim() {
		Fixture f = new Fixture(); CompoundTag before = f.data.save(new CompoundTag(), null);
		var session = f.session(); assertTrue(session.valid(f.data, true, 100));
		assertTrue(session.attemptPath()); session.observe(200); session.cancel();
		assertEquals(before, f.data.save(new CompoundTag(), null)); assertEquals(0, f.data.materialLeases().size(200));
	}
	@Test void revocationReplacementMemberReplacementDataReplacementAndBodyFailurePermanentlyCancel() {
		for (int change = 0; change < 5; change++) {
			Fixture f = new Fixture(); var session = f.session();
			SettlementSavedData current = f.data;
			if (change == 0) {
				f.data.clearGatheringArea(f.camp);
				f.data.permitGatheringArea(f.camp, ACTOR, new BlockPos(40, 64, 0), 8, SettlementGatheringAreas.Mode.BOTH);
			}
			if (change == 1) f.data.permitGatheringArea(f.camp, ACTOR, new BlockPos(41, 64, 0), 8, SettlementGatheringAreas.Mode.BOTH);
			if (change == 2) { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); }
			if (change == 3) current = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
			assertFalse(session.valid(current, change != 4, 101));
			assertFalse(session.valid(f.data, true, 102)); assertFalse(session.attemptPath());
		}
	}
	@Test void budgetsAreWholeTripAndObservationClockCannotBeRestarted() {
		Fixture f = new Fixture(); var session = f.session();
		for (int i = 0; i < SettlementMaterialScouting.MAX_PATH_ATTEMPTS; i++) assertTrue(session.attemptPath());
		assertFalse(session.attemptPath());
		assertTrue(session.valid(f.data, true, 699)); assertFalse(session.valid(f.data, true, 700));
		session = f.session(); session.observe(200); session.observe(250);
		assertTrue(session.valid(f.data, true, 319)); assertFalse(session.valid(f.data, true, 320));
		session = f.session(); assertFalse(session.valid(f.data, true, 99));
	}
	@Test void fillingObservationCacheCancelsWithoutEvictingOrRevivingTheTrip() {
		Fixture f = new Fixture(); var session = f.session();
		for (int i = 0; i < 64; i++) f.data.recordResourceObservation(f.camp, START.offset(i, 0, 0), SettlementResourceObservations.Kind.OAK_LOG);
		var before = f.data.save(new CompoundTag(), null);
		assertFalse(session.valid(f.data, true, 101)); assertFalse(f.data.canObserveResource(f.camp));
		assertEquals(before, f.data.save(new CompoundTag(), null)); assertEquals(64, f.data.resourceObservationsOf(f.camp).size());
	}

	@Test void cooldownIsIndependentOfSessionAndHandlesRewind() {
		var cooldown = new SettlementMaterialScouting.Cooldown(); assertTrue(cooldown.ready(100));
		cooldown.defer(100); assertFalse(cooldown.ready(499)); assertTrue(cooldown.ready(500)); assertTrue(cooldown.ready(99));
		cooldown.defer(200); assertFalse(cooldown.ready(599));
	}
	@Test void shortWaypointsStopAtObservationGroundRatherThanResourceCenter() {
		Fixture f = new Fixture(); var area = f.data.gatheringAreaOf(f.camp).orElseThrow(); BlockPos current = START;
		int count = 0;
		while (true) {
			BlockPos next = SettlementMaterialScouting.waypoint(current, area);
			if (next.equals(current)) break;
			assertEquals(64, next.getY()); assertTrue(current.distManhattan(next) <= SettlementMaterialScouting.STEP);
			current = next; assertTrue(++count <= 12);
		}
		assertEquals(new BlockPos(37, 64, 0), current); assertTrue(area.contains(current));
		assertEquals(current, SettlementMaterialScouting.waypoint(current, area));
	}
	@Test void accessAndProtectionVetoesPrecedeEveryTerrainRead() {
		for (int i = 0; i < 4; i++) {
			Probe probe = new Probe(); probe.bounds = i != 0; probe.loaded = i != 1; probe.ticking = i != 2; probe.blocked = i == 3;
			assertFalse(SettlementMaterialScouting.safe(START, probe)); assertEquals(0, probe.reads);
		}
		Probe corner = new Probe() { public boolean loaded(BlockPos pos) { return !pos.equals(START.offset(1, 1, 1)); } };
		assertFalse(SettlementMaterialScouting.safe(START, corner)); assertEquals(0, corner.reads);
	}
	@Test void farmlandFluidsDoorsAndDangerousGroundCannotBeWalked() {
		for (var block : List.of(Blocks.FARMLAND, Blocks.MAGMA_BLOCK, Blocks.SAND, Blocks.ICE, Blocks.WATER, Blocks.LAVA, Blocks.CACTUS, Blocks.POWDER_SNOW)) {
			Probe probe = new Probe(); probe.support = block.defaultBlockState(); assertFalse(SettlementMaterialScouting.safe(START, probe), block.toString());
		}
		for (var block : List.of(Blocks.OAK_DOOR, Blocks.WATER, Blocks.FIRE, Blocks.SWEET_BERRY_BUSH)) {
			Probe probe = new Probe(); probe.feet = block.defaultBlockState(); assertFalse(SettlementMaterialScouting.safe(START, probe));
		}
		assertTrue(SettlementMaterialScouting.safe(START, new Probe()));
	}
	@Test void pathRequiresAdjacentSameHeightNodesWithinLegAndSafeShoulders() {
		BlockPos target = START.offset(6, 0, 0); List<BlockPos> nodes = new ArrayList<>();
		for (int x = 0; x <= 6; x++) nodes.add(START.offset(x, 0, 0));
		assertTrue(SettlementMaterialScouting.pathSafe(nodes, START, target, new Probe()));
		assertFalse(SettlementMaterialScouting.pathSafe(List.of(target), START, target, new Probe()));
		assertFalse(SettlementMaterialScouting.pathSafe(List.of(START.above(), target), START, target, new Probe()));
		assertFalse(SettlementMaterialScouting.pathSafe(List.of(START, START.offset(1, 0, 1), target), START, target, new Probe()));
		assertFalse(SettlementMaterialScouting.pathSafe(nodes, START, target.offset(1, 0, 0), new Probe()));
		Probe changed = new Probe() { public boolean blocked(BlockPos pos) { return pos.equals(START.offset(3, 0, 1)); } };
		assertFalse(SettlementMaterialScouting.pathSafe(nodes, START, target, changed));
	}
	@Test void capacityIsReadOnlyAndIncludesSuppressionAndBothCaps() {
		var records = new SettlementResourceObservations();
		for (int camp = 0; camp < 16; camp++) {
			UUID id = new UUID(982, camp); assertTrue(records.hasCapacity(id));
			for (int i = 0; i < 64; i++) assertTrue(records.observe(id, START.offset(i, 0, 0), SettlementResourceObservations.Kind.OAK_LOG));
			assertFalse(records.hasCapacity(id));
		}
		assertFalse(records.hasCapacity(new UUID(983, 0))); var before = records.save();
		var first = records.of(new UUID(982, 0)).getFirst(); records.suppress(new UUID(982, 0), first.id());
		assertFalse(records.hasCapacity(new UUID(982, 0))); assertEquals(64, records.of(new UUID(982, 0)).size());
		var saved = records.save(); assertFalse(records.hasCapacity(new UUID(983, 0))); assertEquals(saved, records.save());
		assertNotEquals(before, saved);
	}
	@Test void finalGroundLetsUnmodifiedPassiveScannerObserveCenterWithoutGrantingHarvest() {
		Fixture f = new Fixture(); var area = f.data.gatheringAreaOf(f.camp).orElseThrow();
		BlockPos center = new BlockPos(area.centerX(), 64, area.centerZ());
		BlockPos[] resident = { START };
		var gatherProbe = new SettlementMaterialGathering.Probe() {
			public SettlementSavedData currentData() { return f.data; }
			public boolean activeAdult() { return true; }
			public boolean inBounds(BlockPos pos) { return true; }
			public boolean loaded(BlockPos pos) { return true; }
			public boolean ticking(BlockPos pos) { return true; }
			public double distanceSquared(BlockPos pos) { return resident[0].distSqr(pos); }
			public BlockState state(BlockPos pos) { return pos.equals(center) ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
		};
		assertTrue(f.data.resourceObservationsOf(f.camp).isEmpty());
		assertTrue(SettlementMaterialGathering.candidates(f.data, f.camp, ACTOR, gatherProbe, 0).isEmpty());
		BlockPos position = START;
		for (int i = 0; i < SettlementMaterialScouting.MAX_PATH_ATTEMPTS; i++) position = SettlementMaterialScouting.waypoint(position, area);
		final BlockPos stand = position; resident[0] = stand;
		assertTrue(SettlementResourceDiscovery.near(stand, center)); assertNotEquals(center, stand);
		int[] reads = { 0 };
		var probe = new SettlementResourceDiscovery.Probe() {
			public SettlementObservationClaims membership() { return SettlementObservationClaims.capture(List.of(
				new SettlementMembershipClaims.Source(net.minecraft.world.level.Level.OVERWORLD, f.data)), net.minecraft.world.level.Level.OVERWORLD, f.data); }
			public SettlementResourceDiscovery.Observer observer(UUID id) { return new SettlementResourceDiscovery.Observer(stand, true, false, true, true, false, false); }
			public boolean inBounds(BlockPos pos) { return true; }
			public boolean loaded(BlockPos pos) { return true; }
			public boolean entityTicking(BlockPos pos) { return true; }
			public BlockState blockState(BlockPos pos) { reads[0]++; return pos.equals(center) ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
		};
		for (int tick = 0; tick < SettlementMaterialScouting.OBSERVE_TICKS; tick++) {
			int before = reads[0]; f.data.resourceDiscovery().tick(f.data, tick, probe);
			assertTrue(reads[0] - before <= SettlementResourceDiscovery.POSITION_BUDGET);
		}
		assertEquals(List.of(center), f.data.resourceObservationsOf(f.camp).stream().map(SettlementResourceObservations.Observation::position).toList());
		assertEquals(SettlementResourceObservations.Source.UNKNOWN, f.data.resourceObservationsOf(f.camp).getFirst().source());
		assertTrue(f.data.resourcesOf(f.camp).isEmpty()); assertEquals(0, f.data.materialLeases().size(200));
		var choices = SettlementMaterialGathering.candidates(f.data, f.camp, ACTOR, gatherProbe, 200);
		assertEquals(1, choices.size()); assertEquals(center, choices.getFirst().position());
		assertEquals(0, f.data.materialLeases().size(200));
	}

	@Test void campBoundaryAndDisplacementRejectOutOfTripPositions() {
		Fixture f = new Fixture(); assertTrue(SettlementMaterialScouting.withinCamp(START.offset(64, 0, 0), f.camp));
		assertFalse(SettlementMaterialScouting.withinCamp(START.offset(64, 0, 1), f.camp));
		assertTrue(SettlementMaterialScouting.onLeg(START.offset(2, 0, 0), START, START.offset(6, 0, 0)));
		assertFalse(SettlementMaterialScouting.onLeg(START.offset(2, 0, 1), START, START.offset(6, 0, 0)));
		assertFalse(SettlementMaterialScouting.onLeg(START.offset(7, 0, 0), START, START.offset(6, 0, 0)));
	}

}
