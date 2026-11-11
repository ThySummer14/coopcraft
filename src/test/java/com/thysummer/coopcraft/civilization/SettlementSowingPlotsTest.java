package com.thysummer.coopcraft.civilization;

import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.civilization.SettlementSowingPlots.Status.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SettlementSowingPlotsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static Settlement camp(SettlementSavedData data, int x) { return data.getOrCreate(new BlockPos(x, 64, 0), "Camp", 0); }
	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }
	@Test void optionalFieldDoesNotMigrateResourcesAndRoundTripsStable() {
		var data = new SettlementSavedData(); var camp = camp(data, 0); BlockPos pos = camp.center();
		CompoundTag old = save(data); old.remove("SowingPlots");
		var loaded = SettlementSavedData.load(old, null);
		assertFalse(loaded.sowingPlots().blocked()); assertTrue(loaded.sowingPlots().all().isEmpty()); assertFalse(loaded.isDirty());
		assertEquals(CREATED, data.addSowingPlot(camp, pos));
		var permit = data.sowingPermitOf(camp, pos).orElseThrow(); CompoundTag expected = save(data);
		assertEquals(27, expected.getInt("DataVersion"));
		for (int i = 0; i < 3; i++) {
			data = SettlementSavedData.load(save(data), null);
			assertEquals(expected, save(data)); assertFalse(data.isDirty());
			assertEquals(permit, data.sowingPermitOf(data.settlementById(camp.id()), pos).orElseThrow());
		}
	}
	@Test void onlyChangesDirtyAndAbaCreatesNewToken() {
		var data = new SettlementSavedData(); var camp = camp(data, 0); BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(1, 64, 0);
		assertEquals(CREATED, data.addSowingPlot(camp, pos)); var first = data.sowingPermitOf(camp, pos).orElseThrow();
		pos.set(4, 64, 0); assertEquals(new BlockPos(1, 64, 0), first.position());
		data.setDirty(false); var snapshot = data.sowingPlots();
		assertEquals(UNCHANGED, data.addSowingPlot(camp, first.position())); assertFalse(data.isDirty()); assertSame(snapshot, data.sowingPlots());
		assertEquals(NOT_FOUND, data.removeSowingPlot(camp, pos)); assertFalse(data.isDirty());
		assertEquals(REMOVED, data.removeSowingPlot(camp, first.position()));
		assertEquals(CREATED, data.addSowingPlot(camp, first.position())); var second = data.sowingPermitOf(camp, first.position()).orElseThrow();
		assertNotSame(first, second); assertNotEquals(first.id(), second.id());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.all().clear());
	}
	@Test void ownershipProtectionAndUnknownCampFailClosedWithoutDeletingRecords() {
		var data = new SettlementSavedData(); var camp = camp(data, 0); var pos = new BlockPos(40, 64, 0);
		assertEquals(CREATED, data.addSowingPlot(camp, pos));
		var copy = new Settlement(camp.id(), camp.center(), camp.name(), camp.foundedDay());
		assertTrue(data.sowingPermitOf(copy, pos).isEmpty()); assertEquals(INVALID, data.removeSowingPlot(copy, pos));
		data.addProtectedArea(UUID.randomUUID(), "Protected", 40, 0, 40, 0);
		assertTrue(data.sowingPermitOf(camp, pos).isPresent()); assertFalse(data.canUseSowingPlot(camp, pos));
		var other = camp(data, 64); assertNotSame(camp, other); assertTrue(data.sowingPermitOf(camp, pos).isEmpty()); assertTrue(data.sowingPermitOf(other, pos).isEmpty());
		assertEquals(OTHER_SETTLEMENT, data.removeSowingPlot(other, pos));
		assertEquals(REMOVED, data.removeSowingPlot(camp, pos)); // No loaded-block or protection dependency.
		var plots = new SettlementSowingPlots(); var unknown = UUID.randomUUID(); plots = plots.add(unknown, pos).value();
		CompoundTag tag = save(data); tag.put("SowingPlots", plots.save()); var loaded = SettlementSavedData.load(tag, null);
		assertEquals(1, loaded.sowingPlots().all().size()); assertTrue(loaded.sowingPositions(loaded.settlementById(camp.id())).isEmpty());
	}
	@Test void limitsAndDuplicateCoordinatesNeverEvict() {
		var plots = new SettlementSowingPlots();
		for (int c = 0; c < 4; c++) {
			UUID camp = new UUID(1, c);
			for (int i = 0; i < 32; i++) { var update = plots.add(camp, new BlockPos(c, i, 0)); assertEquals(CREATED, update.status()); plots = update.value(); }
			assertEquals(LIMIT, plots.add(camp, new BlockPos(c, 100, 0)).status());
		}
		assertEquals(128, plots.all().size()); assertEquals(LIMIT, plots.add(UUID.randomUUID(), BlockPos.ZERO.above(1000)).status());
		assertEquals(UNCHANGED, plots.add(new UUID(1, 0), BlockPos.ZERO).status());
		assertEquals(OTHER_SETTLEMENT, plots.add(UUID.randomUUID(), BlockPos.ZERO).status());
	}
	@Test void malformedProtectionBlocksUseButAllowsRevocation() {
		var data = new SettlementSavedData(); var camp = camp(data, 0); assertEquals(CREATED, data.addSowingPlot(camp, camp.center()));
		CompoundTag saved = save(data); saved.putString("PlayerProtectedAreas", "future");
		data = SettlementSavedData.load(saved, null); camp = data.settlementById(camp.id());
		assertTrue(data.sowingPermitOf(camp, camp.center()).isPresent()); assertFalse(data.canUseSowingPlot(camp, camp.center()));
		assertEquals(INVALID, data.addSowingPlot(camp, camp.center().east()));
		assertEquals(REMOVED, data.removeSowingPlot(camp, camp.center()));
	}
	@Test void oversizedSerializedListsArePreservedAndHorizontalRangeIncludesBoundary() {
		for (boolean global : List.of(false, true)) {
			CompoundTag root = new CompoundTag(); root.putInt("Version", 1); ListTag entries = new ListTag();
			for (int i = 0; i < (global ? 129 : 33); i++) {
				CompoundTag e = new CompoundTag(); e.putUUID("Id", new UUID(2, i)); e.putUUID("CampId", new UUID(1, global ? i : 0));
				e.putInt("X", i); e.putInt("Y", 64); e.putInt("Z", 0); entries.add(e);
			}
			root.put("Permits", entries); var loaded = SettlementSowingPlots.load(root);
			assertTrue(loaded.blocked()); assertEquals(root, loaded.save());
		}
		var data = new SettlementSavedData(); var camp = camp(data, 0);
		assertEquals(CREATED, data.addSowingPlot(camp, new BlockPos(64, 1000, 0)));
		assertEquals(INVALID, data.addSowingPlot(camp, new BlockPos(65, 64, 0)));
	}
	@Test void invalidCoordinatesAreRejectedAndPreservedAsAWhole() {
		var camp = UUID.randomUUID();
		for (BlockPos pos : List.of(new BlockPos(Integer.MIN_VALUE, 64, 0), new BlockPos(0, Integer.MIN_VALUE, 0),
			new BlockPos(0, 64, 30_000_001), new BlockPos(0, -2049, 0), new BlockPos(0, 2048, 0))) {
			assertEquals(INVALID, new SettlementSowingPlots().add(camp, pos).status());
			CompoundTag root = (CompoundTag) new SettlementSowingPlots().add(camp, BlockPos.ZERO).value().save();
			CompoundTag entry = root.getList("Permits", Tag.TAG_COMPOUND).getCompound(0);
			entry.putInt("X", pos.getX()); entry.putInt("Y", pos.getY()); entry.putInt("Z", pos.getZ());
			var decoded = SettlementSowingPlots.load(root); assertTrue(decoded.blocked()); assertTrue(decoded.all().isEmpty()); assertEquals(root, decoded.save());
		}
		assertEquals(CREATED, new SettlementSowingPlots().add(camp, new BlockPos(30_000_000, -2048, -30_000_000)).status());
	}
	@Test void malformedOrFutureWholeFieldIsPreservedAndNeverPartiallyLoaded() {
		var original = new SettlementSowingPlots().add(UUID.randomUUID(), BlockPos.ZERO).value();
		CompoundTag valid = (CompoundTag) original.save(); List<Tag> bad = new ArrayList<>();
		bad.add(StringTag.valueOf("opaque"));
		CompoundTag future = valid.copy(); future.putInt("Version", 2); bad.add(future);
		CompoundTag wrongVersion = valid.copy(); wrongVersion.putByte("Version", (byte) 1); bad.add(wrongVersion);
		CompoundTag extra = valid.copy(); extra.putString("Unknown", "keep"); bad.add(extra);
		for (String field : List.of("Id", "CampId", "X", "Y", "Z")) {
			CompoundTag changed = valid.copy(); changed.getList("Permits", Tag.TAG_COMPOUND).getCompound(0).remove(field); bad.add(changed);
		}
		CompoundTag duplicate = valid.copy(); duplicate.getList("Permits", Tag.TAG_COMPOUND).add(valid.getList("Permits", Tag.TAG_COMPOUND).getCompound(0).copy()); bad.add(duplicate);
		CompoundTag duplicatePosition = duplicate.copy(); duplicatePosition.getList("Permits", Tag.TAG_COMPOUND).getCompound(1).putUUID("Id", UUID.randomUUID()); bad.add(duplicatePosition);
		CompoundTag duplicateId = duplicate.copy(); duplicateId.getList("Permits", Tag.TAG_COMPOUND).getCompound(1).putInt("X", 8); bad.add(duplicateId);
		for (Tag raw : bad) {
			var decoded = SettlementSowingPlots.load(raw); assertTrue(decoded.blocked()); assertTrue(decoded.all().isEmpty());
			assertEquals(PRESERVED_UNSUPPORTED, decoded.add(UUID.randomUUID(), BlockPos.ZERO).status());
			assertEquals(PRESERVED_UNSUPPORTED, decoded.remove(UUID.randomUUID(), BlockPos.ZERO).status());
			assertEquals(raw, decoded.save()); assertEquals(raw, SettlementSowingPlots.load(decoded.save()).save());
		}
	}
}
