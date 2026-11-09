package com.thysummer.coopcraft.civilization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCargoApproachTest {
	private static final BlockPos STORE = new BlockPos(0, 1, 0);
	private static final BlockPos FEET = STORE.north();
	private static final Vec3 FROM = ConstructionApproach.feet(FEET);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Probe implements ConstructionApproach.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>();
		final Set<BlockPos> outside = new HashSet<>();
		final Set<BlockPos> notTicking = new HashSet<>();
		int reads;
		public boolean inBounds(BlockPos position) { return !outside.contains(position); }
		public boolean loaded(BlockPos position) { return !unknown.contains(position); }
		public boolean ticking(BlockPos position) { return !notTicking.contains(position); }
		public BlockState state(BlockPos position) {
			assertFalse(unknown.contains(position) || outside.contains(position) || notTicking.contains(position));
			reads++;
			return blocks.getOrDefault(position, position.getY() == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
	}

	@Test void candidateSetIsOnlyFourFaceNeighborsAndCannotOverflow() {
		var positions = SettlementCargoApproach.candidates(STORE, FROM);
		assertEquals(4, positions.size());
		assertEquals(FEET, positions.getFirst());
		assertEquals(4, new HashSet<>(positions).size());
		for (int boundary : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			BlockPos store = new BlockPos(boundary, 1, boundary);
			for (BlockPos position : SettlementCargoApproach.candidates(store, FROM)) {
				assertEquals(1, Math.abs((long) position.getX() - boundary) + Math.abs((long) position.getZ() - boundary));
			}
		}
	}

	@Test void wholeEnvelopeIsPreflightedBeforeAnyBlockRead() {
		for (BlockPos cell : List.of(STORE, FEET, FEET.above(), FEET.below())) {
			for (int mode = 0; mode < 3; mode++) {
				Probe probe = new Probe();
				(mode == 0 ? probe.unknown : mode == 1 ? probe.outside : probe.notTicking).add(cell);
				assertFalse(SettlementCargoApproach.safe(STORE, FEET, probe));
				assertEquals(0, probe.reads);
			}
		}
	}

	@Test void arrivalRejectsWallsUnsafeFloorAndRemoteOrFallingActors() {
		assertTrue(SettlementCargoApproach.arrived(STORE, FROM, new Probe()));
		for (BlockPos cell : List.of(FEET, FEET.above())) {
			for (var block : List.of(Blocks.STONE, Blocks.WATER, Blocks.LAVA, Blocks.OAK_FENCE)) {
				Probe probe = new Probe();
				probe.blocks.put(cell, block.defaultBlockState());
				assertFalse(SettlementCargoApproach.arrived(STORE, FROM, probe));
			}
		}
		for (var block : List.of(Blocks.AIR, Blocks.MAGMA_BLOCK, Blocks.SAND, Blocks.OAK_SLAB, Blocks.WATER)) {
			Probe probe = new Probe();
			probe.blocks.put(FEET.below(), block.defaultBlockState());
			assertFalse(SettlementCargoApproach.arrived(STORE, FROM, probe));
		}
		// Still within the old 3-block reach, but a wall may separate these cells.
		assertFalse(SettlementCargoApproach.arrived(STORE, FROM.add(0, 0, -1), new Probe()));
		assertFalse(SettlementCargoApproach.arrived(STORE, FROM.add(0, .5, 0), new Probe()));
		assertFalse(SettlementCargoApproach.arrived(STORE, new Vec3(Double.NaN, 1, 0), new Probe()));
	}

	@Test void boundedPathBatchesRespectWholeRegionAndTwentyTickGap() {
		var search = new SettlementCargoApproach.Search();
		Probe probe = new Probe();
		AtomicInteger calls = new AtomicInteger();
		assertEquals(SettlementCargoApproach.Outcome.BLOCKED,
			search.attempt(STORE, FROM, 0, probe, () -> false, p -> { fail("unknown search region"); return true; }));
		assertEquals(0, probe.reads);
		for (int now = 1; now < 20; now++) {
			assertEquals(SettlementCargoApproach.Outcome.WAITING,
				search.attempt(STORE, FROM, now, probe, () -> { fail("cooldown"); return true; }, p -> true));
		}
		for (int now : new int[] {20, 40}) {
			assertEquals(SettlementCargoApproach.Outcome.WAITING,
				search.attempt(STORE, FROM, now, probe, () -> true, p -> { calls.incrementAndGet(); return false; }));
		}
		assertEquals(4, calls.get());
		assertEquals(SettlementCargoApproach.Outcome.BLOCKED,
			search.attempt(STORE, FROM, 60, probe, () -> true, p -> { fail("finite sweep exhausted"); return false; }));
	}

	@Test void interruptionAndClockReversalRecoverWithoutInventorySnapshot() {
		var cooldown = new SettlementCargoApproach.Cooldown();
		cooldown.defer(100);
		assertFalse(cooldown.ready(199));
		assertTrue(cooldown.ready(200));
		cooldown.defer(100);
		assertTrue(cooldown.ready(99));
		cooldown.defer(Long.MAX_VALUE - 50);
		assertFalse(cooldown.ready(Long.MIN_VALUE));
		assertTrue(cooldown.ready(Long.MIN_VALUE + 49));
		var search = new SettlementCargoApproach.Search();
		assertEquals(SettlementCargoApproach.Outcome.MOVING, search.attempt(STORE, FROM, 100, new Probe(), () -> true, p -> true));
		// Reusing search after a Goal stop keeps the short path budget, but never stores an inventory.
		assertEquals(SettlementCargoApproach.Outcome.WAITING, search.attempt(STORE, FROM, 101, new Probe(), () -> true, p -> true));
		assertEquals(SettlementCargoApproach.Outcome.MOVING, search.attempt(STORE, FROM, 99, new Probe(), () -> true, p -> true));
	}

	@Test void goalWiringDropsTicketAndKeepsReturnOutOfGenericNavigation() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/VillagerWorkGoal.java"));
		assertTrue(source.contains("RETURN_TO_STOCKPILE(\"return_to_stockpile\")"));
		assertTrue(source.contains("if (action == Action.RETURN_TO_STOCKPILE) { tickCargoReturn(); return; }"));
		String stop = source.substring(source.indexOf("public void stop()"), source.indexOf("private static VillagerWorkGoal goalOf"));
		assertTrue(stop.contains("cargoTicket = null"));
		assertFalse(stop.contains("cargoCooldown ="));
		String tick = source.substring(source.indexOf("private void tickCargoReturn()"), source.indexOf("private boolean shouldEat()"));
		assertTrue(tick.indexOf("if (atCargoStore())") < tick.indexOf("Container currentStore"));
		assertTrue(tick.contains("currentStore, this::atCargoStore"));
		assertTrue(tick.contains("SettlementMembershipClaims.belongsTo"));
		assertTrue(tick.contains("this::navigationSearchRegionLoaded"));
		assertFalse(tick.contains("navigateTo(target.above())"));
		for (String blocker : List.of("cargo_store_unavailable", "cargo_path_blocked", "cargo_store_full")) assertTrue(source.contains(blocker));
	}
}
