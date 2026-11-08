"""Independent reference writer for the K2Pass vault format (see vault-core VaultCrypto.kt).
Used only to create test/fixture.vault so the JS reader is checked against a second implementation.
pip install argon2-cffi cryptography"""
import base64, json, os
from argon2.low_level import hash_secret_raw, Type
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASSWORD = "Correct-Horse-9!"
m, t, p = 65536, 3, 2
salt = bytes(range(16))                       # fixed so the fixture is reproducible
b64 = lambda b: base64.b64encode(b).decode()
aad = f"1|{m}|{t}|{p}|{b64(salt)}".encode()
kek = hash_secret_raw(PASSWORD.encode(), salt, time_cost=t, memory_cost=m, parallelism=p, hash_len=32, type=Type.ID)
vault_key = bytes(range(100, 132))
def enc(key, pt, iv):
    return iv + AESGCM(key).encrypt(iv, pt, aad)
entries = {"entries": [
    {"id": "1", "title": "GitHub", "user": "ibrahim", "pass": "pw-github", "url": "https://github.com", "notes": "", "totp": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "ts": 10, "del": False, "cat": "f:F1", "type": "login", "fav": True,
     "extra": {"urls": "androidapp://com.github.android\nhttps://gist.github.com"}},
    {"id": "2", "title": "Old", "user": "x", "pass": "y", "url": "https://old.example", "notes": "", "ts": 5, "del": False, "cat": "other", "type": "login", "fav": False, "trash": 99},
    {"id": "3", "title": "Router", "user": "admin", "pass": "r0ut3r", "url": "", "notes": "الدور الثاني", "ts": 7, "del": False, "cat": "f:F1", "type": "router", "fav": False, "extra": {"host": "192.168.1.1"}},
    {"id": "F1", "title": "شغل", "user": "", "pass": "", "url": "", "notes": "", "ts": 3, "del": False, "cat": "other", "type": "folder", "fav": False},
]}
out = {"v": 1, "m": m, "t": t, "p": p, "salt": b64(salt),
       "wk": b64(enc(kek, vault_key, bytes([1]*12))),
       "data": b64(enc(vault_key, json.dumps(entries, ensure_ascii=False).encode(), bytes([2]*12)))}
open(os.path.join(os.path.dirname(__file__), "fixture.vault"), "w").write(json.dumps(out))
print("fixture written; password:", PASSWORD)
