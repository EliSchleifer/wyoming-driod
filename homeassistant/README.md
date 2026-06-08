# Wyoming Voice Lab (Home Assistant)

Live microphone waveform visualizer for **wyoming-driod** satellites in Lovelace.

The Android satellite exposes a lightweight `monitor` stream (audio levels only). This custom integration connects from Home Assistant and feeds a Lovelace card.

## Install

### 1. Custom integration

Copy the integration into your Home Assistant config:

```bash
cp -r homeassistant/custom_components/wyoming_voice_lab /config/custom_components/
```

Restart Home Assistant.

### 2. Add a satellite

**Settings → Devices & Services → Add Integration → Wyoming Voice Lab**

| Field | Example |
|-------|---------|
| Name | Android Satellite |
| Host | `192.168.1.129` |
| Port | `10700` |

This creates a diagnostic sensor such as `sensor.android_satellite_mic_level`.

### 3. Lovelace card resource

Copy the card JavaScript:

```bash
cp homeassistant/www/voice-lab-card.js /config/www/voice-lab-card.js
```

Add a dashboard resource (**Settings → Dashboards → ⋮ → Resources → Add resource**):

| URL | Type |
|-----|------|
| `/local/voice-lab-card.js` | JavaScript module |

### 4. Voice Lab dashboard tab

Create a new dashboard view (tab) named **Voice Lab** and add a card:

```yaml
type: custom:voice-lab-card
title: Voice Lab
entity: sensor.android_satellite_mic_level
```

Use the **mic level** entity created by the integration for the satellite you want to monitor.

## Multiple satellites

Add the integration once per satellite (different host/port). Each gets its own entity and card.

## Requirements

- wyoming-driod satellite running with the **monitor** protocol (current app versions)
- Satellite must be reachable from Home Assistant on TCP port `10700`
- Mic capture starts when Home Assistant Wyoming is streaming **or** when the Voice Lab monitor connects

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Card shows "Waiting for audio…" | Start the satellite app; ensure HA or Voice Lab monitor can reach port 10700 |
| Entity unavailable | Check host/IP; ping from HA host |
| Flat line | Speak near the device; confirm mic permission on Android |
