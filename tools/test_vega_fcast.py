#!/usr/bin/env python3
"""FCast v2 end-to-end on VVD only: real TCP -> native receiver -> system player."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import threading
import time

COMPONENT = "com.buzincast.tv.main"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--vpkg", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("build/vega-fcast-checks"))
    parser.add_argument("--media", choices=("mp4", "hls", "dash"), default="mp4")
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--approval", action="store_true", help="Exercise the TV approval flow")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only emulator-N is allowed; never a physical TV")
    root = Path(__file__).resolve().parent.parent
    sample = (root / "vega/assets/raw/media/playback-sample.mp4").read_bytes()
    media_files = {"/clip.mp4": (sample, "video/mp4")}
    if args.media != "mp4":
        fixtures = args.fixtures or root / "build/vega-adaptive-samples"
        for path in fixtures.rglob("*"):
            if path.is_file() and not path.is_symlink():
                mime = {".m3u8": "application/x-mpegURL", ".mpd": "application/dash+xml", ".ts": "video/mp2t"}.get(path.suffix, "application/octet-stream")
                media_files["/" + path.relative_to(fixtures).as_posix()] = (path.read_bytes(), mime)
        manifest = "/hls/stream.m3u8" if args.media == "hls" else "/dash/stream.mpd"
        if manifest not in media_files:
            parser.error("Generate adaptive fixtures first")
    else:
        manifest = "/clip.mp4"
    sdk = shutil.which("vega") or str(Path.home() / "vega/bin/vega")
    if not args.vpkg.is_file():
        parser.error("Build the VVD architecture VPKG first")
    args.output.mkdir(parents=True, exist_ok=True)
    history, requests, observations = [], [], []
    result = {"serial": args.serial, "physicalValidation": False, "media": args.media, "checks": {}}
    saved_approval = None

    def run(arguments, timeout=30):
        completed = subprocess.run([sdk, *arguments], capture_output=True, text=True,
                                   timeout=timeout, env=os.environ.copy())
        history.append({"args": arguments, "exit": completed.returncode,
                        "stdout": completed.stdout, "stderr": completed.stderr})
        if completed.returncode:
            raise RuntimeError(completed.stderr or completed.stdout)
        return completed.stdout

    def shell(*command):
        return run(["exec", "vda", "-s", args.serial, "shell", *command])

    def open_receiver():
        for _ in range(3):
            shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")

    def connect_receiver(expect_approval=False):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            try:
                connected = socket.create_connection(("127.0.0.1", 46899), timeout=1)
            except OSError:
                time.sleep(0.2)
                continue
            connected.settimeout(0.8)
            try:
                # A forward can accept locally before the guest listener exists.
                if len(connected.recv(4, socket.MSG_PEEK)) == 4:
                    if expect_approval:
                        connected.close()
                        raise RuntimeError("FCast sent protocol data before approval")
                    connected.settimeout(5)
                    return connected
            except socket.timeout:
                if expect_approval:
                    connected.settimeout(5)
                    return connected
            except ConnectionResetError:
                pass
            connected.close()
            time.sleep(0.2)
        raise RuntimeError("FCast did not prepare the player/listener")

    class Media(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            if self.path not in media_files:
                self.send_error(404)
                return
            content, content_type = media_files[self.path]
            requests.append({"fixture": self.path, "customHeader": self.headers.get("X-Buzin-Test"),
                             "range": self.headers.get("Range")})
            start, end = 0, len(content) - 1
            byte_range = self.headers.get("Range")
            if byte_range:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", byte_range)
                if not match:
                    self.send_error(416)
                    return
                start = int(match[1])
                end = min(int(match[2]), end) if match[2] else end
                if start > end:
                    self.send_error(416)
                    return
            self.send_response(206 if byte_range else 200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            if byte_range:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(content)}")
            self.end_headers()
            if body:
                try:
                    self.wfile.write(content[start:end + 1])
                except (BrokenPipeError, ConnectionResetError):
                    pass

    server = ThreadingHTTPServer(("127.0.0.1", 40880), Media)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    forwards = []
    client = None
    log_stream = None
    log_output = None
    try:
        devices = run(["exec", "vda", "devices"])
        if not re.search(rf"^{re.escape(args.serial)}\s+device\b", devices, re.M):
            raise RuntimeError("The named VVD is not connected")
        running = shell("vlcm", "list", "--app-id", COMPONENT)
        if COMPONENT in running:
            shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
        run(["run-app", str(args.vpkg.resolve()), COMPONENT, "-d", args.serial], timeout=120)
        if args.approval:
            saved = shell("-c", COMPONENT, "if test -f /data/dlna-approval; then cat /data/dlna-approval; fi")
            if saved not in ("", "0", "1"):
                raise RuntimeError("Unexpected approval preference")
            saved_approval = saved
            shell("-c", COMPONENT, "printf 1 > /data/dlna-approval")
        log_output = (args.output / "receiver-stream.log").open("w")
        log_stream = subprocess.Popen(
            [sdk, "exec", "vda", "-s", args.serial, "shell", "loggingctl", "log", "-f", "-v", "com.buzincast.tv"],
            stdout=log_output, stderr=subprocess.STDOUT, env=os.environ.copy())
        for port, direction in [(46899, "true"), (40880, "false")]:
            run(["device", "start-port-forwarding", "--device", args.serial,
                 "--port", str(port), "--forward", direction])
            forwards.append((port, direction))
        open_receiver()
        if args.approval:
            with connect_receiver(expect_approval=True) as denied:
                shell("inputd-cli", "button_press", "KEY_ENTER")
                try:
                    assert denied.recv(1) == b""
                except ConnectionResetError:
                    pass
            shell("inputd-cli", "button_press", "KEY_BACK")
            # Back restores the FCast item selected in the first cycle.
            shell("inputd-cli", "button_press", "KEY_ENTER")
            client = connect_receiver(expect_approval=True)
            shell("inputd-cli", "button_press", "KEY_RIGHT")
            shell("inputd-cli", "button_press", "KEY_ENTER")
            result["checks"]["remoteDenialAndApproval"] = True
        else:
            client = connect_receiver()

        def read_exact(size):
            data = b""
            while len(data) < size:
                block = client.recv(size - len(data))
                if not block:
                    raise RuntimeError("FCast controller disconnected")
                data += block
            return data

        def receive():
            size, = struct.unpack("<I", read_exact(4))
            if not 1 <= size <= 32000:
                raise RuntimeError("Invalid receiver frame")
            packet = read_exact(size)
            body = json.loads(packet[1:]) if size > 1 else None
            if packet[0] in (6, 7):
                observations.append({"opcode": packet[0], "state": body})
                del observations[:-24]
            return packet[0], body

        def send(opcode, body=None, fragmented=False):
            payload = bytes([opcode]) + (json.dumps(body, ensure_ascii=False).encode() if body is not None else b"")
            packet = struct.pack("<I", len(payload)) + payload
            if fragmented:
                for part in [packet[:1], packet[1:3], packet[3:9], packet[9:]]:
                    client.sendall(part)
                    time.sleep(0.02)
            else:
                client.sendall(packet)

        def until(opcode, predicate=lambda _: True, timeout=12):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                op, body = receive()
                if op == 9 and opcode != 9:
                    raise RuntimeError(f"Receiver playback error: {body}")
                if op == opcode and predicate(body):
                    return body
            raise RuntimeError(f"Missing FCast opcode/state {opcode}")

        result["checks"]["version"] = until(11, lambda b: b["version"] == 2)
        send(11, {"version": 2})
        send(12)
        until(13)
        request = {"container": media_files[manifest][1], "url": "http://127.0.0.1:40880" + manifest,
                   "headers": {"X-Buzin-Test": "vega-fcast"}, "time": 1, "speed": 1}
        send(1, request, fragmented=True)
        result["checks"]["playing"] = until(6, lambda b: b["state"] == 1 and b["time"] >= 1)
        send(2)
        paused = until(6, lambda b: b["state"] == 2)
        held = until(6, lambda b: b["state"] == 2)
        if abs(held["time"] - paused["time"]) > 0.25:
            raise RuntimeError("Paused clock advanced")
        result["checks"]["paused"] = held
        send(5, {"time": 3})
        result["checks"]["seek"] = until(6, lambda b: b["state"] == 2 and abs(b["time"] - 3) < 0.4)
        send(8, {"volume": 0.35})
        result["checks"]["volume"] = until(7, lambda b: abs(b["volume"] - 0.35) < 0.01)
        send(10, {"speed": 1.25})
        send(3)
        result["checks"]["resumed"] = until(6, lambda b: b["state"] == 1 and b["time"] > 3.3 and b["speed"] == 1.25)
        for invalid in [dict(request, url="file:///data/private.mp4"),
                        dict(request, headers={"Host": "invalid"}),
                        dict(request, content="inline manifest")]:
            send(1, invalid)
            until(9, lambda b: bool(b.get("message")))
        result["checks"]["invalidRequestsRejected"] = True
        send(4)
        result["checks"]["stopped"] = until(6, lambda b: b["state"] == 0)
        if not any(entry["customHeader"] == "vega-fcast" for entry in requests):
            raise RuntimeError("Custom request headers did not reach the media server")
        result["checks"]["httpCustomHeaders"] = True
        if args.media != "mp4":
            relevant = [entry for entry in requests if entry["fixture"].startswith("/" + args.media + "/")]
            if not any(entry["fixture"] == manifest for entry in relevant):
                raise RuntimeError("Adaptive manifest was not requested")
            if not any(entry["fixture"].endswith((".ts", ".m4s")) for entry in relevant):
                raise RuntimeError("Adaptive segments were not requested")
            if any(entry["customHeader"] != "vega-fcast" for entry in relevant):
                raise RuntimeError("A manifest or segment request lost its custom header")
            result["checks"]["manifestAndSegments"] = sorted({entry["fixture"] for entry in relevant})
        shell("inputd-cli", "button_press", "KEY_BACK")
        # TCP may still contain state frames queued before Back. Drain that
        # bounded backlog and require EOF/reset within the shutdown deadline.
        deadline = time.monotonic() + 5
        drained = 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("FCast controller did not reach EOF after Back")
            client.settimeout(remaining)
            try:
                buffered = client.recv(4096)
            except ConnectionResetError:
                break
            except socket.timeout as error:
                raise RuntimeError("FCast controller stayed open after Back") from error
            if not buffered:
                break
            drained += len(buffered)
            if drained > 256 * 1024:
                raise RuntimeError("FCast kept transmitting after Back")
        result["checks"]["remoteClosedReceiver"] = True
        result["passed"] = True
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:
        result.update(passed=False, error=str(error), observations=observations,
                      mediaRequests=requests)
        try:
            result["playbackDiagnostic"] = json.loads(shell("-c", COMPONENT, "cat", "/data/playback-state.json"))
        except (RuntimeError, json.JSONDecodeError):
            result["playbackDiagnostic"] = None
        try:
            (args.output / "receiver.log").write_text(shell("loggingctl", "log", "-v", "com.buzincast.tv"))
            shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
        except Exception:
            pass
        raise
    finally:
        if saved_approval is not None:
            restore = f"printf {saved_approval} > /data/dlna-approval" if saved_approval else "rm -f /data/dlna-approval"
            shell("-c", COMPONENT, restore)
        if log_stream:
            log_stream.terminate()
            try:
                log_stream.wait(timeout=5)
            except subprocess.TimeoutExpired:
                log_stream.kill()
                log_stream.wait(timeout=5)
        if log_output:
            log_output.close()
        if client:
            client.close()
        for port, direction in reversed(forwards):
            try:
                run(["device", "stop-port-forwarding", "--device", args.serial,
                     "--port", str(port), "--forward", direction])
            except Exception:
                pass
        server.shutdown()
        server.server_close()
        (args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        (args.output / "commands.json").write_text(json.dumps(history, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
