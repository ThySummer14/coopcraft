package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodSharingMemoriesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static RegistryAccess registries() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), registries()); }
	private static SettlementSavedData reload(SettlementSavedData data) { return SettlementSavedData.load(save(data), registries()); }
	private static SettlementFoodSharing.Completion complete(SettlementFoodSharingTest.Fixture f, long tick) {
		return SettlementFoodSharing.finish(f.prepare(), () -> true, tick, "Giver", "Recipient");
	}
	private static List<SettlementFoodSharingMemories.Delivery> records(SettlementFoodSharingTest.Fixture f) {
		return f.data.foodSharingMemoriesOf(f.camp, f.giver);
	}
	private static CompoundTag history(SettlementSavedData data) { return save(data).getCompound("FoodSharingMemories"); }
	private static CompoundTag oneEntry() {
		var f = new SettlementFoodSharingTest.Fixture(); assertTrue(f.data.rememberFoodSharing(f.camp, complete(f, 42)));
		return history(f.data).getList("Deliveries", Tag.TAG_COMPOUND).getCompound(0).copy();
	}
	private static CompoundTag root(ListTag entries) {
		CompoundTag root = new CompoundTag(); root.putInt("Version", 1); root.put("Deliveries", entries); return root;
	}

	@Test void rememberedDonorsAreRecentReceivedOnlyDistinctAndReadOnly() {
		var f = new SettlementFoodSharingTest.Fixture();
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(f.data, f.camp, f.recipient).isEmpty());
		assertTrue(f.data.rememberFoodSharing(f.camp, complete(f, 10)));
		f.other.clearContent(); assertTrue(f.data.rememberFoodSharing(f.camp, complete(f, 11)));
		f.data.setDirty(false);
		assertEquals(List.of(f.giver), SettlementFoodSharingMemories.rememberedGivers(f.data, f.camp, f.recipient));
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(f.data, f.camp, f.giver).isEmpty());
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(f.data, f.camp, UUID.randomUUID()).isEmpty());
		assertFalse(f.data.isDirty()); assertTrue(f.data.simulationOf(f.camp).isEmpty());
		assertEquals("Giver", f.data.foodSharingMemoriesOf(f.camp, f.recipient).getFirst().giverName());
	}
	@Test void rememberedDonorsNeverLookPastThreePersonalFactsAndUnknownHistoryIsEmpty() {
		var f = new SettlementFoodSharingTest.Fixture();
		ListTag entries = new ListTag(); UUID older = UUID.randomUUID();
		for (int i = 0; i < 6; i++) {
			CompoundTag entry = oneEntry(); entry.putUUID("Id", UUID.randomUUID()); entry.putUUID("Camp", f.camp.id());
			entry.putUUID("Giver", i == 0 ? older : UUID.randomUUID()); entry.putUUID("Recipient", f.recipient);
			entries.add(entry);
		}
		CompoundTag saved = save(f.data); saved.put("FoodSharingMemories", root(entries));
		var data = SettlementSavedData.load(saved, registries()); var camp = data.findSettlementForVillager(f.recipient).orElseThrow();
		var donors = SettlementFoodSharingMemories.rememberedGivers(data, camp, f.recipient);
		assertEquals(3, donors.size()); assertFalse(donors.contains(older));
		assertEquals(entries.getCompound(5).getUUID("Giver"), donors.getFirst());
		CompoundTag unknown = root(entries); unknown.putInt("Version", 999); saved.put("FoodSharingMemories", unknown);
		data = SettlementSavedData.load(saved, registries()); camp = data.findSettlementForVillager(f.recipient).orElseThrow();
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(data, camp, f.recipient).isEmpty());
	}

	@Test void recentOutgoingFactsConsumeTheWindowAndNewCampCannotBorrowOldDonors() {
		var f = new SettlementFoodSharingTest.Fixture(); ListTag entries = new ListTag();
		for (int i = 0; i < 4; i++) {
			CompoundTag entry = oneEntry(); entry.putUUID("Id", UUID.randomUUID()); entry.putUUID("Camp", f.camp.id());
			entry.putUUID("Giver", i == 0 ? f.giver : f.recipient);
			entry.putUUID("Recipient", i == 0 ? f.recipient : UUID.randomUUID()); entries.add(entry);
		}
		CompoundTag saved = save(f.data); saved.put("FoodSharingMemories", root(entries));
		var data = SettlementSavedData.load(saved, registries()); var camp = data.findSettlementForVillager(f.recipient).orElseThrow();
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(data, camp, f.recipient).isEmpty());
		var next = data.getOrCreate(new BlockPos(1000, 64, 0), "Next", 0);
		data.removeMember(f.recipient); data.registerMember(next, f.recipient, 0); data.setDirty(false);
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(data, camp, f.recipient).isEmpty());
		assertTrue(SettlementFoodSharingMemories.rememberedGivers(data, next, f.recipient).isEmpty());
		assertFalse(data.isDirty());
	}

	@Test void successRecordsOnePhysicalRationWithoutPersonNeedsOrAbstractInventoryChanges() {
		var f = new SettlementFoodSharingTest.Fixture(); f.data.setDirty(false);
		var completion = complete(f, 123456); assertNotNull(completion);
		assertTrue(records(f).isEmpty()); assertFalse(f.data.isDirty());
		assertTrue(f.data.rememberFoodSharing(f.camp, completion)); assertTrue(f.data.isDirty());
		var delivery = records(f).getFirst(); assertEquals(123456L, delivery.gameTime());
		assertEquals(f.giver, delivery.giver()); assertEquals(f.recipient, delivery.recipient());
		assertEquals(f.camp.id(), delivery.camp()); assertEquals("minecraft:bread", delivery.item()); assertEquals(1, delivery.count());
		assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, f.other.countItem(Items.BREAD));
		assertEquals(80, f.data.needsOf(f.camp, f.giver).satiety()); assertEquals(35, f.data.needsOf(f.camp, f.recipient).satiety());
		assertTrue(f.data.simulationOf(f.camp).isEmpty()); assertTrue(f.data.resourcesOf(f.camp).isEmpty());
		assertTrue(f.data.getStockpile(f.camp).isEmpty());
	}
	@Test void existingDisabledSimulationIsBitForBitUntouched() {
		var f = new SettlementFoodSharingTest.Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
		state.setSurvivalEnabled(false, 0); CompoundTag before = state.save();
		assertTrue(f.data.rememberFoodSharing(f.camp, complete(f, 52)));
		assertEquals(before, state.save());
	}
	@Test void credentialIsSingleUseButAnotherRealDeliveryInSameTickIsDistinct() {
		var f = new SettlementFoodSharingTest.Fixture(); var first = complete(f, 10);
		assertTrue(f.data.rememberFoodSharing(f.camp, first)); f.data.setDirty(false);
		assertFalse(f.data.rememberFoodSharing(f.camp, first)); assertFalse(f.data.isDirty());
		f.other.clearContent(); var second = complete(f, 10); assertNotNull(second);
		assertTrue(f.data.rememberFoodSharing(f.camp, second)); assertEquals(2, records(f).size());
		assertNotEquals(records(f).get(0).id(), records(f).get(1).id()); assertEquals(14, f.bag.countItem(Items.BREAD));
	}
	@Test void failedCancelledAbsentAndThrowingTransactionsCannotProduceRecords() {
		for (String failure : List.of("arrival", "cancel", "presence", "exception", "time")) {
			var f = new SettlementFoodSharingTest.Fixture(); var ticket = f.prepare(); f.data.setDirty(false);
			if (failure.equals("cancel")) SettlementFoodSharing.cancel(ticket);
			if (failure.equals("presence")) f.present = false;
			if (failure.equals("exception")) assertThrows(IllegalStateException.class, () -> SettlementFoodSharing.finish(ticket,
				() -> { throw new IllegalStateException(); }, 42, "A", "B"));
			else assertNull(SettlementFoodSharing.finish(ticket, () -> !failure.equals("arrival"), failure.equals("time") ? -1 : 42, "A", "B"));
			assertNull(SettlementFoodSharing.finish(ticket, () -> true, 42, "A", "B"));
			assertFalse(f.data.rememberFoodSharing(f.camp, null)); assertTrue(records(f).isEmpty()); assertFalse(f.data.isDirty());
			assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
		}
	}
	@Test void reentryAndCompetingTicketMintOnlyTheActuallySuccessfulCompletion() {
		var f = new SettlementFoodSharingTest.Fixture(); var outer = f.prepare(); var inner = f.prepare();
		var winner = new SettlementFoodSharing.Completion[1];
		assertNull(SettlementFoodSharing.finish(outer, () -> {
			assertNull(SettlementFoodSharing.finish(outer, () -> true, 42, "A", "B"));
			winner[0] = SettlementFoodSharing.finish(inner, () -> true, 42, "A", "B"); return true;
		}, 42, "A", "B"));
		assertNotNull(winner[0]); assertTrue(f.data.rememberFoodSharing(f.camp, winner[0]));
		assertNull(SettlementFoodSharing.finish(inner, () -> true, 42, "A", "B"));
		assertEquals(1, records(f).size()); assertEquals(15, f.bag.countItem(Items.BREAD));
	}
	@Test void throwingContainerNotificationDoesNotMintProofOrCompensateWithCopies() {
		var f = new SettlementFoodSharingTest.Fixture();
		SimpleContainer other = new SimpleContainer(8) {
			@Override public void setItem(int slot, ItemStack stack) { super.setItem(slot, stack); throw new IllegalStateException(); }
		};
		var ticket = SettlementFoodSharing.prepare(f.data, f.camp, f.giver, f.bag, f.recipient, other, () -> true);
		assertThrows(IllegalStateException.class, () -> SettlementFoodSharing.finish(ticket, () -> true, 42, "A", "B"));
		assertTrue(records(f).isEmpty()); assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, other.countItem(Items.BREAD));
		assertNull(SettlementFoodSharing.finish(ticket, () -> true, 42, "A", "B"));
	}
	@Test void completionCannotCrossDataReloadOrCampIdentity() {
		var f = new SettlementFoodSharingTest.Fixture(); var completion = complete(f, 42);
		var loaded = reload(f.data); var camp = loaded.settlementById(f.camp.id()); loaded.setDirty(false);
		assertFalse(loaded.rememberFoodSharing(camp, completion)); assertFalse(loaded.isDirty());
		assertFalse(f.data.rememberFoodSharing(new Settlement(f.camp.id(), f.camp.center(), f.camp.name(), 0), completion));
		assertTrue(f.data.rememberFoodSharing(f.camp, completion));
	}
	@Test void preReloadTicketCannotRecordIntoTheReloadedData() {
		var f = new SettlementFoodSharingTest.Fixture(); var oldTicket = f.prepare();
		var loaded = reload(f.data); var camp = loaded.settlementById(f.camp.id()); loaded.setDirty(false);
		// A stale standalone transaction still owns only its old bags/data, never the loaded world's history.
		var oldCompletion = SettlementFoodSharing.finish(oldTicket, () -> true, 100, "Old A", "Old B");
		assertNotNull(oldCompletion); assertFalse(loaded.rememberFoodSharing(camp, oldCompletion));
		assertTrue(loaded.foodSharingMemoriesOf(camp, f.giver).isEmpty()); assertFalse(loaded.isDirty());
	}

	@Test void twoNbtRoundTripsRestoreOnlyFactsWithoutReplayOrSimulation() {
		var f = new SettlementFoodSharingTest.Fixture(); assertTrue(f.data.rememberFoodSharing(f.camp, complete(f, 42)));
		var expected = records(f); var loaded = reload(reload(f.data)); var camp = loaded.settlementById(f.camp.id());
		loaded.setDirty(false); assertEquals(expected, loaded.foodSharingMemoriesOf(camp, f.recipient));
		assertTrue(loaded.simulationOf(camp).isEmpty()); assertFalse(loaded.isDirty());
		assertEquals(80, loaded.needsOf(camp, f.giver).satiety()); assertEquals(35, loaded.needsOf(camp, f.recipient).satiety());
		assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, f.other.countItem(Items.BREAD));
	}
	@Test void absentFieldRemainsAbsentWithoutDirtyOrStateCreation() {
		var f = new SettlementFoodSharingTest.Fixture(); var loaded = reload(f.data); var camp = loaded.settlementById(f.camp.id());
		loaded.setDirty(false); assertTrue(SettlementFoodSharingMemories.inspect(loaded, camp, f.giver).isEmpty());
		assertFalse(save(loaded).contains("FoodSharingMemories")); assertFalse(loaded.isDirty()); assertTrue(loaded.simulationOf(camp).isEmpty());
	}
	@Test void unknownMalformedAndOversizedSubtreesArePreservedAndFreezeNewWrites() {
		List<Tag> bad = new ArrayList<>(); bad.add(StringTag.valueOf("future"));
		var entry = oneEntry(); ListTag entries = new ListTag(); entries.add(entry);
		CompoundTag future = root(entries.copy()); future.putInt("Version", 2); bad.add(future);
		CompoundTag extra = root(entries.copy()); extra.putString("Future", "keep"); bad.add(extra);
		for (String field : List.of("GameTime", "Count", "Giver", "Item")) {
			var malformed = entry.copy(); malformed.putString(field, "malformed"); ListTag list = new ListTag(); list.add(malformed); bad.add(root(list));
		}
		var longName = entry.copy(); longName.putString("GiverName", "x".repeat(65)); ListTag names = new ListTag(); names.add(longName); bad.add(root(names));
		ListTag duplicate = new ListTag(); duplicate.add(entry.copy()); duplicate.add(entry.copy()); bad.add(root(duplicate));
		for (int size : List.of(33, 257)) {
			ListTag list = new ListTag(); for (int i = 0; i < size; i++) { var copy = entry.copy(); copy.putUUID("Id", new UUID(5, i)); list.add(copy); }
			bad.add(root(list));
		}
		for (Tag raw : bad) {
			var f = new SettlementFoodSharingTest.Fixture(); CompoundTag saved = save(f.data); saved.put("FoodSharingMemories", raw.copy());
			var loaded = SettlementSavedData.load(saved, registries()); var camp = loaded.settlementById(f.camp.id()); loaded.setDirty(false);
			assertTrue(loaded.foodSharingMemoriesBlocked()); assertTrue(loaded.foodSharingMemoriesOf(camp, f.giver).isEmpty());
			var ticket = SettlementFoodSharing.prepare(loaded, camp, f.giver, f.bag, f.recipient, f.other, () -> true);
			var completion = SettlementFoodSharing.finish(ticket, () -> true, 90, "A", "B"); assertNotNull(completion);
			assertFalse(loaded.rememberFoodSharing(camp, completion)); assertFalse(loaded.isDirty());
			assertEquals(raw, save(reload(loaded)).get("FoodSharingMemories"));
		}
	}
	@Test void perCampAndDimensionCapsEvictOldestCompletedFactsWithoutDailyDeduplication() {
		var data = new SettlementSavedData(); Settlement first = null, last = null; UUID giver = new UUID(5, 1), recipient = new UUID(5, 2);
		for (int campIndex = 0; campIndex < 9; campIndex++) {
			Settlement camp = data.getOrCreate(new BlockPos(campIndex * 1000, 64, 0), "Camp" + campIndex, 0);
			if (first == null) first = camp; last = camp;
			data.removeMember(giver); data.removeMember(recipient);
			data.registerMember(camp, giver, 0); data.registerMember(camp, recipient, 0);
			data.setSatiety(camp, giver, 80); data.setSatiety(camp, recipient, 35);
			for (int i = 0; i < 34; i++) {
				var bag = new SimpleContainer(new ItemStack(Items.BREAD, 16)); var other = new SimpleContainer(1);
				var ticket = SettlementFoodSharing.prepare(data, camp, giver, bag, recipient, other, () -> true);
				assertTrue(data.rememberFoodSharing(camp, SettlementFoodSharing.finish(ticket, () -> true, i, "A", "B")));
			}
			ListTag records = history(data).getList("Deliveries", Tag.TAG_COMPOUND);
			UUID id = camp.id(); int count = 0;
			for (int i = 0; i < records.size(); i++) if (records.getCompound(i).getUUID("Camp").equals(id)) count++;
			assertEquals(32, count);
		}
		ListTag records = history(data).getList("Deliveries", Tag.TAG_COMPOUND); assertEquals(256, records.size());
		for (int i = 0; i < records.size(); i++) assertNotEquals(first.id(), records.getCompound(i).getUUID("Camp"));
		assertEquals(List.of(33L, 32L, 31L), data.foodSharingMemoriesOf(last, giver).stream().map(SettlementFoodSharingMemories.Delivery::gameTime).toList());
		assertFalse(reload(data).foodSharingMemoriesBlocked());
	}
	@Test void plainBoundedSnapshotsAndCampScopedQueriesAreReadOnly() {
		var f = new SettlementFoodSharingTest.Fixture(); String longName = "A\n§\u202e" + "😀".repeat(80);
		var completion = SettlementFoodSharing.finish(f.prepare(), () -> true, 99, longName, "Original");
		assertTrue(f.data.rememberFoodSharing(f.camp, completion)); f.bag.setItem(0, new ItemStack(Items.CARROT));
		UUID stranger = new UUID(1, 9); f.data.registerMember(f.camp, stranger, 0); f.data.setDirty(false);
		var record = records(f).getFirst(); assertEquals(64, record.giverName().codePointCount(0, record.giverName().length()));
		assertEquals("Original", record.recipientName()); assertEquals("minecraft:bread", record.item());
		var lines = SettlementFoodSharingMemories.inspect(f.data, f.camp, f.recipient); assertEquals(1, lines.size());
		Object[] args = ((TranslatableContents) lines.getFirst().getContents()).getArgs();
		assertEquals(record.giverName(), ((Component) args[1]).getString()); assertEquals(SettlementFoodSharingMemories.foodName(record), args[4]);
		assertEquals("item.minecraft.bread", ((TranslatableContents) ((Component) args[4]).getContents()).getKey());
		assertTrue(((Component) args[4]).getString().contains("[minecraft:bread]"));
		assertEquals(Component.literal(record.giverName()), args[1]); assertTrue(records(f).getFirst().itemName().length() <= 128);
		assertTrue(f.data.foodSharingMemoriesOf(f.camp, stranger).isEmpty()); assertFalse(f.data.isDirty()); assertTrue(f.data.simulationOf(f.camp).isEmpty());
		f.data.removeMember(f.giver); assertTrue(f.data.foodSharingMemoriesOf(f.camp, f.giver).isEmpty());
		assertEquals(1, f.data.foodSharingMemoriesOf(f.camp, f.recipient).size());
		var elsewhere = f.data.getOrCreate(new BlockPos(1000, 64, 0), "Elsewhere", 0); f.data.registerMember(elsewhere, f.giver, 0);
		assertTrue(f.data.foodSharingMemoriesOf(elsewhere, f.giver).isEmpty());
	}
	@Test void optionalMemoryLinesNeverDisplaceExistingMeetingOrRationDetails() {
		List<Component> memories = List.of(Component.literal("M1"), Component.literal("M2"), Component.literal("M3"));
		for (int existing : List.of(0, 29, 30, 31, 32)) {
			List<Component> details = new ArrayList<>(); for (int i = 0; i < existing; i++) details.add(Component.literal("Original " + i));
			var combined = ResidentDialogService.withOptionalMemories(details, memories);
			assertEquals(Math.min(32, existing + 3), combined.size());
			assertEquals(details, combined.subList(0, existing));
			assertEquals(memories.subList(0, Math.min(3, 32 - existing)), combined.subList(existing, combined.size()));
		}
	}
}
