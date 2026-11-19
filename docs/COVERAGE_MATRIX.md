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
