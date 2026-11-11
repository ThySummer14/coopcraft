package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementShearingLeasesTest {
	private static final UUID ACTOR = new UUID(976, 1), OTHER = new UUID(976, 2), SHEEP = new UUID(977, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(new BlockPos(0, 64, 0), "Home", 0);
		final SettlementShearingLeases leases = data.shearingLeases();
		final Object actor = new Object(), sheep = new Object();
		Fixture() { data.registerMember(camp, ACTOR, 0); data.registerMember(camp, OTHER, 0); }
		SettlementShearingLeases.Lease claim(UUID id, UUID target, long now) { return leases.claim(data, camp, id, actor, target, sheep, now); }
		boolean valid(SettlementShearingLeases.Lease lease, UUID id, UUID target, long now) { return leases.valid(lease, data, camp, id, actor, target, sheep, now); }
	}
	@Test void uuidClaimsCompeteAndOneActorCannotHoldTwoSheep() {
		Fixture f = new Fixture(); var first = f.claim(ACTOR, SHEEP, 0); assertNotNull(first);
		assertTrue(f.data.shearingPermitsOf(f.camp).isEmpty(), "Lease grants no consent");
		assertFalse(f.leases.available(SHEEP, OTHER, 1)); assertNull(f.claim(OTHER, SHEEP, 1));
		assertNull(f.claim(ACTOR, new UUID(977, 2), 1)); assertSame(first, f.claim(ACTOR, SHEEP, 1));
		f.leases.release(first); assertNotNull(f.claim(OTHER, SHEEP, 1));
		assertNotNull(new Fixture().claim(ACTOR, SHEEP, 0), "Separate dimension registries do not share reservations");
	}
	@Test void repeatedClaimsDoNotRenewAndExpiryIsExclusive() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, SHEEP, 10);
		assertEquals(2400, SettlementShearingLeases.TTL_TICKS);
		assertSame(lease, f.claim(ACTOR, SHEEP, 2409)); assertTrue(f.valid(lease, ACTOR, SHEEP, 2409));
		assertTrue(f.leases.available(SHEEP, OTHER, 2410)); assertFalse(f.valid(lease, ACTOR, SHEEP, 2410));
		var next = f.claim(OTHER, SHEEP, 2410); assertNotNull(next);
		f.leases.release(lease); assertTrue(f.valid(next, OTHER, SHEEP, 2410));
	}
	@Test void rollbackAndOverflowPermanentlyInvalidateOldHandles() {
		Fixture f = new Fixture(); var old = f.claim(ACTOR, SHEEP, 10);
		assertTrue(f.leases.available(SHEEP, ACTOR, 20)); assertFalse(f.leases.available(SHEEP, ACTOR, 19));
		assertNotNull(f.claim(OTHER, SHEEP, 19)); assertFalse(f.valid(old, ACTOR, SHEEP, 20));
		assertNull(f.claim(ACTOR, SHEEP, -1)); assertEquals(0, f.leases.size(20));
		assertNull(f.claim(ACTOR, SHEEP, Long.MAX_VALUE));
		var edge = f.claim(ACTOR, SHEEP, Long.MAX_VALUE - SettlementShearingLeases.TTL_TICKS);
		assertNotNull(edge); assertFalse(f.valid(edge, ACTOR, SHEEP, Long.MAX_VALUE));
	}
	@Test void entityInstanceAndMemberAbaCannotRestoreWork() {
		Fixture f = new Fixture(); var lease = f.claim(ACTOR, SHEEP, 0);
		assertFalse(f.leases.valid(lease, f.data, f.camp, ACTOR, new Object(), SHEEP, f.sheep, 1));
		assertFalse(f.leases.valid(lease, f.data, f.camp, ACTOR, f.actor, SHEEP, new Object(), 1));
		f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0);
		assertFalse(f.valid(lease, ACTOR, SHEEP, 1)); assertNotNull(f.claim(ACTOR, SHEEP, 1));
	}
	@Test void staleReleaseAndForeignRegistryCannotAffectReplacement() {
		Fixture f = new Fixture(); var old = f.claim(ACTOR, SHEEP, 0);
		f.leases.release(old); f.leases.release(old); f.leases.release(null);
		var replacement = f.claim(OTHER, SHEEP, 1);
		f.leases.release(new Fixture().claim(OTHER, SHEEP, 1)); f.leases.release(old);
		assertTrue(f.valid(replacement, OTHER, SHEEP, 1)); assertFalse(f.valid(old, ACTOR, SHEEP, 1));
	}
	@Test void capRejectsWithoutEvictingAndExpiryFreesCapacity() {
		Fixture f = new Fixture(); assertEquals(128, SettlementShearingLeases.MAX_LEASES);
		for (int i = 0; i < 128; i++) {
			UUID actor = new UUID(978, i); f.data.registerMember(f.camp, actor, 0);
			assertNotNull(f.claim(actor, new UUID(979, i), 0));
		}
		assertEquals(128, f.leases.size(1)); assertNull(f.claim(ACTOR, SHEEP, 1));
		assertNotNull(f.claim(ACTOR, SHEEP, 2400)); assertEquals(1, f.leases.size(2400));
	}
	@Test void leasesDoNotDirtyOrSerializeAndReloadCannotReuseTicket() {
		Fixture f = new Fixture(); CompoundTag before = f.data.save(new CompoundTag(), null);
		f.data.setDirty(false); var lease = f.claim(ACTOR, SHEEP, 0);
		assertFalse(f.data.isDirty()); assertEquals(before, f.data.save(new CompoundTag(), null)); assertEquals(27, before.getInt("DataVersion"));
		SettlementSavedData loaded = SettlementSavedData.load(before, null);
		Settlement camp = loaded.settlementById(f.camp.id());
		assertEquals(0, loaded.shearingLeases().size(1));
		assertFalse(loaded.shearingLeases().valid(lease, loaded, camp, ACTOR, f.actor, SHEEP, f.sheep, 1));
	}
}
