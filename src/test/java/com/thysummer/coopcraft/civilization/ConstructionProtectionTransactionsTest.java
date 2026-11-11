package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Transaction/probe tests only; no running game, server or villager navigation. */
class ConstructionProtectionTransactionsTest {
	private static final UUID CAMP = new UUID(92, 1), ACTOR = new UUID(92, 2), OWNER = new UUID(92, 3);
	private static final BlockPos ORIGIN = new BlockPos(12, 64, 12);
	private static final ResourceLocation DIM = Level.OVERWORLD.location();
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static ConstructionPlan door() {
		var lower = Blocks.OAK_DOOR.defaultBlockState();
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:protected_delivery"), List.of(
			new ConstructionInstruction(BlockPos.ZERO, lower, Items.OAK_DOOR, true),
			new ConstructionInstruction(BlockPos.ZERO.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Items.OAK_DOOR, false)));
	}

	private static ConstructionPlan stone() {
		return new ConstructionPlan(ResourceLocation.parse("coopcraft:protected_stone"), List.of(
			new ConstructionInstruction(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true)));
	}

	private static UUID protect(SettlementSavedData data) {
		var result = data.addProtectedArea(OWNER, "Paused build", 12, 12, 12, 12);
		assertEquals(PlayerProtectedAreas.Status.ADDED, result.status()); return result.area().id();
	}

	private static BooleanSupplier allowed(SettlementSavedData data, ConstructionPlan plan) {
		return () -> !ConstructionProtection.isBlocked(plan, ORIGIN, data::isResidentModificationBlocked);
	}

	private static SettlementSimulationState simulation() {
		var simulation = SettlementSimulationState.create(new Settlement(CAMP, ORIGIN, "Protected materials", 0));
		simulation.setSurvivalEnabled(true, 0); simulation.setProductionEnabled(true);
		simulation.world().importExternalBatch(ResourceType.STONE, 3, simulation.campOwner());
		return simulation;
	}

	private static Container unreadable() {
		return (Container) Proxy.newProxyInstance(Container.class.getClassLoader(), new Class<?>[] {Container.class},
			(proxy, method, args) -> { throw new AssertionError("Unexpected inventory call: " + method.getName()); });
	}

	@Test void existingProtectionStopsPickupCarriedPlacementAndLegacyExportBeforeAnyInventoryCalls() {
		var data = new SettlementSavedData(); protect(data);
		var plan = door(); var job = ConstructionJob.start(plan, ORIGIN); var part = plan.instructions().getFirst();
		var forbidden = unreadable(); var gate = allowed(data, plan);
		assertFalse(ConstructionSupply.pickup(forbidden, new SimpleContainer(1), DIM, CAMP, ACTOR, job, part, gate));
		assertFalse(ConstructionSupply.place(forbidden, DIM, CAMP, ACTOR, job, part, gate, () -> fail("No placement")));
		var state = simulation(); var before = state.save();
		assertFalse(ConstructionMaterials.place(forbidden, state, Items.COBBLESTONE, true, job, ACTOR,
			() -> fail("No export"), gate, () -> fail("No world write")));
		assertFalse(ConstructionMaterials.place(forbidden, state, Items.OAK_DOOR, false, job.advance(), ACTOR,
			() -> fail("No export"), gate, () -> fail("No free continuation")));
		assertEquals(before, state.save()); state.world().assertConservation();
	}

	@Test void protectionAddedAtFinalPickupGateMovesNoActualItem() {
		var data = new SettlementSavedData(); var plan = door(); var job = ConstructionJob.start(plan, ORIGIN);
		var store = new SimpleContainer(new ItemStack(Items.OAK_DOOR)); var bag = new SimpleContainer(1);
		var calls = new AtomicInteger();
		assertFalse(ConstructionSupply.pickup(store, bag, DIM, CAMP, ACTOR, job, plan.instructions().getFirst(), () -> {
			if (calls.incrementAndGet() == 2) protect(data);
			return allowed(data, plan).getAsBoolean();
		}));
		assertEquals(1, store.countItem(Items.OAK_DOOR)); assertTrue(bag.isEmpty());
	}

	@Test void protectionAddedBeforeReservationOrAtTheLastPlacementGateRestoresExactPhysicalCargo() {
		for (int changedAt : List.of(2, 3)) {
			var data = new SettlementSavedData(); var plan = door(); var job = ConstructionJob.start(plan, ORIGIN);
			var part = plan.instructions().getFirst(); var store = new SimpleContainer(new ItemStack(Items.OAK_DOOR));
			var bag = new SimpleContainer(1);
			assertTrue(ConstructionSupply.pickup(store, bag, DIM, CAMP, ACTOR, job, part, () -> true));
			ItemStack original = bag.getItem(0).copy(); var calls = new AtomicInteger(); var saved = job.save();
			assertFalse(ConstructionSupply.place(bag, DIM, CAMP, ACTOR, job, part, () -> {
				if (calls.incrementAndGet() == changedAt) protect(data);
				return allowed(data, plan).getAsBoolean();
			}, () -> fail("No world write after the live protection veto")));
			assertTrue(ItemStack.matches(original, bag.getItem(0))); assertTrue(store.isEmpty());
			assertEquals(saved, job.save());
		}
	}

	@Test void finalWorldCallbackVetoRestoresCargoEvenAfterEarlierAuthorityChecksPassed() {
		var data = new SettlementSavedData(); var plan = door(); var job = ConstructionJob.start(plan, ORIGIN);
		var part = plan.instructions().getFirst(); var bag = new SimpleContainer(1);
		var store = new SimpleContainer(new ItemStack(Items.OAK_DOOR));
		assertTrue(ConstructionSupply.pickup(store, bag, DIM, CAMP, ACTOR, job, part, () -> true));
		ItemStack original = bag.getItem(0).copy(); var writes = new AtomicInteger();
		assertFalse(ConstructionSupply.place(bag, DIM, CAMP, ACTOR, job, part, allowed(data, plan), () -> {
			assertTrue(bag.isEmpty()); protect(data);
			return allowed(data, plan).getAsBoolean() && writes.incrementAndGet() > 0;
		}));
		assertEquals(0, writes.get()); assertTrue(ItemStack.matches(original, bag.getItem(0)));
	}

	@Test void legacyRealAndAbstractMaterialPathsRecheckBeforeExportAndFinalPlacement() {
		for (boolean abstractStock : List.of(false, true)) {
			var data = new SettlementSavedData(); var plan = stone(); var job = ConstructionJob.start(plan, ORIGIN);
			var state = simulation(); var before = state.save(); var calls = new AtomicInteger();
			var store = new SimpleContainer(1); if (!abstractStock) store.setItem(0, new ItemStack(Items.COBBLESTONE));
			assertFalse(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, ACTOR,
				() -> fail("No export after final authority changed"), () -> {
					if (calls.incrementAndGet() == 2) protect(data);
					return allowed(data, plan).getAsBoolean();
				}, () -> fail("No world write")));
			assertEquals(abstractStock ? 0 : 1, store.countItem(Items.COBBLESTONE));
			assertEquals(before, state.save()); state.world().assertConservation();
		}
	}

	@Test void protectionAppearingAfterACommittedExportRetainsThatUnitAndDoesNotPlaceOrConsumeIt() {
		var data = new SettlementSavedData(); var plan = stone(); var job = ConstructionJob.start(plan, ORIGIN);
		var state = simulation(); var store = new SimpleContainer(1); var dirty = new AtomicInteger();
		assertFalse(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, ACTOR,
			() -> { dirty.incrementAndGet(); protect(data); }, allowed(data, plan), () -> fail("No world write")));
		assertEquals(1, dirty.get()); assertEquals(1, store.countItem(Items.COBBLESTONE));
		assertEquals(3, ConstructionMaterials.available(store, state, Items.COBBLESTONE)); state.world().assertConservation();
	}

	@Test void legacyRealMaterialAndFreeHalfAlsoHonorProtectionAddedInTheFinalWorldCallback() {
		for (boolean consume : List.of(true, false)) {
			var data = new SettlementSavedData(); var plan = stone(); var job = ConstructionJob.start(plan, ORIGIN);
			var store = new SimpleContainer(new ItemStack(Items.COBBLESTONE)); var writes = new AtomicInteger();
			assertFalse(ConstructionMaterials.place(store, null, Items.COBBLESTONE, consume, job, ACTOR,
				() -> fail("No export"), allowed(data, plan), () -> {
					protect(data); return allowed(data, plan).getAsBoolean() && writes.incrementAndGet() > 0;
				}));
			assertEquals(1, store.countItem(Items.COBBLESTONE)); assertEquals(0, writes.get());
		}
	}

	@Test void pausedCurrentDeliveryReturnsOnceWithoutChangingJobAndFullStoreKeepsItsPurpose() {
		var data = new SettlementSavedData(); var plan = door(); var job = ConstructionJob.start(plan, ORIGIN);
		var camp = data.getOrCreate(ORIGIN, "Return", 0); data.setConstructionJob(camp, job);
		var part = plan.instructions().getFirst(); var bag = new SimpleContainer(1);
		var store = new SimpleContainer(new ItemStack(Items.OAK_DOOR));
		assertTrue(ConstructionSupply.pickup(store, bag, DIM, CAMP, ACTOR, job, part, () -> true));
		assertFalse(ConstructionSupply.hasReturnable(bag, DIM, CAMP, ACTOR, ConstructionSystem.returnSupplyJob(data, job, plan)));
		UUID area = protect(data); var before = data.save(new CompoundTag(), null); data.setDirty(false);
		assertTrue(ConstructionSupply.hasReturnable(bag, DIM, CAMP, ACTOR, ConstructionSystem.returnSupplyJob(data, job, plan)));
		ItemStack held = bag.getItem(0).copy(); store.setItem(0, new ItemStack(Items.DIRT, 64));
		assertEquals(0, ConstructionSupply.returnUnused(bag, store, DIM, CAMP, ACTOR,
			ConstructionSystem.returnSupplyJob(data, job, plan), () -> true));
		assertTrue(ItemStack.matches(held, bag.getItem(0)));
		assertTrue(ConstructionSupply.hasFor(bag, DIM, CAMP, ACTOR, job, part));
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		assertEquals(PlayerProtectedAreas.Status.REMOVED, data.removeProtectedArea(area, OWNER, false));
		assertSame(job, ConstructionSystem.returnSupplyJob(data, job, plan));
		assertFalse(ConstructionSupply.hasReturnable(bag, DIM, CAMP, ACTOR, job));
		protect(data); store.clearContent();
		assertEquals(1, ConstructionSupply.returnUnused(bag, store, DIM, CAMP, ACTOR,
			ConstructionSystem.returnSupplyJob(data, job, plan), () -> true));
		assertTrue(bag.isEmpty()); assertEquals(1, store.countItem(Items.OAK_DOOR));
		assertTrue(ConstructionSupply.ordinary(store.getItem(0)));
		assertEquals(0, ConstructionSupply.returnUnused(bag, store, DIM, CAMP, ACTOR, null, () -> true));
		assertEquals(job, data.getConstructionJob(camp).orElseThrow());
	}
}
