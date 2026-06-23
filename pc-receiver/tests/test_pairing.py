from receiver.pairing import PairingManager


def test_pin_and_token_generated(tmp_path):
    pm = PairingManager(tmp_path / "pairing.json")
    assert len(pm.pin) == 6 and pm.pin.isdigit()
    assert len(pm.token) == 64  # 32 octets hex


def test_verify_accepts_pin_and_token(tmp_path):
    pm = PairingManager(tmp_path / "pairing.json")
    assert pm.verify(pm.pin) is True
    assert pm.verify(pm.token) is True
    assert pm.verify("000000") is (pm.pin == "000000")
    assert pm.verify("wrong-token") is False
    assert pm.verify(None) is False


def test_regenerate_revokes_old(tmp_path):
    pm = PairingManager(tmp_path / "pairing.json")
    old_token = pm.token
    pm.regenerate()
    assert pm.token != old_token
    assert pm.verify(old_token) is False


def test_persistence(tmp_path):
    path = tmp_path / "pairing.json"
    pm1 = PairingManager(path)
    pm2 = PairingManager(path)
    assert pm1.token == pm2.token and pm1.pin == pm2.pin
