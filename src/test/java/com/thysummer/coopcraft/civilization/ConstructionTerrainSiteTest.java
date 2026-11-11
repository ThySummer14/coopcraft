package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only synthetic terrain tests using shipped plans, not server/navigation or placement evidence. */
class ConstructionTerrainSiteTest {
	private static final BlockPos CENTER = new BlockPos(0, 64, 0);
	private static List<ConstructionPlan> houses;
	private static ConstructionPlan hearth;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		houses = List.of(resource("stone_cottage"), resource("timber_porch_cabin_v1"));
		hearth = resource("campfire_hearth");
	}

	private static ConstructionPlan resource(String name) throws Exception {
		try (var reader = new InputStreamReader(ConstructionTerrainSiteTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json"), StandardCharsets.UTF_8)) {
			return ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name),
				JsonParser.parseReader(reader).getAsJsonObject());
		}
	}

	private static final class Probe implements ConstructionSite.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> outside = new HashSet<>(), unknown = new HashSet<>(), checked = new HashSet<>();
		final List<BlockPos> reads = new ArrayList<>();
		ToIntFunction<BlockPos> surface = p -> 63;
		int checks;
		boolean allUnknown;
		public boolean inBounds(BlockPos p) {
			checks++;
			return p.getY() >= -64 && p.getY() < 320 && !outside.contains(p);
		}
		public boolean ticking(BlockPos p) {
			assertTrue(inBounds(p));
			if (allUnknown || unknown.contains(p)) return false;
			checked.add(p.immutable());
			return true;
		}
		public BlockState state(BlockPos p) {
			assertTrue(checked.contains(p));
			assertFalse(unknown.contains(p));
			assertTrue(inBounds(p));
			reads.add(p.immutable());
			return blocks.getOrDefault(p, p.getY() <= surface.applyAsInt(p)
				? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
	}

	@Test void originalTwelvePrecedeAllHigherThenAllLowerCandidatesAndAreImmutable() {
		var original = ConstructionSite.candidates(CENTER);
		assertEquals(12, original.size());
		for (ConstructionPlan plan : houses) {
			var mutable = new BlockPos.MutableBlockPos(0, 64, 0);
			var candidates = ConstructionSite.candidates(plan, mutable);
			mutable.set(100, 100, 100);
			assertEquals(36, candidates.size());
			assertEquals(36, new HashSet<>(candidates).size());
			assertEquals(original, candidates.subList(0, 12));
			for (int i = 0; i < 12; i++) {
				assertEquals(original.get(i).above(), candidates.get(i + 12));
				assertEquals(original.get(i).below(), candidates.get(i + 24));
			}
			assertThrows(UnsupportedOperationException.class, candidates::clear);
			assertTrue(candidates.stream().noneMatch(p -> p instanceof BlockPos.MutableBlockPos));
			var diagnostics = ConstructionSite.inspectCandidates(plan, CENTER, new Probe());
			assertEquals(candidates, diagnostics.stream().map(ConstructionSite.Result::origin).toList());
			assertThrows(UnsupportedOperationException.class, diagnostics::clear);
		}
		assertEquals(original, ConstructionSite.candidates(hearth, CENTER));
		assertEquals(12, ConstructionSite.inspectCandidates(hearth, CENTER, new Probe()).size());
	}

	@Test void laterOriginalSiteWinsBeforeAnyHigherSiteAndSelectionStopsEarly() {
		for (ConstructionPlan plan : houses) {
			var probe = new Probe();
			probe.surface = p -> p.getX() >= 18 && p.getX() <= 24 ? 63 : 64;
			var diagnostics = ConstructionSite.inspectCandidates(plan, CENTER, probe);
			assertTrue(diagnostics.get(12).ready(), "first raised site is available too");
			var selected = ConstructionSite.firstReady(plan, CENTER, probe).orElseThrow();
			assertEquals(new BlockPos(18, 64, 0), selected.origin());
			assertEquals(diagnostics.stream().filter(ConstructionSite.Result::ready).findFirst().orElseThrow(), selected);
			var single = new Probe();
			assertTrue(ConstructionSite.inspect(plan, ConstructionSite.candidates(CENTER).getFirst(), single).ready());
			var early = new Probe();
			BlockPos laterUnknown = new BlockPos(-18, 63, 0);
			early.unknown.add(laterUnknown);
			ConstructionSite.firstReady(plan, CENTER, early).orElseThrow();
			assertEquals(single.reads, early.reads);
			assertEquals(single.checks, early.checks);
			assertFalse(early.checked.contains(laterUnknown));
			assertEquals("not_ticking", ConstructionSite.inspectCandidates(plan, CENTER, early).get(11).reason());
		}
	}

	@Test void neighboringFlatLedgesSucceedWhileOriginalLevelFailsAndTwoBlockShiftsStayRejected() {
		for (ConstructionPlan plan : houses) for (int delta : List.of(1, -1, 2, -2)) {
			var probe = new Probe();
			probe.surface = p -> 63 + delta;
			for (BlockPos origin : ConstructionSite.candidates(CENTER)) {
				assertFalse(ConstructionSite.inspect(plan, origin, probe).ready());
			}
			var selected = ConstructionSite.firstReady(plan, CENTER, probe);
			if (Math.abs(delta) == 1) {
				assertEquals(ConstructionSite.candidates(CENTER).getFirst().offset(0, delta, 0),
					selected.orElseThrow().origin());
			} else assertTrue(selected.isEmpty());
		}
	}

	@Test void mixedHeightsAndMissingSupportCannotBeFilledOrSmoothed() {
		for (ConstructionPlan plan : houses) {
			var probe = new Probe();
			probe.surface = p -> Math.floorMod(p.getX(), 2) == 0 ? 63 : 64;
			assertTrue(ConstructionSite.firstReady(plan, CENTER, probe).isEmpty());
			for (int delta : List.of(1, -1)) {
				var ledge = new Probe();
				ledge.surface = p -> 63 + delta;
				BlockPos origin = ConstructionSite.candidates(CENTER).getFirst().offset(0, delta, 0);
				ledge.blocks.put(origin.offset(2, -1, 2), Blocks.AIR.defaultBlockState());
				assertEquals("foundation_unsupported", ConstructionSite.inspect(plan, origin, ledge).reason());
			}
		}
	}

	@Test void shiftedDoorApproachStillRejectsObstaclesFluidsAndUnsafeSupports() {
		for (ConstructionPlan plan : houses) for (int delta : List.of(1, -1)) {
			// Both shipped plans exit north at x=2; the cabin door is recessed behind its porch.
			BlockPos origin = ConstructionSite.candidates(CENTER).getFirst().offset(0, delta, 0);
			for (BlockState state : List.of(Blocks.WATER.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(),
				Blocks.AIR.defaultBlockState())) {
				var probe = new Probe();
				probe.surface = p -> 63 + delta;
				probe.blocks.put(origin.offset(2, -1, -1), state);
				assertEquals("entrance_unsupported", ConstructionSite.inspect(plan, origin, probe).reason());
			}
			for (int y : List.of(1, 2)) {
				var probe = new Probe();
				probe.surface = p -> 63 + delta;
				probe.blocks.put(origin.offset(2, y, -1), Blocks.OAK_PLANKS.defaultBlockState());
				assertEquals("entrance_obstructed", ConstructionSite.inspect(plan, origin, probe).reason());
			}
		}
	}

	@Test void shiftedVolumesNeverReuseOrOverwritePlayerFloorsInteriorOrRoofVoids() {
		for (ConstructionPlan plan : houses) for (int delta : List.of(1, -1)) {
			var positions = new ArrayList<BlockPos>();
			positions.add(BlockPos.ZERO); // An actual planned floor target is still required to be air.
			BlockPos interior = new BlockPos(2, 2, 3);
			assertTrue(plan.instructions().stream().noneMatch(part -> part.offset().equals(interior)));
			positions.add(interior);
			if (plan == houses.get(1)) {
				BlockPos roofVoid = new BlockPos(0, 4, 0);
				assertTrue(plan.instructions().stream().noneMatch(part -> part.offset().equals(roofVoid)));
				assertEquals(4, plan.instructions().stream().mapToInt(part -> part.offset().getY()).max().orElseThrow());
				positions.add(roofVoid); // Cabin roof envelope includes unplanned air beside the upper ridge.
			}
			BlockPos origin = ConstructionSite.candidates(CENTER).getFirst().offset(0, delta, 0);
			for (BlockPos offset : positions) {
				var probe = new Probe();
				probe.surface = p -> 63 + delta;
				BlockPos occupied = origin.offset(offset);
				probe.blocks.put(occupied, Blocks.OAK_PLANKS.defaultBlockState());
				var before = Map.copyOf(probe.blocks);
				var result = ConstructionSite.inspect(plan, origin, probe);
				assertEquals("volume_occupied", result.reason());
				assertEquals(occupied, result.position());
				assertEquals(before, probe.blocks);
			}
		}
	}

	@Test void eachCandidatePreflightsCompleteEnvelopeBeforeAnyRead() {
		for (ConstructionPlan plan : houses) for (BlockPos origin : ConstructionSite.candidates(plan, CENTER)) {
			int edge = plan == houses.getFirst() ? 4 : 6;
			for (BlockPos offset : List.of(new BlockPos(edge, 3, edge), new BlockPos(edge, -1, edge),
				new BlockPos(2, -1, -1), new BlockPos(2, 2, -1))) for (boolean bounds : List.of(false, true)) {
				var probe = new Probe();
				(bounds ? probe.outside : probe.unknown).add(origin.offset(offset));
				assertEquals(bounds ? "out_of_bounds" : "not_ticking", ConstructionSite.inspect(plan, origin, probe).reason());
				assertTrue(probe.reads.isEmpty());
			}
		}
		var probe = new Probe();
		probe.allUnknown = true;
		assertTrue(ConstructionSite.firstReady(houses.getFirst(), CENTER, probe).isEmpty());
		assertTrue(probe.reads.isEmpty());
	}

	@Test void buildBoundsAndUnrepresentableOriginsNeverWrapOrReadWorld() {
		for (ConstructionPlan plan : houses) {
			for (int y : List.of(-66, 320)) {
				var probe = new Probe();
				assertTrue(ConstructionSite.firstReady(plan, new BlockPos(0, y, 0), probe).isEmpty());
				assertTrue(probe.reads.isEmpty());
			}
			for (int extreme : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)) {
				var center = new BlockPos(extreme, extreme, extreme);
				var candidates = ConstructionSite.candidates(plan, center);
				assertEquals(12, candidates.size()); // Two representable directions, three radii, two heights.
				for (BlockPos p : candidates) {
					assertTrue(Math.abs((long) p.getX() - extreme) <= 18);
					assertTrue(Math.abs((long) p.getZ() - extreme) <= 18);
					assertTrue(Math.abs((long) p.getY() - extreme) <= 1);
				}
				var probe = new Probe();
				assertTrue(ConstructionSite.firstReady(plan, center, probe).isEmpty());
				assertTrue(probe.reads.isEmpty());
			}
		}
	}

	@Test void shiftedJobsRoundTripWithoutChangingProgressFingerprintOrRemainingMaterials() {
		for (ConstructionPlan plan : houses) for (int delta : List.of(1, -1)) {
			var probe = new Probe();
			probe.surface = p -> 63 + delta;
			BlockPos origin = ConstructionSite.firstReady(plan, CENTER, probe).orElseThrow().origin();
			var started = ConstructionJob.start(plan, origin);
			assertEquals(0, started.nextIndex());
			var progressed = started;
			for (int i = 0; i < 7; i++) progressed = progressed.advance();
			var remaining = Map.copyOf(ConstructionPlanner.remainingMaterials(plan, progressed.nextIndex()));
			var saved = progressed.save();
			var restored = ConstructionJob.load(saved);
			assertEquals(origin, restored.origin());
			assertEquals(7, restored.nextIndex());
			assertEquals(plan.fingerprint(), restored.planFingerprint());
			assertEquals("ready", restored.compatibilityBlocker(plan));
			assertEquals(progressed, restored);
			assertEquals(saved, restored.save());
			assertEquals(remaining, ConstructionPlanner.remainingMaterials(plan, restored.nextIndex()));
		}
	}

	@Test void repeatedInspectionDoesNotCacheWorldOrChangePlansMaterialsOrSavedJobs() {
		for (ConstructionPlan plan : houses) {
			var fingerprint = plan.fingerprint();
			var materials = Map.copyOf(plan.materials());
			var instructions = List.copyOf(plan.instructions());
			var probe = new Probe();
			var blocks = Map.copyOf(probe.blocks);
			var initial = ConstructionSite.firstReady(plan, CENTER, probe).orElseThrow();
			probe.surface = p -> 64;
			assertEquals(initial.origin().above(), ConstructionSite.firstReady(plan, CENTER, probe).orElseThrow().origin());
			assertEquals(blocks, probe.blocks);
			assertEquals(fingerprint, plan.fingerprint());
			assertEquals(materials, plan.materials());
			assertEquals(instructions, plan.instructions());
			for (boolean legacy : List.of(false, true)) {
				var data = new SettlementSavedData();
				var settlement = data.getOrCreate(CENTER, "Terrain site", 0);
				var job = legacy ? new ConstructionJob(plan.id(), initial.origin(), 7)
					: new ConstructionJob(plan.id(), initial.origin(), 7, fingerprint);
				data.setConstructionJob(settlement, job);
				var before = data.save(new CompoundTag(), null);
				assertTrue(ConstructionSystem.inspectSites(null, data, settlement, plan).isEmpty());
				assertTrue(ConstructionSystem.inspectNewSites(null, data, settlement).isEmpty());
				assertSame(job, data.getConstructionJob(settlement).orElseThrow());
				assertEquals(before, data.save(new CompoundTag(), null));
			}
		}
	}
}
