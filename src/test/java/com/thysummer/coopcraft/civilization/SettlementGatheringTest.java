package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production execution seam, not a real World, Goal, pathfinder or loot table. */
class SettlementGatheringTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Attempt {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Gather", 0);
		final UUID actor = new UUID(31, 1);
		BlockPos pos = new BlockPos(1, 0, 0);
		final SimpleContainer inventory = new SimpleContainer(1);
		boolean adult = true, loaded = true, ticking = true, belowLoaded = true, destroySucceeds = true;
		int loadedReads, tickingReads, blockReads, lootReads, destroys, spilled;
		BlockState block = Blocks.STONE.defaultBlockState(), below = Blocks.DIRT.defaultBlockState();
		Runnable afterLoot = () -> {};
		Attempt() { data.registerMember(settlement, actor, 0); data.toggleResource(settlement, pos); }
		SettlementSimulationState simulation() { return data.getOrCreateSimulation(settlement); }
		void enableSimulation() {
			var state = simulation(); state.mapResident(actor, false, 0);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true); state.setSkillsEnabled(true);
		}
		int run(int... counts) {
			return SettlementGathering.harvest(data, settlement, actor, pos, inventory, () -> adult,
				() -> { loadedReads++; return loaded; }, () -> { tickingReads++; return ticking; },
				() -> { blockReads++; return block; }, () -> belowLoaded, () -> below,
				state -> { assertEquals(block, state); lootReads++; afterLoot.run();
					return java.util.Arrays.stream(counts).mapToObj(count -> new ItemStack(Items.COBBLESTONE, count)).toList(); },
				state -> { destroys++; if (!destroySucceeds) return false; block = Blocks.AIR.defaultBlockState(); return true; },
				stack -> spilled += stack.getCount());
		}
		void assertUntouched() { assertEquals(0, destroys); assertTrue(inventory.isEmpty()); assertEquals(0, spilled); }
	}

	@Test void cancelledMarkStopsBeforeWorldReads() {
		Attempt a = new Attempt(); a.enableSimulation(); a.data.removeResource(a.settlement, a.pos);
		CompoundTag before = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run(1)); a.assertUntouched(); assertEquals(0, a.loadedReads);
		assertEquals(before, a.data.save(new CompoundTag(), null));
		a.simulation().world().assertConservation();
	}
	@Test void cancellationDuringLootNeverCommitsAndRemarkedExecutionSucceeds() {
		Attempt a = new Attempt(); a.enableSimulation();
		var cancelled = new java.util.concurrent.atomic.AtomicReference<CompoundTag>();
		a.afterLoot = () -> { a.data.removeResource(a.settlement, a.pos); cancelled.set(a.data.save(new CompoundTag(), null)); };
		assertEquals(0, a.run(3)); a.assertUntouched(); assertEquals(Blocks.STONE.defaultBlockState(), a.block);
		assertEquals(cancelled.get(), a.data.save(new CompoundTag(), null));
		a.simulation().world().assertConservation();
		assertTrue(a.simulation().world().batchesAt(a.simulation().campOwner()).isEmpty());
		a.data.toggleResource(a.settlement, a.pos); a.afterLoot = () -> {};
		assertEquals(3, a.run(3)); assertEquals(1, a.destroys); assertTrue(a.inventory.isEmpty());
		assertEquals(3, a.simulation().world().batchesAt(a.simulation().campOwner()).stream().mapToLong(b -> b.quantity()).sum());
		a.simulation().world().assertConservation();
		assertFalse(a.data.resourcesOf(a.settlement).contains(a.pos));
	}
	@Test void authorityAndActorFailuresCannotReadWorldOrCleanMarks() {
		for (int failure = 0; failure < 5; failure++) {
			Attempt a = new Attempt();
			switch (failure) {
				case 0 -> a.adult = false;
				case 1 -> a.settlement = new Settlement(a.settlement.id(), a.settlement.center(), a.settlement.name(), 0);
				case 2 -> { a.pos = new BlockPos(60, 0, 0); a.data.toggleResource(a.settlement, a.pos);
					a.data.getOrCreate(a.pos, "Other", 0); }
				case 3 -> { a.simulation().setSurvivalEnabled(true, 0); }
				case 4 -> { a.settlement = a.data.getOrCreate(new BlockPos(100, 0, 0), "Other", 0);
					a.pos = a.settlement.center(); a.data.toggleResource(a.settlement, a.pos); }
			}
			a.block = Blocks.AIR.defaultBlockState(); CompoundTag before = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(1)); a.assertUntouched(); assertEquals(0, a.loadedReads);
			assertEquals(before, a.data.save(new CompoundTag(), null));
		}
	}
	@Test void removedMemberDeadOrForeignCampResidentStopsBeforeWorldReads() {
		for (int failure = 0; failure < 3; failure++) {
			Attempt a = new Attempt(); a.enableSimulation();
			var state = a.simulation();
			var person = state.world().person(state.residentId(a.actor).orElseThrow());
			switch (failure) {
				case 0 -> a.data.removeMember(a.actor);
				case 1 -> com.thysummer.coopcraft.civilization.simulation.FoodAccessTestPeople.markDead(person, 0);
				case 2 -> com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents.move(person, "other-camp");
			}
			CompoundTag before = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(2)); a.assertUntouched(); assertEquals(0, a.loadedReads);
			assertEquals(0, a.blockReads); assertEquals(0, a.lootReads);
			assertEquals(before, a.data.save(new CompoundTag(), null));
			assertTrue(a.data.resourcesOf(a.settlement).contains(a.pos));
			state.world().assertConservation();
		}
	}

	@Test void unloadedOrNonTickingNeverReadBlockOrLoot() {
		for (boolean loaded : List.of(false, true)) {
			Attempt a = new Attempt(); a.loaded = loaded; a.ticking = false;
			assertEquals(0, a.run(1)); a.assertUntouched(); assertEquals(0, a.blockReads); assertEquals(0, a.lootReads);
			assertEquals(loaded ? 1 : 0, a.tickingReads); assertTrue(a.data.resourcesOf(a.settlement).contains(a.pos));
		}
	}
	@Test void changesDuringLootRecheckWorldStateAndAuthority() {
		for (int failure = 0; failure < 6; failure++) {
			Attempt a = new Attempt(); final int condition = failure;
			if (failure == 5) { a.pos = new BlockPos(40, 0, 0); a.data.toggleResource(a.settlement, a.pos); }
			a.afterLoot = () -> { switch (condition) {
				case 0 -> a.adult = false; case 1 -> a.loaded = false; case 2 -> a.ticking = false;
				case 3 -> a.block = Blocks.COBBLESTONE.defaultBlockState();
				case 4 -> a.simulation().setSurvivalEnabled(true, 0);
				case 5 -> a.data.getOrCreate(new BlockPos(60, 0, 0), "New owner", 0);
			} };
			assertEquals(0, a.run(2)); a.assertUntouched(); assertEquals(1, a.lootReads);
			assertTrue(a.data.resourcesOf(a.settlement).contains(a.pos));
		}
	}
	@Test void nextDaySocialBudgetExhaustionDuringLootStopsCommit() {
		Attempt a = new Attempt(); a.enableSimulation(); a.simulation().setNormsEnabled(true);
		a.afterLoot = () -> a.simulation().world().person(a.simulation().residentId(a.actor).orElseThrow()).restoreSocialState(0, 0, 6);
		assertEquals(0, a.run(3)); a.assertUntouched(); assertTrue(a.data.resourcesOf(a.settlement).contains(a.pos));
		assertTrue(a.simulation().world().batchesAt(a.simulation().campOwner()).isEmpty());
	}
	@Test void invalidKnownWoodStoneMarkCanBeCleanedButCropPlotsCannot() {
		for (BlockState block : List.of(Blocks.POTATOES.defaultBlockState(), Blocks.FARMLAND.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
			Attempt a = new Attempt(); a.block = block; a.below = Blocks.FARMLAND.defaultBlockState();
			assertEquals(0, a.run(1)); a.assertUntouched(); assertTrue(a.data.resourcesOf(a.settlement).contains(a.pos));
		}
		Attempt unknown = new Attempt(); unknown.block = Blocks.AIR.defaultBlockState(); unknown.belowLoaded = false;
		assertEquals(0, unknown.run(1)); assertTrue(unknown.data.resourcesOf(unknown.settlement).contains(unknown.pos));
		Attempt obsolete = new Attempt(); obsolete.block = Blocks.DIRT.defaultBlockState();
		assertEquals(0, obsolete.run(1)); obsolete.assertUntouched(); assertFalse(obsolete.data.resourcesOf(obsolete.settlement).contains(obsolete.pos));
	}
	@Test void capacityAndDestroyFailureLeaveAllPersistedStateUntouched() {
		for (boolean capacity : List.of(false, true)) {
			Attempt a = new Attempt(); a.enableSimulation(); a.destroySucceeds = capacity;
			if (capacity) a.inventory.setItem(0, new ItemStack(Items.DIRT, 64));
			CompoundTag before = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(1)); assertEquals(before, a.data.save(new CompoundTag(), null));
			assertEquals(capacity ? 0 : 1, a.destroys); assertEquals(0, a.spilled);
		}
	}
	@Test void originalPerStackCapacityAndOverflowPolicyConservesDrops() {
		Attempt a = new Attempt(); a.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
		assertEquals(8, a.run(4, 4)); assertEquals(64, a.inventory.countItem(Items.COBBLESTONE)); assertEquals(4, a.spilled);
	}
	@Test void productionImportsEachDropBeforeNextInsertionWithoutArtificialOverflow() {
		Attempt a = new Attempt(); a.enableSimulation();
		a.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
		assertEquals(8, a.run(4, 4)); assertEquals(60, a.inventory.countItem(Items.COBBLESTONE));
		assertEquals(0, a.spilled);
		assertEquals(8, a.simulation().world().batchesAt(a.simulation().campOwner()).stream().mapToLong(b -> b.quantity()).sum());
		a.simulation().world().assertConservation();
	}

	@Test void legacyMarksRoundTripWithoutNewPersistenceAndCancelledMarksStayCancelled() {
		Attempt a = new Attempt(); CompoundTag before = a.data.save(new CompoundTag(), null);
		a.data = SettlementSavedData.load(before, null); a.settlement = a.data.find(BlockPos.ZERO).orElseThrow();
		assertEquals(before, a.data.save(new CompoundTag(), null)); assertEquals(2, a.run(2));
		assertEquals(2, a.inventory.countItem(Items.COBBLESTONE));
		a.data = SettlementSavedData.load(a.data.save(new CompoundTag(), null), null);
		a.settlement = a.data.find(BlockPos.ZERO).orElseThrow(); a.block = Blocks.STONE.defaultBlockState();
		assertEquals(0, a.run(1)); assertEquals(1, a.destroys);
	}
}
