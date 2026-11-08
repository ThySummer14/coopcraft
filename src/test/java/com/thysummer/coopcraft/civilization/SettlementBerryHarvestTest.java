package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
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
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Calls the production harvesting entry; no real world, loot evaluation, entity or Goal execution. */
class SettlementBerryHarvestTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Attempt {
		SettlementSavedData data = new SettlementSavedData();
		final Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Berries", 0);
		final UUID actor = new UUID(24, 1);
		final SimpleContainer inventory;
		boolean adult = true, loaded = true, ticking = true, writeSucceeds = true;
		int loadedReads, tickingReads, blockReads, lootReads, writes;
		BlockState block = bush(3);
		Runnable afterLoot = () -> {};
		Runnable beforeWrite = () -> {};
		Attempt(int slots) { inventory = new SimpleContainer(slots); data.registerMember(settlement, actor, 0); }
		SettlementSimulationState simulation() { return data.getOrCreateSimulation(settlement); }
		void enableSimulation() {
			var state = simulation(); state.mapResident(actor, false, 0);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true); state.setSkillsEnabled(true);
		}
		int run(ItemStack... drops) {
			return SettlementForaging.harvest(data, settlement, actor, inventory, () -> adult,
				() -> { loadedReads++; return loaded; }, () -> { tickingReads++; return ticking; },
				() -> { blockReads++; return block; }, () -> { lootReads++; afterLoot.run(); return List.of(drops); },
				mature -> { writes++; beforeWrite.run(); if (!writeSucceeds) return false;
					block = mature.setValue(SweetBerryBushBlock.AGE, 1); return true; });
		}
	}
	private static BlockState bush(int age) { return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, age); }
	private static ItemStack berries(int count) { return new ItemStack(Items.SWEET_BERRIES, count); }
	private static ItemStack custom(int count) {
		ItemStack stack = berries(count); stack.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me")); return stack;
	}

	@Test void completeBatchMustFitBeforeWorldMutation() {
		Attempt a = new Attempt(1);
		assertEquals(0, a.run(berries(3), new ItemStack(Items.POISONOUS_POTATO)));
		assertTrue(a.inventory.isEmpty()); assertEquals(0, a.writes); assertEquals(bush(3), a.block);
	}
	@Test void multipleStacksMergeOnlyAfterSuccessfulWorldWrite() {
		Attempt a = new Attempt(2); a.inventory.setItem(0, berries(60));
		ItemStack first = berries(2), second = berries(63);
		a.beforeWrite = () -> assertEquals(60, a.inventory.countItem(Items.SWEET_BERRIES));
		assertEquals(67, a.run(first, second, berries(2)));
		assertEquals(127, a.inventory.countItem(Items.SWEET_BERRIES));
		assertEquals(2, first.getCount()); assertEquals(63, second.getCount()); assertEquals(bush(1), a.block);
	}
	@Test void exactFullStackFitsButOneMoreCannotFit() {
		Attempt a = new Attempt(1); a.inventory.setItem(0, berries(60));
		assertEquals(4, a.run(berries(4))); a.block = bush(3);
		assertEquals(0, a.run(berries(1))); assertEquals(1, a.writes);
		assertEquals(64, a.inventory.countItem(Items.SWEET_BERRIES)); assertEquals(bush(3), a.block);
	}
	@Test void componentAndPoisonByproductsKeepTheirExactStacks() {
		Attempt a = new Attempt(3); ItemStack named = custom(2);
		assertEquals(6, a.run(named, new ItemStack(Items.POISONOUS_POTATO), berries(3)));
		assertTrue(ItemStack.matches(named, a.inventory.getItem(0)));
		assertEquals(1, a.inventory.countItem(Items.POISONOUS_POTATO)); assertEquals(3, a.inventory.getItem(2).getCount());
	}
	@Test void customComponentsCannotMergeIntoOrdinaryStackForCapacity() {
		Attempt a = new Attempt(1); a.inventory.setItem(0, berries(60));
		assertEquals(0, a.run(custom(1))); assertEquals(0, a.writes);
		assertEquals(60, a.inventory.getItem(0).getCount()); assertEquals(bush(3), a.block);
	}
	@Test void emptyLootRetainsExistingRegrowthWithoutProduction() {
		Attempt a = new Attempt(0); a.enableSimulation(); var saved = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run()); assertEquals(1, a.writes); assertEquals(bush(1), a.block);
		assertTrue(a.inventory.isEmpty()); assertEquals(saved, a.data.save(new CompoundTag(), null));
	}

	@Test void worldWriteFailureLeavesInventoryAndProductionUntouched() {
		Attempt a = new Attempt(2); a.enableSimulation(); a.inventory.setItem(0, berries(60));
		a.writeSucceeds = false; var saved = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run(berries(4))); assertEquals(1, a.writes);
		assertEquals(60, a.inventory.countItem(Items.SWEET_BERRIES)); assertEquals(bush(3), a.block);
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}
	@Test void forbiddenModeShortCircuitsWorldAndLoot() {
		Attempt a = new Attempt(1); a.simulation().setSurvivalEnabled(true, 0);
		assertEquals(0, a.run(berries(3))); assertEquals(0, a.loadedReads); assertEquals(0, a.lootReads);
	}
	@Test void unloadedAndNonTickingTargetsNeverReadBlocksOrLoot() {
		for (boolean loaded : List.of(false, true)) {
			Attempt a = new Attempt(1); a.loaded = loaded; a.ticking = false;
			assertEquals(0, a.run(berries(3))); assertEquals(loaded ? 1 : 0, a.tickingReads);
			assertEquals(0, a.blockReads); assertEquals(0, a.lootReads); assertEquals(0, a.writes);
		}
	}
	@Test void babiesAndUnmappedSimulatedResidentsNeverReadWorld() {
		Attempt baby = new Attempt(1); baby.adult = false;
		assertEquals(0, baby.run(berries(3))); assertEquals(0, baby.loadedReads);
		Attempt unmapped = new Attempt(1); unmapped.simulation().setSurvivalEnabled(true, 0); unmapped.simulation().setProductionEnabled(true);
		assertEquals(0, unmapped.run(berries(3))); assertEquals(0, unmapped.loadedReads);
	}
	@Test void immatureAndNonBushTargetsNeverEvaluateLoot() {
		for (BlockState block : List.of(bush(0), bush(1), Blocks.AIR.defaultBlockState())) {
			Attempt a = new Attempt(1); a.block = block;
			assertEquals(0, a.run(berries(3))); assertEquals(0, a.lootReads); assertEquals(0, a.writes);
		}
	}
	@Test void bothRipeAgesRegrowAtAgeOne() {
		for (int age : List.of(2, 3)) {
			Attempt a = new Attempt(1); a.block = bush(age);
			assertEquals(3, a.run(berries(3))); assertEquals(bush(1), a.block);
			assertEquals(0, a.run(berries(3))); assertEquals(3, a.inventory.countItem(Items.SWEET_BERRIES));
		}
	}
	@Test void productionDisabledDuringLootPreventsCommit() {
		Attempt a = new Attempt(1); a.enableSimulation();
		a.afterLoot = () -> a.simulation().setProductionEnabled(false);
		assertEquals(0, a.run(berries(3))); assertEquals(0, a.writes); assertTrue(a.inventory.isEmpty());
	}
	@Test void survivalEnabledWithoutProductionDuringLootPreventsCommit() {
		Attempt a = new Attempt(1); a.afterLoot = () -> a.simulation().setSurvivalEnabled(true, 0);
		assertEquals(0, a.run(berries(3))); assertEquals(0, a.writes); assertTrue(a.inventory.isEmpty());
	}
	@Test void eligibilityAndExactMatureStateAreRecheckedAfterLoot() {
		for (int condition = 0; condition < 5; condition++) {
			Attempt a = new Attempt(1); final int change = condition;
			a.afterLoot = () -> { switch (change) {
				case 0 -> a.adult = false; case 1 -> a.loaded = false; case 2 -> a.ticking = false;
				case 3 -> a.block = bush(1); case 4 -> a.block = bush(2);
			} };
			assertEquals(0, a.run(berries(3))); assertEquals(0, a.writes); assertTrue(a.inventory.isEmpty());
		}
	}
	@Test void mappingIsRecheckedAfterLoot() {
		Attempt b = new Attempt(1);
		b.afterLoot = () -> { b.simulation().setSurvivalEnabled(true, 0); b.simulation().setProductionEnabled(true); };
		assertEquals(0, b.run(berries(3))); assertEquals(0, b.writes); assertTrue(b.inventory.isEmpty());
	}
	@Test void customOnlyLootDoesNotImportOldOrdinaryFoodOrPractice() {
		Attempt a = new Attempt(3); a.enableSimulation(); a.inventory.setItem(0, berries(5));
		var saved = a.data.save(new CompoundTag(), null);
		assertEquals(3, a.run(custom(2), new ItemStack(Items.POISONOUS_POTATO)));
		assertEquals(5, a.inventory.getItem(0).getCount()); assertTrue(ItemStack.matches(custom(2), a.inventory.getItem(1)));
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}
	@Test void simulationCapacityFailurePreservesAllPersistedState() {
		Attempt a = new Attempt(1); a.enableSimulation(); var saved = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run(berries(3), new ItemStack(Items.POISONOUS_POTATO)));
		assertEquals(0, a.writes); assertTrue(a.inventory.isEmpty()); assertEquals(bush(3), a.block);
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}
	@Test void nonBerryByproductsNeverImportOrPracticeEvenIfMapped() {
		Attempt a = new Attempt(2); a.enableSimulation(); var saved = a.data.save(new CompoundTag(), null);
		assertEquals(3, a.run(new ItemStack(Items.POISONOUS_POTATO), new ItemStack(Items.POTATO, 2)));
		assertEquals(1, a.inventory.countItem(Items.POISONOUS_POTATO)); assertEquals(2, a.inventory.countItem(Items.POTATO));
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}

	@Test void ordinaryProductionKeepsOldCustomStacksAndUsesOneAuthorityAfterReload() {
		for (boolean simulation : List.of(false, true)) {
			Attempt a = new Attempt(4); a.simulation(); if (simulation) a.enableSimulation();
			a.inventory.setItem(0, custom(4));
			assertEquals(7, a.run(berries(2), berries(3), custom(1), new ItemStack(Items.POISONOUS_POTATO)));
			assertTrue(ItemStack.matches(custom(5), a.inventory.getItem(0)));
			assertEquals(simulation ? 5 : 10, a.inventory.countItem(Items.SWEET_BERRIES));
			assertEquals(1, a.inventory.countItem(Items.POISONOUS_POTATO));
			for (int round = 0; round < 3; round++) {
				CompoundTag saved = a.data.save(new CompoundTag(), null); a.data = SettlementSavedData.load(saved, null);
				assertEquals(saved, a.data.save(new CompoundTag(), null));
				var state = a.data.simulationOf(a.settlement).orElse(null);
				long abstractBerries = state == null ? 0 : state.world().inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L);
				assertEquals(simulation ? 5 : 0, abstractBerries);
				assertEquals(10, abstractBerries + a.inventory.countItem(Items.SWEET_BERRIES));
				if (state != null) {
					assertEquals(simulation ? 1 : 0, state.world().ledger().events().stream().filter(e -> e.type() == EventType.SKILL_PRACTICED).count());
					state.world().assertConservation();
				}
			}
		}
	}
}
