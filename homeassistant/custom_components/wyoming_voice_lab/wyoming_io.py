"""Minimal Wyoming protocol reader/writer (matches wyoming-driod)."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any


@dataclass
class WyomingEvent:
    type: str
    data: dict[str, Any] | None = None
    payload: bytes | None = None


async def read_event(reader: asyncio.StreamReader) -> WyomingEvent | None:
    line = await reader.readline()
    if not line:
        return None
    text = line.decode("utf-8").strip()
    if not text:
        return WyomingEvent("")

    header = json.loads(text)
    event_type = header["type"]
    data = header.get("data")

    if "data_length" in header and header["data_length"] > 0:
        raw = await reader.readexactly(header["data_length"])
        data = json.loads(raw.decode("utf-8"))

    payload = None
    if "payload_length" in header and header["payload_length"] > 0:
        payload = await reader.readexactly(header["payload_length"])

    return WyomingEvent(event_type, data, payload)


async def write_event(writer: asyncio.StreamWriter, event: WyomingEvent) -> None:
    header: dict[str, Any] = {"type": event.type, "version": "1.5.4"}
    data_bytes: bytes | None = None
    if event.data is not None:
        data_bytes = json.dumps(event.data).encode("utf-8")
        header["data_length"] = len(data_bytes)
    if event.payload is not None:
        header["payload_length"] = len(event.payload)

    writer.write((json.dumps(header) + "\n").encode("utf-8"))
    if data_bytes:
        writer.write(data_bytes)
    if event.payload:
        writer.write(event.payload)
    await writer.drain()
