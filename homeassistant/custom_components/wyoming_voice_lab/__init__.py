"""Wyoming Voice Lab integration."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo

from .const import CONF_HOST, CONF_NAME, CONF_PORT, DOMAIN, EVENT_LEVEL
from .monitor import SatelliteMonitor

WAVEFORM_SIZE = 96


@dataclass
class SatelliteRuntime:
    entry_id: str
    name: str
    host: str
    port: int
    level: float = 0.0
    peak: float = 0.0
    waveform: deque[float] = field(default_factory=lambda: deque(maxlen=WAVEFORM_SIZE))
    monitor: SatelliteMonitor | None = None
    listeners: list = field(default_factory=list)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    runtime = SatelliteRuntime(
        entry_id=entry.entry_id,
        name=entry.data[CONF_NAME],
        host=entry.data[CONF_HOST],
        port=entry.data[CONF_PORT],
    )

    async def on_level(level: float, peak: float) -> None:
        runtime.level = level
        runtime.peak = peak
        runtime.waveform.append(level)
        hass.bus.async_fire(
            EVENT_LEVEL,
            {
                "entry_id": entry.entry_id,
                "name": runtime.name,
                "host": runtime.host,
                "port": runtime.port,
                "level": level,
                "peak": peak,
                "waveform": list(runtime.waveform),
            },
        )
        for entity in runtime.listeners:
            entity.async_write_ha_state()

    runtime.monitor = SatelliteMonitor(runtime.host, runtime.port, on_level)
    runtime.monitor.start()

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = runtime
    await hass.config_entries.async_forward_entry_setups(entry, ["sensor"])
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unload_ok = await hass.config_entries.async_forward_entry_unload(entry, "sensor")
    runtime: SatelliteRuntime | None = hass.data.get(DOMAIN, {}).pop(entry.entry_id, None)
    if runtime and runtime.monitor:
        await runtime.monitor.stop()
    return unload_ok
