"""Socket-level regression tests for the native AirPlay core (see run.sh).

The script plays the Apple sender: RTSP requests, a pair-verify client built on the `cryptography`
package, an NTP timing responder, the mirroring video connection and RTP/RTCP audio.
"""
import hashlib, os, plistlib, socket, struct, subprocess, sys, threading, time

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

HERE = os.path.dirname(os.path.abspath(__file__))
HARNESS = os.environ.get("HARNESS", os.path.join(HERE, "harness"))
SERVER_LOG = os.environ.get("SERVER_LOG", os.path.join(HERE, "server.log"))
RTSP, VIDEO, AUDIO, AUDIO_CTRL = 17000, 17100, 7011, 7012
SENDER_CLOCK_AHEAD_S = 1000.0  # the simulated sender's clock is deliberately far from ours
NTP_1900_TO_1970 = 2208988800
events, lock = [], threading.Lock()
failures = []


def check(cond, msg):
    print(("PASS " if cond else "FAIL ") + msg)
    if not cond:
        failures.append(msg)


proc = subprocess.Popen([HARNESS], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=open(SERVER_LOG, "w"), text=True, bufsize=1)


def reader():
    for line in proc.stdout:
        with lock:
            events.append(line.strip())


assert proc.stdout.readline().strip() == "READY"
threading.Thread(target=reader, daemon=True).start()


def wait_event(prefix, timeout=3.0):
    end = time.time() + timeout
    while time.time() < end:
        with lock:
            for e in events:
                if e.startswith(prefix):
                    events.remove(e)
                    return e
        time.sleep(0.01)
    return None


def drain(prefix):
    """Forget queued events with this prefix (e.g. left over from a previous step)."""
    with lock:
        events[:] = [e for e in events if not e.startswith(prefix)]


def field(event, name):
    for part in event.split():
        if part.startswith(name + "="):
            return int(part.split("=", 1)[1])
    return None


def recv_response(sock):
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            return None, None, None
        buf += chunk
    head, rest = buf.split(b"\r\n\r\n", 1)
    lines = head.decode().split("\r\n")
    headers = {k.strip().lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:])}
    n = int(headers.get("content-length", "0"))
    while len(rest) < n:
        rest += sock.recv(4096)
    return lines[0], headers, rest[:n]


def request(method, uri, cseq, body=b"", ctype=None):
    h = f"{method} {uri} RTSP/1.0\r\nCSeq: {cseq}\r\n"
    if ctype:
        h += f"Content-Type: {ctype}\r\n"
    if body:
        h += f"Content-Length: {len(body)}\r\n"
    return h.encode() + b"\r\n" + body


def connect():
    s = socket.create_connection(("127.0.0.1", RTSP))
    s.settimeout(5)
    return s


def bplist(obj):
    return plistlib.dumps(obj, fmt=plistlib.FMT_BINARY)


def sender_ntp_now():
    t = time.time() + SENDER_CLOCK_AHEAD_S
    return ((int(t) + NTP_1900_TO_1970) << 32) | int((t - int(t)) * (1 << 32))


def closed_by_peer(sock):
    sock.settimeout(2)
    try:
        return sock.recv(1) == b""
    except ConnectionResetError:
        return True
    except socket.timeout:
        return False


# --- Simulated sender NTP timing server -------------------------------------------------------
ntp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ntp_sock.bind(("127.0.0.1", 0))
ntp_port = ntp_sock.getsockname()[1]
ntp_sources = []


def ntp_responder():
    while True:
        try:
            data, addr = ntp_sock.recvfrom(128)
        except OSError:
            return
        if len(data) >= 32 and data[1] == 0xD2:
            ntp_sources.append(addr)
            t2 = sender_ntp_now()
            reply = bytes([0x80, 0xD3, 0x00, 0x07]) + b"\0" * 4 + data[24:32] + struct.pack(">QQ", t2, sender_ntp_now())
            ntp_sock.sendto(reply, addr)


threading.Thread(target=ntp_responder, daemon=True).start()

# --- Simulated sender audio control (RTCP) port -----------------------------------------------
ctl = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ctl.bind(("127.0.0.1", 0))
ctl_port = ctl.getsockname()[1]


def drain_ctl():
    ctl.settimeout(0.05)
    try:
        while True:
            ctl.recvfrom(64)
    except socket.timeout:
        pass


def rtp(seq, ts, payload):
    return struct.pack(">BBHII", 0x80, 0x60, seq & 0xFFFF, ts & 0xFFFFFFFF, 0x1234) + payload


u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# 1. Header split across TCP segments
s = connect()
req = request("OPTIONS", "*", 1)
for i in range(0, len(req), 7):
    s.sendall(req[i:i + 7])
    time.sleep(0.01)
status, hdr, _ = recv_response(s)
check(status == "RTSP/1.0 200 OK" and hdr.get("cseq") == "1" and "SETUP" in hdr.get("public", ""), "split OPTIONS request answered")

# 2. Pipelined requests in one segment
s.sendall(request("OPTIONS", "*", 2) + request("OPTIONS", "*", 3))
r2, h2, _ = recv_response(s)
r3, h3, _ = recv_response(s)
check(h2 and h3 and h2.get("cseq") == "2" and h3.get("cseq") == "3", "pipelined requests both answered in order")

# 2b. Unknown RTSP method -> 501 (UxPlay behaviour); the connection stays usable
s.sendall(request("FOOBAR", "*", 4))
st, _, _ = recv_response(s)
check(st == "RTSP/1.0 501 Not Implemented", f"unknown method answered with 501 ({st})")
s.sendall(b"PUT /photo HTTP/1.1\r\nCSeq: 5\r\nContent-Length: 3\r\n\r\nabc")
st, _, _ = recv_response(s)
check(st == "HTTP/1.1 200 OK", f"unknown HTTP/1.1 request answered with 200 like UxPlay ({st})")

# 3. GET /info with XML-special characters in device name must stay valid XML
s.sendall(request("GET", "/info", 5))
_, h, body = recv_response(s)
try:
    info = plistlib.loads(body)
    check(info.get("name") == "Salon & <Mutfak> TV" and info["displays"][0]["width"] == 1920, "GET /info is a valid plist with escaped name")
    check(info.get("features") == 0x1E5A7FFFF7 and info["features"] & 0x11 == 0x11 and info["features"] & (1 << 7),
          f"GET /info advertises mirroring + audio + URL video playback ({hex(info.get('features', 0))})")
except Exception as e:
    check(False, f"GET /info plist parse: {e}")

# 3b. GET /info qualifier txtAirPlay / txtRAOP returns only the TXT record
s.sendall(request("GET", "/info", 6, bplist({"qualifier": ["txtAirPlay"]}), "application/x-apple-binary-plist"))
_, h, body = recv_response(s)
check(h.get("content-type") == "application/x-apple-binary-plist" and plistlib.loads(body) == {"txtAirPlay": b"\x05a=bcd"},
      "GET /info txtAirPlay returns the TXT record")
s.sendall(request("GET", "/info", 7, bplist({"qualifier": ["txtRAOP"]}), "application/x-apple-binary-plist"))
_, _, body = recv_response(s)
check(plistlib.loads(body) == {"txtRAOP": b"\x03x=1"}, "GET /info txtRAOP returns the RAOP TXT record")


# 3c. pair-setup + pair-verify with an independent Ed25519 / X25519 / AES-CTR client
def pair_verify(sock, cseq, tamper=False, client_ed=None):
    sock.sendall(request("POST", "/pair-setup", cseq, os.urandom(32), "application/octet-stream"))
    _, _, server_ed_pk = recv_response(sock)
    client_x = X25519PrivateKey.generate()
    client_ed = client_ed or Ed25519PrivateKey.generate()
    cx_pub = client_x.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    ced_pub = client_ed.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    sock.sendall(request("POST", "/pair-verify", cseq + 1, b"\x01\x00\x00\x00" + cx_pub + ced_pub, "application/octet-stream"))
    _, _, step1 = recv_response(sock)
    server_x_pub, enc_server_sig = step1[:32], step1[32:96]
    shared = client_x.exchange(X25519PublicKey.from_public_bytes(server_x_pub))
    key = hashlib.sha512(b"Pair-Verify-AES-Key" + shared).digest()[:16]
    iv = hashlib.sha512(b"Pair-Verify-AES-IV" + shared).digest()[:16]
    ctr = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    server_sig = ctr.update(enc_server_sig)
    try:
        Ed25519PublicKey.from_public_bytes(server_ed_pk).verify(server_sig, server_x_pub + cx_pub)
        server_ok = True
    except Exception:
        server_ok = False
    client_sig = client_ed.sign(cx_pub + server_x_pub)
    if tamper:
        client_sig = bytes([client_sig[0] ^ 1]) + client_sig[1:]
    enc_client_sig = ctr.update(client_sig)  # keystream continues after the server's 64 bytes
    sock.sendall(request("POST", "/pair-verify", cseq + 2, b"\x00\x00\x00\x00" + enc_client_sig, "application/octet-stream"))
    st, _, body = recv_response(sock)
    return server_ok, st, body


p = connect()
server_ok, st, body = pair_verify(p, 20)
check(server_ok, "server's pair-verify signature verifies with its pair-setup key")
check(st == "RTSP/1.0 200 OK" and body == b"", "valid client pair-verify signature accepted")
p.sendall(request("OPTIONS", "*", 30))
check(recv_response(p)[0] == "RTSP/1.0 200 OK", "connection stays open after successful pair-verify")
p.close()
p = connect()
_, st, _ = pair_verify(p, 40, tamper=True)
check(closed_by_peer(p), "tampered pair-verify signature closes the connection")
p.close()

def command(cmd):
    proc.stdin.write(cmd + "\n"); proc.stdin.flush()
    wait_event("ACK", 3)


# 3d. On-screen PIN pairing (UxPlay -pin): gate, the three /pair-setup-pin steps, registration
command("PIN ON")
p = connect()
unpaired = b"\x01\x00\x00\x00" + os.urandom(32) + os.urandom(32)
p.sendall(request("POST", "/pair-verify", 50, unpaired, "application/octet-stream"))
st, _, body = recv_response(p)
check(st == "RTSP/1.0 200 OK" and body == b"", "PIN mode: unpaired device gets an empty pair-verify reply")
p.sendall(request("POST", "/pair-pin-start", 51))
st, _, _ = recv_response(p)
check(st == "RTSP/1.0 200 OK" and wait_event("EVT pinstart") is not None, "pair-pin-start shows a PIN")
p.sendall(request("POST", "/pair-setup-pin", 52, bplist({"method": "pin", "user": "dev-1"}), "application/x-apple-binary-plist"))
st, h, body = recv_response(p)
step1 = plistlib.loads(body) if body else {}
check(wait_event("EVT pinsetup step=1") == "EVT pinsetup step=1 user=dev-1" and step1.get("pk") == b"\xbb" * 256
      and step1.get("salt") == b"\x5a" * 16, "pair-setup-pin step 1 returns {pk, salt}")
p.sendall(request("POST", "/pair-setup-pin", 53, bplist({"pk": os.urandom(256), "proof": b"\x09" * 20}), "application/x-apple-binary-plist"))
st, _, _ = recv_response(p)
check(st == "RTSP/1.0 470 Client Authentication Failure", f"wrong PIN proof answered with 470 ({st})")
p.sendall(request("POST", "/pair-setup-pin", 54, bplist({"pk": os.urandom(256), "proof": b"\x01" * 20}), "application/x-apple-binary-plist"))
st, _, body = recv_response(p)
check(wait_event("EVT pinsetup step=2") is not None and plistlib.loads(body) == {"proof": b"\x02" * 20}, "correct PIN proof answered with M2")
paired_key = Ed25519PrivateKey.generate()
paired_pub = paired_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
p.sendall(request("POST", "/pair-setup-pin", 55, bplist({"epk": paired_pub, "authTag": os.urandom(16)}), "application/x-apple-binary-plist"))
st, _, body = recv_response(p)
check(plistlib.loads(body) == {"epk": b"\x03" * 32, "authTag": b"\x04" * 16}, "pair-setup-pin step 3 exchanges keys")
paired = b"\x01\x00\x00\x00" + os.urandom(32) + paired_pub
p.sendall(request("POST", "/pair-verify", 56, paired, "application/octet-stream"))
st, _, body = recv_response(p)
check(len(body) == 96, f"PIN mode: the paired device passes pair-verify ({len(body)} bytes)")
p.close()

# 3e. PIN mode: a session can only be started by a connection that completed pair-verify
pin_session = bplist({"ekey": os.urandom(72), "eiv": os.urandom(16), "name": "Gate", "timingProtocol": "None",
                      "timingPort": 9})
p = connect()
p.sendall(request("SETUP", "rtsp://x/9", 60, pin_session, "application/x-apple-binary-plist"))
st, _, _ = recv_response(p)
check(st == "RTSP/1.0 470 Connection Authorization Required", f"PIN mode: session SETUP without pair-verify refused ({st})")
server_ok, st, _ = pair_verify(p, 61, client_ed=paired_key)
check(server_ok and st == "RTSP/1.0 200 OK", "PIN mode: the paired key completes pair-verify")
p.sendall(request("SETUP", "rtsp://x/9", 64, pin_session, "application/x-apple-binary-plist"))
st, _, _ = recv_response(p)
check(st == "RTSP/1.0 200 OK", f"PIN mode: verified connection may start a session ({st})")
p.sendall(request("TEARDOWN", "rtsp://x/9", 65)); recv_response(p)
p.close()
# ...and an unverified connection cannot sneak in through a stream SETUP, RECORD or the video port
p = connect()
p.sendall(request("SETUP", "rtsp://x/9", 70, bplist({"streams": [{"type": 110, "streamConnectionID": 5}]}),
                  "application/x-apple-binary-plist"))
st1, _, _ = recv_response(p)
p.sendall(request("RECORD", "rtsp://x/9", 71))
st2, _, _ = recv_response(p)
check(st1 == st2 == "RTSP/1.0 470 Connection Authorization Required" and wait_event("EVT started", 0.3) is None,
      f"PIN mode: stream SETUP / RECORD without pair-verify refused ({st1}, {st2})")
pv = socket.create_connection(("127.0.0.1", VIDEO))
check(closed_by_peer(pv), "video connection without a session is refused")
pv.close(); p.close()
command("PIN OFF")

# 4. Session SETUP (ekey/eiv/name/timing) -> video-style response, sender name captured, NTP started
session = bplist({"ekey": os.urandom(72), "eiv": os.urandom(16), "name": "Berk's MacBook", "timingPort": ntp_port,
                  "timingProtocol": "NTP", "deviceID": "11:22:33:44:55:66"})
hdr_part = request("SETUP", "rtsp://x/1", 8, session, "application/x-apple-binary-plist")
split = hdr_part.index(b"\r\n\r\n") + 4
s.sendall(hdr_part[:split]); time.sleep(0.05); s.sendall(hdr_part[split:])
_, _, body = recv_response(s)
resp = plistlib.loads(body)
check(resp["streams"][0]["type"] == 110 and resp["streams"][0]["dataPort"] == VIDEO, "session SETUP answered with video plist (body split from header)")
time.sleep(0.8)
check(len(ntp_sources) >= 2, f"receiver sends NTP timing requests to the sender ({len(ntp_sources)} so far)")
check(bool(ntp_sources) and resp.get("timingPort") == ntp_sources[0][1],
      f"SETUP timingPort ({resp.get('timingPort')}) is the port the timing requests come from")

# 5. Video stream SETUP
vsetup = bplist({"streams": [{"type": 110, "streamConnectionID": 0xF123456789ABCDEF, "timestampInfo": []}]})
s.sendall(request("SETUP", "rtsp://x/1", 9, vsetup, "application/x-apple-binary-plist"))
_, _, body = recv_response(s)
check(plistlib.loads(body)["streams"][0]["dataPort"] == VIDEO, "video SETUP answered with dataPort")

# 6. RECORD -> connected (with sender name) + started
s.sendall(request("RECORD", "rtsp://x/1", 10))
recv_response(s)
ev = wait_event("EVT connected")
check(ev is not None and "Berk's MacBook" in ev, f"RECORD reports sender name ({ev})")
check(wait_event("EVT started") is not None, "RECORD starts stream")

# 7. Audio SETUP (AAC-ELD) -> audio response + config callback
asetup = bplist({"streams": [{"type": 96, "ct": 8, "spf": 480, "sr": 44100, "controlPort": ctl_port,
                              "audioFormat": 0x1000000, "usingScreen": True}]})
s.sendall(request("SETUP", "rtsp://x/1", 11, asetup, "application/x-apple-binary-plist"))
_, _, body = recv_response(s)
ar = plistlib.loads(body)["streams"][0]
check(ar["type"] == 96 and ar["dataPort"] == AUDIO and ar["controlPort"] == AUDIO_CTRL, "audio SETUP answered with audio ports")
ev = wait_event("EVT audioconfig")
check(ev == "EVT audioconfig codec=8 sr=44100 ch=2 spf=480", f"AAC-ELD routed to Java decoder ({ev})")

# 8. RTP audio packet is forwarded encoded (no ALAC decode of AAC data)
u.sendto(rtp(1, 123456, os.urandom(200)), ("127.0.0.1", AUDIO))
ev = wait_event("EVT audiopacket")
check(ev is not None and field(ev, "size") == 200, f"AAC packet forwarded intact ({ev})")

# 8b. AAC-ELD "no data" filler packets are skipped, not handed to the decoder (and are not a loss)
u.sendto(rtp(2, 123936, bytes([0x00, 0x68, 0x34, 0x00])), ("127.0.0.1", AUDIO))
u.sendto(rtp(3, 124416, os.urandom(77)), ("127.0.0.1", AUDIO))
ev = wait_event("EVT audiopacket", 1)
check(ev is not None and field(ev, "size") == 77, f"filler packet dropped, next packet plays without a gap wait ({ev})")
drain_ctl()

# 8c. Out-of-order packets are played in sequence order
for seq, size in ((100, 100), (102, 102), (101, 101)):
    u.sendto(rtp(seq, seq * 480, os.urandom(size)), ("127.0.0.1", AUDIO))
sizes = [field(wait_event("EVT audiopacket", 1) or "", "size") for _ in range(3)]
check(sizes == [100, 101, 102], f"out-of-order audio reordered ({sizes})")
drain_ctl()

# 8d. A lost packet is requested again on the sender's control port; the retransmission fills the gap
u.sendto(rtp(103, 96000, os.urandom(120)), ("127.0.0.1", AUDIO))
u.sendto(rtp(105, 96960, os.urandom(122)), ("127.0.0.1", AUDIO))
ctl.settimeout(1)
try:
    resend, _ = ctl.recvfrom(64)
except socket.timeout:
    resend = b""
check(len(resend) == 8 and resend[:2] == b"\x80\xd5" and struct.unpack(">HH", resend[4:8]) == (104, 1),
      f"resend request for seq 104 sent to the sender ({resend.hex()})")
ctl.sendto(b"\x80\xd6\x00\x01" + rtp(104, 96480, os.urandom(121)), ("127.0.0.1", AUDIO_CTRL))
sizes = [field(wait_event("EVT audiopacket", 1) or "", "size") for _ in range(3)]
check(sizes == [120, 121, 122], f"retransmitted packet played in order ({sizes})")

# 8e. A lost packet that never comes back is skipped after a short wait (a burst costs one wait)
t0 = time.time()
u.sendto(rtp(106, 144000, os.urandom(130)), ("127.0.0.1", AUDIO))
u.sendto(rtp(146, 163200, os.urandom(170)), ("127.0.0.1", AUDIO))
sizes = [field(wait_event("EVT audiopacket", 1) or "", "size") for _ in range(2)]
check(sizes == [130, 170] and time.time() - t0 < 0.5, f"burst loss skipped after one short wait ({sizes}, {time.time() - t0:.2f}s)")
drain_ctl()

# 8f. RTCP sync maps RTP time onto the sender clock, and NTP maps that onto local time
sync_rtp = 400000
u2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
u2.sendto(struct.pack(">BBHIQI", 0x80, 0xD4, 7, sync_rtp, sender_ntp_now(), sync_rtp + 480), ("127.0.0.1", AUDIO_CTRL))
time.sleep(0.05)
u.sendto(rtp(147, sync_rtp + 44100, os.urandom(90)), ("127.0.0.1", AUDIO))
ev = wait_event("EVT audiopacket", 1)
ahead = (field(ev, "pts") - field(ev, "now")) / 1e9 if ev and field(ev, "pts") else None
check(ahead is not None and 0.85 < ahead < 1.15, f"audio timestamp lands 1 s after the sync point on the local clock ({ahead})")
drain_ctl()

# 9. Volume
s.sendall(request("SET_PARAMETER", "rtsp://x/1", 12, b"volume: -12.500000\r\n", "text/parameters"))
recv_response(s)
check(wait_event("EVT volume") == "EVT volume -12.50", "volume forwarded")
s.sendall(request("GET_PARAMETER", "rtsp://x/1", 13, b"volume\r\n", "text/parameters"))
_, _, body = recv_response(s)
check(body.startswith(b"volume: -12.5"), "GET_PARAMETER returns current volume")

# 9b. Now-playing information: DMAP metadata (UTF-8), cover art, image/none and progress
def dmap(tag, payload):
    return tag.encode() + struct.pack(">I", len(payload)) + payload


meta = dmap("mlit", dmap("minm", "Şarkı 🎵".encode()) + dmap("asar", "Sezen Aksu".encode()) +
            dmap("asal", "Albüm".encode()) + dmap("caps", b"\x01"))
s.sendall(request("SET_PARAMETER", "rtsp://x/1", 30, meta, "application/x-dmap-tagged"))
recv_response(s)
check(wait_event("EVT metadata") == "EVT metadata title=Şarkı 🎵|artist=Sezen Aksu|album=Albüm", "DMAP track metadata parsed")
s.sendall(request("SET_PARAMETER", "rtsp://x/1", 31, b"\xff\xd8" + os.urandom(5000), "image/jpeg"))
recv_response(s)
check(wait_event("EVT coverart") == "EVT coverart size=5002", "cover art forwarded")
s.sendall(request("SET_PARAMETER", "rtsp://x/1", 32, b"", "image/none"))
recv_response(s)
check(wait_event("EVT coverart") == "EVT coverart size=0", "image/none clears the cover art")
start = 0xFFFFFFFF - 44100  # wraps past 2^32 during the track
s.sendall(request("SET_PARAMETER", "rtsp://x/1", 33,
                  f"progress: {start}/{(start + 441000) % 2**32}/{(start + 44100 * 200) % 2**32}\r\n".encode(), "text/parameters"))
recv_response(s)
check(wait_event("EVT progress") == "EVT progress pos=10000 dur=200000", "progress converted to ms across RTP wraparound")
s.sendall(request("SET_PARAMETER", "rtsp://x/1", 34, dmap("mlit", dmap("minm", b"x") + b"asar\xff\xff\xff\xff"), "application/x-dmap-tagged"))
st, _, _ = recv_response(s)
check(st == "RTSP/1.0 200 OK" and wait_event("EVT metadata") == "EVT metadata title=x|artist=|album=", "truncated DMAP item handled safely")

# 10. Audio-only TEARDOWN keeps the video session
s.sendall(request("TEARDOWN", "rtsp://x/1", 14, bplist({"streams": [{"type": 96}]}), "application/x-apple-binary-plist"))
recv_response(s)
check(wait_event("EVT audioconfig") == "EVT audioconfig codec=-1 sr=0 ch=0 spf=0", "audio-only TEARDOWN stops audio")
check(wait_event("EVT stopped", 0.5) is None and wait_event("EVT videostopped", 0.1) is None, "audio-only TEARDOWN keeps the mirroring session")

# 11. ALAC path with hostile payloads must not crash (ASan)
s.sendall(request("SETUP", "rtsp://x/1", 15, bplist({"streams": [{"type": 96, "ct": 2, "spf": 352, "sr": 44100, "controlPort": ctl_port}]}),
                  "application/x-apple-binary-plist"))
recv_response(s)
check(wait_event("EVT audioconfig") == "EVT audioconfig codec=0 sr=44100 ch=2 spf=352", "ALAC decoded natively, delivered as PCM")
for i in range(300):
    u.sendto(rtp(1000 + i, i * 352, os.urandom(1 + (i * 37) % 1400)), ("127.0.0.1", AUDIO))
time.sleep(0.5)
check(proc.poll() is None, "garbage ALAC packets did not crash the server")
drain_ctl()

# 11b. Unknown compression type is muted instead of being decoded as ALAC/AAC
s.sendall(request("SETUP", "rtsp://x/1", 16, bplist({"streams": [{"type": 96, "ct": 1, "spf": 352, "controlPort": ctl_port}]}),
                  "application/x-apple-binary-plist"))
_, _, body = recv_response(s)
check(plistlib.loads(body)["streams"][0]["type"] == 96, "unsupported-codec audio SETUP still answered")
check(wait_event("EVT audioconfig") == "EVT audioconfig codec=-1 sr=0 ch=0 spf=0", "unsupported audio codec (ct=1) is muted")
time.sleep(0.3)
drain("EVT audiopacket")
u.sendto(rtp(9, 9, os.urandom(300)), ("127.0.0.1", AUDIO))
check(wait_event("EVT audiopacket", 0.5) is None, "no packets forwarded for muted audio")

# 11c. A tiny ALAC frame size (the predictor needs 32 warm-up samples) falls back to the default
s.sendall(request("SETUP", "rtsp://x/1", 18, bplist({"streams": [{"type": 96, "ct": 2, "spf": 4, "sr": 44100, "controlPort": ctl_port}]}),
                  "application/x-apple-binary-plist"))
recv_response(s)
check(wait_event("EVT audioconfig") == "EVT audioconfig codec=0 sr=44100 ch=2 spf=352", "ALAC spf=4 replaced by 352")
for i in range(200):
    # 0x20 0x00 ...: one channel, compressed, then a predictor header with large coefficient counts
    u.sendto(rtp(3000 + i, i * 352, bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x1F, 0xFF]) + os.urandom(1 + (i * 13) % 900)), ("127.0.0.1", AUDIO))
time.sleep(0.4)
check(proc.poll() is None, "hostile ALAC frames with a tiny frame size did not crash the server")
drain_ctl()
drain("EVT audiopacket")

# 12. Video connection: codec config + AVCC frame, with sender timestamps mapped to local time
v = socket.create_connection(("127.0.0.1", VIDEO))
sps = bytes([0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78, 0x02, 0x27, 0xE5, 0x84])
pps = bytes([0x68, 0xEB, 0xE3, 0xCB, 0x22, 0xC0])
avcc = bytes([1, 0x64, 0, 0x28, 0xFF, 0xE1]) + struct.pack(">H", len(sps)) + sps + bytes([1]) + struct.pack(">H", len(pps)) + pps


def vpacket(ptype, payload, ntp=0):
    # Mirroring timestamps omit the 1900->1970 seconds that the timing protocol includes.
    raw = (ntp - (NTP_1900_TO_1970 << 32)) if ntp else 0
    return struct.pack("<IBB", len(payload), ptype, 0) + b"\0\0" + struct.pack("<Q", raw) + b"\0" * 112 + payload


v.sendall(vpacket(1, avcc, sender_ntp_now()))
check(wait_event("EVT spspps") == f"EVT spspps sps={len(sps)} pps={len(pps)}", "SPS/PPS extracted from avcC")
idr = bytes([0x65]) + os.urandom(500)
v.sendall(vpacket(0, struct.pack(">I", len(idr)) + idr, sender_ntp_now()))
ev = wait_event("EVT frame")
check(ev is not None and field(ev, "size") == len(idr) + 4 and field(ev, "key") == 1, "AVCC IDR converted to Annex-B keyframe")
lag = (field(ev, "now") - field(ev, "pts")) / 1e6 if ev and field(ev, "pts") else None
check(lag is not None and -50 < lag < 100, f"video timestamp mapped onto the local clock via NTP (frame age {lag} ms)")
v.sendall(vpacket(0, struct.pack(">I", len(idr)) + idr, sender_ntp_now() + (1 << 32)))
ev = wait_event("EVT frame")
ahead = (field(ev, "pts") - field(ev, "now")) / 1e9 if ev and field(ev, "pts") else None
check(ahead is not None and 0.85 < ahead < 1.15, f"a frame stamped 1 s later maps 1 s ahead ({ahead})")

# 13. A second video connection replaces the first
v2 = socket.create_connection(("127.0.0.1", VIDEO))
check(closed_by_peer(v), "previous video connection is closed when a new one arrives")

# 13b. Video-only TEARDOWN stops the mirrored video but keeps the session (UxPlay semantics)
s.sendall(request("TEARDOWN", "rtsp://x/1", 17, bplist({"streams": [{"type": 110}]}), "application/x-apple-binary-plist"))
recv_response(s)
check(wait_event("EVT videostopped") is not None, "video-only TEARDOWN reports the video stream stopped")
check(wait_event("EVT stopped", 0.3) is None, "video-only TEARDOWN keeps the session")
check(closed_by_peer(v2), "video-only TEARDOWN closes the mirroring connection")

# 14. Control connection drops without TEARDOWN -> session ends
s.close()
check(wait_event("EVT stopped") is not None, "dropped control connection ends the session")

# 15. New session + explicit TEARDOWN
s = connect()
s.sendall(request("RECORD", "rtsp://x/2", 1)); recv_response(s)
check(wait_event("EVT started") is not None, "second session starts")
check(wait_event("EVT connected") is not None, "second session connected event")
s.sendall(request("TEARDOWN", "rtsp://x/2", 2)); recv_response(s)
check(wait_event("EVT stopped") is not None, "TEARDOWN ends the session")
s.close()

# 15b. A sender that stays connected but stops sending /feedback is timed out (4 s in this build)
s = connect()
s.sendall(request("RECORD", "rtsp://x/5", 1)); recv_response(s)
check(wait_event("EVT started") is not None, "watchdog session starts")
wait_event("EVT connected")
for i in range(3):  # regular feedback keeps the session alive
    time.sleep(1.5)
    s.sendall(request("POST", "/feedback", 2 + i)); recv_response(s)
check(wait_event("EVT stopped", 0.1) is None, "periodic /feedback keeps the session alive")
check(wait_event("EVT stopped", 8) is not None, "silent sender is timed out by the feedback watchdog")
s.close()

# 15b2. One sender owns the session: another connection can neither change nor end it (409),
# but a new session SETUP takes over (the newest sender wins, like an Apple TV)
a = connect()
utf16_session = bplist({"ekey": os.urandom(72), "eiv": os.urandom(16), "name": "Berk’s iPhone 🎵 Ağ",
                        "timingPort": ntp_port, "timingProtocol": "NTP"})  # non-ASCII: stored as UTF-16
a.sendall(request("SETUP", "rtsp://x/6", 1, utf16_session, "application/x-apple-binary-plist")); recv_response(a)
a.sendall(request("RECORD", "rtsp://x/6", 2)); recv_response(a)
check(wait_event("EVT started") is not None, "ownership: first sender streams")
ev = wait_event("EVT connected")
check(ev is not None and "name=Berk’s iPhone 🎵 Ağ " in ev, f"UTF-16 sender name decoded to UTF-8 ({ev})")
drain("EVT audioconfig")  # left over from the watchdog session ending
b = connect()
b.sendall(request("TEARDOWN", "rtsp://x/6", 1)); st, _, _ = recv_response(b)
check(st == "RTSP/1.0 409 Conflict" and wait_event("EVT stopped", 0.5) is None, f"another connection cannot end the session ({st})")
b.sendall(request("SETUP", "rtsp://x/6", 2, asetup, "application/x-apple-binary-plist")); st, _, _ = recv_response(b)
check(st == "RTSP/1.0 409 Conflict" and wait_event("EVT audioconfig", 0.3) is None, f"another connection cannot set up streams ({st})")
b.sendall(request("SET_PARAMETER", "rtsp://x/6", 3, b"volume: -3.000000\r\n", "text/parameters")); st, _, _ = recv_response(b)
check(st == "RTSP/1.0 409 Conflict" and wait_event("EVT volume", 0.3) is None, f"another connection cannot change the volume ({st})")
a.sendall(request("POST", "/feedback", 3)); st, _, _ = recv_response(a)
check(st == "RTSP/1.0 200 OK", "the owner keeps its session")
b.sendall(request("SETUP", "rtsp://x/7", 4, session, "application/x-apple-binary-plist")); st, _, _ = recv_response(b)
check(st == "RTSP/1.0 200 OK" and wait_event("EVT stopped") is not None and closed_by_peer(a),
      f"a new sender's session SETUP takes over and disconnects the previous one ({st})")
b.sendall(request("RECORD", "rtsp://x/7", 5)); recv_response(b)
check(wait_event("EVT started") is not None, "the new sender streams after taking over")
wait_event("EVT connected")
b.sendall(request("TEARDOWN", "rtsp://x/7", 6)); recv_response(b)
check(wait_event("EVT stopped") is not None, "the new owner can end its session")
a.close(); b.close()

# 15b3. Rapid takeovers and disconnects from several connections at once (NTP start/stop race)
def churn(seconds):
    end = time.time() + seconds
    n = 0
    while time.time() < end:
        try:
            x = connect()
            x.sendall(request("SETUP", "rtsp://x/8", 1, session, "application/x-apple-binary-plist"))
            recv_response(x)
            x.sendall(request("TEARDOWN", "rtsp://x/8", 2))
            recv_response(x)
            x.close()
            n += 1
        except OSError:
            pass
    return n

workers = [threading.Thread(target=churn, args=(2.0,)) for _ in range(3)]
for w in workers: w.start()
for w in workers: w.join()
time.sleep(0.3)
check(proc.poll() is None, "concurrent session takeovers and teardowns do not crash the server")
drain("EVT")

# 15b4. Silent connections that own nothing are closed (5 s in this build); open connections are capped
idle = connect()
idle.sendall(request("OPTIONS", "*", 1)); recv_response(idle)
check(closed_by_peer(idle) is False, "idle connection still open after 2 s")
time.sleep(4)
check(closed_by_peer(idle), "idle connection closed after the idle timeout")
idle.close()
flood = []
for _ in range(40):
    try:
        flood.append(connect())
    except OSError:
        break
time.sleep(0.3)


def closed_now(sock):
    sock.settimeout(0.1)
    try:
        return sock.recv(1) == b""
    except ConnectionResetError:
        return True
    except socket.timeout:
        return False


refused = sum(1 for x in flood if closed_now(x))
check(refused >= 5, f"connections beyond the cap are refused ({refused} of {len(flood)})")
for x in flood: x.close()
time.sleep(1.5)
x = connect()
x.sendall(request("OPTIONS", "*", 1))
check(recv_response(x)[0] == "RTSP/1.0 200 OK", "server accepts connections again after the flood")
x.close()

# 15c. AirPlay video casting (HTTP/1.1 on the same port, UxPlay HLS semantics)
SID = "6F9A1B2C-0000-4000-8000-ABCDEF012345"


def http(sock, method, path, body=b"", ctype=None, sid=SID, extra=""):
    h = f"{method} {path} HTTP/1.1\r\nUser-Agent: AppleCoreMedia/1.0\r\n"
    if sid:
        h += f"X-Apple-Session-ID: {sid}\r\n"
    if ctype:
        h += f"Content-Type: {ctype}\r\n"
    h += f"Content-Length: {len(body)}\r\n{extra}\r\n"
    sock.sendall(h.encode() + body)
    return recv_response(sock)


def read_event(sock):
    """Reads one POST /event the receiver sends on the reverse connection and acknowledges it."""
    sock.settimeout(3)
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            return None, None
        buf += chunk
    head, rest = buf.split(b"\r\n\r\n", 1)
    n = int([l.split(b":")[1] for l in head.split(b"\r\n") if l.lower().startswith(b"content-length")][0])
    while len(rest) < n:
        rest += sock.recv(4096)
    sock.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
    return head.decode(), plistlib.loads(rest[:n])


c = connect()
st, h, body = http(c, "GET", "/server-info")
check(st == "HTTP/1.1 200 OK" and plistlib.loads(body).get("features") == 0x27F, "GET /server-info answered over HTTP/1.1")
rev = connect()
rev.sendall(f"POST /reverse HTTP/1.1\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\nX-Apple-Purpose: event\r\n"
            f"Content-Length: 0\r\nX-Apple-Session-ID: {SID}\r\n\r\n".encode())
rhead = b""
while b"\r\n\r\n" not in rhead:
    rhead += rev.recv(1)
check(rhead.startswith(b"HTTP/1.1 101 Switching Protocols") and b"Upgrade: PTTH/1.0" in rhead and b"Content-Length" not in rhead,
      "POST /reverse switches to PTTH (101)")

st, _, _ = http(c, "POST", "/play", bplist({"Content-Location": "https://media.example/v.mp4", "Start-Position-Seconds": 12.5,
                                            "uuid": "U1"}), "application/x-apple-binary-plist")
check(st == "HTTP/1.1 200 OK" and wait_event("EVT castplay") == "EVT castplay url=https://media.example/v.mp4 start=12.50",
      "direct URL /play starts the player")
st, _, body = http(c, "GET", "/playback-info")
check(st == "HTTP/1.1 200 OK" and body == b"", "playback-info is empty until the media is ready")
command("CASTSTATE 2 100 10 30 1 1")
st, _, body = http(c, "GET", "/playback-info")
pi = plistlib.loads(body)
check(pi["duration"] == 100 and pi["position"] == 10 and pi["rate"] == 1 and pi["readyToPlay"] == 1
      and pi["loadedTimeRanges"][0] == {"start": 10.0, "duration": 20.0}, "playback-info reports player state")
http(c, "POST", "/scrub?position=42.5")
check(wait_event("EVT castseek") == "EVT castseek 42.50", "POST /scrub seeks")
st, _, body = http(c, "GET", "/scrub")
check(body == b"duration: 100.000000\nposition: 10.000000\n", "GET /scrub reports duration and position")
http(c, "POST", "/rate?value=0.000000")
check(wait_event("EVT castrate") == "EVT castrate 0.00", "POST /rate?value=0 pauses")
st, _, body = http(c, "PUT", "/setProperty?forwardEndTime", bplist({"value": {"epoch": 0}}), "application/x-apple-binary-plist")
check(plistlib.loads(body) == {"errorCode": 0}, "known setProperty answered with errorCode 0")
st, _, _ = http(c, "POST", "/fp-setup2")
check(st == "HTTP/1.1 421 Misdirected Request", "FairPlay video (fp-setup2) refused with 421")

# YouTube: playlists come from the sender through FCUP requests on the reverse connection
master = ("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",LANGUAGE=\"en\",DEFAULT=YES,"
          "URI=\"mlhls://localhost/itag/140/audio.m3u8\"\n"
          "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"a\"\nmlhls://localhost/itag/136/video.m3u8?x=1\n")
audio_pl = ("#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"https://rr1.googlevideo.com/videoplayback\",PARAMS=\"sq,lmt\",PREFIX=\"s/\"\n"
            "#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\ns/0/111\n#EXTINF:5.0,\ns/1/111\n#EXT-X-ENDLIST\n")
video_pl = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\nhttps://cdn.example/seg0.ts\n#EXT-X-ENDLIST\n"
st, _, _ = http(c, "POST", "/play", bplist({"Content-Location": "mlhls://localhost/master.m3u8", "Start-Position-Seconds": 5.0,
                                            "uuid": "U2", "clientProcName": "YouTube"}), "application/x-apple-binary-plist")
head, fcup = read_event(rev)
check(head is not None and head.startswith("POST /event HTTP/1.1") and fcup["type"] == "unhandledURLRequest"
      and fcup["request"]["FCUP_Response_URL"] == "mlhls://localhost/master.m3u8"
      and fcup["request"]["FCUP_Response_Headers"]["X-Playback-Session-Id"] == SID,
      "YouTube /play requests the master playlist through FCUP")
fetched = []
for data in (master, audio_pl, video_pl):
    rid = fcup["request"]["FCUP_Response_RequestID"]
    url = fcup["request"]["FCUP_Response_URL"]
    fetched.append(url)
    st, _, _ = http(c, "POST", "/action", bplist({"type": "unhandledURLResponse", "params": {
        "FCUP_Response_Data": data.encode(), "FCUP_Response_RequestID": rid, "FCUP_Response_StatusCode": 0,
        "FCUP_Response_URL": url}}), "application/x-apple-binary-plist")
    if data is not video_pl:
        head, fcup = read_event(rev)
check(fetched == ["mlhls://localhost/master.m3u8", "mlhls://localhost/itag/140/audio.m3u8",
                  "mlhls://localhost/itag/136/video.m3u8"], f"media playlists fetched in master order ({fetched})")
check(wait_event("EVT castplay") == "EVT castplay url=http://localhost:17000/master.m3u8 start=5.00",
      "player starts on the receiver's own master playlist")
hls = connect()
st, h, body = http(hls, "GET", "/master.m3u8", sid=None, extra="Host: localhost:17000\r\n")
check(st == "HTTP/1.1 200 OK" and h.get("content-type", "").startswith("application/x-mpegURL")
      and b'URI="http://localhost:17000/itag/140/audio.m3u8"' in body and b"\nhttp://localhost:17000/itag/136/video.m3u8?x=1\n" in body
      and b"mlhls://" not in body, "master playlist rewritten to localhost")
st, _, body = http(hls, "GET", "/itag/140/audio.m3u8", sid=None, extra="Host: localhost:17000\r\n")
check(b"\nhttps://rr1.googlevideo.com/videoplayback/sq/0/lmt/111\n" in body and b"\nhttps://rr1.googlevideo.com/videoplayback/sq/1/lmt/111\n" in body
      and b"#YT-EXT-CONDENSED-URL" in body, "YouTube condensed segment URLs expanded")
st, _, body = http(hls, "GET", "/itag/136/video.m3u8?x=1", sid=None, extra="Host: localhost:17000\r\n")
check(body.decode() == video_pl, "media playlist found despite a query string")
st, _, _ = http(hls, "GET", "/itag/999/none.m3u8", sid=None, extra="Host: localhost:17000\r\n")
check(st == "HTTP/1.1 404 Not Found", "unknown playlist answered with 404")
hls.close()

# Playback ends on the TV (end of media / Back): stopped event, then the session is closed
command("CASTSTATE 2 100 50 60 1 1")
command("CASTSTATE 0 0 0 0 0 0")
head, ev = read_event(rev)
check(ev == {"category": "video", "sessionID": 1, "state": "stopped"}, f"receiver-side stop sends the stopped event ({ev})")
check(closed_by_peer(rev) and closed_by_peer(c), "receiver-side stop closes the casting connections")
check(wait_event("EVT caststop", 1) is None, "no stop loop back to the player after a local stop")

# The sender stops: no event is sent back
c = connect()
rev = connect()
rev.sendall(f"POST /reverse HTTP/1.1\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\nContent-Length: 0\r\n"
            f"X-Apple-Session-ID: {SID}\r\n\r\n".encode())
rhead = b""
while b"\r\n\r\n" not in rhead:
    rhead += rev.recv(1)
http(c, "POST", "/play", bplist({"Content-Location": "http://media.example/a.m3u8", "uuid": "U3"}), "application/x-apple-binary-plist")
wait_event("EVT castplay")
command("CASTSTATE 2 60 1 5 1 1")
st, _, _ = http(c, "POST", "/stop")
check(st == "HTTP/1.1 200 OK" and wait_event("EVT caststop") is not None, "sender /stop stops the player")
command("CASTSTATE 0 0 0 0 0 0")
rev.settimeout(0.5)
try:
    unexpected = rev.recv(1)
except socket.timeout:
    unexpected = None
check(unexpected is None, "no stopped event echoed for a sender-initiated stop")
c.close(); rev.close()
check(wait_event("EVT caststop", 1) is None, "closing an already-stopped casting session does not stop again")


def open_reverse():
    r = connect()
    r.sendall(f"POST /reverse HTTP/1.1\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\nContent-Length: 0\r\n"
              f"X-Apple-Session-ID: {SID}\r\n\r\n".encode())
    hd = b""
    while b"\r\n\r\n" not in hd:
        hd += r.recv(1)
    return r


def fcup_reply(sock, rid, url, data=None, status=0):  # real senders report success as 0
    params = {"FCUP_Response_RequestID": rid, "FCUP_Response_StatusCode": status, "FCUP_Response_URL": url}
    if data is not None:
        params["FCUP_Response_Data"] = data.encode()
    return http(sock, "POST", "/action", bplist({"type": "unhandledURLResponse", "params": params}),
                "application/x-apple-binary-plist")[0]


# /play can arrive before the reverse connection: the FCUP request is sent once it exists
c = connect()
http(c, "POST", "/play", bplist({"Content-Location": "mlhls://localhost/master.m3u8", "Start-Position-Seconds": 1.0,
                                 "uuid": "U4"}), "application/x-apple-binary-plist")
rev = open_reverse()
head, fcup = read_event(rev)
check(fcup is not None and fcup["request"]["FCUP_Response_URL"] == "mlhls://localhost/master.m3u8",
      "FCUP request sent when the reverse connection arrives after /play")
stale_rid = fcup["request"]["FCUP_Response_RequestID"]
# The user picks another video before the sender answered: the late reply must not be used
http(c, "POST", "/play", bplist({"Content-Location": "mlhls://localhost/master.m3u8", "uuid": "U5"}),
     "application/x-apple-binary-plist")
head, fcup = read_event(rev)
rid = fcup["request"]["FCUP_Response_RequestID"]
check(rid > stale_rid, f"FCUP request IDs keep increasing across videos ({stale_rid} -> {rid})")
st = fcup_reply(c, stale_rid, "mlhls://localhost/master.m3u8", master)
check(st == "HTTP/1.1 400 Bad Request" and wait_event("EVT castplay", 0.3) is None, f"a late reply for the previous video is ignored ({st})")
# /scrub and /rate while the playlists are still being fetched apply when playback starts
http(c, "POST", "/scrub?position=30.0")
http(c, "POST", "/rate?value=1.000000")
check(wait_event("EVT castseek", 0.3) is None and wait_event("EVT castrate", 0.1) is None, "scrub/rate held while fetching")
fcup_reply(c, rid, "mlhls://localhost/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/v.m3u8\n")
check(wait_event("EVT castplay") == "EVT castplay url=http://localhost:17000/master.m3u8 start=30.00"
      and wait_event("EVT castrate") == "EVT castrate 1.00", "held scrub/rate applied when playback starts")
# The sender cannot provide the master playlist: it is told playback stopped instead of hanging
http(c, "POST", "/play", bplist({"Content-Location": "mlhls://localhost/master.m3u8", "uuid": "U6"}),
     "application/x-apple-binary-plist")
head, fcup = read_event(rev)
fcup_reply(c, fcup["request"]["FCUP_Response_RequestID"], "mlhls://localhost/master.m3u8", status=404)
head, ev = read_event(rev)
check(ev == {"category": "video", "sessionID": 1, "state": "stopped"}, f"missing master playlist ends the cast with a stopped event ({ev})")
check(closed_by_peer(c) and closed_by_peer(rev), "the failed cast's connections are closed")
c.close(); rev.close()

# 16. Malformed / hostile plists do not crash
s = connect()
for i, junk in enumerate([b"bplist00" + os.urandom(64), b"bplist00" + b"\xd1" * 200 + b"\0" * 32, os.urandom(300)]):
    s.sendall(request("SETUP", "rtsp://x/3", 100 + i, junk, "application/x-apple-binary-plist"))
    st, _, _ = recv_response(s)
    check(st == "RTSP/1.0 200 OK", f"malformed SETUP #{i} answered without crashing")


def raw_bplist(objects, ref_size=1, offset_size=4):
    """Binary plist from already-encoded objects; object 0 is the top object."""
    out, offsets = b"bplist00", []
    for o in objects:
        offsets.append(len(out))
        out += o
    table = len(out)
    out += b"".join(off.to_bytes(offset_size, "big") for off in offsets)
    return out + b"\0" * 6 + bytes([offset_size, ref_size]) + struct.pack(">QQQ", len(objects), 0, table)


hostile = {
    # UTF-16 string whose length * 2 wraps around 64 bits
    "utf16 length overflow": raw_bplist([b"\x6f\x13" + struct.pack(">Q", 0x8000000000000001)]),
    # 8000 references to one 64 KB blob (512 MB if every reference were copied)
    "shared-reference blowup": raw_bplist([b"\xaf\x11" + struct.pack(">H", 8000) + struct.pack(">H", 1) * 8000,
                                           b"\x4f\x12" + struct.pack(">I", 65536) + b"\0" * 65536], ref_size=2),
    # An array claiming 1,000,000 elements
    "huge element count": raw_bplist([b"\xaf\x12" + struct.pack(">I", 1000000) + b"\0" * 1000000]),
}
for name, body in hostile.items():
    t0 = time.time()
    s.sendall(request("SETUP", "rtsp://x/3", 110, body, "application/x-apple-binary-plist"))
    st, _, _ = recv_response(s)
    check(st == "RTSP/1.0 200 OK" and time.time() - t0 < 2, f"hostile plist ({name}) rejected quickly ({time.time() - t0:.2f}s)")

# 16b. FairPlay key message with an out-of-range mode byte is refused (playfair indexes 4 tables with it)
fp = bytearray(os.urandom(164))
fp[4], fp[12] = 3, 0xFF
s.sendall(request("POST", "/fp-setup", 120, bytes(fp), "application/octet-stream"))
st, _, _ = recv_response(s)
check(st == "RTSP/1.0 400 Bad Request", f"fp-setup with mode byte 0xFF refused ({st})")
s.sendall(request("SETUP", "rtsp://x/3", 121, bplist({"ekey": os.urandom(72), "eiv": os.urandom(16), "timingProtocol": "None"}),
                  "application/x-apple-binary-plist"))
check(recv_response(s)[0] == "RTSP/1.0 200 OK", "SETUP after a refused fp-setup is handled safely")
s.sendall(request("TEARDOWN", "rtsp://x/3", 122)); recv_response(s)

# 17. stop() with live, idle connections (RTSP + NTP) must return promptly and cleanly
idle_rtsp = connect()
idle_rtsp.sendall(request("SETUP", "rtsp://x/4", 1, session, "application/x-apple-binary-plist")); recv_response(idle_rtsp)
idle_rtsp.sendall(request("RECORD", "rtsp://x/4", 2)); recv_response(idle_rtsp)
time.sleep(0.2)
proc.stdin.write("STOP\n"); proc.stdin.flush()
ev = wait_event("STOPPED", 10)
check(ev is not None and int(ev.split()[1]) < 2000, f"stop() with open connections returns promptly ({ev})")
check(wait_event("EVT stopped") is not None, "stop() ends the active session")
proc.wait(10)
check(proc.returncode == 0, f"harness exited cleanly (rc={proc.returncode})")

log = open(SERVER_LOG).read()
own_ub = [l for l in log.splitlines() if "runtime error" in l and "tweetnacl.c" not in l and "alac.c" not in l]
check("ERROR: AddressSanitizer" not in log and not own_ub, f"no ASan reports and no UBSan in project code {own_ub[:3]}")
print("\nFAILURES:", len(failures))
sys.exit(1 if failures else 0)
