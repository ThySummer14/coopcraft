package com.thysummer.coopcraft.civilization;

import static com.thysummer.coopcraft.civilization.SettlementShearingPermits.Status.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SettlementShearingPermitsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final String FIELD = "ShearingPermits";
	private static final UUID AUTHOR = new UUID(901, 1), SHEEP = new UUID(902, 1);

	@Test void roundTripAndSnapshotsAreImmutableAndDimensionLocal() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = camp(data, 0);
		var result = data.permitShearing(camp, AUTHOR, SHEEP);
		assertEquals(CREATED, result.status()); assertTrue(data.isDirty());
		var snapshot = data.shearingPermitsOf(camp);
		assertThrows(UnsupportedOperationException.class, snapshot::clear);
		CompoundTag expected = save(data);
		assertEquals(24, expected.getInt("DataVersion"));
		for (int i = 0; i < 2; i++) {
			data = SettlementSavedData.load(save(data), null);
			assertFalse(data.isDirty()); assertFalse(data.shearingPermitsBlocked());
			assertEquals(result.permit(), data.shearingPermitOf(SHEEP).orElseThrow());
			assertEquals(expected, save(data));
		}
		Settlement canonical = data.settlementById(camp.id());
		assertEquals(REMOVED, data.revokeShearing(canonical, SHEEP));
		assertEquals(List.of(result.permit()), snapshot);
		assertTrue(new SettlementSavedData().shearingPermitOf(SHEEP).isEmpty());
	}

	@Test void onlyRealChangesDirtyAndEveryRealChangeInvalidatesRevisionIncludingAba() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = camp(data, 0);
		Object empty = data.shearingRevision(); data.setDirty(false);
		assertEquals(NOT_FOUND, data.revokeShearing(camp, SHEEP));
		assertEquals(INVALID, data.permitShearing(camp, null, SHEEP).status());
		assertEquals(INVALID, data.revokeShearing(camp, null));
		assertFalse(data.isDirty()); assertSame(empty, data.shearingRevision());
		var first = data.permitShearing(camp, AUTHOR, SHEEP).permit();
		Object granted = data.shearingRevision(); assertNotSame(empty, granted); data.setDirty(false);
		assertEquals(UNCHANGED, data.permitShearing(camp, AUTHOR, SHEEP).status());
		assertFalse(data.isDirty()); assertSame(granted, data.shearingRevision());
		var replacement = data.permitShearing(camp, new UUID(901, 2), SHEEP);
		assertEquals(REPLACED, replacement.status()); assertNotEquals(first.id(), replacement.permit().id());
		assertTrue(data.isDirty()); assertNotSame(granted, data.shearingRevision());
		Object replaced = data.shearingRevision(); data.setDirty(false);
		assertEquals(REMOVED, data.revokeShearing(camp, SHEEP));
		assertTrue(data.isDirty()); assertNotSame(replaced, data.shearingRevision()); assertNotSame(empty, data.shearingRevision());
	}

	@Test void canonicalCampRequiredAndOtherCampCannotTransferOrRevoke() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = camp(data, 0), other = camp(data, 1);
		var permit = data.permitShearing(camp, AUTHOR, SHEEP).permit();
		Object revision = data.shearingRevision(); data.setDirty(false);
		Settlement copy = new Settlement(camp.id(), camp.center(), camp.name(), camp.foundedDay());
		for (Settlement invalid : new Settlement[] {null, copy, camp(new SettlementSavedData(), 0)}) {
			assertEquals(INVALID, data.permitShearing(invalid, AUTHOR, SHEEP).status());
			assertEquals(INVALID, data.revokeShearing(invalid, SHEEP));
			assertTrue(data.shearingPermitsOf(invalid).isEmpty());
		}
		assertEquals(OTHER_SETTLEMENT, data.permitShearing(other, AUTHOR, SHEEP).status());
		assertEquals(OTHER_SETTLEMENT, data.revokeShearing(other, SHEEP));
		assertEquals(permit, data.shearingPermitOf(SHEEP).orElseThrow());
		assertFalse(data.isDirty()); assertSame(revision, data.shearingRevision());
		data.addProtectedArea(AUTHOR, "Protected", -10, -10, 10, 10);
		assertEquals(REMOVED, data.revokeShearing(camp, SHEEP));
	}

	@Test void quotasNeverEvictAndReplacementAtCapacityIsAllowed() {
		SettlementSavedData data = new SettlementSavedData();
		for (int c = 0; c < 8; c++) {
			Settlement camp = camp(data, c);
			for (int i = 0; i < 16; i++) assertEquals(CREATED, data.permitShearing(camp, AUTHOR, new UUID(910 + c, i)).status());
			data.setDirty(false); Object revision = data.shearingRevision();
			assertEquals(LIMIT, data.permitShearing(camp, AUTHOR, new UUID(999, c)).status());
			assertFalse(data.isDirty()); assertSame(revision, data.shearingRevision());
		}
		Settlement extra = camp(data, 8); data.setDirty(false);
		assertEquals(LIMIT, data.permitShearing(extra, AUTHOR, SHEEP).status()); assertFalse(data.isDirty());
		assertEquals(REPLACED, data.permitShearing(camp(data, 0), new UUID(901, 2), new UUID(910, 0)).status());
		assertEquals(REMOVED, data.revokeShearing(camp(data, 0), new UUID(910, 0)));
		assertEquals(CREATED, data.permitShearing(extra, AUTHOR, SHEEP).status());
	}

	@Test void oldMissingInitializesButNewMissingAndBadVersionsStayMissingAndBlocked() {
		for (Tag version : new Tag[] {null, IntTag.valueOf(0), IntTag.valueOf(23), IntTag.valueOf(24),
				IntTag.valueOf(99), IntTag.valueOf(-1), StringTag.valueOf("23"), ByteTag.valueOf((byte) 23)}) {
			CompoundTag input = save(new SettlementSavedData()); input.remove(FIELD);
			if (version == null) input.remove("DataVersion"); else input.put("DataVersion", version);
			boolean legacy = version == null || version instanceof IntTag number && number.getAsInt() >= 0 && number.getAsInt() < 24;
			SettlementSavedData data = SettlementSavedData.load(input, null);
			for (int i = 0; i < 3; i++) {
				assertEquals(!legacy, data.shearingPermitsBlocked()); assertFalse(data.isDirty());
				assertTrue(data.shearingPermitOf(SHEEP).isEmpty());
				CompoundTag output = save(data); assertEquals(legacy, output.contains(FIELD));
				CompoundTag reused = output.copy(); reused.put(FIELD, new CompoundTag()); data.save(reused, null);
				assertEquals(output, reused);
				if (!legacy) assertEquals(PRESERVED_UNSUPPORTED, data.permitShearing(null, AUTHOR, SHEEP).status());
				data = SettlementSavedData.load(output, null);
			}
		}
	}


	@Test void grantAndRevokeDoNotChangeMembersInventoryJobsOrOtherSavedState() {
		SettlementSavedData data = new SettlementSavedData(); Settlement camp = camp(data, 0);
		data.registerMember(camp, AUTHOR, 5); data.setSatiety(camp, AUTHOR, 42);
		data.setStockpile(camp, new BlockPos(2, 64, 2));
		data.toggleResource(camp, BlockPos.ZERO);
		data.setConstructionJob(camp, new ConstructionJob(ConstructionPlanner.STONE_COTTAGE,
			new BlockPos(3, 64, 3), 7, "existing-fingerprint", new UUID(930, 1)));
		var simulation = data.getOrCreateSimulation(camp);
		simulation.mapResident(AUTHOR, false, 42);
		simulation.world().importExternalBatch(ResourceType.WOOD, 12, simulation.campOwner());
		CompoundTag before = save(data); before.remove(FIELD);
		assertEquals(CREATED, data.permitShearing(camp, AUTHOR, SHEEP).status());
		CompoundTag granted = save(data); granted.remove(FIELD); assertEquals(before, granted);
		assertEquals(REMOVED, data.revokeShearing(camp, SHEEP));
		CompoundTag revoked = save(data); revoked.remove(FIELD); assertEquals(before, revoked);
	}

	@Test void malformedFutureDuplicateAndOverLimitSubtreesArePreservedWithoutAliases() {
		SettlementSavedData source = new SettlementSavedData(); Settlement camp = camp(source, 0);
		source.permitShearing(camp, AUTHOR, SHEEP);
		CompoundTag valid = save(source).getCompound(FIELD);
		List<Tag> bad = new ArrayList<>(List.of(StringTag.valueOf("unknown"), new CompoundTag(), new ListTag()));
		bad.add(changed(valid, root -> root.putInt("Version", 2)));
		bad.add(changed(valid, root -> root.putByte("Version", (byte) 1)));
		bad.add(changed(valid, root -> root.putString("Extra", "future")));
		bad.add(changed(valid, root -> root.remove("Permits")));
		bad.add(changed(valid, root -> root.putString("Permits", "wrong")));
		bad.add(changed(valid, root -> { ListTag list = new ListTag(); list.add(StringTag.valueOf("bad")); root.put("Permits", list); }));
		for (String key : List.of("Id", "SheepId", "SettlementId", "Authorizer")) {
			bad.add(changed(valid, root -> entry(root).remove(key)));
			bad.add(changed(valid, root -> entry(root).putString(key, "bad")));
			bad.add(changed(valid, root -> entry(root).putIntArray(key, new int[] {1, 2, 3})));
		}
		bad.add(changed(valid, root -> entry(root).putInt("Future", 1)));
		for (String key : List.of("Id", "SheepId")) bad.add(changed(valid, root -> {
			CompoundTag duplicate = entry(root).copy(); duplicate.putUUID(key.equals("Id") ? "SheepId" : "Id", UUID.randomUUID());
			root.getList("Permits", Tag.TAG_COMPOUND).add(duplicate);
		}));
		for (int count : new int[] {17, 129}) bad.add(changed(valid, root -> {
			ListTag list = new ListTag();
			for (int i = 0; i < count; i++) {
				CompoundTag item = entry(root).copy(); item.putUUID("Id", new UUID(920, i)); item.putUUID("SheepId", new UUID(921, i));
				if (count > 17) item.putUUID("SettlementId", new UUID(922, i / 16)); list.add(item);
			}
			root.put("Permits", list);
		}));
		for (Tag raw : bad) {
			CompoundTag input = save(source); input.put(FIELD, raw.copy());
			SettlementSavedData data = SettlementSavedData.load(input, null);
			for (int i = 0; i < 3; i++) {
				assertTrue(data.shearingPermitsBlocked(), raw::toString);
				Settlement canonical = data.settlementById(camp.id());
				Object revision = data.shearingRevision();
				assertEquals(PRESERVED_UNSUPPORTED, data.permitShearing(canonical, AUTHOR, SHEEP).status());
				assertEquals(PRESERVED_UNSUPPORTED, data.revokeShearing(canonical, SHEEP));
				assertTrue(data.shearingPermitsOf(canonical).isEmpty()); assertFalse(data.isDirty());
				assertSame(revision, data.shearingRevision()); assertEquals(raw, save(data).get(FIELD));
				data = SettlementSavedData.load(save(data), null);
			}
		}
		CompoundTag unknown = new CompoundTag(); unknown.putString("Future", "original");
		CompoundTag input = save(source); input.put(FIELD, unknown);
		SettlementSavedData loaded = SettlementSavedData.load(input, null);
		unknown.putString("Future", "changed"); save(loaded).getCompound(FIELD).putString("Future", "changed output");
		assertEquals("original", save(loaded).getCompound(FIELD).getString("Future"));
	}

	private static Settlement camp(SettlementSavedData data, int index) {
		return data.getOrCreate(new BlockPos(index * 200, 64, 0), "Camp " + index, 1);
	}
	private static CompoundTag save(SettlementSavedData data) { return data.save(new CompoundTag(), null); }
	private static CompoundTag entry(CompoundTag root) { return root.getList("Permits", Tag.TAG_COMPOUND).getCompound(0); }
	private static CompoundTag changed(CompoundTag valid, Consumer<CompoundTag> change) {
		CompoundTag copy = valid.copy(); change.accept(copy); return copy;
	}
}
