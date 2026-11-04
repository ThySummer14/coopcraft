package com.thysummer.coopcraft.probe;

import java.util.Arrays;

import com.google.gson.JsonObject;

/**
 * Development-only in-world frame interval recorder.
 *
 * <p>Records the time between consecutive WorldRenderEvents.END callbacks. This is
 * wall-clock frame pacing (including VSync, CPU stalls and GPU waits), not the
 * duration of a single renderer method. Keep it out of production modpacks.
 */
public final class ProbeFrameTimes {
	private static final int WINDOW_SIZE = 4096;
	private static final long[] recentNanos = new long[WINDOW_SIZE];
	private static long lastFrameNanos;
	private static long observedFrames;
	private static long over50ms;
	private static long over100ms;
	private static int count;
	private static int next;

	private ProbeFrameTimes() {
	}

	/** Called from the rendering thread; synchronized with HTTP snapshots. */
	public static synchronized void record(long nanoTime) {
		if (lastFrameNanos != 0) {
			long elapsed = nanoTime - lastFrameNanos;
			if (elapsed > 0 && elapsed < 30_000_000_000L) {
				recentNanos[next] = elapsed;
				next = (next + 1) % WINDOW_SIZE;
				count = Math.min(WINDOW_SIZE, count + 1);
				observedFrames++;
				if (elapsed >= 50_000_000L) {
					over50ms++;
				}
				if (elapsed >= 100_000_000L) {
					over100ms++;
				}
			}
		}
		lastFrameNanos = nanoTime;
	}

	public static synchronized JsonObject reset() {
		Arrays.fill(recentNanos, 0);
		lastFrameNanos = 0;
		observedFrames = 0;
		over50ms = 0;
		over100ms = 0;
		count = 0;
		next = 0;
		return ProbeJson.ok();
	}

	public static synchronized JsonObject snapshot() {
		JsonObject result = ProbeJson.ok();
		result.addProperty("kind", "world-render-end-frame-interval");
		result.addProperty("available", count > 0);
		result.addProperty("windowFrames", count);
		result.addProperty("totalFrames", observedFrames);
		result.addProperty("totalOver50ms", over50ms);
		result.addProperty("totalOver100ms", over100ms);
		if (count == 0) {
			return result;
		}

		long[] samples = Arrays.copyOf(recentNanos, count);
		Arrays.sort(samples);
		double sum = 0;
		for (long nanos : samples) {
			sum += nanos;
		}
		result.addProperty("p50Ms", percentile(samples, 0.50) / 1_000_000.0);
		result.addProperty("p95Ms", percentile(samples, 0.95) / 1_000_000.0);
		result.addProperty("p99Ms", percentile(samples, 0.99) / 1_000_000.0);
		result.addProperty("maxMs", samples[count - 1] / 1_000_000.0);
		result.addProperty("meanFps", count * 1_000_000_000.0 / sum);

		// 1% low FPS = reciprocal of average frame time of the slowest 1%.
		int worst = Math.max(1, (int) Math.ceil(count * 0.01));
		double worstNanos = 0;
		for (int i = count - worst; i < count; i++) {
			worstNanos += samples[i];
		}
		result.addProperty("onePercentLowFps", worst * 1_000_000_000.0 / worstNanos);
		return result;
	}

	private static long percentile(long[] sorted, double p) {
		int index = Math.max(0, (int) Math.ceil(sorted.length * p) - 1);
		return sorted[index];
	}
}
