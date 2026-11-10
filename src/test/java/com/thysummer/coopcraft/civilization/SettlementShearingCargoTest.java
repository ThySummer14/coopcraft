package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real containers and NBT contracts only; these tests do not shear an entity or start a server. */
class SettlementShearingCargoTest {
	private static final UUID CAMP = new UUID(76, 1), BATCH = new UUID(76, 2),
		SHEEP = new UUID(76, 3), PERMIT = new UUID(76, 4), FOREIGN = new UUID(76, 5);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static ItemStack cargo(UUID camp, int count) {
		return SettlementShearingCargo.mark(new ItemStack(Items.WHITE_WOOL, count), camp, BATCH, SHEEP, PERMIT);
	}

	private static ItemStack alter(ItemStack source, Consumer<CompoundTag> edit) {
		ItemStack stack = source.copy();
		CompoundTag root = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		edit.accept(root.getCompound(SettlementShearingCargo.KEY));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	private static ItemStack markerOn(Item item, int count) {
		ItemStack stack = new ItemStack(item, count);
		CompoundTag root = new CompoundTag(); root.putString(SettlementShearingCargo.KEY, "unknown");
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	@Test void stampsOnlyTheActualStackCopyAndAllRemainingCountsSurviveNbt() {
		for (int count = 1; count <= 64; count++) {
			ItemStack actual = new ItemStack(Items.WHITE_WOOL, count);
			ItemStack marked = SettlementShearingCargo.mark(actual, CAMP, BATCH, SHEEP, PERMIT);
			assertNotSame(actual, marked); assertEquals(count, marked.getCount());
			assertTrue(actual.getComponentsPatch().isEmpty()); assertEquals(count, actual.getCount());
			CompoundTag stamp = marked.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(SettlementShearingCargo.KEY);
			assertEquals(Set.of("Version", "Settlement", "Batch", "Sheep", "Permit"), stamp.getAllKeys());
			assertEquals(1, stamp.getInt("Version")); assertEquals(CAMP, stamp.getUUID("Settlement"));
			assertEquals(BATCH, stamp.getUUID("Batch")); assertEquals(SHEEP, stamp.getUUID("Sheep"));
			assertEquals(PERMIT, stamp.getUUID("Permit"));
			ItemStack loaded = ItemStack.parseOptional(registries, (CompoundTag) marked.save(registries));
			assertTrue(ItemStack.matches(marked, loaded));
			SimpleContainer bag = new SimpleContainer(loaded);
			assertTrue(SettlementShearingCargo.hasReturnable(bag, CAMP));
			assertFalse(SettlementShearingCargo.hasReturnable(bag, FOREIGN));
			marked.shrink(1); assertEquals(count, actual.getCount()); assertEquals(count, loaded.getCount());
		}
	}

	@Test void rejectsNonActualWoolCustomComponentsAndMissingIdentityWithoutMutation() {
		ItemStack named = new ItemStack(Items.WHITE_WOOL, 3);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Personal"));
		ItemStack removedDefault = new ItemStack(Items.WHITE_WOOL); removedDefault.remove(DataComponents.MAX_STACK_SIZE);
		for (ItemStack invalid : List.of(ItemStack.EMPTY, new ItemStack(Items.WHITE_WOOL, 65),
			new ItemStack(Items.WHITE_WOOL, 0), new ItemStack(Items.RED_WOOL), new ItemStack(Items.STRING),
			named, removedDefault, cargo(CAMP, 3), markerOn(Items.WHITE_WOOL, 3))) {
			ItemStack before = invalid.copy();
			assertThrows(IllegalArgumentException.class, () -> SettlementShearingCargo.mark(invalid, CAMP, BATCH, SHEEP, PERMIT));
			assertTrue(ItemStack.matches(before, invalid));
		}
		assertThrows(IllegalArgumentException.class, () -> SettlementShearingCargo.mark(null, CAMP, BATCH, SHEEP, PERMIT));
		ItemStack actual = new ItemStack(Items.WHITE_WOOL, 3);
		assertThrows(IllegalArgumentException.class, () -> SettlementShearingCargo.mark(actual, null, BATCH, SHEEP, PERMIT));
		assertThrows(IllegalArgumentException.class, () -> SettlementShearingCargo.mark(actual, CAMP, null, SHEEP, PERMIT));
		assertThrows(IllegalArgumentException.class, () -> SettlementShearingCargo.mark(actual, CAMP, BATCH, null, PERMIT));
		assertThrows(IllegalArgumentException.class, () -> SettlementShearingCargo.mark(actual, CAMP, BATCH, SHEEP, null));
		assertTrue(actual.getComponentsPatch().isEmpty()); assertEquals(3, actual.getCount());
	}

	@Test void exactV1FieldsTypesItemsAndComponentsAreRequiredForReturn() {
		ItemStack valid = cargo(CAMP, 3);
		List<ItemStack> invalids = new ArrayList<>(List.of(
			alter(valid, tag -> tag.putInt("Version", 2)), alter(valid, tag -> tag.putInt("Version", 0)),
			alter(valid, tag -> tag.putByte("Version", (byte) 1)), alter(valid, tag -> tag.putInt("Extra", 1)),
			markerOn(Items.WHITE_WOOL, 3), markerOn(Items.RED_WOOL, 3), valid.copyWithCount(65)));
		for (String key : List.of("Settlement", "Batch", "Sheep", "Permit")) {
			invalids.add(alter(valid, tag -> tag.remove(key)));
			invalids.add(alter(valid, tag -> tag.putString(key, CAMP.toString())));
			invalids.add(alter(valid, tag -> tag.putIntArray(key, new int[] { 1, 2, 3 })));
		}
		ItemStack named = valid.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
		ItemStack removedDefault = valid.copy(); removedDefault.remove(DataComponents.MAX_STACK_SIZE);
		ItemStack extraRoot = valid.copy();
		CompoundTag root = extraRoot.get(DataComponents.CUSTOM_DATA).copyTag(); root.putInt("other", 1);
		extraRoot.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		ItemStack wrongItem = new ItemStack(Items.RED_WOOL, 3);
		wrongItem.set(DataComponents.CUSTOM_DATA, valid.get(DataComponents.CUSTOM_DATA));
		invalids.addAll(List.of(named, removedDefault, extraRoot, wrongItem));
		for (ItemStack invalid : invalids) {
			ItemStack before = invalid.copy();
			SimpleContainer bag = new SimpleContainer(invalid), store = new SimpleContainer(2);
			assertTrue(SettlementShearingCargo.isMarked(invalid));
			assertFalse(SettlementShearingCargo.hasReturnable(bag, CAMP));
			assertEquals(0, SettlementShearingCargo.deposit(bag, store, CAMP));
			assertEquals(0, SettlementMaterialCrafting.countPlain(bag, invalid.getItem()));
			assertEquals(0, SettlementWorkbenchCargo.deposit(bag, store, CAMP));
			assertTrue(ItemStack.matches(before, bag.getItem(0))); assertTrue(store.isEmpty());
		}
	}

	@Test void onlyAcceptedUnitsLoseTheMarkerAndTheSavedRemainderKeepsItsIdentity() {
		ItemStack original = cargo(CAMP, 3);
		SimpleContainer bag = new SimpleContainer(original.copy()), store = new SimpleContainer(new ItemStack(Items.WHITE_WOOL, 63));
		assertEquals(1, SettlementShearingCargo.deposit(bag, store, CAMP));
		assertEquals(64, store.countItem(Items.WHITE_WOOL)); assertTrue(store.getItem(0).getComponentsPatch().isEmpty());
		assertTrue(ItemStack.matches(original.copyWithCount(2), bag.getItem(0)));
		assertEquals(66, bag.countItem(Items.WHITE_WOOL) + store.countItem(Items.WHITE_WOOL));
		assertEquals(0, SettlementShearingCargo.deposit(bag, store, CAMP));
		bag.setItem(0, ItemStack.parseOptional(registries, (CompoundTag) bag.getItem(0).save(registries)));
		store.setItem(0, ItemStack.EMPTY);
		assertEquals(2, SettlementShearingCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(new ItemStack(Items.WHITE_WOOL, 2), store.getItem(0)));
		assertTrue(bag.isEmpty()); assertEquals(0, SettlementShearingCargo.deposit(bag, store, CAMP));
	}

	@Test void returnRespectsSlotRejectionContainerLimitsAndDoesNotMergeAssignedStacks() {
		SimpleContainer bag = new SimpleContainer(cargo(CAMP, 3));
		SimpleContainer rejects = new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		};
		assertEquals(0, SettlementShearingCargo.deposit(bag, rejects, CAMP)); assertTrue(rejects.isEmpty());
		SimpleContainer limited = new SimpleContainer(1) {
			@Override public int getMaxStackSize() { return 1; }
		};
		assertEquals(1, SettlementShearingCargo.deposit(bag, limited, CAMP));
		assertEquals(2, bag.countItem(Items.WHITE_WOOL)); assertEquals(1, limited.countItem(Items.WHITE_WOOL));
		assertEquals(0, SettlementShearingCargo.deposit(bag, limited, CAMP));
		SimpleContainer assigned = new SimpleContainer(cargo(CAMP, 1));
		assertEquals(0, SettlementShearingCargo.deposit(bag, assigned, CAMP));
		assertTrue(SettlementShearingCargo.isMarked(assigned.getItem(0)));
	}

	@Test void foreignAndOrdinaryStacksStayUntouchedAndIdentityIsNeverRewritten() {
		ItemStack ordinary = new ItemStack(Items.WHITE_WOOL, 4), foreign = cargo(FOREIGN, 3);
		ItemStack recipe = SettlementWorkbenchCargo.output(CAMP, BATCH, SettlementMaterialCrafting.Recipe.WOOL);
		SimpleContainer bag = new SimpleContainer(cargo(CAMP, 2), foreign.copy(), ordinary.copy(), recipe.copy());
		SimpleContainer store = new SimpleContainer(2);
		assertEquals(2, SettlementShearingCargo.deposit(bag, store, CAMP));
		assertEquals(0, SettlementShearingCargo.deposit(bag, store, CAMP));
		assertTrue(ItemStack.matches(foreign, bag.getItem(1))); assertTrue(ItemStack.matches(ordinary, bag.getItem(2)));
		assertTrue(ItemStack.matches(recipe, bag.getItem(3)));
		assertFalse(SettlementShearingCargo.hasReturnable(bag, CAMP));
		assertTrue(SettlementShearingCargo.hasReturnable(bag, FOREIGN));
		assertEquals(3, SettlementShearingCargo.deposit(bag, store, FOREIGN));
		assertEquals(5, store.countItem(Items.WHITE_WOOL));
	}

	@Test void nullEmptyAndSameContainerNeverMutateCustody() {
		SimpleContainer bag = new SimpleContainer(cargo(CAMP, 3)), store = new SimpleContainer(1);
		assertFalse(SettlementShearingCargo.isMarked(null)); assertFalse(SettlementShearingCargo.isMarked(ItemStack.EMPTY));
		assertFalse(SettlementShearingCargo.hasReturnable(null, CAMP));
		assertFalse(SettlementShearingCargo.hasReturnable(bag, null));
		assertEquals(0, SettlementShearingCargo.deposit(null, store, CAMP));
		assertEquals(0, SettlementShearingCargo.deposit(bag, null, CAMP));
		assertEquals(0, SettlementShearingCargo.deposit(bag, store, null));
		assertEquals(0, SettlementShearingCargo.deposit(bag, bag, CAMP));
		assertTrue(ItemStack.matches(cargo(CAMP, 3), bag.getItem(0))); assertTrue(store.isEmpty());
	}

	@Test void evenWrongItemMarkersStayOutOfOrdinaryMaterialsRationsAndTransfers() {
		for (Item item : List.of(Items.WHITE_WOOL, Items.OAK_LOG, Items.COBBLESTONE, Items.STICK,
			Items.SMOOTH_STONE, Items.OAK_PLANKS, Items.POTATO, Items.SWEET_BERRIES)) {
			ItemStack unknown = markerOn(item, 3);
			SimpleContainer bag = new SimpleContainer(unknown.copy()), store = new SimpleContainer(2);
			assertFalse(SettlementCargo.isMaterial(unknown)); assertFalse(SettlementFoodCargo.isFood(unknown));
			assertEquals(0, SettlementFood.points(unknown)); assertEquals(0, SettlementNeeds.consumeOneFood(bag));
			assertEquals(0, ConstructionMaterials.available(bag, null, item));
			assertEquals(0, SettlementMaterialCrafting.countPlain(bag, item));
			assertTrue(MinecraftStockpileAdapter.resourceType(unknown).isEmpty());
			assertEquals(0, SettlementStockpile.depositGatheredResources(store, bag));
			assertEquals(0, SettlementStockpile.depositItem(store, bag, item));
			assertEquals(0, SettlementStockpile.transferItem(bag, store, item, 3));
			assertTrue(ItemStack.matches(unknown, bag.getItem(0))); assertTrue(store.isEmpty());
		}
	}

	private static final class Fixture {
		final UUID actor = UUID.randomUUID();
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Shearing", 0);
		final SimpleContainer bag = new SimpleContainer(8), store = new SimpleContainer(2);
		final AtomicBoolean present = new AtomicBoolean(true);
		Fixture() { data.registerMember(camp, actor, 0); data.setStockpile(camp, BlockPos.ZERO.east()); }
		SettlementCargoDelivery.Ticket prepare() { return SettlementCargoDelivery.prepare(data, camp, actor, bag, present::get); }
		SettlementCargoDelivery.Result finish(SettlementCargoDelivery.Ticket ticket, boolean arrived) {
			return SettlementCargoDelivery.finish(ticket, data, camp, actor, bag, store, () -> arrived);
		}
		SettlementMembershipClaims.Resolution claims() {
			return SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, data)),
				Level.OVERWORLD, actor);
		}
	}

	@Test void deliveryRequiresActualArrivalAndConsumesEachAttempt() {
		Fixture f = new Fixture(); f.bag.setItem(0, cargo(f.camp.id(), 3));
		var ticket = f.prepare(); assertNotNull(ticket);
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.finish(ticket, false).status());
		assertEquals(3, f.bag.countItem(Items.WHITE_WOOL)); assertTrue(f.store.isEmpty());
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, true).status());
		assertEquals(3, f.finish(f.prepare(), true).moved());
		assertEquals(0, f.finish(f.prepare(), true).moved());
		assertTrue(f.bag.isEmpty()); assertTrue(ItemStack.matches(new ItemStack(Items.WHITE_WOOL, 3), f.store.getItem(0)));
	}

	@Test void changedStoreMembershipPresenceOrInventoryCannotDeliverShearingCargo() {
		for (int change = 0; change < 4; change++) {
			Fixture f = new Fixture(); f.bag.setItem(0, cargo(f.camp.id(), 3)); var ticket = f.prepare();
			SimpleContainer currentBag = f.bag;
			switch (change) {
				case 0 -> f.present.set(false);
				case 1 -> { f.data.removeMember(f.actor); f.data.registerMember(f.camp, f.actor, 0); }
				case 2 -> { f.data.setStockpile(f.camp, BlockPos.ZERO.west()); f.data.setStockpile(f.camp, BlockPos.ZERO.east()); }
				case 3 -> currentBag = new SimpleContainer(cargo(f.camp.id(), 3));
			}
			assertEquals(SettlementCargoDelivery.Status.INVALID,
				SettlementCargoDelivery.finish(ticket, f.data, f.camp, f.actor, currentBag, f.store, () -> true).status());
			assertEquals(3, f.bag.countItem(Items.WHITE_WOOL)); assertTrue(f.store.isEmpty());
		}
	}

	@Test void revokedPermitDoesNotInvalidateAlreadyCarriedCargoOrCreateAbstractWool() {
		for (boolean survival : List.of(false, true)) {
			Fixture f = new Fixture();
			var state = f.data.getOrCreateSimulation(f.camp);
			state.mapResident(f.actor, false, 0); state.setSurvivalEnabled(survival, 0); state.setProductionEnabled(true);
			var permit = f.data.permitShearing(f.camp, f.actor, SHEEP).permit(); assertNotNull(permit);
			f.bag.setItem(0, SettlementShearingCargo.mark(new ItemStack(Items.WHITE_WOOL, 3), f.camp.id(), BATCH, SHEEP, permit.id()));
			var ticket = f.prepare();
			assertEquals(SettlementShearingPermits.Status.REMOVED, f.data.revokeShearing(f.camp, SHEEP));
			assertTrue(f.data.shearingPermitOf(SHEEP).isEmpty());
			assertTrue(SettlementCargoDelivery.valid(ticket, f.data, f.camp, f.actor, f.bag));
			var before = List.copyOf(state.world().batches());
			assertEquals(3, f.finish(ticket, true).moved());
			assertEquals(before, state.world().batches()); state.world().assertConservation();
		}
	}

	@Test void deathCustodyPreservesForeignFutureMalformedAndCustomizedStacksAcrossReloadInEitherMode() {
		for (boolean survival : List.of(false, true)) {
			Fixture f = new Fixture();
			var state = f.data.getOrCreateSimulation(f.camp); state.setSurvivalEnabled(survival, 0);
			ItemStack named = cargo(f.camp.id(), 2); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
			ItemStack removedDefault = cargo(f.camp.id(), 1); removedDefault.remove(DataComponents.MAX_STACK_SIZE);
			List<ItemStack> originals = List.of(cargo(f.camp.id(), 3), cargo(FOREIGN, 2),
				alter(cargo(f.camp.id(), 1), tag -> tag.putInt("Version", 99)), markerOn(Items.WHITE_WOOL, 4),
				named, removedDefault, markerOn(Items.RED_WOOL, 2));
			for (int slot = 0; slot < originals.size(); slot++) f.bag.setItem(slot, originals.get(slot).copy());
			f.bag.setItem(7, new ItemStack(Items.WHITE_WOOL, 5));
			int total = originals.stream().mapToInt(ItemStack::getCount).sum();
			SettlementCargoRecovery recovery = new SettlementCargoRecovery();
			assertEquals(total, recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, BlockPos.ZERO, "Worker", () -> f.bag));
			assertEquals(5, f.bag.countItem(Items.WHITE_WOOL));
			assertEquals(0, recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, BlockPos.ZERO, "Worker", () -> f.bag));
			CompoundTag saved = recovery.save(new CompoundTag(), registries);
			recovery = SettlementCargoRecovery.load(saved, registries);
			assertTrue(recovery.available()); assertEquals(saved, recovery.save(new CompoundTag(), registries));
			SimpleContainer rescued = new SimpleContainer(8);
			assertEquals(SettlementCargoRecovery.Status.NOT_AT_PLACE, recovery.recover(f.actor, rescued, () -> false).status());
			assertTrue(rescued.isEmpty()); assertEquals(total, recovery.inspect(f.actor).orElseThrow().totalCount());
			assertEquals(total, recovery.recover(f.actor, rescued, () -> true).moved());
			for (int slot = 0; slot < originals.size(); slot++) assertTrue(ItemStack.matches(originals.get(slot), rescued.getItem(slot)));
			assertTrue(recovery.inspect(f.actor).isEmpty());
			assertEquals(0, recovery.recover(f.actor, rescued, () -> true).moved());
			assertEquals(3, SettlementShearingCargo.deposit(rescued, f.store, f.camp.id()));
			for (int slot = 1; slot < originals.size(); slot++) assertTrue(ItemStack.matches(originals.get(slot), rescued.getItem(slot)));
		}
	}

	@Test void partialReturnThenDeathAndPartialRecoveryConserveOnlyTheActualRemainingWool() {
		Fixture f = new Fixture(); ItemStack original = cargo(f.camp.id(), 3); f.bag.setItem(0, original.copy());
		f.store.setItem(0, new ItemStack(Items.WHITE_WOOL, 63)); f.store.setItem(1, new ItemStack(Items.DIRT, 64));
		assertEquals(1, f.finish(f.prepare(), true).moved());
		assertTrue(ItemStack.matches(original.copyWithCount(2), f.bag.getItem(0)));
		SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		assertEquals(2, recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, BlockPos.ZERO, "Worker", () -> f.bag));
		SimpleContainer rescued = new SimpleContainer(1) {
			@Override public int getMaxStackSize() { return 1; }
		};
		var first = recovery.recover(f.actor, rescued, () -> true);
		assertEquals(1, first.moved()); assertEquals(1, first.remaining());
		assertTrue(ItemStack.matches(original.copyWithCount(1), rescued.getItem(0)));
		assertEquals(0, recovery.recover(f.actor, rescued, () -> true).moved());
		recovery = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
		assertEquals(66, f.store.countItem(Items.WHITE_WOOL) + rescued.countItem(Items.WHITE_WOOL)
			+ recovery.inspect(f.actor).orElseThrow().totalCount());
		f.store.setItem(1, ItemStack.EMPTY);
		assertEquals(1, SettlementShearingCargo.deposit(rescued, f.store, f.camp.id()));
		assertEquals(1, recovery.recover(f.actor, rescued, () -> true).moved());
		assertEquals(1, SettlementShearingCargo.deposit(rescued, f.store, f.camp.id()));
		assertEquals(66, f.store.countItem(Items.WHITE_WOOL)); assertTrue(f.bag.isEmpty()); assertTrue(rescued.isEmpty());
		assertTrue(recovery.inspect(f.actor).isEmpty());
	}
}
