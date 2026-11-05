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
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only emulator-N is allowed; never a physical TV")
    root = Path(__file__).resolve().parent.parent
    sample = (root / "vega/assets/raw/media/playback-sample.mp4").read_bytes()
    sdk = shutil.which("vega") or str(Path.home() / "vega/bin/vega")
    if not args.vpkg.is_file():
        parser.error("Build the VVD architecture VPKG first")
    args.output.mkdir(parents=True, exist_ok=True)
    history, requests = [], []
    result = {"serial": args.serial, "physicalValidation": False, "checks": {}}

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

    class Media(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            if self.path != "/clip.mp4":
                self.send_error(404)
                return
            requests.append({"customHeader": self.headers.get("X-Buzin-Test"),
                             "range": self.headers.get("Range")})
            start, end = 0, len(sample) - 1
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
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            if byte_range:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(sample)}")
            self.end_headers()
            if body:
                try:
                    self.wfile.write(sample[start:end + 1])
                except (BrokenPipeError, ConnectionResetError):
                    pass

    server = ThreadingHTTPServer(("127.0.0.1", 40880), Media)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    forwards = []
    client = None
    try:
        devices = run(["exec", "vda", "devices"])
        if not re.search(rf"^{re.escape(args.serial)}\s+device\b", devices, re.M):
            raise RuntimeError("The named VVD is not connected")
        running = shell("vlcm", "list", "--app-id", COMPONENT)
        if COMPONENT in running:
            shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
        run(["run-app", str(args.vpkg.resolve()), COMPONENT, "-d", args.serial], timeout=120)
        for port, direction in [(46899, "true"), (40880, "false")]:
            run(["device", "start-port-forwarding", "--device", args.serial,
                 "--port", str(port), "--forward", direction])
            forwards.append((port, direction))
        for _ in range(3):
            shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        connect_deadline = time.monotonic() + 25
        while True:
            try:
                client = socket.create_connection(("127.0.0.1", 46899), timeout=1)
                # A forward can accept locally before the guest listener exists.
                client.settimeout(1)
                first = client.recv(4, socket.MSG_PEEK)
                if len(first) == 4:
                    break
                client.close()
            except (OSError, TimeoutError):
                if client:
                    client.close()
            if time.monotonic() > connect_deadline:
                raise RuntimeError("FCast did not prepare the player/listener")
            time.sleep(0.25)
        client.settimeout(5)

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
            return packet[0], json.loads(packet[1:]) if size > 1 else None

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
        request = {"container": "video/mp4", "url": "http://127.0.0.1:40880/clip.mp4",
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
        shell("inputd-cli", "button_press", "KEY_BACK")
        # Forward remains, but the destination listener must be gone.
        try:
            client.settimeout(2)
            if client.recv(1):
                raise RuntimeError("FCast listener did not close its controller")
        except ConnectionResetError:
            pass
        result["checks"]["remoteClosedReceiver"] = True
        result["passed"] = True
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:
        result.update(passed=False, error=str(error))
        try:
            (args.output / "receiver.log").write_text(shell("loggingctl", "log", "-v", "com.buzincast.tv"))
            shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
        except Exception:
            pass
        raise
    finally:
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
