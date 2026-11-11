package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
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

class SettlementGrindingCargoTest {
	private static final UUID CAMP = new UUID(91, 1), BATCH = new UUID(91, 2);
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test void oneCobbleAndOneSmoothStoneHaveExplicitStrictV3Identity() {
		assertTrue(SettlementWorkbenchCargo.supported(Recipe.SMOOTH_STONE));
		assertEquals(1, SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.SMOOTH_STONE).size());
		ItemStack input = input(), output = output();
		assertTrue(input.is(Items.COBBLESTONE)); assertEquals(1, input.getCount());
		assertTrue(output.is(Items.SMOOTH_STONE)); assertEquals(1, output.getCount());
		for (ItemStack stack : List.of(input, output)) {
			CompoundTag stamp = stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
			assertEquals(Set.of("Version", "Recipe", "Settlement", "Batch", "Phase"), stamp.getAllKeys());
			assertEquals(3, stamp.getInt("Version")); assertEquals("SMOOTH_STONE", stamp.getString("Recipe"));
			assertEquals(Recipe.SMOOTH_STONE, SettlementWorkbenchCargo.recipe(stack));
		}
		assertTrue(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH, Recipe.SMOOTH_STONE));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH, Recipe.PLANKS));
		assertTrue(SettlementWorkbenchCargo.matchesOutput(output, CAMP, BATCH, Recipe.SMOOTH_STONE));
	}

	@Test void inputAndOutputSurviveNbtReloadAndActuallyReturn() {
		for (ItemStack source : List.of(input(), output())) {
			ItemStack loaded = reload(source);
			assertTrue(ItemStack.matches(source, loaded));
			SimpleContainer bag = new SimpleContainer(loaded), store = new SimpleContainer(1);
			assertTrue(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
			assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertTrue(bag.isEmpty()); assertEquals(1, store.countItem(source.getItem()));
			assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		}
	}

	@Test void unknownVersionsRecipesAndMalformedFieldsRemainQuarantined() {
		for (ItemStack source : List.of(input(), output())) {
			for (ItemStack bad : List.of(alter(source, t -> t.putInt("Version", 1)),
				alter(source, t -> t.putInt("Version", 2)), alter(source, t -> t.putByte("Version", (byte) 2)),
				alter(source, t -> t.putString("Recipe", "FUTURE")), alter(source, t -> t.putString("Recipe", "smooth_stone")),
				alter(source, t -> t.putInt("Recipe", 1)), alter(source, t -> t.remove("Batch")),
				alter(source, t -> t.putString("Phase", "input")), alter(source, t -> t.putInt("Extra", 1)))) assertQuarantined(bad);
		}
	}

	@Test void wrongItemCustomComponentsAndOversizedLiveStacksAreQuarantined() {
		for (ItemStack source : List.of(input(), output())) {
			ItemStack named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			ItemStack removed = source.copy(); removed.remove(DataComponents.MAX_STACK_SIZE);
			ItemStack wrong = new ItemStack(source.is(Items.COBBLESTONE) ? Items.BIRCH_LOG : Items.BLAZE_ROD);
			wrong.set(DataComponents.CUSTOM_DATA, source.get(DataComponents.CUSTOM_DATA));
			assertQuarantined(named); assertQuarantined(removed); assertQuarantined(wrong);
			SimpleContainer bag = new SimpleContainer(1); bag.setItem(0, source.copy());
			bag.getItem(0).setCount(source.getCount() + 1);
			assertEquals(source.getCount() + 1, bag.getItem(0).getCount());
			ItemStack before = bag.getItem(0).copy();
			assertNull(SettlementWorkbenchCargo.recipe(bag.getItem(0)));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, new SimpleContainer(9), CAMP));
			assertTrue(ItemStack.matches(before, bag.getItem(0)));
		}
	}

	@Test void foreignCargoAndPersonalCobbleAndSmoothStoneCannotBeReturned() {
		for (ItemStack stack : List.of(input(), output(), new ItemStack(Items.COBBLESTONE, 4), new ItemStack(Items.SMOOTH_STONE, 8))) {
			SimpleContainer bag = new SimpleContainer(stack.copy()), store = new SimpleContainer(9);
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, UUID.randomUUID()));
			assertTrue(ItemStack.matches(stack, bag.getItem(0))); assertTrue(store.isEmpty());
		}
	}

	@Test void legacyV1PlanksAndExplicitV2PlanksRemainDistinctFromGrinding() {
		ItemStack legacy = SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.PLANKS);
		CompoundTag stamp = legacy.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
		assertEquals(1, stamp.getInt("Version")); assertFalse(stamp.contains("Recipe"));
		ItemStack v2 = alter(legacy, t -> { t.putInt("Version", 2); t.putString("Recipe", "PLANKS"); });
		for (ItemStack stack : List.of(reload(legacy), reload(v2))) {
			assertEquals(Recipe.PLANKS, SettlementWorkbenchCargo.recipe(stack));
			assertFalse(SettlementWorkbenchCargo.matchesInput(stack, CAMP, BATCH, Recipe.SMOOTH_STONE));
			assertEquals(1, SettlementWorkbenchCargo.deposit(new SimpleContainer(stack), new SimpleContainer(1), CAMP));
		}
	}

	private static ItemStack input() { return SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.SMOOTH_STONE); }
	private static ItemStack output() { return SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.SMOOTH_STONE); }
	private static ItemStack reload(ItemStack stack) { return ItemStack.parseOptional(registries, (CompoundTag) stack.save(registries)); }
	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack stack = source.copy(); CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementWorkbenchCargo.KEY)); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root)); return stack;
	}
	private static void assertQuarantined(ItemStack stack) {
		SimpleContainer bag = new SimpleContainer(stack.copy()), store = new SimpleContainer(9);
		assertTrue(SettlementWorkbenchCargo.isMarked(stack)); assertNull(SettlementWorkbenchCargo.recipe(stack));
		assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(stack));
		assertEquals(0, SettlementMaterialCrafting.countPlain(bag, stack.getItem()));
		assertEquals(0, ConstructionMaterials.available(bag, null, stack.getItem()));
		assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(stack, bag.getItem(0))); assertTrue(store.isEmpty());
	}
}
