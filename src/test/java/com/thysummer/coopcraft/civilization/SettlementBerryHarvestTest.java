package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); registries = VanillaRegistries.createLookup();
	}

	private static final class Attempt {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Berries", 0);
		UUID actor = new UUID(24, 1);
		final SimpleContainer inventory;
		boolean adult = true, loaded = true, ticking = true, writeSucceeds = true;
		int loadedReads, tickingReads, blockReads, lootReads, writes;
		BlockState block = bush(3);
		Runnable afterLoot = () -> {};
		Runnable beforeWrite = () -> {};
		Runnable duringPlanning = () -> {};
		boolean changeDuringPlanning;
		Attempt(int slots) {
			inventory = new SimpleContainer(slots) {
				@Override public ItemStack getItem(int slot) {
					if (changeDuringPlanning) { changeDuringPlanning = false; duringPlanning.run(); }
					return super.getItem(slot);
				}
			};
			data.registerMember(settlement, actor, 0);
			assertEquals(SettlementForagingAreas.Status.CREATED,
				data.permitForagingArea(settlement, actor, BlockPos.ZERO, 1).status());
		}
		SettlementSimulationState simulation() { return data.getOrCreateSimulation(settlement); }
		void enableSimulation() {
			var state = simulation(); state.mapResident(actor, false, 0);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true); state.setSkillsEnabled(true);
		}
		int run(ItemStack... drops) {
			return SettlementForaging.harvest(data, settlement, actor, BlockPos.ZERO, inventory, () -> adult,
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

	@Test void onlyNewPlainBerriesReceiveCapturedClockAndDayWithoutImportingOldBagFood() {
		Attempt a = new Attempt(4); a.enableSimulation();
		for (int day = 0; day < 7; day++) a.simulation().world().advanceDay();
		ItemStack old = berries(11), source = berries(5);
		a.inventory.setItem(0, old.copy());
		Tag before = source.save(registries);
		assertEquals(8, a.run(source, custom(2), new ItemStack(Items.POTATO)));
		assertTrue(ItemStack.matches(old, a.inventory.getItem(0)));
		assertTrue(a.inventory.getItem(0).getComponentsPatch().isEmpty());
		ItemStack cargo = a.inventory.getItem(1);
		assertEquals(5, cargo.getCount());
		var stamp = SettlementFoodAge.read(cargo).orElseThrow();
		assertEquals(a.simulation().foodClockId().orElseThrow(), stamp.clockId());
		assertEquals(ResourceType.WILD_FRUIT, stamp.resourceType());
		assertEquals(7, stamp.harvestDay()); assertEquals(10, stamp.expiryDay());
		assertTrue(ItemStack.matches(custom(2), a.inventory.getItem(2)));
		assertTrue(a.inventory.getItem(3).getComponentsPatch().isEmpty());
		assertEquals(before, source.save(registries));
		assertEquals(0L, a.simulation().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
		var events = a.simulation().world().ledger().events();
		assertEquals(1, events.size()); assertEquals(EventType.SKILL_PRACTICED, events.getFirst().type());
		assertTrue(events.getFirst().causalLinks().isEmpty());
		assertEquals("forage", events.getFirst().context().get("activity"));
		a.simulation().world().assertConservation();
	}

	@Test void matchingCargoMergesButFullDifferentAgeClockPlainOrCustomCargoCannotFit() {
		Attempt same = new Attempt(1); same.enableSimulation();
		UUID clock = same.simulation().foodClockId().orElseThrow();
		same.inventory.setItem(0, SettlementFoodAge.stamp(berries(60), clock, 0));
		assertEquals(4, same.run(berries(4))); assertEquals(64, same.inventory.getItem(0).getCount());
		assertEquals(clock, SettlementFoodAge.read(same.inventory.getItem(0)).orElseThrow().clockId());
		for (int identity = 0; identity < 4; identity++) {
			Attempt a = new Attempt(1); a.enableSimulation(); a.simulation().world().advanceDay();
			ItemStack prior = switch (identity) {
				case 0 -> berries(60);
				case 1 -> SettlementFoodAge.stamp(berries(60), a.simulation().foodClockId().orElseThrow(), 0);
				case 2 -> SettlementFoodAge.stamp(berries(60), new UUID(24, 99), 1);
				default -> custom(60);
			};
			a.inventory.setItem(0, prior.copy());
			var saved = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(berries(4))); assertEquals(0, a.writes);
			assertTrue(ItemStack.matches(prior, a.inventory.getItem(0)));
			assertEquals(bush(3), a.block); assertEquals(saved, a.data.save(new CompoundTag(), null));
		}
	}

	@Test void differentHarvestDaysUseSeparatePhysicalSlotsWithoutRefreshingEarlierCargo() {
		Attempt a = new Attempt(2); a.enableSimulation();
		assertEquals(3, a.run(berries(3)));
		Tag earlier = a.inventory.getItem(0).save(registries);
		a.simulation().world().advanceDay(); a.block = bush(3);
		assertEquals(2, a.run(berries(2)));
		assertEquals(earlier, a.inventory.getItem(0).save(registries));
		assertEquals(0, SettlementFoodAge.read(a.inventory.getItem(0)).orElseThrow().harvestDay());
		assertEquals(1, SettlementFoodAge.read(a.inventory.getItem(1)).orElseThrow().harvestDay());
		assertFalse(ItemStack.isSameItemSameComponents(a.inventory.getItem(0), a.inventory.getItem(1)));
	}

	@Test void existingMarkedLootAndNonBerryOutputsAreNeverRestampedOrPracticed() {
		Attempt a = new Attempt(4); a.enableSimulation();
		ItemStack marked = SettlementFoodAge.stamp(berries(2), new UUID(24, 8), 4);
		var saved = a.data.save(new CompoundTag(), null);
		assertEquals(6, a.run(marked, custom(2), new ItemStack(Items.POTATO), new ItemStack(Items.POISONOUS_POTATO)));
		assertTrue(ItemStack.matches(marked, a.inventory.getItem(0)));
		assertTrue(ItemStack.matches(custom(2), a.inventory.getItem(1)));
		assertTrue(a.inventory.getItem(2).getComponentsPatch().isEmpty());
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}

	@Test void skillsOffLeavesAllSimulationStateUnchangedAfterSuccessfulPhysicalHarvest() {
		Attempt a = new Attempt(2); a.enableSimulation(); a.simulation().setSkillsEnabled(false);
		a.inventory.setItem(0, berries(7));
		var saved = a.data.save(new CompoundTag(), null);
		assertEquals(3, a.run(berries(3)));
		assertEquals(7, a.inventory.getItem(0).getCount()); assertEquals(3, a.inventory.getItem(1).getCount());
		assertTrue(SettlementFoodAge.isMarked(a.inventory.getItem(1)));
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}

	@Test void disabledAndRoundTripModesRejectPreviouslyCapturedHarvests() {
		for (boolean survival : List.of(false, true)) {
			for (int change = 0; change < 3; change++) {
				Attempt a = new Attempt(1); a.simulation(); if (survival) a.enableSimulation();
				final int mutation = change;
				a.afterLoot = () -> { switch (mutation) {
					case 0 -> a.simulation().setSurvivalEnabled(!survival, 0);
					case 1 -> { a.simulation().setSurvivalEnabled(!survival, 0); a.simulation().setSurvivalEnabled(survival, 0); }
					case 2 -> { a.simulation().setProductionEnabled(!survival); a.simulation().setProductionEnabled(survival); }
				} };
				assertEquals(0, a.run(berries(3))); assertEquals(0, a.writes);
				assertEquals(bush(3), a.block); assertTrue(a.inventory.isEmpty());
				assertTrue(a.simulation().world().ledger().events().isEmpty());
			}
		}
	}

	@Test void newSimulationEvenWithModesOffCannotRetroactivelyReplaceCapturedState() {
		Attempt a = new Attempt(1); assertTrue(a.data.simulationOf(a.settlement).isEmpty());
		a.afterLoot = a::simulation;
		assertEquals(0, a.run(berries(3))); assertEquals(0, a.writes); assertTrue(a.inventory.isEmpty());
	}

	@Test void dayAndResidentEligibilityAreRecheckedAfterLootAndInsideWorldCommitGate() {
		for (boolean duringPlanning : List.of(false, true)) {
			for (int change = 0; change < 8; change++) {
				Attempt a = new Attempt(1); a.enableSimulation();
				final int mutation = change;
				Runnable mutate = () -> { switch (mutation) {
					case 0 -> a.simulation().world().advanceDay();
					case 1 -> a.adult = false;
					case 2 -> a.data.removeMember(a.actor);
					case 3 -> { a.data.removeMember(a.actor); a.data.registerMember(a.settlement, a.actor, 0); }
					case 4 -> { a.simulation().setProductionEnabled(false); a.simulation().setProductionEnabled(true); }
					case 5 -> FoodDayTestResidents.kill(a.simulation().world().person(a.simulation().residentId(a.actor).orElseThrow()));
					case 6 -> FoodDayTestResidents.move(a.simulation().world().person(a.simulation().residentId(a.actor).orElseThrow()), "other-camp");
					case 7 -> { a.simulation().setSurvivalEnabled(false, 0); a.simulation().setSurvivalEnabled(true, 0); }
				} };
				if (duringPlanning) { a.duringPlanning = mutate; a.changeDuringPlanning = true; }
				else a.afterLoot = mutate;
				assertEquals(0, a.run(berries(3))); assertEquals(0, a.writes);
				assertEquals(bush(3), a.block); assertTrue(a.inventory.isEmpty());
				assertTrue(a.simulation().world().ledger().events().isEmpty());
			}
		}
	}

	@Test void deadAndForeignCampMappedResidentsCannotReadWorldOrLoot() {
		for (boolean dead : List.of(false, true)) {
			Attempt a = new Attempt(1); a.enableSimulation();
			var person = a.simulation().world().person(a.simulation().residentId(a.actor).orElseThrow());
			if (dead) FoodDayTestResidents.kill(person); else FoodDayTestResidents.move(person, "other-camp");
			var saved = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(berries(3))); assertEquals(0, a.loadedReads); assertEquals(0, a.blockReads);
			assertEquals(0, a.lootReads); assertEquals(0, a.writes); assertTrue(a.inventory.isEmpty());
			assertEquals(saved, a.data.save(new CompoundTag(), null));
		}
	}

	@Test void unavailableFoodClockRejectsSurvivalBeforeWorldReadsButOrdinaryModeStillWorks() {
		for (boolean survival : List.of(false, true)) {
			Attempt a = new Attempt(1); a.enableSimulation();
			a.simulation().setSurvivalEnabled(survival, 0);
			CompoundTag saved = a.data.save(new CompoundTag(), null);
			saved.getList("Simulations", Tag.TAG_COMPOUND).getCompound(0).putString("FoodClockId", "broken clock");
			a.data = SettlementSavedData.load(saved, null);
			a.settlement = a.data.find(BlockPos.ZERO).orElseThrow();
			assertTrue(a.simulation().foodClockId().isEmpty());
			assertEquals(survival ? 0 : 3, a.run(berries(3)));
			assertEquals(survival ? 0 : 1, a.writes);
			if (survival) { assertEquals(0, a.loadedReads); assertEquals(0, a.blockReads); assertEquals(0, a.lootReads); }
			else assertTrue(a.inventory.getItem(0).getComponentsPatch().isEmpty());
			assertEquals(saved, a.data.save(new CompoundTag(), null));
		}
	}

	@Test void reloadPreservesStampedCargoAndFreshHarvestUsesSameClockWithoutRevivingCapturedAttempt() {
		Attempt a = new Attempt(2); a.enableSimulation();
		var capture = SettlementBerryCargoHarvest.capture(a.data, a.settlement, a.actor);
		assertEquals(2, a.run(berries(2)));
		var clock = a.simulation().foodClockId().orElseThrow();
		for (int round = 0; round < 3; round++) {
			Tag cargo = a.inventory.getItem(0).save(registries);
			CompoundTag saved = a.data.save(new CompoundTag(), null);
			a.data = SettlementSavedData.load(saved, null);
			a.settlement = a.data.find(BlockPos.ZERO).orElseThrow();
			a.inventory.setItem(0, ItemStack.parse(registries, cargo).orElseThrow());
			assertEquals(saved, a.data.save(new CompoundTag(), null));
			assertEquals(cargo, a.inventory.getItem(0).save(registries));
			assertEquals(clock, SettlementFoodAge.read(a.inventory.getItem(0)).orElseThrow().clockId());
			assertFalse(capture.valid(a.data, a.settlement, a.actor, () -> a.adult));
		}
		a.block = bush(3); assertEquals(3, a.run(berries(3)));
		assertEquals(5, a.inventory.getItem(0).getCount()); assertTrue(a.inventory.getItem(1).isEmpty());
		assertEquals(clock, SettlementFoodAge.read(a.inventory.getItem(0)).orElseThrow().clockId());
		assertEquals(0L, a.simulation().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
	}

	@Test void harvestedBerriesRemainPhysicalAcrossReloadsWithoutImportingOldCustomStacks() {
		for (boolean simulation : List.of(false, true)) {
			Attempt a = new Attempt(4); a.simulation(); if (simulation) a.enableSimulation();
			a.inventory.setItem(0, custom(4));
			assertEquals(7, a.run(berries(2), berries(3), custom(1), new ItemStack(Items.POISONOUS_POTATO)));
			assertTrue(ItemStack.matches(custom(5), a.inventory.getItem(0)));
			assertEquals(10, a.inventory.countItem(Items.SWEET_BERRIES));
			assertEquals(simulation, SettlementFoodAge.isMarked(a.inventory.getItem(1)));
			assertEquals(1, a.inventory.countItem(Items.POISONOUS_POTATO));
			for (int round = 0; round < 3; round++) {
				CompoundTag saved = a.data.save(new CompoundTag(), null); a.data = SettlementSavedData.load(saved, null);
				a.settlement = a.data.find(BlockPos.ZERO).orElseThrow();
				assertEquals(saved, a.data.save(new CompoundTag(), null));
				var state = a.data.simulationOf(a.settlement).orElse(null);
				long abstractBerries = state == null ? 0 : state.world().inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L);
				assertEquals(0, abstractBerries);
				assertEquals(10, abstractBerries + a.inventory.countItem(Items.SWEET_BERRIES));
				if (state != null) {
					assertEquals(simulation ? 1 : 0, state.world().ledger().events().stream().filter(e -> e.type() == EventType.SKILL_PRACTICED).count());
					state.world().assertConservation();
				}
			}
		}
	}
	@Test void highReserveHarvestKeepsSurvivalCargoOutOfCommonStoreUntilArrival() {
		for (boolean simulation : List.of(false, true)) {
			Attempt a = new Attempt(4);
			for (UUID resident : List.of(new UUID(24, 2), new UUID(24, 3))) a.data.registerMember(a.settlement, resident, 0);
			// Model three loaded adults through the same allocator used by the Goal, without spawning entities.
			var workers = a.data.membersOf(a.settlement).stream()
				.map(member -> new LaborAllocator.Worker(member.villagerId(), member.personality())).toList();
			var roles = LaborAllocator.allocate(workers);
			a.actor = roles.entrySet().stream().filter(e -> e.getValue() == WorkRole.FORAGER).findFirst().orElseThrow().getKey();
			WorkRole role = roles.get(a.actor);
			if (simulation) {
				for (var worker : workers) a.simulation().mapResident(worker.id(), false, 80);
				a.simulation().setSurvivalEnabled(true, 0);
				a.simulation().setProductionEnabled(true);
				a.simulation().setSkillsEnabled(true);
				assertTrue(a.simulation().world().person(a.simulation().residentId(a.actor).orElseThrow()).hunger() < 65);
			}
			SimpleContainer chest = new SimpleContainer(2);
			if (simulation) a.simulation().world().importExternalBatch(ResourceType.WILD_FRUIT, 10, a.simulation().campOwner());
			else chest.setItem(0, berries(40));
			var before = a.data.save(new CompoundTag(), null);
			assertFalse(SettlementForaging.needsForage(true, role,
				() -> SettlementForaging.reserveLow(a.data, a.settlement, chest), 200, 200));
			assertEquals(0, a.lootReads); assertEquals(0, a.writes);
			assertEquals(before, a.data.save(new CompoundTag(), null));
			SettlementReserves.setTarget(a.data, a.settlement, FoodReserveTarget.HIGH);
			int harvests = simulation ? 2 : 3;
			for (int harvest = 0; harvest < harvests; harvest++) {
				assertTrue(SettlementForaging.needsForage(true, role,
					() -> SettlementForaging.reserveLow(a.data, a.settlement, chest), 200, 200));
				assertTrue(SettlementForaging.canForage(a.data, a.settlement));
				a.block = bush(3); // A separate ripe bush, ordinary three-berry loot.
				assertEquals(3, a.run(berries(3)));
				assertEquals(bush(1), a.block);
				if (!simulation) SettlementStockpile.depositItem(chest, a.inventory, Items.SWEET_BERRIES);
				assertEquals(simulation ? 3 * (harvest + 1) : 0, a.inventory.countItem(Items.SWEET_BERRIES));
			}
			assertEquals(harvests, a.writes);
			if (simulation) {
				assertEquals(10L, a.simulation().world().inventoryTotals().get(ResourceType.WILD_FRUIT).longValue());
				assertEquals(6, a.inventory.countItem(Items.SWEET_BERRIES));
				assertTrue(SettlementFoodAge.isMarked(a.inventory.getItem(0)));
				assertEquals(harvests, a.simulation().world().ledger().events().stream().filter(e -> e.type() == EventType.SKILL_PRACTICED).count());
				a.simulation().world().assertConservation();
			} else {
				assertEquals(49, chest.countItem(Items.SWEET_BERRIES));
				assertTrue(a.data.simulationOf(a.settlement).isEmpty());
			}
			assertEquals(simulation, SettlementForaging.needsForage(true, role,
				() -> SettlementForaging.reserveLow(a.data, a.settlement, chest), 200, 200));
			for (int reload = 0; reload < 2; reload++) {
				var saved = a.data.save(new CompoundTag(), null);
				a.data = SettlementSavedData.load(saved, null);
				a.settlement = a.data.find(BlockPos.ZERO).orElseThrow();
				assertEquals(saved, a.data.save(new CompoundTag(), null));
				assertEquals(FoodReserveTarget.HIGH, a.data.foodReserveTargetOf(a.settlement));
				if (simulation) a.data.simulationOf(a.settlement).orElseThrow().world().assertConservation();
			}
		}
	}
}
