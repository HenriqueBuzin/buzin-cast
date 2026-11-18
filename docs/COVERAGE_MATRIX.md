# Current Android coverage matrix

The acceptance target is 100% of every applicable measured metric for each of
the four current APK variants independently. A passing unit-test module does not
establish whole-app coverage. Vega and the API17 frontend are separate targets.

The variants are `normal`, `compat`, `deat`, and `compat-deat`. The regional
variants use the DE/AT package identity and must reject recording and download.
Compatibility builds have API22 as their minimum; modern builds require API25.

## Run all four

Start an isolated Android x86_64 emulator with API25 or newer. Supply its serial
explicitly; physical devices are rejected before any installation or UI action.
Use the repository JDK, SDK, NDK and POSIX build tools as for a normal Android
build. On Windows, set `JAVA_HOME` and pass the POSIX tools directory when the
default `bash` points to WSL rather than the native build environment.

```powershell
python tools/run_android_coverage.py --serial emulator-5558 --bash-dir C:/msys64/usr/bin
```

On Linux or macOS, omit `--bash-dir`. `--sdk-root` overrides SDK environment
variables. The script copies the tracked **public** Appstore verification key
into an ignored debug fixture directory. It does not load regional signing
credentials, validate Amazon DRM, sign production APKs, or submit a release.

The command builds and lints each edition, removes any previous installation of
that package on the emulator, installs its debug APK and test APK,
runs native tests with UBSan, runs Android instrumentation and all UI/media
scenarios including extended playback tests, and writes separate JaCoCo/LLVM
reports. Runtime assertions fail immediately; insufficient coverage is reported
as `target_met: false` while the remaining selected variants are measured.

Select a subset by repeating `--variant`:

```powershell
python tools/run_android_coverage.py --serial emulator-5558 --variant deat --variant compat-deat --bash-dir C:/msys64/usr/bin
```

Use an API22 emulator with only compatibility variants to exercise their minimum
platform. Also test compatibility APKs on newer APIs to cover platform-dependent
behavior. Use `--output build/coverage-api22` to keep a separate report directory.
Each completed variant also preserves raw execution data in a unique snapshot
with APK, test APK, class-file and native-library fingerprints. Testing fails if
those compiled inputs change before the report finishes. Separate API runs
produce separate reports; percentages must never be averaged.

To combine API runs, use the `raw_snapshot` paths recorded in their matrix JSON
files (relative to each run's report directory). Replace the two snapshot names
below with those directories:

```powershell
python tools/merge_android_coverage.py --snapshot SNAPSHOT_API22 --snapshot SNAPSHOT_API28 --output build/coverage-merged/compat --require-100
```

The merger requires the same edition and compiled fingerprints, successful
instrumentation/native/extended UI results, and raw Java/native profiles. It
also checks the compiled mappings before and after reporting. New snapshots
archive the APKs, class trees, unstripped native library, source trees and JVM
execution data before installing the app. Switching build variants therefore
cannot remove the mappings needed to replay a completed run. Older snapshots
without these archives still require the matching live build outputs. JaCoCo
and LLVM recalculate coverage from the union of execution data. Each edition
is merged independently, and incomplete coverage still fails `--require-100`.

## Read and enforce results

`build/android-coverage-matrix/matrix.json` contains the API, selected variants,
APK hashes, all measured counters, and a target result per variant. Each variant
directory retains build/runtime logs, Android XML, native JSON and the detailed
summary. Runtime screenshots and raw profiles remain under
`build/emulator-checks[-legacy][-nodownload]`.

The core and protocol JVM modules enforce 100% of their six metrics through
`:receiver-core:check` and `:dlna-domain:check`. Full-app acceptance additionally
requires every Android/JVM and native metric to be present, with nonempty
mapping inventories. Missing branch or MC/DC metrics cannot count as success.

`all_four_target_met` is true only when all four variants were measured in that
run and every reported counter is fully covered. Files from earlier runs are not
used to fill missing variants. Add `--require-100` for a failing exit status when
any selected variant misses the target. LLVM MC/DC, branches, regions, functions,
instantiations and lines are all included; Android/JVM reports instructions,
branches, lines, complexity, methods and classes.

New runs fingerprint the mapped production sources before compilation and check
them again before and after archiving. A source edit during that interval fails
the run instead of pairing edited text with an intermediate APK. Snapshots record
the source fingerprint alongside the compiled-input identities; the merge tool
rejects changes to those archived sources.

These are debug-instrumented execution results. They complement physical-device
testing and store-distributed license checks; they do not prove those checks ran.

The Android contracts include boot opt-in and broadcast filtering, settings links,
platform local-network permission checks, and safe JNI calls before initialization.
Relay contracts use real local sockets and encrypted frames for pairing, audio,
video, keepalives, malformed input, stream takeover and session teardown. The
paired-relay test allows 45 seconds for two real PBKDF2 derivations on software
emulators; it still requires successful authentication and delivered video.

DACP contracts supply discovery callbacks and use real HTTP sender fixtures.
They check stale and invalid resolution results, sender changes, all remote
actions, transport failures, and commands queued across a sender change. Each
command retains its resolved endpoint and token; reset and sender updates
invalidate earlier discovery callbacks.

Player contracts check the public Media3 playlist, metadata, artwork, duration,
position and available commands on its application looper. A real loopback HTTP
sender verifies play/pause and both next/previous command forms, including the
Active-Remote header. Missing or unresolved senders must not confirm a playback
change; unresolved commands retain their failed future.

Download publication contracts use isolated files and, on API29+, a private
in-process ContentProvider instead of the system download collection. They check
exact copied bytes, pending/complete metadata, missing or unwritable destinations,
null/empty provider query results, and idle cancellation. These utility contracts
do not enable download controls in the DE/AT edition or test an entire network
export through Transformer.

SOAP metadata contracts cover escaping, inherited and rebound namespaces,
default-namespace resets, raw XML fragments and already escaped argument text.
The protocol JVM tests use an independent DOM parser to check the reconstructed
XML; instrumented contracts also exercise the Android XML provider.

Native server contracts create real JNI contexts and verify advertised feature
bits and codecs, PIN metadata, occupied-port recovery, RTSP replies, direct-buffer
bounds and audio start/stop. Temporary pairing keys stay in the emulator app's
cache and are deleted after teardown.

AudioRenderer contracts check detached configuration, gain, codec labels and
diagnostic-buffer parsing against a real engine. Queued configuration changes
must leave the current buffer setting intact until the next decoded packet;
multiple queued updates apply the last value, with exact PCM and timestamps
preserved across the rebuild.

The software-AAC contract decodes checked-in silent AAC packets through the JNI
audio engine and checks every PCM block, timestamp, sample rate and channel
count. It also changes configuration between packets, toggles PCM capture and
requires the decoder latency callback. Independent FFmpeg decoding supplies the
fixture's sample-count and silence expectations. Metadata contracts check nested
DMAP containers, unsigned integer widths, Unicode, truncated messages, duplicate
tags, fallback track fields and preservation of existing artwork.

The platform-AAC regression uses real MediaCodec output, checks PCM and timestamps
on the native drain thread, and verifies capture shutdown and rejected-packet
counters with both latency configurations. A silent tail releases decoder
lookahead before checking the original fixture. On API21/22 the factory uses the
default AAC decoder by MIME and FFmpeg for ALAC, avoiding optional-codec creation
through the unsafe Lollipop NDK wrapper. The historical implementation is in
[AOSP Android 5.1](https://android.googlesource.com/platform/frameworks/av/+/android-5.1.1_r38/media/ndk/NdkMediaCodec.cpp);
the null-codec creation check appears in
[AOSP Android 6.0](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/media/ndk/NdkMediaCodec.cpp).

Recorder-worker contracts create isolated files in the emulator cache. Media3's
Matroska parser checks AVC/HEVC dimensions and sample counts plus exact PCM bytes
and timestamps; the platform extractor can omit Matroska PCM on API22. The tests
also check stop-before-keyframe cleanup and format-change errors. A controlled
worker pause fills the real bounded queue to verify the storage-backpressure
failure and subsequent draining; it is not a physical slow-disk benchmark.

UI profile checks wait for a complete persisted preferences XML during a profile
change. Empty or malformed reads never count as a default profile or a successful
change; unavailable preferences eventually fail the same bounded wait.

DNS wire contracts exercise compressed names, pointer loops and truncated
packets, UTF-8 label truncation and case-insensitive comparison. The decoder
rejects reserved label prefixes rather than treating them as labels longer than
63 bytes. These wire checks do not establish physical USB tether discovery.

Responder contracts use real emulator-loopback UDP for legacy queries and capture
multicast sends in a socket fixture. They validate record types and payloads,
legacy TTLs, cache-flush flags, UTF-8 TXT limits, duplicate removal, subnet filters,
foreign responders and goodbye records. Deterministic timestamps exercise probe,
announcement and retry intervals without waiting for real time to pass. A
separate emulator-only lifecycle case checks that idle discovery stops its worker.

Malformed-probe regression cases declare authority records but truncate the
question, record header, payload or compressed name. The responder validates
their framing before acting: malformed probes neither withdraw an active service
nor emit answers. Valid foreign probes and ordinary discovery queries retain
their existing behavior.

The native unit suite also supplies controlled JNI function tables to the real
log sink. It checks VM-query and thread-attachment failures, reference ownership,
class/method lookup errors, string-allocation failures and throwing callbacks.
The fixtures reject JNI calls made with an unavailable environment or a pending
exception. These tests do not exhaust real memory or abort a Java VM.

Native profiles from the unit executable contribute to the functions also
compiled into the app. The instrumented APK library defines the native mapping
inventory. The test executable is not supplied as an additional coverage object:
that would add helpers belonging only to the separate API17 frontend. This was
checked with the real LLVM tools: unit profiles covered all 196 mapped timeline
lines while the API17-only clock stayed outside the APK's source inventory.
