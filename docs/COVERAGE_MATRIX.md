# Current Android coverage matrix

The acceptance target is 100% of every applicable measured metric for each of
the four current APK variants independently. A passing unit-test module does not
establish whole-app coverage. Vega and the API17 frontend are separate targets.

The variants are `normal`, `compat`, `deat`, and `compat-deat`. The regional
variants use the DE/AT package identity and must reject recording and download.
Compatibility builds have API22 as their minimum; modern builds require API25.

## Verification checkpoint — 2026-11-19

All 35 contracts in the affected preview-host classes passed together on API22
(81.732 seconds, no skips). Assembly and lint passed in 1m1s. Evidence is
`build/tools-contract-host-selection`, with immutable app/test APKs, hashes,
the complete instrumentation transcript and continuous logcat. This is a
selected regional compatibility build, not a complete four-edition report.

Four new Tools contracts check relay receive availability, listening details
and stop controls, receiver status in the diagnostic dialog with Close/Back,
and recording controls according to the edition. Each independent blocker must
first recover to an observably enabled idle control, then disable it again;
consecutive disabled states cannot silently conflate. Restricted-edition
absence checks also require the capture state to produce an observable change
in the relay control. The recording-enabled branch still needs the full matrix.

The initial Tools cohort had three passes and one failed locator assertion:
it assumed every Compose action exposed the widget Button class. The fixture
now reads actual click actions and enabled/disabled ancestors. The strengthened
cohort then passed all three state cases but its first case failed before
entering the screen: ActivityScenario could not launch the animated preview
within 45 seconds. Both failed cohorts remain separate under
`build/clean-tools-selection` and `build/clean-tools-strong-selection`.

The debug preview now accepts `screen=contract_host`, an empty themed host for
contracts that install their own real component or inspect the activity window.
The eight affected classes opt in; ordinary preview screens retain their
behavior. This removes unrelated preview composition from the initial idle wait.
The real brand animation, gesture, logs, main-screen and Tools assertions remain
in the 35-test cohort. This result does not establish the cause of every older
timeout, including the incomplete API36 matrix described below.

The attempt under `build/android-coverage-main-states-api36` lost its controller,
emulator and Java processes during lint and produced no complete runtime report.
The recorded OS boot predates that attempt, so a restart is not established.
Its partial files contribute no accepted coverage.

The fresh attempt under `build/android-coverage-tools-states-api36` passed
assembly, module checks and lint in 15m33s, but stopped before runtime while
fingerprinting a generated class with a 267-character Windows path. The file
was present and readable through an extended path; the ordinary path reported
it missing. Live compiled-class roots now use the same extended-path handling
already used for archived roots. No class is omitted from the inventory.

The regression first reproduced that read failure, then all 26 coverage-tool
tests passed in 2.589 seconds. It checks the complete live class counts,
mutation detection for the long class, and identical archived fingerprints.
The real APK inventory also reads successfully: 407 app, 31 core and 58 domain
classes. A four-edition retry is in progress under
`build/android-coverage-live-paths-api36`; whole-app 100% remains unproven until
every required runtime report and metric passes.

Five main-screen state contracts passed on API22 (14.67 seconds, no skips).
They render the production MainScreen with an isolated, unbound ViewModel:
network ASK/SETTINGS actions and granted-access errors; PIN confirmation/Back
clearing state and dialog; tools/settings/log navigation and return to overview
when developer options are disabled; updated diagnostic sections and the initial
decode window; and metadata/cover-art replacement followed by the unknown-track
fallback. Cover-art verification checks the real painted pixel. These states
must not create a video surface. Preferences, logs, bitmaps and locale are
cleaned up or restored. Assembly and lint passed in 3m. Final evidence is
`build/main-screen-final-selection`.

The original tab assertion expected a selected ancestor to be clickable. The
captured accessibility tree showed Tools selected on a non-clickable ancestor;
the fixture now requires explicit selection anywhere in the bounded ancestor
chain. The initial numeric fixture also expected 1.25 ms to round to 1.3 on an
older Android formatter that rendered 1.2. The final fixture uses unambiguous
1.4 ms input with a temporary US locale and keeps exact text assertions. The
failed cohorts and semantics diagnostics are preserved separately.

An earlier four-edition attempt under `build/android-coverage-init-guards-api36`
hit its 900-second instrumentation deadline while the single-tap surface contract
was running. Its partial transcript includes all five initializer contracts
passing on API36, but there is no completed normal snapshot or report from that
attempt. Partial status is not whole-suite success; the gesture wait requires
isolated diagnosis before retrying the complete matrix. The selected main-screen
results are a separate compiled test APK and cannot be merged into that attempt.

Callback initialization now reports whether binding succeeded. It resolves the
VM, global receiver reference, class and all 22 required Java methods before a
receiver can be started. A failed lookup releases partial references and the
context's synchronization primitives while preserving the pending Java error.
Both native callers honor this result: the production JNI initializer returns
before creating a server, and the coverage callback owner only destroys a
successfully initialized context.

Controlled JNI-table contracts first reproduced three failures in the original
code (four tests, three failures, 0.031 seconds): VM failure left a bound context,
and allocation/missing-method failures continued making JNI calls with an
exception pending. The fixed contracts check every missing-method position,
allocation/class failures, VM status/null output, exact method IDs and reference
ownership. A real Java proxy without the admission callback also returns its
NoSuchMethodError through JNI, followed by successful initialization. Five new
contracts plus the existing callback and server contracts passed on API22:
14 tests, 14.08 seconds, no skips. Assembly and lint passed in 9m44s. Evidence is
`build/callback-init-green-selection`; the original failing cohort is preserved
under `build/callback-init-red-selection`. The fixture models JNI dependency
faults without exhausting an actual VM. These selected profiles do not establish
whole-app or four-edition 100% and cannot be merged into older compiled cohorts.

The prior regional retry was interrupted by a Windows restart at
2026-11-19 00:35:42 UTC, after its recorded start and before a completed report.
Its controller/emulator/Java workers were absent after restart. Completed earlier
normal/compat snapshots remain valid for their original compiled inputs; this
unfinished retry contributes no passing regional snapshot. The new initializer
changes native mappings and requires a fresh four-edition matrix.

Four relay-playback contracts passed on API22 (2.855 seconds, no skips), using
real silent AudioTrack output and the production VideoRenderer. They require
playback-head advancement, correct PCM format, release of the track and worker,
and new owners after restart. Invalid sample rates/channel counts terminate the
worker and permit a later valid session. Video reference NALs check dimension
and codec changes plus timestamp-anchor reset without starting a decoder.
A real pacing sleep exercises the 64-packet limit and overflow error, and stop
must interrupt that sleep, clear the queue and release output. A separate
far-future timestamp case verifies the bounded pacing policy. Assembly and lint
passed in 5m40s; evidence is `build/relay-playback-selection`.

The regional API36 attempt under `build/android-coverage-regional-surface-api36`
stopped when the Android instrumentation command reached its fixed 300-second
limit. Its final logcat still showed gesture tests completing and another test
starting. This attempt has no complete Android result/report and is not accepted
coverage evidence. The host runner now streams instrumentation output to disk,
preserving the last started test on timeout or failure, and accepts
`--instrumentation-timeout` with a positive duration (default 900 seconds).
Only a complete successful transcript can produce a pass or coverage checkpoint;
all strict status/count checks remain. All 25 automation regression tests pass,
including streamed output, configured/default deadlines, timeout retention,
skipped-test rejection and invalid-timeout CLI rejection. The regional retry
uses a fresh output directory and the expanded suite.

Three integrated player-gesture contracts passed on API22 (33.202 seconds,
no skips). Real Android MotionEvents traverse `VideoPlayerGestures` and the
production tap/seek/brightness/volume/zoom states. Single taps remain available
in enabled, disabled and locked modes; blocked double taps do not seek; a tap
during accumulated seeking does not toggle controls and normal tapping resumes
after the real indicator timeout. Drag and pinch tests reject changes when
blocked, then check the actual seek target, window brightness, AudioManager
volume change and zoom acquisition/release when enabled. Emulator volume and
window brightness are restored; the isolated ViewModel, scope and preferences
are released. Assembly and lint passed in 3m20s. Evidence is
`build/player-gesture-surface-selection/instrumentation.txt`.

The newer full API36 normal cohort passed 148 Android tests (223.551 seconds),
18 UBSan native cases and all 13 UI scenarios; its strict raw snapshot validates.
Normal measures 81.7101% Android/JVM lines and 96.0472% native lines, with
66.6667% native MC/DC. The compat cohort passed 151 Android tests (235.353
seconds), the same native/UI requirements and strict snapshot validation;
its corresponding line coverage is 82.9231% and 96.0611%, with 66.6667% MC/DC.
All measured counters remain below 100%. The normal test APK predates the three
indicator contracts and compat predates the three integrated surface contracts;
selected results from later test APKs cannot be merged into those reports.

Three gesture-indicator contracts passed on API22 (48.68 seconds, no skips),
using real Compose pixels and accessibility. They check progress fill at 0%,
25%, 50%, 100% and out-of-range values while preserving the displayed number;
updated and centered information text; and direction-specific double-tap
highlights, the untouched center/opposite side, and removal after the real
750 ms state timeout. Animations run at scale 1 and the original setting is
restored; the isolated ViewModel, coroutine scope and preferences are cleaned up.

The initial selected run passed the directional highlight test but failed to
find text through accessibility's direct text-search API. The corrected fixture
walks a nonempty accessibility tree belonging to the actual app, requires exact
visible text, and checks both the replacement and absence of the old text in a
valid tree. No rendering assertion was removed. Final assembly and lint passed
in 1m25s. Evidence: `build/gesture-indicator-accessibility-selection`; the initial
failed run remains in `build/gesture-indicator-selection`. These selected APKs
have their own hashes and do not establish whole-app or four-edition 100%.

The newer API36 matrix in `build/android-coverage-brand-logs-api36/matrix.json`
is complete: normal passed 139 Android tests, compat 141, and both regional
variants 144 each, alongside 18 UBSan native cases and all 13 UI scenarios per
edition. Android/JVM line coverage is respectively 81.5079%, 81.603%, 81.1888%
and 81.2604%; native lines are 93.1302%, 95.0595%, 96.1171% and 95.9524%.
All twelve counters remain below 100% in every edition. The driver validated
all fifteen required result labels in each immutable raw snapshot before marking
this run complete. Test APKs differ across these cohorts: ALAC contracts were
added after normal was archived and Oboe output contracts after compat. These
compiled identities cannot be merged or described as one identical test suite.

Four additional JNI dependency-failure contracts now exercise the real native
callback table with controlled JNI function tables: allocation failure (including
the second DACP string), failed array copies, unavailable VM/failed thread attach,
and throwing Java callbacks. Every scenario checks reference cleanup, forbids
JNI work with a pending exception or failed VM status, and delivers a successful
follow-up callback. The original code failed three of the four tests on API22:
video used a failed allocation, video called Java after a failed array copy, and
PCM used the output environment after GetEnv returned JNI_ERR. The production
bridge now guards these paths, cleans up the first DACP reference if the second
allocation fails, and clears stale PCM exceptions before allocating again.

The same four tests plus the existing normal callback-delivery contract passed
on API22 (five tests, 0.26 seconds, no skips); assembly and lint passed in 2m12s.
Evidence is `build/callback-fault-green-selection/instrumentation.txt`, with the
original failing run preserved under `build/callback-fault-red-selection`.
This is controlled fault injection, not real ART memory exhaustion. These new
compiled APKs need their own full four-edition matrix; selected results do not
replace whole-app coverage evidence or establish the 100% target.

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

Three Oboe-output contracts passed on API22 in 0.17 seconds. They verify that
post-close recovery opens and starts a different real stream, that callbacks
after stop or owner destruction cannot reopen output, and that an invalid output
format fails recovery without retaining a stream. A late data callback must
still safely fill its buffer with silence after the owner has been destroyed.
The fixture reads actual callback/stream references under the production mutex;
the only output-header change grants this test-access shim friendship. No output
handling logic or production state is replaced. OpenSLES was observed in the
emulator log; this is controlled callback delivery, not a physical device-loss
test. All opened streams are closed before the fixture returns.

Android assemblies and lint passed in 19m31s; the long lint phase was observed
walking source paths in Kotlin analysis, with the declared source roots intact.
No analysis scope was excluded to shorten it. Selected runtime evidence is
`build/native-output-selection/instrumentation.txt`. Earlier frozen normal139
and compat141 APK cohorts do not contain these three tests; subsequent builds
can include them. Their whole-app coverage contribution remains unmeasured
until those complete runs produce accepted reports.

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
those compiled inputs change before the report finishes. Before installing,
the matrix runner also verifies the native coverage library against the APK
entry using the build's NDK strip tool. Separate API runs
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
recalculates coverage from the original and new raw profiles. Like a fresh
coverage run, it includes VPN and offline diagnostics on API23 and newer.
Prior Android profiles and success metadata are never copied into the new
runtime evidence.
The report and manifests remain bound to the original compiled fingerprints.

Use `--collect-only` to archive a successful replay before reporting. This
mode runs the same runtime checks and saves the raw snapshot in `replay.json`
without starting Gradle. It allows another emulator to collect API-specific
coverage while the first build owns the Gradle outputs. Run the merge command
after Gradle is free to calculate the metrics from both snapshots.

To combine API runs, use the `raw_snapshot` paths recorded in their matrix JSON
files (relative to each run's report directory). Replace the two snapshot names
below with those directories:

```powershell
python tools/merge_android_coverage.py --snapshot SNAPSHOT_API22 --snapshot SNAPSHOT_API28 --output build/coverage-merged/compat --require-100
```

The merger requires the same edition and compiled fingerprints, successful
instrumentation/native results, every one of the 13 UI scenarios, and raw
Java/native profiles. It rejects a partial scenario list or a run recorded
with an Android instrumentation class or method filter, and checks the
compiled mappings before and after reporting. New snapshots
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

Service audio position contracts bind the real stopped `AirPlayService` and
observe playback-state callbacks from its actual `MediaSessionCompat`. They
check pause/resume without rewinding extrapolated progress, repeated remote play,
duration bounds, degenerate progress, partial metadata and PNG artwork, current
and duplicate PIN delivery, and connection-approval bypasses. The tests restore
the exact approval preference (including an absent key) and wait for service
destruction without starting a receiver.

The initial six-test run with callback-based observations reproduced three
position defects: pausing rewound 1,321 ms to the last sender report at 1,000 ms;
pausing an over-duration report retained an invalid position; repeated play
published the old report. Capturing the extrapolated position before changing
playback state fixes these cases and keeps the stored position and time anchor
consistent. On API28, all six new tests plus the six existing DACP-player and
audio-metadata tests passed (12 tests, 2.685 seconds, no skips). Debug assembly
passed as well. Selected tests are regression evidence, not full-edition coverage.

The separate healthy API36 normal-edition baseline completed all 172 Android
tests, 18 native unit cases and the 15 required result labels. Its archived
compiled inputs, sources and raw profiles passed snapshot validation. It still
falls below the target: Android/JVM line coverage is 85.0059%, branch coverage
64.6584%; native line coverage is 96.1249%, branch coverage 83.7336% and MC/DC
67.0659%. That frozen cohort predates the service position fix and must not be
merged with profiles from the changed app. Completion still requires fresh,
matching evidence at 100% for every metric in all four editions.

Callback fault contracts also exercise 14 scalar/control callbacks under failed
VM lookup, failed thread attachment and a throwing Java consumer. They require
zero calls through an unavailable environment, no allocation/copy activity for
these callbacks, and successful delivery after the failure clears. Pairing
contracts model a counted null key slot left by failed `strdup`, checking that
lookups skip the hole, later duplicates remain recognized and a new key is
still stored within the table limit. Every remaining allocation stays owned by
the context and is released by its normal destructor.

On API28 the six callback-fault tests, one real-JNI delivery test and five
initializer tests passed (12 tests, 0.291 seconds, no skips); debug assembly
passed in 13 seconds. An export from the exact archived library and fresh native
profile verifies all independent conditions in the progress guard and both
pairing-key guards. The selected callback file still has seven uncovered MC/DC
conditions. These results do not establish 100% for the file, APK or four-edition
matrix. The earlier attempt that lost its local ADB-server connection during
test-APK installation never executed tests and supplies no coverage evidence.

The ALAC fixture now delivers encoded packets through the actual RAOP
`audio_process` callback before the production decoder and PCM capture tap. It
first checks that a missing engine, missing data and zero/negative packet sizes
produce no PCM. Valid packets must then preserve every stereo sample, timestamp,
sample rate and channel count, including the 4,096-frame packet, short 128-frame
tail and default 352-frame RAOP packet. Input and expected PCM hashes remain
checked against the original synthetic fixtures.

On API28 the related callback, initializer, ALAC and audio-lifecycle selection
passed all 15 tests in 0.343 seconds without skips; debug assembly passed in
13 seconds. LLVM export from that selection's exact archived native library
and fresh profile confirms independent coverage of all three audio-input guard
conditions. The callback file now has 42 of 46 MC/DC conditions covered in this
selection. Four remain uncovered, and this selected evidence is still separate
from complete reports for each APK edition.

HLS reset contracts initialize a real RAOP/HTTP connection owner without starting
listeners. They verify shutdown and end-of-stream with both absent and present
owners, the stopped playback sentinel, six exact Java stop events and normal
receiver destruction. The fixture uses an in-memory identity and no key file.

A separate controlled VM fault reports success without an environment. This is
a deliberately malformed dependency result, not behavior induced in ART. All
22 delivery scenarios must avoid JNI calls, allocations and copies for that
result, then deliver normally once the fixture restores a valid environment.
The related API28 selection passed all 16 tests in 0.307 seconds without skips;
debug assembly passed in 15 seconds. The exact library/profile export confirms
independent coverage of the HLS-owner and null-environment guards. The callback
file has 45 of 46 MC/DC conditions covered in this selection. That selection
does not cover TLS-key creation failure or establish the full four-edition target.

The frozen API36 compatibility baseline also completed its 172 Android tests,
18 native cases and all 15 required result labels; archived snapshot validation
passed. It remains below target, including Android/JVM line coverage 85.1486%,
branches 64.793%, native lines 95.9654% and native MC/DC 67.0659%. As with the
normal baseline above, its older compiled mappings cannot absorb profiles from
the subsequent service fix or changed native fixtures.

The modern standalone native unit suite now compiles the unchanged production
C callbacks and a C dependency fixture. Its 19th case wraps `pthread_key_create`
to return `EAGAIN`, then verifies that repeated progress deliveries neither
attach a JVM thread without a detach key nor use the unavailable JNI environment.
It runs after the worker tests have joined, using process-local test state that
does not depend on TLS itself. No real key exhaustion or ART-state reset occurs.
The legacy NDK/C++17 build retains its prior 18 cases and compilation path.

All 19 cases passed under UBSan on API28. ARM32/API22, ARM64/API22 and the legacy
x86/API17 unit executables also compiled. The first TLS-dependent mock timed out;
a subsequent C++ JNI mock exposed a C/C++ indirect-call type mismatch under
UBSan. The final C fixture fixes both test issues without disabling sanitizers.
Those unsuccessful attempts contribute no coverage profiles.

Merging the successful unit profile with the matching 16-test JNI selection,
and exporting only the archived APK library as a coverage object, produced no
LLVM warnings and verified all 46 callback-file MC/DC conditions. The inventory
still contains all 42 functions and 252 branches; 41 functions and 244 branches
are covered. This establishes 100% MC/DC for that file only. It does not establish
100% for its other metrics, the full native code, Android/JVM or any APK edition.

Touch-settings contracts render the actual settings screen with an isolated,
unbound ViewModel and drive its accessibility editors and real input connection.
They check blank, zero and oversized ports, digit normalization, unchanged-value
writes, trimmed receiver names, invalid custom resolution, exact custom sizes
and return to a preset. They restore their private preferences and close the log
worker without changing device settings or starting a receiver.

The first three-test run reproduced `NumberFormatException` when a port was
cleared and IME Done arrived before the next composition. `save()` used a prior
validation result with the current text. It now snapshots and validates the
same text immediately before committing it. The original assertions then passed
all three tests on API28 in 29.878 seconds; debug assembly passed in 50 seconds.
The failed run (two passes, one failure) remains separate from successful profiles.

The successful selection's APKs, native library, compiled classes and owned
sources were archived and checked before generating an Android-only report from
its matching execution profile. That report covers 415 of 601 settings lines
and 184 of 440 settings branches. These selected numbers are not a full-edition
or aggregate result; remaining lines are retained for choosing subsequent cases.
The separate frozen DE/AT baseline also completed all 172 Android tests, 18 native
cases and required runtime labels, passed snapshot validation and remained below
the 100% target. It predates this settings fix and cannot absorb its profiles.

Advanced settings contracts toggle 28 standard/developer switches through both
persisted and rendered states, enforce the fixed cushion and hardware-buffer
bounds, and adjust latency and adaptive buffering through real directional keys.
The numeric fields also verify returning a previously changed value to zero.
IME confirmation now receives the current editor callback through a lambda,
avoiding reuse of the earlier local function reference after a value update.

Window-event traces reproduced a second defect: key-down changed latency from
250 to 300 ms, while key-up immediately restored 250 ms. The custom step handler
consumed only key-down, leaving the Slider to finish its old value on key-up.
`dpadAdjust` now consumes the releases of its handled directional keys as well.
The successful trace contains nine directional releases that preserve their
committed latency/adaptive values, including both adaptive slider boundaries.

On API28, all six settings cases plus five existing main-screen cases passed
(11 tests, 51.943 seconds, no skips). The PIN-dialog check additionally exposed
an API28 accessibility issue: the model and screenshot showed the dialog closed,
but the active-root query returned null. The test now retrieves and refreshes
the focused application window, restores its accessibility service flags, and
still requires a nonempty app tree, cleared PIN and selected overview. It does
not accept an absent tree as successful dismissal. Failed attempts and their
diagnostics remain separate from passing profiles.

The matching selected Android report now covers 563 of 601 settings lines and
277 of 440 settings branches. The complete older frozen matrix also finished:
each of the four editions passed 172 Android tests, 18 native cases and all 15
required result labels; every archived snapshot validated independently. All
four remained below the metric target. Those older sources and mappings remain
reference evidence; the changed app still needs fresh complete edition reports.

The native timestamp/capture contract also checks the transition from an
unsynchronized sender clock to a synchronized clock. Empty and zero-timestamp
packets must not enter timestamped capture; nonempty zero-timestamp PCM still
plays unchanged, and the next synchronized packet is captured once and remains
audible. All 19 modern native cases passed on API28 with undefined-behavior
sanitizer traps enabled. Exporting that run against the matching application
library, without the unit executable as a coverage object, covers both conditions
of the capture/timestamp guard with no LLVM warnings. This is selected native
evidence, not a complete edition report or a four-edition 100% result.

The first fresh API36 run built successfully with lint and passed all 19 native
cases, but its 187 Android tests had seven failures: four main-screen checks and
three settings checks read old accessibility descendants. A failure screenshot
showed the current Open settings button while the queried tree described the
previous screen. Refreshing the root alone did not invalidate those descendants.
The two screen fixtures now explicitly clear the instrumentation accessibility
cache on API34+, where that SDK API is available, before reading current nodes.
All 11 settings/main-screen cases then passed on API36 in 82.172 seconds without
skips, with unchanged source and input fingerprints. The failed full-run profiles
are not accepted; a complete fresh edition run is still required.

The native backlog contract now advances a unit-only monotonic clock across an
underrun: sustained backlog must remain intact during recovery, must not trim
one nanosecond before the two-second throttle boundary, and must trim only the
excess at that boundary. The linker wrapper forwards normal clock calls to libc
and is enabled only in the modern unit executable; production and the legacy
unit build do not link this wrapper. Additional assertions exercise a multi-second
arrival spike and a full ring that must emit no accepted-clock metadata.

All 19 native cases passed on API28 with sanitizer traps. The matching app-library
mapping, excluding the unit executable as a coverage object, reports timeline
MC/DC 14/14, branches 90/92 and regions 150/152, with no LLVM warnings. Functions,
instantiations and lines are fully exercised for that file; the two remaining
branch/region gaps are retained. ARM32/API22, ARM64/API22 and legacy x86/API17
unit builds also compile. These are selected native results, not a whole-app or
four-edition 100% claim.

The subsequent full normal-edition Android run passed all 187 tests without
skips in 304.831 seconds, including the seven checks that previously failed on
API36. Its native suite also passed all 19 cases, and normal/advanced settings
passed the separate external UI checks. Remaining external scenarios and the
complete coverage report must finish before accepting its archived snapshot.
This run was frozen before the unit-only clock/bounds additions above; those
assertions have separate matching native evidence and must be included in the
final complete four-edition cohort.

Two additional ViewModel contracts verify asynchronous profile ownership. A
default-profile request cancels a preceding device probe, restores all tuning
defaults, and must not log cancellation as an error. An invalid profile must
leave the entire preference snapshot unchanged, report one failure, and allow
the next valid request to complete. The full ten-case PlaybackViewModel selection
passed on API28 in 5.267 seconds without skips; debug assembly passed in 42 seconds.
Its matching Android-only execution profile, APKs, native library, source and
class mappings were archived before reporting, with no unrelated JVM profiles.
The current full normal run predates these two cases; the next complete cohort
must therefore run 189 Android tests, plus the current 19 native cases.

The frozen 187-test normal cohort subsequently completed every required runtime
result label and generated both JaCoCo and LLVM reports. Its archived snapshot
validated independently, including the exact source/compiled-input identities.
The strict gate correctly remains below target: JVM branches are 3975/5948 and
lines 7298/8413; native branches are 792/916 and MC/DC 123/167. No complete-edition
metric is yet at 100%. This establishes a valid current production-code gap
inventory; it does not prove coverage for the other three editions or include
the later two ViewModel tests and unit-only clock assertions.

A coverage-only partial-server fixture now owns a real callback binding and its
initialized mutex/condition variable while leaving RAOP, discovery and audio
children absent. Its context uses the same private native state declaration as
the shipped bridge, moved unchanged into a shared header. The test invokes the
shipped JNI controls, verifies unavailable children reject activation/debug or
produce no records, and destroys the context through the shipped teardown path.
It opens no listener, decoder or output stream.

The final 17-case JNI/server/init selection passed on API28 in 10.902 seconds
without skips, after a 26-second initial assembly and 9-second final test assembly.
The matching library-only LLVM export reports all conditions covered for 18
partial-owner guards, with no warnings. All 24 shipped JNI entries are retained,
and the complete mapped native inventory remains exactly 916 branches, 202
functions, 204 instantiations, 1729 lines, 167 MC/DC conditions and 1510 regions.
The bridge file itself reaches MC/DC 39/47 in this selection; remaining paths stay
in the report. These changed source mappings must not absorb the previous full
187-test profiles. The next complete cohort must run 190 Android and 19 native
tests and still satisfy all runtime labels independently for every edition.

The software-AAC recovery case also sends a zero context handle, a null array
through the real debug JNI method, an empty array and an 8193-byte array before
valid audio. Rejected input must emit no PCM; the following 264 real decodes
retain the existing 263 captured packets, exact PCM bytes and timestamps, format
and latency assertions. The 17-case JNI/server/init selection passed in 10.532
seconds without skips. Its matching library-only export covers both conditions
of each debug-input guard and reaches bridge MC/DC 43/47 and branches 161/178,
with the same complete native inventory and no LLVM warnings.

A Windows restart interrupted the preceding full 190-case run after its native
suite passed and before Android instrumentation finished. That partial run has
no accepted result or replay baseline; its files remain diagnostic evidence.
The successful selected retry uses a separate output and cannot substitute for
the required complete four-edition acceptance run.

The subsequent frozen normal 190-test cohort completed all required runtime
labels, passed independent snapshot validation and remained below target. JVM
branches are 3986/5948 and native MC/DC is 146/167. It predates the following
decoder fix and must retain its own compiled mappings and execution profiles.

Coverage-only, thread-scoped PIN substitution now invokes the real JNI
initialization with generated values -1, 10000, 0 and 9999. Invalid values must
abort; valid boundaries and a following ordinary RNG-backed request must succeed.
The fixture checks that the wrapped generator was actually called exactly once.
Ordinary calls use the original generator, and noncoverage builds do not link
either fault wrapper.

The initial 18-case selection exposed a platform AAC packet-zero rejection.
The original failing API return was not recorded, so its exact cause is not
asserted. A separate contract then reproduced a concrete startup failure: when
the first input dequeue returns the valid transient TRY_AGAIN_LATER result, the
decoder made one attempt and rejected the packet (one test failed in 0.175s).
The decoder now permits one additional 95ms wait after its initial 5ms wait,
only until obtaining its first input buffer. Persistent starvation remains
bounded; subsequent live input retains its original 5ms budget.

All 19 JNI/server/init cases passed on API28 in 16.914 seconds without skips.
Contracts cover bounded persistent startup starvation, successful startup retry,
no warmup retry after input becomes ready, exact rejection counters and complete
PCM/timestamp recovery. The library-only export covers both new startup-guard
conditions and both PIN-bound conditions, with no LLVM warnings. Bridge MC/DC
reaches 45/47. The actual recovery code adds four mapped branches, three lines,
two MC/DC conditions and four regions; no inventory is removed. A current
noncoverage x86_64 native Debug build also passed, and its symbols confirm the
original PIN generator/JNI initialization are present while fault wrappers and
LLVM profile symbols are absent. No release APK was signed or published.
The current complete cohort now requires 192 Android and 19 native tests across
all four editions, with every metric and mandatory runtime gate still required.

Discovery-map contracts now exercise a missing TXT key and a missing TXT value
for each advertised protocol through the shipped JNI map getters. Scoped,
thread-local coverage fixtures affect only the requested owned context and
first record. Each case verifies that exactly one entry disappears, all other
entries remain unchanged and a subsequent ordinary request restores the full
map. Registration populates in-memory records without starting a listener.

All 20 selected JNI/server/init cases passed on API28 in 16.276 seconds without
skips. The matching library-only export has no LLVM warnings and covers all
47 bridge MC/DC conditions. It retains all 24 shipped JNI entries and exactly
the preceding recovery inventory: 920 branches, 202 functions, 204
instantiations, 1732 lines, 169 MC/DC conditions and 1514 regions. A separate
noncoverage native Debug build passed in 13 seconds; its library is byte-identical
to the preceding noncoverage build and contains none of the new fault wrappers
or coverage helper exports. No release APK was signed or published.

This adds one Android contract, bringing the current source suite to 193 Android
and 19 native cases. The already-running complete four-edition cohort remains
frozen at its preceding 192-test source identity. Its profiles must not be mixed
with this changed fixture mapping. These selected results establish neither
100% project coverage nor completion of the four-edition acceptance gates.

A real-stream output contract now holds the production output mutex on another
thread while querying diagnostics. The query must return the previously cached
underrun count within 500ms, preserve the stream and retain that count after
normal stop. The holder has a two-second release deadline so a blocking-query
regression produces a bounded failure rather than deadlocking the runner.

The 24-case output/JNI/server/init selection passed on API28 in 16.627 seconds
without skips. The matching library-only export independently covers both
conditions of the output diagnostic lock/stream guard, retains bridge MC/DC
47/47 and exactly preserves the preceding native inventory and 24 shipped JNI
entries, with no LLVM warnings. Only coverage fixture code and one Android test
changed; production output locking is unchanged. The current source suite now
has 194 Android cases; the ongoing frozen 192-case cohort remains separate.
A separate noncoverage native Debug build passed in 12 seconds; symbol checks
confirm the original entry points remain and the fault helpers are absent.

The ALAC contract also covers capture enabled before a PCM consumer is attached.
It decodes a real fixture packet through the normal RAOP callback with no sink,
attaches the consumer and then verifies every stereo sample, channel, timestamp
and short final packet using the existing fixture hashes. This adds one Android
case, bringing the current source suite to 195 cases.

All 27 selected output/JNI/server/init/ALAC cases passed on API28 in 17.888 seconds
without skips, after a successful 1m28s assembly. The matching library-only export
covers both conditions of the capture-enabled/consumer guard as well as the
output diagnostic guard and all 47 bridge MC/DC conditions. The mapped production
inventory and 24 shipped JNI entries remain unchanged; LLVM emitted no warnings.
A separate noncoverage native Debug build passed in nine seconds. These remain
selected contracts, separate from the ongoing complete frozen 192-case cohort.
The selected run's archived owned-source identity starts `2f864a89107d`; later
upstream source changes require fresh compiled mappings and execution evidence.

Five recording ViewModel contracts now bind a real stopped service and exercise
regular-file filtering and date ordering, canonical-directory traversal guards,
valid deletion, readable FileProvider sharing URIs and grant flags, unavailable
sharing targets, deferred refresh/deletion during armed capture, local playback
routing and disconnected actions. External chooser launches are captured at the
application context boundary; the provider and recording files remain real.
Each case cleans up its own files, closes the capture/service/log workers and
restores the launch-on-connect preference. DE/AT executes the same cases and
asserts the disabled recording actions cannot delete, share, capture or play.

The 21-case recording/playback/audio-state selection passed without skips on
API28 in both normal (7.231s) and DE/AT (6.676s) editions. Assemblies passed in
11 seconds and 1m40s respectively; matched archived-class JaCoCo reports passed
in seven and nine seconds. In the normal selection, recording-file validation
covers 14/14 branches; deletion, sharing and recording toggling each cover 4/4.
Refresh and playback retain uncovered paths, and the DE/AT private validation
helper remains uncovered because its public recording actions are disabled.
These counters remain visible in the reports; no coverage inventory is removed.

The current source suite now has 200 Android cases. The ongoing full four-edition
run remains frozen at 192 cases and must finish against its original mappings;
these selected profiles cannot replace its acceptance gates or establish 100%
coverage for the current source.

Six MainActivity lifecycle contracts now use Android-driven activity transitions
and a real bound receiver. They cover configuration recreation with a retained
ViewModel, callback replacement and ownership on destruction, idle foreground
shutdown, background retention, active sender/local-video/mirroring protection
and keep-screen-on changes. A separate real PiP round trip reopens the existing
activity through a launcher-equivalent intent and verifies the receiver session
survives. Stop-policy cases temporarily deny PiP so Android reaches onStop instead
of keeping the activity visible in its pinned window. Both PiP policy changes
check the effective AppOps decision and restore the original policy.

All six cases passed together on API28 in 104.910 seconds without skips. The
matching archived-class JaCoCo report passed in 15 seconds and confirms onStop
covers all 46 instructions and 12 branches; the PiP-mode callback covers all ten
instructions. onDestroy retains one uncovered branch. The production lifecycle
implementation is unchanged, and its source bytes match the preceding normal
192-case cohort. The final assembly passed in 36 seconds and lint passed in
4m46s in the separate clean checkout, with the same test source. These are
selected lifecycle results, not complete current-edition coverage.

The current source suite now has 206 Android cases. The preceding frozen
192-case cohort completed all required runtime gates independently for normal,
compat, DE/AT and compat DE/AT, with 19 native cases and all 192 Android cases
passing in each. Its strict 100% check still fails because coverage remains
incomplete; its profiles must remain separate from the changed current inputs.

The now-playing audio panel could compress its volume sliders into overlapping
rows on a short landscape viewport. The panel now bounds the cover art to the
available space and scrolls instead of compressing the controls. A seventh
activity contract starts a real bound audio session, scrolls to the receiver
slider, adjusts device media volume by touch and verifies single-step decreases
and increases with the remote's arrows. It also checks that sender volume stays
unavailable without a remote endpoint and restores the original device volume.

The isolated contract passed on API28 in 19.538 seconds. The matched JaCoCo
report covers all 329 instructions, 10 branches and 29 lines of `VolumeSlider`,
which was unexecuted in the frozen 192-case baseline. All 12 activity and main
screen regression cases also passed together on API28 in 168.854 seconds.
The source suite now has 207 Android cases. The in-flight 206-case matrix keeps its original source and
profiles, and the new test must run in a fresh matrix for all four editions.

A bound-service contract now resolves a loopback DACP endpoint and verifies
the real HTTP volume commands, remote token and sender feedback. The service
stops stepping when feedback crosses the target or reaches it, and independent
sender control preserves the receiver's device volume. The contract restores the
controller, preference and device volume after execution. The shared HTTP test
fixture listens only on loopback.

All 20 service, DACP request and DACP discovery tests passed on API28 in
3.342 seconds. The matched report covers 17 of 22 branches in `_clientVolStep`,
compared with zero in the frozen 192-case normal report, and all instructions
in `onClientVolumeQuery`. The current source suite now has 208 Android cases.

The same DACP contract now also verifies that retargeting while a command is
pending sends no second command, missing feedback expires the target, late
feedback does not continue an expired request and repeated identical feedback
stops at the 32-command limit. The 20-test selection passed again on API28 in
5.057 seconds. The matched report covers 4/4 branches in `_driveClientVol`
and 18/22 in `_clientVolStep`. The Android case count stays at 208.

A separate synchronized-volume contract checks that the receiver follows the
sender's mute request on the actual Android media stream. Duplicate mute reports
preserve the saved level, which the service restores on sender disconnect. The
21-test service and DACP selection passed on API28 in 8.389 seconds. The matching
report covers all 4 branches in `setClientVolume` and all 12 in `_applyClientVol`.
The current source suite now contains 209 Android cases.

The Android native unit executable now injects allocation failures into the
production DNS-SD initialization routine. The contract checks the error
reported at each allocation site, release of any partial state, and clean
initialization when allocations succeed again. The allocator wrappers are
linked only into the native tests.

All 20 native tests passed under UBSan on the API28 emulator. Exporting the unit
profile against the matching application library confirmed all three MC/DC
conditions of the allocation guard in dnssd_init. This result is from the
native unit run. The complete four-edition cohort has separate runtime gates.

The API28 four-edition attempt at 206 Android cases stopped in the normal
edition. Its runner reached the 900-second timeout while the framework waited
for the main queue to idle after the receiver started. The lifecycle fixture now
sets the system animator scale to zero while exercising the real activity
transitions and restores the original setting during cleanup.

The normal API28 lifecycle and main-screen selection passed all 12 tests in
117.152 seconds. This includes the previously stalled idle receiver lifecycle and
the real PiP round trip. The run verified that the emulator's original animator
scale of 1.0 was restored after the selection.

The DNS allocation test now checks each failure with and without the optional
error output. All 20 native tests passed under UBSan on API28. Export against
the matching application library covers all 12 branch outcomes and all three
MC/DC conditions in dnssd_init.

The service audio tests now verify that a music-to-mirror transition disables
the Android media session and clears the prior track and playback position.
Audio received for the mirrored screen stays in mirror mode. The normal API28
service and DACP selection passed all 22 tests in 5.383 seconds. Its matching
report covers 4/4 branches in onMirrorRunning and 9/12 in _setAudioOnly.
The source suite now contains 210 Android cases.

The full normal API28 run showed that disabling animations alone moved the
lifecycle stall to a later idle wait. The fixture now drives actual Android
activity operations and waits for their observed lifecycle stages. It leaves
the system animator scale alone. All seven lifecycle cases passed within a
210-case normal instrumentation run, including the previous stall, stop/resume,
configuration recreation and PiP. The run reached its end in 362.033 seconds
with two failures elsewhere, in settings buffer editing and diagnostic dialog
dismissal, so the complete edition remains unverified.

The settings fixture now checks that the actual editor has focus and shows its
normalized draft before dispatching IME Done. All six settings cases passed on
normal API28, including resetting the maximum output buffer to automatic sizing.
The same run still reproduced diagnostic dismissal failing in the Tools screen.

The Tools diagnostic fixture now waits for the diagnostic window to receive
input focus before injecting a touchscreen tap on Close or pressing Back. It
verifies the Tools screen is visible again after each action. The API28 normal
selection then passed all ten Settings and Tools tests in 48.080 seconds.
Both failures from the 210-case normal run are covered by that selection.
A complete matrix run is still required.

The 210-case API28 normal run passed 209 cases. Its only failure was an intro
touch test with a fixed wall-clock threshold. The skip tests now compose the
actual intro against a controlled animation scale and verify that home was
still hidden when the input arrived. The natural-completion case keeps the
ordinary animation clock.

The coverage-only Oboe fixture now rejects an invalid output format at startup
and tests losing an opened stream before it starts. The latter exercises a
real OpenSL ES stream, then verifies that the same output can reopen normally
with automatic buffer sizing. The 31-case normal API28 selection passed in
31.773 seconds. Its matched native report covers 4/4 branch outcomes in
AudioOutput::start and 17/20 in AudioOutput::openLocked.
The source suite now contains 211 Android cases.

The FFmpeg contract exercises the real software decoder's context, extra-data,
packet and frame allocation failures. It verifies that partial decoder resources
are released, covers an absent decoder and invalid ALAC configuration, and checks
that malformed packet input is rejected before a valid packet produces 352 stereo
frames at its supplied timestamp. The fixture is compiled in the production
decoder translation unit only for coverage builds to avoid extra coverage copies.

All 36 selected normal API28 tests passed in 37.164 seconds. The matched
native report covers 12/12 branch outcomes and both MC/DC conditions in
FfmpegDecoder::init. It also covers 10/12 branch outcomes in decode and both
MC/DC conditions of its input-length guard. The suite now has 212 Android cases.

The software decoder test now supplies an empty frame, a mono frame to the
stereo receiver, and an unsupported 32-bit PCM frame at the FFmpeg receive
boundary. Each uses a real compressed input packet. Unexpected frames emit
no PCM, and the next valid packet decodes with the expected timestamp.

All 36 selected normal API28 cases passed in 44.910 seconds. The matched
report covers all 44 branch outcomes in FfmpegDecoder, including 12/12 in
decode and 10/10 in toPcm, and all four mapped MC/DC conditions for the class.

The diagnostic output contract supplies controlled Oboe responses for an
unidentified backend and for AAudio reporting MMAP. The Kotlin receiver checks
the actual emitted log messages. A failed underrun query must retain the last
successful value. The suite also exercises real playback and reopening on the
emulator in its other cases.

All 37 selected normal API28 cases passed in 39.960 seconds. The matched
report covers all 42 branch outcomes in AudioOutput and OboeCallbacks. It also
covers both mapped MC/DC conditions in the MMAP decision and both in the
underrun polling guard. The suite now contains 213 Android cases.

The 211-case pinned matrix completed all runtime gates for normal and generated
its report while compat started building. The report measured 70.5111% of
Android/JVM branch outcomes and 92.0652% of native branch outcomes for that build.

The receiver notification test checks the platform notification returned by the
service for idle, PIN pairing, and audio playback. It checks artwork, the media
session token and transport actions. On the recording-enabled edition it arms
capture, verifies the recording notification takes priority, and sends its
actual Stop pending intent to return to the media notification.

All 23 service and DACP tests passed on normal and DE/AT in API28 emulators.
Their matched reports cover 16/18 and 15/18 branch outcomes in
AirPlayService._buildMediaNotification, respectively. The pinned normal
211-case report covered 9/18 in this method. The suite now has 214 Android cases.

The media notification test now exercises an absent session and an adapter
that returns no session token. Both cases keep the track title and omit
transport actions. The DE/AT test supplies an in-memory recording status to
exercise notification presentation. The normal test sends the live Stop action.

Both API28 editions passed all 23 service and DACP cases. Their matched
reports cover all 18 branch outcomes and 221 instructions in
AirPlayService._buildMediaNotification.

The pinned 211-case compat run compiled, passed lint and passed all 20 native
tests. Its one instrumentation failure came from the profile test assuming a
device probe would still be pending. The test now waits for a controlled probe
to start and verifies that it completes cancellation when the default profile
replaces it. The ordinary entry point continues to read device capabilities
on the IO dispatcher.

All 10 selected playback and profile cases passed on compat API28 in
4.044 seconds. The complete four-edition verification remains required.

The receiver display test verifies explicit portrait and landscape settings,
automatic orientation, and custom resolutions above and below the decoder's
width and height limits. The suite now has 215 Android cases.

All 24 selected service and DACP cases passed on normal API28 with the same
instrumented build in landscape and portrait. The combined report covers
17 of 18 branch outcomes and 157 of 159 instructions in
AirPlayService._displaySize.

Android display mode reports the physical panel dimensions even when the
emulator configuration rotates. The display calculation now takes the panel
dimensions and Android orientation as inputs. Instrumentation exercises
landscape, portrait, and square panels from a single emulator while the service
test still checks the live configuration and decoder limits.

All 25 selected service and DACP cases passed on normal API28. The matched
report covers all 18 branches and 143 instructions in receiverDisplaySize
and all 23 instructions in the AirPlayService._displaySize delegate. The suite
now has 216 Android cases.

The lifecycle instrumentation now checks resolution advertisements on a real
running receiver. It dispatches controlled configuration callbacks and
verifies that a new automatic orientation advertises once. Repeated
orientations, fixed resolutions and stopped receivers emit no update.
All eight lifecycle cases passed on normal API28. The matched report covers
7 of 8 branches and all 64 instructions in AirPlayService.onConfigurationChanged.
The suite now has 217 Android cases.

The compat report pinned at c13c113 passed 214 Android and 20 native cases
and all 15 runtime result checks. Its report measured 70.7297% JVM branch
coverage and 93.7229% native branch coverage.

The JNI callback tests now inject unavailable environments, peer-string
allocation failure and a Java exception into connection admission. Each
failure rejects the peer and the next valid callback can accept it. A PCM
case starts with a pending exception and verifies that it is cleared before
allocation and delivery. An unreferenced static callback stub was removed.

All 15 callback cases passed on normal API28. LLVM coverage from the
matching APK library shows all 6 admission and all 28 PCM callback branch
outcomes. The suite now has 219 Android cases.

The native video ready wait preserves one absolute deadline across wakeups.
It now releases the playback lock and returns on any condition-variable
error, including a timeout. The native unit contract exercises a wakeup
without readiness followed by readiness, timeout, and another error.

All 21 native unit cases passed on API28. The previous implementation failed
only the new case when the wait returned EINVAL. With the corrected build,
the unit profile mapped to the normal APK library covers all four branch
outcomes in android_raop_callbacks.c:_video_wait_ready.

The JNI attachment test lets the simulated JVM attach a native thread and
then injects a failure in the thread-local detach registration. The
production callback detaches the thread immediately, skips delivery, and
can deliver the next valid callback. The linker hook is coverage-only.

The normal API28 emulator passed all 16 callback tests. Their native
profile covers 13 of 14 outcomes in android_raop_callbacks.c:_get_env.
Together with the standalone TLS key-creation failure profile for the
unchanged callback source, all 14 outcomes are covered in the normal APK
mapping. The suite now has 220 Android cases.

The pinned c13c113 DE/AT run passed 213 of 214 Android cases. The settings
switch test found the ALAC title during an animated accessibility scroll,
then lost it before reading the rendered checked state. The settings test
now scrolls the real screen in overlapping increments and waits for the
layout before inspecting and clicking each control. The app retains its
default saved scroll state.

All six Settings cases passed on the updated DE/AT API28 APK at
1920 x 1080 and density 320. The previously failing switch case passed
two additional runs on the same APK.

The receiver integration suite now sends live SOAP requests to the DLNA
listener and verifies playback through its recording player port. Cases
cover seek, pause, resume, stop, live-duration reporting, rejected media,
player failure, ownership handoff, photo routing by file extension or MIME,
and expiration when no photo viewer opens.

All 13 receiver integration cases passed on normal API28. The matched
report for DlnaReceiver.kt covers 91 of 126 branches, including 25 of 30 in
the player-state tick, 15 of 16 in prepareAndPlay and 18 of 22 in its posted
callback. The suite now has 223 Android cases.

The DLNA tests now verify RenderingControl against the Android media stream,
including denial, volume clamping, sequenced GENA notifications, and
restoration of the original volume. A separate case holds the main looper
until AirPlay claims playback after the SOAP request was accepted, then
verifies the queued DLNA load is discarded.

All 15 receiver socket integration cases passed on normal API28. On the
identical application APK, the selected DlnaReceiver report grew from
91 to 94 out of 126 covered branches and from 108 to 116 out of 121 lines.
The suite now has 225 Android cases.

The DLNA integration tests now check the ownership and shutdown guards with
real requests queued on the Android main looper. Seek, pause, play and stop
do not reach a replacement FCast player. Closing the receiver discards a
queued load and volume update and closes its listening socket. Repeated
start calls create one listener.

All 17 receiver integration cases passed on normal API28. On the identical
application APK, DlnaReceiver coverage grew from 94 to 106 out of 126
branches. The suite now has 227 Android cases.

The receiver now validates each stored fallback MAC as six two-digit hex
octets before decoding it. Existing valid identities are kept. Invalid
values receive a new persisted AAI unicast identity. Its first octet
guarantees that it passes the zero and placeholder checks.

All 27 selected service and DACP cases passed on normal API28. The matched
report covers all 24 branches and 160 JVM instructions across the five
validation, persistence, generation and conversion methods. Hardware
interface enumeration is measured separately. The suite now has 229 Android
cases.

The network interface test uses the same selector called at receiver
startup. It verifies that unavailable or invalid hardware addresses
fall through to the saved identity. It also verifies that the selector
reads eligible Wi-Fi and Ethernet addresses lazily and keeps the first
usable address.

All 37 selected service, DACP and lifecycle cases passed on normal API28.
The matched report covers all 8 branches and 52 instructions in the
selector. It also covers the real Android adapter and two of its four
branch outcomes. The suite now has 231 Android cases.

If RAOP pairing or HTTP initialization fails, JNI destroys the partial
engine and callback binding and returns failure before the service tries
to start the listener. The lifecycle test forces this return status,
checks the service error state and resource release, and starts the
receiver successfully on its next attempt.

All 22 selected lifecycle and native receiver cases passed on normal API28.
The matching native profile covers both outcomes of RAOP initialization
and 19 of 22 branch outcomes in nativeInit. The suite now has 232 Android
cases.

The native init contract now forces RAOP allocation, RAOP configuration,
and DNS-SD allocation to fail separately. DNS-SD failure also aborts
initialization because RAOP needs its discovery context during requests.
After each injected failure the service reports the error and releases
its startup resources. A later attempt starts the receiver.

All 27 selected cases passed on normal API28, including the real JNI
callback lookup failure test. The matching LLVM report covers all 22
branch outcomes in nativeInit.

The foreground restart contract changes the active service's port to a
socket already held by the test. It verifies the receiver closes the old
RTSP listener and exits foreground mode on failure, then successfully
serves RTSP on the requested port once it is available.

All 28 selected cases passed on normal API28. Comparing the same app APK
before and after adding the case, restart coverage grew from 1 to 4 of
4 branches and failed-start coverage grew from 5 to 6 of 10 branches.
The suite now has 233 Android cases.

The service lifecycle test starts DLNA, FCast and Cast V2 through their
preferences and connects to each actual listener. USB-only mode closes
the three LAN listeners. Returning to LAN mode restores their sockets,
and stopping the service closes them again.

The initial selected run exposed a moving volume control during a touch
test. The gesture now settles before releasing its scroll, and the test
checks stable slider bounds before tapping. All 29 selected cases passed
on normal API28 after that adjustment. On the same APK, server startup
coverage grew from 19 to 29 of 42 branches. The suite has 234 Android cases.

The media ownership contract exercises the actual player as FCast hands
playback off to AirPlay and then to a local video. It checks that AirPlay
can pause immediately after requesting playback and that late AirPlay and
FCast commands leave the local video's speed, position and pause state intact.
AirPlay rate and seek commands now resolve ownership on the same queue as
playback startup and pass the active session token to the player.
All 30 selected lifecycle, receiver, and JNI tests passed on normal API28.
The matching JaCoCo report covers 17 of 22 branches in `beginMedia`.

The suite now has 235 Android cases. In the separate four-edition run pinned
at `1b991d4`, DE/AT passed all 231 Android tests, 21 native cases and the
required runtime gates. It reached 72.70% JVM branch coverage and 95.00%
native branch coverage. Those figures apply to the pinned build.

Four FCast socket contracts now verify playback and volume updates, pairing
refusal before the handshake, preservation of an active client when another
connects, and listener startup failure on an occupied port. All 21 selected
receiver integration tests passed on normal API28. The FCast receiver and its
update callback have identical compiled bytes across the two selected runs.
Together they went from 125 to 154 covered branches out of 208. The current
suite has 239 Android cases.

Three Media3 contracts exercise paused local playback with surface replacement,
seeking, speed, and subtitle changes, then verify that the renderer reports a
missing local file to its owning session. The session replacement contract
delivers late callbacks through the old listener and a recoverable error
through the active listener to verify ownership and retry notification.

All 33 selected Android cases passed on normal API28. With identical compiled
player classes, `AirPlayVideoPlayer.kt` went from 132 to 173 of 173 covered
lines and from 57 to 96 of 130 covered branches. The current suite has 242
Android cases.

The AAC configuration contract checks the exact AudioSpecificConfig bytes
for LC at 960 and 1024 samples and ELD at 480 and 512 samples. The fallback
contract injects platform configure and start failures independently in the
coverage build. It verifies release of each rejected platform codec, a
successful FFmpeg fallback with its own resource cleanup, and a clean failure
when the software allocation also fails.

The 19 selected native-audio Android cases passed independently on normal
and compat DE/AT API28. Both LLVM profiles cover all 10 branch outcomes each
in `codecConfig`, `startCodec`, and `makeDecoder`. The current suite has 244
Android cases.

Both new cases also passed on API22 using the same compat DE/AT APK and
test APK. Combining the profiles from that identical native binary raised
`startMediaCodec` from 8 to 11 of 14 covered branches.

In the separate full run pinned at `1b991d4`, normal passed all 231 Android
tests, 21 native cases, and the remaining runtime gates. It reached 73.05%
JVM branch coverage and 95.00% native branch coverage.

The automatic ALAC decoder contract delivers the original 352-frame stereo
fixture through the RAOP callback with the software override off. It checks
every PCM16 sample, channel order, frame count, and presentation timestamp.
The capture fixture accepts asynchronous output from a platform decoder and
joins it before reading the recorded PCM.

All 20 selected audio cases passed on normal and compat DE/AT API28. The
normal profile increased coverage of `startMediaCodec` from 7 to 9 of 10
branches. All six selected AAC and ALAC cases passed on the same compat DE/AT
APK on API22. Combining profiles for that binary covers 13 of 14 branches in
`startMediaCodec` and 116 of 130 in `audio_decoder.h`. The current suite has
245 Android cases.

The MediaCodec input contract uses a real dequeued slot and covers a missing
buffer, insufficient capacity, and rejection when queueing. It checks the
copied byte count, payload prefix, buffer index, and presentation timestamp.
The latency contract checks unmatched timestamps and verifies runtime metrics
with logging disabled. Both hooks are limited to the coverage build.

All 22 selected audio cases passed independently on normal and compat DE/AT
API28. On the unchanged decoder source in the normal build, coverage
increased from 10 to 14 of 14 branches in
`MediaCodecDecoder::decode`, from 2 to 4 of 4 in `LatencyProbe::match`, and
from 9 to 10 of 10 in `LatencyReporter::record`. The suite has 247 Android
cases.

The older full run pinned at `1b991d4` failed one compat DE/AT test after the
platform decoder rejected AAC packet 30. The same test passed in the newer
22-case regional selection. A full regional pass remains outstanding for
the current source.

The offline PCM fixture now lets the emulator wait up to 500 ms for a free
MediaCodec input slot. It still requires every submitted fixture packet to be
accepted and checks the decoded samples, frame sizes, and timestamps. The
separate startup and live-budget test still injects unavailable input with
the original timeouts and checks that the startup retry ends once the codec
accepts a packet. All 22 selected cases passed on compat DE/AT API28 after
the fixture adjustment.

The MediaCodec drain contract substitutes three output records in the
coverage build. The drain thread releases the missing, empty, and valid
buffers exactly once, and only the valid buffer reaches the PCM tap. The
test checks the byte offset and timestamp as well as the decoded samples.
The input-buffer contract also waits for codec warmup before checking what
happens after a slot is acquired.

All 23 selected audio cases passed on normal and compat DE/AT API28. The
matching reports cover all 10 branches of `MediaCodecDecoder::drainLoop`,
up from 7 of 10, and all 12 MC/DC conditions in `audio_decoder.h`. The suite
now has 248 Android cases.

The preferred AAC decoder contract supplies a real MediaCodec instance when
the named decoder is requested in the coverage build, then checks that it
starts, the MIME fallback is skipped, and the selected instance is released.
On API22 the contract checks that the named request is skipped and the MIME
decoder is used. The FFmpeg log contract captures Android log priorities and
checks warning and error formatting and filtering of informational logs.

All 25 selected audio cases passed on normal and compat DE/AT API28, and
all 13 selected decoder cases passed on the same compat DE/AT artifacts on
API22. The normal report covers 10 of 10 branches in `startMediaCodec`.
The combined compat DE/AT profile covers all 14 branches in that method.
Both reports cover every line, function, instantiation, region, and MC/DC
condition in `audio_decoder.h`. The two remaining branch outcomes are the
destructor's checks for an absent codec and a non-joinable drain thread.
The current suite has 250 Android cases.

`MediaCodecDecoder` now owns the started platform codec before launching its
drain thread. If thread creation fails, the codec is stopped and released
during construction unwinding, and decoder selection proceeds to FFmpeg.
Successful construction guarantees a joinable worker, which is joined before
the codec owner releases it. The regression forces `pthread_create` to return
`EAGAIN` and verifies platform and software resource cleanup.

All 26 selected audio cases passed on normal and compat DE/AT API28. All
14 selected decoder cases passed on API22 using the identical compat DE/AT
APK, test APK, and native binary. `audio_decoder.h` reached 100% on all six
native metrics in the normal selected run and in the combined compat DE/AT
profile. These reports cover this decoder source, while the full edition
reports cover the entire application. The suite has 251 Android cases.

The video decoder contracts now exercise Qualcomm option removal across
retries, the Adreno 620 operating-rate exception, Hisilicon, Exynos, and
Amlogic settings, and early return for codecs with native low latency.
The production selector still queries Android's actual codec capabilities
and renderer. The tests pass controlled codec names and capability responses
to the same format configuration code.

The nine selector cases passed on normal API28 and on compat DE/AT API22
and API28. The policy method covered 27 of 28 branches on API28, and 28 of
28 when combining the same compat DE/AT APK's profiles across both APIs.
The current suite has 253 Android cases.

The HEVC device contracts now exercise the selector's allowlist and blocklist
for emulator families, legacy software codecs, Adreno 399 and 400, Bravia,
NVIDIA K1 tablets, Sabrina, Fire OS, and PowerVR. They verify the Android 26,
28, and 29 thresholds using the same prefix policies as the platform selector.
All 12 selector cases passed on normal API28 and compat DE/AT on API22 and
API28. All 34 branches of the three device policy methods were covered in
the normal and combined regional reports. `DecoderSelector.kt` increased
from 102 to 122 of 188 covered branches in the selected normal run. The
current suite has 256 Android cases.

The full run pinned at `0a7158d` passed compat DE/AT with 247 Android tests,
21 native cases, and the remaining runtime gates. The edition reached 74.80%
JVM branch coverage and 96.97% native branch coverage.

The HEVC capability contracts check the decision order for the forced,
trusted, and measured fallback paths. For automatic fallback, AVC must
report that it cannot meet the request and HEVC must report that it can.
The trust contract verifies that software and ineligible hardware cannot
qualify through a performance class, native low latency, or a vendor match.
The platform adapter still gets device and capability data from Android.

All 14 selected cases passed on normal and compat DE/AT API28. Matching
JaCoCo reports cover all 16 branches of the two HEVC decision methods. The
API30 replay passed both new tests and failed the existing software AVC case
at 64 × 48. That API30 run remains incomplete. The suite has 258 Android
cases.

The API30 AVC replay was corrected after querying the emulator's actual
codec inventory. `OMX.google.h264.decoder` supports 64 × 48 on that emulator
and reports `isSoftwareOnly=false`. The selector now prefers platform
software flags where available, then tries known platform software prefixes.
Each candidate's size support is checked during the search so an incompatible
earlier candidate cannot hide a compatible one. The updated AVC contract
verifies a successful 64 × 48 selection and a size beyond the entire AVC
inventory. All 14 selector cases passed on normal API30 and compat DE/AT
API22.

The video capability contracts verify that an available performance-point
result decides the request, including when the advertised list is empty.
With no advertised data, the selector queries frame rates and retries portrait
dimensions in landscape order when needed. On API29 and later the tests use
Android `PerformancePoint` objects to check both resolution and frame rate.
All 16 selector cases passed on normal API30 and compat DE/AT API28. Their
JaCoCo reports cover both branches of the decision to use performance-point
data or the measured fallback. The current suite has 260 Android cases.

The full run pinned at `0a7158d` also verified the normal edition with 247
Android cases, 21 native cases, and all 15 gates. It reached 75.00% JVM branch
coverage and 97.28% native branch coverage.

The native bridge contracts exercise the actual RAOP log callback installed
at receiver initialization. They verify Android priorities for error, warning,
notice, info, and debug logs and check that message text preserves literal
percent signs. The debug AAC input contract uses a synthetic JNI table to
verify that an unavailable byte array is neither decoded nor released.
All 28 selected native bridge and audio cases passed on normal and compat
DE/AT API28. Each report covers all six RAOP log callback branches and all
ten debug AAC input branches. The suite has 262 Android cases.

The audio engine now returns a failure to JNI if allocation of a pending
configuration fails. The existing pending configuration stays in place.
A scoped allocator failure test verifies that the next real AAC packet
applies the retained configuration and that another update still works.
All 29 selected native bridge and audio cases passed on normal and compat
DE/AT API28. Both profiles cover all four branches of the audio engine
configuration method and both outcomes of its JNI result. The absent-handle
test lives in the separate `NativeBridgeContractTest` class. The modified
audio engine and its fixture also compiled for ARMv7 API22. The suite has
263 Android cases.

The frozen `0a7158d` full run also verified DE/AT with all 15 runtime
gates passing. It measured 74.66% JVM branch coverage and 96.96% native
branch coverage. These figures apply to the 247-case cohort.

Three Android settings tests switch discovery scope in both directions,
save the device orientation presets, and exercise blank, overflowing, valid,
and preset frame-rate values through the UI. All nine settings tests passed
on normal and compat DE/AT API28. The matching JaCoCo profiles cover both
discovery-summary branches, both frame-rate parsing branches, and all four
custom FPS editor action branches. Comparison with the identical compiled
settings class from the 247-case cohort identifies seven newly covered
branch outcomes in each profile. The suite has 266 Android cases.

Three bound-service contracts verify that invalid video dimensions preserve
the display state, a valid mirror takes over from the relay listener, and
orientation changes preserve the AirPlay session token. They also verify
that audio and track metadata survive the first of two disconnects and
that a mirror releases previous local playback without losing ownership.
All 19 service contracts passed on normal and compat DE/AT API28. Each
matching profile covers eight of ten video-size branch outcomes and the
previously uncovered callback that releases the old local player. The
suite has 269 Android cases.

Two service contracts connect an authenticated relay peer over loopback.
They verify relay ownership through a display resize, release on the end
packet, and preservation of the AirPlay mirror through queued relay teardown.
All 28 selected service and relay tests passed on normal and compat DE/AT
API28. Each matching JaCoCo report exercises 13 previously uncovered relay
callback branch outcomes. The video-size callback reaches nine of ten branch
outcomes in both profiles. The suite has 271 Android cases.

Two service contracts verify that the relay refuses peers during local video
or an AirPlay connection, accepts a peer when the receiver is free, and leaves
newer local playback active after queued mirror teardown. All 30 selected
service and relay tests passed on normal and compat DE/AT API28. The matching
JaCoCo profiles cover all four relay admission branch outcomes and both
outcomes of the queued playback ownership check. The suite has 273 Android
cases.

The frozen `0a7158d` run completed all four editions. Each passed the
247 Android cases, 21 native cases, and all 15 runtime gates. JVM branch
coverage ranged from 74.66% to 75.15%, and native branch coverage from
96.43% to 97.28%. The strict coverage gate remains below target in all
four reports.

Two lifecycle contracts exercise the foreground start action with a busy
RTSP port and the service recovery callback for stopped, disabled, USB-only,
disconnected, and reconnecting LAN states. The reconnecting LAN case checks
that the service replaces the receiver and responds over RTSP after restart.
All four selected lifecycle tests passed on normal and compat DE/AT API28.
Both JaCoCo profiles cover all ten network-recovery callback branch outcomes
and five of six foreground start command branch outcomes. The suite has 275
Android cases.

Two service tests deliver current and replaced player tokens to the playback
callbacks. They verify that dimensions, title, recovery, progress, and end
events affect only the current session. All 32 selected service and relay
tests passed on normal and compat DE/AT API28. The matching JaCoCo reports
cover all 12 branch outcomes of the size, title, end, and recovery callbacks,
and 13 of 14 playback snapshot branch outcomes. The suite has 277 Android
cases.

The `73f1e5e` run verified compat DE/AT with 275 Android cases, 21 native
cases, and all 15 runtime gates. It covered 4505 of 5896 JVM branches
(76.41%) and 906 of 922 native branches (98.26%).

Two settings contracts exercise the TV dialogs using key navigation. The
port test verifies invalid-draft rejection, cancellation, normalization,
and restored focus. The resolution test verifies that both dimensions are
applied together after the form is saved. All 11 settings tests passed on
normal and compat DE/AT API28. Each matching JaCoCo report covers 312 of 448
settings branches, up from 292 in the preceding settings run. The TV editor
file covers 28 of 77 branches on normal and 26 on compat DE/AT. The suite has
279 Android cases.

The audio diagnostics contract queues and applies a new engine configuration,
holds the rebuild lock, and polls from another thread. It verifies the cached
snapshot is returned while that lock is held and that the next poll sees the
new cushion. All 30 selected audio contracts passed on normal and compat
DE/AT API28. Both matching profiles cover all six engine debug branch
outcomes. Three API22 contracts passed with the same compat DE/AT APK and
native library as the API28 run. Their combined profile covers all 14 codec
startup branch outcomes, including the API22 AAC and ALAC paths. The engine
and its fixture also compiled for ARMv7 API22. The suite has 280 Android
cases.

The `73f1e5e` run verified normal with 275 Android cases, 21 native cases,
and all 15 runtime gates. It covered 4572 of 5976 JVM branches (76.51%) and
905 of 918 native branches (98.58%).

The Tools TV relay form contract checks read-only fields before editing,
remote focus movement, port normalization, and retention of accepted values
after reopening. The isolated ViewModel exercises the form without opening a
relay connection. All five Tools tests passed on normal and compat DE/AT
API28. The matching reports cover 14 of 16 relay dialog branch outcomes,
compared with three in the frozen run. Both reports also execute all
instructions in the three TV field update callbacks. The suite has 281
Android cases.

The ordered settings broadcast tests verify typed values and defaults,
ignored actions including JVM hash collisions, and rejection before
persistence. The nine selected contracts passed on normal and DE/AT API28.
Both reports cover all six JVM metrics for `SettingsReceiver`, including 21
of 21 branch outcomes. The foreground startup retry test now saves the port
before binding the service. In the frozen 275-case DE/AT run, a pending
preference restart closed the RTSP connection during the check. The corrected
test passed on DE/AT. The suite has 283 Android cases.

The adaptive AAC jitter contract feeds two consecutive timestamps with a
receiver-side stall and checks that the measured cushion reaches its
one-second limit while both packets still deliver PCM. It and the two
selected AAC regression tests passed on normal and compat DE/AT API28.
The matching LLVM profiles show one execution on each side of the ceiling
check in both editions. The suite has 284 Android cases.

The ALAC recovery contract rejects the first FFmpeg allocation, verifies
the retry deadline, and confirms decoded PCM after retrying the same format.
It then switches from 352 to 4096 samples per frame while keeping ALAC and
checks the new PCM size and timestamp. Both outcomes of the same-codec
format check execute in the selected LLVM profiles for normal and compat
DE/AT. The frozen 275-case profiles did not execute the format-change
outcome in either edition. The suite has 285 Android cases.

The receiver adapter tests cover repeated start and close, closing before
the first start, and refusal of connections after the listener closes. The
four selected contracts also exercised existing native initialization and
occupied-port failures. Normal and compat DE/AT API28 covered all six JVM
metrics for `AirPlayReceiverAdapter`, including 16 of 16 branches compared
with 13 in the frozen 275-case profiles for the same class. The suite has
287 Android cases.

Calibration tests check that an unsaved margin survives activity recreation,
Enter persists it, and both localized hints fit a narrow preview. The
preview's drawing paint is private, so Kotlin no longer emits an unused
accessor for the anonymous view. The targeted normal and compat DE/AT
API28 profiles cover all six JVM metrics for `CalibrationActivity`, including
16 of 16 branches. The suite has 289 Android cases.

The TV focus contracts verify the retry budget when a target is absent,
confirmation before or after a frame, and transfers between attached
controls in Compose. One transfer forces a transient failure in the target's
focus properties, then verifies that the next attempt moves focus. All six
JVM metrics for `TvFocus` were covered in the selected normal and compat
DE/AT API28 profiles, including 20 of 20 branch outcomes. The same bytecode
had 9 and 12 branch outcomes covered in the respective 284-case profiles.
The suite has 292 Android cases.

Network diagnostics use the same formatter for Android snapshots and
instrumented cases. The tests check the complete localized output for a
stopped receiver with no LAN IPv4, a VPN flag, and USB-only mode, and for a
running LAN receiver.
They also verify both states of DLNA and FCast. The formatter covers all 12
branches in the normal and compat DE/AT API28 selections. The full file
covers 15 of 18 branches, with three remaining in Android network capability
lookup. The suite has 294 Android cases.

The public diagnostics contract checks the report against the emulator's
active Android network. Running the same APK pair with connectivity and
then with no default network covers 17 of 18 diagnostic branches in the
normal and compat DE/AT API28 selections. The remaining branch requires
Android to report an active VPN transport. The suite has 295 Android cases.

The diagnostics contract also runs against a real Android VPN in a disposable
emulator. The debug-only service exposes a VPN transport to the app's package
and closes its tunnel after checking the report. For normal and compat DE/AT
API28, the connected, VPN, and offline profiles from the same APK pairs cover
all six JVM metrics for `NetworkDiagnostics`, including 18 of 18 branches.

The full matrix collector now runs the Android VPN and offline scenarios on
API 23 and newer, after the UI checks. It records each transcript and profile
against the installed APK pair and restores the emulator's network settings.
The relay form now sends Down from a valid pairing code to Connect. The
targeted compat test passed on API28 after the previous 284-case compat run
failed to reach Connect using Tab.

The experience profile contracts verify the saved video and audio settings
for Cinema, Presentation, Games, and Default. Invalid profiles and a device
without a supported mode leave the existing preferences intact. The implicit
Games profile reads the emulator's advertised AVC capabilities. Resolution
formatting now uses the non-null mode of a selected plan directly. All six
JVM metrics for `ExperienceProfiles` are covered in the selected normal and
compat DE/AT API28 profiles, including 24 branch outcomes. The suite has
298 Android cases.

The video surface tests exercise the real Android `SurfaceView`. They verify
that a live surface fits landscape and portrait bounds, notifies its owner
when removed, and respects caller-supplied playback dimensions. The selected
normal and compat DE/AT API28 profiles cover all six JVM metrics for
`VideoSurfaceView`, including 10 of 10 branches compared with 9 in both
284-case profiles. The suite has 300 Android cases.

The TV sidebar contract verifies that D-pad navigation moves focus while the
selection stays in place until OK, and that activation calls the latest
selection callback after recomposition. The selected normal and compat DE/AT
API28 profiles cover all six JVM metrics for `TvSidebar`, including 12 of 12
branches. The suite has 301 Android cases.

The About contract verifies the installed version and current copyright year,
the website and source intents, and that a missing browser leaves the dialog
open. It exercises returning from licenses with the button and Back, then
closing About. The selected TV profiles for normal and compat DE/AT API28
execute all 99 lines and 19 methods of `BuzinAbout`, including the link
fallback. They cover six of eight branch outcomes. The default-version
fallback and the non-TV focus outcome stay outside this TV selection. The
suite has 302 Android cases.

About now uses the version generated for its Gradle edition. The TV contract
checks that the displayed version equals the installed package version. A
second case exercises the ordinary Android UI mode and dismisses About with
Back. The combined selected profiles cover all six JVM metrics for
`BuzinAbout` in normal and compat DE/AT on API28, including all six branch
outcomes. The suite has 303 Android cases.

Connection approval contracts cover grant and denial deadlines, both
16-entry decision caches, shared requests for identical callers, timeout,
interruption, and replies from before a receiver restart. The production
constructor uses Android's monotonic clock and a 25-second request timeout.
The seven selected contracts cover all six JVM metrics for
`ConnectionApproval` in normal and compat DE/AT API28, including 44 of 44
branches. The suite has 308 Android cases.

The discovery-detachment contract keeps a real native listener and
temporarily hides its DNS records from the JNI bridge. It verifies opening
and closing the port, changing HLS while discovery is unavailable, and
restoring its advertised HLS flags. The selected normal and compat DE/AT
API28 profiles execute both outcomes of the discovery guard in start, stop,
and HLS configuration. The 295-case profiles had zero executions of the
unavailable-discovery outcome at all three guards. The fixture is linked
in coverage builds. The suite has 309 Android cases.

The JNI callback lifetime contract uses a native pthread and checks one
attachment across two deliveries and one detachment at exit. A separate
delivery with an already attached thread does not change ownership. The
destructor directly detaches the stored JVM because pthread calls it only
for a non-null thread-specific value. The normal and compat DE/AT API28
profiles run the destructor through real callbacks and the counted fixture.
All 12 selected cases pass on each edition. The suite has 310 Android cases.

Adaptive audio jitter now keeps its timing difference wide until the
histogram bucket is bounded. The native regression verifies a 180-day
clock gap saturates at the configured ceiling. Adaptive buffering starts
at zero and keeps the existing upper limit. Both selected editions passed
21 native UBSan cases and three Android AAC contracts on API28. LLVM
reports 14 of 14 branch outcomes in `DelayTracker::observe` in the native
unit executable and 13 of 14 in the selected APK runs. The unit executable
also compiled for ARMv7 API22.

The decoder takes a queued configuration with one atomic exchange. The
concurrent AAC contract verifies that a settings writer can queue changes
while the decoder delivers every PCM timestamp and that the next packet
applies the final 80 ms cushion. All four selected cases passed in normal
and compat DE/AT on API28. LLVM reports both outcomes of the exchange in
each edition: five packets consumed a configuration and 352 found none.
The suite has 311 Android cases.

The complete API28 matrix at `972df96` verified all four editions with
295 Android cases, 21 native cases, and all 15 runtime checks each. JVM
branch coverage ranged from 77.60% to 77.94%. Native branch coverage ranged
from 97.94% to 98.80%.

The DNS-SD TXT capacity contract fills a record with distinct keys, updates
one key at capacity, rejects an extra key, and checks that every remaining
key and value keeps its position. The fixture runs only in instrumented
builds. Both DNS-SD contracts passed on normal and compat DE/AT API28.
LLVM measured all six branch outcomes of `_txt_set` in both editions.
The matched 295-case profiles missed the existing-key update and the
new-key rejection. The suite now has 312 Android cases.

The adaptive delay contract uses explicit packet and arrival timestamps.
It verifies the initial cushion, a lower latency becoming the new baseline,
the ceiling after a later jitter spike, and the fixed cushion ignoring jitter.
The existing AAC integration contract exercises the same limit through the
decoder. Both cases passed on normal and compat DE/AT API28. Each LLVM
profile covered all 14 branch outcomes and both MC/DC conditions in
`DelayTracker::observe`. The suite now has 313 Android cases.

Three player remote-key contracts exercise select and directional keys with the
controls hidden, visible, and locked. They check that focus navigation remains
available, that hidden controls send signed seek feedback, that certain keys
reveal or unlock controls, and that media keys update playback or consume the
event. All three passed on normal and compat DE/AT API28. JaCoCo measured
50/50 branches, 239/239 instructions, 43/43 lines, and 26/26 complexity in
`handlePlayerKeyEvent` for both editions. The 295-case API28 profiles covered
28/50 branches in that method. The suite now has 316 Android cases.

The first verified edition of the API28 311-case matrix at `6e77219` is
compat DE/AT. All 311 Android cases, 21 native cases, and 15 runtime checks
passed. JaCoCo measured 4591/5892 branches (77.92%) and LLVM measured
905/912 branches (99.23%). The strict coverage gate remains open.

The playback surface contract measures real buffers for best fit, stretch,
crop in both aspect directions, and native-pixel sizing. It checks the
best-fit fallback before dimensions arrive and keeps the same surface while
changing modes. Two other contracts verify the four scale labels and icons
and the playback clock across the one-hour boundary. All five surface and
display cases passed in normal and compat DE/AT API28. Sizing covered 12/13
branches, up from 3/13 in the 295-case profiles. The remaining sizing branch
is the compiler-generated `NoWhenBranchMatchedException` default for an
exhaustive enum dispatch inside Compose. The labels, icons, and clock covered
all 10 branch outcomes across their three methods. The suite now has 319
Android cases.

Reading the composition density before the scale dispatch keeps the
modifier choice independent of composition-local reads. The normal and
compat DE/AT API28 profiles both cover all 12 sizing branches, 166
instructions, and 14 lines of the sizing lambda. All five checks passed in
each edition, exercising
the exact dimensions, the fallback, and the lifetime of the live surface.

The normal edition of the API28 311-case matrix at `6e77219` also passed
311 Android cases, 21 native cases, and 15 runtime checks. JaCoCo measured
4662/5972 branches (78.06%), and LLVM measured 904/908 branches (99.56%).

The remote seek feedback contract renders the indicator and checks that
it stays hidden until shown, replaces its signed offset and target during
seeks, displays a zero accumulated offset, and removes its accessibility
text when the overlay closes. It passed on normal and compat DE/AT API28.
The indicator's rendering lambda covered all four branch outcomes,
193 instructions, and 25 lines in each edition. The suite has 320 Android
cases.

The API22 replay of the frozen 311-case compat DE/AT APKs passed all 21
native cases. Two settings instrumentation cases failed: the audio-delay
slider range was not verified and the custom-height field could not be
focused. The replay did not produce an eligible merged coverage report.

The settings contracts now recognize the API22 SeekBar's zero-width
accessibility range. They verify the rendered value beside the actual
slider, the persisted preference, real keyboard focus, and DPAD changes.
On API28 they continue to verify the reported range value. The custom
resolution contract recognizes focus on the button or its labeled parent,
uses Right to move from Width to Height, and checks that the pair is only
saved together. Both contracts passed on the same compat DE/AT APK and
test APK on API22 and API28. The complete API22 replay remains pending for
the updated test build.

Player buttons now dispatch through the standard Compose click handler.
The input contract presses the same button by accessibility action, touch,
and remote OK and checks one action for each input. The toolbar contract
checks clipboard copying, availability of the download action, and
indeterminate and determinate progress. Touch is delivered through the
test activity's decor view so it reaches the real Compose pointer handler
on API22. Both targeted tests passed on compat DE/AT API22 and API28 and
on normal API28. Both API28 JaCoCo profiles cover all eight branch outcomes
governing copy and download visibility and progress. The suite has 322
Android cases.

The DE/AT edition of the API28 311-case matrix at `6e77219` passed all
311 Android cases, 21 native cases, and 15 runtime checks. JaCoCo measured
4588/5892 branches (77.87%), and LLVM measured 904/908 (99.56%).
The updated 320-case API22 compat DE/AT instrumentation run completed with
one failure in the activity idle-shutdown contract. Both settings cases
from the previous replay passed in that run.

All four editions completed the API28 311-case matrix at `6e77219`, each
with 21 native cases and 15 recorded runtime checks. The compat result was
4661/5972 JVM branches (78.05%) and 900/912 native branches (98.68%).
The four-edition coverage gate remains open.

The receiver now checks the bound service's current activity during
`onStop`. An API22 regression begins a connection at the lifecycle stop
event while the ViewModel snapshot still shows zero connections. This
reproduced an unintended receiver shutdown with the original APK.
The local-video scenario also reproduced a decoder error from a released
surface on API28. Playback surface callbacks on the main thread now
detach synchronously before the holder releases the surface and reject
invalid replacements. All four targeted lifecycle and renderer cases
passed on the compat DE/AT APK on API22 and API28 and on normal API28.
The API28 profiles cover all seven lines and 10/14 branches of `onStop`.
The suite has 324 Android cases.

The Android DNS-SD shim now returns its existing registration error code
when the discovery state is absent. Both invalid-state calls are covered
along with valid records, oversized hardware addresses, and TXT capacity.
Both API28 profiles, normal and compat DE/AT, cover all 18 RAOP
registration outcomes and all 14 AirPlay registration outcomes.

The 324-case API22 run at `2673805` passed the receiver idle and playback
surface cases. Two settings cases still failed after the earlier isolated
checks passed. The settings harness now waits for the updated accessibility
tree while scanning and uses the nearest labeled button group to check
focus. The three selected settings cases passed on API22 and API28 using
the same compat DE/AT APK pair.

Those matched API22 and API28 runs also passed the AAC decoder-selection
and automatic ALAC PCM contracts. LLVM covered both outcomes of the
device-API guard and both outcomes of the Lollipop ALAC fallback decision
in `startMediaCodec`. The merged profile uses the exact same native
binary and APK pair across the two devices.

Automatic picture-in-picture now checks the bound receiver's current
session. The API28 round-trip contract first leaves an idle receiver and
then starts a sender and issues the leave callback on the same main-thread
turn, while the ViewModel still reports no connections. It verifies that
the active receiver enters PiP and returns to the same activity. The
selected shutdown and PiP cases passed on normal and compat DE/AT API28.
Both profiles cover all six branches of the shared active-stream
decision and nine of ten branches in `onStop`.

The compat DE/AT API22 instrumentation suite at `ab239e5` passed all
324 cases, including discovery settings, both custom dimensions, idle
shutdown, and playback surface lifetime. The complete matched
four-edition coverage matrix remains pending.

The six lifecycle cases for `d227a50` also passed with the identical normal
APK pair on API36. The merged API28/API36 JaCoCo profile covered all four
branch outcomes in `onUserLeaveHint` and seven of eight in
`_shouldAutoPip`.

The binding-loss regression delivers the activity's ServiceConnection
callback while the receiver remains active and the ViewModel still reports
the previous connection. It verifies that leaving the activity keeps the
receiver running without entering PiP, then reconnects and verifies idle
shutdown. All seven selected cases passed on normal and compat DE/AT on
API28. Both profiles covered all 10 branch outcomes and all six lines in
`onStop`. The suite now has 325 Android cases.

The compat DE/AT edition of the API28 324-case matrix at `5e49ea9` passed
the 324 Android cases, 21 native cases, and 15 recorded runtime checks.
JaCoCo measured 4639/5887 JVM branches and LLVM measured 909/912 native
branches before merging any API22 profiles.

Two API22 codec cases replayed that edition's exact APK pair. Combining
their LLVM profile with the complete API28 measurements covered all 912
branches, 203 functions, 1751 lines, 171 MC/DC conditions, and 1500 regions
of the native app binary. Instantiations measured 204/205. The remaining
instance is the coverage bridge's inline copy of `monoNs` in
`audio_time.h`. This matched native result includes a selected API22 run
and the complete API28 suite.

The cached audio diagnostic contract now measures elapsed time with the
same monotonic clock used by the native audio code. The contract passed
on normal and compat DE/AT API28. Both LLVM profiles recorded two calls
to the previously unused coverage-bridge instantiation of `monoNs`.
The latest full build must
recalculate all six native metrics with its own matching profiles.

The lower player controls now have contracts for elapsed and remaining
time, rotate, lock, scale, and the availability of the PiP action. The
seek contract renders an unknown duration, then uses a focused slider in
an RTL layout to verify that Right advances, Left rewinds, and each
completed key gesture reports its end. The suite has 327 Android cases.
Both contracts passed on normal and compat DE/AT API28 and compat DE/AT
API22. The API28 reports cover all eight branch outcomes for the time
display, PiP action, known duration, and slider focus.

The normal edition of the API28 324-case matrix at `5e49ea9` passed its
Android and native cases and runtime checks. JaCoCo measured 4710/5967
JVM branches and LLVM measured 906/908 app-native branches.

The queued audio configuration contract now starts real Oboe output
before feeding AAC, checks that the next decoded packet opens replacement
output, and verifies that reissuing Start leaves that output active.
The test still checks the applied cushion and exact PCM timestamps and
bytes. The normal API28 profile executes both previously missing outcomes
that stop and restart an active output in `AudioEngine::decode`.

Two FCast socket contracts passed on the normal API28 build. One rejects
an HLS load during relay reception and accepts the same load after reception
ends, verifying default playback parameters. The other returns the playback
error to the client and verifies that the same connection responds to a ping.
The normal JaCoCo profile added three branch outcomes to the earlier full
API28 baseline for the byte-identical `FCastReceiver` class (139/186 to
142/186). This comparison applies only to that class. The complete matrix and
the regional variants still require their own reports.

The compact DE/AT build passed the audio scan contract on Android API22.
It drives the bound ViewModel through both scan directions and verifies the
HTTP commands and sender token at the loopback sender. Calls before a
controller is available and after unbinding send no scan commands. Against
the byte-identical `MainViewModel` class from the 324-case baseline, the
selected profile raised `audioScanBegin` from 2/6 to 6/6 branches and
`audioScanEnd` from 2/4 to 4/4.

The earlier FCast matrix assembled and passed lint for normal, but another
Gradle build switched editions before the runner archived the live mappings.
The runner rejected the missing mappings. This attempt produced no complete
edition report. The follow-up measures editions sequentially.

The sequential compact DE/AT run passed all 330 Android cases on API28,
including the new FCast and audio scan tests, and all 21 native cases under
UBSan. The runner then lost the accessibility hierarchy between the successful
dialog-close check and a second dump during the cancel scenario. The check
now reads the settled hierarchy once. The isolated cancel scenario passed
with the same APK after the fix.

Two AAC/ALAC codec contracts passed on API22 using byte-identical copies of
that run's app and test APKs. Combining their LLVM profile with the native
and Android instrumentation profiles from API28 measured 100% of all six
app-native metrics for compact DE/AT: 912 branches, 203 functions,
205 instantiations, 1751 lines, 171 MC/DC conditions, and 1500 regions.
This native result leaves the full runtime and JVM coverage gates open.

Four further contracts passed in compact DE/AT. On API22 the bound model
only opened a relay listener while the receiver was idle, and the playback
speed selector applied a preset, reset to normal speed, and closed via Back
or an outside tap. On API30 the settings screen handled denied and granted
overlay permission, including fallback to the app settings page. The
MediaSession also routed commands to the audio sender or local video player
according to the active owner.

Compared with the byte-identical class mappings of the 324-case baseline,
the selected JaCoCo profiles added 32 branch outcomes across the measured
classes for the model, speed selector, settings and service. The compact
DE/AT matrix APK was already frozen before these contracts were added, so
its reports still represent the 330-case suite.

The complete compact DE/AT API28 snapshot validated independently with
330 Android cases, 21 native cases and all required runtime scenarios,
including VPN and offline diagnostics. Against the 324-case report, JVM
branches increased from 4639/5887 to 4659/5887 (79.14%), and complexity
increased from 3934/5098 to 3956/5099 (77.58%). Native instantiations are
now 205/205, with the three missing API28 branch outcomes still in the
API22 decoder paths. The API22 replay uses the exact frozen APKs.

The FCast error contract passed for compact DE/AT on API28. A real local
FCast client received the error for its current media session and stayed
connected. Errors for local playback, replaced sessions and a detached
FCast receiver were ignored. The byte-identical service class's player error
callback reached 6/6 branches, up from 0/6 in the complete 330-case report.

The compact DE/AT API28 notification contract sent the real previous,
pause, next and resume pending intents. The local DACP sender verified
each HTTP command in order and the active remote token. In the
byte-identical broadcast receiver, JaCoCo covered 5 of 9 branch outcomes
that were untested in the complete 330-case baseline.

The compact DE/AT API28 relay contract passed with an authenticated local
receiver. It verified PCM format, timestamps and contents, then exact encoded
video bytes and dimensions. The receiver saw no video before dimensions
were available. Compared with the complete 330-case baseline for the
byte-identical service class, `onAudioPcm` rose from 0/4 to 3/4 branches and
`onVideoData` rose from 4/8 to 6/8.

The compact DE/AT API28 audio touch contract used the real player and a
local DACP sender. A held forward or reverse control sent its scan command
before the gesture ended. Releasing or cancelling the touch then sent
`playresume`. When added to the complete 330-case baseline's matching
class mappings, this profile covered six additional `MainScreen` methods
and one additional `MainViewModel` branch.

The first full API22 replay of the 330-case compact DE/AT APK completed
instrumentation with two failures: restoring receiver volume after
disconnect and focusing the touch port editor. Its full replay was
therefore not accepted as a completed coverage snapshot.

The affected service and settings test classes were repeated on API22
with the exact same app and test APKs. All 37 cases passed, including
both that failed in the full replay. A second full replay is running
with the same APKs and a fresh emulator boot.

The normal edition's local-network permission contract passed on
Android API37. A matching API28 instrumentation profile covers the
older platform path. Combining the two profiles for the identical
normal app and test APKs increased `hasLocalNetwork` from 1/4 to 4/4
branch outcomes. The API37 selection verifies both permission decisions
with a controlled context.

The complete normal API28 run passed all 334 Android cases, 21 native
cases and every required runtime scenario. All six app-native metrics
reached 100% in this run, including 908/908 branches, 203/203 functions,
205/205 instantiations, 1745/1745 lines, 171/171 MC/DC conditions and
1494/1494 regions. JVM branches reached 4755/5967 (79.69%), and
complexity reached 4020/5132 (78.33%).

The second complete API22 attempted replay passed the two cases that
failed previously, but could not enter text in the custom FPS editor.
The test recorded a visible label while the corresponding editor was
absent from the fresh hierarchy. The app's full API22 snapshot remains
pending while the test's focus handling is validated.

The touch editor fixture now retains the editor node identified by its
field label and refreshes that node during focus and text entry. This
keeps the assertion tied to the same field if the IME moves the label
outside the viewport. All 12 settings contracts passed on API22 with
the updated fixture.

The normal API37 permission UI rejected the first request, opened the
second request from the app, and automatically started the receiver
after the Android permission controller granted access. The app and test
APKs matched the complete normal API28 snapshot. The API37 profile raised
`MainActivity._syncLocalNetwork` from 1/14 to 11/14 branch outcomes and
`_requestLocalNetwork` from 0/4 to 2/4. A separate run verified that two
denials opened Android app settings. The emulator's system process crashed
after opening Settings, leaving the return-from-Settings path unverified.

The same normal APK pair passed all 16 decoder selection contracts on
API37. Compared with the complete API28 report for the matching classes,
the combined profiles covered 155/196 decoder-selector branches and 52/56
methods, up from 135/196 and 49/56. The newer platform run exercises
MediaCodec capability queries that are unavailable on API28.

The standard compat APK and its archived API28 test APK passed all 338
instrumentation tests, 21 native UBSan cases, and all runtime gates, including
VPN and offline diagnostics. JaCoCo covered 4795/5967 branches, 4057/5132
complexity units, and 7982/8445 lines. On this API alone, LLVM covered
909/912 branches, 1496/1500 regions, and 1748/1751 lines. Functions,
instantiations, and MC/DC reached 100%.

The byte-identical standard compat APK pair also passed the AAC
preferred-decoder and automatic ALAC PCM contracts on API22. With the
combined API28 and API22 profiles, LLVM covered all 912 branches, 203
functions, 205 instantiations, 1751 lines, 171 MC/DC conditions, and 1500
regions. This establishes all six native targets for standard compat.

A separate compat DE/AT API22 run passed 336 of 338 instrumentation tests.
Replaying the two failures three times with the unchanged APK pair passed
the custom-resolution focus test all three times. The relay audio and video
forwarding test passed once and timed out before authentication twice.
The service relay test now gives the software emulator the same 45-second
authentication wait used by the standalone relay contract and reports a
terminal sender error immediately. With the app APK unchanged, the adjusted
relay test and all 338 instrumentation tests passed on API22. The same
run passed all 15 gate entries, including 21 native UBSan cases. Its
archived profile covered 4670/5887 JVM branches, 3978/5099 complexity units,
and 903/912 native branches. The API28 replay passed all its applicable
gates with the same app, test, class and library hashes. Combined coverage
reached 4749/5887 JVM branches, 4033/5099 complexity units, 7963/8414
lines, and all six native targets, including 912/912 native branches.

Three additional compact DE/AT instrumentation contracts passed on API28.
The audio miniplayer applied a new track title and returned to the overview.
Subtitles imported through the content resolver into the bound player,
survived a rejected import, and were cleared by the remove action.
The DLNA control server served the device description on a fallback port
while another socket held its preferred port.

For byte-matched classes, the new profile added six branch outcomes and
two methods under `MainViewModel`, eight branch outcomes and three methods
under `MainScreenKt`, and two branch outcomes and two methods under
`ControlServer`, compared with the complete compact DE/AT API28 profile.

The next compact DE/AT API28 run verified that an in-flight subtitle
import is discarded after disconnect, replacement playback, or stop.
It also verified that losing an advertised USB link closes its socket,
sends zero-TTL records, and reports the loss once. The same run passed
the regional download policy test. With the preceding scope included,
coverage for byte-matched classes increased from 110/162 to 115/162
branches under `MainViewModel` and from 149/217 to 163/217 branches under
`UsbMdnsAdvertiser`. The standard edition's start/cancel path is measured
separately.

The API28 normal DE/AT run passed 337 of 338 instrumentation cases. The
single-tap gesture contract failed once, then passed three times with the
unchanged APK pair. Its fixture now waits for Compose to apply each gesture
mode and dispatches a tap's down and up events without an idle wait between
them. The same app APK passed all three gesture-surface contracts with the
adjusted test APK. A fresh normal DE/AT run then passed all 15 gate
entries on API28, including 342 instrumentation and 21 native UBSan
cases. That snapshot covered 4750/5887 JVM branches, 4039/5099 complexity
units, 7971/8414 lines, and all six native targets, including 908/908
native branches.

The compact DE/AT API28 remote-seek contract passed with a synthetic
40-second H.264 clip. Two right presses with the controls hidden showed
20 seconds of cumulative feedback and moved playback to 20 seconds. After
the feedback expired, a left press showed a fresh 10-second rewind and
moved playback back. The miniplayer contract passed in the same run. On
byte-matched classes, the profile added ten branch outcomes and five
methods under `MainScreenKt`.

The playback Download action passed on both compact editions on API28.
Standard compat started and cancelled an active export. Compact DE/AT
kept the downloader idle for the same action. The standard run used the
byte-identical app APK from the complete API28 snapshot and added three
branch outcomes under `MainViewModel` and one branch outcome and method
under `AirPlayService`.

After a later remote-seek run missed the one-second feedback window, the
fixture began delivering both right presses during the same main-loop turn
before waiting for the cumulative label. That case and the rotate-player
case passed twice on API28 with the same APK pair. The rotate control applied
both orientations on the same activity while retaining the paused session.
The new profile added four branch outcomes and one method under
`MainScreenKt` beyond the earlier remote-seek profile.

The same normal DE/AT app and test APKs from the complete API28 run
passed the picture-in-picture round trip and the disconnected-binding
contract on API36. The API36 profile exercised the automatic-entry
configuration introduced in Android 12. For byte-matched `MainActivity`
classes, the combined profile increased covered branches from 69/122 to 75/122,
methods from 33/44 to 38/44, and classes from 8/16 to 12/16.

All six Tools screen cases passed on compact DE/AT API28. The diagnostic
report test checks the rendered report after accessibility scroll actions
and after remote Down and Up, including the return to its initial position.
Android 9 did not emit observable scroll events for the accessibility
action, so the test compares the settled report pixels. It added five
branch outcomes and two methods under `ToolsScreenKt`.

A paused 1:4 video reproduced an Android API28 crash when Home
triggered PiP. The platform rejected the requested window aspect ratio.
The app now clamps only the PiP window ratio to the supported range. The
same narrow clip kept its paused session through PiP and fullscreen on
API28 using Home and on API36 using explicit entry. Accessibility Home did
not enter PiP on the API36 emulator, so automatic entry there is unverified.

The standard normal API28 run passed 343 of 345 instrumentation tests.
The two receiver failures were a reset during the optional LAN receiver
connection and an empty RTSP reply during restart. Both cases passed twice
on the unchanged APK pair. The fixture now waits for listener readiness
before asserting success and still rejects an incorrect RTSP status.
The two cases, two additional receiver restart contracts, and the narrow
PiP contract passed together on standard normal API28 with the adjustment.

The full standard normal API28 run at `538db32` passed all 15 gate
entries, including 347 instrumentation tests and 21 native UBSan cases.
The archived report covered 4852/5967 JVM branches, 4101/5132 complexity
units, and 8059/8445 lines. This run measured 905/908 native branches.
The remaining outcomes were in the Oboe output callback's attenuation
path, which did not execute in this run.

Compact DE/AT API28 passed the connection approval dialog contract with
Back, Decline, and Allow. The launch-on-connect case left the app
backgrounded after a disabled first connection and after an additional
sender, then showed the app for the next first sender. Together the
profiles added seven branch outcomes and ten methods across
`MainViewModel`, `MainScreenKt`, and `AirPlayService`.

The same edition passed the subtitle picker recovery contract. A missing
document activity kept its dialog open with an error, and a later picker
launch cleared the warning. This added six branch outcomes and three
methods under `MainScreenKt`.

The coverage-only Oboe fixture now checks the real callback's signed
stereo PCM at full, half, and zero gain without opening an audio device.
It passed on compact DE/AT API28 and exercised all four branch outcomes
in the callback. The new diagnostic fixture requires a fresh profile for
each complete APK measurement.

The Java consumer of Kotlin's legacy `PlayerPort` bridge kept an
on-demand item seekable with its reported duration. All 192 DLNA-domain
tests passed. Combining that JVM profile with the same archived Android
profiles and class bytes added one method and one class to the app report.

The complete normal DE/AT run at `538db32` on API28 passed 347 Android
instrumentation tests, 21 native UBSan cases, and all 15 runtime gates.
The archived report covered 4774/5887 JVM branches, 4060/5099 complexity
units, 8014/8414 JVM lines, and all six native metrics at 100%, including
908/908 native branches.

The archived standard normal API28 APK pair from the same revision
passed 17 scoped tests on API30 for overlay permission and the platform
decoder inventory. Combining the API30 profile with the complete API28
run on matching class bytes added seven branch outcomes and one method
under `SettingsScreenKt`, plus 18 branch outcomes and four methods under
`DecoderSelector`.

The standard compatibility API28 run at `538db32` passed 346 of 347
instrumentation tests. The subtitle session-transition test failed while
looking up a new child coroutine. The revised test holds the real file
read until the receiver detaches, the session is replaced, or playback
stops, then waits for that specific import to finish. The revised test,
the subtitle picker recovery, and the Oboe gain contract passed three
consecutive rounds on the standard compatibility build. The matching
native profile exercised all four outcomes in `OboeCallbacks::onAudioReady`.

The complete compact DE/AT API28 run at `538db32` passed all 347 Android
tests, 21 native UBSan cases, and 15 runtime gates. It covered 4775/5887
JVM branches, 4060/5099 complexity units, and 8019/8414 JVM lines. The
native report covered 906/912 branches. Its six missing outcomes were
in the pre-API23 AAC/ALAC selection and the Oboe attenuation callback.

The standard compatibility API28 run at `4b0fd2e` passed 351 Android
instrumentation tests, 21 native UBSan cases, and all 15 runtime gates.
It covered 4868/5967 JVM branches, 4129/5134 complexity units, and
8082/8447 JVM lines. The synchronized subtitle transition and
deterministic Oboe gain contracts passed in the full run. Native branch
coverage was 909/912. The only uncovered outcomes were codec selection
before API23, which requires a replay of the same APKs on API22.

On standard normal API28, taps on the video background showed and hid
the unlocked toolbar, then showed and hid only the unlock button while
locked. Unlocking restored the toolbar and the clip stayed paused. The
profile added four branch outcomes and two methods under `MainScreenKt`.
The Oboe gain contract passed in the same scope and exercised all four
callback outcomes on the native library matched to the installed APK.

The Tools screen requested the Fire TV mirroring activity and displayed
recovery instructions when launching it failed. The mirroring, relay
form, and recording policy tests passed together on standard normal
API28. The profile added three branch outcomes and two methods under
`ToolsScreenKt`.

The compact DE/AT API22 run at `4b0fd2e` passed 351 Android
instrumentation tests, 21 native UBSan cases, and all 15 runtime gates.
It covered 4740/5887 JVM branches and 4050/5101 complexity units. Three
AAC, ALAC, and output gain cases then passed on API28 with byte-identical
copies of the archived app APK, test APK, and native library. Combining
those native profiles covered both API-level decoder paths and all four
Oboe gain outcomes. The combined scope reached 910/912 native branches
and 170/171 MC/DC conditions. The remaining branch outcomes were both
in the Oboe backend and memory-mapping diagnostic log.

The standard normal API28 recording test used two actual files. Cancel
and remote Back preserved the selected file. Confirm removed only that
file and refreshed the list. Its touch-driven confirmation profile
added 15 branch outcomes and eight methods under `ToolsScreenKt`
relative to the complete standard normal `538db32` snapshot.

The legacy network selector passed against the real Android network list
on API28. The four connected diagnostics tests passed, followed by the
offline diagnostic after both emulator radios were disabled. Matching
profiles covered 11/14 branch outcomes inside the legacy selector.

On standard normal API28, a sender poll reopened pending playback after
a local Stop issued before media arrived. The service now suppresses
further polls in that state. The regression passed on the rebuilt APK,
including a subsequent explicit media load and Stop. The two playback
ownership cases also passed against the same APK pair.

The complete compact DE/AT API28 replay of `4b0fd2e` passed all 351
Android tests, 21 native UBSan cases, and 15 runtime gates. The exact input
hashes matched the API22 snapshot. The merged report reached all six
native targets, including 912/912 branches and 171/171 MC/DC conditions.
It measured 4821/5887 JVM branches, 4108/5101 complexity units, and
8055/8416 JVM lines.

The standard compatibility API22 replay of `4b0fd2e` passed the same 351
Android tests, 21 native UBSan cases, and all 15 runtime gates. Merging
its profiles with the byte-matched API28 snapshot reached all six native
targets. The combined report measured 4905/5967 JVM branches,
4156/5134 complexity units, and 8096/8447 JVM lines.

The standard normal API28 build at `711845e` kept its compiled APKs and
mappings when the Windows lint scan was interrupted. With those frozen
inputs, all 353 Android tests, 21 native UBSan cases, and 15 runtime gates
passed. The final report covered all six native metrics, including 908/908
branches and 171/171 MC/DC conditions. JVM coverage reached 80,248/86,570
instructions, 4,873/5,967 branches, 8,085/8,447 lines, 4,135/5,134 complexity,
2,175/2,351 methods, and 306/313 classes. The local Windows lint run did not
finish.

On standard normal API28, touching the pending video left its toolbar
visible. Remote Back returned to the overview while sender polling
continued. Compared with the complete standard baseline plus the earlier
touch-controls profile, this added six branch outcomes and one method
under `MainScreenKt`.

The queued AirPlay callback test rejected a request from before startup
and a request queued across shutdown. A fresh request opened playback
after the receiver restarted. The scope covered 5/6 outcomes in the
`onVideoPlay` handler. That test, the pending-video UI test, and the
earlier playback ownership test passed together. The ownership test now
uses a 40-second clip so playback stays available for the rate assertions.

The standard normal API28 VPN test verified that the initial network
snapshot does not trigger recovery, connecting and disconnecting a VPN
reports both route changes, and a queued check after closing the observer
leaves the restored route unchanged. Against byte-matched classes from
the earlier complete standard run, `NetworkRecovery` increased from 12/24
to 15/24 branch outcomes and from 9/10 to 10/10 methods.

The expanded diagnostic scenario checks that an unchanged route emits no
notification and that removing the VPN while both radios are off reports a
lost route. On API25, the platform's default-network callback missed the
VPN transition even though the diagnostics reported the VPN transport.
The observer now registers a separate VPN callback on API24 through API27
and applies the existing debounce and route comparison to both callbacks.
The online and offline diagnostics passed on API25 and API28 with
identical app and test APKs. The API25 emulator guest needed root ADB
for the radio toggle because its shell user lacks `CHANGE_WIFI_STATE`.
The combined scoped profiles covered 30/34 branch outcomes and all 10
methods in `NetworkRecovery`.

The normal DE/AT API28 snapshot at `2123209` passed all 356 Android tests,
21 native UBSan cases, and 15 runtime gates. All six native metrics reached
100%, including 908/908 branches and 171/171 MC/DC conditions. The JVM
totals were 79,426/85,759 instructions, 4,809/5,889 branches, 8,049/8,421
lines, 4,102/5,103 complexity, 2,170/2,352 methods, and 305/313 classes.

The settings contracts check that blank or zero dimensions leave the
saved resolution intact and that IME Done commits normalized valid
dimensions. The audio contract taps the delay slider, verifies the
persisted result, and checks that disabling and enabling the setting
restores the default delay. On standard normal API28, both passed and
raised byte-matched `SettingsScreenKt` coverage from 320/448 to 324/448
branches and from 169/187 to 172/187 methods.
The compact DE/AT API22 run improved the same counts against its combined
API22 and API28 baseline. The test sends the pointer events through the
activity window because API22's system input injection can target another
window and reject the gesture.

The relay contracts check that eight rejected peers expire a listener
without ending a stream, that a new listener can accept a peer, and that a
sender can retry after a rejected pairing. The accepted sender sends an
authenticated keepalive while idle, followed by video and a clean end
frame. All nine relay tests passed on API28 in both normal editions.
Against their byte-matched full baselines, `MirrorRelay` branch coverage
went from 82/122 to 87/122 in the standard edition and 88/122 in DE/AT.
Both compact editions passed all nine tests on API22 and reached 88/122
from 82/122 against their byte-matched API22 and API28 baselines. In the
first compact DE/AT run, both new scenarios passed and an existing
handshake test timed out. All nine passed when rerun with the same APKs
and more emulator memory.

The activity lifecycle contract started and stopped the bound receiver
through `MainViewModel`, used a DMAP audio title while the video had no
title, and switched to the title supplied by Media3 without changing the
audio metadata. This passed in standard normal API28 and standard compact
API22. Against each edition's byte-matched baseline, `MainViewModel`
covered 172/192 branches and 100/103 methods, up from 169/192 and 99/103.

The surface lifecycle contract uses real Android surfaces for mirroring
and media playback. It replaces both surfaces, verifies that stale
destruction callbacks leave the replacements attached, and verifies that
callbacks while the model is detached leave the service unchanged. The
contract and the title/receiver contract passed in standard normal API28
and standard compact API22. The additional profiles raised
`MainViewModel` from 172/192 to 174/192 branches and `VideoRenderer`
from 86/120 to 87/120 on the matching class bytes for both editions.

The log contract used a directory at the snapshot path to force a real
write failure. The log stayed visible and both entries were persisted
when storage became available. The sender-volume contract now dispatches
through the model and verifies that an unbound call sends no command.
Both passed alongside the two lifecycle contracts in standard normal
API28 and standard compact API22. This raised `MainViewModel` to 176/192
branches and 102/103 methods in each edition.

The recording-list contract now checks the byte sizes through the same
properties the UI reads. It passed in both standard editions and brought
the combined, byte-matched `MainViewModel` profiles to 103/103 methods.
Branch coverage remained 176/192 in those scoped profiles.

The bound playback contract verifies that receiver start and stop requests
are ignored while detached, then drives scrubbing and silence skipping
through the bound model and waits for ExoPlayer to apply the queued
commands. Starting new media clears the previous scrub target and the
optimistic silence setting. The contract passed on standard normal API28
and standard compact API22. It added seven branch outcomes to the
matching `MainViewModel` profiles, bringing both to 183/192 branches and
103/103 methods.
Normal DE/AT API28 and compact DE/AT API22 passed the same contract.
Each moved from 127/162 to 134/162 branches and from 96/103 to 97/103
methods against its byte-matched full baseline.

The relay model contract ignores a detached request, records the error
from an invalid port, and pairs successfully over a socket on the local
emulator interface using a trimmed address. The diagnostic contract
updates the model snapshot while diagnostics are enabled and preserves
the last snapshot after they are disabled. Both passed on normal DE/AT
API28 and compact DE/AT API22, increasing the scoped `MainViewModel`
branch count from 134/162 to 138/162 in each edition.
Both also passed against the unchanged, instrumented application APKs
from the standard normal API28 and compact API22 scrub runs. The new
test APKs increased each standard baseline from 183/192 to 187/192
branches while retaining 103/103 methods.

The log export contract stops the storage worker and waits for the
export coroutine to finish. It confirms the app keeps its visible logs
and produces neither a share intent nor a stale export file. The
recording contract uses a private fixture directory to verify first
launch without storage, clearing the list on detach, and clearing it
when the storage directory disappears. Both passed on normal API28
and compact API22, in both the standard and DE/AT editions. The DE/AT
recording list remained empty even when the test directory contained a
file. The byte-matched `MainViewModel` profiles moved from 187/192 to
188/192 branches in each standard edition and from 138/162 to 139/162
in each DE/AT edition.

The offline diagnostic contract also checks the address shown by
`MainViewModel` after the emulator loses its LAN IPv4 address. It
passed with Wi-Fi and mobile data disabled, before the VPN recovery
fixture started. On API28 the two DE/AT APKs each moved from 139/162
to 140/162 branches in the byte-matched model profiles. The standard
normal and compact APKs also passed on API28 and reached 189/192 branches
and 103/103 methods in each standard edition.

The four existing model contracts for media titles, surface ownership,
sender volume, and log storage recovery also passed on both DE/AT APKs
on API28. They reused the byte-identical application and instrumentation
APKs from the offline diagnostic runs and brought each regional profile
to 147/162 branches and 99/103 methods. The standard profiles still
show redundant null checks in the generated bytecode of recording-list
refresh and a second service lookup in recording playback.

Recording commands now resolve their directory through the receiver they
will use for the operation. List refresh handles missing receivers and
unavailable directories as an empty file list. The six standard normal
recording contracts passed against the rebuilt API28 application. The
matching profile covered all 32 branch outcomes in the five recording
methods, including valid playback, rejected paths, and detached actions.
The six recording contracts also passed in normal DE/AT API28 and compact
DE/AT API22. The staged app packages retained byte-identical native
libraries in all four variants. An interrupted ADB connection left the
first compact DE/AT transcript empty, so the passing rerun used the same
application and test APKs and required a complete transcript.

On API22, the isolated recording tests initially populated FileProvider's
cache with the temporary log directory. Priming it with the real app
context restored sharing and playback from internal recording storage.
The six standard compact contracts then passed and covered the same
32 of 32 branch outcomes in the five recording methods. The normal API28
recording suite also passed with the corrected fixture.

The normal and DE/AT normal full runs at `dda6d32` exposed a race in the
closed-log-worker contract. The test looked up the export coroutine
after dispatch, when it could already have completed. The model now
returns that coroutine's job and the test joins the exact operation.

The FCast socket contract checks that two immediately queued loads keep
the first DASH item playing, then accepts the HLS request after the load
cooldown. The compact DE/AT API28 run passed and added two branch outcomes
to a byte-matched `FCastReceiver` profile (159/208 to 161/208).
The four log screen contracts and the FCast cooldown contract also passed
together on standard normal API28.

The standard compact API22 run at `dda6d32` completed all 15 runtime
gates, including 369 Android instrumentation cases, 21 native UBSan
cases, the TV launcher, calibration and rendered captions.
The report measured 80,466/86,654 JVM instructions, 4,880/5,971 branches,
8,042/8,473 lines, 4,173/5,140 complexity, 2,197/2,352 methods and 306/313
classes. The API22 native profile measured 903/912 branches, 203/203
functions, 205/205 instantiations, 1,748/1,751 lines, 170/171 MC/DC and
1,495/1,500 regions. These counts are for a single API level. The prior
100% native result used profiles from API22 and API28 with matching APKs.

All 16 decoder selection contracts passed on API37 with the standard
normal application and test APKs archived at `2fd28fa`. The three matching
`DecoderSelector` classes had 135/196 covered branch outcomes on API28.
Adding the existing API30 profile brought them to 153/196, and adding the
API37 profile brought them to 160/196. Methods remained at 53/56. The
API37 run measured decoder selection only.

Two decoder inventory contracts exercise recovery after a failed regular
codec query and the distinction between an empty regular inventory and
an available software decoder. On standard normal API28 the same
instrumented APKs passed first the 16 existing cases and then all 18
cases. The three decoder class mappings increased from 1,218/1,493 to
1,243/1,493 instructions, 135/196 to 138/196 branches, 139/150 to 142/150
lines, 105/156 to 109/156 complexity and 51/58 to 52/58 methods. Both
counted classes remained covered. Running the same APKs on API37 raised
instructions from 1,312/1,493 to 1,337/1,493, branches from 148/196 to
151/196, lines from 141/150 to 144/150, complexity from 112/156 to
116/156 and methods from 53/58 to 54/58, with both classes still covered.
The standard compact API22 build at `b66c95d` also passed all 18 cases.
Compared with the 16 existing cases on the same APKs, decoder coverage
rose from 1,153/1,493 to 1,167/1,493 instructions, 123/196 to 125/196
branches, 131/150 to 133/150 lines and 100/156 to 102/156 complexity.
Methods stayed at 52/58 and classes at 2/2.

The standard normal API28 replay with the archived `2fd28fa` APKs
completed all 15 runtime gates. Its 370 Android instrumentation cases
included the closed log worker and FCast cooldown regressions, and all
21 native UBSan cases passed. The JVM report measured 81,282/86,666
instructions, 4,956/5,971 branches, 8,142/8,472 lines, 4,226/5,141
complexity, 2,211/2,353 methods and 306/313 classes. Native coverage was
100% in all six metrics. This report predates the decoder inventory
change and its two new contracts.

The TV form keyboard contract opens two real editors with the remote.
It verifies that the Android input connection's Next action moves focus,
and that only Done submits. The normal API28 focus suite passed all four
cases. Adding the new profile to the matching full `TvEditField` classes
increased coverage from 1,734/1,871 to 1,760/1,871 instructions, 58/77 to
60/77 branches, 55/79 to 57/79 complexity and 33/40 to 35/40 methods.
All 92 lines and five counted classes remained covered.
The standard compact API22 focus suite also passed all four cases. Its
byte-matched component comparison against the full API22 baseline
produced the same gains in instructions, branches, complexity and methods.

The normal DE/AT API28 replay of `2fd28fa` passed all 15 runtime gates,
370 Android instrumentation cases and 21 native UBSan cases. Its JVM
report measured 79,707/85,829 instructions, 4,867/5,893 branches,
8,089/8,436 lines, 4,166/5,106 complexity, 2,189/2,353 methods and 305/313
classes. All six native metrics reached 100% on this API. The report
uses the archived APKs from before the decoder inventory change and the
TV keyboard test.

The download publication contract now checks that a failed write or a
rejected finalization removes the pending MediaStore entry. A successful
publication retains its destination. The standard normal API37 build
passed all seven publication tests with the isolated provider. On the
same APKs, adding the rejected-finalization case raised `VideoDownloader`
coverage from 444/544 to 453/544 instructions, 19/28 to 21/28 branches and
22/30 to 24/30 complexity. The API28 run also passed and exercised the
legacy app-specific storage path. Both runs used local test media.

The standard compact API37 publication suite passed all nine cases.
The new cases verify that an unavailable output stream removes the
pending entry, and that an unavailable path lookup returns the download
folder and filename after a successful save. On the same compiled APKs,
the two cases raised `VideoDownloader` coverage from 457/557 to 475/557
instructions, 21/28 to 22/28 branches, 76/88 to 79/88 lines and 24/30 to
25/30 complexity. All 16 methods and three counted classes stayed covered.
The same test APK passed all nine cases on API22, where the MediaStore
cases return early and the legacy publication assertions still run.

The standard compact API22 replay at `2fd28fa` passed all 15 runtime
gates, 370 Android instrumentation cases and 21 native UBSan cases. The
JVM report measured 80,499/86,654 instructions, 4,882/5,971 branches,
8,043/8,472 lines, 4,175/5,141 complexity, 2,198/2,353 methods and 306/313
classes. The API22 native profile measured 903/912 branches, 203/203
functions, 205/205 instantiations, 1,748/1,751 lines, 170/171 MC/DC and
1,495/1,500 regions. These native counts are from API22 alone.

The same standard compact APKs passed all 15 runtime gates, 370 Android
instrumentation cases and 21 native UBSan cases on API28. Combining that
snapshot with API22 reached 100% in all six native metrics. The combined
JVM report measured 81,415/86,654 instructions, 4,984/5,971 branches,
8,161/8,472 lines, 4,251/5,141 complexity, 2,213/2,353 methods and 306/313
classes. The `2fd28fa` reports now confirm 100% native coverage for all
four variants. Every variant still has JVM coverage gaps.

The next archived cohort at `e7ae3da` passed all 15 runtime gates,
375 Android instrumentation cases, 21 native UBSan cases, and Android
VPN and offline diagnostics on API28 for both normal editions. Each
edition retained 100% in all six native metrics. The standard JVM report
measured 81,405/86,713 instructions, 4,985/5,977 branches, 8,156/8,485
lines, 4,259/5,146 complexity, 2,219/2,355 methods and 306/313 classes.
The DE/AT JVM report measured 79,866/85,876 instructions, 4,901/5,899
branches, 8,106/8,449 lines, 4,201/5,111 complexity, 2,197/2,355 methods
and 305/313 classes.

The standard compact API22 run at `bc8099d` passed lint, 386 Android
tests, 21 native UBSan cases, and all 15 runtime gates. Its API22-only
JVM profile covered 80,865/86,746 instructions, 4,935/5,977 branches,
8,081/8,495 lines, 4,237/5,150 complexity, 2,216/2,359 methods and
306/313 classes. Native coverage on this API was 903/912 branches,
203/203 functions, 205/205 instantiations, 1,748/1,751 lines,
170/171 MC/DC and 1,495/1,500 regions.

The TV frame-rate editor test verifies that Cancel discards the draft,
an overflowing integer leaves the previous frame rate intact, a valid
custom value persists, and choosing a preset replaces it. The focused
API28 run used the same standard application APK as the archived
`e7ae3da` instrumentation suite. Adding its profile to the two matching
`SettingsScreenKt` class files raised instructions from 8,109/8,345 to
8,199/8,345, branches from 335/448 to 337/448, lines from 586/594 to
588/594, complexity from 232/268 to 235/268 and methods from 173/187 to
175/187. Class coverage stayed at 1/1.

Two TV editor cases passed on API28 after adding an empty-frame-rate
check and saving a receiver name with leading and trailing spaces.
The blank rate disables Save and the name is stored without the outer
spaces. Adding the profile to the same two `SettingsScreenKt` mappings
raised instructions to 8,200/8,345, branches to 338/448 and complexity
to 236/268. Both cases also passed with the standard app on API30 and
the standard compact app on API22.

The mirroring fullscreen contract verifies that a disabled preference or
an active PIN leaves the overview visible, dismissing the PIN does not
automatically enter fullscreen, and a fresh mirror enters fullscreen and
returns to the overview on Back. The normal API28 main-screen suite
passed all nine cases. Adding its profile to 28 matching `MainScreen`
class files increased covered branches from 427/535 to 432/535,
instructions from 10,767/11,296 to 10,769/11,296 and complexity from
314/399 to 318/399. Line, method and class counts stayed the same.

The same fullscreen test passed on standard compact API22 with all nine
main-screen cases. It waits for the actual surface view to mount so that
Android scheduling of surface-holder callbacks does not determine the UI
policy result. Adding the new profile to the matching full API22 report
raised `MainScreen` instructions from 10,581/11,290 to 10,583/11,290,
branches from 422/535 to 427/535, and complexity from 310/399 to 314/399.
The covered line, method and class counts stayed the same.

On API30 the TV-sized emulator still exposed stale accessibility
descendants after the visible screen changed. The main-screen fixture
now refreshes those nodes before checking state or dispatching a click.
Both affected diagnostics tests passed in isolation, and all nine
main-screen cases then passed with the archived `e7ae3da` application
APK and rebuilt test APK. The resulting component profile matched the
already measured 432/535 branch outcomes.

On the same TV-sized API30 emulator, refreshing the relay form's
accessibility nodes restored reliable focus checks when moving between
fields. The remote-navigation regression passed in isolation, then all
eight Tools cases passed with the archived application APK.

The compact DE/AT API28 replay of `2fd28fa` passed all 15 runtime gates,
370 Android instrumentation cases and 21 native UBSan cases. The JVM
report measured 79,733/85,817 instructions, 4,867/5,893 branches,
8,093/8,436 lines, 4,164/5,106 complexity, 2,190/2,353 methods and 305/313
classes. Its API28 native profile measured 909/912 branches, 203/203
functions, 205/205 instantiations, 1,748/1,751 lines, 171/171 MC/DC and
1,496/1,500 regions. These native counts are from API28 alone.

The same compact DE/AT application and test APKs passed all 15 runtime
gates, 370 Android instrumentation cases and 21 native UBSan cases on
API22. Merging the two byte-matched snapshots yielded 100% in all six
native metrics. The merged JVM report measured 79,913/85,817 instructions,
4,900/5,893 branches, 8,110/8,436 lines, 4,194/5,106 complexity,
2,191/2,353 methods and 305/313 classes.

The TV custom-resolution contract starts with a preset, verifies that
Save is disabled with zero or one valid dimension, and enters values
with leading zeros through the remote editor. The normal API28 run
confirmed that saving both dimensions persisted the canonical `1280x720`
preference and kept the stored preset until the final Save action.
The other 12 settings cases also passed on the same standard normal
API28 APKs. Adding the custom-resolution profile to those cases raised
the matching `SettingsScreen` mappings from 7,742/8,345 to 8,096/8,345
instructions, 311/448 to 332/448 branches, 572/594 to 586/594 lines,
209/268 to 230/268 complexity and 165/187 to 173/187 methods. The single
counted class remained covered.
On compact API22 the resolution test now locates the saved draft after
the dialog closes, since dismissing the IME can scroll the editor out of
view. It then explicitly returns focus to Width before checking Right
navigates to Height. All 13 settings cases passed on standard compact
API22. Its byte-matched comparison showed the same gains as standard
normal API28, including 311/448 to 332/448 covered branches.

The MediaSession routing contract now checks play, pause, exact seek and
ten-second forward and rewind commands on a paused local video. It
also verifies that the video commands do not reach the audio sender.
The normal API28 test passed. Adding its profile to the byte-matched
full `AirPlayService` mappings raised instructions from 5,753/6,164 to
5,801/6,164, branches from 493/601 to 499/601, lines from 878/911 to
886/911, complexity from 420/538 to 426/538, and methods from 219/236 to
221/236. All 22 counted classes remained covered.

The AirPlay lifecycle contract checks that disconnecting one sender
keeps playback active, the last disconnect ends its AirPlay session,
and the queued teardown leaves a replacement local session active.
The standard normal API28 case passed. Adding its profile after the
MediaSession transport profile raised `AirPlayService` to 5,813/6,164
instructions, 504/601 branches and 430/538 complexity. Line, method
and class coverage stayed at 886/911, 221/236 and 22/22 respectively.
Both playback contracts also passed on standard compact API22. Adding
their profile to 33 matching `AirPlayService` class files raised covered
instructions from 5,733/6,164 to 5,793/6,164, branches from 492/601 to
503/601, lines from 872/911 to 880/911, complexity from 419/538 to
429/538 and methods from 219/236 to 221/236. All 22 counted classes
remained covered.

The full standard compact API22 run exposed a timing dependency in the
lifecycle test: the video decoder can advance the same session from
connecting to playing during the first sender's disconnect. The test
now checks that the original session token and AirPlay owner are
preserved. It passed on API22 with the same archived application APK
and the rebuilt test APK.

The compact API22 regression rerun also passed rotation and repeat
seeking with the archived application. The tests wait for the decoded
video dimensions and the settled seek duration before asserting the
next control action.

On API28 a real Media3 session reported the requested 1.5× playback
rate and attributed the end of the fixture to its owning AirPlay
token. Combining that profile with the matching four
`AirPlayVideoPlayer` class mappings raised instructions from 1,124/1,174
to 1,134/1,174, branches from 108/138 to 110/138 and complexity from
106/134 to 107/134. All lines, methods and counted classes stayed covered.

An API28 follow-up verified that relative seeking lands at the end of a
paused clip, and a new video preserves the last sender gain only when
requested. Media3 can report the last playable millisecond (999 ms on
the 1,000 ms fixture). Adding this profile raised the same player
mappings to 1,138/1,174 instructions, 112/138 branches and 109/134
complexity. Lines, methods and counted classes stayed at 100%.

A live HLS fixture built from the synthetic test clip exercised the
paused player before and after Media3 reports a ready live timeline.
The API28 case added three branches, eight instructions and two
complexity entries to the same player mappings, raising them to
115/138 branches, 1,146/1,174 instructions, and 111/134 complexity.

Two API28 cases verified that disabling recovery reports a transient
network failure to the owning session and that the text overlay ignores
bitmap cues and caps displayed text at 4,000 characters. The combined
player profile reached 116/138 branches, 1,148/1,174 instructions, and
112/134 complexity.

Two codec-selection cases passed on both API28 and API30. The real
platform encoder was excluded from AVC decoder selection, and capability
queries checked native low-latency support and rejected an unknown MIME
type. On matching `DecoderSelector` class files, adding both profiles
raised instructions from 1,243/1,493 to 1,311/1,493, branches from
138/196 to 144/196, lines from 142/150 to 144/150, and complexity from
109/156 to 112/156.

Two API28 relay cases verified that switching to receive closes an
unfinished sender pairing without replacing the new listener state,
and that the listener rechecks display availability after pairing
proof. The matching `MirrorRelay` profile grew from 88/122 to 89/122
branches, 1,271/1,557 to 1,276/1,557 instructions, and 50/78 to 51/78
complexity.

The standard API36 picture-in-picture run passed narrow playback,
the picture-in-picture round trip, and a disconnected activity binding.
Adding that profile to the 16 matching `MainActivity` class files
raised branches from 69/122 to 75/122, methods from 33/44 to 38/44,
and counted classes from 8/12 to 12/12.

All ten main-screen cases passed on API28 after adding a touch test
for fullscreen mirroring. The controls timed out, a real pointer tap
restored them, and Exit returned to the mirrored overview. On the
28 matching `MainScreenKt` class files, the focused test raised the
profile from 430/535 to 431/535 branches, 317/399 to 322/399 complexity,
and 21/22 to 22/22 counted classes.

The Appstore startup integration passed two API28 tests. Duplicate
purchasing registration still attempts license verification with the
application context, and a license startup exception does not escape.
The scoped run covered all 50 instructions in the initializer and its
SDK callbacks. The purchase response branches remain outside this run.

The follow-up API28 run passed four Appstore cases. It dispatched every
SDK purchase status locally, verified that fulfillment is requested
only for the successful receipt, and handled unavailable user, product
and purchase-update data. The purchase response handler reached 28/31
instructions and 3/4 branches. The SDK requires a non-null purchase
status when constructing its response.

On API28 the decoder restart test checked AVC and HEVC access units
and truncated headers. The method that scans for restartable frames
reached 30/30 branches. Adding the focused profile to the matching
`VideoRenderer` class mappings raised total branches from 87/120 to
93/120 and complexity from 67/101 to 73/101.

The compact DE/AT API22 run at `e7ae3da` passed 375 Android tests,
21 native UBSan cases and all 15 runtime gates. The API22-only JVM
profile covered 79,127/85,864 instructions, 4,824/5,899 branches,
8,004/8,449 lines, 4,145/5,111 complexity, 2,183/2,355 methods and
305/313 classes. It covered 903/912 native branches, 203/203 functions,
205/205 instantiations, 1,748/1,751 lines, 170/171 MC/DC and
1,495/1,500 regions.

The same compact DE/AT APKs passed 375 Android tests, 21 native UBSan
cases, all 15 runtime gates, and VPN and offline diagnostics on API28.
The validated API22 and API28 merge covered all six native metrics
at 100%. The JVM profile covered 80,029/85,864 instructions, 4,927/5,899
branches, 8,123/8,449 lines, 4,222/5,111 complexity, 2,198/2,355 methods,
and 305/313 classes.

The standard API28 diagnostics and playback-control profiles passed
ten focused Android tests. With the matching application class files,
the video diagnostics formatter increased from 2/4 to 4/4 branches,
the DACP controller from 32/34 to 34/34 branches, and the seek gesture
state from 23/26 to 26/26 branches. All six JVM counters reached 100%
for the seek gesture state.

The API28 headset-output test exercised the production classifier with
wired, Bluetooth and USB headsets, as well as speaker, HDMI, ordinary
USB and unknown outputs. The classifier reached 19/19 instructions,
10/10 branches, 5/5 lines, 6/6 complexity and 1/1 method.

Two API28 DLNA socket cases verified that the root URL returns the
device description, unsupported HTTP methods return 405, and legacy
POST event renewals keep the subscription ID and requested timeout.
The matching `ControlServer` mappings rose from 107/146 to 114/146
branches, 203/213 to 205/213 lines, and 73/110 to 77/110 complexity.

An API28 follow-up exercised a SOAP action supplied entirely in the
request body, rejected a header naming a different action, and verified
that description URLs reject control and event methods. The matching
`ControlServer` mappings reached 1,506/1,726 instructions, 119/146
branches, and 82/110 complexity.

Three focused tests passed on the standard API28 APK. The picture-fit
case saved all four fixed margins, switched overscan on and off, and
opened corner calibration. Adding the profile to the matching settings
class files raised complexity from 236/268 to 239/268 and methods from
175/187 to 178/187. The USB mDNS case kept the current advertisement
for incomplete or unrelated responses and sent goodbye records only
after a complete foreign response. It raised matching branch coverage
from 163/217 to 172/217 and complexity from 114/161 to 122/161. The
FCast socket case rejected invalid load fields and accepted explicit
null options with the default playback settings. It raised matching
branch coverage from 161/208 to 171/208 and complexity from 83/128 to
93/128. These are scoped counter gains for matching class files.
