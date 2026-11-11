package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Resource/compiler and bounded static geometry evidence, not a World or navigation simulation. */
class TimberSidePorchCabinPlanTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:timber_side_porch_cabin_v1");
	private static final BlockPos ORIGIN = new BlockPos(10, 64, 20);
	private static ConstructionPlan cabin;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		cabin = ConstructionPlan.fromJson(ID, resource());
	}

	private static JsonObject resource() throws Exception {
		return resource("timber_side_porch_cabin_v1");
	}

	private static JsonObject resource(String name) throws Exception {
		try (var input = TimberSidePorchCabinPlanTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input);
			try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
	}

	private static Map<BlockPos, BlockState> layout() {
		Map<BlockPos, BlockState> result = new HashMap<>();
		for (var part : cabin.instructions()) assertNull(result.put(part.offset(), part.state()));
		return result;
	}

	@Test void resourceCompilesWithExactDimensionsUniqueCellsAndMaterialCosts() throws Exception {
		var json = resource();
		assertEquals(JsonParser.parseString("[7,5,7]"), json.get("size"));
		assertFalse(json.has("layers"), "Explicit placements must not override and reorder layer supports");
		assertEquals(154, json.getAsJsonArray("special").size());
		assertEquals(154, cabin.instructions().size());
		assertEquals(154, layout().size());
		assertEquals(Map.of(Items.OAK_PLANKS, 122, Items.OAK_LOG, 24, Items.OAK_DOOR, 1,
			Items.WHITE_BED, 2, Items.TORCH, 2), cabin.materials());
		assertEquals(151, cabin.instructions().stream().filter(p -> p.consume()).count());
		assertEquals(3, cabin.instructions().stream().filter(p -> !p.consume()).count());
		for (var p : layout().keySet()) {
			assertTrue(p.getX() >= 0 && p.getX() < 7 && p.getY() >= 0 && p.getY() < 5
				&& p.getZ() >= 0 && p.getZ() < 7);
		}
	}

	@Test void freshResourceCompilationRetainsInstructionOrderAndFingerprint() throws Exception {
		for (int reload = 0; reload < 3; reload++) {
			var recompiled = ConstructionPlan.fromJson(ID, resource());
			assertEquals(cabin.instructions(), recompiled.instructions());
			assertEquals(cabin.fingerprint(), recompiled.fingerprint());
		}
	}

	@Test void fullFloorAndSouthHighRoofNorthLowRoofHaveExactFootprints() {
		var blocks = layout();
		for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
			assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), blocks.get(new BlockPos(x, 0, z)));
			assertEquals(new BlockPos(x, 0, z), cabin.instructions().get((6 - z) * 7 + 6 - x).offset());
			if (x <= 4 && z >= 4) {
				assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), blocks.get(new BlockPos(x, 4, z)));
				var rim = blocks.get(new BlockPos(x, 3, z));
				if (x == 0 || x == 4 || z == 4 || z == 6) assertTrue(rim.is(Blocks.OAK_LOG));
				else assertNull(rim);
			} else {
				assertNull(blocks.get(new BlockPos(x, 4, z)));
				if (x <= 4 && z <= 3 || x >= 5 && z >= 3)
					assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), blocks.get(new BlockPos(x, 3, z)));
				else assertNull(blocks.get(new BlockPos(x, 3, z)));
			}
		}
		for (int y : List.of(1, 2)) for (int x = 0; x <= 4; x++) for (int z = 0; z < 7; z++) {
			if (x != 0 && x != 4 && z != 0 && z != 6) continue;
			var wall = blocks.get(new BlockPos(x, y, z));
			if (x == 4 && z == 4) assertTrue(wall.is(Blocks.OAK_DOOR));
			else if ((x == 0 || x == 4) && (z == 0 || z == 6)) assertTrue(wall.is(Blocks.OAK_LOG));
			else assertTrue(wall.is(Blocks.OAK_PLANKS));
		}
		for (int z : List.of(3, 6)) for (int y : List.of(1, 2))
			assertTrue(blocks.get(new BlockPos(6, y, z)).is(Blocks.OAK_LOG));
	}

	@Test void mirrorThenTurnMatchesOfficialStatesAndPreservesEveryMaterialProgressIndex() throws Exception {
		var original = ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:timber_porch_cabin_v1"),
			resource("timber_porch_cabin_v1"));
		assertNotEquals(original.id(), cabin.id());
		assertNotEquals(original.fingerprint(), cabin.fingerprint());
		assertEquals(original.materials(), cabin.materials());
		assertEquals(original.instructions().size(), cabin.instructions().size());
		for (int i = 0; i < cabin.instructions().size(); i++) {
			var old = original.instructions().get(i);
			var next = cabin.instructions().get(i);
			assertEquals(old.item(), next.item(), "Material index " + i);
			assertEquals(old.consume(), next.consume(), "Consumption index " + i);
			assertEquals(new BlockPos(6 - old.offset().getZ(), old.offset().getY(), 6 - old.offset().getX()), next.offset());
			assertEquals(old.state().mirror(Mirror.FRONT_BACK).rotate(Rotation.CLOCKWISE_90), next.state());
		}
	}

	@Test void multipartConsumersAreAdjacentAndFollowTheirSupports() {
		Map<BlockPos, BlockState> placed = new HashMap<>();
		int doors = 0, beds = 0, torches = 0;
		var parts = cabin.instructions();
		for (int i = 0; i < parts.size(); i++) {
			var part = parts.get(i);
			var state = part.state();
			if (state.getBlock() instanceof DoorBlock && part.consume()) {
				doors++;
				assertEquals(new BlockPos(4, 1, 4), part.offset());
				assertEquals(Direction.EAST, state.getValue(DoorBlock.FACING));
				assertEquals(DoorHingeSide.RIGHT, state.getValue(DoorBlock.HINGE));
				assertSupported(placed, part.offset().below());
				assertEquals(part.offset().above(), parts.get(i + 1).offset());
				assertEquals(state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), parts.get(i + 1).state());
				assertFalse(parts.get(i + 1).consume());
			}
			if (state.getBlock() instanceof BedBlock && part.consume()) {
				beds++;
				assertTrue(List.of(new BlockPos(2, 1, 5), new BlockPos(2, 1, 1)).contains(part.offset()));
				assertEquals(Direction.WEST, state.getValue(BedBlock.FACING));
				var head = part.offset().west();
				assertSupported(placed, part.offset().below());
				assertSupported(placed, head.below());
				assertEquals(head, parts.get(i + 1).offset());
				assertEquals(state.setValue(BedBlock.PART, BedPart.HEAD), parts.get(i + 1).state());
				assertFalse(parts.get(i + 1).consume());
			}
			if (state.getBlock() instanceof WallTorchBlock) {
				torches++;
				assertTrue(List.of(new BlockPos(3, 2, 5), new BlockPos(3, 2, 1)).contains(part.offset()));
				Direction facing = state.getValue(WallTorchBlock.FACING);
				assertEquals(part.offset().getZ() == 5 ? Direction.NORTH : Direction.SOUTH, facing);
				assertSupported(placed, part.offset().relative(facing.getOpposite()));
			}
			placed.put(part.offset(), state);
		}
		assertEquals(1, doors);
		assertEquals(2, beds);
		assertEquals(2, torches);
	}

	private static void assertSupported(Map<BlockPos, BlockState> placed, BlockPos position) {
		assertNotNull(placed.get(position), "Support must precede consumer: " + position);
		assertTrue(ConstructionSite.safeSupport(placed.get(position)), "Full safe support: " + position);
	}

	@Test void porchEntryAndBedApproachesRemainClearWithTwoBlockHeadroom() {
		var blocks = layout();
		// East-facing door at x=4 is recessed behind two straight porch columns.
		for (int x : List.of(6, 5, 3, 2, 1)) assertClearColumn(blocks, x, 4);
		for (int z = 2; z <= 4; z++) assertClearColumn(blocks, 3, z);
		for (int x : List.of(1, 2)) assertClearColumn(blocks, x, 2);
		for (int x : List.of(1, 2)) for (int z : List.of(1, 5)) {
			assertTrue(blocks.get(new BlockPos(x, 1, z)).is(Blocks.WHITE_BED));
			assertNull(blocks.get(new BlockPos(x, 2, z)));
		}
	}

	private static void assertClearColumn(Map<BlockPos, BlockState> blocks, int x, int z) {
		assertSupported(blocks, new BlockPos(x, 0, z));
		assertNull(blocks.get(new BlockPos(x, 1, z)));
		assertNull(blocks.get(new BlockPos(x, 2, z)));
	}

	@Test void everyPlacementHasAStaticStandingWitnessWithinSixBlocks() {
		// Flat solid ground at y=-1. Search 32 exterior ground columns and 49 floor columns.
		// Interior witnesses require an already placed safe floor and two clear cells.
		// This proves bounded distance/standing geometry, NOT line of sight, navigation, or AI choice.
		Map<BlockPos, BlockState> placed = new HashMap<>();
		int highestRoofWitnesses = 0;
		for (var part : cabin.instructions()) {
			boolean witness = false;
			for (int x = -1; x <= 7; x++) for (int z = -1; z <= 7; z++) {
				boolean exterior = x == -1 || x == 7 || z == -1 || z == 7;
				var feet = new BlockPos(x, exterior ? 0 : 1, z);
				if (!exterior && (!placed.containsKey(feet.below())
					|| !ConstructionSite.safeSupport(placed.get(feet.below())))) continue;
				if (placed.containsKey(feet) || placed.containsKey(feet.above())) continue;
				// Also keep the witness clear after this placement.
				if (part.offset().equals(feet) || part.offset().equals(feet.above())) continue;
				// Entity feet at column center, target at block center, matching the six-block metric.
				double dx = part.offset().getX() - x, dz = part.offset().getZ() - z;
				double dy = part.offset().getY() + 0.5 - feet.getY();
				if (dx * dx + dy * dy + dz * dz <= 36.0) witness = true;
			}
			assertTrue(witness, "No static standing witness for " + part.offset());
			if (part.offset().getY() == 4) highestRoofWitnesses++;
			placed.put(part.offset(), part.state());
		}
		assertEquals(15, highestRoofWitnesses);
	}


	@Test void everyTargetHasAnExteriorGroundWitnessWithoutEnteringTheCabin() {
		// Independent stricter bound: flat exterior ground only, feet y=0. No door operation,
		// interior standing position, or roof climbing is assumed. This is still not pathfinding.
		var finalLayout = layout();
		double worstHighRoofSquared = 0.0;
		int highRoofTargets = 0;
		for (var part : cabin.instructions()) {
			double nearestSquared = Double.POSITIVE_INFINITY;
			for (int x = -1; x <= 7; x++) for (int z = -1; z <= 7; z++) {
				if (x != -1 && x != 7 && z != -1 && z != 7) continue;
				var feet = new BlockPos(x, 0, z);
				assertFalse(finalLayout.containsKey(feet));
				assertFalse(finalLayout.containsKey(feet.above()));
				double dx = part.offset().getX() - x, dz = part.offset().getZ() - z;
				double dy = part.offset().getY() + 0.5;
				nearestSquared = Math.min(nearestSquared, dx * dx + dy * dy + dz * dz);
			}
			assertTrue(nearestSquared <= 36.0, "No exterior witness for " + part.offset());
			if (part.offset().getY() == 4) {
				highRoofTargets++;
				worstHighRoofSquared = Math.max(worstHighRoofSquared, nearestSquared);
			}
		}
		assertEquals(15, highRoofTargets);
		assertEquals(29.25, worstHighRoofSquared);
	}

	private static final class FlatProbe implements ConstructionSite.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> checked = new HashSet<>(), unknown = new HashSet<>();
		int floorY = ORIGIN.getY() - 1;
		int reads;
		public boolean inBounds(BlockPos p) { return true; }
		public boolean ticking(BlockPos p) {
			if (unknown.contains(p)) return false;
			checked.add(p.immutable());
			return true;
		}
		public BlockState state(BlockPos p) {
			assertTrue(checked.contains(p));
			assertFalse(unknown.contains(p));
			reads++;
			return blocks.getOrDefault(p, p.getY() <= floorY
				? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
	}

	@Test void sharedSiteInspectionAcceptsFlatGroundAtZeroAndBothOneBlockOffsets() {
		for (int height : List.of(0, 1, -1)) {
			var origin = ORIGIN.offset(0, height, 0);
			var flat = new FlatProbe();
			flat.floorY += height;
			assertTrue(ConstructionSite.inspect(cabin, origin, flat).ready());
			for (int y = -1; y <= 2; y++) assertTrue(flat.checked.contains(origin.offset(7, y, 4)));
			assertFalse(flat.checked.contains(origin.offset(2, 1, -1)), "The old north exit is not used");
		}
	}

	@Test void eastExitAndRecessedCorridorObstaclesAreRejectedDespiteClearOldNorthExit() {
		for (int y : List.of(1, 2)) {
			var exterior = new FlatProbe();
			var exit = ORIGIN.offset(7, y, 4);
			exterior.blocks.put(exit, Blocks.STONE.defaultBlockState());
			var blocked = ConstructionSite.inspect(cabin, ORIGIN, exterior);
			assertEquals("entrance_obstructed", blocked.reason());
			assertEquals(exit, blocked.position());
			for (int x : List.of(5, 6)) {
				var corridor = new FlatProbe();
				var obstacle = ORIGIN.offset(x, y, 4);
				corridor.blocks.put(obstacle, Blocks.STONE.defaultBlockState());
				var occupied = ConstructionSite.inspect(cabin, ORIGIN, corridor);
				assertEquals("volume_occupied", occupied.reason());
				assertEquals(obstacle, occupied.position());
			}
		}
	}

	@Test void eastExitAcceptsSafeLevelOrOneStepDownSupportAndRejectsUnsafeFloors() {
		for (int height : List.of(0, 1, -1)) {
			var origin = ORIGIN.offset(0, height, 0);
			var levelFloor = origin.offset(7, 0, 4);
			var lowerFloor = origin.offset(7, -1, 4);
			var level = new FlatProbe();
			level.floorY += height;
			level.blocks.put(levelFloor, Blocks.STONE.defaultBlockState());
			assertTrue(ConstructionSite.inspect(cabin, origin, level).ready());
			var stepDown = new FlatProbe();
			stepDown.floorY += height;
			assertTrue(ConstructionSite.inspect(cabin, origin, stepDown).ready());
			for (var unsafe : List.of(Blocks.AIR, Blocks.WATER, Blocks.MAGMA_BLOCK, Blocks.SAND)) {
				var probe = new FlatProbe();
				probe.floorY += height;
				probe.blocks.put(lowerFloor, unsafe.defaultBlockState());
				var result = ConstructionSite.inspect(cabin, origin, probe);
				assertEquals("entrance_unsupported", result.reason());
				assertEquals(lowerFloor, result.position());
			}
			var waterAtLevel = new FlatProbe();
			waterAtLevel.floorY += height;
			waterAtLevel.blocks.put(levelFloor, Blocks.WATER.defaultBlockState());
			var result = ConstructionSite.inspect(cabin, origin, waterAtLevel);
			assertEquals("entrance_unsupported", result.reason());
			assertEquals(levelFloor, result.position());
		}
	}

	@Test void unknownEastExitEnvelopeFailsBeforeAnyWorldReads() {
		for (int y = -1; y <= 2; y++) {
			var probe = new FlatProbe();
			var unknown = ORIGIN.offset(7, y, 4);
			probe.unknown.add(unknown);
			var result = ConstructionSite.inspect(cabin, ORIGIN, probe);
			assertEquals("not_ticking", result.reason());
			assertEquals(unknown, result.position());
			assertEquals(0, probe.reads);
		}
	}

	@Test void oldNorthExitObstaclesAndUnknownCellsDoNotReplaceTheEastExit() {
		var probe = new FlatProbe();
		for (int y = -1; y <= 2; y++) {
			var oldExit = ORIGIN.offset(2, y, -1);
			probe.blocks.put(oldExit, Blocks.STONE.defaultBlockState());
			probe.unknown.add(oldExit);
		}
		assertTrue(ConstructionSite.inspect(cabin, ORIGIN, probe).ready());
		assertTrue(probe.checked.contains(ORIGIN.offset(7, 1, 4)));
	}
}
