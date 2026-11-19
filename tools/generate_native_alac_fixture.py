"""Generate original stereo PCM, losslessly encode ALAC and verify its round trip."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--frames", type=int, choices=(352, 4224), default=4224)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/native_audio")
    args = parser.parse_args()
    ffmpeg, ffprobe = shutil.which(args.ffmpeg), shutil.which(args.ffprobe)
    if not ffmpeg or not ffprobe:
        parser.error("Both FFmpeg and FFprobe must be available; use their explicit executable paths if needed")
    frames, sample_rate, channels = args.frames, 44100, 2
    pcm = b"".join(struct.pack("<hh", (i * 97) % 30001 - 15000, (i * 137 + 19) % 28001 - 14000) for i in range(frames))
    with tempfile.TemporaryDirectory(prefix="buzin-alac-") as temporary:
        directory = Path(temporary)
        source, encoded, decoded = directory / "source.wav", directory / "encoded.m4a", directory / "decoded.s16le"
        with wave.open(str(source), "wb") as output:
            output.setnchannels(channels); output.setsampwidth(2); output.setframerate(sample_rate)
            output.writeframes(pcm)
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
                        "-map_metadata", "-1", "-c:a", "alac", "-sample_fmt", "s16p", "-flags", "+bitexact", str(encoded)], check=True)
        metadata = json.loads(subprocess.check_output([ffprobe, "-v", "error", "-select_streams", "a:0", "-show_packets",
            "-show_entries", "stream=codec_name,sample_rate,channels,sample_fmt,bits_per_raw_sample:packet=pos,size,duration,pts", "-of", "json", str(encoded)], text=True))
        stream = metadata["streams"][0]
        if (stream["codec_name"], stream["sample_fmt"], int(stream["sample_rate"]), stream["channels"], int(stream["bits_per_raw_sample"])) != ("alac", "s16p", sample_rate, channels, 16):
            raise RuntimeError("Encoder did not produce the required stereo PCM16 ALAC format")
        packets = metadata["packets"]
        expected_frames = [352] if frames == 352 else [4096, 128]
        expected_pts = [0] if frames == 352 else [0, 4096]
        if [packet["duration"] for packet in packets] != expected_frames or [packet["pts"] for packet in packets] != expected_pts:
            raise RuntimeError("ALAC packet sizes/timestamps did not match the selected fixture")
        container = encoded.read_bytes()
        payloads = [container[int(packet["pos"]):int(packet["pos"]) + int(packet["size"])] for packet in packets]
        if any(len(payload) != int(packet["size"]) for payload, packet in zip(payloads, packets)):
            raise RuntimeError("Packet offsets extend beyond the encoded container")
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(encoded), "-c:a", "pcm_s16le", "-f", "s16le", str(decoded)], check=True)
        if decoded.read_bytes() != pcm:
            raise RuntimeError("Independent FFmpeg decoding did not reproduce the original PCM exactly")
        data = b"".join(struct.pack(">I", len(payload)) + payload for payload in payloads)
        manifest = {"format": "big-endian uint32 length followed by one raw ALAC packet, repeated",
            "sample_rate": sample_rate, "channels": channels, "bits": 16, "frames": frames,
            "packet_frames": [packet["duration"] for packet in packets], "packet_sizes": [len(payload) for payload in payloads],
            "pcm_sha256": hashlib.sha256(pcm).hexdigest(), "packets_sha256": hashlib.sha256(data).hexdigest(),
            "left": "(frame * 97) % 30001 - 15000", "right": "(frame * 137 + 19) % 28001 - 14000"}
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "alac16.packets").write_bytes(data)
        (args.output / "alac16.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {frames} stereo PCM16 frames in {len(packets)} ALAC packets; {len(data)} encoded bytes")


if __name__ == "__main__":
    main()
