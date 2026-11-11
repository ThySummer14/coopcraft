package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillagerGiftServiceTest {
	private static final String ACTOR = "minecraft-player:test";
	private static final UUID RESIDENT = new UUID(10, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void threeResidentsUseSelectedTargetAndActiveUnits() {
		for (boolean survival : List.of(false, true)) {
			var f = new Fixture(survival);
			f.stock.setItem(0, new ItemStack(Items.BREAD, 10)); // 40 physical points
			f.state().world().importExternalBatch(ResourceType.WILD_FRUIT, 10, f.state().campOwner()); // 180 nutrition
			assertEquals(survival ? 180 : 40, f.snapshot().current().orElseThrow());
			assertEquals(survival ? 120 : 24, f.snapshot().goal());
			var refused = f.offer();
			assertFalse(refused.accepted()); assertEquals("reserve_target_met", refused.reason());
			assertEquals(5, f.hand.getItem(2).getCount()); assertEquals(0, f.person().trustIn(ACTOR));
			SimulationEvent old = f.last();
			assertEvidence(old, "normal", survival ? "nutrition" : "points", survival ? 180 : 40, survival ? 120 : 24);
			SettlementReserves.setTarget(f.data, f.settlement, FoodReserveTarget.HIGH);
			assertEquals(survival ? 240 : 48, f.snapshot().goal());
			assertAcceptedOnce(f);
			assertEvidence(f.last(), "high", survival ? "nutrition" : "points", survival ? 180 : 40, survival ? 240 : 48);
			assertEquals("below_reserve_target", f.last().context().get("reason"));
			assertEvidence(old, "normal", survival ? "nutrition" : "points", survival ? 180 : 40, survival ? 120 : 24);
			assertThrows(UnsupportedOperationException.class, () -> old.context().put("targetId", "high"));
		}
	}

	@Test void acceptedGiftDebitsOnlyCapturedSlotAndNeverFeedsResident() {
		for (boolean survival : List.of(false, true)) {
			var f = new Fixture(survival);
			f.hand.setItem(0, new ItemStack(Items.SWEET_BERRIES, 5));
			var decorated = new ItemStack(Items.SWEET_BERRIES, 5);
			decorated.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
			f.hand.setItem(1, decorated);
			assertAcceptedOnce(f);
			assertEquals(5, f.hand.getItem(0).getCount());
			assertTrue(ItemStack.matches(decorated, f.hand.getItem(1)));
			assertEquals(survival ? 18 : 1, f.snapshot().current().orElseThrow());
			assertEquals(80, f.data.needsOf(f.settlement, RESIDENT).satiety());
			assertEquals(20, f.person().hunger());
			if (survival) assertTrue(f.stock.isEmpty());
			else assertEquals(1, f.stock.getItem(0).getCount());
			f.state().world().assertConservation();
		}
	}

	@Test void changedSlotTypeCountOrComponentsCannotTransferOrCreateAid() {
		for (boolean survival : List.of(false, true)) {
			for (String mutation : List.of("slot", "type", "count", "component")) {
				var f = new Fixture(survival);
				ItemStack captured = f.hand.getItem(2).copy();
				IntSupplier selected = () -> 2;
				switch (mutation) {
					case "slot" -> selected = () -> 1;
					case "type" -> f.hand.setItem(2, new ItemStack(Items.POTATO, 5));
					case "count" -> f.hand.getItem(2).shrink(1);
					case "component" -> f.hand.getItem(2).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
				}
				ItemStack before = f.hand.getItem(2).copy();
				CompoundTag saved = f.data.save(new CompoundTag(), null);
				f.data.setDirty(false);
				var result = f.offer(selected, captured, f.stock);
				assertEquals("transfer_failed", result.reason()); assertFalse(result.accepted());
				assertTrue(ItemStack.matches(before, f.hand.getItem(2)));
				assertEquals(saved, f.data.save(new CompoundTag(), null)); assertFalse(f.data.isDirty());
				assertNoAid(f);
			}
		}
	}

	@Test void slotChangingImmediatelyBeforeDebitRecordsRefusalWithoutAid() {
		var f = new Fixture(true);
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var result = f.offer(() -> calls.getAndIncrement() == 0 ? 2 : 0, f.hand.getItem(2).copy(), null);
		assertFalse(result.accepted()); assertEquals("transfer_failed", result.reason());
		assertEquals(5, f.hand.getItem(2).getCount()); assertEquals(0, f.snapshot().current().orElseThrow());
		assertEquals(EventType.GIFT_REFUSED, f.last().type()); assertNoAid(f);
	}

	@Test void stackMutationBetweenValidationAndDebitFailsWithoutImport() {
		for (String mutation : List.of("type", "count", "component")) {
			var f = new Fixture(true);
			var calls = new java.util.concurrent.atomic.AtomicInteger();
			var result = f.offer(() -> {
				if (calls.getAndIncrement() == 1) {
					switch (mutation) {
						case "type" -> f.hand.setItem(2, new ItemStack(Items.POTATO, 5));
						case "count" -> f.hand.getItem(2).shrink(1);
						case "component" -> f.hand.getItem(2).set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
					}
				}
				return 2;
			}, f.hand.getItem(2).copy(), null);
			assertFalse(result.accepted()); assertEquals("transfer_failed", result.reason());
			assertEquals(mutation.equals("count") ? 4 : 5, f.hand.getItem(2).getCount());
			assertEquals(0, f.snapshot().current().orElseThrow());
			assertEquals(EventType.GIFT_REFUSED, f.last().type()); assertNoAid(f);
		}
	}

	@Test void samePhysicalSourceAndDestinationIsNotAGift() {
		var f = new Fixture(false);
		CompoundTag before = f.data.save(new CompoundTag(), null); f.data.setDirty(false);
		var result = f.offer(() -> 2, f.hand.getItem(2).copy(), f.hand);
		assertEquals("transfer_failed", result.reason()); assertFalse(result.accepted());
		assertEquals(5, f.hand.getItem(2).getCount()); assertFalse(f.data.isDirty());
		assertEquals(before, f.data.save(new CompoundTag(), null)); assertNoAid(f);
	}

	@Test void abstractFoodConversionsUseActualResourceNutrition() {
		for (var item : List.of(Items.SWEET_BERRIES, Items.POTATO, Items.COD)) {
			var f = new Fixture(true);
			f.hand.setItem(2, new ItemStack(item, 5));
			assertAcceptedOnce(f);
			assertEquals(item == Items.SWEET_BERRIES ? 18 : item == Items.POTATO ? 24 : 20,
				f.snapshot().current().orElseThrow());
			assertEquals(20, f.person().hunger()); f.state().world().assertConservation();
		}
	}

	@Test void fullAndRejectingPhysicalContainersRestoreExactSource() {
		for (boolean rejecting : List.of(false, true)) {
			var f = new Fixture(false);
			Container stock = rejecting ? new SimpleContainer(1) {
				@Override public boolean canPlaceItem(int slot, ItemStack item) { return false; }
			} : new SimpleContainer(new ItemStack(Items.COBBLESTONE, 64));
			ItemStack before = f.hand.getItem(2).copy();
			var result = f.offer(() -> 2, before.copy(), stock);
			assertFalse(result.accepted()); assertEquals("stockpile_full", result.reason());
			assertTrue(ItemStack.matches(before, f.hand.getItem(2)));
			assertEquals(rejecting ? 0 : 64, stock.getItem(0).getCount());
			assertEquals(EventType.GIFT_REFUSED, f.last().type()); assertNoAid(f);
		}
	}

	@Test void unknownPhysicalStockRemainsUnknownAndReadOnly() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Unknown", 0);
		data.registerMember(settlement, RESIDENT, 0); data.setSatiety(settlement, RESIDENT, 80);
		var hand = new SimpleContainer(new ItemStack(Items.SWEET_BERRIES, 3));
		CompoundTag before = data.save(new CompoundTag(), null); data.setDirty(false);
		for (int i = 0; i < 3; i++) {
			var snapshot = SettlementReserves.inspect(data, settlement, null);
			assertTrue(snapshot.current().isEmpty()); assertTrue(snapshot.shortfall().isEmpty());
			var result = VillagerGiftService.offer(data, settlement, RESIDENT, false, ACTOR, "Player", hand, 0,
				() -> 0, hand.getItem(0).copy(), null);
			assertEquals("no_stockpile", result.reason()); assertFalse(result.accepted());
			assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
			assertTrue(data.simulationOf(settlement).isEmpty()); assertEquals(3, hand.getItem(0).getCount());
		}
	}

	@Test void disabledSimulationCannotSupplyPhysicalAuthority() {
		var f = new Fixture(true);
		f.state().world().importExternalBatch(ResourceType.FISH, 100, f.state().campOwner());
		f.state().setSurvivalEnabled(false, 0);
		assertAcceptedOnce(f);
		assertEquals(1, f.snapshot().current().orElseThrow());
		assertEquals(2000, f.state().world().availableNutrition(f.state().campOwner()));
		assertEquals("points", f.last().context().get("unit"));
	}

	@Test void customAndUnsupportedFoodAndNonmembersNeverGainAid() {
		for (boolean survival : List.of(false, true)) {
			var f = new Fixture(survival);
			f.hand.getItem(2).set(DataComponents.CUSTOM_NAME, Component.literal("Custom food"));
			assertEquals("unsupported_food", f.offer().reason()); assertNoAid(f);
			assertEquals(5, f.hand.getItem(2).getCount());
			f.hand.setItem(2, new ItemStack(Items.STICK, 5));
			assertEquals("not_food", f.offer().reason()); assertNoAid(f);
			var outsider = VillagerGiftService.offer(f.data, f.settlement, new UUID(99, 99), false, ACTOR, "Player",
				f.hand, 2, () -> 2, f.hand.getItem(2).copy(), f.stock);
			assertEquals("not_member", outsider.reason()); assertNoAid(f);
		}
		var f = new Fixture(true);
		f.hand.setItem(2, new ItemStack(Items.BEETROOT, 5));
		assertEquals("unsupported_food", f.offer().reason()); assertEquals(5, f.hand.getItem(2).getCount()); assertNoAid(f);
	}

	@Test void twoReloadsKeepTargetTrustLedgerAndOriginalDecisionEvidence() {
		var f = new Fixture(true);
		SettlementReserves.setTarget(f.data, f.settlement, FoodReserveTarget.HIGH);
		assertAcceptedOnce(f);
		var event = f.last();
		var originalDetails = GiftReserveDetails.project(event);
		f.state().world().importExternalBatch(ResourceType.FISH, 20, f.state().campOwner());
		var events = List.copyOf(f.state().world().ledger().events());
		for (int round = 0; round < 2; round++) {
			f.data = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
			assertEquals(FoodReserveTarget.HIGH, f.data.foodReserveTargetOf(f.settlement));
			assertEquals(2, f.person().trustIn(ACTOR)); assertEquals(1, f.person().reciprocityWith(ACTOR));
			assertEquals(events, f.state().world().ledger().events());
			assertEquals(event, f.state().world().ledger().events().stream().filter(e -> e.eventId() == event.eventId()).findFirst().orElseThrow());
			f.data.setDirty(false); CompoundTag before = f.data.save(new CompoundTag(), null);
			for (int i = 0; i < 3; i++) { var snapshot = f.snapshot(); VillagerInteractionDecision.decide(80, snapshot, 2, 50, true); }
			assertFalse(f.data.isDirty()); assertEquals(before, f.data.save(new CompoundTag(), null));
			f.state().world().assertConservation();
		}
		assertEvidence(event, "high", "nutrition", 0, 240);
		SettlementReserves.setTarget(f.data, f.settlement, FoodReserveTarget.NORMAL);
		assertEquals(originalDetails, GiftReserveDetails.project(event));
	}

	private static void assertAcceptedOnce(Fixture f) {
		long previous = f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.GIFT_RECEIVED).count();
		var result = f.offer(); assertTrue(result.accepted()); assertEquals(1, result.moved());
		assertEquals(4, f.hand.getItem(2).getCount()); assertEquals(2, f.person().trustIn(ACTOR));
		assertEquals(1, f.person().reciprocityWith(ACTOR));
		assertEquals(previous + 1, f.state().world().ledger().events().stream().filter(e -> e.type() == EventType.GIFT_RECEIVED).count());
	}
	private static void assertNoAid(Fixture f) {
		assertEquals(0, f.person().trustIn(ACTOR)); assertEquals(0, f.person().reciprocityWith(ACTOR));
		assertTrue(f.state().world().ledger().events().stream().noneMatch(e -> e.type() == EventType.GIFT_RECEIVED));
	}
	private static void assertEvidence(SimulationEvent event, String target, String unit, long current, long goal) {
		assertEquals(target, event.context().get("targetId")); assertEquals(unit, event.context().get("unit"));
		assertEquals(Long.toString(current), event.context().get("currentBefore"));
		assertEquals(Long.toString(goal), event.context().get("goalBefore"));
	}
	private static final class Fixture {
		SettlementSavedData data = new SettlementSavedData();
		final Settlement settlement = data.getOrCreate(BlockPos.ZERO, "Gifts", 0);
		final SimpleContainer hand = new SimpleContainer(4);
		final SimpleContainer stock = new SimpleContainer(3);
		Fixture(boolean survival) {
			for (int i = 1; i <= 3; i++) data.registerMember(settlement, new UUID(10, i), 0);
			data.setSatiety(settlement, RESIDENT, 80);
			state().mapResident(RESIDENT, false, 80); state().setSurvivalEnabled(survival, 0);
			hand.setItem(2, new ItemStack(Items.SWEET_BERRIES, 5));
		}
		SettlementSimulationState state() { return data.getOrCreateSimulation(settlement); }
		Person person() { return state().world().person(state().residentId(RESIDENT).orElseThrow()); }
		SettlementReserves.Snapshot snapshot() { return SettlementReserves.inspect(data, settlement, stock); }
		SimulationEvent last() { return state().world().ledger().lastEvent().orElseThrow(); }
		VillagerInteraction.OfferResult offer() { return offer(() -> 2, hand.getItem(2).copy(), stock); }
		VillagerInteraction.OfferResult offer(IntSupplier selected, ItemStack captured, Container target) {
			return VillagerGiftService.offer(data, settlement, RESIDENT, false, ACTOR, "Player", hand, 2, selected, captured, target);
		}
	}
}
