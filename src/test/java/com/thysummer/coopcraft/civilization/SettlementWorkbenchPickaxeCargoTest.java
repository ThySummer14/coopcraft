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

class SettlementWorkbenchPickaxeCargoTest {
	private static final UUID CAMP = UUID.randomUUID(), BATCH = UUID.randomUUID();
	private static final Recipe RECIPE = Recipe.STONE_PICKAXE;
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test void exactV2IdentityAndAllPartialInputsSurviveReload() {
		var inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, RECIPE);
		assertEquals(2, inputs.size()); assertTrue(inputs.get(0).is(Items.COBBLESTONE)); assertEquals(3, inputs.get(0).getCount());
		assertTrue(inputs.get(1).is(Items.STICK)); assertEquals(2, inputs.get(1).getCount());
		assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.input(CAMP, BATCH, RECIPE));
		List<ItemStack> all = new ArrayList<>(inputs); all.add(SettlementWorkbenchCargo.output(CAMP, BATCH, RECIPE));
		for (ItemStack source : all) {
			var tag = source.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
			assertEquals(Set.of("Version", "Recipe", "Settlement", "Batch", "Phase"), tag.getAllKeys());
			assertEquals(2, tag.getInt("Version")); assertEquals("STONE_PICKAXE", tag.getString("Recipe"));
			for (int n = 1; n <= source.getCount(); n++) {
				var partial = source.copyWithCount(n); var loaded = reload(partial);
				assertTrue(ItemStack.matches(partial, loaded)); assertEquals(RECIPE, SettlementWorkbenchCargo.recipe(loaded));
				assertEquals(source.is(Items.STONE_PICKAXE), SettlementWorkbenchCargo.matchesOutput(loaded, CAMP, BATCH, RECIPE));
				assertEquals(!source.is(Items.STONE_PICKAXE), SettlementWorkbenchCargo.matchesInput(loaded, CAMP, BATCH, RECIPE));
				assertFalse(SettlementWorkbenchCargo.matchesOutput(loaded, CAMP, UUID.randomUUID(), RECIPE));
			}
			assertNull(SettlementWorkbenchCargo.recipe(source.copyWithCount(source.getCount() + 1)));
		}
	}

	@Test void malformedFutureWrongRecipeAndComponentRichToolRemainQuarantined() {
		List<ItemStack> all = new ArrayList<>(SettlementWorkbenchCargo.inputs(CAMP, BATCH, RECIPE));
		all.add(SettlementWorkbenchCargo.output(CAMP, BATCH, RECIPE));
		for (ItemStack source : all) {
			ItemStack named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			ItemStack removed = source.copy(); removed.remove(DataComponents.MAX_STACK_SIZE);
			for (ItemStack bad : List.of(named, removed, alter(source, tag -> tag.putString("Recipe", "stone_pickaxe")),
				alter(source, tag -> tag.putString("Recipe", "FUTURE")), alter(source, tag -> tag.putInt("Version", 3)),
				alter(source, tag -> tag.putInt("Version", 1)), alter(source, tag -> tag.putByte("Version", (byte) 2)),
				alter(source, tag -> tag.remove("Batch")), alter(source, tag -> tag.putString("Phase", "input")),
				alter(source, tag -> tag.putInt("extra", 1)), source.copyWithCount(source.getCount() + 1))) {
				assertTrue(SettlementWorkbenchCargo.isMarked(bad)); assertNull(SettlementWorkbenchCargo.recipe(bad));
				assertTrue(SettlementWorkbenchCargo.unmarkOutput(bad).isEmpty());
				SimpleContainer bag = new SimpleContainer(bad.copy()), store = new SimpleContainer(8);
				assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertTrue(ItemStack.matches(bad, bag.getItem(0)));
			}
		}
		ItemStack damaged = SettlementWorkbenchCargo.output(CAMP, BATCH, RECIPE); damaged.setDamageValue(1);
		assertNull(SettlementWorkbenchCargo.recipe(damaged)); assertTrue(SettlementWorkbenchCargo.unmarkOutput(damaged).isEmpty());
	}

	@Test void partialReturnKeepsUnacceptedMarkerAndToolReturnsExactlyOnce() {
		var inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, RECIPE);
		SimpleContainer bag = new SimpleContainer(inputs.get(0).copy(), inputs.get(1).copy());
		SimpleContainer store = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 63));
		assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertEquals(2, bag.getItem(0).getCount());
		assertEquals(RECIPE, SettlementWorkbenchCargo.recipe(reload(bag.getItem(0))));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		store.setItem(0, ItemStack.EMPTY); assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		store.setItem(0, ItemStack.EMPTY); assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertTrue(bag.isEmpty());
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH, RECIPE); bag.setItem(0, reload(output));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, UUID.randomUUID()));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(SettlementWorkbenchCargo.matchesOutput(bag.getItem(0), CAMP, BATCH, RECIPE));
		assertTrue(SettlementWorkbenchCargo.unmarkOutput(output).getComponentsPatch().isEmpty());
		assertTrue(SettlementWorkbenchCargo.isMarked(output));
		store.setItem(0, ItemStack.EMPTY); assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(store.getItem(0).is(Items.STONE_PICKAXE)); assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
	}

	private static ItemStack reload(ItemStack stack) { return ItemStack.parse(registries, stack.save(registries)).orElseThrow(); }
	private static ItemStack alter(ItemStack stack, Consumer<CompoundTag> change) {
		ItemStack result = stack.copy(); CompoundTag root = result.get(DataComponents.CUSTOM_DATA).copyTag();
		CompoundTag tag = root.getCompound(SettlementWorkbenchCargo.KEY); change.accept(tag); root.put(SettlementWorkbenchCargo.KEY, tag);
		result.set(DataComponents.CUSTOM_DATA, CustomData.of(root)); return result;
	}
}
