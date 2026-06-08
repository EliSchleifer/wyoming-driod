"""Sensor platform for Wyoming Voice Lab."""

from __future__ import annotations

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    entity = WyomingVoiceLabSensorEntity(runtime, entry.entry_id, entry.data["name"])
    runtime.listeners.append(entity)
    async_add_entities([entity])


class WyomingVoiceLabSensorEntity(SensorEntity):
    """Mic level sensor for a Wyoming satellite."""

    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_native_unit_of_measurement = PERCENTAGE
    _attr_icon = "mdi:microphone"

    def __init__(self, runtime, entry_id: str, name: str) -> None:
        self._runtime = runtime
        self._entry_id = entry_id
        self._attr_unique_id = f"{entry_id}_mic_level"
        self._attr_name = f"{name} mic level"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry_id)},
            name=name,
            manufacturer="wyoming-driod",
            model="Wyoming Satellite",
        )

    @property
    def native_value(self) -> float:
        return round(self._runtime.level * 100, 1)

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "peak_percent": round(self._runtime.peak * 100, 1),
            "waveform": list(self._runtime.waveform),
            "host": self._runtime.host,
            "port": self._runtime.port,
            "entry_id": self._entry_id,
        }
