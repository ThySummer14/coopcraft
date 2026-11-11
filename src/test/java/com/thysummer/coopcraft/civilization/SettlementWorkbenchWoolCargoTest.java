package com.thysummer.coopcraft.civilization;

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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Container and serialization contracts only; no running world or server. */
class SettlementWorkbenchWoolCargoTest {
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

	@Test void strictV2WoolSupportsFourStringsAndOneWhiteWoolAcrossReload() {
		ItemStack input = SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.WOOL);
		assertTrue(input.is(Items.STRING)); assertEquals(4, input.getCount());
		assertTrue(ItemStack.matches(input, SettlementWorkbenchCargo.inputs(CAMP, BATCH, Recipe.WOOL).getFirst()));
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.WOOL);
		assertTrue(output.is(Items.WHITE_WOOL)); assertEquals(1, output.getCount());
		for (ItemStack source : List.of(input, output)) {
			CompoundTag tag = source.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementWorkbenchCargo.KEY);
			assertEquals(Set.of("Version", "Settlement", "Batch", "Phase", "Recipe"), tag.getAllKeys());
			assertEquals(2, tag.getInt("Version")); assertEquals("WOOL", tag.getString("Recipe"));
			for (int count = 1; count <= source.getCount(); count++) {
				ItemStack partial = source.copyWithCount(count);
				ItemStack loaded = ItemStack.parseOptional(registries, (CompoundTag) partial.save(registries));
				assertTrue(ItemStack.matches(partial, loaded));
				assertEquals(Recipe.WOOL, SettlementWorkbenchCargo.recipe(loaded));
				assertEquals(source.is(Items.STRING), SettlementWorkbenchCargo.matchesInput(loaded, CAMP, BATCH, Recipe.WOOL));
				assertTrue(SettlementWorkbenchCargo.hasReturnable(new SimpleContainer(loaded), CAMP));
			}
			assertNull(SettlementWorkbenchCargo.recipe(source.copyWithCount(source.getCount() + 1)));
			assertNull(SettlementWorkbenchCargo.recipe(source.copyWithCount(0)));
		}
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH, Recipe.BED));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, UUID.randomUUID(), Recipe.WOOL));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, UUID.randomUUID(), BATCH, Recipe.WOOL));
	}

	@Test void namedMalformedAndUnknownWoolCargoStayQuarantined() {
		for (ItemStack source : List.of(SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.WOOL),
			SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.WOOL))) {
			ItemStack named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			ItemStack removedDefault = source.copy(); removedDefault.remove(DataComponents.MAX_STACK_SIZE);
			ItemStack wrongItem = new ItemStack(Items.RED_WOOL);
			wrongItem.set(DataComponents.CUSTOM_DATA, source.get(DataComponents.CUSTOM_DATA));
			for (ItemStack invalid : List.of(named, removedDefault, wrongItem,
				alter(source, tag -> tag.putString("Recipe", "wool")),
				alter(source, tag -> tag.putString("Recipe", "FUTURE")),
				alter(source, tag -> tag.putInt("Recipe", 2)), alter(source, tag -> tag.remove("Recipe")),
				alter(source, tag -> tag.putInt("Version", 1)), alter(source, tag -> tag.putInt("Version", 3)),
				alter(source, tag -> tag.putByte("Version", (byte) 2)),
				alter(source, tag -> tag.putString("Phase", "input")),
				alter(source, tag -> tag.putInt("extra", 1)), alter(source, tag -> tag.remove("Batch")),
				alter(source, tag -> tag.putString("Settlement", CAMP.toString())))) {
				ItemStack before = invalid.copy();
				SimpleContainer bag = new SimpleContainer(invalid), store = new SimpleContainer(2);
				assertTrue(SettlementWorkbenchCargo.isMarked(invalid));
				assertNull(SettlementWorkbenchCargo.recipe(invalid));
				assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(invalid));
				assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
				assertEquals(0, SettlementMaterialCrafting.countPlain(bag, invalid.getItem()));
				assertEquals(0, ConstructionMaterials.available(bag, null, invalid.getItem()));
				assertEquals(0, SettlementStockpile.depositGatheredResources(store, bag));
				assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
				assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
			}
		}
	}

	@Test void canceledStringsReturnPartiallyAndOutputReturnsOnlyOnce() {
		ItemStack foreign = SettlementWorkbenchCargo.input(UUID.randomUUID(), BATCH, Recipe.WOOL);
		SimpleContainer bag = new SimpleContainer(SettlementWorkbenchCargo.input(CAMP, BATCH, Recipe.WOOL), foreign.copy());
		SimpleContainer store = new SimpleContainer(new ItemStack(Items.STRING, 62));
		assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertEquals(2, bag.getItem(0).getCount());
		assertTrue(SettlementWorkbenchCargo.matchesInput(bag.getItem(0), CAMP, BATCH, Recipe.WOOL));
		assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		store.setItem(0, ItemStack.EMPTY);
		assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(foreign, bag.getItem(1)));
		bag.setItem(0, SettlementWorkbenchCargo.output(CAMP, BATCH, Recipe.WOOL));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		store.setItem(0, ItemStack.EMPTY);
		assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(new ItemStack(Items.WHITE_WOOL), store.getItem(0)));
	}

	@Test void deathRecoveryKeepsPartialStringsOutputAndUnknownCargoAcrossSave() {
		UUID actor = UUID.randomUUID(); SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Wool", 0); data.registerMember(camp, actor, 0);
		var claims = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, data)),
			Level.OVERWORLD, actor);
		ItemStack input = SettlementWorkbenchCargo.input(camp.id(), BATCH, Recipe.WOOL).copyWithCount(2);
		ItemStack output = SettlementWorkbenchCargo.output(camp.id(), BATCH, Recipe.WOOL);
		ItemStack unknown = alter(output, tag -> tag.putString("Recipe", "FUTURE"));
		List<ItemStack> originals = List.of(input.copy(), output.copy(), unknown.copy());
		SimpleContainer bag = new SimpleContainer(input, output, unknown, new ItemStack(Items.STRING, 5));
		SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		assertEquals(4, recovery.captureDeath(claims, Level.OVERWORLD, actor, BlockPos.ZERO, "Worker", () -> bag));
		assertEquals(5, bag.countItem(Items.STRING));
		CompoundTag saved = recovery.save(new CompoundTag(), registries);
		recovery = SettlementCargoRecovery.load(saved, registries);
		assertEquals(saved, recovery.save(new CompoundTag(), registries));
		SimpleContainer rescued = new SimpleContainer(3);
		assertEquals(4, recovery.recover(actor, rescued, () -> true).moved());
		for (int index = 0; index < originals.size(); index++)
			assertTrue(ItemStack.matches(originals.get(index), rescued.getItem(index)));
		SimpleContainer store = new SimpleContainer(3);
		assertEquals(3, SettlementWorkbenchCargo.deposit(rescued, store, camp.id()));
		assertTrue(ItemStack.matches(originals.get(2), rescued.getItem(2)));
	}
}
