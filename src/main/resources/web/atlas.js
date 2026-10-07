/* Atlas web map. Coordinates: Leaflet's CRS.Simple with lat = -z and lng = x, so north is up and one unit is one block.
   At zoom 0 one pixel is one block; tile level k (0..6) is zoom -k. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const BLANK = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
  const VOLTAGE = ['12 V DC', '230 V', '10 kV', '110 kV'];
  const VCOLOR = ['#e04040', '#e0e0e0', '#f0a030', '#60a0ff'];

  const state = {
    info: null, dim: null, rev: Date.now(), live: null,
    on: JSON.parse(localStorage.getItem('atlas.layers') || 'null') || {
      players: true, waypoints: true, grid: false, radiation: true, doserate: true, sources: true, emitters: false, fallout: false,
      broadcast: false, stations: true
    },
    station: localStorage.getItem('atlas.station') || 'best',
  };
  // ?layers=grid,broadcast,... switches exactly these on (for links and screenshots), without saving
  const forced = new URLSearchParams(location.search).get('layers');
  if (forced !== null) {
    const want = forced.split(',');
    Object.keys(state.on).forEach((k) => { state.on[k] = want.includes(k); });
  }
  const save = () => { if (forced !== null) return; localStorage.setItem('atlas.layers', JSON.stringify(state.on)); localStorage.setItem('atlas.station', state.station); };

  const map = L.map('map', { crs: L.CRS.Simple, minZoom: -6, maxZoom: 4, zoomSnap: 1, preferCanvas: true, zoomControl: false, attributionControl: true });
  // top right: the panel covers the top left
  L.control.zoom({ position: 'topright' }).addTo(map);
  map.attributionControl.setPrefix('<a href="https://leafletjs.com">Leaflet</a>');
  const ll = (x, z) => L.latLng(-z, x);
  const canvas = L.canvas({ padding: 0.3 });

  const Tiles = L.TileLayer.extend({
    getTileUrl(c) { return `tiles/${state.dim}/${-c.z}/${c.x}_${c.y}.png?v=${state.rev}`; }
  });
  const tiles = new Tiles('', { tileSize: 256, minZoom: -6, maxZoom: 4, minNativeZoom: -6, maxNativeZoom: 0, noWrap: true,
    errorTileUrl: BLANK, keepBuffer: 4, attribution: 'Atlas' }).addTo(map);

  const groups = {
    doserate: L.layerGroup(), sources: L.layerGroup(), emitters: L.layerGroup(), fallout: L.layerGroup(),
    grid: L.layerGroup(), coverage: L.layerGroup(), stations: L.layerGroup(), waypoints: L.layerGroup(), players: L.layerGroup(),
  };
  Object.values(groups).forEach((g) => g.addTo(map));

  const get = (url) => fetch(url, { cache: 'no-store' }).then((r) => r.ok ? r.json() : null).catch(() => null);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const fmt = (v, unit = '') => v >= 100 ? v.toFixed(0) + unit : v >= 1 ? v.toFixed(2) + unit : v >= 0.01 ? v.toFixed(3) + unit : v.toFixed(4) + unit;
  const watts = (w) => w >= 1e6 ? (w / 1e6).toFixed(1) + ' MW' : w >= 1e3 ? (w / 1e3).toFixed(w >= 1e4 ? 0 : 1) + ' kW' : Math.round(w) + ' W';
  const freq = (hz) => hz >= 30e6 ? (hz / 1e6).toFixed(1) + ' MHz' : (hz / 1e3).toFixed(0) + ' kHz';
  const nice = (id) => id.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  const clock = (t) => { const h = Math.floor(((t + 6000) % 24000) / 1000), m = Math.floor(((t + 6000) % 1000) * 0.06); return `${h}:${String(m).padStart(2, '0')}`; };

  // ------------------------------------------------------------------ panel

  function buildPanel() {
    const has = (l) => state.info.layers.includes(l);
    const box = (key, text, sub = '') => `<div class="layer"><label><input type="checkbox" data-k="${key}"${state.on[key] ? ' checked' : ''}> ${text}</label>${sub}</div>`;
    const subBox = (key, text) => `<label><input type="checkbox" data-k="${key}"${state.on[key] ? ' checked' : ''}> ${text}</label>`;
    let h = box('players', 'Players') + box('waypoints', 'Waypoints');
    if (has('grid')) h += box('grid', 'Power grid');
    if (has('radiation')) h += box('radiation', 'Radiation', `<div class="sub">${subBox('doserate', 'Dose rate map')}${subBox('sources', 'Sources, zones, clouds')}${subBox('fallout', 'Fallout points')}${subBox('emitters', 'Radiating blocks')}</div>`);
    if (has('broadcast')) h += box('broadcast', 'Broadcast reception', `<div class="sub"><select id="station"><option value="best">Best of all stations</option></select>${subBox('stations', 'Transmitters')}</div>`);
    $('layers').innerHTML = h;
    $('layers').querySelectorAll('input[data-k]').forEach((cb) => cb.addEventListener('change', () => {
      state.on[cb.dataset.k] = cb.checked; save(); refreshAll();
    }));
    const st = $('station');
    if (st) st.addEventListener('change', () => { state.station = st.value; save(); drawCoverage(); });
  }

  $('fold').addEventListener('click', () => { $('panel').classList.toggle('folded'); $('fold').textContent = $('panel').classList.contains('folded') ? '+' : '–'; });

  // ------------------------------------------------------------------ legend

  function legend() {
    let h = '';
    if (state.on.radiation && state.on.doserate) {
      h += '<h3>Dose rate (rad/s)</h3><div class="bar" style="background:linear-gradient(90deg,' +
        DOSE.map((s) => `rgb(${s[1]},${s[2]},${s[3]})`).join(',') + ')"></div><div class="ticks"><span>0.002</span><span>0.01</span><span>0.1</span><span>1</span><span>10</span><span>100</span></div>';
    }
    if (state.on.radiation && state.on.sources) {
      h += '<div class="key"><span class="pin">☁</span> radioactive cloud</div><div class="key"><span class="pin">☢</span> source, open core, waste barrel</div>';
    }
    if (state.on.grid && state.grid) {
      h += '<h3>Power grid</h3>' + VOLTAGE.map((v, i) => `<div class="key"><span class="sw" style="background:${VCOLOR[i]}"></span>${v}</div>`).join('') +
        '<div class="key"><span class="sw" style="background:#fff;border:2px solid #ff3030"></span>overloaded</div>';
      const nets = (state.grid.networks || []).filter((n) => n.delivered > 0 || n.demanded > 0).sort((a, b) => b.delivered - a.delivered).slice(0, 8);
      if (nets.length) h += '<h3>Largest networks</h3>' + nets.map((n) =>
        `<div class="key"><span class="sw" style="background:${VCOLOR[n.voltage]}"></span>${VOLTAGE[n.voltage]}: ${watts(n.delivered)} of ${watts(n.demanded)}${n.fraction < 0.999 ? ` <b style="color:#ff6b5b">(${Math.round(n.fraction * 100)} %)</b>` : ''}</div>`).join('');
    }
    if (state.on.broadcast && state.coverage) {
      h += '<h3>Reception (portable radio)</h3>' + RECEPTION.map((r) => `<div class="key"><span class="sw" style="background:rgba(${r[1]},${r[2]},${r[3]},.9)"></span>${r[4]}</div>`).join('') +
        `<div class="ticks"><span>computed at ${clock(state.coverage.time)}${state.coverage.range ? `, up to ${state.coverage.range} blocks (dashed)` : ''}</span></div>`;
    }
    $('legend').innerHTML = h;
  }

  // ------------------------------------------------------------------ rasters

  const DOSE = [[-2.7, 70, 190, 90, 0.30], [-2, 150, 210, 60, 0.42], [-1, 245, 205, 40, 0.5], [0, 250, 125, 30, 0.58], [1, 220, 40, 40, 0.66], [2, 140, 20, 130, 0.72]];
  function doseColor(rate) {
    const v = Math.log10(Math.max(rate, 1e-4));
    if (v <= DOSE[0][0]) return DOSE[0].slice(1);
    for (let i = 1; i < DOSE.length; i++) {
      if (v <= DOSE[i][0]) {
        const a = DOSE[i - 1], b = DOSE[i], t = (v - a[0]) / (b[0] - a[0]);
        return [0, 1, 2, 3].map((k) => a[k + 1] + (b[k + 1] - a[k + 1]) * t);
      }
    }
    return DOSE[DOSE.length - 1].slice(1);
  }
  const RECEPTION = [[0, 130, 130, 140, 'noisy: just below copy'], [0, 235, 200, 60, 'fair'], [10, 140, 205, 80, 'good'], [20, 40, 175, 95, 'excellent']];
  function receptionColor(margin) {
    if (margin < -10) return null;
    let c = RECEPTION[0];
    if (margin >= 0) c = RECEPTION[1];
    if (margin >= 10) c = RECEPTION[2];
    if (margin >= 20) c = RECEPTION[3];
    return [c[1], c[2], c[3], margin < 0 ? 0.18 : 0.32];
  }

  /** cells: [x, z, value]; one canvas pixel per cell, stretched (and smoothed) over the map. */
  function raster(cells, step, color) {
    if (!cells.length) return null;
    let x0 = Infinity, z0 = Infinity, x1 = -Infinity, z1 = -Infinity;
    for (const c of cells) { x0 = Math.min(x0, c[0]); z0 = Math.min(z0, c[1]); x1 = Math.max(x1, c[0]); z1 = Math.max(z1, c[1]); }
    const w = (x1 - x0) / step + 1, h = (z1 - z0) / step + 1;
    if (w * h > 16e6) return null;
    const cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    const ctx = cv.getContext('2d'), img = ctx.createImageData(w, h);
    for (const c of cells) {
      const col = color(c[2]);
      if (!col) continue;
      const i = (((c[1] - z0) / step) * w + (c[0] - x0) / step) * 4;
      img.data[i] = col[0]; img.data[i + 1] = col[1]; img.data[i + 2] = col[2]; img.data[i + 3] = Math.round(col[3] * 255);
    }
    ctx.putImageData(img, 0, 0);
    return L.imageOverlay(cv.toDataURL(), L.latLngBounds(ll(x0, z0), ll(x1 + step, z1 + step)), { interactive: false, className: 'raster' });
  }

  // ------------------------------------------------------------------ layers

  async function drawRadiation() {
    ['doserate', 'sources', 'emitters', 'fallout'].forEach((g) => groups[g].clearLayers());
    if (!state.on.radiation || !state.info.layers.includes('radiation')) return;
    const [src, dose] = await Promise.all([get(`api/radiation/${state.dim}`), state.on.doserate ? get(`api/doserate/${state.dim}`) : null]);
    if (dose && dose.cells) {
      const ov = raster(dose.cells, dose.step, doseColor);
      if (ov) groups.doserate.addLayer(ov);
      state.dose = dose;
      state.doseAt = new Map(dose.cells.map((c) => [c[0] + ',' + c[1], c[2]]));
    }
    if (!src) return;
    if (state.on.sources) {
      for (const z of src.zones) {
        L.rectangle(L.latLngBounds(ll(z.minX, z.minZ), ll(z.maxX + 1, z.maxZ + 1)), { renderer: canvas, color: '#ff5050', weight: 1, dashArray: '4 4', fillOpacity: 0.08 })
          .bindTooltip(`<b>Zone ${esc(z.name)}</b><br>${fmt(z.rads)} rad/s, y ${z.minY}–${z.maxY}`).addTo(groups.sources);
      }
      for (const s of src.sources) {
        if (s.kind === 'fallout') continue;
        const cloud = s.kind === 'cloud';
        L.circle(ll(s.x, s.z), { radius: s.radius, renderer: canvas, color: cloud ? '#9aa0a8' : '#ff6040', weight: 1, dashArray: '3 5', fill: cloud, fillOpacity: 0.12 })
          .addTo(groups.sources);
        L.marker(ll(s.x, s.z), { icon: L.divIcon({ className: 'pin', html: cloud ? '☁' : '☢', iconSize: [18, 18] }) })
          .bindTooltip(`<b>${cloud ? 'Radioactive cloud' : s.kind === 'release' ? 'Open reactor core' : 'Source ' + esc(s.name)}</b><br>` +
            (cloud ? `${Math.round(s.y)} m up, ${Math.round(s.radius)} blocks around<br>` : '') + `${fmt(s.rads)} rad/s at the centre`).addTo(groups.sources);
      }
      for (const b of src.barrels) {
        L.marker(ll(b[0] + 0.5, b[2] + 0.5), { icon: L.divIcon({ className: 'pin', html: '☢', iconSize: [18, 18] }) })
          .bindTooltip(`<b>Nuclear waste barrel</b><br>${fmt(src.barrelRads)} rad/s`).addTo(groups.sources);
      }
    }
    if (state.on.fallout) {
      for (const s of src.sources) {
        if (s.kind !== 'fallout') continue;
        L.circleMarker(ll(s.x, s.z), { radius: 3, renderer: canvas, color: '#c8b040', weight: 1, fillOpacity: 0.7 })
          .bindTooltip(`<b>Fallout</b><br>${fmt(s.rads)} rad/s at the centre, ${Math.round(s.radius)} blocks`).addTo(groups.fallout);
      }
    }
    if (state.on.emitters) {
      for (const e of src.emitters) {
        L.circleMarker(ll(e[0] + 0.5, e[2] + 0.5), { radius: 2, renderer: canvas, color: '#ff3030', weight: 1, fillOpacity: 0.9 })
          .bindTooltip(`<b>${esc(nice(e[4].split(':')[1] || e[4]))}</b><br>${fmt(e[3])} rad/s at 1 m · y ${e[1]}`).addTo(groups.emitters);
      }
    }
  }

  async function drawGrid() {
    groups.grid.clearLayers();
    if (!state.on.grid || !state.info.layers.includes('grid')) return;
    const g = await get(`api/grid/${state.dim}`);
    state.grid = g;
    if (!g) return;
    for (const w of g.wires) {
      const over = w[5] > 1;
      L.polyline([ll(w[0] + 0.5, w[1] + 0.5), ll(w[2] + 0.5, w[3] + 0.5)], { renderer: canvas, color: over ? '#ff3030' : VCOLOR[w[4]], weight: w[4] >= 3 ? 3 : 2, opacity: 0.9 })
        .bindTooltip(`<b>${VOLTAGE[w[4]]} line</b><br>${w[6].toFixed(1)} A (${Math.round(w[5] * 100)} % of its rating)`).addTo(groups.grid);
    }
    for (const c of g.cables) {
      const over = c[4] > 1, busy = c[4] > 0.8;
      L.circleMarker(ll(c[0] + 0.5, c[2] + 0.5), { radius: 2, renderer: canvas, stroke: busy, color: over ? '#ff3030' : '#ffa030', weight: 2, fillColor: VCOLOR[c[3]], fillOpacity: 0.95 })
        .bindTooltip(`<b>${VOLTAGE[c[3]]} cable</b><br>load ${Math.round(c[4] * 100)} % · y ${c[1]}`).addTo(groups.grid);
    }
    for (const n of g.nodes) {
      if (n[3] === 'connector') continue;
      const icon = n[3] === 'breaker' ? (n[6] ? '⏻' : '⭘') : n[3] === 'meter' ? '◔' : /transformer/.test(n[4]) ? '⧓' : /solar/.test(n[4]) ? '☀' : /battery|lifepo/.test(n[4]) ? '▮' : '■';
      L.marker(ll(n[0] + 0.5, n[2] + 0.5), { icon: L.divIcon({ className: 'pin', html: `<span style="color:${n[5] >= 0 ? VCOLOR[n[5]] : '#ddd'};font-size:13px">${icon}</span>`, iconSize: [14, 14] }) })
        .bindTooltip(`<b>${esc(nice(n[4]))}</b>${n[3] === 'breaker' ? (n[6] ? ' (closed)' : ' (open)') : ''}<br>${n[5] >= 0 ? VOLTAGE[n[5]] + ' · ' : ''}y ${n[1]}`).addTo(groups.grid);
    }
  }

  async function drawCoverage() {
    groups.coverage.clearLayers();
    groups.stations.clearLayers();
    if (!state.on.broadcast || !state.info.layers.includes('broadcast')) return;
    const cov = await get(`api/coverage/${state.dim}`) || await get(`api/stations/${state.dim}`);
    state.coverage = cov;
    if (!cov) return;
    const sel = $('station');
    if (sel) {
      const keep = state.station;
      sel.innerHTML = '<option value="best">Best of all stations</option>' + cov.stations.map((s, i) =>
        `<option value="${i}">${esc(s.name)} · ${freq(s.hz)}</option>`).join('');
      sel.value = keep !== 'best' && keep < cov.stations.length ? keep : 'best';
    }
    const cells = new Map();
    cov.stations.forEach((s, i) => {
      if (!s.cells || (state.station !== 'best' && String(i) !== String(state.station))) return;
      for (const c of s.cells) {
        const k = c[0] + ',' + c[1], m = c[2] - s.minSnr;
        if (!cells.has(k) || cells.get(k)[2] < m) cells.set(k, [c[0], c[1], m]);
      }
    });
    const ov = raster([...cells.values()], cov.step || 32, receptionColor);
    if (ov) groups.coverage.addLayer(ov);
    // where the computation ends (reception goes on beyond)
    if (cov.range) {
      cov.stations.forEach((s, i) => {
        if (state.station !== 'best' && String(i) !== String(state.station)) return;
        L.circle(ll(s.x, s.z), { radius: cov.range, renderer: canvas, color: '#8fa0b0', weight: 1, dashArray: '6 6', fill: false, interactive: false })
          .addTo(groups.coverage);
      });
    }
    if (state.on.stations) {
      for (const s of cov.stations) {
        L.marker(ll(s.x + 0.5, s.z + 0.5), { icon: L.divIcon({ className: 'pin', html: '📡', iconSize: [20, 20] }) })
          .bindTooltip(`<b>${esc(s.name)}</b><br>${s.band} ${freq(s.hz)} ${s.mode}<br>${watts(s.power)} transmitter, ${watts(s.radiated)} radiated`).addTo(groups.stations);
      }
    }
  }

  async function drawWaypoints() {
    groups.waypoints.clearLayers();
    if (!state.on.waypoints) return;
    const wps = await get('api/waypoints') || [];
    for (const w of wps) {
      if (w.dim !== state.dim) continue;
      L.marker(ll(w.x + 0.5, w.z + 0.5), { icon: L.divIcon({ className: 'pin', html: `<span style="color:${esc(w.color)}">⚑</span>`, iconSize: [18, 18], iconAnchor: [5, 16] }) })
        .bindTooltip(esc(w.name), { permanent: true, direction: 'right', className: 'label', offset: [6, -8] })
        .on('click', () => map.setView(ll(w.x, w.z), Math.max(map.getZoom(), 1)))
        .addTo(groups.waypoints);
    }
  }

  function drawPlayers() {
    groups.players.clearLayers();
    if (!state.on.players || !state.live) return;
    for (const p of state.live.players) {
      if (p.dim !== state.dim) continue;
      L.marker(ll(p.x, p.z), { icon: L.divIcon({ className: '', html: `<div class="player"><i style="transform:rotate(${p.yaw + 180}deg)"></i></div>`, iconSize: [14, 14] }) })
        .bindTooltip(`${esc(p.name)}`, { permanent: true, direction: 'top', className: 'label', offset: [0, -8] })
        .bindPopup(`<b>${esc(p.name)}</b><br>${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)}<br>health ${p.health}`)
        .addTo(groups.players);
    }
  }

  function status() {
    const l = state.live;
    if (!l) { $('status').textContent = 'Server not reachable'; return; }
    const r = (l.render || []).find((d) => d.dim === state.dim);
    const parts = [`${clock(l.time)} · ${l.weather}`, `${l.players.length} player${l.players.length === 1 ? '' : 's'} online`];
    if (r && r.total > 0 && r.done < r.total) parts.push(`rendering ${Math.round(r.done / r.total * 100)} % (${r.done} of ${r.total} chunks)`);
    $('status').innerHTML = parts.join('<br>');
  }

  function refreshAll() {
    drawRadiation().then(legend);
    drawGrid().then(legend);
    drawCoverage().then(legend);
    drawWaypoints();
    drawPlayers();
    legend();
  }

  // ------------------------------------------------------------------ view and start

  function setDim(key, keepView) {
    state.dim = key;
    meter.pins.clearLayers();
    meter.last = null;
    $('dim').value = key;
    tiles.redraw();
    if (!keepView) {
      const d = state.info.dims.find((x) => x.key === key);
      map.setView(ll(d ? d.x : 0, d ? d.z : 0), -1);
    }
    refreshAll();
  }

  function hash() {
    const c = map.getCenter();
    history.replaceState(null, '', `#${state.dim}/${Math.round(c.lng)}/${Math.round(-c.lat)}/${map.getZoom()}`);
  }

  map.on('mousemove', (e) => { $('coords').textContent = `x ${Math.floor(e.latlng.lng)}   z ${Math.floor(-e.latlng.lat)}`; });

  // ------------------------------------------------------------------ measuring radiation under the mouse

  const meter = { on: false, want: null, busy: false, cache: new Map(), last: null, pins: L.layerGroup().addTo(map), el: $('meter') };
  const lethal = (rads, max) => {
    if (rads < 0.0005) return 'harmless';
    const s = max / rads;
    return s < 60 ? `lethal dose in ${Math.round(s)} s` : s < 3600 ? `lethal dose in ${Math.round(s / 60)} min` : s < 86400 * 3 ? `lethal dose in ${(s / 3600).toFixed(s < 36000 ? 1 : 0)} h` : 'lethal dose in days';
  };
  const swatch = (rads) => { const c = doseColor(rads); return rads < 0.002 ? '#3a424d' : `rgb(${c[0] | 0},${c[1] | 0},${c[2] | 0})`; };
  function meterHtml(m, estimate) {
    if (!m) return '';
    let h = `<div class="big"><span class="sw" style="background:${swatch(m.rads)}"></span>${estimate ? '≈ ' : ''}${fmt(m.rads)} <small>rad/s</small></div>`;
    h += `<div class="where">x ${m.x} z ${m.z}${m.ground !== undefined ? ` · ${(m.y - m.ground).toFixed(1)} m above the ground (y ${m.ground})` : ''}</div>`;
    if (estimate) return h + '<div class="where">measuring…</div>';
    h += `<div class="where">${lethal(m.rads, m.maxRads)}${m.loaded ? '' : ' · area not loaded: walls and roofs not counted'}</div>`;
    if (m.parts && m.parts.length) h += '<div class="parts">' + m.parts.map((p) => `<div><span>${esc(p[0])}</span><b>${fmt(p[1])}</b></div>`).join('') + '</div>';
    return h;
  }
  function meterShow(m, estimate, pt) {
    meter.el.innerHTML = meterHtml(m, estimate);
    if (pt) meterPlace(pt);
  }
  /** Next to the mouse, on the other side near the edges of the map. */
  function meterPlace(pt) {
    const size = map.getSize(), w = meter.el.offsetWidth || 220, h = meter.el.offsetHeight || 90;
    meter.el.style.left = (pt.x + 18 + w > size.x ? pt.x - 12 - w : pt.x + 18) + 'px';
    meter.el.style.top = (pt.y + 18 + h > size.y ? pt.y - 12 - h : pt.y + 18) + 'px';
  }
  async function meterFetch() {
    if (meter.busy || !meter.want) return;
    const w = meter.want, key = `${state.dim}/${w.x},${w.z}`;
    const hit = meter.cache.get(key);
    if (hit && Date.now() - hit.at < 3000) { meter.want = null; if (meter.last === key) meterShow(hit.m, false); return; }
    meter.busy = true; meter.want = null;
    const m = await get(`api/measure/${state.dim}?x=${w.x}&z=${w.z}`);
    meter.busy = false;
    if (m && !m.busy) {
      meter.cache.set(key, { m, at: Date.now() });
      if (meter.cache.size > 400) meter.cache.delete(meter.cache.keys().next().value);
      if (meter.last === key && meter.on) meterShow(m, false);
    } else if (meter.last === key && meter.on) {
      meter.el.innerHTML = '<div class="where">The server does not answer (paused while nobody is online?)</div>';
    }
    meterFetch();
  }
  function meterMove(e) {
    if (!meter.on) return;
    const x = Math.floor(e.latlng.lng), z = Math.floor(-e.latlng.lat), key = `${state.dim}/${x},${z}`;
    if (key === meter.last) { meterPlace(e.containerPoint); return; }
    meter.last = key;
    // at once: the cached reading or the 8-block grid, then the real measurement
    const hit = meter.cache.get(key);
    if (hit) meterShow(hit.m, false, e.containerPoint);
    else {
      const st = state.dose && state.dose.step, g = st && state.doseAt ? state.doseAt.get(Math.floor(x / st) * st + ',' + Math.floor(z / st) * st) || 0 : 0;
      meterShow({ x, z, rads: g }, true, e.containerPoint);
    }
    meter.el.hidden = false;
    meter.want = { x, z };
    meterFetch();
  }
  function meterToggle(on) {
    meter.on = on;
    meter.button.classList.toggle('active', on);
    map.getContainer().classList.toggle('measuring', on);
    meter.el.hidden = true;
    meter.last = null;
  }
  map.on('mousemove', meterMove);
  map.on('mouseout', () => { meter.el.hidden = true; meter.last = null; });
  map.on('click', (e) => {
    if (!meter.on) return;
    const key = meter.last, hit = key && meter.cache.get(key);
    if (!hit) return;
    const m = hit.m;
    L.marker(ll(m.x + 0.5, m.z + 0.5), { icon: L.divIcon({ className: 'pin', html: '✚', iconSize: [16, 16] }) })
      .bindTooltip(`<b>${fmt(m.rads)} rad/s</b> · x ${m.x} z ${m.z}<br>${lethal(m.rads, m.maxRads)}<br><small>click to remove</small>`, { permanent: true, direction: 'right', offset: [8, 0] })
      .on('click', (ev) => { L.DomEvent.stopPropagation(ev); meter.pins.removeLayer(ev.target); })
      .addTo(meter.pins);
  });
  const MeterControl = L.Control.extend({
    options: { position: 'topright' },
    onAdd() {
      const bar = L.DomUtil.create('div', 'leaflet-bar');
      const a = L.DomUtil.create('a', 'meter-btn', bar);
      a.href = '#'; a.title = 'Measure radiation under the mouse (M); click to pin a reading'; a.innerHTML = '☢';
      L.DomEvent.on(a, 'click', (ev) => { L.DomEvent.preventDefault(ev); L.DomEvent.stopPropagation(ev); meterToggle(!meter.on); });
      meter.button = a;
      return bar;
    }
  });
  document.addEventListener('keydown', (e) => {
    if (!meter.button || e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
    if (e.key === 'm' || e.key === 'M') meterToggle(!meter.on);
    if (e.key === 'Escape' && meter.on) meterToggle(false);
  });
  map.on('moveend', hash);

  async function start() {
    state.info = await get('api/info');
    if (!state.info) { $('status').textContent = 'Server not reachable'; return; }
    document.title = state.info.title;
    $('dim').innerHTML = state.info.dims.map((d) => `<option value="${d.key}">${esc(nice(d.id.split(':')[1]))}</option>`).join('');
    $('dim').addEventListener('change', () => setDim($('dim').value, false));
    buildPanel();
    if (state.info.layers.includes('radiation')) new MeterControl().addTo(map);
    const h = location.hash.slice(1).split('/');
    if (h.length === 4 && state.info.dims.some((d) => d.key === h[0])) {
      setDim(h[0], true);
      map.setView(ll(+h[1], +h[2]), +h[3]);
    } else {
      setDim(state.info.dims[0].key, false);
    }
    const tickLive = async () => { state.live = await get('api/live'); drawPlayers(); status(); };
    tickLive();
    setInterval(tickLive, 2000);
    setInterval(() => { state.rev = Date.now(); tiles.setUrl('', false); }, 30000);
    setInterval(() => { drawRadiation(); drawGrid().then(legend); drawWaypoints(); }, 10000);
    setInterval(() => drawCoverage().then(legend), 60000);
  }
  start();
})();
