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

/** Pure custody contracts, first executed by CI without starting a Minecraft server. */
class SettlementWorkbenchDoorCargoTest {
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

	@Test void preservesLegacyEncodingAndReadsBothVersionedRecipesAcrossNbt() {
		ItemStack legacy = SettlementWorkbenchCargo.input(CAMP, BATCH);
		CompoundTag oldTag = legacy.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
		assertEquals(1, oldTag.getInt("Version"));
		assertEquals(Set.of("Version", "Settlement", "Batch", "Phase"), oldTag.getAllKeys());
		assertTrue(ItemStack.matches(legacy, SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.PLANKS)));
		assertTrue(ItemStack.matches(SettlementWorkbenchCargo.output(CAMP, BATCH),
			SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.PLANKS)));
		ItemStack explicitPlanks = alter(legacy, tag -> { tag.putInt("Version", 2); tag.putString("Recipe", "PLANKS"); });
		ItemStack input = SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.DOOR);
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.DOOR);
		assertTrue(input.is(Items.OAK_PLANKS)); assertEquals(6, input.getCount());
		assertTrue(output.is(Items.OAK_DOOR)); assertEquals(3, output.getCount());
		assertEquals("DOOR", input.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY).getString("Recipe"));
		for (ItemStack source : List.of(legacy, explicitPlanks, input, output)) {
			ItemStack parsed = ItemStack.parseOptional(registries, (CompoundTag) source.save(registries));
			assertTrue(ItemStack.matches(source, parsed));
			assertEquals(source.is(Items.OAK_LOG) ? Recipe.PLANKS : Recipe.DOOR, SettlementWorkbenchCargo.recipe(parsed));
			assertTrue(SettlementWorkbenchCargo.hasReturnable(new SimpleContainer(parsed), CAMP));
		}
		assertTrue(SettlementWorkbenchCargo.matchesInput(explicitPlanks, CAMP, BATCH));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, UUID.randomUUID(), BATCH, Recipe.DOOR));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, UUID.randomUUID(), Recipe.DOOR));
		assertFalse(SettlementWorkbenchCargo.matchesInput(output, CAMP, BATCH, Recipe.DOOR));
		for (int count = 1; count <= 6; count++)
			assertTrue(SettlementWorkbenchCargo.matchesInput(input.copyWithCount(count), CAMP, BATCH, Recipe.DOOR));
		for (Recipe recipe : Recipe.values()) assertEquals(recipe == Recipe.PLANKS || recipe == Recipe.DOOR, SettlementWorkbenchCargo.supported(recipe));
		assertFalse(SettlementWorkbenchCargo.supported(null));
		assertThrows(IllegalArgumentException.class, () -> SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.BED));
	}

	@Test void malformedV2NeverBecomesLegacyOrOrdinaryStock() {
		ItemStack input = SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.DOOR);
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.DOOR);
		ItemStack named = input.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Personal"));
		ItemStack removedDefault = input.copy(); removedDefault.remove(DataComponents.MAX_STACK_SIZE);
		ItemStack extraRoot = input.copy();
		CompoundTag root = extraRoot.get(DataComponents.CUSTOM_DATA).copyTag(); root.putInt("other", 1);
		extraRoot.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		ItemStack wrongItem = new ItemStack(Items.OAK_LOG, 6);
		wrongItem.set(DataComponents.CUSTOM_DATA, input.get(DataComponents.CUSTOM_DATA));
		for (ItemStack invalid : List.of(
			alter(input, tag -> tag.remove("Recipe")), alter(input, tag -> tag.putInt("Recipe", 2)),
			alter(input, tag -> tag.putString("Recipe", "door")), alter(input, tag -> tag.putString("Recipe", "BED")),
			alter(input, tag -> tag.putString("Recipe", "FUTURE")), alter(input, tag -> tag.putInt("Version", 1)),
			alter(input, tag -> tag.putInt("Version", 3)), alter(input, tag -> tag.putByte("Version", (byte) 2)),
			alter(input, tag -> tag.putString("Phase", "input")), alter(input, tag -> tag.putInt("extra", 1)),
			alter(input, tag -> tag.remove("Batch")), alter(input, tag -> tag.putString("Settlement", CAMP.toString())),
			input.copyWithCount(7), output.copyWithCount(4), named, removedDefault, extraRoot, wrongItem)) {
			ItemStack before = invalid.copy(); SimpleContainer bag = new SimpleContainer(invalid), store = new SimpleContainer(2);
			assertTrue(SettlementWorkbenchCargo.isMarked(invalid));
			assertNull(SettlementWorkbenchCargo.recipe(invalid));
			assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(invalid));
			assertFalse(SettlementWorkbenchCargo.matchesInput(invalid, CAMP, BATCH, Recipe.DOOR));
			assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
			assertEquals(0, SettlementMaterialCrafting.countPlain(bag, invalid.getItem()));
			assertEquals(0, ConstructionMaterials.available(bag, null, invalid.getItem()));
			assertEquals(0, SettlementStockpile.depositGatheredResources(store, bag));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
		}
	}

	@Test void partialInputAndOutputReturnOnlyAcceptedAmountsWithoutTouchingOtherPlanks() {
		for (ItemStack cargo : List.of(SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.DOOR),
			SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.DOOR))) {
			ItemStack before = cargo.copy();
			ItemStack personal = new ItemStack(Items.OAK_PLANKS, 12);
			personal.set(DataComponents.CUSTOM_NAME, Component.literal("Mine"));
			ItemStack supply = new ItemStack(Items.OAK_PLANKS);
			CompoundTag root = new CompoundTag(); root.putString(ConstructionSupply.KEY, "reserved");
			supply.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			ItemStack ordinary = new ItemStack(Items.OAK_PLANKS, 7);
			SimpleContainer bag = new SimpleContainer(cargo, personal.copy(), supply.copy(), ordinary.copy());
			SimpleContainer store = new SimpleContainer(new ItemStack(cargo.getItem(), 63));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, UUID.randomUUID()));
			assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
			assertTrue(ItemStack.matches(before.copyWithCount(before.getCount() - 1), bag.getItem(0)));
			assertEquals(Recipe.DOOR, SettlementWorkbenchCargo.recipe(bag.getItem(0)));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			store.setItem(0, ItemStack.EMPTY);
			assertEquals(before.getCount() - 1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertTrue(bag.getItem(0).isEmpty());
			assertEquals(before.getCount() - 1, store.getItem(0).getCount());
			assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
			assertTrue(ItemStack.matches(personal, bag.getItem(1)));
			assertTrue(ItemStack.matches(supply, bag.getItem(2)));
			assertTrue(ItemStack.matches(ordinary, bag.getItem(3)));
		}
	}
	@Test void deathRecoveryPreservesDoorAndUnknownCargoAcrossSave() {
		UUID actor = UUID.randomUUID(); SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(net.minecraft.core.BlockPos.ZERO, "Doors", 0);
		data.registerMember(camp, actor, 0);
		var claims = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(
			net.minecraft.world.level.Level.OVERWORLD, data)), net.minecraft.world.level.Level.OVERWORLD, actor);
		ItemStack input = SettlementWorkbenchCargo.input(camp.id(), BATCH, Recipe.DOOR).copyWithCount(4);
		ItemStack output = SettlementWorkbenchCargo.output(camp.id(), BATCH, Recipe.DOOR).copyWithCount(2);
		ItemStack unknown = alter(output, tag -> tag.putString("Recipe", "FUTURE"));
		List<ItemStack> originals = List.of(input.copy(), output.copy(), unknown.copy());
		SimpleContainer bag = new SimpleContainer(input, output, unknown, new ItemStack(Items.OAK_PLANKS, 5));
		SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		assertEquals(8, recovery.captureDeath(claims, net.minecraft.world.level.Level.OVERWORLD, actor,
			net.minecraft.core.BlockPos.ZERO, "Worker", () -> bag));
		assertEquals(5, bag.countItem(Items.OAK_PLANKS));
		CompoundTag saved = recovery.save(new CompoundTag(), registries);
		recovery = SettlementCargoRecovery.load(saved, registries);
		assertEquals(saved, recovery.save(new CompoundTag(), registries));
		SimpleContainer rescued = new SimpleContainer(4);
		assertEquals(8, recovery.recover(actor, rescued, () -> true).moved());
		for (int index = 0; index < originals.size(); index++)
			assertTrue(ItemStack.matches(originals.get(index), rescued.getItem(index)));
	}

}
