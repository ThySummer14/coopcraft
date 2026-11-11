package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure custody contracts; CI runs these without launching a Minecraft server. */
class SettlementWorkbenchBedCargoTest {
	private static final UUID CAMP = UUID.randomUUID(), BATCH = UUID.randomUUID();
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack stack = source.copy();
		CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementWorkbenchCargo.KEY));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	@Test void completeInputsPreserveLegacyEncodingsAndRejectSingleInputBedApi() {
		for (Recipe recipe : List.of(Recipe.PLANKS, Recipe.DOOR)) {
			List<ItemStack> inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, recipe);
			assertEquals(1, inputs.size());
			assertTrue(ItemStack.matches(SettlementWorkbenchCargo.input(CAMP, BATCH, recipe), inputs.getFirst()));
			for (ItemStack stack : List.of(inputs.getFirst(), SettlementWorkbenchCargo.output(CAMP, BATCH, recipe))) {
				CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
				assertEquals(recipe == Recipe.PLANKS ? 1 : 2, tag.getInt("Version"));
				assertEquals(recipe == Recipe.PLANKS ? Set.of("Version", "Settlement", "Batch", "Phase")
					: Set.of("Version", "Settlement", "Batch", "Phase", "Recipe"), tag.getAllKeys());
				if (recipe == Recipe.DOOR) assertEquals("DOOR", tag.getString("Recipe"));
			}
		}
		assertTrue(ItemStack.matches(SettlementWorkbenchCargo.input(CAMP, BATCH),
			SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.PLANKS).getFirst()));
		for (Recipe recipe : Recipe.values()) {
			assertEquals(recipe == Recipe.PLANKS || recipe == Recipe.STICKS || recipe == Recipe.DOOR || recipe == Recipe.BED || recipe == Recipe.WOOL
				|| recipe == Recipe.POLISH_PICKAXE || recipe == Recipe.SMOOTH_STONE || recipe == Recipe.STONE_PICKAXE || recipe == Recipe.TORCH_COAL || recipe == Recipe.TORCH_CHARCOAL,
				SettlementWorkbenchCargo.supported(recipe));
			if (!SettlementWorkbenchCargo.supported(recipe))
				assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.inputs(CAMP, BATCH, recipe));
		}
		assertFalse(SettlementWorkbenchCargo.supported(null));
		assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.inputs(CAMP, BATCH, null));
		assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.BED));
	}

	@Test void bedInputsValidateEachItemAndPartialCountAndSurviveNbtReload() {
		List<ItemStack> inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.BED);
		assertEquals(2, inputs.size());
		assertTrue(inputs.get(0).is(Items.WHITE_WOOL)); assertTrue(inputs.get(1).is(Items.OAK_PLANKS));
		for (ItemStack input : inputs) {
			assertEquals(3, input.getCount());
			for (int count = 1; count <= 3; count++) {
				ItemStack partial = input.copyWithCount(count);
				ItemStack loaded = ItemStack.parseOptional(registries, (CompoundTag) partial.save(registries));
				assertTrue(ItemStack.matches(partial, loaded));
				assertEquals(Recipe.BED, SettlementWorkbenchCargo.recipe(loaded));
				assertEquals(SettlementWorkbenchCargo.Phase.INPUT, SettlementWorkbenchCargo.phase(loaded));
				assertTrue(SettlementWorkbenchCargo.matchesInput(loaded, CAMP, BATCH, Recipe.BED));
				assertTrue(SettlementWorkbenchCargo.hasReturnable(new SimpleContainer(loaded), CAMP));
			}
			assertFalse(SettlementWorkbenchCargo.matchesInput(input.copyWithCount(0), CAMP, BATCH, Recipe.BED));
			assertFalse(SettlementWorkbenchCargo.matchesInput(input.copyWithCount(4), CAMP, BATCH, Recipe.BED));
			assertFalse(SettlementWorkbenchCargo.matchesInput(input, UUID.randomUUID(), BATCH, Recipe.BED));
			assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, UUID.randomUUID(), Recipe.BED));
			assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH, Recipe.DOOR));
			assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH));
			CompoundTag tag = input.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
			assertEquals(2, tag.getInt("Version")); assertEquals("BED", tag.getString("Recipe"));
		}
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.BED);
		assertTrue(output.is(Items.WHITE_BED)); assertEquals(1, output.getCount());
		ItemStack loaded = ItemStack.parseOptional(registries, (CompoundTag) output.save(registries));
		assertTrue(ItemStack.matches(output, loaded));
		assertEquals(SettlementWorkbenchCargo.Phase.OUTPUT, SettlementWorkbenchCargo.phase(loaded));
		assertEquals(Recipe.BED, SettlementWorkbenchCargo.recipe(loaded));
		assertFalse(SettlementWorkbenchCargo.matchesInput(loaded, CAMP, BATCH, Recipe.BED));
		assertNull(SettlementWorkbenchCargo.recipe(output.copyWithCount(2)));
	}

	@Test void malformedBedCargoRemainsQuarantinedWithoutLosingComponents() {
		List<ItemStack> sources = new ArrayList<>(SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.BED));
		sources.add(SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.BED));
		for (ItemStack source : sources) {
			ItemStack named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Mine"));
			ItemStack removedDefault = source.copy(); removedDefault.remove(DataComponents.MAX_STACK_SIZE);
			ItemStack wrongItem = new ItemStack(Items.RED_WOOL);
			wrongItem.set(DataComponents.CUSTOM_DATA, source.get(DataComponents.CUSTOM_DATA));
			ItemStack extraRoot = source.copy();
			CompoundTag root = extraRoot.get(DataComponents.CUSTOM_DATA).copyTag(); root.putString(ConstructionSupply.KEY, "reserved");
			extraRoot.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			ItemStack wrongRootType = source.copy();
			CompoundTag badRoot = new CompoundTag(); badRoot.putString(SettlementWorkbenchCargo.KEY, "BED");
			wrongRootType.set(DataComponents.CUSTOM_DATA, CustomData.of(badRoot));
			for (ItemStack invalid : List.of(
				alter(source, tag -> tag.remove("Recipe")), alter(source, tag -> tag.putInt("Recipe", 2)),
				alter(source, tag -> tag.putString("Recipe", "bed")), alter(source, tag -> tag.putString("Recipe", "FUTURE")),
				alter(source, tag -> tag.putInt("Version", 1)), alter(source, tag -> tag.putInt("Version", 3)),
				alter(source, tag -> tag.putByte("Version", (byte) 2)), alter(source, tag -> tag.putString("Phase", "input")),
				alter(source, tag -> tag.putInt("extra", 1)), alter(source, tag -> tag.remove("Batch")),
				alter(source, tag -> tag.putString("Settlement", CAMP.toString())),
				source.copyWithCount(source.getCount() + 1), named, removedDefault, wrongItem, extraRoot, wrongRootType)) {
				ItemStack before = invalid.copy(); SimpleContainer bag = new SimpleContainer(invalid), store = new SimpleContainer(3);
				assertTrue(SettlementWorkbenchCargo.isMarked(invalid));
				assertNull(SettlementWorkbenchCargo.recipe(invalid));
				assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(invalid));
				assertFalse(SettlementWorkbenchCargo.matchesInput(invalid, CAMP, BATCH, Recipe.BED));
				assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
				assertEquals(0, SettlementMaterialCrafting.countPlain(bag, invalid.getItem()));
				assertEquals(0, ConstructionMaterials.available(bag, null, invalid.getItem()));
				assertEquals(0, SettlementStockpile.depositGatheredResources(store, bag));
				assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
				assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
			}
		}
	}

	@Test void canceledBatchReturnsEachMaterialPartiallyWithoutMixingIdentities() {
		List<ItemStack> inputs = SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.BED);
		ItemStack foreign = SettlementWorkbenchCargo.inputs(UUID.randomUUID(), BATCH, Recipe.BED).getFirst();
		SimpleContainer bag = new SimpleContainer(inputs.get(0), inputs.get(1), foreign.copy());
		SimpleContainer store = new SimpleContainer(new ItemStack(Items.WHITE_WOOL, 63), new ItemStack(Items.OAK_PLANKS, 62));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, UUID.randomUUID()));
		assertEquals(3, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertEquals(2, bag.getItem(0).getCount()); assertEquals(1, bag.getItem(1).getCount());
		for (int slot = 0; slot < 2; slot++) {
			assertTrue(SettlementWorkbenchCargo.matchesInput(bag.getItem(slot), CAMP, BATCH, Recipe.BED));
			assertEquals(64, store.getItem(slot).getCount());
			assertTrue(store.getItem(slot).getComponentsPatch().isEmpty());
		}
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		store.setItem(0, ItemStack.EMPTY); store.setItem(1, ItemStack.EMPTY);
		assertEquals(3, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertEquals(2, store.countItem(Items.WHITE_WOOL)); assertEquals(1, store.countItem(Items.OAK_PLANKS));
		assertTrue(bag.getItem(0).isEmpty()); assertTrue(bag.getItem(1).isEmpty());
		assertTrue(ItemStack.matches(foreign, bag.getItem(2)));
		assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
	}

	@Test void outputReturnsOnlyOnceAndLeavesConstructionPersonalAndOrdinaryBedsAlone() {
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.BED);
		ItemStack personal = new ItemStack(Items.WHITE_BED); personal.set(DataComponents.CUSTOM_NAME, Component.literal("My bed"));
		ConstructionInstruction step = new ConstructionInstruction(BlockPos.ZERO, Blocks.WHITE_BED.defaultBlockState(), Items.WHITE_BED, true);
		ConstructionJob job = ConstructionJob.start(new ConstructionPlan(ResourceLocation.parse("coopcraft:bed_cargo"), List.of(step)), BlockPos.ZERO);
		UUID actor = UUID.randomUUID();
		SimpleContainer constructionBag = new SimpleContainer(1);
		assertTrue(ConstructionSupply.pickup(new SimpleContainer(new ItemStack(Items.WHITE_BED)), constructionBag,
			Level.OVERWORLD.location(), CAMP, actor, job, step, () -> true));
		ItemStack supply = constructionBag.getItem(0).copy();
		assertTrue(ConstructionSupply.hasFor(constructionBag, Level.OVERWORLD.location(), CAMP, actor, job, step));
		ItemStack ordinary = new ItemStack(Items.WHITE_BED);
		SimpleContainer bag = new SimpleContainer(output.copy(), personal.copy(), supply.copy(), ordinary.copy());
		SimpleContainer store = new SimpleContainer(new ItemStack(Items.STONE, 64));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(output, bag.getItem(0)));
		store.setItem(0, ItemStack.EMPTY);
		assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(bag.getItem(0).isEmpty()); assertTrue(ItemStack.matches(ordinary, store.getItem(0)));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(personal, bag.getItem(1)));
		assertTrue(ItemStack.matches(supply, bag.getItem(2)));
		assertTrue(ItemStack.matches(ordinary, bag.getItem(3)));
	}

	@Test void deathRecoveryPreservesBothMaterialsOutputAndUnknownCargoAcrossSave() {
		UUID actor = UUID.randomUUID(); SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Beds", 0); data.registerMember(camp, actor, 0);
		var claims = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, data)),
			Level.OVERWORLD, actor);
		List<ItemStack> inputs = SettlementWorkbenchCargo.inputs(camp.id(), BATCH, Recipe.BED);
		ItemStack wool = inputs.get(0).copyWithCount(2), planks = inputs.get(1).copyWithCount(1);
		ItemStack output = SettlementWorkbenchCargo.output(camp.id(), BATCH, Recipe.BED);
		ItemStack unknown = alter(output, tag -> tag.putString("Recipe", "FUTURE"));
		List<ItemStack> originals = List.of(wool.copy(), planks.copy(), output.copy(), unknown.copy());
		SimpleContainer bag = new SimpleContainer(wool, planks, output, unknown, new ItemStack(Items.WHITE_WOOL, 5));
		SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		assertEquals(5, recovery.captureDeath(claims, Level.OVERWORLD, actor, BlockPos.ZERO, "Worker", () -> bag));
		assertEquals(5, bag.countItem(Items.WHITE_WOOL));
		CompoundTag saved = recovery.save(new CompoundTag(), registries);
		recovery = SettlementCargoRecovery.load(saved, registries);
		assertEquals(saved, recovery.save(new CompoundTag(), registries));
		SimpleContainer rescued = new SimpleContainer(4);
		assertEquals(5, recovery.recover(actor, rescued, () -> true).moved());
		for (int index = 0; index < originals.size(); index++)
			assertTrue(ItemStack.matches(originals.get(index), rescued.getItem(index)));
	}
}
