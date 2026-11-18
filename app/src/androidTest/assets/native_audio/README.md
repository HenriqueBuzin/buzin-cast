# Original ALAC fixture

`alac16.packets` contains two raw ALAC packets, each prefixed by its big-endian
unsigned 32-bit byte length. They represent 4224 stereo PCM16 frames at 44100 Hz:
one complete 4096-frame packet and a short final 128-frame packet.
`raop352/` contains the first 352 frames as one ALAC packet, testing the receiver's
default frame size when no valid format announcement has replaced it.

The input is generated solely from these integer formulas, with no third-party
recording or downloaded media:

* Left sample: `(frame * 97) % 30001 - 15000`.
* Right sample: `(frame * 137 + 19) % 28001 - 14000`.

The differing, nonzero channels detect interleave/order errors. The short final
packet checks that an earlier decoder scratch buffer cannot add stale samples.
`alac16.json` records packet lengths and hashes of the packet file and original
little-endian PCM. The generator independently decodes its M4A intermediate with
FFmpeg and rejects any mismatch before writing the fixture.

Regenerate from the repository root:

```sh
python tools/generate_native_alac_fixture.py
python tools/generate_native_alac_fixture.py --frames 352 --output app/src/androidTest/assets/native_audio/raop352
```

Pass `--ffmpeg` and `--ffprobe` if the executables are outside `PATH`. These
fixtures belong to the instrumentation test APK, not the released app's assets.
