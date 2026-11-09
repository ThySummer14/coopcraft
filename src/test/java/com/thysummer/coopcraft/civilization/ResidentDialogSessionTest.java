package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.civilization.ResidentDialogSession.Request.*;

/** Pure production session/registry tests. No actual player, world, transport or Mixin is executed. */
class ResidentDialogSessionTest {
	@Test void claimsBeforeEffectsAndCannotReplayAfterAnException() {
		var session = new ResidentDialogSession<>(new Object(), 10);
		var effects = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> {
			if (session.claim(1, 11) == NEXT) {
				effects.incrementAndGet();
				throw new IllegalStateException("Effect failed after mutation");
			}
		});
		assertEquals(DUPLICATE, session.claim(1, 12));
		assertEquals(1, effects.get());
		assertEquals(1, session.acknowledgedRequest());
		assertEquals(GAP, session.claim(3, 13));
		assertEquals(1, session.acknowledgedRequest());
		assertEquals(NEXT, session.claim(2, 14));
		assertEquals(GAP, session.claim(0, 14));
	}

	@Test void expirationIsFixedAgainstDuplicateTrafficAndTimeReversal() {
		var session = new ResidentDialogSession<>(new Object(), 10);
		assertTrue(session.alive(2409));
		assertFalse(session.alive(2410));
		assertFalse(session.alive(9));
		assertEquals(NEXT, session.claim(1, 100));
		assertEquals(DUPLICATE, session.claim(1, 2499));
		assertEquals(CLOSED, session.claim(2, 2500));
		assertEquals(10, session.createdTick);
		var nearOverflow = new ResidentDialogSession<>(new Object(), Long.MAX_VALUE - 10);
		assertTrue(nearOverflow.alive(Long.MAX_VALUE));
		assertFalse(nearOverflow.alive(Long.MIN_VALUE));
	}

	@Test void closeInvalidatesTokenAndReleasesObservedTicketWithoutCancellingIt() {
		var session = new ResidentDialogSession<>(new Object(), 0);
		Object ticket = new Object();
		session.observeInvitation(ticket);
		assertSame(ticket, session.observedInvitation());
		assertTrue(session.matchesToken(session.token));
		assertFalse(session.matchesToken(UUID.randomUUID()));
		session.close();
		assertFalse(session.matchesToken(session.token));
		assertEquals(CLOSED, session.claim(1, 0));
		assertNull(session.observedInvitation());
	}

	@Test void playersHaveIndependentSequencesAndOldRemovalCannotRemoveReentrantReplacement() {
		var registry = new ResidentDialogSession.Registry<Object>();
		UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
		var old = new ResidentDialogSession<>(new Object(), 0);
		var second = new ResidentDialogSession<>(new Object(), 0);
		assertTrue(registry.add(alice, old));
		assertTrue(registry.add(bob, second));
		assertEquals(NEXT, old.claim(1, 1));
		assertEquals(0, second.acknowledgedRequest());
		assertTrue(registry.remove(alice, old));
		var replacement = new ResidentDialogSession<>(new Object(), 2);
		assertTrue(registry.add(alice, replacement));
		assertFalse(registry.remove(alice, old));
		assertFalse(registry.add(alice, old));
		assertSame(replacement, registry.get(alice));
		assertSame(second, registry.get(bob));
		assertFalse(replacement.matchesToken(old.token));
		for (var session : registry.all()) {
			registry.remove(session == replacement ? alice : bob, session);
		}
		assertTrue(registry.all().isEmpty());
	}

	@Test void registryHasAHardBoundAndReleasesCapacity() {
		var registry = new ResidentDialogSession.Registry<Object>();
		UUID first = new UUID(0, 0);
		for (int i = 0; i < ResidentDialogSession.MAX_SESSIONS; i++) {
			assertTrue(registry.add(new UUID(0, i), new ResidentDialogSession<>(new Object(), 0)));
		}
		assertFalse(registry.add(UUID.randomUUID(), new ResidentDialogSession<>(new Object(), 0)));
		assertTrue(registry.remove(first, registry.get(first)));
		assertTrue(registry.add(UUID.randomUUID(), new ResidentDialogSession<>(new Object(), 0)));
	}

	@Test void duplicateRequestDoesNotRefreshSnapshotTicketOrRevision() {
		var session = new ResidentDialogSession<>(new Object(), 0);
		Object shown = new Object();
		session.observeInvitation(shown);
		assertEquals(0, session.revision());
		assertEquals(NEXT, session.claim(1, 1));
		session.nextRevision();
		assertEquals(DUPLICATE, session.claim(1, 2));
		assertSame(shown, session.observedInvitation());
		assertEquals(1, session.revision());
	}
}
