package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents;
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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual crop entry with real containers and states, without a running world or loot tables. */
class SettlementPotatoHarvestTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static BlockState crop(int age) { return ((CropBlock) Blocks.POTATOES).getStateForAge(age); }
	private static ItemStack potatoes(int count) { return new ItemStack(Items.POTATO, count); }
	private static ItemStack custom() {
		ItemStack result = potatoes(2); result.set(DataComponents.CUSTOM_NAME, Component.literal("Keep")); return result;
	}
	private static final class Attempt {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Potatoes", 0);
		final UUID actor = new UUID(25, 1);
		final SimpleContainer inventory;
		BlockState block = crop(7);
		boolean adult = true, loaded = true, ticking = true, succeeds = true, planning;
		int writes, reads, loots;
		Runnable afterLoot = () -> {}, duringPlanning = () -> {};
		Attempt(int slots) {
			inventory = new SimpleContainer(slots) {
				@Override public ItemStack getItem(int slot) {
					if (planning) { planning = false; duringPlanning.run(); }
					return super.getItem(slot);
				}
			};
			data.registerMember(camp, actor, 0); data.toggleResource(camp, BlockPos.ZERO);
		}
		SettlementSimulationState state() { return data.getOrCreateSimulation(camp); }
		void enable() {
			state().mapResident(actor, false, 0); state().setSurvivalEnabled(true, 0);
			state().setProductionEnabled(true); state().setSkillsEnabled(true);
		}
		int run(ItemStack... loot) {
			return SettlementCropHarvest.harvest(data, camp, actor, BlockPos.ZERO, inventory, () -> adult,
				() -> { reads++; return loaded; }, () -> ticking, () -> block,
				() -> { loots++; afterLoot.run(); return List.of(loot); },
				mature -> { writes++; if (!succeeds) return false; block = crop(0); return true; });
		}
		void unchanged() { assertEquals(0, writes); assertEquals(crop(7), block); assertTrue(inventory.isEmpty()); }
	}

	@Test void seedIsReservedBeforeOnlyNewOrdinaryOutputIsStamped() {
		Attempt a = new Attempt(5); a.enable(); a.state().world().advanceDay();
		ItemStack old = potatoes(9), named = custom(), first = potatoes(1), second = potatoes(4);
		a.inventory.setItem(0, old.copy());
		assertEquals(4, a.run(named, first, new ItemStack(Items.POISONOUS_POTATO), second));
		assertEquals(crop(0), a.block); assertEquals(1, a.writes);
		assertTrue(ItemStack.matches(old, a.inventory.getItem(0)));
		assertTrue(ItemStack.matches(named, a.inventory.getItem(1)));
		assertEquals(1, a.inventory.getItem(2).getCount()); assertFalse(SettlementFoodAge.isMarked(a.inventory.getItem(2)));
		var stamp = SettlementFoodAge.read(a.inventory.getItem(3)).orElseThrow();
		assertEquals(4, a.inventory.getItem(3).getCount()); assertEquals(ResourceType.TUBERS, stamp.resourceType());
		assertEquals(1, stamp.harvestDay()); assertEquals(9, stamp.expiryDay());
		assertEquals(a.state().foodClockId().orElseThrow(), stamp.clockId());
		assertEquals(1, first.getCount()); assertEquals(4, second.getCount());
		assertEquals(0L, a.state().world().inventoryTotals().get(ResourceType.TUBERS).longValue());
		assertEquals(1, a.state().world().ledger().events().size()); a.state().world().assertConservation();
	}

	@Test void soleFreshSeedReplantsEvenWithFullBagWithoutProductionOrPractice() {
		Attempt a = new Attempt(1); a.enable(); a.inventory.setItem(0, potatoes(64));
		var saved = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run(potatoes(1))); assertEquals(1, a.writes); assertEquals(crop(0), a.block);
		assertEquals(64, a.inventory.countItem(Items.POTATO)); assertEquals(saved, a.data.save(new CompoundTag(), null));
	}
	@Test void seedWithOnlyByproductsPreservesThemWithoutFoodPractice() {
		Attempt a = new Attempt(3); a.enable();
		ItemStack named = custom();
		ItemStack marked = SettlementFoodAge.stamp(potatoes(2), new UUID(25, 9), 4);
		var saved = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run(potatoes(1), named, marked, new ItemStack(Items.POISONOUS_POTATO)));
		assertEquals(1, a.writes); assertEquals(crop(0), a.block);
		assertTrue(ItemStack.matches(named, a.inventory.getItem(0)));
		assertTrue(ItemStack.matches(marked, a.inventory.getItem(1)));
		assertEquals(1, a.inventory.getItem(2).getCount());
		assertEquals(saved, a.data.save(new CompoundTag(), null));
	}

	@Test void oldBagAndMarkedOrCustomLootCannotSupplySeed() {
		for (int kind = 0; kind < 4; kind++) {
			Attempt a = new Attempt(3); a.enable(); a.inventory.setItem(0, potatoes(5));
			ItemStack loot = switch (kind) {
				case 0 -> custom(); case 1 -> new ItemStack(Items.POISONOUS_POTATO);
				case 2 -> SettlementFoodAge.stamp(potatoes(2), a.state().foodClockId().orElseThrow(), 0);
				default -> ItemStack.EMPTY;
			};
			var saved = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(loot)); assertEquals(0, a.writes); assertEquals(crop(7), a.block);
			assertEquals(5, a.inventory.countItem(Items.POTATO)); assertEquals(saved, a.data.save(new CompoundTag(), null));
		}
	}
	@Test void capacityUsesStampedIdentityAndMustFitEveryByproduct() {
		for (boolean oldPlain : List.of(false, true)) {
			Attempt a = new Attempt(1); a.enable();
			if (oldPlain) a.inventory.setItem(0, potatoes(60));
			var saved = a.data.save(new CompoundTag(), null);
			assertEquals(0, a.run(potatoes(3), new ItemStack(Items.POISONOUS_POTATO)));
			assertEquals(0, a.writes); assertEquals(crop(7), a.block);
			assertEquals(oldPlain ? 60 : 0, a.inventory.countItem(Items.POTATO));
			assertEquals(saved, a.data.save(new CompoundTag(), null));
		}
		Attempt plain = new Attempt(1); plain.enable(); plain.inventory.setItem(0, potatoes(60));
		assertEquals(0, plain.run(potatoes(3))); assertEquals(0, plain.writes);
		Attempt same = new Attempt(1); same.enable();
		same.inventory.setItem(0, SettlementFoodAge.stamp(potatoes(60), same.state().foodClockId().orElseThrow(), 0));
		assertEquals(4, same.run(potatoes(5))); assertEquals(64, same.inventory.getItem(0).getCount());
	}
	@Test void writeFailureNeverInsertsOrPractices() {
		Attempt a = new Attempt(2); a.enable(); a.succeeds = false; var saved = a.data.save(new CompoundTag(), null);
		assertEquals(0, a.run(potatoes(5))); assertEquals(1, a.writes);
		assertTrue(a.inventory.isEmpty()); assertEquals(crop(7), a.block); assertEquals(saved, a.data.save(new CompoundTag(), null));
	}
	@Test void ordinaryModeStillMergesPlainPotatoesAndReservesOneSeed() {
		Attempt a = new Attempt(1); a.inventory.setItem(0, potatoes(60));
		assertEquals(4, a.run(potatoes(5))); assertEquals(64, a.inventory.getItem(0).getCount());
		assertFalse(SettlementFoodAge.isMarked(a.inventory.getItem(0))); assertTrue(a.data.simulationOf(a.camp).isEmpty());
	}
	@Test void unmarkedDeadForeignUnmappedOrDisabledNeverReadWorld() {
		for (int condition = 0; condition < 6; condition++) {
			Attempt a = new Attempt(1); a.enable();
			var person = a.state().world().person(a.state().residentId(a.actor).orElseThrow());
			switch (condition) {
				case 0 -> a.data.toggleResource(a.camp, BlockPos.ZERO);
				case 1 -> a.adult = false;
				case 2 -> FoodDayTestResidents.kill(person);
				case 3 -> FoodDayTestResidents.move(person, "foreign");
				case 4 -> a.data.removeMember(a.actor);
				case 5 -> a.state().setProductionEnabled(false);
			}
			assertEquals(0, a.run(potatoes(4))); assertEquals(0, a.reads); assertEquals(0, a.loots); a.unchanged();
		}
	}
	@Test void unmappedSurvivalResidentCannotReadWorld() {
		Attempt a = new Attempt(1);
		a.state().setSurvivalEnabled(true, 0); a.state().setProductionEnabled(true);
		assertEquals(0, a.run(potatoes(4))); assertEquals(0, a.reads); assertEquals(0, a.loots); a.unchanged();
	}
	@Test void changingMaturityDuringLootCannotReplantOrInsert() {
		Attempt a = new Attempt(1); a.enable(); a.afterLoot = () -> a.block = crop(6);
		assertEquals(0, a.run(potatoes(4))); assertEquals(0, a.writes);
		assertEquals(crop(6), a.block); assertTrue(a.inventory.isEmpty());
		assertTrue(a.state().world().ledger().events().isEmpty());
	}
	@Test void newHarvestDoesNotRefreshOldMarkedCargoOrTreatItAsSeed() {
		Attempt a = new Attempt(3); a.enable();
		UUID clock = a.state().foodClockId().orElseThrow();
		ItemStack old = SettlementFoodAge.stamp(potatoes(5), clock, 0);
		a.inventory.setItem(0, old.copy()); a.state().world().advanceDay();
		assertEquals(3, a.run(potatoes(4)));
		assertTrue(ItemStack.matches(old, a.inventory.getItem(0)));
		assertEquals(1, SettlementFoodAge.read(a.inventory.getItem(1)).orElseThrow().harvestDay());
		a.block = crop(7);
		assertEquals(0, a.run(old)); assertEquals(crop(7), a.block); assertEquals(1, a.writes);
		assertEquals(8, a.inventory.countItem(Items.POTATO));
	}

	@Test void callbackChangesRejectBeforeWorldMutationAtBothPlanningBoundaries() {
		for (boolean planning : List.of(false, true)) for (int condition = 0; condition < 11; condition++) {
			Attempt a = new Attempt(2); a.enable(); final int change = condition;
			Runnable mutate = () -> { switch (change) {
				case 0 -> a.state().world().advanceDay();
				case 1 -> a.adult = false;
				case 2 -> a.data.removeMember(a.actor);
				case 3 -> { a.data.removeMember(a.actor); a.data.registerMember(a.camp, a.actor, 0); }
				case 4 -> { a.state().setProductionEnabled(false); a.state().setProductionEnabled(true); }
				case 5 -> { a.state().setSurvivalEnabled(false, 0); a.state().setSurvivalEnabled(true, 0); }
				case 6 -> FoodDayTestResidents.kill(a.state().world().person(a.state().residentId(a.actor).orElseThrow()));
				case 7 -> FoodDayTestResidents.move(a.state().world().person(a.state().residentId(a.actor).orElseThrow()), "foreign");
				case 8 -> a.data.toggleResource(a.camp, BlockPos.ZERO);
				case 9 -> a.loaded = false;
				case 10 -> a.ticking = false;
			} };
			if (planning) { a.duringPlanning = mutate; a.planning = true; } else a.afterLoot = mutate;
			assertEquals(0, a.run(potatoes(4))); a.unchanged(); assertTrue(a.state().world().ledger().events().isEmpty());
		}
	}
}
