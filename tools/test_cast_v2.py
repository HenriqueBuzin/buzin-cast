"""Optional hardware smoke test using an independent Cast sender.

Install PyChromecast (tested with 13.1.0), enable experimental Cast V2 in Buzin,
then: python test_cast_v2.py HOST PORT http://YOUR-PC:PORT/silent-test.mp4
Use a synthetic, silent HTTP(S) fixture. This does not validate Google device
authentication, Google apps, DRM, mirroring, or automatic discovery.
"""
import sys
import time
import uuid
import socket
import ssl
import struct
import urllib.request
import pychromecast
from pychromecast import cast_channel_pb2


def until(check, description, timeout=15):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if check():
            print("PASS " + description, flush=True)
            return
        time.sleep(0.2)
    raise AssertionError(description)


def run(host, port, media_url):
    with urllib.request.urlopen(urllib.request.Request(media_url, method="HEAD"), timeout=5) as response:
        assert response.status == 200, "Fixture server must be running"
    info = pychromecast.CastInfo(
        [pychromecast.ServiceInfo(pychromecast.SERVICE_TYPE_HOST, (host, int(port)))],
        uuid.uuid4(), "Buzin Cast Experimental", "Buzin Cast", host, int(port), "cast", "Buzin Cast",
    )
    cast = pychromecast.Chromecast(info, tries=1, timeout=5, retry_wait=1)
    try:
        cast.wait(timeout=10)
        until(lambda: cast.status is not None, "TLS connection and receiver status")
        mc = cast.media_controller
        previous_media_id = mc.status.media_session_id
        mc.play_media(media_url, "video/mp4", current_time=3)
        until(lambda: mc.status.media_session_id != previous_media_id and
              mc.status.player_state == "PLAYING" and mc.status.current_time > 3.5,
              "launch default media receiver, load and playback")
        mc.pause()
        until(lambda: mc.status.player_state == "PAUSED", "pause")
        mc.seek(18)
        until(lambda: mc.status.current_time >= 17, "seek")
        cast.set_volume(0.3)
        until(lambda: abs(cast.status.volume_level - 0.3) < 0.01, "volume")
        cast.set_volume_muted(True)
        until(lambda: cast.status.volume_muted, "mute")
        mc.play()
        until(lambda: mc.status.player_state == "PLAYING", "resume")
        mc.stop()
        until(lambda: mc.status.player_state == "IDLE", "idle state after stop")
        cast.quit_app()
        until(lambda: cast.status.app_id is None, "stop and quit receiver app")
    finally:
        cast.disconnect()


def check_auth_unavailable(host, port):
    # Isolated negative test: the local TLS certificate is self-signed. This
    # intentionally does not assert Google's device identity or certificate trust.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, int(port)), timeout=5) as tcp:
        with context.wrap_socket(tcp, server_hostname=host) as peer:
            challenge = cast_channel_pb2.DeviceAuthMessage()
            challenge.challenge.SetInParent()
            frame = cast_channel_pb2.CastMessage(
                protocol_version=0, source_id="sender-0", destination_id="receiver-0",
                namespace="urn:x-cast:com.google.cast.tp.deviceauth", payload_type=1,
                payload_binary=challenge.SerializeToString(),
            ).SerializeToString()
            peer.sendall(struct.pack(">I", len(frame)) + frame)

            def exact(size):
                data = b""
                while len(data) < size:
                    part = peer.recv(size - len(data))
                    if not part:
                        raise AssertionError("Truncated auth response")
                    data += part
                return data

            size = struct.unpack(">I", exact(4))[0]
            assert 0 < size <= 65536
            response = cast_channel_pb2.CastMessage.FromString(exact(size))
            auth = cast_channel_pb2.DeviceAuthMessage.FromString(response.payload_binary)
            assert auth.HasField("error") and not auth.HasField("response")
            print("PASS Google device authentication explicitly unavailable", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: test_cast_v2.py HOST PORT SILENT_MEDIA_URL")
    run(*sys.argv[1:])
    time.sleep(0.6)  # Receiver intentionally throttles repeated TLS handshakes.
    check_auth_unavailable(*sys.argv[1:3])
