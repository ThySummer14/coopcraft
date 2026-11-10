package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementChildCareClaimsTest {
	private static UUID id(long value) { return new UUID(0, value); }

	@Test void competingGiversAndEitherRoleAreExclusive() {
		var claims = new SettlementChildCareClaims();
		var lease = claims.claim(id(1), id(2), 10);
		assertNotNull(lease);
		assertEquals(id(1), lease.giver()); assertEquals(id(2), lease.recipient());
		assertNull(claims.claim(id(3), id(2), 10));
		assertNull(claims.claim(id(2), id(3), 10));
		assertNull(claims.claim(id(3), id(1), 10));
		assertNull(claims.claim(id(1), id(3), 10));
		assertNull(claims.claim(id(1), id(2), 10));
		assertTrue(claims.active(lease, 10));
	}

	@Test void expiryReleasesBothButKeepsShortCooldown() {
		var claims = new SettlementChildCareClaims();
		var lease = claims.claim(id(1), id(2), 0);
		assertTrue(claims.active(lease, 399));
		assertFalse(claims.active(lease, 400));
		assertFalse(claims.available(id(1), 499));
		assertFalse(claims.available(id(2), 499));
		assertTrue(claims.available(id(1), 500));
		assertTrue(claims.available(id(2), 500));
	}

	@Test void releaseAndCancellationCoolBothAndStaleReleaseCannotCancelReplacement() {
		var claims = new SettlementChildCareClaims();
		var old = claims.claim(id(1), id(2), 0);
		claims.release(old, 40);
		assertNull(claims.claim(id(3), id(2), 139));
		var replacement = claims.claim(id(1), id(2), 140);
		assertNotNull(replacement);
		claims.release(old, 141);
		assertTrue(claims.active(replacement, 141));
		claims.cancelResident(id(2));
		assertFalse(claims.active(replacement, 141));
		assertFalse(claims.available(id(1), 240));
		assertTrue(claims.available(id(1), 241));
		assertTrue(claims.available(id(2), 241));
	}

	@Test void foreignTokenNullAndSelfCannotInterfere() {
		var a = new SettlementChildCareClaims(); var b = new SettlementChildCareClaims();
		var first = a.claim(id(1), id(2), 0);
		var second = b.claim(id(1), id(2), 0);
		a.release(second, 1); a.release(null, 1); a.cancelResident(null);
		assertTrue(a.active(first, 1)); assertFalse(a.active(second, 1));
		assertNull(a.claim(null, id(3), 1)); assertNull(a.claim(id(3), null, 1));
		assertNull(a.claim(id(3), id(3), 1)); assertFalse(a.available(null, 1));
	}

	@Test void clockReversalAndOverflowInvalidateOldTokensAndAllowSafeRestart() {
		var claims = new SettlementChildCareClaims();
		var old = claims.claim(id(1), id(2), 20);
		assertFalse(claims.active(old, 19));
		var replacement = claims.claim(id(1), id(2), 19);
		assertNotNull(replacement);
		assertFalse(claims.active(replacement, Long.MAX_VALUE));
		assertNull(claims.claim(id(1), id(2), -1));
		long edge = Long.MAX_VALUE - SettlementChildCareClaims.TTL - SettlementChildCareClaims.RETRY;
		var edgeLease = claims.claim(id(1), id(2), edge);
		assertNotNull(edgeLease);
		claims.cancelResident(id(1));
		assertFalse(claims.active(edgeLease, edge));
		assertNull(claims.claim(id(1), id(2), edge + 1));
		assertNotNull(claims.claim(id(1), id(2), 0));
	}

	@Test void capNeverEvictsActiveClaimsOrTheirCooldowns() {
		var claims = new SettlementChildCareClaims();
		for (int i = 0; i < SettlementChildCareClaims.LIMIT; i++)
			assertNotNull(claims.claim(id(i * 2), id(i * 2 + 1), 0));
		assertNull(claims.claim(id(1000), id(1001), 0));
		for (int i = 0; i < SettlementChildCareClaims.LIMIT; i++) claims.cancelResident(id(i * 2));
		assertNull(claims.claim(id(1000), id(1001), 99));
		assertFalse(claims.available(id(0), 99));
		assertNotNull(claims.claim(id(1000), id(1001), 100));
	}

	@Test void savedDataClaimsAreTransientCleanAndCancelOnMembershipRemoval() {
		var data = new SettlementSavedData();
		var before = data.save(new CompoundTag(), null);
		var lease = data.childCareClaims().claim(id(1), id(2), 0);
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
		var restored = SettlementSavedData.load(before, null);
		assertFalse(restored.childCareClaims().active(lease, 0));
		assertNotNull(restored.childCareClaims().claim(id(1), id(2), 0));
		data.removeMember(id(2));
		assertFalse(data.childCareClaims().active(lease, 0));
		assertFalse(data.isDirty());
		assertFalse(data.childCareClaims().available(id(1), 99));
		assertTrue(data.childCareClaims().available(id(1), 100));
	}
}
