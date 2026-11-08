package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real blockstates/containers and production service/NBT; no world, loot-table evaluation or Goal execution. */
class SettlementCropHarvestTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void onlyFullyMaturePotatoesQualify() {
		CropBlock crop = (CropBlock) Blocks.POTATOES;
		for (int age = 0; age < crop.getMaxAge(); age++) assertFalse(SettlementCropHarvest.isRipe(crop.getStateForAge(age)));
		assertTrue(SettlementCropHarvest.isRipe(crop.getStateForAge(crop.getMaxAge())));
		assertFalse(SettlementCropHarvest.isRipe(((CropBlock) Blocks.CARROTS).getStateForAge(7)));
		assertFalse(SettlementCropHarvest.isRipe(Blocks.AIR.defaultBlockState()));
	}

	@Test void oneSeedAcrossMultipleLootStacksAndNoInputMutation() {
		ItemStack first = new ItemStack(Items.POTATO, 1), second = new ItemStack(Items.POTATO, 4);
		var output = SettlementCropHarvest.outputAfterSeed(List.of(first, second)).orElseThrow();
		assertEquals(1, first.getCount()); assertEquals(4, second.getCount());
		assertEquals(4, output.stream().mapToInt(ItemStack::getCount).sum());
	}

	@Test void missingOrdinarySeedRejectsPoisonAndCustomPotatoes() {
		ItemStack named = new ItemStack(Items.POTATO, 3);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
		assertTrue(SettlementCropHarvest.outputAfterSeed(List.of(named, new ItemStack(Items.POISONOUS_POTATO))).isEmpty());
		assertTrue(SettlementCropHarvest.outputAfterSeed(List.of()).isEmpty());
	}

	@Test void customAndPoisonByproductsArePreserved() {
		ItemStack named = new ItemStack(Items.POTATO, 2);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
		var output = SettlementCropHarvest.outputAfterSeed(List.of(named, new ItemStack(Items.POISONOUS_POTATO), new ItemStack(Items.POTATO, 3))).orElseThrow();
		SimpleContainer inventory = new SimpleContainer(3);
		assertTrue(SettlementHarvestCommit.commit(inventory, output, () -> true));
		assertTrue(ItemStack.matches(named, inventory.getItem(0)));
		assertEquals(1, inventory.countItem(Items.POISONOUS_POTATO));
		assertEquals(4, inventory.countItem(Items.POTATO));
	}

	@Test void capacityMustFitTheWholeBatchBeforeWorldMutation() {
		SimpleContainer inventory = new SimpleContainer(1);
		AtomicInteger replants = new AtomicInteger();
		assertFalse(SettlementHarvestCommit.commit(inventory, List.of(new ItemStack(Items.POTATO, 2), new ItemStack(Items.POISONOUS_POTATO)), () -> { replants.incrementAndGet(); return true; }));
		assertTrue(inventory.isEmpty()); assertEquals(0, replants.get());
	}

	@Test void failedReplantNeverInsertsOutput() {
		SimpleContainer inventory = new SimpleContainer(2);
		inventory.setItem(0, new ItemStack(Items.POTATO, 60));
		assertFalse(SettlementHarvestCommit.commit(inventory, List.of(new ItemStack(Items.POTATO, 4)), () -> false));
		assertEquals(60, inventory.countItem(Items.POTATO));
	}

	@Test void exactFitMergesOnlyAfterReplant() {
		SimpleContainer inventory = new SimpleContainer(1);
		inventory.setItem(0, new ItemStack(Items.POTATO, 60));
		assertTrue(SettlementHarvestCommit.commit(inventory, List.of(new ItemStack(Items.POTATO, 4)), () -> { assertEquals(60, inventory.countItem(Items.POTATO)); return true; }));
		assertEquals(64, inventory.countItem(Items.POTATO));
	}

	@Test void onlySeedCanReplantWithoutCreatingFood() {
		var output = SettlementCropHarvest.outputAfterSeed(List.of(new ItemStack(Items.POTATO))).orElseThrow();
		assertTrue(output.isEmpty());
		assertTrue(SettlementHarvestCommit.commit(new SimpleContainer(0), output, () -> true));
	}

	@Test void resourceMarksPersistAndExplicitCancellationSurvivesReload() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		BlockPos plot = new BlockPos(2, 0, 1);
		assertTrue(data.toggleResource(settlement, plot));
		for (int round = 0; round < 3; round++) {
			CompoundTag saved = data.save(new CompoundTag(), null);
			data = SettlementSavedData.load(saved, null);
			assertEquals(saved, data.save(new CompoundTag(), null));
			assertTrue(data.resourcesOf(settlement).contains(plot));
		}
		assertFalse(data.toggleResource(settlement, plot));
		assertFalse(SettlementSavedData.load(data.save(new CompoundTag(), null), null).resourcesOf(settlement).contains(plot));
	}

	@Test void foodModeDisallowsSurvivalWithoutProduction() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		assertTrue(SettlementForaging.canForage(data, settlement));
		var state = data.getOrCreateSimulation(settlement);
		state.setSurvivalEnabled(true, 0);
		assertFalse(SettlementForaging.canForage(data, settlement));
		state.setProductionEnabled(true);
		assertTrue(SettlementForaging.canForage(data, settlement));
	}

	@Test void unmarkedAndCancelledTargetsNeverReadWorld() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		assertFalse(SettlementCropHarvest.validTarget(data, settlement, BlockPos.ZERO,
			() -> { fail("No world access for unmarked crop"); return true; }, () -> true,
			() -> ((CropBlock) Blocks.POTATOES).getStateForAge(7)));
		data.toggleResource(settlement, BlockPos.ZERO);
		assertTrue(valid(data, settlement, 7));
		data.toggleResource(settlement, BlockPos.ZERO);
		assertFalse(valid(data, settlement, 7));
	}

	@Test void unloadedOrNonTickingMarkedPlotNeverReadsBlockOrRemovesMark() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.toggleResource(settlement, BlockPos.ZERO);
		for (boolean loaded : List.of(false, true)) {
			assertFalse(SettlementCropHarvest.validTarget(data, settlement, BlockPos.ZERO, () -> loaded,
				() -> { assertTrue(loaded); return false; },
				() -> { fail("Block must not be read"); return Blocks.AIR.defaultBlockState(); }));
			assertTrue(data.resourcesOf(settlement).contains(BlockPos.ZERO));
		}
	}

	@Test void targetRechecksMaturityWithoutLosingPlot() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.toggleResource(settlement, BlockPos.ZERO);
		assertTrue(valid(data, settlement, 7));
		assertFalse(valid(data, settlement, 0));
		assertTrue(data.resourcesOf(settlement).contains(BlockPos.ZERO));
		assertTrue(valid(data, settlement, 7));
	}

	@Test void targetRechecksAuthorityBeforeWorldRead() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.toggleResource(settlement, BlockPos.ZERO);
		assertTrue(valid(data, settlement, 7));
		var state = data.getOrCreateSimulation(settlement);
		state.setSurvivalEnabled(true, 0);
		assertFalse(SettlementCropHarvest.validTarget(data, settlement, BlockPos.ZERO,
			() -> { fail("Disabled food work must not read world"); return true; }, () -> true,
			() -> ((CropBlock) Blocks.POTATOES).getStateForAge(7)));
		state.setProductionEnabled(true);
		assertTrue(valid(data, settlement, 7));
		state.setProductionEnabled(false);
		assertFalse(valid(data, settlement, 7));
	}

	private static boolean valid(SettlementSavedData data, Settlement settlement, int age) {
		return SettlementCropHarvest.validTarget(data, settlement, BlockPos.ZERO, () -> true, () -> true,
			() -> ((CropBlock) Blocks.POTATOES).getStateForAge(age));
	}

	@Test void netPotatoesUseExistingProductionServiceAndSurviveReload() {
		for (boolean simulation : List.of(false, true)) {
			SettlementSavedData data = new SettlementSavedData();
			Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
			UUID actor = new UUID(23, 1);
			data.registerMember(settlement, actor, 0);
			var state = data.getOrCreateSimulation(settlement);
			state.mapResident(actor, false, 0);
			state.setSurvivalEnabled(simulation, 0);
			state.setProductionEnabled(simulation);
			state.setSkillsEnabled(simulation);
			SimpleContainer inventory = new SimpleContainer(2);
			var output = SettlementCropHarvest.outputAfterSeed(List.of(new ItemStack(Items.POTATO, 5), new ItemStack(Items.POISONOUS_POTATO))).orElseThrow();
			assertTrue(SettlementHarvestCommit.commit(inventory, output, () -> true));
			assertEquals(simulation ? 4 : 0, SettlementSimulationService.recordProduction(data, settlement, actor, inventory, Items.POTATO, 4, "harvest_crop"));
			assertEquals(1, inventory.countItem(Items.POISONOUS_POTATO));
			assertEquals(simulation ? 0 : 4, inventory.countItem(Items.POTATO));
			assertEquals(simulation ? 1 : 0, state.world().ledger().events().stream().filter(event -> event.type() == EventType.SKILL_PRACTICED).count());
			for (int round = 0; round < 2; round++) {
				CompoundTag saved = data.save(new CompoundTag(), null);
				data = SettlementSavedData.load(saved, null);
				assertEquals(saved, data.save(new CompoundTag(), null));
				state = data.simulationOf(settlement).orElseThrow();
				assertEquals(4, inventory.countItem(Items.POTATO) + state.world().inventoryTotals().getOrDefault(ResourceType.TUBERS, 0L));
				state.world().assertConservation();
			}
		}
	}
}
