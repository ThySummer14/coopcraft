package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production scanner with real SavedData, block states and containers, but no server. */
class SettlementResourceDiscoveryTest {
	private static final UUID ACTOR = new UUID(301, 1);
	private static final BlockPos CENTER = new BlockPos(0, 64, 0);
	private static final int SWEEP_TICKS = (SettlementResourceDiscovery.VOLUME + SettlementResourceDiscovery.POSITION_BUDGET - 1)
		/ SettlementResourceDiscovery.POSITION_BUDGET;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static BlockState ripe() {
		return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
	}

	private static final class Fixture implements SettlementResourceDiscovery.Probe {
		final SettlementSavedData data;
		final Settlement camp;
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<UUID, SettlementResourceDiscovery.Observer> observers = new HashMap<>();
		boolean bounds = true, loaded = true, ticking = true, allRipe;
		int reads, selections;
		long time;
		Runnable onRead = () -> {};
		Fixture() { this(new SettlementSavedData()); }
		Fixture(SettlementSavedData data) {
			this.data = data; camp = data.getOrCreate(CENTER, "Observation", 0);
			data.registerMember(camp, ACTOR, 0); observers.put(ACTOR, adult(CENTER));
		}
		@Override public SettlementObservationClaims membership() { return claims(data); }
		@Override public SettlementResourceDiscovery.Observer observer(UUID id) { selections++; return observers.get(id); }
		@Override public boolean inBounds(BlockPos pos) { return bounds; }
		@Override public boolean loaded(BlockPos pos) { return loaded; }
		@Override public boolean entityTicking(BlockPos pos) { return ticking; }
		@Override public BlockState blockState(BlockPos pos) {
			assertTrue(bounds && loaded && ticking, "No block read may precede world eligibility checks");
			assertTrue(observers.values().stream().anyMatch(observer -> SettlementResourceDiscovery.near(observer.position(), pos)));
			assertTrue(CENTER.distSqr(pos) <= SettlementResourceDiscovery.CAMP_RADIUS_SQUARED);
			reads++; onRead.run();
			return blocks.getOrDefault(pos, allRipe ? ripe() : Blocks.AIR.defaultBlockState());
		}
		void tick() { data.resourceDiscovery().tick(data, time++, this); }
		void sweep() { for (int i = 0; i < SWEEP_TICKS + 2; i++) tick(); }
		java.util.List<SettlementResourceObservations.Observation> records() { return data.resourceObservationsOf(camp); }
	}

	private static SettlementObservationClaims claims(SettlementSavedData data) {
		return SettlementObservationClaims.capture(java.util.List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, data)), Level.OVERWORLD, data);
	}

	private static SettlementResourceDiscovery.Observer adult(BlockPos pos) {
		return new SettlementResourceDiscovery.Observer(pos, true, false, true, true, false, false);
	}

	@Test void recordsRipeBerriesAsUnknownWithoutGrantingResourceMarksOrRefreshingDirtyState() {
		Fixture f = new Fixture(); f.blocks.put(CENTER, ripe()); f.sweep();
		assertEquals(1, f.records().size());
		var found = f.records().getFirst();
		assertEquals(CENTER, found.position());
		assertEquals(SettlementResourceObservations.Kind.SWEET_BERRY, found.kind());
		assertEquals(SettlementResourceObservations.Source.UNKNOWN, found.source());
		assertEquals(SettlementResourceObservations.State.CANDIDATE, found.state());
		assertTrue(f.data.resourcesOf(f.camp).isEmpty());
		f.data.setDirty(false); CompoundTag saved = f.data.save(new CompoundTag(), null);
		f.sweep();
		assertFalse(f.data.isDirty()); assertEquals(saved, f.data.save(new CompoundTag(), null));
	}

	@Test void mixedMaterialsShareOneBudgetAndNeverInferPlayerBuiltProvenance() {
		Fixture f = new Fixture();
		var materials = java.util.List.of(ripe(), Blocks.OAK_LOG.defaultBlockState(),
			Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
		for (int i = 0; i < 4; i++) f.blocks.put(CENTER.east(i), materials.get(i));
		for (int i = 0; i < SWEEP_TICKS + 2; i++) {
			int reads = f.reads; f.tick();
			assertTrue(f.reads - reads <= SettlementResourceDiscovery.POSITION_BUDGET);
			int after = f.reads;
			f.data.resourceDiscovery().tick(f.data, f.time - 1, f);
			assertEquals(after, f.reads);
		}
		assertEquals(4, f.records().size());
		for (var record : f.records()) {
			assertEquals(SettlementResourceDiscovery.classify(f.blocks.get(record.position())), record.kind());
			assertEquals(SettlementResourceObservations.Source.UNKNOWN, record.source());
			assertFalse(f.data.canHarvestBerries(f.camp, record.position()));
		}
		assertTrue(f.data.resourcesOf(f.camp).isEmpty());
	}

	@Test void classificationRejectsOtherLogsStrippedWoodPlanksAndOtherStone() {
		assertEquals(SettlementResourceObservations.Kind.OAK_LOG, SettlementResourceDiscovery.classify(Blocks.OAK_LOG.defaultBlockState()));
		assertEquals(SettlementResourceObservations.Kind.STONE, SettlementResourceDiscovery.classify(Blocks.STONE.defaultBlockState()));
		assertEquals(SettlementResourceObservations.Kind.STONE, SettlementResourceDiscovery.classify(Blocks.COBBLESTONE.defaultBlockState()));
		for (var block : java.util.List.of(Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.STRIPPED_OAK_LOG,
			Blocks.OAK_WOOD, Blocks.OAK_PLANKS, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS,
			Blocks.DEEPSLATE, Blocks.GRANITE, Blocks.POTATOES, Blocks.AIR)) {
			assertNull(SettlementResourceDiscovery.classify(block.defaultBlockState()));
		}
		assertNull(SettlementResourceDiscovery.classify(Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1)));
		assertNull(SettlementResourceDiscovery.classify(null));
	}

	@Test void protectionPreventsAllTargetReadsAndPreservesHistoricalCandidatesAndSuppressions() {
		Fixture f = new Fixture(); f.blocks.put(CENTER, ripe()); f.blocks.put(CENTER.east(), Blocks.OAK_LOG.defaultBlockState());
		f.blocks.put(CENTER.east(2), Blocks.STONE.defaultBlockState());
		f.blocks.put(CENTER.east(3), Blocks.COBBLESTONE.defaultBlockState());
		f.sweep(); f.data.changeResourceObservation(f.camp, f.records().getFirst().id(), true);
		var before = f.records();
		assertEquals(PlayerProtectedAreas.Status.ADDED, f.data.addProtectedArea(ACTOR, "Keep", -8, -8, 8, 8).status());
		f.data.setDirty(false); int reads = f.reads;
		f.sweep(); assertEquals(reads, f.reads); assertEquals(before, f.records()); assertFalse(f.data.isDirty());
	}

	@Test void protectionAddedDuringReadVetoesEveryKindWithoutErasingOldCandidate() {
		for (var state : java.util.List.of(ripe(), Blocks.OAK_LOG.defaultBlockState(),
			Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
			Fixture f = new Fixture();
			// The first in-range coordinate appears after the intentionally budgeted corner skips.
			BlockPos first = CENTER.offset(-8, -2, 0);
			f.data.recordResourceObservation(f.camp, first, true);
			var before = f.records(); f.blocks.put(first, state);
			f.onRead = () -> f.data.addProtectedArea(ACTOR, "During read", -8, -8, 8, 8);
			f.sweep(); assertEquals(1, f.reads); assertEquals(before, f.records());
			assertFalse(f.data.canHarvestBerries(f.camp, first));
		}
	}

	@Test void malformedProtectionPreventsTargetReadsWithinTheExistingAttemptBudget() {
		Fixture seed = new Fixture(); CompoundTag tag = seed.data.save(new CompoundTag(), null);
		tag.putString("PlayerProtectedAreas", "unknown protection");
		Fixture f = new Fixture(SettlementSavedData.load(tag, null)); f.allRipe = true;
		f.sweep(); assertEquals(0, f.reads); assertTrue(f.records().isEmpty());
		assertTrue(f.selections <= (SWEEP_TICKS + 2) * (SettlementResourceDiscovery.POSITION_BUDGET + 1));
	}

	@Test void perDimensionBudgetCountsSkippedPositionsAndCannotBeSpentTwiceInOneTick() {
		Fixture f = new Fixture(); f.allRipe = true;
		for (int i = 0; i < SWEEP_TICKS * 2; i++) {
			int before = f.reads; f.tick();
			assertTrue(f.reads - before <= SettlementResourceDiscovery.POSITION_BUDGET);
			int after = f.reads;
			f.data.resourceDiscovery().tick(f.data, f.time - 1, f);
			assertEquals(after, f.reads);
		}
		assertEquals(SettlementResourceObservations.MAX_PER_SETTLEMENT, f.records().size());
		assertTrue(f.reads > SettlementResourceDiscovery.POSITION_BUDGET);
	}

	@Test void noMoreThanFourUnavailableMembersAreSelectedPerTick() {
		Fixture f = new Fixture(); f.observers.clear();
		for (int i = 2; i <= 100; i++) f.data.registerMember(f.camp, new UUID(301, i), 0);
		f.tick(); assertEquals(SettlementResourceDiscovery.MEMBER_BUDGET, f.selections); assertEquals(0, f.reads);
		f.tick(); assertEquals(SettlementResourceDiscovery.MEMBER_BUDGET * 2, f.selections);
	}

	@Test void unknownUnloadedNonTickingAndOutOfBoundsObserversCauseZeroBlockReads() {
		for (int unavailable = 0; unavailable < 4; unavailable++) {
			Fixture f = new Fixture(); f.allRipe = true;
			switch (unavailable) {
				case 0 -> f.observers.clear();
				case 1 -> f.loaded = false;
				case 2 -> f.ticking = false;
				case 3 -> f.bounds = false;
			}
			f.sweep(); assertEquals(0, f.reads); assertTrue(f.records().isEmpty());
		}
	}

	@Test void deadRemovedChildWrongDimensionSleepingNoAiAndDistantObserversCannotScan() {
		var invalid = java.util.List.of(
			new SettlementResourceDiscovery.Observer(CENTER, false, false, true, true, false, false),
			new SettlementResourceDiscovery.Observer(CENTER, true, true, true, true, false, false),
			new SettlementResourceDiscovery.Observer(CENTER, true, false, false, true, false, false),
			new SettlementResourceDiscovery.Observer(CENTER, true, false, true, false, false, false),
			new SettlementResourceDiscovery.Observer(CENTER, true, false, true, true, true, false),
			new SettlementResourceDiscovery.Observer(CENTER, true, false, true, true, false, true),
			adult(CENTER.east(65)));
		for (var observer : invalid) {
			Fixture f = new Fixture(); f.observers.put(ACTOR, observer); f.allRipe = true; f.sweep();
			assertEquals(0, f.reads); assertTrue(f.records().isEmpty());
		}
	}

	@Test void worldChecksPrecedeEachTargetReadNotJustTheObserversChunk() {
		Fixture f = new Fixture(); f.allRipe = true;
		var probe = new SettlementResourceDiscovery.Probe() {
			@Override public SettlementObservationClaims membership() { return claims(f.data); }
			@Override public SettlementResourceDiscovery.Observer observer(UUID id) { return adult(CENTER); }
			@Override public boolean inBounds(BlockPos pos) { return pos.equals(CENTER) || pos.getX() >= 0; }
			@Override public boolean loaded(BlockPos pos) { assertTrue(inBounds(pos)); return pos.equals(CENTER) || pos.getZ() >= 0; }
			@Override public boolean entityTicking(BlockPos pos) { assertTrue(loaded(pos)); return pos.equals(CENTER) || pos.getY() >= CENTER.getY(); }
			@Override public BlockState blockState(BlockPos pos) {
				assertTrue(inBounds(pos) && loaded(pos) && entityTicking(pos)); f.reads++; return ripe();
			}
		};
		for (int i = 0; i < SWEEP_TICKS; i++) f.data.resourceDiscovery().tick(f.data, i, probe);
		assertTrue(f.reads > 0); assertFalse(f.records().isEmpty());
	}

	@Test void residentBecomingIneligibleOrLosingMembershipStopsTheActiveScan() {
		for (boolean remove : java.util.List.of(false, true)) {
			Fixture f = new Fixture(); f.allRipe = true;
			while (f.reads == 0) f.tick();
			int reads = f.reads;
			if (remove) f.data.removeMember(ACTOR); else f.observers.put(ACTOR,
				new SettlementResourceDiscovery.Observer(CENTER, true, false, true, true, true, false));
			f.tick(); assertEquals(reads, f.reads);
		}
	}

	@Test void changedMembershipDuringReadCannotRecordFromStaleIdentity() {
		Fixture f = new Fixture(); f.allRipe = true;
		f.onRead = () -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 1); };
		while (f.reads == 0) f.tick();
		assertEquals(1, f.reads); assertTrue(f.records().isEmpty());
	}

	@Test void allMembersEventuallyObserveWithoutAnUnboundedResidentCursorMap() {
		Fixture f = new Fixture(); UUID other = new UUID(301, 2);
		f.data.registerMember(f.camp, other, 0); f.observers.put(other, adult(CENTER.east(20)));
		f.blocks.put(CENTER.east(20), ripe());
		var probe = new SettlementResourceDiscovery.Probe() {
			@Override public SettlementObservationClaims membership() { return claims(f.data); }
			@Override public SettlementResourceDiscovery.Observer observer(UUID id) { return f.observers.get(id); }
			@Override public boolean inBounds(BlockPos pos) { return true; }
			@Override public boolean loaded(BlockPos pos) { return true; }
			@Override public boolean entityTicking(BlockPos pos) { return true; }
			@Override public BlockState blockState(BlockPos pos) { return f.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
		};
		for (int i = 0; i < SWEEP_TICKS * 2; i++) f.data.resourceDiscovery().tick(f.data, i, probe);
		assertEquals(CENTER.east(20), f.records().getFirst().position());
	}

	@Test void unrelatedMembershipChurnDoesNotRestartSweepsOrStarveTheNextResident() {
		Fixture f = new Fixture(); UUID second = new UUID(301, 2), churn = new UUID(301, 3);
		f.data.registerMember(f.camp, second, 0); f.observers.put(second, adult(CENTER.east(20)));
		f.blocks.put(CENTER.east(8), ripe()); f.blocks.put(CENTER.east(28), ripe());
		for (int i = 0; i < SWEEP_TICKS * 2 + 2; i++) {
			f.data.removeMember(churn); f.data.registerMember(f.camp, churn, i);
			f.tick();
		}
		assertEquals(java.util.Set.of(CENTER.east(8), CENTER.east(28)),
			f.records().stream().map(SettlementResourceObservations.Observation::position).collect(java.util.stream.Collectors.toSet()));
	}

	@Test void typeReplacementUpdatesOrRemovesOnlyCandidateAndNeverAuthorizesGathering() {
		for (var replacement : java.util.List.of(Blocks.OAK_LOG, Blocks.STONE, Blocks.COBBLESTONE, Blocks.POTATOES)) {
			Fixture f = new Fixture(); f.blocks.put(CENTER, ripe()); f.sweep(); assertEquals(1, f.records().size());
			f.blocks.put(CENTER, replacement.defaultBlockState());
			assertFalse(SettlementGathering.validTarget(f.data, f.camp, ACTOR, CENTER,
				() -> true, () -> true, () -> true, () -> { fail("Candidate is not authority to inspect a gathering target"); return ripe(); }));
			f.sweep();
			if (replacement == Blocks.POTATOES) assertTrue(f.records().isEmpty());
			else {
				assertEquals(1, f.records().size());
				assertEquals(SettlementResourceDiscovery.classify(replacement.defaultBlockState()), f.records().getFirst().kind());
				assertEquals(SettlementResourceObservations.Source.UNKNOWN, f.records().getFirst().source());
			}
			assertTrue(f.data.resourcesOf(f.camp).isEmpty());
			assertFalse(f.data.canHarvestBerries(f.camp, CENTER));
			assertFalse(SettlementGathering.validTarget(f.data, f.camp, ACTOR, CENTER,
				() -> true, () -> true, () -> true, () -> { fail("Updated candidate still grants no gathering authority"); return ripe(); }));
		}
	}

	@Test void suppressPersistsAcrossRescansAndResumeRequiresFreshObservation() {
		Fixture f = new Fixture(); f.blocks.put(CENTER, ripe()); f.sweep();
		String id = f.records().getFirst().id();
		assertEquals(SettlementResourceObservations.Change.CHANGED, f.data.changeResourceObservation(f.camp, id, true));
		f.blocks.put(CENTER, Blocks.OAK_LOG.defaultBlockState());
		f.sweep(); assertEquals(SettlementResourceObservations.State.SUPPRESSED, f.records().getFirst().state());
		assertEquals(id, f.records().getFirst().id());
		assertEquals(SettlementResourceObservations.Kind.SWEET_BERRY, f.records().getFirst().kind());
		assertEquals(SettlementResourceObservations.Change.CHANGED, f.data.changeResourceObservation(f.camp, id, false));
		assertTrue(f.records().isEmpty()); assertTrue(f.data.resourcesOf(f.camp).isEmpty());
		f.sweep(); assertEquals(SettlementResourceObservations.State.CANDIDATE, f.records().getFirst().state());
	}

	@Test void observationsDoNotChangePhysicalContainersBlocksOrSimulationBatches() {
		Fixture f = new Fixture(); f.blocks.put(CENTER, ripe());
		f.blocks.put(CENTER.east(2), Blocks.OAK_LOG.defaultBlockState());
		f.blocks.put(CENTER.west(2), Blocks.COBBLESTONE.defaultBlockState());
		SimpleContainer bag = new SimpleContainer(new ItemStack(Items.SWEET_BERRIES, 3));
		SimpleContainer stockpile = new SimpleContainer(new ItemStack(Items.OAK_LOG, 7));
		ItemStack bagBefore = bag.getItem(0).copy(), stockBefore = stockpile.getItem(0).copy();
		f.data.setStockpile(f.camp, CENTER.east()); f.data.toggleResource(f.camp, CENTER.north());
		var simulation = f.data.getOrCreateSimulation(f.camp); simulation.mapResident(ACTOR, false, 30);
		CompoundTag before = f.data.save(new CompoundTag(), null); before.remove("ResourceObservations");
		Map<BlockPos, BlockState> worldBefore = Map.copyOf(f.blocks);
		f.sweep();
		CompoundTag after = f.data.save(new CompoundTag(), null); after.remove("ResourceObservations");
		assertEquals(before, after); assertEquals(worldBefore, f.blocks);
		assertTrue(ItemStack.matches(bagBefore, bag.getItem(0))); assertTrue(ItemStack.matches(stockBefore, stockpile.getItem(0)));
		assertEquals(java.util.Set.of(CENTER.north()), f.data.resourcesOf(f.camp));
	}
}
