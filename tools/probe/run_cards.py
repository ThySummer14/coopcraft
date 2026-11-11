#!/usr/bin/env python3
"""Run YAML test cards against the CoopCraft Probe (dev-only in-game HTTP API).

Usage:
    python tools/probe/run_cards.py --card tools/cards/client_smoke.yml
    python tools/probe/run_cards.py --card tools/cards/settlement_roster.yml --dry-run

The runner is intentionally dependency-light: PyYAML + the Python standard library.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
from typing import Any, Dict, List, Optional, Tuple

try:
    import yaml
except ImportError as error:  # pragma: no cover - friendly message
    raise SystemExit("PyYAML is required: python -m pip install PyYAML") from error


class CardError(RuntimeError):
    pass


class ProbeError(RuntimeError):
    pass


@dataclass
class Side:
    name: str
    port: int
    token: str
    timeout: float = 10.0

    def call(self, path: str, payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        url = f"http://127.0.0.1:{self.port}{path}"
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=data,
            method="POST" if payload is not None else "GET",
            headers={
                "Content-Type": "application/json",
                "X-Probe-Token": self.token,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise ProbeError(f"{self.name} {path} HTTP {error.code}: {body}") from error
        except urllib.error.URLError as error:
            raise ProbeError(f"{self.name} {path} unreachable: {error}") from error
        try:
            result = json.loads(body)
        except json.JSONDecodeError as error:
            raise ProbeError(f"{self.name} {path} returned invalid JSON: {body[:300]}") from error
        if not result.get("ok", False):
            raise ProbeError(f"{self.name} {path} failed: {result.get('error', result)}")
        return result


@dataclass
class RunResult:
    card: str
    ok: bool = True
    warnings: List[str] = field(default_factory=list)
    steps: List[Dict[str, Any]] = field(default_factory=list)

    def as_dict(self) -> Dict[str, Any]:
        return {
            "ok": self.ok,
            "card": self.card,
            "warnings": self.warnings,
            "steps": self.steps,
        }


def load_card(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    if not isinstance(data, dict):
        raise CardError("card root must be a mapping")
    if not data.get("id"):
        raise CardError("card requires an id")
    return data


def split_path(path: str) -> List[str]:
    if path.startswith("data."):
        path = path[len("data."):]
    return [part for part in path.split(".") if part]


def resolve_path(value: Any, path: str) -> Tuple[bool, Any]:
    def resolve(current: Any, parts: List[str]) -> Tuple[bool, Any]:
        if not parts:
            return True, current
        part, remaining = parts[0], parts[1:]
        if part == "*":
            if not isinstance(current, list):
                return False, None
            values = []
            for item in current:
                found, resolved = resolve(item, remaining)
                if found:
                    if isinstance(resolved, list) and "*" in remaining:
                        values.extend(resolved)
                    else:
                        values.append(resolved)
            return bool(values), values
        if isinstance(current, dict):
            if part not in current:
                return False, None
            return resolve(current[part], remaining)
        if isinstance(current, list):
            try:
                index = int(part)
            except ValueError:
                return False, None
            if index < 0 or index >= len(current):
                return False, None
            return resolve(current[index], remaining)
        return False, None

    parts = split_path(path)
    found, resolved = resolve(value, parts)
    if not found and path.startswith("data.") and isinstance(value, dict) and "data" in value:
        return resolve(value["data"], parts)
    return found, resolved


def compare(actual: Any, op: str, expected: Any) -> bool:
    if op == "exists":
        wanted = bool(expected)
        return (actual is not None) == wanted
    if op == "eq":
        return actual == expected
    if op == "ne":
        return actual != expected
    if op == "contains":
        if isinstance(actual, str):
            return str(expected) in actual
        if isinstance(actual, (list, dict)):
            return expected in actual
        return False
    if op == "not_contains":
        return not compare(actual, "contains", expected)
    if op == "contains_any":
        alternatives = expected if isinstance(expected, list) else [expected]
        if isinstance(actual, str):
            return any(str(alternative) in actual for alternative in alternatives)
        if isinstance(actual, (list, dict)):
            return any(alternative in actual for alternative in alternatives)
        return False
    if op in ("gt", "gte", "lt", "lte"):
        try:
            left = float(actual)
            right = float(expected)
        except (TypeError, ValueError):
            return False
        return {
            "gt": left > right,
            "gte": left >= right,
            "lt": left < right,
            "lte": left <= right,
        }[op]
    return False


class CardRunner:
    def __init__(self, client: Side, server: Side, dry_run: bool = False):
        self.client = client
        self.server = server
        self.dry_run = dry_run
        self.result = RunResult(card="")
        self.last_ui: Optional[Dict[str, Any]] = None
        self.last_status: Dict[str, Dict[str, Any]] = {}

    def side(self, name: str) -> Side:
        if name == "client":
            return self.client
        if name == "server":
            return self.server
        raise CardError(f"unknown side: {name}")

    def fetch(self, side_name: str, endpoint: str = "status", payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        if endpoint == "ui":
            path = "/ui/snapshot"
        elif endpoint == "status":
            path = "/status"
        elif endpoint.startswith("/"):
            path = endpoint
        else:
            raise CardError(f"unknown endpoint alias: {endpoint}")
        result = self.side(side_name).call(path, payload)
        if endpoint == "ui":
            self.last_ui = result
        elif endpoint == "status":
            self.last_status[side_name] = result
        return result

    def assert_json(self, spec: Dict[str, Any]) -> None:
        if "request_any" in spec:
            candidates = spec["request_any"]
            if "request" in spec or not isinstance(candidates, list) or not candidates:
                raise CardError("request_any requires a non-empty list and cannot be combined with request")
            failures = []
            for candidate in candidates:
                attempt = dict(spec)
                attempt.pop("request_any")
                attempt["request"] = candidate
                try:
                    self.assert_json(attempt)
                    return
                except (CardError, ProbeError) as error:
                    failures.append(str(error))
            raise CardError("all request candidates failed: " + " | ".join(failures))
        side_name = spec.get("side", "server")
        endpoint = spec.get("endpoint", "status")
        path = spec.get("path", "")
        op = spec.get("op", "eq")
        expected = spec.get("value")
        data = self.fetch(side_name, endpoint, spec.get("request"))
        found, actual = resolve_path(data, path)
        if not found:
            raise CardError(f"path not found: {side_name}/{endpoint}:{path}")
        if not compare(actual, op, expected):
            raise CardError(f"assert failed: {path} {op} {expected!r}, actual={actual!r}")

    def assert_json_with_wait(self, spec: Dict[str, Any]) -> None:
        timeout_ms = max(0, int(spec.get("timeout_ms", 0)))
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            try:
                self.assert_json(spec)
                return
            except CardError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)

    def assert_ui(self, spec: Dict[str, Any]) -> None:
        data = self.fetch("client", "ui")
        title = str(data.get("title", ""))
        if "title_contains" in spec and str(spec["title_contains"]) not in title:
            raise CardError(f"screen title mismatch: expected {spec['title_contains']!r}, actual={title!r}")
        if "title_contains_any" in spec and not any(str(item) in title for item in spec["title_contains_any"]):
            raise CardError(f"screen title does not contain any of {spec['title_contains_any']!r}: actual={title!r}")
        texts = [str(item) for item in data.get("texts", [])]
        for widget in data.get("widgets", []):
            texts.append(str(widget.get("message", "")))
        if "text_contains" in spec and not any(str(spec["text_contains"]) in text for text in texts):
            raise CardError(f"UI text not found: {spec['text_contains']!r}")
        if "text_contains_any" in spec and not any(
            any(str(expected) in text for text in texts) for expected in spec["text_contains_any"]
        ):
            raise CardError(f"none of the expected UI texts found: {spec['text_contains_any']!r}")
        for forbidden in spec.get("text_not_contains", []):
            if any(str(forbidden) in text for text in texts):
                raise CardError(f"forbidden UI text found: {forbidden!r}")
        if "screen_contains" in spec and str(spec["screen_contains"]) not in str(data.get("screen", "")):
            raise CardError(f"screen class mismatch: expected {spec['screen_contains']!r}, actual={data.get('screen')!r}")

    def assert_ui_with_wait(self, spec: Dict[str, Any]) -> None:
        timeout_ms = max(0, int(spec.get("timeout_ms", 0)))
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            try:
                self.assert_ui(spec)
                return
            except CardError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)

    def player_use_until(self, spec: Dict[str, Any]) -> None:
        if "screen_contains" not in spec:
            raise CardError("player_use_until requires screen_contains")
        payload = {key: spec[key] for key in ("x", "y", "z", "face", "shift") if key in spec}
        timeout_ms = max(0, int(spec.get("timeout_ms", 10000)))
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            self.client.call("/player/use", payload)
            try:
                self.assert_ui({"screen_contains": spec["screen_contains"]})
                return
            except CardError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.5)

    def click_text(self, text: Any) -> None:
        alternatives = [text] if isinstance(text, str) else list(text)
        data = self.fetch("client", "ui")
        for alternative in alternatives:
            for widget in data.get("widgets", []):
                message = str(widget.get("message", ""))
                if str(alternative) and str(alternative) in message and widget.get("visible", True) and widget.get("active", True):
                    x = float(widget.get("x", 0)) + float(widget.get("width", 0)) / 2.0
                    y = float(widget.get("y", 0)) + float(widget.get("height", 0)) / 2.0
                    self.client.call("/ui/click", {"x": x, "y": y})
                    return
        raise CardError(f"clickable widget not found: {alternatives!r}")

    def click_text_until(self, spec: Dict[str, Any]) -> int:
        target = spec.get("text_any", spec.get("text"))
        if not target:
            raise CardError("click_text_until requires text_any")
        alternatives = target if isinstance(target, list) else [target]
        button = spec.get("button", ["下一页", "Next"])
        max_clicks = max(0, int(spec.get("max_clicks", 5)))
        interval_ms = max(0, int(spec.get("interval_ms", 300)))
        for page in range(max_clicks + 1):
            self.client.call("/ui/reset")
            time.sleep(interval_ms / 1000.0)
            try:
                self.assert_ui({"text_contains_any": alternatives})
                return page + 1
            except CardError:
                if page >= max_clicks:
                    raise
                self.click_text(button)
        raise AssertionError("unreachable")

    def screenshot(self, name: str) -> str:
        response = self.client.call("/screenshot", {"name": name})
        path = str(response.get("path", ""))
        deadline = time.time() + 5.0
        while time.time() < deadline:
            if path and os.path.exists(path) and os.path.getsize(path) > 0:
                return path
            time.sleep(0.1)
        raise CardError(f"screenshot was not written: {path}")

    def execute_step(self, index: int, step: Dict[str, Any]) -> Dict[str, Any]:
        if not isinstance(step, dict) or len(step) != 1:
            raise CardError(f"step {index} must be a one-key mapping")
        kind, value = next(iter(step.items()))
        detail: Any = None

        if kind == "wait":
            milliseconds = int(value)
            if not self.dry_run:
                time.sleep(max(0, milliseconds) / 1000.0)
            detail = {"ms": milliseconds}
        elif kind in ("server_command", "client_command"):
            side_name = "server" if kind == "server_command" else "client"
            command = str(value).lstrip("/")
            if not self.dry_run:
                response = self.side(side_name).call("/command", {"cmd": command})
                result = response.get("result")
                if result == 0:
                    self.result.warnings.append(
                        f"step {index}: command returned 0: {command} ({response.get('output', [])})"
                    )
                detail = {"result": result, "output": response.get("output", [])}
            else:
                detail = {"command": command}
        elif kind in ("key", "mapping"):
            payload = {"mapping": str(value)} if str(value).startswith("key.") else {"key": str(value)}
            if not self.dry_run:
                response = self.client.call("/key", payload)
                detail = {"mapping": response.get("mapping")}
            else:
                detail = payload
        elif kind in ("player_use", "player_use_entity"):
            payload = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                endpoint = "/player/use-entity" if kind == "player_use_entity" else "/player/use"
                detail = self.client.call(endpoint, payload)
            else:
                detail = payload
        elif kind == "player_use_until":
            spec = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                self.player_use_until(spec)
            detail = spec
        elif kind == "ui_click":
            payload = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                detail = self.client.call("/ui/click", payload)
            else:
                detail = payload
        elif kind == "ui_close":
            if not self.dry_run:
                self.client.call("/ui/close")
            detail = {"closed": True}
        elif kind == "click_text":
            if not self.dry_run:
                self.click_text(value)
            detail = {"text": value}
        elif kind == "click_text_until":
            spec = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                detail = {"page": self.click_text_until(spec)}
            else:
                detail = spec
        elif kind == "screenshot":
            name = str(value) if value not in (True, None) else f"card-{int(time.time())}"
            if not self.dry_run:
                detail = {"path": self.screenshot(name)}
            else:
                detail = {"name": name}
        elif kind == "assert_ui":
            spec = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                self.assert_ui_with_wait(spec)
            detail = spec
        elif kind == "assert_json":
            spec = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                self.assert_json_with_wait(spec)
            detail = spec
        elif kind == "assert_block":
            spec = dict(value) if isinstance(value, dict) else {}
            if not self.dry_run:
                response = self.server.call("/world/block", {
                    "x": spec.get("x"),
                    "y": spec.get("y"),
                    "z": spec.get("z"),
                })
                expected = str(spec.get("block", ""))
                actual = str(response.get("block", ""))
                if expected and actual != expected:
                    raise CardError(f"block mismatch at {spec}: expected {expected}, actual {actual}")
                detail = {"block": actual}
            else:
                detail = spec
        else:
            self.result.warnings.append(f"step {index}: unsupported step '{kind}'")
            detail = {"unsupported": kind}

        return {"step": index, "kind": kind, "ok": True, "detail": detail}

    def run(self, card: Dict[str, Any]) -> RunResult:
        self.result.card = str(card.get("id", ""))
        setup = card.get("setup") or {}
        if isinstance(setup, dict):
            for key in ("server_commands", "client_commands"):
                commands = setup.get(key) or []
                side_name = "server" if key == "server_commands" else "client"
                for command in commands:
                    step = {f"{side_name}_command": command}
                    if not self.dry_run:
                        response = self.side(side_name).call("/command", {"cmd": str(command).lstrip("/")})
                        if response.get("result") == 0:
                            self.result.warnings.append(
                                f"setup {side_name} command returned 0: {command} ({response.get('output', [])})"
                            )
        steps = card.get("steps") or []
        if not isinstance(steps, list):
            raise CardError("steps must be a list")
        for index, step in enumerate(steps, start=1):
            try:
                self.result.steps.append(self.execute_step(index, step))
            except Exception as error:
                self.result.ok = False
                self.result.steps.append({
                    "step": index,
                    "kind": next(iter(step)) if isinstance(step, dict) and step else "?",
                    "ok": False,
                    "error": str(error),
                })
                break
        return self.result


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a CoopCraft Probe YAML card")
    parser.add_argument("--card", required=True, help="YAML card path")
    parser.add_argument("--client-port", type=int, default=int(os.getenv("COOPCRAFT_PROBE_CLIENT_PORT", "39002")))
    parser.add_argument("--server-port", type=int, default=int(os.getenv("COOPCRAFT_PROBE_SERVER_PORT", "39001")))
    parser.add_argument("--token", default=os.getenv("COOPCRAFT_PROBE_TOKEN", "coopcraft"))
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true", help="print only the JSON summary")
    args = parser.parse_args()

    card = load_card(args.card)
    runner = CardRunner(
        Side("client", args.client_port, args.token, args.timeout),
        Side("server", args.server_port, args.token, args.timeout),
        dry_run=args.dry_run,
    )
    result = runner.run(card).as_dict()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"card: {result['card']}")
        for step in result["steps"]:
            marker = "OK" if step.get("ok") else "FAIL"
            print(f"  [{marker}] {step.get('step')}: {step.get('kind')} {step.get('detail', step.get('error', ''))}")
        if result["warnings"]:
            print("warnings:")
            for warning in result["warnings"]:
                print(f"  - {warning}")
        print("PASS" if result["ok"] else "FAIL")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
