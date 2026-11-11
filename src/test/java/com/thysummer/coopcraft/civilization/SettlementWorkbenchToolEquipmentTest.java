package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure container/custody tests; no level or server required. */
class SettlementWorkbenchToolEquipmentTest {
	private static final UUID ACTOR = new UUID(97, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void equipsExactlyTheActualCompletedToolAndCannotRepeat() {
		Fixture f = new Fixture(); f.finish();
		ItemStack actual = f.bag.getItem(0);
		assertTrue(f.equip()); assertSame(actual, f.hand.get()); assertTrue(f.bag.isEmpty());
		assertEquals(1, actual.getCount()); assertTrue(actual.is(Items.STONE_PICKAXE));
		assertEquals(new ItemStack(Items.STONE_PICKAXE).getComponentsPatch(), actual.getComponentsPatch());
		assertFalse(f.equip()); assertSame(actual, f.hand.get());
		assertFalse(SettlementWorkbenchCrafting.finish(f.ticket, f.data, f.camp, ACTOR, f.bag, () -> true));
	}

	@Test void settingSameStationPositionKeepsCompletedBatchAuthorized() {
		Fixture f = new Fixture(); f.finish(); ItemStack actual = f.bag.getItem(0);
		Object binding = f.data.craftingStationBindingToken(f.camp);
		f.data.setCraftingStation(f.camp, f.ticket.station());
		assertSame(binding, f.data.craftingStationBindingToken(f.camp));
		assertTrue(f.equip()); assertSame(actual, f.hand.get()); assertTrue(f.bag.isEmpty());
	}

	@Test void occupiedHandAndStaleAuthorityPreserveMarkedOutput() {
		for (int scenario = 0; scenario < 3; scenario++) {
			Fixture f = new Fixture(); f.finish(); ItemStack actual = f.bag.getItem(0), before = actual.copy();
			if (scenario == 0) f.hand.stack = new ItemStack(Items.APPLE);
			if (scenario == 1) f.rebindStation();
			if (scenario == 2) f.atStation = () -> false;
			assertFalse(f.equip()); assertSame(actual, f.bag.getItem(0)); assertTrue(ItemStack.matches(before, actual));
			f.hand.stack = ItemStack.EMPTY; f.atStation = () -> true;
			assertFalse(f.equip()); assertTrue(SettlementWorkbenchCargo.isMarked(actual));
		}
	}

	@Test void callbacksCannotReplaceSourceFillHandOrRebindAuthorityDuringFinalCheck() {
		for (int scenario = 0; scenario < 3; scenario++) {
			Fixture f = new Fixture(); f.finish(); ItemStack actual = f.bag.getItem(0);
			int action = scenario;
			f.atStation = () -> {
				if (action == 0) f.hand.stack = new ItemStack(Items.APPLE);
				if (action == 1) f.bag.setItem(0, actual.copy());
				if (action == 2) f.rebindStation();
				return true;
			};
			assertFalse(f.equip()); assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE));
			assertTrue(SettlementWorkbenchCargo.isMarked(f.bag.getItem(0)));
		}
		Fixture f = new Fixture(); f.finish();
		f.atStation = () -> {
			f.worldValid = () -> { f.data.setStockpile(f.camp, f.ticket.store()); return true; };
			return true;
		};
		assertFalse(f.equip()); assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE));
	}

	@Test void wrongCampBatchUnknownMarkerAndModifiedComponentsRemainQuarantined() {
		for (int scenario = 0; scenario < 7; scenario++) {
			Fixture f = new Fixture(); f.finish(); ItemStack changed = f.bag.getItem(0);
			switch (scenario) {
				case 0 -> changed = SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), Recipe.STONE_PICKAXE);
				case 1 -> changed = SettlementWorkbenchCargo.output(f.camp.id(), UUID.randomUUID(), Recipe.STONE_PICKAXE);
				case 2 -> {
					CompoundTag root = changed.get(DataComponents.CUSTOM_DATA).copyTag();
					root.getCompound(SettlementWorkbenchCargo.KEY).putInt("Version", 99);
					changed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
				}
				case 3 -> changed.set(DataComponents.CUSTOM_NAME, Component.literal("Personal"));
				case 4 -> changed.remove(DataComponents.TOOL);
				case 5 -> changed.setDamageValue(1);
				case 6 -> changed = new ItemStack(Items.STONE_PICKAXE);
			}
			f.bag.setItem(0, changed); ItemStack before = changed.copy();
			assertFalse(f.equip()); assertSame(changed, f.bag.getItem(0)); assertTrue(ItemStack.matches(before, changed));
			assertTrue(f.hand.get().isEmpty());
		}
	}

	@Test void duplicatedExactOutputIsNeverEquippedOrDeducted() {
		Fixture f = new Fixture(); f.finish(); ItemStack actual = f.bag.getItem(0);
		f.bag.setItem(1, actual.copy());
		assertFalse(f.equip()); assertSame(actual, f.bag.getItem(0));
		assertEquals(2, f.bag.countItem(Items.STONE_PICKAXE)); assertTrue(f.hand.get().isEmpty());
	}

	@Test void personalPickaxesInEitherHandOrBackpackPreventASecondTool() {
		for (int scenario = 0; scenario < 3; scenario++) {
			Fixture f = new Fixture(); f.finish();
			ItemStack personal = new ItemStack(Items.DIAMOND_PICKAXE);
			personal.set(DataComponents.CUSTOM_NAME, Component.literal("Keep")); ItemStack before = personal.copy();
			if (scenario == 0) f.hand.stack = personal;
			if (scenario == 1) f.off = personal;
			if (scenario == 2) f.bag.setItem(2, personal);
			assertFalse(f.equip()); assertTrue(ItemStack.matches(before, personal));
			assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE));
		}
	}

	@Test void pureDemandDetectsEveryPickaxeWithoutItemTags() {
		SimpleContainer bag = new SimpleContainer(3);
		assertTrue(SettlementWorkbenchToolEquipment.needsFirstPickaxe(bag, ItemStack.EMPTY, ItemStack.EMPTY));
		assertFalse(SettlementWorkbenchToolEquipment.needsFirstPickaxe(bag, new ItemStack(Items.APPLE), ItemStack.EMPTY));
		for (var item : new net.minecraft.world.item.Item[] { Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
			Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE }) {
			ItemStack pick = new ItemStack(item);
			assertFalse(SettlementWorkbenchToolEquipment.needsFirstPickaxe(bag, ItemStack.EMPTY, pick));
			bag.setItem(1, pick);
			assertFalse(SettlementWorkbenchToolEquipment.needsFirstPickaxe(bag, ItemStack.EMPTY, ItemStack.EMPTY));
			bag.setItem(1, ItemStack.EMPTY);
		}
		bag.setItem(0, SettlementWorkbenchCargo.output(UUID.randomUUID(), UUID.randomUUID(), Recipe.STONE_PICKAXE));
		assertFalse(SettlementWorkbenchToolEquipment.needsFirstPickaxe(bag, ItemStack.EMPTY, ItemStack.EMPTY));
	}

	@Test void handSetterThrowingBeforeOrAfterMutationNeverLosesOrDuplicatesTool() {
		for (boolean after : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.finish(); ItemStack actual = f.bag.getItem(0);
			f.hand.fail = true; f.hand.after = after;
			assertEquals(after, f.equip());
			assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE) + (f.hand.get().isEmpty() ? 0 : f.hand.get().getCount()));
			assertSame(actual, after ? f.hand.get() : f.bag.getItem(0));
			assertEquals(!after, SettlementWorkbenchCargo.isMarked(actual));
			assertFalse(f.equip());
		}
	}

	@Test void removalExceptionsAndRollbackNotificationExceptionsInspectActualCustody() {
		for (boolean after : new boolean[] { false, true }) {
			FaultBag bag = new FaultBag(); Fixture f = new Fixture(bag); f.finish();
			ItemStack actual = bag.getItem(0); bag.failRemoval = true; bag.after = after;
			assertEquals(after, f.equip()); assertSame(actual, after ? f.hand.get() : bag.getItem(0));
			assertEquals(1, bag.countItem(Items.STONE_PICKAXE) + (f.hand.get().isEmpty() ? 0 : 1));
		}
		FaultBag bag = new FaultBag(); Fixture f = new Fixture(bag); f.finish(); ItemStack actual = bag.getItem(0);
		bag.failRestoreNotification = true; f.hand.fail = true;
		assertFalse(f.equip()); assertSame(actual, bag.getItem(0)); assertTrue(SettlementWorkbenchCargo.isMarked(actual));
		assertTrue(f.hand.get().isEmpty());
	}

	private static final class Hand implements SettlementShearsLoan.Hand {
		ItemStack stack = ItemStack.EMPTY; boolean fail, after;
		public ItemStack get() { return stack; }
		public void set(ItemStack value) {
			if (fail && !after) throw new IllegalStateException("before");
			stack = value;
			if (fail) throw new IllegalStateException("after");
		}
	}
	private static final class FaultBag extends SimpleContainer {
		boolean failRemoval, after, failRestoreNotification;
		FaultBag() { super(8); }
		@Override public ItemStack removeItemNoUpdate(int slot) {
			if (failRemoval && !after) throw new IllegalStateException("before removal");
			ItemStack actual = super.removeItemNoUpdate(slot);
			if (failRemoval) throw new IllegalStateException("after removal");
			return actual;
		}
		@Override public void setItem(int slot, ItemStack value) {
			super.setItem(slot, value);
			if (failRestoreNotification) throw new IllegalStateException("after restoration");
		}
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Tool workbench", 0);
		final SimpleContainer bag; final Hand hand = new Hand();
		ItemStack off = ItemStack.EMPTY;
		BooleanSupplier atStation = () -> true, worldValid = () -> true;
		SettlementWorkbenchCrafting.Ticket ticket;
		Fixture() { this(new SimpleContainer(8)); }
		Fixture(SimpleContainer bag) {
			this.bag = bag; data.registerMember(camp, ACTOR, 0);
			data.setStockpile(camp, new BlockPos(1, 0, 0)); data.setCraftingStation(camp, new BlockPos(3, 0, 0));
			var state = data.getOrCreateSimulation(camp); state.mapResident(ACTOR, false, 0);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		}
		void rebindStation() {
			data.setCraftingStation(camp, ticket.station().offset(1, 0, 0));
			data.setCraftingStation(camp, ticket.station());
		}
		void finish() {
			SimpleContainer store = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 3), new ItemStack(Items.STICK, 2));
			ticket = SettlementWorkbenchCrafting.prepare(data, camp, ACTOR, bag, Recipe.STONE_PICKAXE, () -> worldValid.getAsBoolean(), () -> true);
			assertNotNull(ticket); assertTrue(SettlementWorkbenchCrafting.pickup(ticket, data, camp, ACTOR, bag, store, () -> true));
			assertTrue(SettlementWorkbenchCrafting.finish(ticket, data, camp, ACTOR, bag, () -> true));
		}
		boolean equip() { return SettlementWorkbenchToolEquipment.equipFinishedPickaxe(ticket, data, camp, ACTOR, bag, hand, () -> off, atStation); }
	}
}
