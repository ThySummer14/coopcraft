package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.SettlementGatheringAreas.Mode.*;
import static com.thysummer.coopcraft.civilization.SettlementGatheringAreas.Status.*;
import static com.thysummer.coopcraft.civilization.SettlementResourceObservations.Kind.OAK_LOG;
import static com.thysummer.coopcraft.civilization.SettlementResourceObservations.Kind.SWEET_BERRY;
import static org.junit.jupiter.api.Assertions.*;

class SettlementGatheringAreasTest {
	private static final UUID AUTHOR = new UUID(930, 1);
	private static final String FIELD = "GatheringAreas";
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void modesPermitOnlyTheirMaterialsInAnInclusiveFullHeightSquare() {
		SettlementSavedData data = fixture(); Settlement camp = camp(data);
		for (var mode : SettlementGatheringAreas.Mode.values()) {
			data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, 8, mode);
			for (int y : new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}) {
				BlockPos corner = new BlockPos(8, y, -8);
				assertEquals(mode != STONE, data.canGatherMaterial(camp, corner, OAK_LOG));
				assertEquals(mode != WOOD, data.canGatherMaterial(camp, corner, SettlementResourceObservations.Kind.STONE));
				assertFalse(data.canGatherMaterial(camp, corner, SWEET_BERRY));
			}
			assertFalse(data.canGatherMaterial(camp, new BlockPos(9, 0, 0), OAK_LOG));
		}
		assertFalse(data.canGatherMaterial(camp, null, OAK_LOG));
		assertFalse(data.canGatherMaterial(camp, BlockPos.ZERO, null));
		SettlementSavedData other = fixture();
		assertFalse(other.canGatherMaterial(camp(other), BlockPos.ZERO, OAK_LOG));
	}

	@Test void unchangedPermitRetainsIdentityWhileEveryChangeAndRecreationInvalidatesIt() {
		SettlementSavedData data = fixture(); Settlement camp = camp(data);
		var original = data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, 2, WOOD).area();
		data.setDirty(false);
		var unchanged = data.permitGatheringArea(camp, AUTHOR, new BlockPos(0, 300, 0), 2, WOOD);
		assertEquals(UNCHANGED, unchanged.status()); assertSame(original, unchanged.area()); assertFalse(data.isDirty());
		var modeChanged = data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, 2, BOTH);
		assertEquals(REPLACED, modeChanged.status()); assertNotSame(original, modeChanged.area());
		var authorChanged = data.permitGatheringArea(camp, new UUID(930, 2), BlockPos.ZERO, 2, BOTH);
		assertNotSame(modeChanged.area(), authorChanged.area());
		var moved = data.permitGatheringArea(camp, AUTHOR, new BlockPos(1, 0, 0), 3, BOTH);
		assertNotSame(authorChanged.area(), moved.area()); assertTrue(data.isDirty());
		assertEquals(REMOVED, data.clearGatheringArea(camp)); data.setDirty(false);
		assertEquals(NOT_FOUND, data.clearGatheringArea(camp)); assertFalse(data.isDirty());
		var recreated = data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, 2, WOOD).area();
		assertNotSame(original, recreated); assertNotEquals(original.id(), recreated.id());
	}

	@Test void invalidRangesAndCapacityNeverMutateOrEvictExistingAreas() {
		SettlementSavedData data = fixture(); Settlement camp = camp(data);
		for (int radius : new int[] {-1, 0, 9, Integer.MAX_VALUE})
			assertEquals(INVALID, data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, radius, BOTH).status());
		assertTrue(SettlementGatheringAreas.validRange(camp, new BlockPos(62, 1000, 0), 1));
		assertFalse(SettlementGatheringAreas.validRange(camp, new BlockPos(63, 0, 0), 1));
		assertFalse(SettlementGatheringAreas.validRange(camp, new BlockPos(45, 0, 45), 1));
		assertTrue(SettlementGatheringAreas.validBounds(29_999_976, -29_999_976, 8));
		assertFalse(SettlementGatheringAreas.validBounds(29_999_977, 0, 8));
		assertFalse(SettlementGatheringAreas.validBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
		assertEquals(INVALID, data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, 1, null).status());
		assertEquals(INVALID, data.permitGatheringArea(camp, null, BlockPos.ZERO, 1, BOTH).status());
		assertEquals(INVALID, data.clearGatheringArea(null)); assertFalse(data.isDirty());
		SettlementGatheringAreas areas = new SettlementGatheringAreas();
		for (int i = 0; i < 128; i++) {
			var update = areas.permit(new UUID(931, i), AUTHOR, BlockPos.ZERO, 1, BOTH);
			assertEquals(CREATED, update.status()); areas = update.value();
		}
		var full = areas;
		assertSame(full, full.permit(new UUID(932, 1), AUTHOR, BlockPos.ZERO, 1, BOTH).value());
		assertEquals(LIMIT, full.permit(new UUID(932, 1), AUTHOR, BlockPos.ZERO, 1, BOTH).status());
		assertEquals(REPLACED, full.permit(new UUID(931, 0), AUTHOR, BlockPos.ZERO, 2, WOOD).status());
		assertEquals(CREATED, full.revoke(new UUID(931, 0)).value()
			.permit(new UUID(932, 1), AUTHOR, BlockPos.ZERO, 1, BOTH).status());
	}

	@Test void currentCanonicalCampAndProtectionAlwaysTakePriority() {
		SettlementSavedData data = fixture(); Settlement camp = camp(data);
		data.permitGatheringArea(camp, AUTHOR, BlockPos.ZERO, 2, BOTH);
		Settlement copy = new Settlement(camp.id(), camp.center(), camp.name(), camp.foundedDay());
		assertTrue(data.gatheringAreaOf(copy).isEmpty());
		assertFalse(data.canGatherMaterial(copy, BlockPos.ZERO, OAK_LOG));
		assertEquals(INVALID, data.clearGatheringArea(copy));
		var protection = data.addProtectedArea(AUTHOR, "Keep", 0, 0, 0, 0).area();
		assertFalse(data.canGatherMaterial(camp, new BlockPos(0, 1000, 0), OAK_LOG));
		assertTrue(data.canGatherMaterial(camp, new BlockPos(1, 0, 0), OAK_LOG));
		data.removeProtectedArea(protection.id(), AUTHOR, false);
		assertTrue(data.canGatherMaterial(camp, BlockPos.ZERO, OAK_LOG));
		CompoundTag moved = save(data);
		moved.getList("Settlements", Tag.TAG_COMPOUND).getCompound(0).putLong("Center", new BlockPos(1000, 0, 0).asLong());
		SettlementSavedData relocated = SettlementSavedData.load(moved, null);
		assertFalse(relocated.canGatherMaterial(relocated.all().getFirst(), BlockPos.ZERO, OAK_LOG));
		CompoundTag damaged = save(data); damaged.remove("PlayerProtectedAreas");
		SettlementSavedData blocked = SettlementSavedData.load(damaged, null);
		assertTrue(blocked.protectedAreas().blocked()); assertFalse(blocked.canGatherMaterial(camp(blocked), BlockPos.ZERO, OAK_LOG));
	}

	@Test void version23RoundTripsWithoutChangingManualMarksObservationsOrInventory() {
		SettlementSavedData data = fixture(); CompoundTag before = save(data);
		var area = data.permitGatheringArea(camp(data), AUTHOR, BlockPos.ZERO, 8, BOTH).area();
		CompoundTag expected = save(data); assertEquals(24, expected.getInt("DataVersion"));
		assertEquals(1, expected.getCompound(FIELD).getInt("Version"));
		for (int pass = 0; pass < 2; pass++) {
			data = SettlementSavedData.load(save(data), null);
			assertEquals(area, data.gatheringAreaOf(camp(data)).orElseThrow()); assertFalse(data.isDirty());
			assertTrue(data.canGatherMaterial(camp(data), BlockPos.ZERO, OAK_LOG));
			assertEquals(expected, save(data)); assertEquals(withoutArea(before), withoutArea(save(data)));
		}
		data.clearGatheringArea(camp(data)); assertEquals(before, save(data));
		for (int version : new int[] {0, 19, 22, 23, 99}) {
			CompoundTag legacy = before.copy(); legacy.remove(FIELD); legacy.putInt("DataVersion", version);
			data = SettlementSavedData.load(legacy, null);
			assertFalse(data.gatheringAreasBlocked()); assertTrue(data.gatheringAreaOf(camp(data)).isEmpty());
			assertFalse(data.canGatherMaterial(camp(data), BlockPos.ZERO, OAK_LOG));
			assertEquals(withoutArea(before), withoutArea(save(data)));
		}
	}

	@Test void malformedFutureAndDuplicateSubtreesArePreservedEntirelyWithoutAuthority() {
		SettlementSavedData source = fixture(); source.permitGatheringArea(camp(source), AUTHOR, BlockPos.ZERO, 2, BOTH);
		CompoundTag baseline = save(source), valid = baseline.getCompound(FIELD);
		List<Tag> cases = new ArrayList<>(List.of(StringTag.valueOf("future"), new CompoundTag()));
		cases.add(changed(valid, root -> root.putInt("Version", 2)));
		cases.add(changed(valid, root -> root.putByte("Version", (byte) 1)));
		cases.add(changed(valid, root -> root.putString("Unknown", "retain")));
		cases.add(changed(valid, root -> entry(root).putString("Mode", "ORE")));
		cases.add(changed(valid, root -> entry(root).putString("Extra", "retain")));
		cases.add(changed(valid, root -> entry(root).putInt("Radius", 9)));
		for (String key : List.of("Id", "SettlementId", "Authorizer", "CenterX", "CenterZ", "Radius", "Mode")) {
			cases.add(changed(valid, root -> entry(root).remove(key)));
			cases.add(changed(valid, root -> entry(root).put(key, new ListTag())));
		}
		for (String key : List.of("Id", "SettlementId")) cases.add(changed(valid, root -> {
			CompoundTag duplicate = entry(root).copy(); duplicate.putUUID(key, UUID.randomUUID());
			root.getList("Areas", Tag.TAG_COMPOUND).add(duplicate);
		}));
		cases.add(changed(valid, root -> {
			for (int i = 0; i < 128; i++) {
				CompoundTag extra = entry(root).copy(); extra.putUUID("Id", new UUID(934, i));
				extra.putUUID("SettlementId", new UUID(935, i)); root.getList("Areas", Tag.TAG_COMPOUND).add(extra);
			}
		}));
		for (Tag raw : cases) {
			CompoundTag input = baseline.copy(); input.put(FIELD, raw.copy());
			SettlementSavedData data = SettlementSavedData.load(input, null);
			for (int pass = 0; pass < 2; pass++) {
				assertTrue(data.gatheringAreasBlocked(), raw::toString); assertTrue(data.gatheringAreaOf(camp(data)).isEmpty());
				assertFalse(data.canGatherMaterial(camp(data), BlockPos.ZERO, OAK_LOG));
				assertEquals(PRESERVED_UNSUPPORTED, data.permitGatheringArea(camp(data), AUTHOR, BlockPos.ZERO, 1, WOOD).status());
				assertEquals(PRESERVED_UNSUPPORTED, data.clearGatheringArea(camp(data))); assertFalse(data.isDirty());
				assertEquals(raw, save(data).get(FIELD)); assertEquals(withoutArea(baseline), withoutArea(save(data)));
				data = SettlementSavedData.load(save(data), null);
			}
		}
		CompoundTag raw = new CompoundTag(); raw.putString("Future", "original");
		var blocked = SettlementGatheringAreas.load(raw); raw.putString("Future", "changed");
		((CompoundTag) blocked.save()).putString("Future", "changed again");
		assertEquals("original", ((CompoundTag) blocked.save()).getString("Future"));
	}

	private static CompoundTag changed(CompoundTag valid, Consumer<CompoundTag> edit) {
		CompoundTag copy = valid.copy(); edit.accept(copy); return copy;
	}
	private static CompoundTag entry(CompoundTag root) { return root.getList("Areas", Tag.TAG_COMPOUND).getCompound(0); }
	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }
	private static CompoundTag withoutArea(CompoundTag input) { CompoundTag copy = input.copy(); copy.remove(FIELD); return copy; }
	private static Settlement camp(SettlementSavedData data) { return data.find(BlockPos.ZERO).orElseThrow(); }
	private static SettlementSavedData fixture() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = data.getOrCreate(BlockPos.ZERO, "Camp", 0);
		data.registerMember(camp, AUTHOR, 0); data.toggleResource(camp, BlockPos.ZERO);
		data.recordResourceObservation(camp, new BlockPos(1, 64, 1), true);
		data.getOrCreateSimulation(camp).world().importExternalBatch(ResourceType.WOOD, 12,
			data.getOrCreateSimulation(camp).campOwner());
		data.setDirty(false); return data;
	}
}
