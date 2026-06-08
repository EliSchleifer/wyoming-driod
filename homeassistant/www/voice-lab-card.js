class VoiceLabCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._waveform = new Float32Array(96);
    this._level = 0;
    this._peak = 0;
    this._active = false;
    this._unsub = null;
  }

  static getStubConfig() {
    return {
      type: "custom:voice-lab-card",
      entity: "",
      title: "Voice Lab",
    };
  }

  static getConfigElement() {
    return document.createElement("voice-lab-card-editor");
  }

  setConfig(config) {
    if (!config.entity) {
      throw new Error("Set an entity from Wyoming Voice Lab");
    }
    this._config = config;
    this._entryId = null;
  }

  set hass(hass) {
    this._hass = hass;
    if (!this._config) return;

    const state = hass.states[this._config.entity];
    if (state) {
      this._entryId = state.attributes.entry_id || null;
      if (state.attributes.waveform) {
        const wf = state.attributes.waveform;
        for (let i = 0; i < this._waveform.length; i++) {
          this._waveform[i] = wf[i] || 0;
        }
      }
      this._level = (state.state === "unavailable" ? 0 : parseFloat(state.state)) / 100;
      this._peak = (state.attributes.peak_percent || 0) / 100;
      this._active = this._level > 0.001;
    }

    if (!this._unsub) {
      this._unsub = hass.connection.subscribeEvents((ev) => {
        const data = ev.data;
        if (this._entryId && data.entry_id !== this._entryId) return;
        if (!this._entryId && this._config.entity) {
          const name = this._hass.states[this._config.entity]?.attributes?.friendly_name;
          if (name && data.name && !name.includes(data.name)) return;
        }
        this._level = data.level || 0;
        this._peak = data.peak || 0;
        this._active = this._level > 0.001 || this._peak > 0.001;
        if (Array.isArray(data.waveform)) {
          for (let i = 0; i < this._waveform.length; i++) {
            this._waveform[i] = data.waveform[i] || 0;
          }
        }
        this._draw();
      }, "wyoming_voice_lab_level");
    }

    if (!this._raf) {
      this._raf = requestAnimationFrame(() => this._draw());
    }
  }

  connectedCallback() {
    if (!this._canvas) {
      this.shadowRoot.innerHTML = `
        <style>
          :host { display: block; }
          .card {
            background: var(--card-background-color, #fff);
            border-radius: var(--ha-card-border-radius, 12px);
            box-shadow: var(--ha-card-box-shadow, 0 2px 4px rgba(0,0,0,.1));
            padding: 16px;
            overflow: hidden;
          }
          .title {
            font-size: 1.1rem;
            font-weight: 600;
            margin-bottom: 4px;
          }
          .subtitle {
            color: var(--secondary-text-color, #666);
            font-size: 0.85rem;
            margin-bottom: 12px;
          }
          canvas {
            width: 100%;
            height: 160px;
            display: block;
            border-radius: 8px;
            background: rgba(0,0,0,0.04);
          }
          .stats {
            margin-top: 10px;
            font-size: 0.95rem;
            font-weight: 600;
            text-align: center;
          }
        </style>
        <div class="card">
          <div class="title"></div>
          <div class="subtitle"></div>
          <canvas width="640" height="160"></canvas>
          <div class="stats"></div>
        </div>
      `;
      this._titleEl = this.shadowRoot.querySelector(".title");
      this._subtitleEl = this.shadowRoot.querySelector(".subtitle");
      this._statsEl = this.shadowRoot.querySelector(".stats");
      this._canvas = this.shadowRoot.querySelector("canvas");
      this._ctx = this._canvas.getContext("2d");
    }
    this._draw();
  }

  disconnectedCallback() {
    if (this._unsub) {
      this._unsub.then((unsub) => unsub());
      this._unsub = null;
    }
  }

  getCardSize() {
    return 3;
  }

  _draw() {
    if (!this._ctx || !this._config) return;
    const title = this._config.title || "Voice Lab";
    const state = this._hass?.states[this._config.entity];
    const satelliteName = state?.attributes?.host
      ? `${state.attributes.host}:${state.attributes.port}`
      : this._config.entity;

    this._titleEl.textContent = title;
    this._subtitleEl.textContent = state?.attributes?.friendly_name || satelliteName;
    this._statsEl.textContent = this._active
      ? `Level ${Math.round(this._level * 100)}% · Peak ${Math.round(this._peak * 100)}%`
      : "Waiting for audio…";

    const ctx = this._ctx;
    const w = this._canvas.width;
    const h = this._canvas.height;
    ctx.clearRect(0, 0, w, h);

    const barH = h * 0.18;
    ctx.fillStyle = "rgba(26, 115, 232, 0.15)";
    ctx.fillRect(16, 8, w - 32, barH);
    if (this._active) {
      ctx.fillStyle = "#1A73E8";
      ctx.fillRect(16, 8, (w - 32) * this._level, barH);
      const peakX = 16 + (w - 32) * this._peak;
      ctx.strokeStyle = "#1558B0";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(peakX, 8);
      ctx.lineTo(peakX, 8 + barH);
      ctx.stroke();
    }

    const waveTop = barH + 24;
    const waveH = h - waveTop - 8;
    const midY = waveTop + waveH / 2;
    ctx.strokeStyle = "rgba(0,0,0,0.08)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(16, midY);
    ctx.lineTo(w - 16, midY);
    ctx.stroke();

    if (!this._active) return;

    ctx.strokeStyle = "#1A73E8";
    ctx.lineWidth = 2;
    ctx.beginPath();
    const step = (w - 32) / (this._waveform.length - 1);
    for (let i = 0; i < this._waveform.length; i++) {
      const x = 16 + step * i;
      const y = midY - this._waveform[i] * waveH * 0.45;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
  }
}

class VoiceLabCardEditor extends HTMLElement {
  setConfig(config) {
    this._config = config;
  }

  set hass(hass) {
    this._hass = hass;
    if (!this._form) {
      this.innerHTML = `<ha-form></ha-form>`;
      this._form = this.querySelector("ha-form");
    }
    const entities = Object.keys(hass.states).filter((id) =>
      id.startsWith("sensor.") && id.includes("mic_level")
    );
    this._form.hass = hass;
    this._form.data = this._config || {};
    this._form.schema = [
      { name: "title", selector: { text: {} } },
      {
        name: "entity",
        required: true,
        selector: { entity: { domain: "sensor" } },
      },
    ];
    this._form.computeLabel = (schema) => {
      if (schema.name === "title") return "Title";
      if (schema.name === "entity") return "Satellite mic level entity";
      return schema.name;
    };
    this._form.addEventListener("value-changed", (ev) => {
      ev.stopPropagation();
      const value = { ...ev.detail.value, type: "custom:voice-lab-card" };
      this.dispatchEvent(new CustomEvent("config-changed", { detail: { config: value } }));
    });
  }
}

customElements.define("voice-lab-card", VoiceLabCard);
customElements.define("voice-lab-card-editor", VoiceLabCardEditor);
window.customCards = window.customCards || [];
window.customCards.push({
  type: "voice-lab-card",
  name: "Voice Lab",
  description: "Live microphone waveform from a Wyoming satellite",
  preview: true,
});
