package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementMaterialLeasesTest {
	private static final UUID ACTOR = new UUID(941, 1), OTHER = new UUID(941, 2);
	private static final BlockPos POS = new BlockPos(1, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Home", 0);
		final SettlementMaterialLeases leases = data.materialLeases();
		Fixture() { data.registerMember(camp, ACTOR, 0); data.registerMember(camp, OTHER, 0); }
		SettlementMaterialLeases.Lease claim(UUID actor, BlockPos pos, long now) { return leases.claim(data, camp, actor, pos, now); }
		boolean valid(SettlementMaterialLeases.Lease lease, UUID actor, BlockPos pos, long now) { return leases.valid(lease, data, camp, actor, pos, now); }
	}

	@Test void manualAndAutomaticRolesCompeteForCoordinatesWithoutGrantingAuthority() {
		Fixture f = new Fixture();
		var manual = f.claim(ACTOR, POS, 0); assertNotNull(manual);
		assertTrue(f.data.gatheringAreaOf(f.camp).isEmpty());
		assertFalse(f.data.canGatherMaterial(f.camp, POS, SettlementResourceObservations.Kind.OAK_LOG));
		assertFalse(f.leases.available(POS, OTHER, 1)); assertNull(f.claim(OTHER, POS, 1));
		assertTrue(f.leases.available(POS, ACTOR, 1)); assertNull(f.claim(ACTOR, POS.east(), 1));
		f.leases.release(manual); assertNotNull(f.claim(OTHER, POS, 1));
		Fixture dimension = new Fixture(); assertNotNull(dimension.claim(ACTOR, POS, 1));
	}

	@Test void leasesAreCoordinationOnlyAndAreaOrProtectionChangesDoNotBecomeAuthorityChecks() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 0);
		f.data.permitGatheringArea(f.camp, ACTOR, f.camp.center(), 8, SettlementGatheringAreas.Mode.BOTH);
		f.data.clearGatheringArea(f.camp);
		f.data.addProtectedArea(ACTOR, "Keep", 1, 0, 1, 0);
		assertTrue(f.valid(lease, ACTOR, POS, 1));
		assertFalse(f.data.canGatherMaterial(f.camp, POS, SettlementResourceObservations.Kind.STONE));
	}

	@Test void repeatedClaimsNeverRenewAndExpiryIsExclusive() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 10);
		assertEquals(2400, SettlementMaterialLeases.TTL_TICKS);
		assertSame(lease, f.claim(ACTOR, POS, 2409)); assertTrue(f.valid(lease, ACTOR, POS, 2409));
		assertFalse(f.valid(lease, ACTOR, POS, 2410));
		assertNotNull(f.claim(OTHER, POS, 2410));
	}

	@Test void clockReversalAndOverflowCannotCreateImmortalLeases() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 10);
		assertFalse(f.valid(lease, ACTOR, POS, 9)); assertEquals(0, f.leases.size(9));
		assertNull(f.claim(ACTOR, POS, -1));
		assertNull(f.claim(ACTOR, POS, Long.MAX_VALUE));
		assertNull(f.claim(ACTOR, POS, Long.MAX_VALUE - SettlementMaterialLeases.TTL_TICKS + 1));
		var boundary = f.claim(ACTOR, POS, Long.MAX_VALUE - SettlementMaterialLeases.TTL_TICKS);
		assertNotNull(boundary); assertTrue(f.valid(boundary, ACTOR, POS, Long.MAX_VALUE - SettlementMaterialLeases.TTL_TICKS));
		assertFalse(f.valid(boundary, ACTOR, POS, Long.MAX_VALUE));
	}

	@Test void availabilityAdvancesClockAndRollbackPermanentlyClearsLeases() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 10);
		assertTrue(f.leases.available(POS, ACTOR, 20));
		assertFalse(f.leases.available(POS, ACTOR, 19));
		var newTimeline = f.claim(OTHER, POS, 19); assertNotNull(newTimeline);
		assertFalse(f.valid(lease, ACTOR, POS, 20), "Availability rollback must release old work even after time recovers");
		assertEquals(1, f.leases.size(20));
		assertSame(newTimeline, f.claim(OTHER, POS, 20));
	}

	@Test void availabilityExpiresWithoutRenewingAndOldHandlesCannotReviveOrReleaseReplacement() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 10);
		assertTrue(f.leases.available(POS, ACTOR, 2409));
		assertFalse(f.leases.available(POS, OTHER, 2409));
		assertTrue(f.leases.available(POS, OTHER, 2410), "Availability must not renew an existing lease");
		assertFalse(f.valid(lease, ACTOR, POS, 2410)); assertEquals(0, f.leases.size(2410));
		assertFalse(f.leases.available(POS, ACTOR, 2409));
		assertFalse(f.valid(lease, ACTOR, POS, 2410), "Expired handle must stay invalid after clock recovery");
		var replacement = f.claim(OTHER, POS, 2410); assertNotNull(replacement);
		f.leases.release(lease); assertTrue(f.valid(replacement, OTHER, POS, 2410));
	}

	@Test void capRejectsInsteadOfEvictingAndExpirationReclaimsCapacity() {
		Fixture f = new Fixture(); assertEquals(128, SettlementMaterialLeases.MAX_LEASES);
		SettlementMaterialLeases.Lease first = null;
		for (int i = 0; i < SettlementMaterialLeases.MAX_LEASES; i++) {
			UUID actor = new UUID(942, i); f.data.registerMember(f.camp, actor, 0);
			var lease = f.claim(actor, new BlockPos(i, 64, 0), 0); assertNotNull(lease);
			if (i == 0) first = lease;
		}
		assertEquals(128, f.leases.size(1)); assertNull(f.claim(ACTOR, POS.west().west(), 1));
		assertTrue(f.valid(first, new UUID(942, 0), new BlockPos(0, 64, 0), 1));
		assertNotNull(f.claim(ACTOR, POS.west().west(), 2400)); assertEquals(1, f.leases.size(2400));
	}

	@Test void releaseIsIdempotentAndStaleOrForeignHandlesCannotReleaseNewWork() {
		Fixture f = new Fixture(); var first = f.claim(ACTOR, POS, 0);
		f.leases.release(first); f.leases.release(first); f.leases.release(null);
		assertFalse(f.valid(first, ACTOR, POS, 1));
		var next = f.claim(OTHER, POS, 1); f.leases.release(first);
		Fixture foreign = new Fixture(); var foreignLease = foreign.claim(OTHER, POS, 1);
		f.leases.release(foreignLease); assertTrue(f.valid(next, OTHER, POS, 1));
		assertFalse(f.valid(next, ACTOR, POS, 1)); assertFalse(f.valid(next, OTHER, POS.east(), 1));
		f.leases.release(next); assertEquals(0, f.leases.size(1));
	}

	@Test void memberAbaAndCopiedCampNeverReviveCapturedWork() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 0);
		f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0);
		assertFalse(f.valid(lease, ACTOR, POS, 1));
		Settlement copy = new Settlement(f.camp.id(), f.camp.center(), f.camp.name(), f.camp.foundedDay());
		assertNull(f.leases.claim(f.data, copy, ACTOR, POS, 1));
		assertNotNull(f.claim(ACTOR, POS, 1));
	}

	@Test void leasesNeverPersistAndReloadCannotReuseOldHandles() {
		Fixture f = new Fixture(); CompoundTag before = f.data.save(new CompoundTag(), null);
		var lease = f.claim(ACTOR, POS, 0); assertNotNull(lease);
		assertEquals(before, f.data.save(new CompoundTag(), null));
		SettlementSavedData loaded = SettlementSavedData.load(before, null);
		Settlement camp = loaded.settlementById(f.camp.id());
		assertEquals(0, loaded.materialLeases().size(1));
		assertFalse(loaded.materialLeases().valid(lease, loaded, camp, ACTOR, POS, 1));
		assertNotNull(loaded.materialLeases().claim(loaded, camp, OTHER, POS, 1));
		f.leases.release(lease); assertEquals(before, f.data.save(new CompoundTag(), null));
	}
}
