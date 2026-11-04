"""Offline tests for the dev-only diagnostic collector; no Minecraft instance needed."""
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import collect_perf  # noqa: E402


class PerfCollectorTests(unittest.TestCase):
    def test_summary_uses_frame_percentiles_not_fps_average(self):
        rows = [
            {"elapsedSec": 0, "fps": 100, "serverTick": 10,
             "frame": {"available": True, "p99Ms": 30, "onePercentLowFps": 25,
                       "totalOver50ms": 2, "totalOver100ms": 1}},
            {"elapsedSec": 2, "fps": 50, "serverTick": 50,
             "frame": {"available": True, "p99Ms": 80, "onePercentLowFps": 10,
                       "totalOver50ms": 5, "totalOver100ms": 2}},
        ]
        result = collect_perf.summarize(rows)
        self.assertEqual(result["samples"], 2)
        self.assertEqual(result["fpsMin"], 50)
        self.assertEqual(result["fpsMean"], 75)
        self.assertEqual(result["worstWindowP99FrameMs"], 80)
        self.assertEqual(result["lowestWindow1PercentLowFps"], 10)
        self.assertEqual(result["observedOver50ms"], 5)
        self.assertEqual(result["observedOver100ms"], 2)
        self.assertEqual(result["approxServerTicksPerSec"], 20)
        self.assertNotIn("networkRttMs", result)

    def test_empty_samples_do_not_invent_metrics(self):
        result = collect_perf.summarize([{"elapsedSec": 0, "clientError": "TimeoutError"}])
        self.assertEqual(result["clientErrors"], 1)
        self.assertIsNone(result["fpsMin"])
        self.assertIsNone(result["worstWindowP99FrameMs"])
        self.assertIsNone(result["approxServerTicksPerSec"])

    def test_sample_does_not_persist_player_or_ip(self):
        responses = {
            "/status": {"ok": True, "fps": 58, "connected": True,
                        "player": "NotForLogs", "playerNames": ["NotForLogs"],
                        "serverAddress": "100.77.12.22"},
            "/perf/frame": {"ok": True, "available": True, "p99Ms": 74,
                            "totalOver50ms": 4, "private": "secret"},
        }
        with patch.object(collect_perf, "read_probe",
                          side_effect=lambda port, token, endpoint, timeout: responses[endpoint]):
            row = collect_perf.sample_once(39002, 39001, "sensitive-token", False, 3)
        self.assertEqual(row["fps"], 58)
        self.assertEqual(row["frame"]["p99Ms"], 74)
        self.assertNotIn("player", str(row))
        self.assertNotIn("NotForLogs", str(row))
        self.assertNotIn("100.77.12.22", str(row))
        self.assertNotIn("secret", str(row))
        self.assertNotIn("sensitive-token", str(row))

    def test_server_tick_requires_elapsed_time(self):
        rows = [{"elapsedSec": 1, "serverTick": 42}, {"elapsedSec": 1, "serverTick": 45}]
        self.assertIsNone(collect_perf.summarize(rows)["approxServerTicksPerSec"])

    def test_server_error_preserves_client_stats(self):
        with patch.object(collect_perf, "read_probe", side_effect=[
            {"ok": True, "fps": 60, "connected": True},
            {"ok": True, "available": False},
            TimeoutError(),
        ]):
            row = collect_perf.sample_once(39002, 39001, "coopcraft", True, 2)
        self.assertEqual(row["fps"], 60)
        self.assertEqual(row["serverError"], "TimeoutError")


if __name__ == "__main__":
    unittest.main()
