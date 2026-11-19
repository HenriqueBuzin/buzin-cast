#!/usr/bin/env python3
"""Exercise Vega's Cast V2 TCP/TLS receiver and system player on VVD."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import socket
import ssl
import struct
import subprocess
import threading
import time

COMPONENT = "com.buzincast.tv.main"
RECEIVER = "receiver-0"
PREFIX = "urn:x-cast:com.google.cast."
MEDIA_PORT = 40883


def encode_field(tag, value):
    if isinstance(value, int):
        return bytes([tag * 8, value])
    size = len(value)
    length = bytearray()
    while size >= 128:
        length.append((size % 128) + 128)
        size //= 128
    length.append(size)
    return bytes([tag * 8 + 2]) + length + value


class Controller:
    def __init__(self, connection):
        self.connection = connection
        self.request_id = 0

    def send(self, channel, destination, body, fragmented=False):
        binary = isinstance(body, bytes)
        values = [0, b"sender-vvd", destination.encode(), (PREFIX + channel).encode(), int(binary)]
        packet = b"".join(encode_field(i, value) for i, value in enumerate(values, 1))
        packet += encode_field(7 if binary else 6, body if binary else json.dumps(body).encode())
        frame = struct.pack(">I", len(packet)) + packet
        if fragmented:
            for chunk in (frame[:2], frame[2:13], frame[13:]):
                self.connection.sendall(chunk)
                time.sleep(0.03)
        else:
            self.connection.sendall(frame)

    def read_exact(self, size):
        data = bytearray()
        while len(data) < size:
            part = self.connection.recv(size - len(data))
            if not part:
                raise EOFError("Cast V2 disconnected")
            data.extend(part)
        return bytes(data)

    def receive(self):
        size, = struct.unpack(">I", self.read_exact(4))
        assert 0 < size <= 65536
        packet = iter(self.read_exact(size))

        def varint():
            value = 0
            for i in range(5):
                byte = next(packet)
                value |= (byte & 127) << (i * 7)
                if byte < 128:
                    return value
            raise ValueError("Invalid varint")

        fields = {}
        for tag in packet:
            value = varint()
            fields[tag >> 3] = bytes(next(packet) for _ in range(value)) if tag & 7 == 2 else value
        assert fields[3] == b"sender-vvd"
        if fields[5] == 1:
            return fields[7]
        return json.loads(fields[6])

    def until(self, predicate):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            body = self.receive()
            if predicate(body):
                return body
        raise AssertionError("Cast V2 status did not reach the expected value")

    def command(self, channel, destination, command, fragmented=False, **fields):
        self.request_id += 1
        self.send(channel, destination, dict(fields, type=command, requestId=self.request_id), fragmented)
        return self.until(lambda body: body.get("requestId") == self.request_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--vpkg", required=True, type=Path)
    parser.add_argument("--approval", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not args.vpkg.is_file():
        parser.error("Supply an emulator-N and a built Vega package")

    def vega(*command, timeout=30):
        return subprocess.check_output(["vega", *command], text=True, timeout=timeout)

    def shell(*command):
        return vega("exec", "vda", "-s", args.serial, "shell", *command)

    def component(command):
        return shell("-c", COMPONENT, command).strip()

    def key(value):
        shell("inputd-cli", "button_press", "KEY_" + value)

    devices = vega("exec", "vda", "devices")
    assert re.search(rf"^{re.escape(args.serial)}\s+device\b", devices, re.M)
    if COMPONENT in shell("vlcm", "list", "--app-id", COMPONENT):
        shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
    vega("run-app", str(args.vpkg.resolve()), COMPONENT, "-d", args.serial, timeout=120)
    preference = component("if test -f /data/dlna-approval; then cat /data/dlna-approval; fi")
    assert preference in ("", "0", "1")
    sample_dir = Path(__file__).resolve().parent.parent / "vega/assets/raw/media"
    sample = (sample_dir / "playback-sample.mp4").read_bytes()

    class MediaHandler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, has_body):
            if self.path != "/playback-sample.mp4":
                self.send_error(404)
                return
            start, end = 0, len(sample) - 1
            range_header = self.headers.get("Range")
            if range_header:
                bounds = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header)
                if not bounds:
                    self.send_error(416)
                    return
                start = int(bounds[1])
                if bounds[2]:
                    end = min(end, int(bounds[2]))
                if start > end:
                    self.send_error(416)
                    return
            self.send_response(206 if range_header else 200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            if range_header:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(sample)}")
            self.end_headers()
            if has_body:
                try:
                    self.wfile.write(sample[start:end + 1])
                except (BrokenPipeError, ConnectionResetError):
                    pass

    server = ThreadingHTTPServer(("127.0.0.1", MEDIA_PORT), MediaHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    forwards = []
    try:
        component(f"printf {int(args.approval)} > /data/dlna-approval")
        key("ENTER")
        for _ in range(5):
            key("DOWN")
        key("ENTER")
        port = None
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            report = component("if test -f /data/cast-v2-port.json; then cat /data/cast-v2-port.json; fi")
            if report:
                port = json.loads(report)["port"]
                break
            time.sleep(0.2)
        assert port, "Cast V2 listener did not appear"
        for forward in ((port, "true"), (MEDIA_PORT, "false")):
            vega("device", "start-port-forwarding", "--device", args.serial,
                 "--port", str(forward[0]), "--forward", forward[1])
            forwards.append(forward)

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        # This isolated integration test uses the receiver's local, self-signed identity.
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with socket.create_connection(("127.0.0.1", port), timeout=8) as sock, context.wrap_socket(
                sock, do_handshake_on_connect=False) as tls:
            if args.approval:
                tls.settimeout(0.8)
                try:
                    tls.do_handshake()
                except TimeoutError:
                    key("RIGHT")
                    key("ENTER")
                else:
                    raise AssertionError("Cast V2 completed TLS before TV approval")
                finally:
                    tls.settimeout(8)
            tls.do_handshake()
            assert tls.version() in ("TLSv1.2", "TLSv1.3")
            cast = Controller(tls)
            cast.send("tp.heartbeat", RECEIVER, {"type": "PING"}, fragmented=True)
            assert cast.receive() == {"type": "PONG"}
            cast.send("tp.deviceauth", RECEIVER, b"\x0a\x00")
            assert cast.receive() == b"\x1a\x02\x08\x00"
            cast.send("tp.connection", RECEIVER, {"type": "CONNECT"})
            initial = cast.command("receiver", RECEIVER, "GET_STATUS")
            assert initial["status"]["applications"] == []
            availability = cast.command("receiver", RECEIVER, "GET_APP_AVAILABILITY", appId=["CC1AD845", "invalid"])
            assert availability["availability"] == {"CC1AD845": "APP_AVAILABLE", "invalid": "APP_UNAVAILABLE"}
            launch = cast.command("receiver", RECEIVER, "LAUNCH", appId="CC1AD845")
            app = launch["status"]["applications"][0]
            transport = app["transportId"]
            cast.send("tp.connection", transport, {"type": "CONNECT"})
            media = {"contentId": f"http://127.0.0.1:{MEDIA_PORT}/playback-sample.mp4", "contentType": "video/mp4"}
            loaded = cast.command("media", transport, "LOAD", fragmented=True, media=media, autoplay=False)["status"][0]
            assert loaded["playerState"] == "PAUSED"
            session = {"mediaSessionId": loaded["mediaSessionId"]}

            def observed(state, after=0):
                return cast.until(lambda b: b.get("type") == "MEDIA_STATUS" and any(
                    item["playerState"] == state and item["currentTime"] >= after
                    for item in b["status"]))["status"][0]

            assert observed("PAUSED")["currentTime"] < 0.25
            cast.command("media", transport, "PLAY", **session)
            observed("PLAYING", 0.75)
            cast.command("media", transport, "PAUSE", **session)
            paused = observed("PAUSED")
            assert abs(observed("PAUSED")["currentTime"] - paused["currentTime"]) < 0.25
            seek = cast.command("media", transport, "SEEK", currentTime=3, **session)["status"][0]
            assert abs(seek["currentTime"] - 3) < 0.25
            volume = cast.command("receiver", RECEIVER, "SET_VOLUME", volume={"level": 0.35, "muted": True})
            assert volume["status"]["volume"] == {"level": 0.35, "muted": True}
            cast.command("media", transport, "PLAY", **session)
            observed("PLAYING", 3.5)
            invalid = cast.command("media", transport, "LOAD", media=dict(media, contentId="file:///invalid"))
            assert invalid["type"] == "INVALID_REQUEST"
            stopped = cast.command("media", transport, "STOP", **session)
            assert stopped["status"][0]["playerState"] == "IDLE"
            quit_app = cast.command("receiver", RECEIVER, "STOP", sessionId=app["sessionId"])
            assert quit_app["status"]["applications"] == []
            key("BACK")
            try:
                assert tls.recv(4096) == b""
            except ConnectionResetError:
                pass
            assert not component("if test -f /data/cast-v2-port.json; then cat /data/cast-v2-port.json; fi")
        print(json.dumps({"passed": True, "physicalValidation": False, "transport": "TLS",
                          "tvApproval": args.approval, "checks": ["heartbeat", "auth-unavailable", "launch", "load-paused",
                                     "play", "pause", "seek", "volume", "invalid-url", "stop", "back"]}))
    finally:
        for port, direction in reversed(forwards):
            vega("device", "stop-port-forwarding", "--device", args.serial,
                 "--port", str(port), "--forward", direction)
        try:
            component(f"printf {preference} > /data/dlna-approval" if preference else "rm -f /data/dlna-approval")
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
