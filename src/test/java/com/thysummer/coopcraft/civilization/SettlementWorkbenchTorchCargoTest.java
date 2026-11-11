package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
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

class SettlementWorkbenchTorchCargoTest {
	private static final UUID CAMP = UUID.randomUUID(), BATCH = UUID.randomUUID();
	private static final List<Recipe> RECIPES = List.of(Recipe.TORCH_COAL, Recipe.TORCH_CHARCOAL);
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test void exactV2FuelIdentityAndEveryPartialOutputSurviveNbtRoundTrip() {
		for (Recipe recipe : RECIPES) {
			assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.input(CAMP, BATCH, recipe));
			List<ItemStack> sources = new ArrayList<>(SettlementWorkbenchCargo.inputs(CAMP, BATCH, recipe));
			assertEquals(2, sources.size());
			assertEquals(1, sources.stream().filter(s -> s.is(Items.STICK)).findFirst().orElseThrow().getCount());
			assertEquals(1, sources.stream().filter(s -> s.is(recipe == Recipe.TORCH_COAL ? Items.COAL : Items.CHARCOAL)).findFirst().orElseThrow().getCount());
			sources.add(SettlementWorkbenchCargo.output(CAMP, BATCH, recipe));
			assertEquals(4, sources.getLast().getCount()); assertTrue(sources.getLast().is(Items.TORCH));
			for (ItemStack source : sources) {
				var tag = source.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
				assertEquals(Set.of("Version", "Recipe", "Settlement", "Batch", "Phase"), tag.getAllKeys());
				assertEquals(2, tag.getInt("Version")); assertEquals(recipe.name(), tag.getString("Recipe"));
				for (int count = 1; count <= source.getCount(); count++) {
					ItemStack partial = source.copyWithCount(count), loaded = reload(partial);
					assertTrue(ItemStack.matches(partial, loaded)); assertEquals(recipe, SettlementWorkbenchCargo.recipe(loaded));
					assertEquals(!source.is(Items.TORCH), SettlementWorkbenchCargo.matchesInput(loaded, CAMP, BATCH, recipe));
					assertFalse(SettlementWorkbenchCargo.matchesInput(loaded, CAMP, BATCH, recipe == Recipe.TORCH_COAL ? Recipe.TORCH_CHARCOAL : Recipe.TORCH_COAL));
					assertTrue(SettlementWorkbenchCargo.hasReturnable(new SimpleContainer(loaded), CAMP));
				}
				assertNull(SettlementWorkbenchCargo.recipe(source.copyWithCount(source.getCount() + 1)));
			}
		}
	}

	@Test void partialInputAndOutputReturnsPreserveUnacceptedNbtAndNeverDuplicate() {
		for (Recipe recipe : RECIPES) {
			var inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, recipe);
			SimpleContainer bag = new SimpleContainer(inputs.get(0).copy(), inputs.get(1).copy());
			SimpleContainer store = new SimpleContainer(new ItemStack(inputs.get(0).getItem(), 63));
			assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertEquals(64, store.getItem(0).getCount()); assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			bag.setItem(1, reload(bag.getItem(1))); assertTrue(SettlementWorkbenchCargo.isMarked(bag.getItem(1)));
			store.setItem(0, ItemStack.EMPTY); assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertTrue(bag.isEmpty());
			bag.setItem(0, SettlementWorkbenchCargo.output(CAMP, BATCH, recipe)); store.setItem(0, new ItemStack(Items.TORCH, 62));
			assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertEquals(2, bag.getItem(0).getCount());
			bag.setItem(0, reload(bag.getItem(0))); assertEquals(recipe, SettlementWorkbenchCargo.recipe(bag.getItem(0)));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			store.setItem(0, ItemStack.EMPTY); assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertTrue(bag.isEmpty());
		}
	}

	@Test void malformedCustomForeignAndWrongFuelCargoRemainUntouched() {
		for (Recipe recipe : RECIPES) {
			List<ItemStack> sources = new ArrayList<>(SettlementWorkbenchCargo.inputs(CAMP, BATCH, recipe));
			sources.add(SettlementWorkbenchCargo.output(CAMP, BATCH, recipe));
			for (ItemStack source : sources) {
				ItemStack named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
				ItemStack removed = source.copy(); removed.remove(DataComponents.MAX_STACK_SIZE);
				ItemStack wrong = new ItemStack(source.is(Items.STICK) ? Items.BLAZE_ROD : source.is(Items.TORCH) ? Items.SOUL_TORCH : recipe == Recipe.TORCH_COAL ? Items.CHARCOAL : Items.COAL);
				wrong.set(DataComponents.CUSTOM_DATA, source.get(DataComponents.CUSTOM_DATA));
				for (ItemStack bad : List.of(named, removed, wrong,
					alter(source, tag -> tag.putString("Recipe", "FUTURE")), alter(source, tag -> tag.putString("Recipe", recipe.name().toLowerCase(java.util.Locale.ROOT))),
					alter(source, tag -> tag.putInt("Version", 1)), alter(source, tag -> tag.putInt("Version", 3)),
					alter(source, tag -> tag.putByte("Version", (byte) 2)), alter(source, tag -> tag.putInt("Recipe", 2)),
					alter(source, tag -> tag.remove("Batch")), alter(source, tag -> tag.putString("Phase", "input")),
					alter(source, tag -> tag.putInt("extra", 1)), source.copyWithCount(source.getCount() + 1))) {
					assertTrue(SettlementWorkbenchCargo.isMarked(bad)); assertNull(SettlementWorkbenchCargo.recipe(bad));
					assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(bad));
					assertRefused(bad, CAMP);
				}
				assertRefused(source, UUID.randomUUID());
			}
		}
	}

	@Test void personalOrdinaryTorchesAndBothFuelTypesAreNotCargo() {
		for (var item : List.of(Items.TORCH, Items.COAL, Items.CHARCOAL, Items.STICK)) {
			ItemStack stack = new ItemStack(item, 8); SimpleContainer bag = new SimpleContainer(stack), store = new SimpleContainer(9);
			assertFalse(SettlementWorkbenchCargo.isMarked(stack));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertEquals(8, bag.getItem(0).getCount()); assertTrue(store.isEmpty());
		}
	}

	@Test void legacyPlanksAndExistingV2KeepTheirOriginalWireFormats() {
		for (Recipe recipe : List.of(Recipe.PLANKS, Recipe.DOOR, Recipe.BED, Recipe.WOOL)) {
			for (ItemStack stack : SettlementWorkbenchCargo.inputs(CAMP, BATCH, recipe)) {
				var tag = stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
				assertEquals(recipe == Recipe.PLANKS ? 1 : 2, tag.getInt("Version"));
				assertEquals(recipe != Recipe.PLANKS, tag.contains("Recipe")); assertEquals(recipe, SettlementWorkbenchCargo.recipe(reload(stack)));
			}
		}
	}

	private static void assertRefused(ItemStack stack, UUID camp) {
		ItemStack before = stack.copy(); SimpleContainer bag = new SimpleContainer(stack.copy()), store = new SimpleContainer(9);
		assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, camp));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, camp));
		assertEquals(0, SettlementMaterialCrafting.countPlain(bag, stack.getItem()));
		assertEquals(0, ConstructionMaterials.available(bag, null, stack.getItem()));
		assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
	}
	private static ItemStack reload(ItemStack stack) { return ItemStack.parseOptional(registries, (CompoundTag) stack.save(registries)); }
	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack stack = source.copy(); CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementWorkbenchCargo.KEY)); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root)); return stack;
	}
}
