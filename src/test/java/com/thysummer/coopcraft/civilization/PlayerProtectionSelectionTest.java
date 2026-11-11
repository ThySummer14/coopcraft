package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.PlayerProtectionSelection.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure production state-machine tests. No live player, network, server or game is exercised. */
class PlayerProtectionSelectionTest {
	private static final UUID OWNER = new UUID(1, 2);
	private static final PlayerProtectionSelection.Actor PLAYER = actor(OWNER, "minecraft:overworld", true);
	private static final PlayerProtectionSelection.Boundary BORDER = (x, z) -> Math.abs((long) x) <= 1_000 && Math.abs((long) z) <= 1_000;

	private static PlayerProtectionSelection.Actor actor(UUID id, String dimension, boolean eligible) {
		return new PlayerProtectionSelection.Actor(id, dimension, eligible);
	}
	private static PlayerProtectionSelection.Result click(PlayerProtectionSelection sessions, int x, int z, long tick) {
		return sessions.click(PLAYER, tick, true, true, true, x, z, BORDER);
	}
	private static PlayerProtectionSelection.Writer writer(AtomicInteger writes) {
		return (owner, name, bounds) -> {
			writes.incrementAndGet();
			return new PlayerProtectedAreas.AddResult(PlayerProtectedAreas.Status.ADDED,
				new PlayerProtectedAreas.Area(UUID.randomUUID(), owner, name,
					bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ()));
		};
	}
	private static PlayerProtectionSelection ready(long start) {
		var sessions = new PlayerProtectionSelection();
		assertEquals(STARTED, sessions.start(PLAYER, " Home ", start).status());
		assertEquals(FIRST_CORNER, click(sessions, 10, 20, start + 1).status());
		assertEquals(PREVIEW, click(sessions, -4, -5, start + 2).status());
		return sessions;
	}

	@Test void reversedCornersOnlyPreviewUntilConfirmAndCannotWriteTwice() {
		var writes = new AtomicInteger();
		var sessions = ready(100);
		var preview = sessions.inspect(PLAYER, 103);
		assertEquals("Home", preview.draft().name());
		assertEquals(new PlayerProtectionSelection.Bounds(-4, -5, 10, 20), preview.draft().bounds());
		assertEquals(15, preview.draft().bounds().width());
		assertEquals(26, preview.draft().bounds().depth());
		assertEquals(0, writes.get());
		var saved = sessions.confirm(PLAYER, 104, BORDER, writer(writes));
		assertEquals(CONFIRMED, saved.selection().status());
		assertEquals(OWNER, saved.saved().area().ownerId());
		assertEquals(1, writes.get());
		assertEquals(0, sessions.size());
		assertEquals(NO_SELECTION, sessions.confirm(PLAYER, 105, BORDER, writer(writes)).selection().status());
		assertEquals(1, writes.get());
	}

	@Test void aLaterClickCannotChangeAnAlreadyPreviewedRange() {
		var sessions = ready(0);
		var bounds = sessions.inspect(PLAYER, 3).draft().bounds();
		assertEquals(PREVIEW, click(sessions, 700, 700, 4).status());
		assertEquals(bounds, sessions.inspect(PLAYER, 5).draft().bounds());
	}

	@Test void rejectsOversizedAndOutOfBoundsCornersButRetainsFirstCornerForCorrection() {
		var sessions = new PlayerProtectionSelection();
		sessions.start(PLAYER, "Home", 0);
		assertEquals(OUT_OF_BOUNDS, click(sessions, 1_001, 0, 1).status());
		assertNull(sessions.inspect(PLAYER, 1).draft().first());
		assertEquals(FIRST_CORNER, click(sessions, 0, 0, 2).status());
		assertEquals(TOO_LARGE, click(sessions, 128, 0, 3).status());
		assertEquals(TOO_LARGE, click(sessions, 0, -128, 4).status());
		assertNull(sessions.inspect(PLAYER, 4).draft().second());
		assertEquals(OUT_OF_BOUNDS, sessions.click(PLAYER, 5, true, true, true,
			Integer.MAX_VALUE, Integer.MIN_VALUE, (x, z) -> true).status());
		assertEquals(PREVIEW, click(sessions, 127, -127, 6).status());
		assertEquals(128, sessions.inspect(PLAYER, 6).draft().bounds().width());
		assertEquals(128, sessions.inspect(PLAYER, 6).draft().bounds().depth());
	}

	@Test void invalidGesturesAndOffhandAreConsumedWithoutSelectingOrFallingThroughToOldMarking() {
		var sessions = new PlayerProtectionSelection();
		assertFalse(click(sessions, 1, 1, 0).consumesClick(), "Players not selecting retain ordinary interaction");
		sessions.start(PLAYER, "Home", 0);
		var oldMarkCalls = new AtomicInteger();
		for (boolean[] flags : new boolean[][] {{false, true, true}, {true, false, true}, {true, true, false}}) {
			var result = sessions.click(PLAYER, 1, flags[0], flags[1], flags[2], 2, 2, BORDER);
			if (!result.consumesClick()) oldMarkCalls.incrementAndGet();
			assertEquals(GUIDANCE, result.status());
			assertNull(result.draft().first());
		}
		assertEquals(0, oldMarkCalls.get());
		var first = click(sessions, 3, 3, 2);
		assertTrue(first.consumesClick());
		assertEquals(FIRST_CORNER, first.status());
		var offhand = sessions.click(PLAYER, 2, false, true, true, 4, 4, BORDER);
		assertTrue(offhand.consumesClick());
		assertNull(offhand.draft().second());
		assertTrue(click(sessions, 4, 4, 3).consumesClick());
		assertTrue(click(sessions, 4, 4, 6_000).consumesClick(), "Expiry during this click must not reach old marking");
	}

	@Test void deadlineIsFixedAtFiveMinutesAndResistsTimeReversalOrOverflow() {
		var sessions = ready(10);
		assertEquals(PREVIEW, click(sessions, 10, 10, 6_009).status());
		assertEquals(EXPIRED, sessions.inspect(PLAYER, 6_010).status());
		assertEquals(0, sessions.size());
		sessions.start(PLAYER, "Home", 10);
		assertEquals(EXPIRED, sessions.inspect(PLAYER, 9).status());
		sessions.start(PLAYER, "Home", Long.MAX_VALUE - 10);
		assertEquals(NEED_CORNERS, sessions.inspect(PLAYER, Long.MAX_VALUE).status());
		assertEquals(EXPIRED, sessions.inspect(PLAYER, Long.MIN_VALUE).status());
	}

	@Test void confirmingRechecksDimensionRoleAndChangedBorderAndNeverCallsWriterWhenInvalid() {
		var writes = new AtomicInteger();
		var sessions = ready(0);
		assertEquals(DIMENSION_CHANGED, sessions.confirm(actor(OWNER, "minecraft:the_nether", true), 3,
			BORDER, writer(writes)).selection().status());
		assertEquals(NO_SELECTION, sessions.inspect(PLAYER, 4).status());
		sessions = ready(0);
		assertEquals(PLAYER_UNAVAILABLE, sessions.confirm(actor(OWNER, PLAYER.dimension(), false), 3,
			BORDER, writer(writes)).selection().status());
		sessions = ready(0);
		assertEquals(OUT_OF_BOUNDS, sessions.confirm(PLAYER, 3, (x, z) -> x >= 0, writer(writes)).selection().status());
		assertEquals(NO_SELECTION, sessions.inspect(PLAYER, 4).status());
		assertEquals(0, writes.get());
	}

	@Test void confirmationNeedsBothCornersAndDoesNotConsumeIncompleteSelection() {
		var writes = new AtomicInteger();
		var sessions = new PlayerProtectionSelection();
		sessions.start(PLAYER, "Home", 0);
		assertEquals(NEED_CORNERS, sessions.confirm(PLAYER, 1, BORDER, writer(writes)).selection().status());
		click(sessions, 1, 1, 2);
		assertEquals(NEED_CORNERS, sessions.confirm(PLAYER, 3, BORDER, writer(writes)).selection().status());
		assertEquals(1, sessions.size());
		assertEquals(0, writes.get());
	}

	@Test void persistenceRejectionOrExceptionCannotReplayAnUncertainWrite() {
		var writes = new AtomicInteger();
		var rejected = ready(0);
		var result = rejected.confirm(PLAYER, 3, BORDER, (owner, name, bounds) -> {
			writes.incrementAndGet();
			return new PlayerProtectedAreas.AddResult(PlayerProtectedAreas.Status.OWNER_LIMIT, null);
		});
		assertEquals(SAVE_REJECTED, result.selection().status());
		assertEquals(PlayerProtectedAreas.Status.OWNER_LIMIT, result.saved().status());
		assertEquals(NO_SELECTION, rejected.confirm(PLAYER, 4, BORDER, writer(writes)).selection().status());
		var uncertain = ready(0);
		assertThrows(IllegalStateException.class, () -> uncertain.confirm(PLAYER, 3, BORDER, (owner, name, bounds) -> {
			writes.incrementAndGet();
			throw new IllegalStateException("Failure after write");
		}));
		assertEquals(NO_SELECTION, uncertain.confirm(PLAYER, 4, BORDER, writer(writes)).selection().status());
		assertEquals(2, writes.get());
	}

	@Test void cancelDisconnectDimensionChangeAndRoleLossReleaseCapacity() {
		var sessions = ready(0);
		assertEquals(CANCELLED, sessions.cancel(OWNER, 3).status());
		assertEquals(NO_SELECTION, sessions.cancel(OWNER, 3).status());
		assertEquals(0, sessions.size());
		for (var current : new PlayerProtectionSelection.Actor[] {null,
			actor(OWNER, "minecraft:the_end", true), actor(OWNER, PLAYER.dimension(), false),
			actor(UUID.randomUUID(), PLAYER.dimension(), true)}) {
			sessions.start(PLAYER, "Home", 0);
			var closed = sessions.prune(1, id -> current);
			assertEquals(1, closed.size());
			assertEquals(OWNER, closed.getFirst().playerId());
			assertEquals(0, sessions.size());
		}
	}

	@Test void sessionsAreIndependentBoundedAndExpiredEntriesDoNotBlockNewStarts() {
		var sessions = new PlayerProtectionSelection();
		for (int i = 0; i < PlayerProtectionSelection.MAX_SESSIONS; i++) {
			assertEquals(STARTED, sessions.start(actor(new UUID(0, i), PLAYER.dimension(), true), "Home", 0).status());
		}
		assertEquals(SESSION_LIMIT, sessions.start(PLAYER, "Home", 1).status());
		var existing = actor(new UUID(0, 0), PLAYER.dimension(), true);
		assertEquals(STARTED, sessions.start(existing, "Replacement", 2).status());
		assertEquals(256, sessions.size());
		sessions.forget(existing.id());
		assertEquals(STARTED, sessions.start(PLAYER, "Home", 3).status());
		assertEquals(NO_SELECTION, sessions.inspect(existing, 4).status());
		assertEquals(SESSION_LIMIT, sessions.start(actor(new UUID(4, 5), PLAYER.dimension(), true), "New", 6_000).status());
		assertEquals(1, sessions.size());
		assertEquals(256, sessions.trackedCount());
		assertEquals(STARTED, sessions.start(actor(new UUID(4, 5), PLAYER.dimension(), true), "New", 6_020).status());
		assertEquals(1, sessions.size());
	}

	@Test void closingDraftBlocksTrailingOffhandAndExpiresWithoutAffectingUnrelatedPlayers() {
		var sessions = ready(0);
		assertEquals(EXPIRED, click(sessions, 4, 4, 6_000).status());
		assertEquals(CLOSED_GESTURE, sessions.click(PLAYER, 6_000, false, true, true, 4, 4, BORDER).status());
		assertTrue(sessions.click(PLAYER, 6_019, false, true, true, 4, 4, BORDER).consumesClick());
		assertFalse(sessions.click(actor(new UUID(9, 9), PLAYER.dimension(), true), 6_001,
			false, true, true, 4, 4, BORDER).consumesClick());
		assertFalse(sessions.click(PLAYER, 6_020, false, true, true, 4, 4, BORDER).consumesClick());
		assertEquals(0, sessions.trackedCount());
		for (boolean confirm : new boolean[] {false, true}) {
			sessions = ready(0);
			if (confirm) sessions.confirm(PLAYER, 3, BORDER, writer(new AtomicInteger()));
			else sessions.cancel(OWNER, 3);
			assertEquals(CLOSED_GESTURE, sessions.click(PLAYER, 3, false, true, true, 3, 3, BORDER).status());
			assertEquals(CLOSED_GESTURE, sessions.click(PLAYER, 4, true, true, true, 3, 3, BORDER).status());
			assertEquals(1, sessions.trackedCount());
			sessions.forget(OWNER);
			assertEquals(0, sessions.trackedCount());
		}
	}

	@Test void lifecycleExpiryAndDimensionChangeAlsoProtectTheNextPacket() {
		for (boolean changedDimension : new boolean[] {false, true}) {
			var sessions = ready(0);
			var current = changedDimension ? actor(OWNER, "minecraft:the_end", true) : PLAYER;
			long tick = changedDimension ? 3 : 6_000;
			assertEquals(1, sessions.prune(tick, id -> current).size());
			assertEquals(CLOSED_GESTURE, sessions.click(current, tick, false, true, true, 3, 3, BORDER).status());
			assertEquals(0, sessions.size());
			assertEquals(1, sessions.trackedCount());
		}
	}

	@Test void namesAndPlayerIdentityAreValidatedBeforeCreatingSelections() {
		var sessions = new PlayerProtectionSelection();
		assertEquals(PLAYER_UNAVAILABLE, sessions.start(null, "Home", 0).status());
		assertEquals(PLAYER_UNAVAILABLE, sessions.start(actor(null, PLAYER.dimension(), true), "Home", 0).status());
		assertEquals(PLAYER_UNAVAILABLE, sessions.start(actor(OWNER, null, true), "Home", 0).status());
		for (String invalid : new String[] {null, "", "  ", "a".repeat(49), "bad\nname"}) {
			assertEquals(INVALID_NAME, sessions.start(PLAYER, invalid, 0).status());
		}
		assertEquals(0, sessions.size());
		assertEquals(STARTED, sessions.start(PLAYER, "a".repeat(48), 0).status());
		assertEquals(PLAYER_UNAVAILABLE, sessions.start(actor(OWNER, PLAYER.dimension(), false), "Home", 1).status());
		assertEquals(0, sessions.size());
	}
}
