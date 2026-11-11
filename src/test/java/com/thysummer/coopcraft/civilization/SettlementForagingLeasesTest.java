package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementForagingLeasesTest {
	private static final UUID ACTOR = new UUID(61, 1), OTHER = new UUID(61, 2);
	private static final BlockPos POS = new BlockPos(1, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Home", 0);
		final SettlementForagingLeases leases = data.foragingLeases();
		Fixture() {
			data.registerMember(camp, ACTOR, 0); data.registerMember(camp, OTHER, 0);
			data.permitForagingArea(camp, ACTOR, camp.center(), 16);
		}
		SettlementForagingLeases.Lease claim(UUID actor, BlockPos pos, long now) { return leases.claim(data, camp, actor, pos, now); }
		boolean valid(SettlementForagingLeases.Lease lease, UUID actor, BlockPos pos, long now) { return leases.valid(lease, data, camp, actor, pos, now); }
	}

	@Test void dimensionCoordinatesAreExclusiveAndEachResidentHasOnlyOneLease() {
		Fixture f = new Fixture();
		var first = f.claim(ACTOR, POS, 0); assertNotNull(first);
		assertFalse(f.leases.available(POS, OTHER, 1)); assertNull(f.claim(OTHER, POS, 1));
		assertNull(f.claim(ACTOR, POS.east(), 1)); assertTrue(f.valid(first, ACTOR, POS, 1));
		Fixture otherDimension = new Fixture();
		assertNotNull(otherDimension.claim(OTHER, POS, 1));
		assertFalse(otherDimension.leases.valid(first, otherDimension.data, otherDimension.camp, ACTOR, POS, 1));
	}

	@Test void repeatedClaimsNeverExtendAbsoluteExpiryAndClockRollbackInvalidates() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 10);
		assertSame(lease, f.claim(ACTOR, POS, 209)); assertTrue(f.valid(lease, ACTOR, POS, 209));
		assertFalse(f.valid(lease, ACTOR, POS, 210));
		var next = f.claim(OTHER, POS, 210); assertNotNull(next); assertNotSame(lease, next);
		assertFalse(f.valid(next, OTHER, POS, 209));
		assertNull(f.claim(ACTOR, POS, -1)); assertNull(f.claim(ACTOR, POS, Long.MAX_VALUE));
	}

	@Test void limitRejectsRatherThanEvictingAnotherActiveResidentsLease() {
		Fixture f = new Fixture(); List<SettlementForagingLeases.Lease> leases = new ArrayList<>();
		for (int index = 0; index < SettlementForagingLeases.MAX_LEASES; index++) {
			UUID actor = new UUID(62, index); f.data.registerMember(f.camp, actor, 0);
			leases.add(f.claim(actor, new BlockPos(index % 16, 64, index / 16), 0));
			assertNotNull(leases.getLast());
		}
		assertEquals(SettlementForagingLeases.MAX_LEASES, f.leases.size(1));
		assertNull(f.claim(ACTOR, new BlockPos(-1, 64, 0), 1));
		assertTrue(f.valid(leases.getFirst(), new UUID(62, 0), new BlockPos(0, 64, 0), 1));
		assertNotNull(f.claim(ACTOR, new BlockPos(-1, 64, 0), 200));
		assertEquals(1, f.leases.size(200));
	}

	@Test void stopReleaseIsIdempotentAndOldReferencesCannotReleaseNewWork() {
		Fixture f = new Fixture(); var first = f.claim(ACTOR, POS, 0);
		f.leases.release(first); f.leases.release(first);
		assertFalse(f.valid(first, ACTOR, POS, 1));
		var next = f.claim(OTHER, POS, 1); assertNotNull(next);
		f.leases.release(first); assertTrue(f.valid(next, OTHER, POS, 1));
		f.leases.release(next); assertEquals(0, f.leases.size(1));
	}

	@Test void permitMembershipAndProtectionChangesInvalidateCapturedWorkIncludingAba() {
		for (int mutation = 0; mutation < 5; mutation++) {
			Fixture f = new Fixture(); var lease = f.claim(ACTOR, POS, 0);
			switch (mutation) {
				case 0 -> f.data.clearForagingArea(f.camp);
				case 1 -> f.data.permitForagingArea(f.camp, ACTOR, f.camp.center(), 15);
				case 2 -> { f.data.clearForagingArea(f.camp); f.data.permitForagingArea(f.camp, ACTOR, f.camp.center(), 16); }
				case 3 -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); }
				case 4 -> f.data.addProtectedArea(ACTOR, "House", 1, 0, 1, 0);
			}
			assertFalse(f.valid(lease, ACTOR, POS, 1)); assertEquals(0, f.leases.size(1));
		}
	}

	@Test void acquiringAndReleasingNeverPersistsAndReloadCannotReviveLeases() {
		Fixture f = new Fixture(); CompoundTag before = f.data.save(new CompoundTag(), null);
		var lease = f.claim(ACTOR, POS, 0); assertNotNull(lease);
		assertEquals(before, f.data.save(new CompoundTag(), null));
		SettlementSavedData loaded = SettlementSavedData.load(before, null);
		Settlement camp = loaded.settlementById(f.camp.id());
		assertEquals(0, loaded.foragingLeases().size(1));
		assertFalse(loaded.foragingLeases().valid(lease, loaded, camp, ACTOR, POS, 1));
		assertNotNull(loaded.foragingLeases().claim(loaded, camp, OTHER, POS, 1));
		f.leases.release(lease); assertEquals(before, f.data.save(new CompoundTag(), null));
	}
}
