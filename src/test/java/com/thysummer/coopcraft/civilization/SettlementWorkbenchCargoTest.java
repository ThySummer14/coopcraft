package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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

/** Server-free custody/arrival contracts; no game, server or pathfinding is started. */
class SettlementWorkbenchCargoTest {
	private static final UUID CAMP = UUID.randomUUID(), BATCH = UUID.randomUUID();
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static ItemStack alter(ItemStack original, Consumer<CompoundTag> edit) {
		ItemStack changed = original.copy();
		CompoundTag root = changed.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementWorkbenchCargo.KEY));
		changed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return changed;
	}

	@Test void validIdentityAndItemNbtRoundTrip() {
		ItemStack input = SettlementWorkbenchCargo.input(CAMP, BATCH);
		assertTrue(SettlementWorkbenchCargo.matchesInput(input, CAMP, BATCH));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, UUID.randomUUID(), BATCH));
		assertFalse(SettlementWorkbenchCargo.matchesInput(input, CAMP, UUID.randomUUID()));
		assertEquals(1, input.getCount());
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH);
		assertEquals(4, output.getCount());
		assertFalse(SettlementWorkbenchCargo.matchesInput(output, CAMP, BATCH));
		for (ItemStack stack : List.of(input, output)) {
			ItemStack parsed = ItemStack.parseOptional(registries, (CompoundTag) stack.save(registries));
			assertTrue(ItemStack.matches(stack, parsed));
			assertTrue(SettlementWorkbenchCargo.hasReturnable(new SimpleContainer(parsed), CAMP));
			assertFalse(SettlementCargo.isMaterial(parsed));
		}
	}

	@Test void malformedFutureWrongTypesCountsAndCustomStacksRemainQuarantined() {
		ItemStack input = SettlementWorkbenchCargo.input(CAMP, BATCH);
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH);
		ItemStack named = input.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Personal"));
		ItemStack extraRoot = input.copy();
		CompoundTag extra = extraRoot.get(DataComponents.CUSTOM_DATA).copyTag(); extra.putString("other", "retain");
		extraRoot.set(DataComponents.CUSTOM_DATA, CustomData.of(extra));
		ItemStack wrongItem = new ItemStack(Items.COBBLESTONE);
		wrongItem.set(DataComponents.CUSTOM_DATA, input.get(DataComponents.CUSTOM_DATA));
		ItemStack malformedRoot = new ItemStack(Items.OAK_LOG);
		CompoundTag bad = new CompoundTag(); bad.putString(SettlementWorkbenchCargo.KEY, "future");
		malformedRoot.set(DataComponents.CUSTOM_DATA, CustomData.of(bad));
		List<ItemStack> invalid = List.of(
			alter(input, tag -> tag.putInt("Version", 2)), alter(input, tag -> tag.putByte("Version", (byte) 1)),
			alter(input, tag -> tag.putString("Phase", "input")), alter(input, tag -> tag.remove("Batch")),
			alter(input, tag -> tag.putString("Settlement", CAMP.toString())), alter(input, tag -> tag.putInt("Extra", 1)),
			input.copyWithCount(2), output.copyWithCount(5), named, extraRoot, wrongItem, malformedRoot);
		for (ItemStack stack : invalid) {
			ItemStack before = stack.copy(); SimpleContainer bag = new SimpleContainer(stack), store = new SimpleContainer(2);
			assertTrue(SettlementWorkbenchCargo.isMarked(stack));
			assertEquals(SettlementWorkbenchCargo.Phase.UNKNOWN, SettlementWorkbenchCargo.phase(stack));
			assertFalse(SettlementWorkbenchCargo.matchesInput(stack, CAMP, BATCH));
			assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
			assertFalse(SettlementCargo.isMaterial(stack));
			assertEquals(0, SettlementStockpile.depositGatheredResources(store, bag));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
		}
	}


	@Test
	@org.junit.jupiter.api.parallel.ResourceLock("minecraft-item-tags")
	void runtimeLogTagCannotBypassWorkbenchQuarantine() {
		var registry = BuiltInRegistries.ITEM;
		var original = new java.util.HashMap<net.minecraft.tags.TagKey<net.minecraft.world.item.Item>,
			java.util.List<net.minecraft.core.Holder<net.minecraft.world.item.Item>>>();
		registry.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
		var controlled = new java.util.HashMap<>(original);
		controlled.put(net.minecraft.tags.ItemTags.LOGS, List.of(Items.OAK_LOG.builtInRegistryHolder()));
		try {
			registry.bindTags(controlled);
			assertTrue(SettlementCargo.isMaterial(new ItemStack(Items.OAK_LOG)));
			for (ItemStack cargo : List.of(SettlementWorkbenchCargo.input(CAMP, BATCH),
				alter(SettlementWorkbenchCargo.input(CAMP, BATCH), tag -> tag.putInt("Version", 9)))) {
				SimpleContainer bag = new SimpleContainer(cargo), store = new SimpleContainer(1);
				ItemStack before = cargo.copy();
				assertFalse(SettlementCargo.isMaterial(cargo));
				assertEquals(0, SettlementStockpile.depositGatheredResources(store, bag));
				assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
			}
		} finally {
			registry.resetTags(); registry.bindTags(original);
		}
	}

	@Test void partialThenFullReturnStripsOnlyAcceptedCopyAndDoesNotMergeCustomItems() {
		ItemStack output = SettlementWorkbenchCargo.output(CAMP, BATCH), before = output.copy();
		SimpleContainer bag = new SimpleContainer(output), store = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 62));
		assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertEquals(64, store.getItem(0).getCount()); assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
		assertTrue(ItemStack.matches(before.copyWithCount(2), bag.getItem(0)));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(before.copyWithCount(2), bag.getItem(0)));
		ItemStack personal = new ItemStack(Items.OAK_PLANKS, 60);
		personal.set(DataComponents.CUSTOM_NAME, Component.literal("Mine")); store.setItem(0, personal);
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(personal, store.getItem(0)));
		store.setItem(0, ItemStack.EMPTY);
		assertEquals(2, SettlementWorkbenchCargo.deposit(bag, store, CAMP)); assertTrue(bag.isEmpty());
		assertEquals(2, store.getItem(0).getCount()); assertFalse(SettlementWorkbenchCargo.isMarked(store.getItem(0)));
	}

	@Test void publicStockCannotUseReservedOrUnknownWorkbenchInputAsStructuralLogs() {
		ItemStack input = SettlementWorkbenchCargo.input(CAMP, BATCH);
		ItemStack future = alter(input, tag -> tag.putInt("Version", 2));
		SimpleContainer stock = new SimpleContainer(input, future, new ItemStack(Items.OAK_LOG, 2));
		assertEquals(2, ConstructionMaterials.available(stock, null, Items.OAK_LOG));
		stock.setItem(2, ItemStack.EMPTY);
		assertEquals(0, ConstructionMaterials.available(stock, null, Items.OAK_LOG));
		assertEquals(0, SettlementMaterialCrafting.countPlain(stock, Items.OAK_LOG));
		assertTrue(ItemStack.matches(input, stock.getItem(0)));
		assertTrue(ItemStack.matches(future, stock.getItem(1)));
	}

	@Test void inputReturnsButWrongCampOrdinaryPlanksAndAliasedContainersDoNot() {
		SimpleContainer bag = new SimpleContainer(SettlementWorkbenchCargo.input(CAMP, BATCH)), store = new SimpleContainer(2);
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, bag, CAMP));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, UUID.randomUUID()));
		assertEquals(1, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
		assertTrue(store.getItem(0).is(Items.OAK_LOG)); assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
		bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
		assertFalse(SettlementCargo.hasCargo(bag)); assertFalse(SettlementWorkbenchCargo.hasReturnable(bag, CAMP));
		assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
	}

	@Test void realDeliveryBoundaryRejectsRemoteAndChangedBindingThenAcceptsArrival() {
		SettlementCargoDeliveryTest.Fixture f = new SettlementCargoDeliveryTest.Fixture();
		f.bag.setItem(0, SettlementWorkbenchCargo.output(f.camp.id(), BATCH));
		ItemStack before = f.bag.getItem(0).copy();
		var ticket = f.prepare();
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, SettlementCargoDelivery.finish(ticket, f.data,
			f.camp, SettlementCargoDeliveryTest.ACTOR, f.bag, f.store, () -> false).status());
		assertTrue(ItemStack.matches(before, f.bag.getItem(0))); assertTrue(f.store.isEmpty());
		ticket = f.prepare(); f.data.setStockpile(f.camp, BlockPos.ZERO.west());
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket).status());
		assertTrue(ItemStack.matches(before, f.bag.getItem(0))); assertTrue(f.store.isEmpty());
		assertEquals(4, f.finish(f.prepare()).moved()); assertTrue(f.bag.isEmpty());
		assertEquals(4, f.store.countItem(Items.OAK_PLANKS)); assertTrue(f.store.getItem(0).getComponentsPatch().isEmpty());
	}

	@Test void deathRecoveryPreservesValidAndUnknownFullComponentsAcrossSaveInBothModes() {
		for (boolean survival : List.of(false, true)) {
			UUID actor = UUID.randomUUID(); SettlementSavedData data = new SettlementSavedData();
			Settlement camp = data.getOrCreate(BlockPos.ZERO, "Cargo", 0); data.registerMember(camp, actor, 0);
			data.getOrCreateSimulation(camp).setSurvivalEnabled(survival, 0);
			var claims = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, data)),
				Level.OVERWORLD, actor);
			ItemStack input = SettlementWorkbenchCargo.input(camp.id(), BATCH);
			ItemStack output = SettlementWorkbenchCargo.output(camp.id(), BATCH);
			ItemStack future = alter(output, tag -> tag.putInt("Version", 8));
			future.set(DataComponents.CUSTOM_NAME, Component.literal("Preserve unknown"));
			ItemStack invalid = new ItemStack(Items.DIAMOND, 3);
			CompoundTag root = new CompoundTag(); root.putString(SettlementWorkbenchCargo.KEY, "unknown"); root.putInt("other", 12);
			invalid.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			List<ItemStack> originals = List.of(input.copy(), output.copy(), future.copy(), invalid.copy());
			SimpleContainer bag = new SimpleContainer(input, output, future, invalid, new ItemStack(Items.OAK_PLANKS, 5));
			SettlementCargoRecovery recovery = new SettlementCargoRecovery();
			assertEquals(12, recovery.captureDeath(claims, Level.OVERWORLD, actor, BlockPos.ZERO, "Worker", () -> bag));
			assertEquals(5, bag.countItem(Items.OAK_PLANKS));
			CompoundTag saved = recovery.save(new CompoundTag(), registries);
			recovery = SettlementCargoRecovery.load(saved, registries); assertTrue(recovery.available());
			assertEquals(saved, recovery.save(new CompoundTag(), registries));
			SimpleContainer rescued = new SimpleContainer(8);
			assertEquals(12, recovery.recover(actor, rescued, () -> true).moved());
			for (int index = 0; index < originals.size(); index++) assertTrue(ItemStack.matches(originals.get(index), rescued.getItem(index)));
		}
	}
}
