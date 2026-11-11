package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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

class SettlementPolishingCargoTest {
	private static final UUID CAMP = new UUID(95, 1), BATCH = new UUID(95, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void strictV4ContainsOnlyThreeStoneAndTwoSticksNeverANewPickaxe() {
		var inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.POLISH_PICKAXE);
		assertEquals(2, inputs.size());
		SimpleContainer bag = new SimpleContainer(inputs.toArray(ItemStack[]::new));
		assertEquals(3, bag.countItem(Items.SMOOTH_STONE)); assertEquals(2, bag.countItem(Items.STICK));
		for (ItemStack stack : inputs) {
			var stamp = stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
			assertEquals(Set.of("Version", "Recipe", "Settlement", "Batch", "Phase"), stamp.getAllKeys());
			assertEquals(4, stamp.getInt("Version")); assertEquals("POLISH_PICKAXE", stamp.getString("Recipe"));
			assertEquals(SettlementWorkbenchCargo.Phase.INPUT, SettlementWorkbenchCargo.phase(stack));
			assertTrue(SettlementWorkbenchCargo.matchesInput(stack, CAMP, BATCH, Recipe.POLISH_PICKAXE));
			assertFalse(SettlementWorkbenchCargo.matchesOutput(stack, CAMP, BATCH, Recipe.POLISH_PICKAXE));
		}
		assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.POLISH_PICKAXE));
		assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.POLISH_PICKAXE));
	}

	@Test void oldVersionsOutputAndMalformedFieldsStayQuarantined() {
		for (ItemStack source : SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.POLISH_PICKAXE)) {
			for (ItemStack bad : List.of(alter(source, t -> t.putInt("Version", 2)),
				alter(source, t -> t.putInt("Version", 3)), alter(source, t -> t.putInt("Version", 5)),
				alter(source, t -> t.putByte("Version", (byte) 4)), alter(source, t -> t.putString("Phase", "OUTPUT")),
				alter(source, t -> t.putString("Phase", "input")), alter(source, t -> t.putString("Recipe", "polish_pickaxe")),
				alter(source, t -> t.putString("Recipe", "SMOOTH_STONE")), alter(source, t -> t.remove("Batch")),
				alter(source, t -> t.putInt("Extra", 1)))) quarantined(bad);
			ItemStack named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Private")); quarantined(named);
			ItemStack removed = source.copy(); removed.remove(DataComponents.MAX_STACK_SIZE); quarantined(removed);
			ItemStack oversized = source.copy(); oversized.grow(1); quarantined(oversized);
			ItemStack wrong = new ItemStack(Items.STONE_PICKAXE); wrong.set(DataComponents.CUSTOM_DATA, source.get(DataComponents.CUSTOM_DATA)); quarantined(wrong);
		}
	}

	@Test void reloadedInputsReturnAsPlainMaterialsWithoutTouchingPersonalOrForeignStacks() {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		SimpleContainer bag = new SimpleContainer(6), store = new SimpleContainer(4);
		var inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.POLISH_PICKAXE);
		for (int i = 0; i < inputs.size(); i++) bag.setItem(i, ItemStack.parseOptional(registries, (CompoundTag) inputs.get(i).save(registries)));
		bag.setItem(2, new ItemStack(Items.SMOOTH_STONE, 7)); bag.setItem(3, new ItemStack(Items.STICK, 9));
		var foreign = SettlementWorkbenchCargo.inputs(UUID.randomUUID(), BATCH, Recipe.POLISH_PICKAXE);
		bag.setItem(4, foreign.get(0)); bag.setItem(5, foreign.get(1));
		assertEquals(5, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertEquals(3, SettlementMaterialCrafting.countPlain(store, Items.SMOOTH_STONE));
		assertEquals(2, SettlementMaterialCrafting.countPlain(store, Items.STICK));
		assertEquals(7, bag.getItem(2).getCount()); assertEquals(9, bag.getItem(3).getCount());
		assertTrue(ItemStack.matches(foreign.get(0), bag.getItem(4))); assertTrue(ItemStack.matches(foreign.get(1), bag.getItem(5)));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
	}

	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack stack = source.copy(); CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementWorkbenchCargo.KEY)); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root)); return stack;
	}
	private static void quarantined(ItemStack stack) {
		SimpleContainer bag = new SimpleContainer(1), store = new SimpleContainer(9); bag.setItem(0, stack.copy());
		// setItem clamps stacks with a removed MAX_STACK_SIZE component. Restore the malformed
		// fixture after insertion so quarantine, rather than container normalization, is under test.
		bag.getItem(0).setCount(stack.getCount());
		assertEquals(stack.getCount(), bag.getItem(0).getCount());
		assertEquals(stack.getComponentsPatch(), bag.getItem(0).getComponentsPatch());
		assertTrue(ItemStack.matches(stack, bag.getItem(0)));
		assertTrue(SettlementWorkbenchCargo.isMarked(stack)); assertNull(SettlementWorkbenchCargo.recipe(stack));
		assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(stack));
		assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
		assertEquals(0, SettlementMaterialCrafting.countPlain(bag, stack.getItem()));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(stack, bag.getItem(0))); assertTrue(store.isEmpty());
	}
}
