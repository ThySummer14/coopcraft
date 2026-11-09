package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionSiteTest {
	private static final BlockPos ORIGIN = new BlockPos(6, 64, 0);
	private static ConstructionPlan cottage;
	private static ConstructionPlan hearth;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		cottage = resource("stone_cottage");
		hearth = resource("campfire_hearth");
	}

	private static ConstructionPlan resource(String name) throws Exception {
		try (var reader = new InputStreamReader(ConstructionSiteTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json"), StandardCharsets.UTF_8)) {
			return ConstructionPlan.fromJson(net.minecraft.resources.ResourceLocation.parse("coopcraft:" + name),
				JsonParser.parseReader(reader).getAsJsonObject());
		}
	}

	private static final class Probe implements ConstructionSite.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>();
		final Set<BlockPos> outside = new HashSet<>();
		final Set<BlockPos> checked = new HashSet<>();
		int reads;
		boolean ground = true;
		public boolean inBounds(BlockPos position) {
			return position.getY() >= -64 && position.getY() < 320 && !outside.contains(position);
		}
		public boolean ticking(BlockPos position) {
			assertTrue(inBounds(position), "must not query ticking beyond bounds");
			if (unknown.contains(position)) return false;
			checked.add(position.immutable());
			return true;
		}
		public BlockState state(BlockPos position) {
			assertTrue(checked.contains(position), "must check each position before state access");
			assertFalse(unknown.contains(position));
			assertTrue(inBounds(position));
			reads++;
			return blocks.getOrDefault(position, ground && position.getY() == 63
				? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
		void put(BlockPos offset, BlockState state) { blocks.put(ORIGIN.offset(offset), state); }
	}

	private static ConstructionSite.Result inspect(Probe probe) {
		return ConstructionSite.inspect(cottage, ORIGIN, probe);
	}

	@Test void shippedCottageFitsFlatGroundWithOneBlockDoorStep() {
		var result = inspect(new Probe());
		assertTrue(result.ready());
		assertEquals(cottage.id(), result.blueprintId());
		assertEquals(ORIGIN, result.origin());
		assertNull(result.position());
	}

	@Test void everyFoundationCellNeedsSupportEvenUnderUnfilledInterior() {
		for (BlockState state : List.of(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
			Blocks.LAVA.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(),
			Blocks.CHEST.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.ICE.defaultBlockState())) {
			var probe = new Probe();
			probe.put(new BlockPos(2, -1, 2), state);
			var result = inspect(probe);
			assertEquals("foundation_unsupported", result.reason(), state.toString());
			assertEquals(ORIGIN.offset(2, -1, 2), result.position());
		}
		var floating = new Probe();
		floating.ground = false;
		assertEquals("foundation_unsupported", inspect(floating).reason());
	}

	@Test void fullVolumeIncludesEmptyInteriorAndNeverReusesPlayerFloor() {
		for (BlockPos offset : List.of(new BlockPos(2, 1, 2), new BlockPos(2, 2, 2), BlockPos.ZERO,
			new BlockPos(4, 3, 4))) {
			var probe = new Probe();
			probe.put(offset, Blocks.COBBLESTONE.defaultBlockState());
			var before = Map.copyOf(probe.blocks);
			var result = inspect(probe);
			assertEquals("volume_occupied", result.reason());
			assertEquals(ORIGIN.offset(offset), result.position());
			assertEquals(before, probe.blocks);
		}
	}

	@Test void safePlayerBuiltSupportBelowFloorIsAllowedWithoutReplacement() {
		var probe = new Probe();
		probe.put(new BlockPos(2, -1, 2), Blocks.OAK_PLANKS.defaultBlockState());
		var before = Map.copyOf(probe.blocks);
		assertTrue(inspect(probe).ready());
		assertEquals(before, probe.blocks);
	}

	@Test void bothDoorStandingHeightsAreSupportedButDropsAndHazardsAreRejected() {
		var sameHeight = new Probe();
		sameHeight.put(new BlockPos(2, 0, -1), Blocks.STONE.defaultBlockState());
		assertTrue(inspect(sameHeight).ready());
		for (BlockState state : List.of(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
			Blocks.MAGMA_BLOCK.defaultBlockState())) {
			var probe = new Probe();
			probe.put(new BlockPos(2, -1, -1), state);
			assertEquals("entrance_unsupported", inspect(probe).reason());
		}
		var floodedStep = new Probe();
		floodedStep.put(new BlockPos(2, 0, -1), Blocks.WATER.defaultBlockState());
		assertEquals("entrance_unsupported", inspect(floodedStep).reason());
	}

	@Test void doorFeetAndHeadMustBeAirEvenWhenHigherStepExists() {
		for (int y : List.of(1, 2)) {
			var probe = new Probe();
			probe.put(new BlockPos(2, 0, -1), Blocks.STONE.defaultBlockState());
			probe.put(new BlockPos(2, y, -1), Blocks.OAK_PLANKS.defaultBlockState());
			assertEquals("entrance_obstructed", inspect(probe).reason());
		}
	}

	@Test void everyPerimeterDoorIsCheckedAndEitherFacingAlongItsAxisIsRecognized() {
		var parts = new ArrayList<>(cottage.instructions().stream()
			.filter(p -> !(p.offset().getX() == 4 && p.offset().getZ() == 2
				&& (p.offset().getY() == 1 || p.offset().getY() == 2))).toList());
		for (int y : List.of(1, 2)) {
			var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST)
				.setValue(DoorBlock.HALF, y == 1 ? net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
					: net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
			parts.add(new ConstructionInstruction(new BlockPos(4, y, 2), state, Blocks.OAK_DOOR.asItem(), y == 1));
		}
		var twoDoors = changed(parts);
		var probe = new Probe();
		assertTrue(ConstructionSite.inspect(twoDoors, ORIGIN, probe).ready());
		probe.put(new BlockPos(5, 2, 2), Blocks.STONE.defaultBlockState());
		var blocked = ConstructionSite.inspect(twoDoors, ORIGIN, probe);
		assertEquals("entrance_obstructed", blocked.reason());
		assertEquals(ORIGIN.offset(5, 2, 2), blocked.position());
	}

	@Test void allFourDoorSidesUseTheirOwnExteriorStandingColumn() {
		ConstructionPlan rotated = cottage;
		for (BlockPos outside : List.of(new BlockPos(2, 1, -1), new BlockPos(5, 1, 2),
			new BlockPos(2, 1, 5), new BlockPos(-1, 1, 2))) {
			var probe = new Probe();
			assertTrue(ConstructionSite.inspect(rotated, ORIGIN, probe).ready());
			probe.put(outside, Blocks.STONE.defaultBlockState());
			var blocked = ConstructionSite.inspect(rotated, ORIGIN, probe);
			assertEquals("entrance_obstructed", blocked.reason());
			assertEquals(ORIGIN.offset(outside), blocked.position());
			rotated = changed(rotated.instructions().stream().map(p -> new ConstructionInstruction(
				new BlockPos(4 - p.offset().getZ(), p.offset().getY(), p.offset().getX()),
				p.state().rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90), p.item(), p.consume())).toList());
		}
	}

	@Test void missingPlanAndExcessiveInstructionCountsFailWithoutAProbe() {
		assertEquals("plan_unavailable", ConstructionSite.inspect(null, ORIGIN, null).reason());
		assertEquals("unsupported_layout", ConstructionSite.inspect(changed(List.of()), ORIGIN, null).reason());
		assertEquals("unsupported_layout", ConstructionSite.inspect(changed(java.util.Collections.nCopies(4097,
			cottage.instructions().get(0))), ORIGIN, null).reason());
	}

	@Test void entireReadEnvelopeIsPreflightedBeforeAnyBlockRead() {
		for (BlockPos offset : List.of(new BlockPos(4, -1, 4), new BlockPos(4, 3, 4),
			new BlockPos(2, 2, -1), new BlockPos(2, -1, -1))) {
			for (boolean outOfBounds : List.of(false, true)) {
				var probe = new Probe();
				(outOfBounds ? probe.outside : probe.unknown).add(ORIGIN.offset(offset));
				var result = inspect(probe);
				assertEquals(outOfBounds ? "out_of_bounds" : "not_ticking", result.reason());
				assertEquals(ORIGIN.offset(offset), result.position());
				assertEquals(0, probe.reads);
			}
		}
	}

	@Test void buildHeightAndOverflowStopBeforeBlockAccess() {
		for (BlockPos origin : List.of(new BlockPos(0, -64, 0), new BlockPos(0, 318, 0),
			new BlockPos(Integer.MAX_VALUE, 64, 0))) {
			var probe = new Probe();
			assertEquals("out_of_bounds", ConstructionSite.inspect(cottage, origin, probe).reason());
			assertEquals(0, probe.reads);
		}
	}

	@Test void ambiguousLayoutsFailWithoutWorldAccess() {
		List<ConstructionPlan> plans = new ArrayList<>();
		plans.add(changed(cottage.instructions().stream().filter(p -> !p.offset().equals(BlockPos.ZERO)).toList()));
		plans.add(changed(cottage.instructions().stream().filter(p -> !(p.state().getBlock() instanceof DoorBlock)).toList()));
		plans.add(changed(cottage.instructions().stream().map(p -> p.state().getBlock() instanceof DoorBlock
			? new ConstructionInstruction(p.offset(), p.state().setValue(DoorBlock.FACING, Direction.EAST), p.item(), p.consume()) : p).toList()));
		plans.add(changed(cottage.instructions().stream().map(p -> p.state().getBlock() instanceof DoorBlock
			? new ConstructionInstruction(new BlockPos(0, p.offset().getY(), 0), p.state(), p.item(), p.consume()) : p)
			.toList()));
		plans.add(changed(cottage.instructions().stream().filter(p -> !(p.state().getBlock() instanceof DoorBlock
			&& p.state().getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)).toList()));
		var duplicate = new ArrayList<>(cottage.instructions());
		duplicate.add(cottage.instructions().get(0));
		plans.add(changed(duplicate));
		var elevated = new ArrayList<>(cottage.instructions().stream().filter(p -> !(p.state().getBlock() instanceof DoorBlock)
			&& !p.offset().equals(new BlockPos(2, 3, 0))).toList());
		cottage.instructions().stream().filter(p -> p.state().getBlock() instanceof DoorBlock)
			.forEach(p -> elevated.add(new ConstructionInstruction(p.offset().above(), p.state(), p.item(), p.consume())));
		plans.add(changed(elevated));
		plans.add(changed(List.of(cottage.instructions().stream()
			.filter(p -> p.state().getBlock() instanceof net.minecraft.world.level.block.BedBlock).findFirst().orElseThrow())));
		for (ConstructionPlan plan : plans) {
			var probe = new Probe();
			assertEquals("unsupported_layout", ConstructionSite.inspect(plan, ORIGIN, probe).reason());
			assertEquals(0, probe.reads);
		}
	}

	@Test void oversizedSyntheticLayoutIsBoundedWithoutWorldReads() {
		var parts = new ArrayList<>(cottage.instructions());
		parts.add(new ConstructionInstruction(new BlockPos(1000000, 0, 0), Blocks.STONE.defaultBlockState(),
			Blocks.STONE.asItem(), true));
		var probe = new Probe();
		assertEquals("unsupported_layout", ConstructionSite.inspect(changed(parts), ORIGIN, probe).reason());
		assertEquals(0, probe.reads);
	}

	@Test void fullCubeRuleRejectsPartialCollisionAndFluidOrHazardSupports() {
		assertTrue(ConstructionSite.safeSupport(Blocks.COBBLESTONE.defaultBlockState()));
		for (BlockState state : List.of(Blocks.OAK_SLAB.defaultBlockState(), Blocks.CAMPFIRE.defaultBlockState(),
			Blocks.SOUL_SAND.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(), Blocks.CACTUS.defaultBlockState(),
			Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(), Blocks.FROSTED_ICE.defaultBlockState(),
			Blocks.SWEET_BERRY_BUSH.defaultBlockState(), Blocks.WITHER_ROSE.defaultBlockState())) {
			assertFalse(ConstructionSite.safeSupport(state), state.toString());
		}
	}

	@Test void volumeLimitAlsoAppliesWhenEveryAxisIsWithinSixtyFour() {
		var parts = new ArrayList<>(cottage.instructions());
		parts.add(new ConstructionInstruction(new BlockPos(63, 4, 63), Blocks.STONE.defaultBlockState(),
			Blocks.STONE.asItem(), true));
		assertEquals("unsupported_layout", ConstructionSite.inspect(changed(parts), ORIGIN, null).reason());
	}

	@Test void knownHouseWithRemovedBedsCannotFallBackToNonresidentialChecks() {
		var bedless = changed(cottage.instructions().stream()
			.filter(p -> !(p.state().getBlock() instanceof net.minecraft.world.level.block.BedBlock)).toList());
		assertTrue(ConstructionSite.isHousing(bedless));
		assertEquals("unsupported_layout", ConstructionSite.inspect(bedless, ORIGIN, null).reason());
		assertFalse(ConstructionSite.isHousing(hearth));
	}

	@Test void hearthStillOnlyChecksItsTargetsEvenWithoutFoundationOrDoor() {
		var probe = new Probe();
		probe.ground = false;
		probe.unknown.add(ORIGIN.below());
		probe.blocks.put(ORIGIN.above(), Blocks.STONE.defaultBlockState());
		assertTrue(ConstructionSite.inspect(hearth, ORIGIN, probe).ready());
		assertEquals(9, probe.reads);
	}

	@Test void repeatedCandidateInspectionIsReadOnlyOrderedAndImmutable() {
		var probe = new Probe();
		var before = Map.copyOf(probe.blocks);
		var candidates = ConstructionSite.inspectCandidates(cottage, new BlockPos(0, 64, 0), probe);
		assertEquals(36, candidates.size());
		assertEquals(List.of(new BlockPos(0, 64, -6), new BlockPos(6, 64, 0), new BlockPos(0, 64, 6),
			new BlockPos(-6, 64, 0)), candidates.subList(0, 4).stream().map(ConstructionSite.Result::origin).toList());
		assertEquals(candidates, ConstructionSite.inspectCandidates(cottage, new BlockPos(0, 64, 0), probe));
		assertThrows(UnsupportedOperationException.class, () -> candidates.clear());
		assertEquals(before, probe.blocks);
		var mutable = new BlockPos.MutableBlockPos(6, 64, 0);
		var result = new ConstructionSite.Result(cottage.id(), mutable, "test", mutable);
		mutable.set(99, 99, 99);
		assertEquals(ORIGIN, result.origin());
		assertEquals(ORIGIN, result.position());
	}

	@Test void existingJobDiagnosticsNeverInspectOrReplaceSavedJob() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Site test", 0);
		var job = new ConstructionJob(cottage.id(), ORIGIN, 7, cottage.fingerprint());
		data.setConstructionJob(settlement, job);
		var before = data.save(new CompoundTag(), null);
		for (int i = 0; i < 3; i++) {
			assertTrue(ConstructionSystem.inspectSites(null, data, settlement, cottage).isEmpty());
			assertTrue(ConstructionSystem.inspectNewHousingSites(null, data, settlement).isEmpty());
			assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
			assertEquals(before, data.save(new CompoundTag(), null));
		}
	}

	@Test void existingUnverifiedJobStillPausesWithoutNewSiteProbesOrJobReplacement() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Legacy site", 0);
		var legacy = new ConstructionJob(cottage.id(), ORIGIN, 7);
		data.setConstructionJob(settlement, legacy);
		var before = data.save(new CompoundTag(), null);
		assertTrue(ConstructionSystem.nextBuildTarget(null, data, settlement).isEmpty());
		assertEquals(legacy, data.getConstructionJob(settlement).orElseThrow());
		assertEquals(before, data.save(new CompoundTag(), null));
	}

	private static ConstructionPlan changed(List<ConstructionInstruction> parts) {
		return new ConstructionPlan(cottage.id(), parts);
	}
}
