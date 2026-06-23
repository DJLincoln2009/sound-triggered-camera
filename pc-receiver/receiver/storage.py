"""Stockage local : métadonnées SQLite + fichiers vidéo sur le système de fichiers.

Implémente EF-16 (stockage horodaté + origine), EF-19 (journal d'événements),
EF-20 (suppression/archivage). Aucune donnée ne quitte la machine.
"""
from __future__ import annotations

import shutil
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


_SCHEMA = """
CREATE TABLE IF NOT EXISTS recordings (
    recording_id TEXT PRIMARY KEY,
    device_id    TEXT,
    device_name  TEXT,
    origin       TEXT NOT NULL,            -- 'sound' | 'manual'
    sound_label  TEXT,
    started_at   TEXT,                     -- ISO-8601 UTC
    received_at  TEXT NOT NULL,
    duration_s   REAL,
    width        INTEGER,
    height       INTEGER,
    size_bytes   INTEGER,
    mime         TEXT,
    file_path    TEXT NOT NULL,
    archived     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        TEXT NOT NULL,               -- ISO-8601 UTC
    type      TEXT NOT NULL,               -- sound_trigger | manual_trigger | stop |
                                           -- connected | disconnected | settings | error
    device_id TEXT,
    detail    TEXT
);
"""


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Storage:
    """Accès thread-safe à la base SQLite et au stockage fichiers."""

    def __init__(self, db_path: Path, recordings_dir: Path):
        self._db_path = db_path
        self._recordings_dir = recordings_dir
        self._lock = threading.RLock()
        db_path.parent.mkdir(parents=True, exist_ok=True)
        recordings_dir.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    # --- enregistrements ------------------------------------------------------

    def target_path(self, recording_id: str, started_at: str | None, ext: str) -> Path:
        """Chemin de stockage organisé par date (EF-16)."""
        day = (started_at or _now())[:10]
        folder = self._recordings_dir / day
        folder.mkdir(parents=True, exist_ok=True)
        return folder / f"{recording_id}.{ext.lstrip('.')}"

    def recording_exists(self, recording_id: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM recordings WHERE recording_id = ?", (recording_id,)
            ).fetchone()
            return row is not None

    def add_recording(self, meta: dict[str, Any], file_path: Path, size_bytes: int) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT OR REPLACE INTO recordings
                   (recording_id, device_id, device_name, origin, sound_label, started_at,
                    received_at, duration_s, width, height, size_bytes, mime, file_path, archived)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0)""",
                (
                    meta.get("recording_id"),
                    meta.get("device_id"),
                    meta.get("device_name"),
                    meta.get("origin", "manual"),
                    meta.get("sound_label"),
                    meta.get("started_at"),
                    _now(),
                    meta.get("duration_s"),
                    meta.get("width"),
                    meta.get("height"),
                    size_bytes,
                    meta.get("mime", "video/mp4"),
                    str(file_path),
                ),
            )
            self._conn.commit()

    def list_recordings(self, include_archived: bool = False) -> list[dict[str, Any]]:
        with self._lock:
            q = "SELECT * FROM recordings"
            if not include_archived:
                q += " WHERE archived = 0"
            q += " ORDER BY COALESCE(started_at, received_at) DESC"
            return [dict(r) for r in self._conn.execute(q).fetchall()]

    def get_recording(self, recording_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM recordings WHERE recording_id = ?", (recording_id,)
            ).fetchone()
            return dict(row) if row else None

    def archive_recording(self, recording_id: str, archived: bool = True) -> bool:
        with self._lock:
            cur = self._conn.execute(
                "UPDATE recordings SET archived = ? WHERE recording_id = ?",
                (1 if archived else 0, recording_id),
            )
            self._conn.commit()
            return cur.rowcount > 0

    def delete_recording(self, recording_id: str) -> bool:
        with self._lock:
            row = self.get_recording(recording_id)
            if not row:
                return False
            try:
                Path(row["file_path"]).unlink(missing_ok=True)
            except OSError:
                pass
            self._conn.execute("DELETE FROM recordings WHERE recording_id = ?", (recording_id,))
            self._conn.commit()
            return True

    # --- journal d'événements (EF-19) -----------------------------------------

    def add_event(self, type_: str, device_id: str | None = None, detail: str | None = None) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO events (ts, type, device_id, detail) VALUES (?,?,?,?)",
                (_now(), type_, device_id, detail),
            )
            self._conn.commit()

    def list_events(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
            return [dict(r) for r in rows]

    def close(self) -> None:
        with self._lock:
            self._conn.close()
