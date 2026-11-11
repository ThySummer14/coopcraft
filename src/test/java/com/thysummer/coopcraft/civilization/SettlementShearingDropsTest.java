package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Capture scope state only. Does not instantiate a world or claim that Mixin ran. */
class SettlementShearingDropsTest {
	@Test void scopeRejectsNestedCaptureAndAlwaysClearsThreadLocal() {
		Object sheep = new Object();
		try (var scope = SettlementShearingDrops.beginIdentity(sheep)) {
			assertNotNull(scope); assertNull(SettlementShearingDrops.beginIdentity(sheep));
			SettlementShearingDrops.enter(sheep); SettlementShearingDrops.exit(sheep, true);
			assertTrue(scope.complete()); assertTrue(scope.drops().isEmpty(), "No real spawned entities means no output");
		}
		try (var next = SettlementShearingDrops.beginIdentity(sheep)) { assertNotNull(next); assertFalse(next.complete()); }
	}
	@Test void recursiveSameSheepAndOtherSheepInvalidateWholeCapture() {
		for (boolean same : new boolean[] {true, false}) {
			Object sheep = new Object(), nested = same ? sheep : new Object();
			try (var scope = SettlementShearingDrops.beginIdentity(sheep)) {
				SettlementShearingDrops.enter(sheep); SettlementShearingDrops.enter(nested);
				SettlementShearingDrops.exit(nested, true); SettlementShearingDrops.exit(sheep, true);
				assertFalse(scope.complete()); assertTrue(scope.drops().isEmpty());
			}
		}
	}
	@Test void exceptionalAndRepeatedInvocationCannotProvideAReplayableCapture() {
		Object sheep = new Object();
		try (var scope = SettlementShearingDrops.beginIdentity(sheep)) {
			SettlementShearingDrops.enter(sheep); SettlementShearingDrops.exit(sheep, false);
			assertFalse(scope.complete());
		}
		try (var scope = SettlementShearingDrops.beginIdentity(sheep)) {
			SettlementShearingDrops.enter(sheep); SettlementShearingDrops.exit(sheep, true);
			SettlementShearingDrops.enter(sheep); SettlementShearingDrops.exit(sheep, true);
			assertFalse(scope.complete());
		}
		assertThrows(IllegalStateException.class, () -> {
			try (var ignored = SettlementShearingDrops.beginIdentity(sheep)) { throw new IllegalStateException("Failure"); }
		});
		try (var next = SettlementShearingDrops.beginIdentity(sheep)) { assertNotNull(next); }
	}
}
