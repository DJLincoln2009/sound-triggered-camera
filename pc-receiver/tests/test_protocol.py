import json

import pytest

from receiver import protocol


def test_loads_requires_type():
    with pytest.raises(ValueError):
        protocol.loads(json.dumps({"foo": "bar"}))


def test_loads_rejects_non_object():
    with pytest.raises(ValueError):
        protocol.loads(json.dumps([1, 2, 3]))


def test_roundtrip_dumps_loads():
    msg = protocol.start_recording("abc", max_duration_s=10)
    parsed = protocol.loads(protocol.dumps(msg))
    assert parsed["type"] == protocol.MsgType.START_RECORDING
    assert parsed["origin"] == "manual"
    assert parsed["request_id"] == "abc"
    assert parsed["max_duration_s"] == 10


def test_stop_recording_builder():
    msg = protocol.stop_recording("xyz")
    assert msg == {"type": "STOP_RECORDING", "request_id": "xyz"}


def test_set_settings_builder_merges():
    msg = protocol.set_settings("r1", {"threshold": 0.5, "video_quality": "HD_720P"})
    assert msg["type"] == protocol.MsgType.SET_SETTINGS
    assert msg["request_id"] == "r1"
    assert msg["threshold"] == 0.5


def test_hello_ack_includes_token_only_when_accepted():
    ok = protocol.hello_ack(True, "PC", token="secret")
    assert ok["accepted"] is True and ok["token"] == "secret"
    ko = protocol.hello_ack(False, "PC", reason="unpaired", token="secret")
    assert ko["accepted"] is False and "token" not in ko
    assert ko["reason"] == "unpaired"


def test_utc_now_iso_format():
    ts = protocol.utc_now_iso()
    assert ts.endswith("Z") and "T" in ts and len(ts) == 20
