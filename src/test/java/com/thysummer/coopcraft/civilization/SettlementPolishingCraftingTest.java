package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementPolishingCraftingTest {
	private static final UUID ACTOR = new UUID(96, 1), OTHER = new UUID(96, 2);
	private static HolderLookup.Provider registries;
	private static Holder<Enchantment> efficiency, unbreaking, fortune;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); registries = VanillaRegistries.createLookup();
		var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
		efficiency = enchantments.getOrThrow(Enchantments.EFFICIENCY);
		unbreaking = enchantments.getOrThrow(Enchantments.UNBREAKING);
		fortune = enchantments.getOrThrow(Enchantments.FORTUNE);
	}

	@Test void defaultPhysicalModeConsumesLastWholeBatchAndMutatesOnlyOriginalEnchantmentsOnce() {
		Fixture f = new Fixture(); f.supply(); ItemStack original = f.hand.stack;
		original.setDamageValue(73); original.set(DataComponents.CUSTOM_NAME, Component.literal("Old pickaxe"));
		original.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Keep history"))));
		CompoundTag tag = new CompoundTag(); tag.putString("Owner", "Resident"); original.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		original.set(DataComponents.REPAIR_COST, 7); original.remove(DataComponents.RARITY);
		original.enchant(efficiency, 5); original.enchant(fortune, 3);
		original.set(DataComponents.ENCHANTMENTS, original.getEnchantments().withTooltip(false));
		ItemStack expected = SettlementToolUpgrade.polish(original, efficiency, unbreaking).orElseThrow();
		var t = f.prepare(); assertNotNull(t); assertEquals(3, f.store.countItem(Items.SMOOTH_STONE));
		assertTrue(f.pickup(t)); assertTrue(f.store.isEmpty());
		assertEquals(3, f.bag.countItem(Items.SMOOTH_STONE)); assertEquals(2, f.bag.countItem(Items.STICK));
		assertTrue(f.finish(t)); assertFalse(f.finish(t)); assertTrue(f.bag.isEmpty());
		assertSame(original, f.hand.stack); assertEquals(0, f.hand.writes); assertEquals(1, original.getCount());
		assertEquals(expected.save(registries), original.save(registries));
		assertTrue(f.data.simulationOf(f.camp).isEmpty());
	}

	@Test void privateOrCustomIngredientsCannotSupplyPublicBatchAndRemainUntouched() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.bag.setItem(6, new ItemStack(Items.SMOOTH_STONE, 9)); f.bag.setItem(7, new ItemStack(Items.STICK, 11));
			if (scenario > 0) f.supply();
			if (scenario == 1) f.store.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			if (scenario == 2) f.store.getItem(1).remove(DataComponents.MAX_STACK_SIZE);
			if (scenario == 3) f.store.getItem(0).setCount(2);
			var bag = snapshot(f.bag); var store = snapshot(f.store); var hand = f.hand.stack.copy();
			assertFalse(f.pickup(f.prepare())); unchanged(bag, f.bag); unchanged(store, f.store); assertTrue(ItemStack.matches(hand, f.hand.stack));
		}
	}

	@Test void privateInventoryMaterialsSurviveSuccessfulPublicPolishing() {
		Fixture f = new Fixture(); f.supply(); f.bag.setItem(6, new ItemStack(Items.SMOOTH_STONE, 9)); f.bag.setItem(7, new ItemStack(Items.STICK, 11));
		var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.finish(t));
		assertEquals(9, f.bag.countItem(Items.SMOOTH_STONE)); assertEquals(11, f.bag.countItem(Items.STICK));
		assertEquals(0, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
	}

	@Test void changedReferenceDamageCountOrComponentsRevokeCompletion() {
		for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			ItemStack original = f.hand.stack;
			switch (scenario) {
				case 0 -> f.hand.stack = original.copy();
				case 1 -> original.setDamageValue(1);
				case 2 -> original.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
				case 3 -> original.setCount(2);
				case 4 -> original.remove(DataComponents.RARITY);
			}
			var hand = f.hand.stack.copy(); var bag = snapshot(f.bag);
			assertFalse(f.finish(t)); unchanged(bag, f.bag); assertTrue(ItemStack.matches(hand, f.hand.stack));
			assertEquals(0, original.getEnchantments().getLevel(unbreaking));
			assertEquals(5, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		}
	}

	@Test void invalidOrAlreadyPolishedHandsNeverWithdrawMaterials() {
		for (ItemStack tool : List.of(ItemStack.EMPTY, new ItemStack(Items.STONE_PICKAXE, 2), new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.STONE_AXE))) {
			Fixture f = new Fixture(); f.supply(); f.hand.stack = tool;
			assertNull(f.prepare()); assertEquals(3, f.store.countItem(Items.SMOOTH_STONE)); assertTrue(f.bag.isEmpty());
		}
		Fixture f = new Fixture(); f.supply(); f.hand.stack.enchant(efficiency, 1); f.hand.stack.enchant(unbreaking, 1);
		assertNull(f.prepare()); assertTrue(f.bag.isEmpty());
	}

	@Test void fullBagOrFullStoreCannotCauseAbstractExports() {
		for (boolean fullBag : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.simulation(); f.abstractSupply();
			SimpleContainer full = fullBag ? f.bag : f.store;
			for (int i = 0; i < full.getContainerSize(); i++) full.setItem(i, new ItemStack(Items.DIRT, 64));
			var bag = snapshot(f.bag); var store = snapshot(f.store); int events = f.state().world().ledger().events().size();
			assertFalse(f.pickup(f.prepare())); unchanged(bag, f.bag); unchanged(store, f.store);
			assertEquals(3, f.count(ResourceType.SMOOTH_STONE)); assertEquals(2, f.count(ResourceType.STICK));
			assertEquals(events, f.state().world().ledger().events().size());
		}
	}

	@Test void absentWarehouseArrivalCannotWithdrawOrExportMaterials() {
		for (boolean abstractInputs : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.simulation();
			if (abstractInputs) f.abstractSupply(); else f.supply();
			var store = snapshot(f.store); int events = f.state().world().ledger().events().size();
			assertFalse(SettlementWorkbenchCrafting.pickup(f.prepare(), f.data, f.camp, ACTOR, f.bag, f.store, () -> false));
			assertTrue(f.bag.isEmpty()); unchanged(store, f.store);
			assertEquals(abstractInputs ? 3 : 0, f.count(ResourceType.SMOOTH_STONE));
			assertEquals(abstractInputs ? 2 : 0, f.count(ResourceType.STICK));
			assertEquals(events, f.state().world().ledger().events().size());
			assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
		}
	}

	@Test void twoWorkersCannotClaimTheSameLastMaterials() {
		Fixture f = new Fixture(); f.supply(); f.data.registerMember(f.camp, OTHER, 0);
		SimpleContainer bag = new SimpleContainer(8); Hand hand = new Hand(); var first = f.prepare();
		var second = SettlementWorkbenchCrafting.preparePolishing(f.data, f.camp, OTHER, bag, hand, efficiency, unbreaking, () -> true, () -> true);
		assertNotNull(second); assertTrue(f.pickup(first));
		assertFalse(SettlementWorkbenchCrafting.pickup(second, f.data, f.camp, OTHER, bag, f.store, () -> true));
		assertTrue(f.finish(first)); assertTrue(bag.isEmpty()); assertEquals(0, hand.stack.getEnchantments().getLevel(efficiency));
	}

	@Test void cancellationLostDemandAndReloadReturnMaterialsWithoutUpgrading() {
		for (int scenario = 0; scenario < 3; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			if (scenario == 0) SettlementWorkbenchCrafting.cancel(t);
			if (scenario == 1) f.demand.set(false);
			SettlementSavedData data = f.data; Settlement camp = f.camp; SimpleContainer bag = f.bag;
			if (scenario == 2) {
				data = SettlementSavedData.load(f.data.save(new CompoundTag(), registries), registries); camp = data.find(BlockPos.ZERO).orElseThrow();
				bag = new SimpleContainer(8);
				for (int slot = 0; slot < f.bag.getContainerSize(); slot++) if (!f.bag.getItem(slot).isEmpty())
					bag.setItem(slot, ItemStack.parseOptional(registries, (CompoundTag) f.bag.getItem(slot).save(registries)));
			}
			assertFalse(SettlementWorkbenchCrafting.finishPolishing(t, data, camp, ACTOR, bag, () -> true));
			var delivery = SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, () -> true);
			assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, SettlementCargoDelivery.finish(delivery, data, camp, ACTOR, bag, f.store, () -> false).status());
			delivery = SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, () -> true);
			assertEquals(SettlementCargoDelivery.Status.DELIVERED, SettlementCargoDelivery.finish(delivery, data, camp, ACTOR, bag, f.store, () -> true).status());
			assertTrue(bag.isEmpty()); assertEquals(3, f.store.countItem(Items.SMOOTH_STONE)); assertEquals(2, f.store.countItem(Items.STICK));
			assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
		}
	}

	@Test void restoredAuthorityAndArrivalCallbacksCannotBypassTicketRevocation() {
		for (boolean station : new boolean[] { false, true }) for (int scenario = 0; scenario < 5; scenario++) {
			Fixture f = new Fixture(); f.simulation(); f.supply(); var t = f.prepare(); if (station) assertTrue(f.pickup(t));
			final int change = scenario;
			BooleanSupplier arrival = () -> {
				switch (change) {
					case 0 -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 1); }
					case 1 -> { f.data.setStockpile(f.camp, t.store().above()); f.data.setStockpile(f.camp, t.store()); }
					case 2 -> { f.data.setGrindingStation(f.camp, t.station().above()); f.data.setGrindingStation(f.camp, t.station()); }
					case 3 -> { f.state().setProductionEnabled(false); f.state().setProductionEnabled(true); }
					case 4 -> { f.state().setSurvivalEnabled(false, 1); f.state().setSurvivalEnabled(true, 2); }
				} return true;
			};
			var bag = snapshot(f.bag); var store = snapshot(f.store);
			assertFalse(station ? SettlementWorkbenchCrafting.finishPolishing(t, f.data, f.camp, ACTOR, f.bag, arrival)
				: SettlementWorkbenchCrafting.pickup(t, f.data, f.camp, ACTOR, f.bag, f.store, arrival));
			unchanged(bag, f.bag); unchanged(store, f.store); assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
		}
	}

	@Test void abstractInputsExportOnceAndCompletionLinksOnlyThoseExports() {
		Fixture f = new Fixture(); f.simulation(); f.abstractSupply(); var t = f.prepare(); assertTrue(f.pickup(t));
		var exports = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).toList();
		assertEquals(2, exports.size()); assertEquals(0, f.count(ResourceType.SMOOTH_STONE)); assertEquals(0, f.count(ResourceType.STICK));
		// An otherwise matching export after pickup belongs to another attempt, not this sealed cargo.
		f.state().world().importExternalBatch(ResourceType.STICK, 1, f.state().campOwner());
		var later = f.state().world().batchesAt(f.state().campOwner()).stream().filter(b -> b.resourceType() == ResourceType.STICK).findFirst().orElseThrow();
		assertEquals(1, MinecraftStockpileAdapter.exportToContainer(f.state().world(), f.state().campOwner(), later.batchId(), 1,
			f.store, List.of(f.state().residentId(ACTOR).orElseThrow()), Map.of("activity", "craft", "recipe", Recipe.POLISH_PICKAXE.id())));
		assertTrue(f.finish(t)); assertFalse(f.finish(t)); assertTrue(f.bag.isEmpty());
		var completions = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.CRAFT_COMPLETED).toList();
		assertEquals(1, completions.size()); assertEquals("polished_stone_pickaxe", completions.getFirst().context().get("recipe"));
		assertEquals(exports.stream().map(e -> e.eventId()).collect(java.util.stream.Collectors.toSet()), new java.util.HashSet<>(completions.getFirst().causalLinks()));
		f.state().world().assertConservation();
	}

	@Test void ordinaryPrepareFinishAndMaterialCraftingCannotManufacturePolishedTools() {
		Fixture f = new Fixture(); f.simulation(); f.supply();
		assertNull(SettlementWorkbenchCrafting.prepare(f.data, f.camp, ACTOR, f.bag, Recipe.POLISH_PICKAXE, () -> true, () -> true));
		assertFalse(SettlementMaterialCrafting.craft(f.store, f.state(), Recipe.POLISH_PICKAXE, ACTOR, () -> fail("dirty")));
		assertTrue(SettlementMaterialCrafting.chooseRecipe(f.store, f.state(), Map.of(Items.STONE_PICKAXE, 1)).isEmpty());
		var t = f.prepare(); assertTrue(f.pickup(t)); var bag = snapshot(f.bag);
		assertFalse(SettlementWorkbenchCrafting.finish(t, f.data, f.camp, ACTOR, f.bag, () -> true));
		unchanged(bag, f.bag); assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
	}

	@Test void incompleteDuplicatedForeignAndCustomCargoNeverUpgradesTheTool() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t));
			switch (scenario) {
				case 0 -> f.bag.getItem(0).shrink(1);
				case 1 -> f.bag.setItem(2, f.bag.getItem(0).copy());
				case 2 -> f.bag.setItem(0, SettlementWorkbenchCargo.inputs(f.camp.id(), UUID.randomUUID(), Recipe.POLISH_PICKAXE).getFirst());
				case 3 -> f.bag.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
			}
			var bag = snapshot(f.bag); assertFalse(f.finish(t)); unchanged(bag, f.bag);
			assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
		}
	}

	@Test void fullWarehouseRetainsCancelledInputsUntilBothMaterialsCanReturn() {
		Fixture f = new Fixture(); f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); SettlementWorkbenchCrafting.cancel(t);
		for (int slot = 0; slot < f.store.getContainerSize(); slot++) f.store.setItem(slot, new ItemStack(Items.DIRT, 64));
		var before = snapshot(f.bag); var delivery = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, f.bag, () -> true);
		assertEquals(SettlementCargoDelivery.Status.NO_CAPACITY,
			SettlementCargoDelivery.finish(delivery, f.data, f.camp, ACTOR, f.bag, f.store, () -> true).status());
		unchanged(before, f.bag); f.store.setItem(0, ItemStack.EMPTY); f.store.setItem(1, ItemStack.EMPTY);
		delivery = SettlementCargoDelivery.prepare(f.data, f.camp, ACTOR, f.bag, () -> true);
		assertEquals(SettlementCargoDelivery.Status.DELIVERED,
			SettlementCargoDelivery.finish(delivery, f.data, f.camp, ACTOR, f.bag, f.store, () -> true).status());
		assertTrue(f.bag.isEmpty()); assertEquals(3, f.store.countItem(Items.SMOOTH_STONE)); assertEquals(2, f.store.countItem(Items.STICK));
	}

	@Test void worldAndDemandCallbacksCannotRestoreRevokedStationAuthority() {
		for (boolean worldCallback : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.supply(); AtomicBoolean armed = new AtomicBoolean();
			BooleanSupplier mutate = () -> {
				if (armed.get()) f.data.setGrindingStation(f.camp, new BlockPos(3, 0, 0));
				return true;
			};
			var t = SettlementWorkbenchCrafting.preparePolishing(f.data, f.camp, ACTOR, f.bag, f.hand, efficiency, unbreaking,
				worldCallback ? mutate : () -> true, worldCallback ? () -> true : mutate);
			assertNotNull(t); assertTrue(f.pickup(t)); armed.set(true); var bag = snapshot(f.bag);
			assertFalse(f.finish(t)); unchanged(bag, f.bag); assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
		}
	}

	@Test void physicalInputsWorkWithAbstractModesDisabledWithoutExportingAgain() {
		for (boolean survivalOff : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.simulation(); f.supply(); f.abstractSupply();
			if (survivalOff) f.state().setSurvivalEnabled(false, 1); else f.state().setProductionEnabled(false);
			var t = f.prepare(); assertTrue(f.pickup(t)); assertTrue(f.finish(t));
			assertEquals(3, f.count(ResourceType.SMOOTH_STONE)); assertEquals(2, f.count(ResourceType.STICK));
			assertFalse(f.state().world().ledger().events().stream().anyMatch(e -> e.type() == EventType.EXTERNAL_EXPORT));
		}
	}

	@Test void lastDemandCallbackChangingPrivateSlotInvalidatesTheConsumedPreview() {
		Fixture f = new Fixture(); f.supply(); AtomicBoolean armed = new AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		BooleanSupplier demand = () -> {
			if (armed.get() && calls.incrementAndGet() == 3) f.bag.setItem(7, new ItemStack(Items.DIAMOND, 7));
			return true;
		};
		var t = SettlementWorkbenchCrafting.preparePolishing(f.data, f.camp, ACTOR, f.bag, f.hand, efficiency, unbreaking, () -> true, demand);
		assertTrue(f.pickup(t)); armed.set(true);
		assertFalse(f.finish(t)); assertEquals(3, calls.get());
		assertEquals(7, f.bag.countItem(Items.DIAMOND)); assertEquals(3, f.bag.countItem(Items.SMOOTH_STONE)); assertEquals(2, f.bag.countItem(Items.STICK));
		assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency));
		assertFalse(f.finish(t)); assertEquals(5, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertEquals(7, f.bag.countItem(Items.DIAMOND));
	}

	@Test void handAliasesInBagOrWarehouseCannotBeConsumedAsIndependentCustody() {
		for (boolean bagAlias : new boolean[] { false, true }) {
			Fixture f = new Fixture(); f.supply(); ItemStack original = f.hand.stack;
			if (bagAlias) f.bag.setItem(7, original); else f.store.setItem(8, original);
			var bag = snapshot(f.bag); var store = snapshot(f.store); var t = f.prepare();
			if (bagAlias) assertNull(t); else assertNotNull(t);
			assertFalse(f.pickup(t)); unchanged(bag, f.bag); unchanged(store, f.store);
			assertSame(original, f.hand.stack); assertEquals(0, original.getEnchantments().getLevel(efficiency));
		}
	}

	@Test void throwingSetterAfterMutationCannotReplayTheTicketOrManufactureARefund() {
		AtomicBoolean armed = new AtomicBoolean();
		Fixture f = new Fixture(new SimpleContainer(8) {
			@Override public void setItem(int slot, ItemStack stack) {
				super.setItem(slot, stack);
				if (armed.get()) throw new IllegalStateException("Notification failed after mutation");
			}
		});
		f.supply(); var t = f.prepare(); assertTrue(f.pickup(t)); armed.set(true);
		assertThrows(IllegalStateException.class, () -> f.finish(t)); armed.set(false);
		var remaining = snapshot(f.bag); assertFalse(f.finish(t)); unchanged(remaining, f.bag);
		assertEquals(0, f.hand.stack.getEnchantments().getLevel(efficiency)); assertTrue(f.store.isEmpty());
		int stone = f.bag.countItem(Items.SMOOTH_STONE), sticks = f.bag.countItem(Items.STICK);
		assertTrue(stone <= 3 && sticks <= 2);
		assertEquals(stone + sticks, SettlementWorkbenchCargo.deposit(f.bag, f.store, f.camp.id()));
		assertEquals(stone, f.store.countItem(Items.SMOOTH_STONE)); assertEquals(sticks, f.store.countItem(Items.STICK));
	}

	private static ItemStack[] snapshot(SimpleContainer c) { ItemStack[] result = new ItemStack[c.getContainerSize()]; for (int i = 0; i < result.length; i++) result[i] = c.getItem(i).copy(); return result; }
	private static void unchanged(ItemStack[] before, SimpleContainer c) { for (int i = 0; i < before.length; i++) assertTrue(ItemStack.matches(before[i], c.getItem(i)), "slot " + i); }
	private static final class Hand implements SettlementShearsLoan.Hand {
		ItemStack stack = new ItemStack(Items.STONE_PICKAXE); int writes;
		public ItemStack get() { return stack; }
		public void set(ItemStack stack) { writes++; this.stack = stack; }
	}
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData(); final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Polishing", 0);
		final SimpleContainer bag, store = new SimpleContainer(9);
		final Hand hand = new Hand(); final AtomicBoolean demand = new AtomicBoolean(true);
		Fixture() { this(new SimpleContainer(8)); }
		Fixture(SimpleContainer bag) { this.bag = bag; data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, new BlockPos(1, 0, 0)); data.setGrindingStation(camp, new BlockPos(3, 0, 0)); }
		void supply() { store.setItem(0, new ItemStack(Items.SMOOTH_STONE, 3)); store.setItem(1, new ItemStack(Items.STICK, 2)); }
		void simulation() { var s = data.getOrCreateSimulation(camp); s.mapResident(ACTOR, false, 0); s.setSurvivalEnabled(true, 0); s.setProductionEnabled(true); }
		SettlementSimulationState state() { return data.simulationOf(camp).orElseThrow(); }
		void abstractSupply() { state().world().importExternalBatch(ResourceType.SMOOTH_STONE, 3, state().campOwner()); state().world().importExternalBatch(ResourceType.STICK, 2, state().campOwner()); }
		long count(ResourceType type) { return state().world().batchesAt(state().campOwner()).stream().filter(b -> b.resourceType() == type).mapToLong(b -> b.quantity()).sum(); }
		SettlementWorkbenchCrafting.Ticket prepare() { return SettlementWorkbenchCrafting.preparePolishing(data, camp, ACTOR, bag, hand, efficiency, unbreaking, () -> true, demand::get); }
		boolean pickup(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.pickup(t, data, camp, ACTOR, bag, store, () -> true); }
		boolean finish(SettlementWorkbenchCrafting.Ticket t) { return SettlementWorkbenchCrafting.finishPolishing(t, data, camp, ACTOR, bag, () -> true); }
	}
}
