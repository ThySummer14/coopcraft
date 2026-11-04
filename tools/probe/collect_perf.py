#!/usr/bin/env python3
"""Collect dev-only CoopCraft frame pacing and tick snapshots from localhost probes.

No remote addresses, player names or tokens are persisted. The client probe must
run in a real OpenGL window, and frame intervals require an active world view.
"""
from __future__ import annotations

import argparse
import json
import math
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def read_probe(port: int, token: str, endpoint: str, timeout: float = 3.0,
               post: bool = False) -> dict[str, Any]:
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{endpoint}",
        data=b"{}" if post else None,
        method="POST" if post else "GET",
        headers={"X-Probe-Token": token, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        result = json.load(response)
    if not isinstance(result, dict) or not result.get("ok", False):
        raise ValueError(f"probe endpoint {endpoint} returned an error")
    return result


def sample_once(client_port: int, server_port: int, token: str,
                with_server: bool, timeout: float) -> dict[str, Any]:
    """Return only a privacy-safe subset, not raw /status JSON."""
    row: dict[str, Any] = {"elapsedSec": 0.0}
    try:
        started = time.monotonic()
        status = read_probe(client_port, token, "/status", timeout)
        frame = read_probe(client_port, token, "/perf/frame", timeout)
        row["clientProbeMs"] = round((time.monotonic() - started) * 1000, 2)
        row["fps"] = status.get("fps")
        row["inWorld"] = bool(status.get("connected", False))
        row["frame"] = {
            key: frame.get(key) for key in (
                "available", "windowFrames", "totalFrames", "p50Ms",
                "p95Ms", "p99Ms", "maxMs", "meanFps",
                "onePercentLowFps", "totalOver50ms", "totalOver100ms",
            ) if key in frame
        }
    except (OSError, ValueError, TimeoutError) as error:
        row["clientError"] = error.__class__.__name__
    if with_server:
        try:
            started = time.monotonic()
            server = read_probe(server_port, token, "/status", timeout)
            row["serverProbeMs"] = round((time.monotonic() - started) * 1000, 2)
            row["serverTick"] = server.get("tick")
        except (OSError, ValueError, TimeoutError) as error:
            row["serverError"] = error.__class__.__name__
    return row


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate observed local-probe measurements; never claim network RTT."""
    fps = [float(row["fps"]) for row in rows if
           isinstance(row.get("fps"), (int, float)) and row["fps"] > 0]
    windows = [row.get("frame", {}) for row in rows]
    p99 = [float(frame["p99Ms"]) for frame in windows if isinstance(frame.get("p99Ms"), (int, float))]
    one_low = [float(frame["onePercentLowFps"]) for frame in windows
               if isinstance(frame.get("onePercentLowFps"), (int, float))]
    totals = [frame for frame in windows if frame.get("available")]
    summary: dict[str, Any] = {
        "samples": len(rows),
        "clientErrors": sum("clientError" in row for row in rows),
        "serverErrors": sum("serverError" in row for row in rows),
        "fpsMin": round(min(fps), 2) if fps else None,
        "fpsMean": round(sum(fps) / len(fps), 2) if fps else None,
        "worstWindowP99FrameMs": round(max(p99), 3) if p99 else None,
        "lowestWindow1PercentLowFps": round(min(one_low), 2) if one_low else None,
        "observedOver50ms": max((int(f.get("totalOver50ms") or 0) for f in totals), default=None),
        "observedOver100ms": max((int(f.get("totalOver100ms") or 0) for f in totals), default=None),
    }
    tick_rows = [row for row in rows if isinstance(row.get("serverTick"), int)]
    if len(tick_rows) >= 2:
        elapsed = tick_rows[-1]["elapsedSec"] - tick_rows[0]["elapsedSec"]
        delta_tick = tick_rows[-1]["serverTick"] - tick_rows[0]["serverTick"]
        summary["approxServerTicksPerSec"] = (
            round(delta_tick / elapsed, 2) if elapsed > 0 and delta_tick >= 0 else None
        )
    else:
        summary["approxServerTicksPerSec"] = None
    return summary


def collect(duration: float, interval: float, client_port: int,
            server_port: int, token: str, with_server: bool,
            timeout: float) -> list[dict[str, Any]]:
    read_probe(client_port, token, "/perf/reset", timeout, post=True)
    started = time.monotonic()
    deadline = started + duration
    rows = []
    next_sample = started
    while time.monotonic() < deadline or not rows:
        now = time.monotonic()
        if now < next_sample:
            time.sleep(next_sample - now)
        row = sample_once(client_port, server_port, token, with_server, timeout)
        row["elapsedSec"] = round(time.monotonic() - started, 3)
        rows.append(row)
        next_sample += interval
        if next_sample < time.monotonic():
            next_sample = time.monotonic()
        if time.monotonic() >= deadline:
            break
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect local dev probe frame pacing and tick samples")
    parser.add_argument("--duration", type=float, default=60.0)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--client-port", type=int, default=39002)
    parser.add_argument("--server-port", type=int, default=39001)
    parser.add_argument("--token", default="coopcraft")
    parser.add_argument("--with-server", action="store_true")
    parser.add_argument("--scenario", default="dev-baseline")
    parser.add_argument("--output", default="build/perf/dev-baseline.json")
    parser.add_argument("--timeout", type=float, default=3.0)
    args = parser.parse_args()
    if not (0 < args.duration <= 900 and 0.2 <= args.interval <= 30 and 0.2 <= args.timeout <= 30):
        parser.error("duration: (0,900], interval: [0.2,30], timeout: [0.2,30]")
    if not all(0 < port <= 65535 for port in (args.client_port, args.server_port)):
        parser.error("invalid port")
    try:
        rows = collect(args.duration, args.interval, args.client_port,
                       args.server_port, args.token, args.with_server, args.timeout)
    except (OSError, ValueError, TimeoutError) as error:
        print(f"Probe unavailable: {type(error).__name__}; start the dev-only client and enter a world.")
        return 2
    result = {
        "scenario": args.scenario,
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "measurement": "in-world frame interval, not rendering CPU time or network RTT",
        "summary": summarize(rows),
        "samples": rows,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {len(rows)} probe samples to {output}")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
