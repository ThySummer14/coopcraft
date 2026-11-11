package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Inventory transactions and controlled placement probes only; no running world or navigation. */
class ConstructionPlankSupplyTest {
	private static final ResourceLocation DIM = Level.OVERWORLD.location();
	private static final UUID CAMP = new UUID(94, 1), ACTOR = new UUID(94, 2);
	private static final BlockPos ORIGIN = new BlockPos(12, 64, 12);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Fixture {
		final ConstructionInstruction part = new ConstructionInstruction(BlockPos.ZERO,
			Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS, true);
		final ConstructionPlan plan = new ConstructionPlan(ResourceLocation.parse("coopcraft:plank_supply"), List.of(part));
		final ConstructionJob job = ConstructionJob.start(plan, ORIGIN);
		final SimpleContainer store = new SimpleContainer(new ItemStack(Items.OAK_PLANKS));
		final SimpleContainer bag = new SimpleContainer(8);
		boolean pickup() { return ConstructionSupply.pickup(store, bag, DIM, CAMP, ACTOR, job, part, () -> true); }
		boolean has() { return ConstructionSupply.hasFor(bag, DIM, CAMP, ACTOR, job, part); }
	}

	@Test void lastPlankRequiresArrivalAndBuildsWithoutReopeningItsEmptyStore() {
		var f = new Fixture();
		assertTrue(ConstructionSupply.supported(Items.OAK_PLANKS));
		assertFalse(ConstructionSupply.supported(Items.OAK_LOG));
		assertFalse(ConstructionSupply.supported(Items.COBBLESTONE));
		assertFalse(ConstructionSupply.pickup(f.store, f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> false));
		assertEquals(1, f.store.countItem(Items.OAK_PLANKS)); assertTrue(f.bag.isEmpty());
		assertTrue(f.pickup()); assertTrue(f.store.isEmpty()); assertTrue(f.has());
		assertEquals(1, f.bag.countItem(Items.OAK_PLANKS)); assertFalse(f.pickup());
		assertTrue(ConstructionSystem.materialReady(f.job, f.part, DIM, CAMP, ACTOR, f.bag,
			() -> { throw new AssertionError("Carried last plank must not reopen its store"); },
			() -> { throw new AssertionError("No abstract plank export"); }));
		var probe = new ConstructionStepReadiness.Probe() {
			public boolean inBounds(BlockPos at) { return true; }
			public boolean ticking(BlockPos at) { return true; }
			public BlockState state(BlockPos at) { return Blocks.AIR.defaultBlockState(); }
			public boolean entityObstructed(BlockPos at) { return false; }
			public SettlementHousing.BedCheck inspectBed(BlockPos at) { return fail("No bed in plank plan"); }
		};
		var placed = new AtomicInteger();
		assertTrue(ConstructionSystem.placeIfReady(f.job, f.plan, probe,
			() -> ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> true,
				() -> { assertTrue(f.bag.isEmpty()); placed.incrementAndGet(); return true; })));
		assertEquals(1, placed.get()); assertTrue(f.store.isEmpty()); assertTrue(f.bag.isEmpty());
		assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> true,
			() -> fail("A consumed plank cannot place twice")));
	}

	@Test void failedThrowingAndVetoedPlacementRestoreTheExactPlank() {
		var f = new Fixture(); assertTrue(f.pickup());
		ItemStack held = f.bag.getItem(0).copy();
		assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> true,
			() -> { assertTrue(f.bag.isEmpty()); return false; }));
		assertTrue(ItemStack.matches(held, f.bag.getItem(0)));
		assertThrows(IllegalStateException.class, () -> ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR,
			f.job, f.part, () -> true, () -> { throw new IllegalStateException("World write failed"); }));
		assertTrue(ItemStack.matches(held, f.bag.getItem(0)));
		for (int deniedAt : List.of(2, 3)) {
			var gates = new AtomicInteger();
			assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part,
				() -> gates.incrementAndGet() != deniedAt, () -> fail("Live veto must prevent placement")));
			assertTrue(ItemStack.matches(held, f.bag.getItem(0))); assertTrue(f.store.isEmpty());
		}
	}

	@Test void cancellationStepPlanAndMissingBlueprintReturnOnlyAfterArrival() {
		for (int kind = 0; kind < 5; kind++) {
			var f = new Fixture(); assertTrue(f.pickup());
			ConstructionJob current = switch (kind) {
				case 0 -> null;
				case 1 -> f.job.advance();
				case 2 -> f.job.withPlanFingerprint("changed");
				case 3 -> ConstructionSystem.returnSupplyJob(new SettlementSavedData(), f.job, null);
				default -> ConstructionJob.start(f.plan, ORIGIN);
			};
			ItemStack held = f.bag.getItem(0).copy();
			assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, f.job, () -> true));
			assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, current, f.part, () -> true,
				() -> fail("Obsolete plank purpose must not place")));
			assertTrue(ConstructionSupply.hasReturnable(f.bag, DIM, CAMP, ACTOR, current));
			assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, current, () -> false));
			assertTrue(ItemStack.matches(held, f.bag.getItem(0)));
			assertEquals(1, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, current, () -> true));
			assertTrue(ItemStack.matches(new ItemStack(Items.OAK_PLANKS), f.store.getItem(0)));
			assertTrue(f.bag.isEmpty());
			assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, current, () -> true));
		}
	}

	@Test void protectionAddedDuringPlacementPreservesCargoUntilItsStoreCanAcceptReturn() {
		for (int changedAt : List.of(2, 3)) {
			var f = new Fixture(); var data = new SettlementSavedData();
			var camp = data.getOrCreate(ORIGIN, "Plank return", 0); data.setConstructionJob(camp, f.job);
			assertTrue(f.pickup()); ItemStack held = f.bag.getItem(0).copy(); var gates = new AtomicInteger();
			assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> {
				if (gates.incrementAndGet() == changedAt) {
					assertEquals(PlayerProtectedAreas.Status.ADDED,
						data.addProtectedArea(ACTOR, "Paused", 12, 12, 12, 12).status());
				}
				return !ConstructionProtection.isBlocked(f.plan, ORIGIN, data::isResidentModificationBlocked);
			}, () -> fail("Protected plank must not place")));
			assertTrue(ItemStack.matches(held, f.bag.getItem(0)));
			var before = data.save(new CompoundTag(), null);
			var returnJob = ConstructionSystem.returnSupplyJob(data, f.job, f.plan); assertNull(returnJob);
			f.store.setItem(0, new ItemStack(Items.DIRT, 64));
			assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, returnJob, () -> true));
			assertTrue(ItemStack.matches(held, f.bag.getItem(0)));
			f.store.clearContent();
			assertEquals(1, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, returnJob, () -> true));
			assertTrue(ConstructionSupply.ordinary(f.store.getItem(0))); assertTrue(f.bag.isEmpty());
			assertEquals(before, data.save(new CompoundTag(), null));
			assertEquals(f.job, data.getConstructionJob(camp).orElseThrow());
		}
	}

	@Test void jobAndPlankNbtReloadKeepTheSamePurposeAndAllowOneSuccessfulPlacement() {
		var f = new Fixture(); assertTrue(f.pickup());
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ItemStack restored = ItemStack.parse(registries, f.bag.getItem(0).save(registries)).orElseThrow();
		ConstructionJob restoredJob = ConstructionJob.load(f.job.save());
		assertEquals(f.job, restoredJob); assertTrue(ItemStack.matches(f.bag.getItem(0), restored));
		assertEquals(1, restored.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(ConstructionSupply.KEY).getInt("Version"));
		f.bag.setItem(0, restored);
		assertTrue(ConstructionSupply.hasFor(f.bag, DIM, CAMP, ACTOR, restoredJob, f.part));
		assertFalse(ConstructionSupply.matches(restored, DIM, CAMP, UUID.randomUUID(), restoredJob, f.part));
		assertFalse(ConstructionSupply.matches(restored, DIM, CAMP, ACTOR, ConstructionJob.start(f.plan, ORIGIN), f.part));
		assertTrue(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, restoredJob, f.part, () -> true, () -> true));
		assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, restoredJob, f.part, () -> true,
			() -> fail("Reload must not duplicate supply")));
		assertTrue(f.store.isEmpty()); assertTrue(f.bag.isEmpty());
	}

	@Test void customizedMalformedAndFuturePlanksAreNeverCountedSpentOrLaundered() {
		for (int kind = 0; kind < 6; kind++) {
			var f = new Fixture(); assertTrue(f.pickup()); ItemStack cargo = f.bag.getItem(0);
			CompoundTag root = cargo.get(DataComponents.CUSTOM_DATA).copyTag();
			switch (kind) {
				case 0 -> cargo.set(DataComponents.CUSTOM_NAME, Component.literal("Keep named plank"));
				case 1 -> root.getCompound(ConstructionSupply.KEY).putInt("Version", 99);
				case 2 -> root.putString(ConstructionSupply.KEY, "unknown");
				case 3 -> root.putString("unrelated", "preserve");
				case 4 -> cargo.setCount(2);
				case 5 -> { cargo.remove(DataComponents.CUSTOM_DATA); cargo.set(DataComponents.CUSTOM_NAME, Component.literal("Personal plank")); }
				default -> throw new AssertionError();
			}
			if (kind != 5) cargo.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			ItemStack before = cargo.copy();
			assertFalse(f.has());
			assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> true,
				() -> fail("Custom or unknown supply cannot place")));
			assertEquals(0, ConstructionSupply.returnUnused(f.bag, f.store, DIM, CAMP, ACTOR, null, () -> true));
			assertTrue(ItemStack.matches(before, f.bag.getItem(0)));
			f.store.setItem(0, f.bag.removeItemNoUpdate(0));
			assertEquals(0, ConstructionSupply.countAvailable(f.store, Items.OAK_PLANKS));
			assertEquals(0, ConstructionMaterials.available(f.store, null, Items.OAK_PLANKS));
			assertFalse(f.pickup()); assertTrue(ItemStack.matches(before, f.store.getItem(0)));
		}
	}

	@Test void reservedPlanksCannotPayOtherRecipesOrSuppressPlainPlankDemand() {
		for (var recipe : List.of(SettlementMaterialCrafting.Recipe.DOOR,
			SettlementMaterialCrafting.Recipe.BED, SettlementMaterialCrafting.Recipe.CRAFTING_TABLE)) {
			var f = new Fixture(); assertTrue(f.pickup()); ItemStack reserved = f.bag.getItem(0).copy();
			int needed = recipe.ingredients().get(Items.OAK_PLANKS);
			var stock = new SimpleContainer(8);
			stock.setItem(0, reserved.copy()); stock.setItem(1, new ItemStack(Items.OAK_PLANKS, needed - 1));
			ItemStack named = new ItemStack(Items.OAK_PLANKS, 64);
			named.set(DataComponents.CUSTOM_NAME, Component.literal("Private planks")); stock.setItem(2, named.copy());
			ItemStack unknown = new ItemStack(Items.OAK_PLANKS, 64); var marker = new CompoundTag();
			marker.putString(ConstructionSupply.KEY, "unknown"); unknown.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
			stock.setItem(3, unknown.copy());
			if (recipe == SettlementMaterialCrafting.Recipe.BED) stock.setItem(4, new ItemStack(Items.WHITE_WOOL, 3));
			assertTrue(SettlementMaterialCrafting.chooseRecipe(stock, null, Map.of(recipe.output(), 1)).isEmpty());
			assertFalse(SettlementMaterialCrafting.craft(stock, null, recipe, ACTOR, () -> fail("Insufficient plain planks")));
			stock.setItem(5, new ItemStack(Items.OAK_LOG));
			assertEquals(SettlementMaterialCrafting.Recipe.PLANKS,
				SettlementMaterialCrafting.chooseRecipe(stock, null, Map.of(Items.OAK_PLANKS, needed)).orElseThrow());
			stock.setItem(5, ItemStack.EMPTY); stock.getItem(1).grow(1);
			assertTrue(SettlementMaterialCrafting.craft(stock, null, recipe, ACTOR, () -> {}));
			assertEquals(recipe.count(), stock.countItem(recipe.output()));
			assertEquals(0, SettlementMaterialCrafting.countPlain(stock, Items.OAK_PLANKS));
			assertTrue(ItemStack.matches(reserved, stock.getItem(0)));
			assertTrue(ItemStack.matches(named, stock.getItem(2))); assertTrue(ItemStack.matches(unknown, stock.getItem(3)));
		}
	}

	@Test void personalPlanksAreNeitherConstructionPaymentNorOrdinaryCommunalCargo() {
		var f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 7));
		assertFalse(f.has()); assertFalse(SettlementCargo.hasCargo(f.bag));
		assertFalse(ConstructionSystem.materialReady(f.job, f.part, DIM, CAMP, ACTOR, f.bag,
			() -> new SimpleContainer(1), () -> null));
		assertFalse(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> true,
			() -> fail("Personal plain planks are not assigned payment")));
		assertEquals(0, SettlementStockpile.depositGatheredResources(f.store, f.bag));
		assertTrue(f.pickup()); assertEquals(7, f.bag.getItem(0).getCount());
		assertTrue(ConstructionSupply.matches(f.bag.getItem(1), DIM, CAMP, ACTOR, f.job, f.part));
		assertFalse(SettlementCargo.hasCargo(f.bag));
		assertEquals(0, SettlementStockpile.depositGatheredResources(f.store, f.bag));
		assertTrue(ConstructionSupply.place(f.bag, DIM, CAMP, ACTOR, f.job, f.part, () -> true, () -> true));
		assertTrue(ItemStack.matches(new ItemStack(Items.OAK_PLANKS, 7), f.bag.getItem(0)));
		assertTrue(f.bag.getItem(1).isEmpty()); assertTrue(f.store.isEmpty());
	}
}
