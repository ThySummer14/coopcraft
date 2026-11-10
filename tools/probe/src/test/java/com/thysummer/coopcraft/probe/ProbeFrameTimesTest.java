package com.thysummer.coopcraft.probe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProbeFrameTimesTest {
	@BeforeEach
	void reset() {
		ProbeFrameTimes.reset();
	}

	@Test
	void retainsLongFramesInsteadOfDiscardingTheWorstStalls() {
		ProbeFrameTimes.record(1_000_000_000L);
		ProbeFrameTimes.record(31_000_000_000L);
		ProbeFrameTimes.record(32_000_000_000L);

		var snapshot = ProbeFrameTimes.snapshot();
		assertEquals(2, snapshot.get("totalFrames").getAsInt());
		assertEquals(2, snapshot.get("totalOver100ms").getAsInt());
		assertEquals(30_000.0, snapshot.get("maxMs").getAsDouble());
		assertEquals(2, snapshot.get("windowFrames").getAsInt());
	}

	@Test
	void ignoresOnlyNonPositiveIntervals() {
		ProbeFrameTimes.record(10_000_000_000L);
		ProbeFrameTimes.record(10_000_000_000L);
		ProbeFrameTimes.record(9_000_000_000L);

		assertEquals(0, ProbeFrameTimes.snapshot().get("totalFrames").getAsInt());
	}
}
