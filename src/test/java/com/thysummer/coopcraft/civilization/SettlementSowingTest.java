package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real item and block states with explicit world callbacks; no server, AI or crop growth is run. */
class SettlementSowingTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static final BlockPos PLOT = new BlockPos(3, 64, 0);
	static class Field implements SettlementSowing.Probe {
		BlockState crop = Blocks.AIR.defaultBlockState(), soil = Blocks.FARMLAND.defaultBlockState();
		BlockPos blocked;
		boolean bounds = true, loaded = true, ticking = true, light = true, writes = true;
		int reads, placements;
		public boolean inBounds(BlockPos pos) { return bounds || !pos.equals(blocked); }
		public boolean loaded(BlockPos pos) { return loaded || !pos.equals(blocked); }
		public boolean ticking(BlockPos pos) { return ticking || !pos.equals(blocked); }
		public BlockState state(BlockPos pos) { reads++; return pos.equals(PLOT.below()) ? soil : crop; }
		public boolean canGrow(BlockPos pos) { return light; }
		public boolean plant(BlockPos pos, BlockState air, BlockState below) {
			placements++;
			if (!writes || !crop.equals(air) || !soil.equals(below)) return false;
			crop = ((CropBlock) Blocks.POTATOES).getStateForAge(0); return true;
		}
	}
	static class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Sowing", 0);
		final UUID actor;
		final SimpleContainer bag = new SimpleContainer(8);
		final Field field;
		boolean live = true;
		Fixture() { this(new UUID(83, 1), new Field()); }
		Fixture(UUID actor, Field field) {
			this.actor = actor; this.field = field; data.registerMember(camp, actor, 0);
			assertEquals(SettlementSowingPlots.Status.CREATED, data.addSowingPlot(camp, PLOT));
			bag.setItem(0, new ItemStack(Items.POTATO, retained() + 3));
		}
		int retained() { return SettlementFoodCargo.retainedFoodPoints(data, camp, actor); }
		SettlementSowing.Ticket prepare() { return SettlementSowing.prepare(data, camp, actor, PLOT, bag, () -> live, field); }
		boolean finish(SettlementSowing.Ticket ticket) { return SettlementSowing.finish(ticket, () -> true); }
		boolean hasSeed() { return SettlementSowing.hasSeed(data, camp, actor, bag); }
	}
	@Test void onlyRealSurplusAboveExistingPersonalityFloorCanBeSown() {
		for (int index = 1; index <= 24; index++) {
			var f = new Fixture(new UUID(83, index), new Field());
			f.bag.setItem(0, new ItemStack(Items.POTATO, f.retained())); assertFalse(f.hasSeed());
			f.bag.getItem(0).grow(1); assertTrue(f.hasSeed());
			assertTrue(f.finish(f.prepare())); assertEquals(f.retained(), f.bag.countItem(Items.POTATO));
			assertFalse(f.hasSeed());
		}
	}
	@Test void mixedFoodPointsCanProtectRationButOnlyAnOrdinaryPotatoIsSeed() {
		var f = new Fixture(); f.bag.clearContent();
		f.bag.setItem(0, new ItemStack(Items.BREAD, 64)); f.bag.setItem(1, new ItemStack(Items.POTATO));
		assertTrue(f.hasSeed()); assertTrue(f.finish(f.prepare()));
		assertEquals(64, f.bag.countItem(Items.BREAD)); assertEquals(0, f.bag.countItem(Items.POTATO));
		for (ItemStack unsuitable : List.of(new ItemStack(Items.POISONOUS_POTATO), new ItemStack(Items.CARROT))) {
			f.bag.setItem(1, unsuitable); assertFalse(f.hasSeed());
		}
		ItemStack named = new ItemStack(Items.POTATO); named.set(DataComponents.CUSTOM_NAME, Component.literal("Personal"));
		f.bag.setItem(1, named); assertFalse(f.hasSeed());
		ItemStack marked = new ItemStack(Items.POTATO);
		CompoundTag custom = new CompoundTag(); custom.putString("coopcraft_food_age", "future");
		marked.set(DataComponents.CUSTOM_DATA, CustomData.of(custom)); f.bag.setItem(1, marked); assertFalse(f.hasSeed());
	}
	@Test void plantingConsumesOneOnceWithoutCreatingStateHistoryOrLegacyMarks() {
		var f = new Fixture(); var before = f.data.save(new CompoundTag(), null); f.data.setDirty(false);
		var ticket = f.prepare(); assertNotNull(ticket); assertEquals(PLOT, ticket.position());
		assertTrue(f.finish(ticket)); assertFalse(f.finish(ticket));
		assertEquals(1, f.field.placements); assertEquals(f.retained() + 2, f.bag.countItem(Items.POTATO));
		assertEquals(((CropBlock) Blocks.POTATOES).getStateForAge(0), f.field.crop);
		assertEquals(before, f.data.save(new CompoundTag(), null)); assertFalse(f.data.isDirty());
		assertTrue(f.data.simulationOf(f.camp).isEmpty()); assertTrue(f.data.resourcesOf(f.camp).isEmpty());
	}
	@Test void failedOrAbsentArrivalAndFailedWorldWriteNeverDeductSeed() {
		var f = new Fixture(); var ticket = f.prepare();
		assertFalse(SettlementSowing.finish(ticket, () -> false)); assertEquals(0, f.field.placements);
		f.field.writes = false; assertFalse(f.finish(ticket)); assertFalse(f.finish(ticket));
		assertEquals(1, f.field.placements); assertEquals(f.retained() + 3, f.bag.countItem(Items.POTATO));
	}
	@Test void consumptionOrSharingDuringArrivalCannotSpendStaleInventory() {
		var f = new Fixture(); var ticket = f.prepare(); AtomicInteger calls = new AtomicInteger();
		assertFalse(SettlementSowing.finish(ticket, () -> {
			if (calls.incrementAndGet() == 2) f.bag.getItem(0).shrink(1);
			return true;
		}));
		assertEquals(0, f.field.placements); assertEquals(f.retained() + 2, f.bag.countItem(Items.POTATO));
		f.bag.setItem(0, new ItemStack(Items.POTATO, f.retained()));
		assertFalse(f.finish(ticket)); assertEquals(0, f.field.placements);
	}
	@Test void missingPermissionProtectionAndReplacementCancelBeforeWorldReads() {
		for (String change : List.of("revoke", "replace", "protected", "member", "body")) {
			var f = new Fixture(); var ticket = f.prepare();
			switch (change) {
				case "revoke" -> f.data.removeSowingPlot(f.camp, PLOT);
				case "replace" -> { f.data.removeSowingPlot(f.camp, PLOT); f.data.addSowingPlot(f.camp, PLOT); }
				case "protected" -> f.data.addProtectedArea(f.actor, "garden", 3, 0, 3, 0);
				case "member" -> { f.data.removeMember(f.actor); f.data.registerMember(f.camp, f.actor, 0); }
				case "body" -> f.live = false;
			}
			f.field.reads = 0; assertFalse(f.finish(ticket)); assertEquals(0, f.field.reads, change);
			assertEquals(0, f.field.placements); assertEquals(f.retained() + 3, f.bag.countItem(Items.POTATO));
		}
	}
	@Test void survivalAndModeAbaCancelWithoutExportingAbstractTubers() {
		var f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
		var ticket = f.prepare(); state.setSurvivalEnabled(true, 0); assertFalse(f.hasSeed());
		state.setSurvivalEnabled(false, 0); assertTrue(f.hasSeed()); assertFalse(f.finish(ticket));
		assertEquals(0, f.field.placements); assertTrue(state.world().inventoryTotals().values().stream().allMatch(value -> value == 0));
		assertTrue(f.finish(f.prepare()));
	}
	@Test void everyCellIsPreflightedBeforeAirSoilOrLightReads() {
		for (BlockPos cell : List.of(PLOT, PLOT.below(), PLOT.above(), PLOT.east(), PLOT.west(), PLOT.north(), PLOT.south())) {
			for (String gate : List.of("bounds", "loaded", "ticking")) {
				var f = new Fixture(); f.field.blocked = cell;
				switch (gate) { case "bounds" -> f.field.bounds = false; case "loaded" -> f.field.loaded = false; case "ticking" -> f.field.ticking = false; }
				assertNull(f.prepare()); assertEquals(0, f.field.reads, gate + " " + cell); assertEquals(0, f.field.placements);
			}
		}
	}
	@Test void occupiedCropUnpreparedGroundAndInsufficientLightDoNotStartOrFinish() {
		for (BlockState other : List.of(Blocks.POTATOES.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), Blocks.WATER.defaultBlockState())) {
			var f = new Fixture(); var ticket = f.prepare(); f.field.crop = other;
			assertNull(f.prepare()); assertFalse(f.finish(ticket)); assertEquals(0, f.field.placements);
		}
		var f = new Fixture(); var ticket = f.prepare(); f.field.soil = Blocks.DIRT.defaultBlockState();
		assertFalse(f.finish(ticket)); f.field.soil = Blocks.FARMLAND.defaultBlockState(); f.field.light = false;
		assertFalse(f.finish(ticket)); assertNull(f.prepare());
	}
	@Test void twoRealBagsCannotBothSpendSeedOnTheSameCell() {
		var first = new Fixture(); UUID secondId = new UUID(83, 2); first.data.registerMember(first.camp, secondId, 0);
		var secondBag = new SimpleContainer(8); secondBag.setItem(0, new ItemStack(Items.POTATO, 32));
		var one = first.prepare();
		var two = SettlementSowing.prepare(first.data, first.camp, secondId, PLOT, secondBag, () -> true, first.field);
		assertNotNull(two); assertTrue(first.finish(one)); assertFalse(SettlementSowing.finish(two, () -> true));
		assertEquals(1, first.field.placements); assertEquals(32, secondBag.countItem(Items.POTATO));
	}
	@Test void plantedPlotLaterUsesExistingHarvestAndSeedReservationWithoutExtraAuthorization() {
		var f = new Fixture(); assertTrue(f.finish(f.prepare()));
		f.field.crop = ((CropBlock) Blocks.POTATOES).getStateForAge(7); // Growth is supplied, not simulated.
		assertTrue(SettlementCropHarvest.validTarget(f.data, f.camp, PLOT, () -> true, () -> true, () -> f.field.crop));
		assertEquals(2, SettlementCropHarvest.harvest(f.data, f.camp, f.actor, PLOT, f.bag, () -> true, () -> true, () -> true,
			() -> f.field.crop, () -> List.of(new ItemStack(Items.POTATO, 3)), mature -> {
				f.field.crop = ((CropBlock) Blocks.POTATOES).getStateForAge(0); return true;
			}));
		assertEquals(f.retained() + 4, f.bag.countItem(Items.POTATO)); assertTrue(f.data.resourcesOf(f.camp).isEmpty());
		f.data.removeSowingPlot(f.camp, PLOT); f.field.crop = ((CropBlock) Blocks.POTATOES).getStateForAge(7);
		assertFalse(SettlementCropHarvest.validTarget(f.data, f.camp, PLOT, () -> true, () -> true, () -> f.field.crop));
	}
}
