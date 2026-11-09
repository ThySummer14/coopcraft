package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionApproachTest {
	private static final BlockPos TARGET = new BlockPos(3, 3, 3);
	private static final Vec3 FROM = new Vec3(3.5, 0, -8.5);

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final class Probe implements ConstructionApproach.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> outside = new HashSet<>(), unloaded = new HashSet<>(), notTicking = new HashSet<>();
		final Set<BlockPos> checked = new HashSet<>();
		int checks, reads;
		boolean ground = true;
		public boolean inBounds(BlockPos position) { checks++; return !outside.contains(position); }
		public boolean loaded(BlockPos position) {
			assertFalse(outside.contains(position));
			return !unloaded.contains(position);
		}
		public boolean ticking(BlockPos position) {
			assertFalse(outside.contains(position));
			assertFalse(unloaded.contains(position));
			if (notTicking.contains(position)) return false;
			checked.add(position);
			return true;
		}
		public BlockState state(BlockPos position) {
			assertTrue(checked.contains(position));
			assertFalse(outside.contains(position) || unloaded.contains(position) || notTicking.contains(position));
			reads++;
			return blocks.getOrDefault(position, ground && position.getY() == -1
				? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
	}

	@Test void boundedCandidatesUseIntegerFeetHeightAndActualReach() {
		var candidates = ConstructionApproach.candidates(TARGET, FROM);
		assertFalse(candidates.isEmpty());
		assertTrue(candidates.size() <= 11 * 7 * 11);
		assertEquals(candidates.size(), new HashSet<>(candidates).size());
		for (BlockPos candidate : candidates) {
			assertTrue(Math.abs(candidate.getX() - TARGET.getX()) <= 5);
			assertTrue(Math.abs(candidate.getZ() - TARGET.getZ()) <= 5);
			assertTrue(candidate.getY() - TARGET.getY() >= -5 && candidate.getY() - TARGET.getY() <= 1);
			assertTrue(ConstructionApproach.withinReach(TARGET, candidate));
		}
		assertTrue(candidates.contains(new BlockPos(3, 0, -1)), "old adjacent-column search missed this roof approach");
		assertEquals(28.25, ConstructionApproach.feet(new BlockPos(3, 0, -1)).distanceToSqr(TARGET.getCenter()));
		assertFalse(ConstructionApproach.withinReach(TARGET, TARGET.offset(3, -5, 0)), "block centers would incorrectly allow this");
	}

	@Test void deterministicDistanceOrderUsesFractionalEntityPosition() {
		Vec3 from = new Vec3(2.99, 0.1, -2.99);
		var candidates = ConstructionApproach.candidates(TARGET, from);
		assertEquals(candidates, ConstructionApproach.candidates(TARGET, from));
		double previous = -1;
		for (BlockPos candidate : candidates) {
			double distance = ConstructionApproach.feet(candidate).distanceToSqr(from);
			assertTrue(distance >= previous);
			previous = distance;
		}
	}

	@Test void everyUnknownEnvelopeCellRejectsBeforeAnyStateRead() {
		BlockPos feet = new BlockPos(3, 0, -1);
		for (BlockPos unknown : List.of(feet, feet.above(), feet.below())) {
			for (int kind = 0; kind < 3; kind++) {
				Probe probe = new Probe();
				(kind == 0 ? probe.outside : kind == 1 ? probe.unloaded : probe.notTicking).add(unknown);
				assertFalse(ConstructionApproach.safe(TARGET, feet, probe));
				assertEquals(0, probe.reads, "all three cells must pass metadata checks first");
			}
		}
	}

	@Test void dangerousOrPartialSupportCannotBecomeAStandingPosition() {
		BlockPos feet = new BlockPos(3, 0, -1);
		for (var block : List.of(Blocks.AIR, Blocks.WATER, Blocks.LAVA, Blocks.SAND, Blocks.MAGMA_BLOCK,
			Blocks.CACTUS, Blocks.POWDER_SNOW, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK,
			Blocks.OAK_LEAVES, Blocks.CHEST, Blocks.OAK_SLAB, Blocks.ICE, Blocks.PACKED_ICE,
			Blocks.BLUE_ICE, Blocks.FROSTED_ICE)) {
			Probe probe = new Probe();
			probe.blocks.put(feet.below(), block.defaultBlockState());
			assertFalse(ConstructionApproach.safe(TARGET, feet, probe), block.toString());
		}
		assertTrue(ConstructionApproach.safe(TARGET, feet, new Probe()));
	}

	@Test void feetAndHeadMustBeStrictAir() {
		BlockPos feet = new BlockPos(3, 0, -1);
		for (BlockPos occupied : List.of(feet, feet.above())) {
			for (var block : List.of(Blocks.WATER, Blocks.SHORT_GRASS, Blocks.TORCH, Blocks.STONE)) {
				Probe probe = new Probe();
				probe.blocks.put(occupied, block.defaultBlockState());
				assertFalse(ConstructionApproach.safe(TARGET, feet, probe));
			}
		}
	}

	@Test void futureTargetCannotOccupyFeetOrHeadEvenWhileAir() {
		for (BlockPos feet : List.of(TARGET, TARGET.below())) {
			Probe probe = new Probe();
			assertFalse(ConstructionApproach.safe(TARGET, feet, probe));
			assertEquals(0, probe.checks);
			assertEquals(0, probe.reads);
			assertFalse(ConstructionApproach.candidates(TARGET, FROM).contains(feet));
		}
	}

	@Test void actualCabinBuildOrderKeepsAnExteriorGroundApproachForEveryRoofInstruction() throws Exception {
		ConstructionPlan plan;
		try (var reader = new InputStreamReader(getClass().getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/timber_porch_cabin_v1.json"), StandardCharsets.UTF_8)) {
			plan = ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:timber_porch_cabin_v1"),
				JsonParser.parseReader(reader).getAsJsonObject());
		}
		Probe probe = new Probe();
		int roofParts = 0;
		for (var part : plan.instructions()) {
			BlockPos target = part.offset();
			if (target.getY() >= 3) {
				roofParts++;
				assertTrue(ConstructionApproach.candidates(target, FROM).stream()
					.filter(pos -> pos.getY() == 0 && (pos.getX() < 0 || pos.getX() > 6 || pos.getZ() < 0 || pos.getZ() > 6))
					.anyMatch(pos -> ConstructionApproach.safe(target, pos, probe)), target.toString());
			}
			probe.blocks.put(target, part.state());
		}
		assertTrue(roofParts > 0);
	}

	@Test void failedPathsHaveEightAttemptBudgetAndTwentyTickNoReadCooldown() {
		var search = new ConstructionApproach.Search();
		Probe probe = new Probe();
		List<BlockPos> attempts = new ArrayList<>();
		assertFalse(search.attempt(TARGET, FROM, 100, probe, () -> true, pos -> { attempts.add(pos); return false; }));
		assertEquals(8, attempts.size());
		int reads = probe.reads, checks = probe.checks;
		for (long now = 100; now < 120; now++) {
			assertFalse(search.attempt(TARGET, FROM, now, probe, () -> { fail("no region scan during cooldown"); return true; }, pos -> true));
		}
		assertEquals(reads, probe.reads);
		assertEquals(checks, probe.checks);
		search.attempt(TARGET, FROM, 120, probe, () -> true, pos -> { attempts.add(pos); return false; });
		assertEquals(16, attempts.size());
		assertEquals(16, new HashSet<>(attempts).size(), "second round must progress past first eight");
	}

	@Test void successStopsBatchAndNextRoundAdvancesRatherThanRestarting() {
		var search = new ConstructionApproach.Search();
		Probe probe = new Probe();
		List<BlockPos> attempts = new ArrayList<>();
		assertTrue(search.attempt(TARGET, FROM, 0, probe, () -> true, pos -> { attempts.add(pos); return true; }));
		assertTrue(search.attempt(TARGET, FROM, 20, probe, () -> true, pos -> { attempts.add(pos); return true; }));
		assertEquals(2, attempts.size());
		assertNotEquals(attempts.get(0), attempts.get(1));
	}

	@Test void unavailableRegionAndEmptySafeSetStillConsumeCooldown() {
		for (boolean region : List.of(true, false)) {
			var search = new ConstructionApproach.Search();
			Probe probe = new Probe();
			probe.ground = false;
			assertFalse(search.attempt(TARGET, FROM, 10, probe, () -> region, pos -> { fail("no safe candidates"); return false; }));
			int reads = probe.reads, checks = probe.checks;
			assertFalse(search.attempt(TARGET, FROM, 11, probe, () -> { fail("cooldown"); return true; }, pos -> true));
			assertEquals(reads, probe.reads);
			assertEquals(checks, probe.checks);
			assertTrue(checks <= 3 * ConstructionApproach.candidates(TARGET, FROM).size(), "one bounded scan per round");
			if (!region) assertEquals(0, checks);
		}
	}

	@Test void blockedGroundCanRecoverWithoutChangingTarget() {
		var search = new ConstructionApproach.Search();
		Probe probe = new Probe();
		probe.ground = false;
		assertFalse(search.attempt(TARGET, FROM, 0, probe, () -> true, pos -> true));
		probe.ground = true;
		assertTrue(search.attempt(TARGET, FROM, 20, probe, () -> true, pos -> true));
	}

	@Test void changedTargetCanSearchImmediatelyButSameTargetRetainsCooldown() {
		var search = new ConstructionApproach.Search();
		Probe probe = new Probe();
		assertTrue(search.attempt(TARGET, FROM, 0, probe, () -> true, pos -> true));
		assertFalse(search.attempt(TARGET.immutable(), FROM.add(1, 0, 0), 1, probe, () -> true, pos -> true));
		assertTrue(search.attempt(TARGET.east(), FROM, 1, probe, () -> true, pos -> true));
	}

	@Test void clockWrapAndClockReversalDoNotLoseCooldownOrPermanentlyStall() {
		var search = new ConstructionApproach.Search();
		Probe probe = new Probe();
		assertTrue(search.attempt(TARGET, FROM, Long.MAX_VALUE - 5, probe, () -> true, pos -> true));
		assertFalse(search.attempt(TARGET, FROM, Long.MIN_VALUE + 4, probe, () -> true, pos -> true));
		assertTrue(search.attempt(TARGET, FROM, Long.MIN_VALUE + 14, probe, () -> true, pos -> true));
		assertTrue(search.attempt(TARGET, FROM, Long.MIN_VALUE + 13, probe, () -> true, pos -> true));
	}

	@Test void negativeCursorCannotIndexOutsideFiniteCandidates() throws Exception {
		var search = new ConstructionApproach.Search();
		Probe probe = new Probe();
		search.attempt(TARGET, FROM, 0, probe, () -> true, pos -> false);
		var cursor = ConstructionApproach.Search.class.getDeclaredField("cursor");
		cursor.setAccessible(true);
		long now = 20;
		for (int value : new int[] {Integer.MIN_VALUE, -1, Integer.MAX_VALUE}) {
			cursor.setInt(search, value);
			AtomicInteger attempts = new AtomicInteger();
			search.attempt(TARGET, FROM, now, probe, () -> true,
				pos -> { attempts.incrementAndGet(); return false; });
			assertEquals(8, attempts.get());
			now += 20;
		}
	}


	@Test void extremeCoordinatesNeverWrapAndEnvelopeOverflowReadsNothing() {
		for (int edge : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			BlockPos target = new BlockPos(edge, edge, edge);
			for (BlockPos candidate : ConstructionApproach.candidates(target, target.getCenter())) {
				assertTrue(Math.abs((long) candidate.getX() - edge) <= 5);
				assertTrue(Math.abs((long) candidate.getY() - edge) <= 5);
				assertTrue(Math.abs((long) candidate.getZ() - edge) <= 5);
			}
			Probe probe = new Probe();
			BlockPos feet = new BlockPos(0, edge, 0);
			assertFalse(ConstructionApproach.safe(new BlockPos(1, edge, 0), feet, probe));
			assertEquals(0, probe.reads);
			assertEquals(0, probe.checks);
		}
	}
}
