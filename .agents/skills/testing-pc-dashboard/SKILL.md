---
name: testing-pc-dashboard
description: Test the sound-triggered-camera PC receiver dashboard end-to-end (pairing, manual trigger, deferred upload, and optional WebRTC live streaming) without a real phone, using the bundled camera simulator. Use when verifying PC app or live-streaming changes.
---

# Testing the PC receiver dashboard

The PC app (`pc-receiver/`) is the only module runnable on a Linux VM. Android/iOS need
real devices/macOS, so test them via build only (`./gradlew assembleDebug`). The bundled
`scripts/camera_simulator.py` is the reference protocol client and stands in for a phone.

## Setup

```bash
cd pc-receiver
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt        # app deps
pip install -r requirements-dev.txt    # pytest + aiortc/numpy (needed for --live)
```

## Run the server

```bash
python -m receiver --port 8766 --name "Test PC" --data-dir /tmp/soundcam-test
```
The console prints the dashboard URL and the 6-digit pairing PIN. Dashboard:
`http://localhost:8766`. State/PIN also via `GET /api/state`.

Tip: a stale browser tab from a previous session can show old data (old server name /
camera). Always reload (`Ctrl+R`) and confirm the header shows your `--name` before testing.

## Pair the simulated camera

```bash
cd scripts
python -u camera_simulator.py --pin <PIN> --url ws://127.0.0.1:8766
# add --upload sample.mp4 to auto-upload a video on STOP (tests deferred transfer)
# add --live to act as a WebRTC sender (animated test pattern) for live streaming
```
The camera then shows « En ligne » in the dashboard. Run unbuffered (`python -u`) so prints
flush when piped to a log file.

## Core flows to verify (via the UI)

- Pairing: correct PIN → camera « En ligne »; wrong PIN → rejected.
- Manual trigger / stop: « Déclencher » → green ACK + « Enregistrement » badge; « Arrêter »
  → ACK; with `--upload`, the video appears in « Bibliothèque » and plays.

## Live streaming (WebRTC, optional, off by default)

Architecture: the **browser** is the WebRTC receiver, the **PC only relays signaling**
(`/signal` WebSocket ↔ camera `/ws`), the **camera is the sender**. 100% local:
`iceServers: []` everywhere (no STUN/TURN). State `live_enabled` persists in
`<data-dir>/live.json`, default OFF.

To test (requires the simulator started with `--live`):
1. Dashboard → « Tableau de bord » → card « Contrôle à distance ».
2. Precondition: checkbox « Activer la diffusion en direct (WebRTC) » is unchecked and the
   « Voir en direct » button is disabled (proves opt-in default-off).
3. Check the box → button becomes enabled (`POST /api/live {enabled:true}`).
4. Click « Voir en direct » → within a few seconds a « Diffusion en direct » card appears
   showing live animated video and status « En direct. ». The `--live` simulator renders a
   moving white square + shifting colors — take two screenshots ~2s apart to prove the
   feed is live (square position changes), not a frozen frame.
5. « Arrêter le direct » closes the card.
6. « Journal d'événements » logs `live` events (activated / session started).

If live video never appears: check the server is reachable, the simulator was started with
`--live` (aiortc installed), and that `live_enabled` is true. Reset state via
`curl -X POST http://127.0.0.1:8766/api/live -H 'Content-Type: application/json' -d '{"enabled":false}'`.

## Unit tests & build

```bash
cd pc-receiver && . .venv/bin/activate && python -m pytest -q   # includes live signaling tests
cd android && ./gradlew assembleDebug                            # APK incl. native WebRTC libs
```

## Devin Secrets Needed

None — everything runs locally on the VM (100% local, no cloud credentials).
