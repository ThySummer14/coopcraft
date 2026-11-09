package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual shared selection and inventory services with controlled world probes, not an entity navigation test. */
class ConstructionSupplyIntegrationTest {
	private static final ResourceLocation DIM = Level.OVERWORLD.location();
	private static final UUID CAMP = new UUID(64, 1), FIRST = new UUID(64, 2), SECOND = new UUID(64, 3);
	private static final BlockPos ORIGIN = new BlockPos(8, 64, 8);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static ConstructionPlan pair(boolean door) {
		BlockState first = door ? Blocks.OAK_DOOR.defaultBlockState()
			: Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		BlockState second = door ? first.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
			: first.setValue(BedBlock.PART, BedPart.HEAD);
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:supply_pair"), List.of(
			new ConstructionInstruction(BlockPos.ZERO, first, first.getBlock().asItem(), true),
			new ConstructionInstruction(door ? BlockPos.ZERO.above() : BlockPos.ZERO.east(), second, second.getBlock().asItem(), false)));
	}

	private static final class Probe implements ConstructionStepReadiness.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		public boolean inBounds(BlockPos at) { return true; }
		public boolean ticking(BlockPos at) { return true; }
		public BlockState state(BlockPos at) { return blocks.getOrDefault(at, Blocks.AIR.defaultBlockState()); }
		public boolean entityObstructed(BlockPos at) { return false; }
		public SettlementHousing.BedCheck inspectBed(BlockPos at) {
			return SettlementHousing.inspectBed(new SettlementHousing.BedAccess() {
				public boolean isLoaded(BlockPos cell) { return true; }
				public BlockState stateAt(BlockPos cell) { return state(cell); }
			}, at);
		}
	}

	@Test void lastUnitSurvivesReloadAndBuildsBothHalvesWithoutReadingTheSourceAgain() {
		for (boolean door : List.of(true, false)) {
			var plan = pair(door); var first = plan.instructions().getFirst();
			var job = ConstructionJob.start(plan, ORIGIN);
			var store = new SimpleContainer(new ItemStack(first.item())); var bag = new SimpleContainer(8);
			assertTrue(ConstructionSupply.pickup(store, bag, DIM, CAMP, FIRST, job, first, () -> true));
			assertTrue(store.isEmpty()); assertFalse(SettlementCargo.hasCargo(bag));
			var restoredJob = ConstructionJob.load(job.save());
			assertTrue(ConstructionSystem.materialReady(restoredJob, first, DIM, CAMP, FIRST, bag,
				() -> { throw new AssertionError("Must not inspect the empty/forgotten store"); },
				() -> { throw new AssertionError("Must not export from the simulation"); }));
			var probe = new Probe();
			assertTrue(ConstructionSystem.placeIfReady(restoredJob, plan, probe,
				() -> ConstructionSupply.place(bag, DIM, CAMP, FIRST, restoredJob, first, () -> true,
					() -> { assertTrue(bag.isEmpty()); probe.blocks.put(ORIGIN, first.state()); return true; })));
			assertTrue(bag.isEmpty());
			var next = restoredJob.advance(); var free = plan.instructions().getLast();
			assertTrue(ConstructionSystem.materialReady(next, free, DIM, CAMP, FIRST, bag,
				() -> { throw new AssertionError("Free half must not inspect the store"); }, () -> null));
			assertTrue(ConstructionSystem.placeIfReady(next, plan, probe,
				() -> ConstructionMaterials.place(null, null, free.item(), false, next, FIRST,
					() -> { throw new AssertionError("No export"); },
					() -> { probe.blocks.put(ORIGIN.offset(free.offset()), free.state()); return true; })));
			assertEquals(2, probe.blocks.size()); assertTrue(store.isEmpty()); assertTrue(bag.isEmpty());
		}
	}

	@Test void twoWorkersChargeOneActualItemAndTheObsoleteItemReturnsOnlyAtTheStore() {
		var plan = pair(true); var job = ConstructionJob.start(plan, ORIGIN); var instruction = plan.instructions().getFirst();
		var store = new SimpleContainer(new ItemStack(Items.OAK_DOOR, 2));
		var first = new SimpleContainer(8); var second = new SimpleContainer(8);
		assertTrue(ConstructionSupply.pickup(store, first, DIM, CAMP, FIRST, job, instruction, () -> true));
		assertTrue(ConstructionSupply.pickup(store, second, DIM, CAMP, SECOND, job, instruction, () -> true));
		var current = new AtomicReference<>(job);
		assertTrue(ConstructionSupply.place(first, DIM, CAMP, FIRST, job, instruction,
			() -> job.equals(current.get()), () -> true));
		current.set(job.advance());
		assertFalse(ConstructionSupply.place(second, DIM, CAMP, SECOND, job, instruction,
			() -> job.equals(current.get()), () -> { throw new AssertionError("Stale placement"); }));
		assertEquals(0, ConstructionSupply.returnUnused(second, store, DIM, CAMP, SECOND, current.get(), () -> false));
		assertEquals(1, second.countItem(Items.OAK_DOOR));
		assertEquals(1, ConstructionSupply.returnUnused(second, store, DIM, CAMP, SECOND, current.get(), () -> true));
		assertTrue(second.isEmpty()); assertEquals(1, store.countItem(Items.OAK_DOOR));
		assertTrue(ConstructionSupply.ordinary(store.getItem(0)));
	}

	@Test void materialGateRejectsWrongAssignmentAndMarkedPublicInventory() {
		var plan = pair(false); var job = ConstructionJob.start(plan, ORIGIN); var instruction = plan.instructions().getFirst();
		var store = new SimpleContainer(new ItemStack(Items.WHITE_BED)); var bag = new SimpleContainer(8);
		assertTrue(ConstructionSupply.pickup(store, bag, DIM, CAMP, FIRST, job, instruction, () -> true));
		var recreated = ConstructionJob.start(plan, ORIGIN);
		assertFalse(ConstructionSystem.materialReady(recreated, instruction, DIM, CAMP, FIRST, bag, () -> store, () -> null));
		assertFalse(ConstructionSystem.materialReady(job, instruction, DIM, CAMP, SECOND, bag, () -> null, () -> null));
		var recoveredStore = new SimpleContainer(bag.getItem(0).copy());
		assertFalse(ConstructionSystem.materialReady(job, instruction, DIM, CAMP, SECOND, null, () -> recoveredStore, () -> null));
		assertTrue(ConstructionSupply.hasReturnable(bag, DIM, CAMP, FIRST, recreated));
	}

	@Test void visibleActionIdentityAndRecoveredSupplyKeysAreBilingual() throws Exception {
		for (String language : List.of("zh_cn", "en_us")) {
			try (var stream = getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json")) {
				assertNotNull(stream);
				var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String key : List.of("coopcraft.interaction.action.collect_build_supply", "coopcraft.interaction.action.return_build_supply",
					"coopcraft.interaction.blocker.construction_invalid_job_id", "coopcraft.command.settlement.construction_adopt_invalid_job_id",
					"coopcraft.construction_supply.reserved", "coopcraft.construction_supply.recovered")) {
					assertTrue(json.has(key)); assertFalse(json.get(key).getAsString().isBlank());
				}
			}
		}
	}
}
