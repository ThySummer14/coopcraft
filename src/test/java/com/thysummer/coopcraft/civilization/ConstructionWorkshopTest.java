package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure fixture checks; no game/server startup or claim of navigation correctness. */
class ConstructionWorkshopTest {
	private static final BlockPos ORIGIN = new BlockPos(10, 64, 20);
	private static ConstructionPlan workshop;

	@BeforeAll static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		try (var reader = new InputStreamReader(ConstructionWorkshopTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/starter_workshop_v1.json"), StandardCharsets.UTF_8)) {
			var json = JsonParser.parseReader(reader).getAsJsonObject();
			assertEquals("[1,1,1]", json.getAsJsonArray("size").toString());
			workshop = ConstructionPlan.fromJson(ConstructionPlanner.STARTER_WORKSHOP, json);
		}
	}

	private static final class Probe implements ConstructionSite.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<BlockPos> outside = new HashSet<>(), unknown = new HashSet<>(), checked = new HashSet<>();
		final List<BlockPos> reads = new ArrayList<>();
		int checks;
		boolean requireEnvelope = true;
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
			if (requireEnvelope) assertEquals(27, checked.size());
			reads.add(p.immutable());
			return blocks.getOrDefault(p, p.getY() == 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
	}

	private static ConstructionPlan plan(ConstructionInstruction... parts) {
		return new ConstructionPlan(ConstructionPlanner.STARTER_WORKSHOP, List.of(parts));
	}

	@Test void shippedPlanCostsExactlyOneRealTableOnlyOnSuccessfulPlacement() {
		assertEquals(1, workshop.instructions().size());
		var part = workshop.instructions().getFirst();
		assertEquals(BlockPos.ZERO, part.offset());
		assertEquals(Blocks.CRAFTING_TABLE.defaultBlockState(), part.state());
		assertEquals(Map.of(Items.CRAFTING_TABLE, 1), workshop.materials());
		var stockpile = new SimpleContainer(1);
		stockpile.setItem(0, new ItemStack(Items.CRAFTING_TABLE, 2));
		var job = ConstructionJob.start(workshop, ORIGIN);
		var placements = new AtomicInteger();
		assertFalse(ConstructionMaterials.place(stockpile, null, part.item(), part.consume(), job,
			UUID.randomUUID(), () -> {}, () -> false));
		assertEquals(2, stockpile.countItem(Items.CRAFTING_TABLE));
		assertTrue(ConstructionMaterials.place(stockpile, null, part.item(), part.consume(), job,
			UUID.randomUUID(), () -> {}, () -> { placements.incrementAndGet(); return true; }));
		assertEquals(1, stockpile.countItem(Items.CRAFTING_TABLE));
		assertEquals(1, placements.get());
	}

	@Test void onlyExactSupportedIdAndInstructionCanGainWorkshopSemantics() {
		var table = workshop.instructions().getFirst();
		var invalid = List.of(plan(), plan(table, table),
			plan(new ConstructionInstruction(BlockPos.ZERO.above(), table.state(), table.item(), true)),
			plan(new ConstructionInstruction(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), table.item(), true)),
			plan(new ConstructionInstruction(BlockPos.ZERO, table.state(), Items.OAK_PLANKS, true)),
			plan(new ConstructionInstruction(BlockPos.ZERO, table.state(), table.item(), false)),
			new ConstructionPlan(ResourceLocation.parse("coopcraft:other_workshop"), List.of(table)));
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Workshop", 0);
		for (var source : invalid) {
			var probe = new Probe();
			var result = ConstructionWorkshop.inspect(source, ORIGIN, probe);
			assertEquals("unsupported_layout", result.reason());
			assertEquals(source.id(), result.blueprintId());
			assertEquals(ORIGIN, result.origin());
			assertEquals(ORIGIN, result.position());
			assertFalse(ConstructionWorkshop.registerCompleted(data, settlement, source, ORIGIN, probe));
			assertEquals(0, probe.checks);
			assertTrue(probe.reads.isEmpty());
		}
		assertEquals("unsupported_layout", ConstructionWorkshop.inspect(null, ORIGIN, new Probe()).reason());
	}

	@Test void entireEnvelopeIsCheckedBeforeAnyReadAndFailureKeepsItsExactPosition() {
		var ready = new Probe();
		var result = ConstructionWorkshop.inspect(workshop, ORIGIN, ready);
		assertTrue(result.ready());
		assertEquals(workshop.id(), result.blueprintId());
		assertNull(result.position());
		assertEquals(27, ready.reads.size());
		for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
			var position = ORIGIN.offset(x, y, z);
			for (boolean bounds : List.of(false, true)) {
				var probe = new Probe();
				(bounds ? probe.outside : probe.unknown).add(position);
				result = ConstructionWorkshop.inspect(workshop, ORIGIN, probe);
				assertEquals(bounds ? "out_of_bounds" : "not_ticking", result.reason());
				assertEquals(position, result.position());
				assertEquals(ORIGIN, result.origin());
				assertEquals(workshop.id(), result.blueprintId());
				assertTrue(probe.reads.isEmpty());
			}
		}
	}

	@Test void everyFoundationCellRejectsHazardsAndAllWorkingCellsRequireStrictAir() {
		for (var unsafe : List.of(Blocks.AIR, Blocks.WATER, Blocks.LAVA, Blocks.SAND, Blocks.GRAVEL,
			Blocks.OAK_LEAVES, Blocks.OAK_SLAB, Blocks.CHEST, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
			Blocks.POWDER_SNOW, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.ICE, Blocks.PACKED_ICE,
			Blocks.BLUE_ICE, Blocks.FROSTED_ICE)) {
			for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
				var probe = new Probe();
				var position = ORIGIN.offset(x, -1, z);
				probe.blocks.put(position, unsafe.defaultBlockState());
				var result = ConstructionWorkshop.inspect(workshop, ORIGIN, probe);
				assertEquals("foundation_unsupported", result.reason(), unsafe.toString());
				assertEquals(position, result.position());
			}
		}
		for (int y = 0; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
			for (var obstacle : List.of(Blocks.SHORT_GRASS, Blocks.STONE, Blocks.CRAFTING_TABLE, Blocks.WATER)) {
				var probe = new Probe();
				var position = ORIGIN.offset(x, y, z);
				probe.blocks.put(position, obstacle.defaultBlockState());
				var result = ConstructionWorkshop.inspect(workshop, ORIGIN, probe);
				assertEquals("volume_occupied", result.reason());
				assertEquals(position, result.position());
				assertEquals(Map.of(position, obstacle.defaultBlockState()), probe.blocks);
			}
		}
	}

	@Test void overflowNeverWrapsOrReadsTheWorld() {
		for (int extreme : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)) {
			for (var origin : List.of(new BlockPos(extreme, 64, 20), new BlockPos(10, extreme, 20),
				new BlockPos(10, 64, extreme))) {
				var probe = new Probe();
				var result = ConstructionWorkshop.inspect(workshop, origin, probe);
				assertEquals("out_of_bounds", result.reason());
				assertEquals(origin, result.position());
				assertEquals(0, probe.checks);
				assertTrue(probe.reads.isEmpty());
			}
		}
	}

	@Test void completionRequiresRealLoadedTableIsIdempotentAndNeverOverwritesOtherMarker() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Workshop", 0);
		var probe = new Probe();
		probe.requireEnvelope = false;
		data.setDirty(false);
		assertFalse(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
		assertTrue(data.getCraftingStation(settlement).isEmpty());
		assertFalse(data.isDirty());
		probe.blocks.put(ORIGIN, Blocks.CRAFTING_TABLE.defaultBlockState());
		assertTrue(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
		assertEquals(ORIGIN, data.getCraftingStation(settlement).orElseThrow());
		assertTrue(data.isDirty());
		data.setDirty(false);
		var before = data.save(new CompoundTag(), null);
		assertTrue(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
		assertEquals(Set.of(ORIGIN), Set.copyOf(probe.reads));
		probe.blocks.put(ORIGIN, Blocks.AIR.defaultBlockState());
		assertFalse(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
		var other = ORIGIN.offset(20, 0, 0);
		data.setCraftingStation(settlement, other);
		data.setDirty(false);
		probe.reads.clear();
		assertFalse(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
		assertEquals(other, data.getCraftingStation(settlement).orElseThrow());
		assertFalse(data.isDirty());
		assertTrue(probe.reads.isEmpty());
	}

	@Test void completionDoesNotReadUnknownOrOutOfBoundsTargetsOrNeighbors() {
		for (boolean bounds : List.of(false, true)) {
			var data = new SettlementSavedData();
			var settlement = data.getOrCreate(BlockPos.ZERO, "Workshop", 0);
			var probe = new Probe();
			probe.requireEnvelope = false;
			probe.blocks.put(ORIGIN, Blocks.CRAFTING_TABLE.defaultBlockState());
			(bounds ? probe.outside : probe.unknown).add(ORIGIN);
			data.setDirty(false);
			assertFalse(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
			assertTrue(probe.reads.isEmpty());
			assertFalse(data.isDirty());
			probe.outside.clear();
			probe.unknown.clear();
			probe.unknown.add(ORIGIN.above());
			assertTrue(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
			assertEquals(List.of(ORIGIN), probe.reads);
		}
	}

	@Test void inspectionAndRegistrationLeaveSavedJobFingerprintAndPlanUnchanged() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Workshop", 0);
		var job = ConstructionJob.start(workshop, ORIGIN);
		data.setConstructionJob(settlement, job);
		var before = data.save(new CompoundTag(), null);
		var savedJob = job.save();
		var parts = List.copyOf(workshop.instructions());
		var fingerprint = workshop.fingerprint();
		data.setDirty(false);
		for (int i = 0; i < 3; i++) assertTrue(ConstructionWorkshop.inspect(workshop, ORIGIN, new Probe()).ready());
		assertEquals(before, data.save(new CompoundTag(), null));
		assertTrue(data.getCraftingStation(settlement).isEmpty());
		assertFalse(data.isDirty());
		var probe = new Probe();
		probe.requireEnvelope = false;
		probe.blocks.put(ORIGIN, Blocks.CRAFTING_TABLE.defaultBlockState());
		assertTrue(ConstructionWorkshop.registerCompleted(data, settlement, workshop, ORIGIN, probe));
		assertEquals(job, data.getConstructionJob(settlement).orElseThrow());
		assertEquals(savedJob, data.getConstructionJob(settlement).orElseThrow().save());
		assertEquals(job, ConstructionJob.load(savedJob));
		assertEquals(parts, workshop.instructions());
		assertEquals(fingerprint, workshop.fingerprint());
	}
}
