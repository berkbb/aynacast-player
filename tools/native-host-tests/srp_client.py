"""Independent SRP-6a client (AirPlay pair-setup-pin, as in pyatv/UxPlay) used to make test vectors."""
import hashlib
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

N = int("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310D"
        "CD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF74"
        "7359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D"
        "5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6"
        "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16)
g = 2
H = lambda *p: hashlib.sha1(b"".join(p)).digest()
mini = lambda n: n.to_bytes((n.bit_length() + 7) // 8 or 1, "big")
pad = lambda n: n.to_bytes(256, "big")
i = lambda b: int.from_bytes(b, "big")


def client_session(user, pin, salt, B_bytes, a_bytes):
    B = i(B_bytes)
    a = i(a_bytes)
    A = pow(g, a, N)
    k = i(H(mini(N), pad(g)))
    u = i(H(pad(A), pad(B)))
    x = i(H(salt, H(user.encode(), b":", pin.encode())))
    S = pow((B - k * pow(g, x, N)) % N, a + u * x, N)
    K = H(mini(S), b"\0\0\0\0") + H(mini(S), b"\0\0\0\1")
    hn, hg = H(mini(N)), H(mini(g))
    M1 = H(bytes(p ^ q for p, q in zip(hn, hg)), H(user.encode()), salt, mini(A), mini(B), K)
    M2 = H(mini(A), M1, K)
    return mini(A), M1, M2, K


def aes_material(K):
    key = hashlib.sha512(b"Pair-Setup-AES-Key" + K).digest()[:16]
    iv = bytearray(hashlib.sha512(b"Pair-Setup-AES-IV" + K).digest()[:16])
    return key, iv


def client_encrypt_pk(K, client_pk):
    key, iv = aes_material(K)
    iv[15] = (iv[15] + 1) & 0xFF
    sealed = AESGCM(key).encrypt(bytes(iv), client_pk, None)
    return sealed[:-16], sealed[-16:]


def client_decrypt_server_pk(K, epk, tag):
    key, iv = aes_material(K)
    iv[15] = (iv[15] + 2) & 0xFF
    return AESGCM(key).decrypt(bytes(iv), epk + tag, None)


def server_B(user, pin, salt, b_bytes):
    """Server public key, computed independently of the Kotlin code."""
    x = i(H(salt, H(user.encode(), b":", pin.encode())))
    v = pow(g, x, N)
    k = i(H(mini(N), pad(g)))
    return mini((k * v + pow(g, i(b_bytes), N)) % N)
