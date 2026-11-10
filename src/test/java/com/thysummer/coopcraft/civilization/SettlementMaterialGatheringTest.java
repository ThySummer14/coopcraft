package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static com.thysummer.coopcraft.civilization.SettlementResourceObservations.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real service and Minecraft containers, with only world access replaced by a deterministic probe. */
class SettlementMaterialGatheringTest {
	private static final UUID ACTOR = new UUID(943, 1), OTHER = new UUID(943, 2);
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0), TARGET = new BlockPos(2, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Fixture implements SettlementMaterialGathering.Probe {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(ORIGIN, "Home", 0);
		SettlementSavedData current = data;
		BlockState block = Blocks.OAK_LOG.defaultBlockState();
		boolean adult = true, bounds = true, loaded = true, ticking = true;
		double distance = 4;
		int reads, lootCalls, destroyCalls;
		Runnable onStateRead = () -> {};
		Fixture(boolean permit) {
			data.registerMember(camp, ACTOR, 0); data.registerMember(camp, OTHER, 0);
			data.recordResourceObservation(camp, TARGET, OAK_LOG);
			if (permit) permit();
		}
		void permit() { data.permitGatheringArea(camp, ACTOR, ORIGIN, 8, SettlementGatheringAreas.Mode.BOTH); }
		public SettlementSavedData currentData() { return current; }
		public boolean activeAdult() { return adult; }
		public boolean inBounds(BlockPos pos) { return bounds; }
		public boolean loaded(BlockPos pos) { return loaded; }
		public boolean ticking(BlockPos pos) { return ticking; }
		public double distanceSquared(BlockPos pos) { return distance; }
		public BlockState state(BlockPos pos) { reads++; onStateRead.run(); return block; }
		List<SettlementMaterialGathering.Choice> candidates() { return SettlementMaterialGathering.candidates(data, camp, ACTOR, this, 0); }
		SettlementMaterialGathering.Ticket ticket() {
			var ticket = SettlementMaterialGathering.prepare(data, camp, ACTOR, candidates().getFirst(), this);
			assertNotNull(ticket); return ticket;
		}
		SettlementSimulationState simulation() {
			var state = data.getOrCreateSimulation(camp); state.mapResident(ACTOR, false, 100);
			state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true); state.setSkillsEnabled(true); return state;
		}
		int harvest(SettlementMaterialGathering.Ticket ticket, SimpleContainer bag, List<ItemStack> drops, Runnable afterLoot, boolean destroy) {
			return SettlementMaterialGathering.harvest(ticket, this, bag,
				state -> { lootCalls++; afterLoot.run(); return drops; },
				state -> { destroyCalls++; return destroy; });
		}
	}

	@Test void unknownSourceIsEligibleOnlyWithExplicitAreaAndSelectionIsImmutableAndReadOnly() {
		Fixture f = new Fixture(false); assertTrue(f.candidates().isEmpty()); assertEquals(0, f.reads);
		f.permit(); CompoundTag before = f.data.save(new CompoundTag(), null);
		var choices = f.candidates(); assertEquals(1, choices.size());
		assertEquals(SettlementResourceObservations.Source.UNKNOWN, choices.getFirst().observation().source());
		assertThrows(UnsupportedOperationException.class, choices::clear);
		assertEquals(before, f.data.save(new CompoundTag(), null)); assertEquals(0, f.data.materialLeases().size(0));
	}

	@Test void modeProtectionMembershipAndWorldGatesAllPrecedeBlockReads() {
		List<Consumer<Fixture>> changes = List.of(
			f -> f.data.permitGatheringArea(f.camp, ACTOR, ORIGIN, 8, SettlementGatheringAreas.Mode.STONE),
			f -> f.data.addProtectedArea(ACTOR, "Keep", 2, 0, 2, 0),
			f -> f.data.removeMember(ACTOR), f -> f.adult = false,
			f -> f.bounds = false, f -> f.loaded = false, f -> f.ticking = false,
			f -> f.distance = 145, f -> f.current = new SettlementSavedData(),
			f -> f.data.materialLeases().claim(f.data, f.camp, OTHER, TARGET, 0));
		for (var change : changes) {
			Fixture f = new Fixture(true); change.accept(f);
			assertTrue(f.candidates().isEmpty()); assertEquals(0, f.reads);
		}
	}

	@Test void matchesOnlyObservedOakAndStoneKindsIncludingCobblestone() {
		Fixture f = new Fixture(true);
		for (BlockState block : List.of(Blocks.BIRCH_LOG.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
			f.block = block; assertTrue(f.candidates().isEmpty());
		}
		f.data.recordResourceObservation(f.camp, TARGET, STONE);
		for (BlockState block : List.of(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState())) {
			f.block = block; assertEquals(1, f.candidates().size());
		}
		f.data.recordResourceObservation(f.camp, TARGET, SWEET_BERRY);
		assertTrue(f.candidates().isEmpty());
	}

	@Test void suppressionAndObservationReplacementInvalidateTicketsWithoutAbaRevival() {
		for (int mutation = 0; mutation < 3; mutation++) {
			Fixture f = new Fixture(true); var ticket = f.ticket(); String id = ticket.choice.observation().id();
			if (mutation == 0) f.data.changeResourceObservation(f.camp, id, true);
			if (mutation == 1) { f.data.recordResourceObservation(f.camp, TARGET, STONE); f.data.recordResourceObservation(f.camp, TARGET, OAK_LOG); }
			if (mutation == 2) {
				f.data.changeResourceObservation(f.camp, id, true); f.data.changeResourceObservation(f.camp, id, false);
				f.data.recordResourceObservation(f.camp, TARGET, OAK_LOG);
			}
			f.reads = 0; assertFalse(SettlementMaterialGathering.valid(ticket, f)); assertEquals(0, f.reads);
		}
	}

	@Test void areaAndMemberAbaReloadAndTemporaryBlockChangePermanentlyCancelTickets() {
		for (int mutation = 0; mutation < 5; mutation++) {
			Fixture f = new Fixture(true); var ticket = f.ticket();
			switch (mutation) {
				case 0 -> { f.data.clearGatheringArea(f.camp); f.permit(); }
				case 1 -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); }
				case 2 -> f.current = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
				case 3 -> f.block = Blocks.AIR.defaultBlockState();
				case 4 -> f.data.permitGatheringArea(f.camp, ACTOR, ORIGIN, 7, SettlementGatheringAreas.Mode.BOTH);
			}
			assertFalse(SettlementMaterialGathering.valid(ticket, f));
			f.current = f.data; f.block = Blocks.OAK_LOG.defaultBlockState(); f.permit();
			assertFalse(SettlementMaterialGathering.valid(ticket, f));
		}
	}

	@Test void stateReadCallbacksCannotHideAreaOrMemberAbaAtAnyHarvestValidationBoundary() {
		for (boolean memberAba : List.of(false, true)) {
			for (int validation = 1; validation <= 3; validation++) {
				Fixture f = new Fixture(true); var ticket = f.ticket(); SimpleContainer bag = new SimpleContainer(2);
				int mutateAt = f.reads + validation;
				f.onStateRead = () -> {
					if (f.reads != mutateAt) return;
					if (memberAba) { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); }
					else { f.data.clearGatheringArea(f.camp); f.permit(); }
				};
				assertEquals(0, f.harvest(ticket, bag, List.of(new ItemStack(Items.OAK_LOG)), () -> {}, true));
				assertEquals(mutateAt, f.reads); assertEquals(0, f.destroyCalls); assertTrue(bag.isEmpty());
				assertEquals(validation == 1 ? 0 : 1, f.lootCalls);
				assertFalse(SettlementMaterialGathering.valid(ticket, f), "Restored authority cannot revive the captured identity");
			}
		}
	}

	@Test void revocationBeforeLootOrDuringLootNeverDestroysOrInserts() {
		for (boolean beforeLoot : List.of(false, true)) {
			Fixture f = new Fixture(true); var ticket = f.ticket(); SimpleContainer bag = new SimpleContainer(2);
			if (beforeLoot) f.data.clearGatheringArea(f.camp);
			assertEquals(0, f.harvest(ticket, bag, List.of(new ItemStack(Items.OAK_LOG)), () -> f.data.clearGatheringArea(f.camp), true));
			assertEquals(beforeLoot ? 0 : 1, f.lootCalls); assertEquals(0, f.destroyCalls); assertTrue(bag.isEmpty());
		}
	}

	@Test void blockOrObservationReplacementDuringLootCannotDestroyTheReplacement() {
		for (boolean replaceObservation : List.of(false, true)) {
			Fixture f = new Fixture(true); var ticket = f.ticket(); SimpleContainer bag = new SimpleContainer(2);
			assertEquals(0, f.harvest(ticket, bag, List.of(new ItemStack(Items.OAK_LOG)), () -> {
				if (replaceObservation) {
					f.data.recordResourceObservation(f.camp, TARGET, STONE);
					f.data.recordResourceObservation(f.camp, TARGET, OAK_LOG);
				} else f.block = Blocks.BIRCH_LOG.defaultBlockState();
			}, true));
			assertEquals(1, f.lootCalls); assertEquals(0, f.destroyCalls); assertTrue(bag.isEmpty());
		}
	}

	@Test void inventoryPlanningRechecksAuthorityImmediatelyBeforeDestroy() {
		Fixture f = new Fixture(true); var ticket = f.ticket(); boolean[] revoke = {false};
		SimpleContainer bag = new SimpleContainer(1) {
			@Override public ItemStack getItem(int slot) {
				if (revoke[0]) { revoke[0] = false; f.data.clearGatheringArea(f.camp); }
				return super.getItem(slot);
			}
		};
		bag.setItem(0, new ItemStack(Items.OAK_LOG, 3));
		assertEquals(0, f.harvest(ticket, bag, List.of(new ItemStack(Items.OAK_LOG)), () -> revoke[0] = true, true));
		assertEquals(0, f.destroyCalls); assertEquals(3, bag.countItem(Items.OAK_LOG));
	}

	@Test void aggregateCapacityAndFailedDestroyPreserveInventoryAndPractice() {
		for (boolean insufficient : List.of(false, true)) {
			Fixture f = new Fixture(true); f.simulation(); var ticket = f.ticket();
			SimpleContainer bag = new SimpleContainer(1); bag.setItem(0, new ItemStack(Items.OAK_LOG, 63));
			CompoundTag before = f.data.save(new CompoundTag(), null);
			List<ItemStack> drops = insufficient ? List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.OAK_LOG)) : List.of(new ItemStack(Items.OAK_LOG));
			assertEquals(0, f.harvest(ticket, bag, drops, () -> {}, false));
			assertEquals(insufficient ? 0 : 1, f.destroyCalls); assertEquals(63, bag.countItem(Items.OAK_LOG));
			assertEquals(before, f.data.save(new CompoundTag(), null));
		}
	}

	@Test void actualCustomFoodDropsKeepComponentsWithoutAbstractDuplicatesAndTicketCannotReplay() {
		Fixture f = new Fixture(true); var simulation = f.simulation(); var ticket = f.ticket();
		SimpleContainer bag = new SimpleContainer(4); bag.setItem(0, new ItemStack(Items.BREAD, 5));
		ItemStack named = new ItemStack(Items.OAK_LOG, 2); named.set(DataComponents.CUSTOM_NAME, Component.literal("Actual loot"));
		ItemStack food = new ItemStack(Items.APPLE, 3); food.set(DataComponents.CUSTOM_NAME, Component.literal("Custom food loot"));
		var batches = List.copyOf(simulation.world().batchesAt(simulation.campOwner()));
		assertEquals(5, f.harvest(ticket, bag, List.of(named, food), () -> {}, true));
		assertEquals(1, f.destroyCalls); assertEquals(2, bag.countItem(Items.OAK_LOG)); assertEquals(3, bag.countItem(Items.APPLE));
		assertEquals(5, bag.countItem(Items.BREAD)); assertEquals(named.getComponents(), bag.getItem(1).getComponents());
		assertEquals(food.getComponents(), bag.getItem(2).getComponents());
		assertEquals(2, named.getCount()); assertEquals(3, food.getCount());
		assertEquals(batches, simulation.world().batchesAt(simulation.campOwner())); simulation.world().assertConservation();
		assertEquals(0, f.harvest(ticket, bag, List.of(named, food), () -> fail("Replayed loot"), true));
		assertEquals(1, f.destroyCalls);
	}

	@Test void discoveryReadsAtMostSixtyFourExistingRecordsWithoutScanningNewCoordinates() {
		Fixture f = new Fixture(true);
		f.data.recordResourceObservation(f.camp, TARGET, (SettlementResourceObservations.Kind) null);
		for (int i = 0; i < 64; i++) assertTrue(f.data.recordResourceObservation(f.camp, new BlockPos(i % 8, 64, i / 8), OAK_LOG));
		assertFalse(f.data.recordResourceObservation(f.camp, new BlockPos(-1, 64, 0), OAK_LOG));
		assertEquals(64, f.candidates().size()); assertEquals(64, f.reads);
	}
}
