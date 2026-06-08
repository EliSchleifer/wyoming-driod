"""Connects to a wyoming-driod satellite monitor stream."""

from __future__ import annotations

import asyncio
import logging
from typing import Callable

from .wyoming_io import WyomingEvent, read_event, write_event

_LOGGER = logging.getLogger(__name__)

LevelCallback = Callable[[float, float], None]


class SatelliteMonitor:
    """Subscribe to audio-level events from a Wyoming satellite."""

    def __init__(
        self,
        host: str,
        port: int,
        on_level: LevelCallback,
    ) -> None:
        self._host = host
        self._port = port
        self._on_level = on_level
        self._task: asyncio.Task | None = None
        self._running = False

    def start(self) -> None:
        if self._task is not None:
            return
        self._running = True
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        self._running = False
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None

    async def _run(self) -> None:
        while self._running:
            try:
                await self._session()
            except asyncio.CancelledError:
                raise
            except Exception as err:
                _LOGGER.debug(
                    "Monitor disconnected from %s:%s (%s)", self._host, self._port, err
                )
            if self._running:
                await asyncio.sleep(2)

    async def _session(self) -> None:
        reader, writer = await asyncio.open_connection(self._host, self._port)
        try:
            await write_event(writer, WyomingEvent("describe"))
            await self._read_until(reader, "info")

            await write_event(writer, WyomingEvent("monitor"))
            await self._read_until(reader, "monitor-started")

            while self._running:
                event = await read_event(reader)
                if event is None:
                    break
                if event.type == "audio-level" and event.data is not None:
                    level = float(event.data.get("level", 0.0))
                    peak = float(event.data.get("peak", 0.0))
                    self._on_level(level, peak)
                elif event.type == "ping":
                    await write_event(writer, WyomingEvent("pong", event.data))
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def _read_until(self, reader: asyncio.StreamReader, event_type: str) -> None:
        for _ in range(20):
            event = await read_event(reader)
            if event is None:
                return
            if event.type == event_type:
                return
