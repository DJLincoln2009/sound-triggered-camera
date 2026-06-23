"use strict";

// Interface de pilotage de l'application de réception.
// Communique avec le backend Flask par API REST + polling d'état.

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

let state = { cameras: [], settings: {}, pairing_pin: "" };
let selectedRecording = null;

// --- Onglets -----------------------------------------------------------------
$$(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    $$(".tab").forEach((t) => t.classList.remove("active"));
    $$(".panel").forEach((p) => p.classList.remove("active"));
    tab.classList.add("active");
    $("#tab-" + tab.dataset.tab).classList.add("active");
    if (tab.dataset.tab === "library") loadRecordings();
    if (tab.dataset.tab === "events") loadEvents();
  });
});

// --- Utilitaires -------------------------------------------------------------
async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...opts,
  });
  let body = null;
  try { body = await res.json(); } catch (_) {}
  return { ok: res.ok, status: res.status, body };
}

function fmtBytes(n) {
  if (!n) return "—";
  const u = ["o", "Ko", "Mo", "Go"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return n.toFixed(i ? 1 : 0) + " " + u[i];
}
function fmtDuration(s) {
  if (s == null) return "—";
  const m = Math.floor(s / 60), sec = Math.round(s % 60);
  return m + ":" + String(sec).padStart(2, "0");
}
function fmtDate(iso) { return iso ? iso.slice(0, 10) : "—"; }
function fmtTime(iso) { return iso ? iso.slice(11, 19) : "—"; }

// --- Polling de l'état (EF-13, ENF-10) --------------------------------------
async function pollState() {
  const { ok, body } = await api("/api/state");
  if (ok && body) {
    state = body;
    renderPairing();
    renderCameras();
  }
}

function renderPairing() { $("#pairing-pin").textContent = state.pairing_pin || "––––––"; }

function renderCameras() {
  const ul = $("#cameras");
  const empty = $("#cameras-empty");
  const select = $("#target-camera");
  ul.innerHTML = "";
  empty.style.display = state.cameras.length ? "none" : "block";

  const prev = select.value;
  select.innerHTML = "";
  state.cameras.forEach((c) => {
    const cls = c.recording ? "recording" : c.online ? "online" : "offline";
    const label = c.recording ? "Enregistrement" : c.online ? "En ligne" : "Hors-ligne";
    const battery = c.battery_level != null ? ` · batterie ${Math.round(c.battery_level * 100)}%` : "";
    const li = document.createElement("li");
    li.innerHTML = `
      <span class="dot ${cls}"></span>
      <div>
        <div class="cam-name">${escapeHtml(c.name)}</div>
        <div class="cam-meta">${escapeHtml(c.platform || "?")} · ${escapeHtml(c.model || "")}${battery}</div>
      </div>
      <span class="badge ${cls}">${label}</span>`;
    ul.appendChild(li);

    const opt = document.createElement("option");
    opt.value = c.device_id;
    opt.textContent = c.name + (c.online ? "" : " (hors-ligne)");
    select.appendChild(opt);
  });
  if (prev) select.value = prev;
}

function escapeHtml(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// --- Contrôle à distance (EF-14, EF-15, ENF-09, ENF-04) ----------------------
function feedback(el, msg, kind) {
  el.textContent = msg;
  el.className = "hint " + (kind || "");
}

$("#btn-trigger").addEventListener("click", async () => {
  const btn = $("#btn-trigger");
  const fb = $("#control-feedback");
  btn.classList.add("loading"); btn.disabled = true;
  feedback(fb, "Envoi de la commande…");
  const device_id = $("#target-camera").value || null;
  const { ok, body } = await api("/api/trigger", {
    method: "POST", body: JSON.stringify({ device_id }),
  });
  btn.classList.remove("loading"); btn.disabled = false;
  if (ok && body.ok) feedback(fb, `Enregistrement démarré (ACK reçu, id ${body.recording_id || "?"})`, "ok");
  else feedback(fb, "Échec : " + (body && body.reason ? body.reason : "commande non acquittée"), "err");
});

$("#btn-stop").addEventListener("click", async () => {
  const btn = $("#btn-stop");
  const fb = $("#control-feedback");
  btn.classList.add("loading"); btn.disabled = true;
  feedback(fb, "Envoi de l'arrêt…");
  const device_id = $("#target-camera").value || null;
  const { ok, body } = await api("/api/stop", {
    method: "POST", body: JSON.stringify({ device_id }),
  });
  btn.classList.remove("loading"); btn.disabled = false;
  if (ok && body.ok) feedback(fb, "Arrêt confirmé (ACK reçu).", "ok");
  else feedback(fb, "Échec : " + (body && body.reason ? body.reason : "commande non acquittée"), "err");
});

// --- Bibliothèque (EF-16, EF-17, EF-20) -------------------------------------
async function loadRecordings() {
  const archived = $("#filter-archived").checked ? "1" : "0";
  const { ok, body } = await api(`/api/recordings?archived=${archived}`);
  if (!ok) return;
  const origin = $("#filter-origin").value;
  const rows = body.filter((r) => !origin || r.origin === origin);
  renderRecordings(rows);
  renderRecent(body.slice(0, 6));
}

function renderRecordings(rows) {
  const tbody = $("#rec-rows");
  tbody.innerHTML = "";
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="6" class="empty">Aucun enregistrement.</td></tr>`;
    return;
  }
  rows.forEach((r) => {
    const tr = document.createElement("tr");
    tr.dataset.id = r.recording_id;
    tr.innerHTML = `
      <td>${fmtDate(r.started_at || r.received_at)}</td>
      <td>${fmtTime(r.started_at || r.received_at)}</td>
      <td><span class="origin ${r.origin}">${r.origin === "sound" ? "Sonore" : "Manuel"}</span></td>
      <td>${fmtDuration(r.duration_s)}</td>
      <td>${fmtBytes(r.size_bytes)}</td>
      <td class="row-actions">
        <button data-act="play">▶</button>
        <button data-act="archive">${r.archived ? "Désarchiver" : "Archiver"}</button>
        <button data-act="delete">🗑</button>
      </td>`;
    tr.addEventListener("dblclick", () => playRecording(r));
    tr.querySelector('[data-act="play"]').addEventListener("click", (e) => { e.stopPropagation(); playRecording(r); });
    tr.querySelector('[data-act="archive"]').addEventListener("click", async (e) => {
      e.stopPropagation();
      await api(`/api/recordings/${r.recording_id}/archive`, {
        method: "POST", body: JSON.stringify({ archived: !r.archived }),
      });
      loadRecordings();
    });
    tr.querySelector('[data-act="delete"]').addEventListener("click", async (e) => {
      e.stopPropagation();
      if (!confirm("Supprimer définitivement cet enregistrement ?")) return;
      await api(`/api/recordings/${r.recording_id}`, { method: "DELETE" });
      loadRecordings();
    });
    tbody.appendChild(tr);
  });
}

function renderRecent(rows) {
  const ul = $("#recent");
  ul.innerHTML = "";
  if (!rows.length) { ul.innerHTML = `<li class="empty">Aucun enregistrement reçu.</li>`; return; }
  rows.forEach((r) => {
    const li = document.createElement("li");
    li.innerHTML = `
      <span class="origin ${r.origin}">${r.origin === "sound" ? "Sonore" : "Manuel"}</span>
      <span>${fmtDate(r.started_at || r.received_at)} ${fmtTime(r.started_at || r.received_at)}</span>
      <span style="margin-left:auto">${fmtDuration(r.duration_s)} · ${fmtBytes(r.size_bytes)}</span>`;
    li.style.cursor = "pointer";
    li.addEventListener("click", () => playRecording(r));
    ul.appendChild(li);
  });
}

function playRecording(r) {
  selectedRecording = r.recording_id;
  $$(".rec-table tr").forEach((tr) => tr.classList.toggle("selected", tr.dataset.id === r.recording_id));
  const v = $("#player");
  v.src = `/api/recordings/${r.recording_id}/file`;
  v.play().catch(() => {});
  $("#player-meta").textContent =
    `${r.origin === "sound" ? "Déclenchement sonore" : "Déclenchement manuel"} · ` +
    `${fmtDate(r.started_at)} ${fmtTime(r.started_at)} · ${fmtDuration(r.duration_s)} · ` +
    `${r.width || "?"}×${r.height || "?"} · ${escapeHtml(r.device_name || r.device_id || "")}`;
  $$(".tab").forEach((t) => t.classList.remove("active"));
  $$(".panel").forEach((p) => p.classList.remove("active"));
  document.querySelector('.tab[data-tab="library"]').classList.add("active");
  $("#tab-library").classList.add("active");
}

$("#filter-origin").addEventListener("change", loadRecordings);
$("#filter-archived").addEventListener("change", loadRecordings);

// --- Réglages (EF-18) --------------------------------------------------------
function fillSettingsForm() {
  const s = state.settings || {};
  if (s.threshold != null) { $("#threshold").value = s.threshold; $("#threshold-out").textContent = s.threshold; }
  if (s.video_quality) $("#video_quality").value = s.video_quality;
  $("#use_classifier").checked = !!s.use_classifier;
  if (Array.isArray(s.target_labels)) $("#target_labels").value = s.target_labels.join(", ");
  const ah = s.active_hours || {};
  $("#ah_enabled").checked = !!ah.enabled;
  if (ah.start) $("#ah_start").value = ah.start;
  if (ah.end) $("#ah_end").value = ah.end;
}

$("#threshold").addEventListener("input", (e) => { $("#threshold-out").textContent = e.target.value; });

$("#settings-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const payload = {
    threshold: parseFloat($("#threshold").value),
    video_quality: $("#video_quality").value,
    use_classifier: $("#use_classifier").checked,
    target_labels: $("#target_labels").value.split(",").map((s) => s.trim()).filter(Boolean),
    active_hours: {
      enabled: $("#ah_enabled").checked,
      start: $("#ah_start").value,
      end: $("#ah_end").value,
    },
  };
  const fb = $("#settings-feedback");
  feedback(fb, "Application en cours…");
  const { ok, body } = await api("/api/settings", { method: "POST", body: JSON.stringify(payload) });
  if (ok) {
    const push = body.push || {};
    if (push.ok) feedback(fb, "Réglages enregistrés et appliqués sur la caméra (ACK reçu).", "ok");
    else feedback(fb, "Réglages enregistrés. Poussés à la prochaine connexion caméra.", "ok");
  } else feedback(fb, "Échec de l'enregistrement des réglages.", "err");
});

// --- Journal (EF-19) ---------------------------------------------------------
async function loadEvents() {
  const { ok, body } = await api("/api/events?limit=200");
  if (!ok) return;
  const tbody = $("#event-rows");
  tbody.innerHTML = "";
  if (!body.length) { tbody.innerHTML = `<tr><td colspan="4" class="empty">Aucun événement.</td></tr>`; return; }
  body.forEach((ev) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${escapeHtml(ev.ts)}</td><td>${escapeHtml(ev.type)}</td>
      <td>${escapeHtml(ev.device_id || "—")}</td><td>${escapeHtml(ev.detail || "")}</td>`;
    tbody.appendChild(tr);
  });
}

// --- Appairage (ENF-06) ------------------------------------------------------
$("#btn-regen").addEventListener("click", async () => {
  if (!confirm("Régénérer le code d'appairage ? Les caméras déjà appairées devront être ré-appairées.")) return;
  const { ok, body } = await api("/api/pairing/regenerate", { method: "POST" });
  if (ok) { state.pairing_pin = body.pairing_pin; renderPairing(); }
});

// --- Démarrage ---------------------------------------------------------------
(async function init() {
  await pollState();
  fillSettingsForm();
  loadRecordings();
  setInterval(pollState, 1500);
})();
