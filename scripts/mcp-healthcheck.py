#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import select
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_COMMAND = [str(REPO_ROOT / "scripts" / "start-mcp-server.sh")]


class MCPReader:
    def __init__(self, stream: Any) -> None:
        self.stream = stream
        self.buffer = bytearray()

    def read_message(self, deadline: float) -> dict[str, Any]:
        while True:
            header_end = self.buffer.find(b"\r\n\r\n")
            if header_end != -1:
                message = self._try_decode_message(header_end)
                if message is not None:
                    return message

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Timed out waiting for MCP message")

            ready, _, _ = select.select([self.stream], [], [], remaining)
            if not ready:
                raise TimeoutError("Timed out waiting for MCP message")

            chunk = os.read(self.stream.fileno(), 4096)
            if not chunk:
                raise EOFError("MCP process closed stdout before sending a complete message")
            self.buffer.extend(chunk)

    def _try_decode_message(self, header_end: int) -> dict[str, Any] | None:
        headers = self._parse_headers(bytes(self.buffer[:header_end]))
        if b"content-length" not in headers:
            raise ValueError("Missing Content-Length header in MCP response")

        content_length = int(headers[b"content-length"])
        body_start = header_end + 4
        body_end = body_start + content_length
        if len(self.buffer) < body_end:
            return None

        payload = bytes(self.buffer[body_start:body_end])
        del self.buffer[:body_end]
        return json.loads(payload.decode("utf-8"))

    @staticmethod
    def _parse_headers(header_blob: bytes) -> dict[bytes, bytes]:
        headers: dict[bytes, bytes] = {}
        for line in header_blob.split(b"\r\n"):
            if not line:
                continue
            if b":" not in line:
                raise ValueError(f"Malformed MCP header line: {line!r}")
            key, value = line.split(b":", 1)
            headers[key.strip().lower()] = value.strip()
        return headers


def send_message(proc: subprocess.Popen[bytes], message: dict[str, Any]) -> None:
    payload = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    envelope = f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii") + payload
    assert proc.stdin is not None
    proc.stdin.write(envelope)
    proc.stdin.flush()


def wait_for_response(reader: MCPReader, deadline: float, expected_id: int) -> dict[str, Any]:
    while True:
        message = reader.read_message(deadline)
        if message.get("id") == expected_id:
            return message


def terminate_process(proc: subprocess.Popen[bytes]) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=2)


def read_stderr(proc: subprocess.Popen[bytes]) -> str:
    if proc.stderr is None:
        return ""
    data = proc.stderr.read()
    if not data:
        return ""
    return data.decode("utf-8", errors="replace").strip()


def run_healthcheck(command: list[str], timeout_sec: float, protocol_version: str) -> tuple[bool, str]:
    started_at = time.monotonic()
    env = os.environ.copy()
    env.setdefault("SCI_MCP_ALLOWED_ROOTS", str(REPO_ROOT))
    proc = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=False,
        cwd=str(REPO_ROOT),
        env=env,
    )

    try:
        deadline = started_at + timeout_sec
        reader = MCPReader(proc.stdout)

        send_message(
            proc,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": protocol_version,
                    "capabilities": {},
                    "clientInfo": {"name": "semantic-code-indexing-healthcheck", "version": "1.0"},
                },
            },
        )
        init_reply = wait_for_response(reader, deadline, expected_id=1)
        if "error" in init_reply:
            return False, f"initialize failed: {init_reply['error']}"

        send_message(
            proc,
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        )
        send_message(
            proc,
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
        )
        tools_reply = wait_for_response(reader, deadline, expected_id=2)
        if "error" in tools_reply:
            return False, f"tools/list failed: {tools_reply['error']}"

        tools = tools_reply.get("result", {}).get("tools", [])
        elapsed = time.monotonic() - started_at
        return True, f"OK: handshake in {elapsed:.2f}s, tools={len(tools)}"
    except Exception as exc:  # noqa: BLE001
        stderr_text = read_stderr(proc)
        suffix = f"\n--- stderr ---\n{stderr_text}" if stderr_text else ""
        return False, f"{exc}{suffix}"
    finally:
        terminate_process(proc)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Health-check semantic-code-indexing MCP startup over stdio."
    )
    parser.add_argument(
        "--timeout-sec",
        type=float,
        default=30.0,
        help="Max total time for initialize + tools/list.",
    )
    parser.add_argument(
        "--protocol-version",
        default="2024-11-05",
        help="Protocol version to send during initialize.",
    )
    parser.add_argument(
        "--command",
        nargs="+",
        default=DEFAULT_COMMAND,
        help="Command used to launch the MCP server.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    ok, message = run_healthcheck(
        command=args.command,
        timeout_sec=args.timeout_sec,
        protocol_version=args.protocol_version,
    )
    stream = sys.stdout if ok else sys.stderr
    print(message, file=stream)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
