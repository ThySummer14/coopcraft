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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Static siting evidence only: these tests do not start a server or prove villager navigation. */
class ConstructionPorchSiteTest {
	private static final BlockPos ORIGIN = new BlockPos(10, 64, 20);
	private static ConstructionPlan cottage;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		try (var reader = new InputStreamReader(ConstructionPorchSiteTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/stone_cottage.json"), StandardCharsets.UTF_8)) {
			cottage = ConstructionPlan.fromJson(ConstructionPlanner.STONE_COTTAGE,
				JsonParser.parseReader(reader).getAsJsonObject());
		}
	}

	private static final class Probe implements ConstructionSite.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>(), outside = new HashSet<>(), checked = new HashSet<>();
		int reads, checks;
		public boolean inBounds(BlockPos p) { checks++; return !outside.contains(p); }
		public boolean ticking(BlockPos p) {
			assertFalse(outside.contains(p));
			if (unknown.contains(p)) return false;
			checked.add(p.immutable());
			return true;
		}
		public BlockState state(BlockPos p) {
			assertTrue(checked.contains(p));
			assertFalse(unknown.contains(p));
			assertFalse(outside.contains(p));
			reads++;
			return blocks.getOrDefault(p, p.getY() == 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
	}

	private static ConstructionInstruction part(BlockPos p, BlockState state) {
		return new ConstructionInstruction(p, state, state.getBlock().asItem(), true);
	}

	private static void door(List<ConstructionInstruction> parts, int x, int z, Direction facing) {
		for (int y = 1; y <= 2; y++) parts.add(part(new BlockPos(x, y, z), Blocks.OAK_DOOR.defaultBlockState()
			.setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF,
				y == 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER)));
	}

	private static ConstructionPlan plan(List<ConstructionInstruction> parts) {
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:porch_site_fixture"), parts);
	}

	private static ConstructionPlan porch(int doorZ, Direction facing, boolean closeRear) {
		List<ConstructionInstruction> parts = new ArrayList<>();
		for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
			parts.add(part(new BlockPos(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState()));
			parts.add(part(new BlockPos(x, 3, z), Blocks.OAK_PLANKS.defaultBlockState()));
		}
		parts.add(part(new BlockPos(1, 1, 4), Blocks.RED_BED.defaultBlockState()));
		door(parts, 3, doorZ, facing);
		if (closeRear) parts.add(part(new BlockPos(3, 1, doorZ + 1), Blocks.OAK_PLANKS.defaultBlockState()));
		return plan(parts);
	}

	private static ConstructionPlan replace(ConstructionPlan source, BlockPos p, BlockState state) {
		var parts = new ArrayList<>(source.instructions().stream().filter(i -> !i.offset().equals(p)).toList());
		if (state != null) parts.add(part(p, state));
		return plan(parts);
	}

	private static ConstructionPlan rotate(ConstructionPlan source) {
		return plan(source.instructions().stream().map(i -> new ConstructionInstruction(
			new BlockPos(6 - i.offset().getZ(), i.offset().getY(), i.offset().getX()),
			i.state().rotate(Rotation.CLOCKWISE_90), i.item(), i.consume())).toList());
	}

	@Test void recessedPorchesUnderEavesWorkOnAllAxesWithEitherFacingSign() {
		for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH)) {
			var source = porch(2, facing, true);
			for (BlockPos exterior : List.of(new BlockPos(3, 1, -1), new BlockPos(7, 1, 3),
				new BlockPos(3, 1, 7), new BlockPos(-1, 1, 3))) {
				var probe = new Probe();
				assertTrue(ConstructionSite.inspect(source, ORIGIN, probe).ready());
				assertTrue(probe.checked.contains(ORIGIN.offset(exterior)));
				probe.blocks.put(ORIGIN.offset(exterior), Blocks.STONE.defaultBlockState());
				var blocked = ConstructionSite.inspect(source, ORIGIN, probe);
				assertEquals("entrance_obstructed", blocked.reason());
				assertEquals(ORIGIN.offset(exterior), blocked.position());
				source = rotate(source);
			}
		}
	}

	@Test void blockedPlannedCorridorsAndUnsafeOrMissingFloorsFailBeforeWorldChecks() {
		var source = porch(2, Direction.NORTH, true);
		var invalid = new ArrayList<ConstructionPlan>();
		for (int y : List.of(1, 2)) invalid.add(replace(source, new BlockPos(3, y, 0), Blocks.OAK_PLANKS.defaultBlockState()));
		for (BlockState floor : List.of(Blocks.OAK_SLAB.defaultBlockState(), Blocks.WATER.defaultBlockState(),
			Blocks.SAND.defaultBlockState())) invalid.add(replace(source, new BlockPos(3, 0, 1), floor));
		invalid.add(replace(source, new BlockPos(3, 0, 1), null));
		invalid.add(replace(source, new BlockPos(0, 0, 6), null)); // Whole base still required, beyond corridor.
		var innerDoor = new ArrayList<>(source.instructions());
		door(innerDoor, 3, 1, Direction.NORTH); // A closed inner door is not planned air.
		invalid.add(plan(innerDoor));
		invalid.add(replace(source, new BlockPos(3, 2, 2), null));
		invalid.add(replace(source, new BlockPos(3, 2, 2), Blocks.OAK_DOOR.defaultBlockState()
			.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.SOUTH)));
		for (ConstructionPlan candidate : invalid) {
			var probe = new Probe();
			assertEquals("unsupported_layout", ConstructionSite.inspect(candidate, ORIGIN, probe).reason());
			assertEquals(0, probe.reads);
			assertEquals(0, probe.checks);
		}
	}

	@Test void shortestPlannedExitWinsAndTiesFollowFacingWithoutWorldFallback() {
		for (int z : List.of(2, 3, 4)) for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH)) {
			var source = porch(z, facing, false);
			boolean north = z < 3 || (z == 3 && facing == Direction.NORTH);
			BlockPos selected = ORIGIN.offset(3, 1, north ? -1 : 7);
			BlockPos alternative = ORIGIN.offset(3, 1, north ? 7 : -1);
			var probe = new Probe();
			assertTrue(ConstructionSite.inspect(source, ORIGIN, probe).ready());
			assertTrue(probe.checked.contains(selected));
			assertFalse(probe.checked.contains(alternative));
			probe.blocks.put(selected, Blocks.STONE.defaultBlockState());
			var result = ConstructionSite.inspect(source, ORIGIN, probe);
			assertEquals("entrance_obstructed", result.reason());
			assertEquals(selected, result.position());
		}
	}

	@Test void blockedShorterPlannedRouteCanUseLongerStraightRouteBeforeWorldReads() {
		var source = replace(porch(2, Direction.NORTH, false), new BlockPos(3, 2, 1), Blocks.STONE.defaultBlockState());
		var probe = new Probe();
		assertTrue(ConstructionSite.inspect(source, ORIGIN, probe).ready());
		assertTrue(probe.checked.contains(ORIGIN.offset(3, 1, 7)));
		assertFalse(probe.checked.contains(ORIGIN.offset(3, 1, -1)));
		probe.unknown.add(ORIGIN.offset(3, 2, 7));
		probe.reads = 0;
		assertEquals("not_ticking", ConstructionSite.inspect(source, ORIGIN, probe).reason());
		assertEquals(0, probe.reads);
	}

	@Test void newExteriorColumnAndFullVolumeArePreflightedWithZeroReadsWhenUnknown() {
		var source = porch(2, Direction.NORTH, true);
		var offsets = new ArrayList<BlockPos>();
		for (int y = -1; y <= 2; y++) offsets.add(new BlockPos(3, y, -1));
		offsets.add(new BlockPos(6, 3, 6));
		offsets.add(new BlockPos(6, -1, 6));
		for (BlockPos p : offsets) for (boolean bounds : List.of(false, true)) {
			var probe = new Probe();
			(bounds ? probe.outside : probe.unknown).add(ORIGIN.offset(p));
			var result = ConstructionSite.inspect(source, ORIGIN, probe);
			assertEquals(bounds ? "out_of_bounds" : "not_ticking", result.reason());
			assertEquals(ORIGIN.offset(p), result.position());
			assertEquals(0, probe.reads);
		}
	}

	@Test void everyRecessedDoorRequiresItsOwnExteriorAndBothStandingLevelsRemainSupported() {
		var parts = new ArrayList<>(porch(2, Direction.NORTH, true).instructions());
		door(parts, 5, 2, Direction.NORTH);
		parts.add(part(new BlockPos(5, 1, 3), Blocks.STONE.defaultBlockState()));
		var source = plan(parts);
		var probe = new Probe();
		assertTrue(ConstructionSite.inspect(source, ORIGIN, probe).ready());
		probe.blocks.put(ORIGIN.offset(5, 0, -1), Blocks.STONE.defaultBlockState());
		assertTrue(ConstructionSite.inspect(source, ORIGIN, probe).ready());
		probe.blocks.put(ORIGIN.offset(5, 2, -1), Blocks.STONE.defaultBlockState());
		assertEquals("entrance_obstructed", ConstructionSite.inspect(source, ORIGIN, probe).reason());
		probe.blocks.remove(ORIGIN.offset(5, 2, -1));
		probe.blocks.put(ORIGIN.offset(5, 0, -1), Blocks.WATER.defaultBlockState());
		assertEquals("entrance_unsupported", ConstructionSite.inspect(source, ORIGIN, probe).reason());
	}

	@Test void shippedCottageAndSavedJobsRemainReadOnlyWithUnchangedPlansAndNbt() {
		var source = porch(2, Direction.NORTH, true);
		var instructions = List.copyOf(source.instructions());
		var fingerprint = source.fingerprint();
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Porch site", 0);
		var saved = new ConstructionJob(source.id(), ORIGIN, 7, fingerprint);
		data.setConstructionJob(settlement, saved);
		var before = data.save(new CompoundTag(), null);
		var probe = new Probe();
		var blocks = Map.copyOf(probe.blocks);
		assertTrue(ConstructionSite.inspect(cottage, ORIGIN, probe).ready());
		assertTrue(probe.checked.contains(ORIGIN.offset(2, 1, -1)));
		for (int i = 0; i < 3; i++) {
			assertTrue(ConstructionSite.inspect(source, ORIGIN, probe).ready());
			assertTrue(ConstructionSystem.inspectSites(null, data, settlement, source).isEmpty());
			assertEquals(saved, data.getConstructionJob(settlement).orElseThrow());
			assertEquals(before, data.save(new CompoundTag(), null));
			assertEquals(instructions, source.instructions());
			assertEquals(fingerprint, source.fingerprint());
			assertEquals(blocks, probe.blocks);
		}
	}
}
