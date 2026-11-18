# Current Android coverage matrix

The acceptance target is 100% of every applicable measured metric for each of
the four current APK variants independently. A passing unit-test module does not
establish whole-app coverage. Vega and the API17 frontend are separate targets.

The variants are `normal`, `compat`, `deat`, and `compat-deat`. The regional
variants use the DE/AT package identity and must reject recording and download.
Compatibility builds have API22 as their minimum; modern builds require API25.

## Verification checkpoint — 2026-11-18

The completed normal-APK run passed 114 Android instrumentation tests, 18 native
cases with UBSan, and all 13 UI scenarios including extended media controls and
rendered captions. Its archived report measures 78.2816% Android/JVM lines,
57.0034% Android/JVM branches, 77.718% native lines, 65.625% native branches and
40.4908% native MC/DC. The other measured counters also remain below 100%; the
strict target is false. This cohort includes the MIME-query fix and paced AAC
fixture but predates the eight selected Playback ViewModel contracts below.
The API28 matrix is now complete: normal passed 114 Android tests, compat 122,
and both regional variants 123 each. Every edition also passed 18 native cases
and all 13 UI scenarios. Compat measures 78.6029% Android/JVM lines and 57.4411%
branches; DE/AT measures 78.1534% and 57.5427%; compat DE/AT measures 78.5475%
and 57.8328%. Native MC/DC is 40.4908% for normal/compat and 47.8528% for the
regional builds, which include the newer DNS contracts. All four strict targets
remain false. The runner's final nonzero exit is the coverage gate, after all
four reports were generated; earlier interrupted attempts add no passing evidence.

Additional selected contracts passed on unchanged, fingerprint-checked APKs:
three DACP player tests and two AudioRenderer tests on API22, and six download
publication/export tests on both API22 and API36. An earlier API36 attempt with
the original three publication tests encountered a startup ANR and passed on
retry; the subsequent six-test execution passed on its first attempt.
These supplemental test APKs differ from the full-suite test APK and must not
be merged into its matrix as if their compiled identities matched.

The coverage automation regression suite passes all 21 tests. Whole-app 100%
coverage across all four variants remains unverified and is still outstanding.

The frozen compat replay on API22 completed all 122 Android tests, 18 native
cases and all 13 UI scenarios. Its original API28 and new API22 raw profiles
were merged successfully using the new replay CLI and identical compiled
fingerprints. The union measures 78.7576% Android/JVM lines, 58.1481% branches,
77.619% native lines and 40.4908% native MC/DC; `--require-100` correctly fails.
The replay report is `build/frozen-compat-api22-replay/replay.json`.

Six additional Cast command contracts passed on API22 in 0.06 seconds using an
unchanged app APK and a separate test APK. They check app availability limits,
launch/stop session identity, connection destinations, cached/atomic volume,
supported URL-media MIME types, URL precedence, protected/invalid media rejection,
receiver ownership/busy state, load throttling, seek/resume commands, player-state
reporting, terminal idle status and session-counter rollover. They exercise the
real command handler on the Android main thread with a recording playback port;
they do not open a listener, fetch external media or establish TLS certification.
This selected preflight is not a complete matrix snapshot and its results cannot
be merged with a different compiled test APK. The instrumentation evidence is
`build/cast-command-probe/instrumentation.txt`; a full rebuilt matrix must
include these contracts before claiming a measured coverage increase.

The initial frozen API36 replay stopped after two decoder-contract failures:
the tests selected the first AVC inventory entry even though they require the
standard software decoder. The helper now explicitly selects the Google/AOSP
software entry; all capability, fallback and format-key assertions remain.
The failed frozen replay is not accepted coverage evidence. A supplementary
manually packaged probe also crashed before running any tests; its cause is
unconfirmed and it adds no passing evidence.

The final Gradle-built compat DE/AT APK and test APK passed all 14 selected
contracts on API36 in 4.902 seconds: seven decoder contracts, six Cast command
contracts, and the new real-JNI callback contract. The latter checks byte-exact
video/metadata/artwork/PCM payloads, unsigned progress values, codec and PIN policy,
owned registration keys with duplicate/capacity limits, playback snapshot/reset
rules, and callbacks on native-created threads. Deliberately throwing Java
callbacks verify admission rejection, recovery on the next callback and PCM
exception cleanup. It opens no listener or player and does not exhaust memory.
The coverage-only JNI fixture calls the production callback table; its own code
is not added to the production inventory. Both Android assemblies and lint
passed. The selected evidence is
`build/native-callback-final-selection/instrumentation.txt`. The subsequent
API36 normal run passed all 130 Android tests and 18 native cases, but stopped
at the first UI scenario: `failure.png` shows a System UI ANR dialog over the
already-rendered Tools screen. This is an incomplete run, not accepted coverage.
Its artifacts remain at `build/android-coverage-callback-contracts-api36`.
The clean-boot retry at `build/callback-normal-frozen-api36-retry` subsequently
completed all 130 Android tests, 18 native cases and all 13 UI scenarios using
the exact frozen APKs, mappings and native executable, with fresh runtime data.
Its full raw snapshot passed the strict completeness and compiled-identity
validator. The normal API36 report measures 79.8549% Android/JVM lines and
60.7239% branches; native lines are 93.1302%, branches 79.9279%, functions
97.0297%, instantiations 96.5517%, regions 90.7581% and MC/DC 61.9632%.
Every applicable metric remains below 100%. These results apply to this normal
APK cohort, not to the other three editions or the later brand/log test cohort.

Six new rendering/launch contracts passed on API22 in 28.082 seconds using a
Gradle-built compat DE/AT APK and test APK. They inspect actual screenshot pixels
as the logo traces and fills, checking the rounded tile, B cutout, equal aspect
ratio and progress clamping. Launch cases cover disabled and natural completion,
remote key-down versus key-up, Back, and touch skipping; skip checks require home
to appear before the natural reveal can finish. Completed intros must release
Back handling, avoid activating home with the skip key, compose home once and
dispose it when the activity closes. The animator scale is enabled for each test
and restored afterward. Evidence is
`build/brand-launch-selection/instrumentation.txt`; the assemblies passed in
2m34s. These selected contracts need inclusion in the next full matrix before
their coverage can contribute to the four-variant target.

Three new log-screen contracts passed on API22 in 20.226 seconds. Real Compose
accessibility actions verify an initially empty screen, append/automatic scrolling
to the last of 101 entries, clearing and subsequent recovery. Export checks read
the actual FileProvider content URI and compare the empty and Unicode snapshots
exactly, including the chooser/send actions, MIME type and read-permission flag.
A recording Application intercepts chooser launches, so no external app receives
logs. A deliberately failing chooser launch must be contained and the next export
must succeed. Isolated files and cache directories are removed after the log
worker closes. Initial selector failures are preserved separately: the icon label
can be below its clickable accessibility ancestor, so the test now locates that
ancestor and still requires its real click action and resulting state changes.
Evidence is `build/logs-screen-accessibility-selection/instrumentation.txt`;
the failed selector run at `build/logs-screen-selection` adds no passing evidence.
A fresh four-variant API36 run including the brand and log-screen contracts has
started after successful completion of the frozen normal retry. These selected
results do not establish new whole-app percentages or satisfy the 100% target.

Two new ALAC contracts passed on API22 in 0.057 seconds using the Gradle-built
compat DE/AT APK and test APK. They decode original, non-silent stereo PCM16
through the production audio engine with software ALAC selected, checking every
sample byte, channel order, rate/channel metadata and timestamp. One corpus has
4096 frames followed by a short 128-frame packet; the other checks the engine's
352-frame default after an invalid zero-size format announcement. The short
packet must not publish stale samples from the larger interleave scratch buffer.
The coverage-only JNI fixture collects the real capture tap without starting an
audio output stream. The native engine and decoder implementation are unchanged.

`tools/generate_native_alac_fixture.py` constructs both stereo channels from
integer formulas, encodes ALAC and independently checks an exact PCM round trip
before writing packet files and hashes. The fixtures are instrumentation assets
and are not packaged in release apps. Both Android assemblies and lint passed
in 3m21s; selected runtime evidence is
`build/native-alac-default-selection/instrumentation.txt`. These selected data
are not merged into an earlier test-APK cohort. The already-frozen normal run
has 139 Android tests; subsequent builds can include the two new ALAC contracts.
Their measured coverage contribution still requires complete runtime reports.

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

Replay a completed frozen snapshot on another x86_64 emulator API without
rebuilding its app, test APK or native executable:

```powershell
python tools/replay_android_coverage.py --serial emulator-5562 --snapshot SNAPSHOT_API28_COMPAT --output build/replay-compat-api22 --require-100
```

Repeat `--snapshot` to select other editions, with one snapshot per edition.
The replayer validates complete archived evidence and checks the target API and
ABI before changing installations. It copies compiled inputs and pure JVM data
into a fresh directory, runs all native/Android/extended UI scenarios, and
recalculates coverage from the original and new raw profiles. Prior Android
profiles and success metadata are never copied into the new runtime evidence.
The report and manifests remain bound to the original compiled fingerprints.

To combine API runs, use the `raw_snapshot` paths recorded in their matrix JSON
files (relative to each run's report directory). Replace the two snapshot names
below with those directories:

```powershell
python tools/merge_android_coverage.py --snapshot SNAPSHOT_API22 --snapshot SNAPSHOT_API28 --output build/coverage-merged/compat --require-100
```

The merger requires the same edition and compiled fingerprints, successful
instrumentation/native results, every one of the 13 UI scenarios, and raw
Java/native profiles. A partial scenario list is rejected. It
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

On Windows, the Python archive and fingerprint operations use extended paths
for generated class trees. Deep report directories can otherwise exceed the
legacy path limit when Hilt class names are appended. The archive regression
checks paths longer than 260 characters and still requires identical compiled
and source fingerprints; a partial copy cannot become a valid snapshot.

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
do not enable download controls in the DE/AT edition. Additional lifecycle tests
run the real Transformer: unreadable input must reset progress and remove the
temporary export; repeated start must retain the active instance; cancellation
must clear that instance and remove its file. A synthetic local MP4 exercises
successful export and publication, checking all ten video samples, timestamps,
dimensions and pending-store completion through MediaExtractor. This is a local
media export, not a network transfer or a physical storage benchmark.

Gesture-state contracts use an isolated ViewModel without a bound receiver.
They check timeline guards, double-tap zone boundaries and direction changes,
seekbar and drag clamping, pending-scrub continuation, all content scales, zoom
limits and indicator expiry. A real emulator activity window and AudioManager
exercise brightness/volume limits and vertical-gesture selection; the original
stream volume is restored. These are state contracts, not pointer-detector or
complete touch-interface coverage. The selected six-test API22 run passes;
whole-variant reports must include the same tests in their compiled cohort.

Pointer-detector contracts dispatch Android MotionEvents through an emulator
activity's View tree and real Compose pointer modifiers. Five selected API22
tests check both drag axes and directions, below-slop motion, cancellation,
multiple-pointer rejection, pinch expansion/contraction, stationary pointers,
two-pointer pan, and events consumed in the Initial pass. This covers the
detector pipeline without claiming Android system InputDispatcher injection or
physical touchscreen validation. Callback positions, directions and ownership
release are asserted; the helpers do not replace the production detectors.

Photo-viewer contracts launch the real activity and serve synthetic red/blue
PNGs over emulator-loopback HTTP. Three selected API22 tests check decoded
pixels, rotation/scale controls, duplicate URLs, the 20-entry history limit,
wrapped navigation, the real slideshow timer, localized failure messages and
recovery. Bitmap recycling and visibility are checked when the activity closes;
the tests do not require external image hosts or personal photos.

Decoder-selector contracts query the real platform codec inventory and video
capabilities. Seven selected API22 tests check software selection, unsupported
MIME/sizes, AVC/HEVC identities, adaptive playback, generic low-latency retry
keys, frame-rate rejection, prefix matching and portrait fallback call order.
Capability queries normalize MIME names with Locale.ROOT: the earlier selector
accepted uppercase MIME during enumeration but then threw when querying that
type on Android. The regression also changes the default locale to Turkish and
restores it afterward. These contracts do not establish vendor-chip behavior.
The earlier 85-test whole-app report predates this production fix and must not
be reported as coverage of the corrected APKs.

Playback ViewModel contracts use an isolated application and preferences with
no receiver service bound. Eight selected API22 tests check pending/active media
controls, speed limits, silence settings, scrub settlement/timeout/cancellation,
cached preference flows, external preference writes, asynchronous log persistence
and clearing, transient service messages, PIN and permission-display state.
The real coroutine jobs and log worker are closed at teardown. These tests
exercise disconnected UI state, not bound-service commands or actual seeking
in a media player; whole-variant runs must measure those paths separately.

SOAP metadata contracts cover escaping, inherited and rebound namespaces,
default-namespace resets, raw XML fragments and already escaped argument text.
The protocol JVM tests use an independent DOM parser to check the reconstructed
XML; instrumented contracts also exercise the Android XML provider.

Native server contracts create real JNI contexts and verify advertised feature
bits and codecs, PIN metadata, occupied-port recovery, RTSP replies, direct-buffer
bounds and audio start/stop. Temporary pairing keys stay in the emulator app's
cache and are deleted after teardown.

The coverage-only DNS JNI fixture calls the production Android DNS-SD shim's C
APIs in memory. It checks empty and rebuilt TXT records, owned name/address
copies, optional length pointers, invalid record indices, feature bits at both
32-bit boundaries, invalid bit/value arguments, all PIN modes, codec combinations,
public-key removal and compressed-audio metadata changes. Oversized hardware
addresses must fail registration safely. The shim's record builder performs no
network announcement. The new contract passed within the DE/AT 123-test API28
instrumentation run; the full edition report still requires its UI scenarios.

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

The platform-AAC fixture is paced at its 1024-sample/44100-Hz packet duration,
rounded up to 24ms. The earlier 10ms schedule sent over twice real time and
could exhaust codec input slots under emulator load. Rejected-packet counters
are checked after every input, while exact PCM bytes, every timestamp and
capture shutdown remain required. The revised selected test passed three API28
executions against the unchanged APK; full-matrix validation is still required.

Recorder-worker contracts create isolated files in the emulator cache. Media3's
Matroska parser checks AVC/HEVC dimensions and sample counts plus exact PCM bytes
and timestamps; the platform extractor can omit Matroska PCM on API22. The tests
also check stop-before-keyframe cleanup and format-change errors. A controlled
worker pause fills the real bounded queue to verify the storage-backpressure
failure and subsequent draining; it is not a physical slow-disk benchmark.

UI profile checks wait for a complete persisted preferences XML during a profile
change. Empty or malformed reads never count as a default profile or a successful
change; unavailable preferences eventually fail the same bounded wait.

Remote activation also waits for a valid accessibility hierarchy before reading
focus or sending a key. A transient missing hierarchy does not advance focus;
persistent unavailability fails the bounded wait without activating a control.

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
