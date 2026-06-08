"""Wyoming Voice Lab integration."""

from __future__ import annotations

import asyncio
import logging
from collections import deque
from dataclasses import dataclass, field

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.typing import ConfigType
import voluptuous as vol

from .const import CONF_HOST, CONF_NAME, CONF_PORT, DOMAIN, EVENT_LEVEL
from .monitor import SatelliteMonitor
from .sensor import WyomingVoiceLabSensorEntity

_LOGGER = logging.getLogger(__name__)

WAVEFORM_SIZE = 96
PLATFORMS = [Platform.SENSOR]

SATELLITE_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_NAME): cv.string,
        vol.Required(CONF_HOST): cv.string,
        vol.Required(CONF_PORT, default=10700): cv.port,
    }
)

CONFIG_SCHEMA = vol.Schema(
    {
        DOMAIN: vol.Schema(
            {
                vol.Required("satellites"): vol.All(cv.ensure_list, [SATELLITE_SCHEMA]),
            }
        )
    },
    extra=vol.ALLOW_EXTRA,
)


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


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """YAML setup for one or more satellites."""
    if DOMAIN not in config:
        return True

    for satellite in config[DOMAIN]["satellites"]:
        entry_id = f"yaml_{satellite[CONF_HOST]}_{satellite[CONF_PORT]}"
        if entry_id in hass.data.get(DOMAIN, {}):
            continue
        await _start_runtime(
            hass,
            entry_id=entry_id,
            name=satellite[CONF_NAME],
            host=satellite[CONF_HOST],
            port=satellite[CONF_PORT],
            create_entities=True,
        )
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    await _start_runtime(
        hass,
        entry_id=entry.entry_id,
        name=entry.data[CONF_NAME],
        host=entry.data[CONF_HOST],
        port=entry.data[CONF_PORT],
        create_entities=False,
    )
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def _start_runtime(
    hass: HomeAssistant,
    entry_id: str,
    name: str,
    host: str,
    port: int,
    create_entities: bool,
) -> None:
    runtime = SatelliteRuntime(entry_id=entry_id, name=name, host=host, port=port)

    @callback
    def publish_level(level: float, peak: float) -> None:
        runtime.level = level
        runtime.peak = peak
        runtime.waveform.append(level)
        hass.bus.async_fire(
            EVENT_LEVEL,
            {
                "entry_id": entry_id,
                "name": name,
                "host": host,
                "port": port,
                "level": level,
                "peak": peak,
                "waveform": list(runtime.waveform),
            },
        )
        for entity in runtime.listeners:
            entity.async_write_ha_state()

    runtime.monitor = SatelliteMonitor(host, port, publish_level)
    runtime.monitor.start()

    hass.data.setdefault(DOMAIN, {})[entry_id] = runtime
    _LOGGER.info("Wyoming Voice Lab monitoring %s at %s:%s", name, host, port)

    if create_entities:
        entity = WyomingVoiceLabSensorEntity(runtime, entry_id, name)
        runtime.listeners.append(entity)
        entity.async_schedule_update_ha_state(True)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unload_ok = await hass.config_entries.async_forward_entry_unload(entry, "sensor")
    await _stop_runtime(hass, entry.entry_id)
    return unload_ok


async def _stop_runtime(hass: HomeAssistant, entry_id: str) -> None:
    runtime: SatelliteRuntime | None = hass.data.get(DOMAIN, {}).pop(entry_id, None)
    if runtime and runtime.monitor:
        await runtime.monitor.stop()
