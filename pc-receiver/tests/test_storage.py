from receiver.storage import Storage


def _meta(rid="rec_1", origin="sound"):
    return {
        "recording_id": rid, "device_id": "dev1", "device_name": "Cam",
        "origin": origin, "sound_label": "Glass", "started_at": "2026-06-21T14:32:10Z",
        "duration_s": 12.4, "width": 1280, "height": 720, "mime": "video/mp4",
    }


def _make(tmp_path):
    return Storage(tmp_path / "db.sqlite3", tmp_path / "rec")


def test_add_and_list_recording(tmp_path):
    st = _make(tmp_path)
    f = st.target_path("rec_1", "2026-06-21T14:32:10Z", "mp4")
    f.write_bytes(b"x" * 100)
    st.add_recording(_meta(), f, 100)
    rows = st.list_recordings()
    assert len(rows) == 1
    assert rows[0]["recording_id"] == "rec_1"
    assert rows[0]["origin"] == "sound"
    assert "2026-06-21" in rows[0]["file_path"]


def test_recording_exists_and_idempotent(tmp_path):
    st = _make(tmp_path)
    f = st.target_path("rec_1", None, "mp4")
    f.write_bytes(b"x")
    assert st.recording_exists("rec_1") is False
    st.add_recording(_meta(), f, 1)
    assert st.recording_exists("rec_1") is True


def test_archive_and_filter(tmp_path):
    st = _make(tmp_path)
    f = st.target_path("rec_1", None, "mp4")
    f.write_bytes(b"x")
    st.add_recording(_meta(), f, 1)
    assert st.archive_recording("rec_1") is True
    assert st.list_recordings(include_archived=False) == []
    assert len(st.list_recordings(include_archived=True)) == 1


def test_delete_removes_file(tmp_path):
    st = _make(tmp_path)
    f = st.target_path("rec_1", None, "mp4")
    f.write_bytes(b"x")
    st.add_recording(_meta(), f, 1)
    assert st.delete_recording("rec_1") is True
    assert not f.exists()
    assert st.delete_recording("rec_1") is False


def test_events(tmp_path):
    st = _make(tmp_path)
    st.add_event("manual_trigger", "dev1", "ok")
    st.add_event("sound_trigger", "dev1", "Glass")
    events = st.list_events()
    assert len(events) == 2
    assert events[0]["type"] == "sound_trigger"  # ordre décroissant
