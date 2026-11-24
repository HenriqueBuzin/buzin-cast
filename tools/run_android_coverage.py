"""Build, install, test and measure the four current Android APK variants.

Uses an already running, explicitly named emulator. Never connects to physical
TVs, signs a release, or uses regional production credentials. A copied public
key provides the DE/AT debug package identity, not an Appstore license test.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import uuid
from pathlib import Path
from zipfile import ZipFile

from test_emulator import positive_seconds


VARIANTS = {
    "normal": (False, False),
    "compat": (True, False),
    "deat": (False, True),
    "compat-deat": (True, True),
}
COMPILED_MANIFEST = "compiled-inputs.json"
COMPILED_AUXILIARY_FILES = ("core.exec", "domain.exec", "inputs/native_core_tests")


def compiled_auxiliary_hashes(snapshot):
    """Fingerprint the unit coverage and native test executable stored with a build."""
    return {name: hashlib.sha256(filesystem_path(snapshot / name).read_bytes()).hexdigest()
            for name in COMPILED_AUXILIARY_FILES}


def seal_compiled_snapshot(snapshot, variant, identity, sources):
    manifest = {"variant": variant, "archived_mappings": True, "source_files_sha256": sources,
                "compiled_auxiliary_sha256": compiled_auxiliary_hashes(snapshot), **identity}
    temporary = filesystem_path(snapshot / (COMPILED_MANIFEST + ".tmp"))
    temporary.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    temporary.replace(filesystem_path(snapshot / COMPILED_MANIFEST))
    return manifest


def filesystem_path(path):
    """Use Windows' extended path syntax for deep generated class inventories."""
    path = Path(path)
    if os.name != "nt":
        return path
    absolute = str(path.resolve())
    if absolute.startswith("\\\\?\\"):
        return Path(absolute)
    if absolute.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + absolute[2:])
    return Path("\\\\?\\" + absolute)


def class_roots(root, build, archive=None):
    if archive is not None:
        return {name:filesystem_path(archive)/"classes"/name for name in ("app","app-kotlin","core","domain")}
    roots = {
        "app":build/"intermediates/classes/debug/transformDebugClassesWithAsm/dirs",
        "app-kotlin":build/"tmp/kotlin-classes/debug",
        "core":root/"receiver-core/build/classes/kotlin/main",
        "domain":root/"dlna-domain/build/classes/kotlin/main",
    }
    return {name:filesystem_path(directory) for name,directory in roots.items()}


def native_library(build):
    binaries=list((build/"intermediates/cxx/Debug").glob("*/obj/x86_64/libairplay_native.so"))
    if not binaries:
        raise RuntimeError("Native application library is required for provenance")
    return max(binaries,key=lambda path:path.stat().st_mtime)


def packaged_native_library(apk):
    with ZipFile(apk) as package:
        try:
            library = package.read("lib/x86_64/libairplay_native.so")
        except KeyError as error:
            raise RuntimeError(f"APK is missing the x86_64 libairplay_native.so: {apk}") from error
    if not library:
        raise RuntimeError(f"APK contains an empty x86_64 libairplay_native.so: {apk}")
    return library


def verify_packaged_native_library(apk, mapping, llvm_strip):
    """Confirm the native coverage mapping belongs to the library shipped in the APK."""
    packaged = packaged_native_library(apk)
    if mapping.read_bytes() == packaged:
        return
    with TemporaryDirectory(prefix="coverage-native-identity-") as directory:
        stripped = Path(directory) / "libairplay_native.so"
        subprocess.run([str(llvm_strip), "--strip-unneeded", "-o", str(stripped), str(mapping)], check=True)
        if stripped.read_bytes() != packaged:
            raise RuntimeError("Native coverage mapping does not match the library packaged in the APK")


def coverage_identity(root, build, apk, test_apk, archive=None):
    """Fingerprint actual compiled inputs, independent of source mtimes or Git dirt."""
    digest=hashlib.sha256()
    roots=class_roots(root,build,archive)
    counts={}
    for label,directory in roots.items():
        files=sorted(directory.rglob("*.class"))
        counts[label]=len(files)
        for file in files:
            digest.update((label+"/"+file.relative_to(directory).as_posix()+"\0").encode())
            digest.update(hashlib.sha256(file.read_bytes()).digest())
    if any(counts[label]==0 for label in ("app","core","domain")):
        raise RuntimeError("Compiled application/core/domain classes are required for provenance")
    if archive is not None:
        archive=filesystem_path(archive)
        apk=archive/"inputs/app.apk"
        test_apk=archive/"inputs/test.apk"
        native=archive/"inputs/libairplay_native.so"
    else:
        native=native_library(build)
    return {
        "apk_sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),
        "test_apk_sha256":hashlib.sha256(test_apk.read_bytes()).hexdigest(),
        "class_files_sha256":digest.hexdigest(),
        "class_counts":counts,
        "native_library_sha256":hashlib.sha256(native.read_bytes()).hexdigest(),
    }


def source_identity(root, archive=None):
    """Hash precisely the owned source trees copied with compiled mappings."""
    if archive is not None:
        archive=filesystem_path(archive)
    groups = ({name:archive/"sources"/name for name in ("app","core","domain","native")}
              if archive is not None else {
                  "app":root/"app/src/main/kotlin", "core":root/"receiver-core/src/main/kotlin",
                  "domain":root/"dlna-domain/src/main/kotlin", "native":root/"app/src/main/cpp"})
    digest = hashlib.sha256()
    for name, directory in groups.items():
        files = directory.glob("*") if name == "native" else directory.rglob("*")
        for file in sorted(files):
            if not file.is_file() or file.suffix not in (".kt", ".java", ".c", ".cpp", ".h"):
                continue
            digest.update((name+"/"+file.relative_to(directory).as_posix()+"\0").encode())
            digest.update(hashlib.sha256(file.read_bytes()).digest())
    return digest.hexdigest()


def archive_compiled_inputs(root,build,apk,test_apk,snapshot,expected,expected_sources=None):
    """Keep mappings before Gradle switches editions and removes stale outputs."""
    snapshot=filesystem_path(snapshot)
    if expected_sources is not None and source_identity(root) != expected_sources:
        raise RuntimeError("Production sources changed during compilation; rebuild after edits settle")
    inputs=snapshot/"inputs"
    inputs.mkdir()
    for name,file in (("app.apk",apk),("test.apk",test_apk),("libairplay_native.so",native_library(build))):
        shutil.copyfile(file,inputs/name)
    for name,directory in class_roots(root,build).items():
        if directory.exists():
            shutil.copytree(directory,snapshot/"classes"/name)
    sources={"app":root/"app/src/main/kotlin","core":root/"receiver-core/src/main/kotlin","domain":root/"dlna-domain/src/main/kotlin"}
    for name,directory in sources.items():
        shutil.copytree(directory,snapshot/"sources"/name)
    native_sources=snapshot/"sources/native"
    native_sources.mkdir()
    for file in (root/"app/src/main/cpp").iterdir():
        if file.suffix in (".c",".cpp",".h"):
            shutil.copyfile(file,native_sources/file.name)
    for name,module in (("core","receiver-core"),("domain","dlna-domain")):
        shutil.copyfile(root/module/"build/jacoco/test.exec",snapshot/(name+".exec"))
    if coverage_identity(root,build,apk,test_apk,archive=snapshot)!=expected:
        raise RuntimeError("Archived compiled inputs do not match the tested APK")
    if expected_sources is not None and source_identity(root,archive=snapshot) != expected_sources:
        raise RuntimeError("Production sources changed while archiving compiled mappings")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--variant", action="append", choices=list(VARIANTS), help="Repeat to select variants; default: all four")
    parser.add_argument("--sdk-root", type=Path)
    parser.add_argument("--bash-dir", type=Path, help="Windows POSIX tool directory used by native Gradle tasks")
    parser.add_argument("--output", type=Path, help="Separate report destination, for example build/coverage-api22")
    parser.add_argument("--instrumentation-timeout", type=positive_seconds,
                        help="Override the Android test runner's instrumentation timeout in seconds")
    parser.add_argument("--require-100", action="store_true", help="Fail unless every selected variant reaches all metrics")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicit emulator-<port> serial is permitted")
    variants = list(dict.fromkeys(args.variant or VARIANTS))
    root = Path(__file__).resolve().parents[1]
    sdk_value = args.sdk_root or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_value and platform.system() == "Windows":
        sdk_value = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
    if not sdk_value:
        parser.error("Pass --sdk-root or set ANDROID_HOME")
    sdk = Path(sdk_value).resolve()
    adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    # Validate the entire selection before installing or changing emulator data.
    api = int(subprocess.check_output([str(adb), "-s", args.serial, "shell", "getprop", "ro.build.version.sdk"], text=True).strip())
    minimum = max(22 if VARIANTS[name][0] else 25 for name in variants)
    if api < minimum:
        parser.error(f"Selected variants require emulator API {minimum} or newer; got {api}")
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk)
    ndk = re.search(r'^ndk = "([^"]+)"', (root / "gradle/libs.versions.toml").read_text(), re.M).group(1)
    env["ANDROID_NDK_ROOT"] = str(sdk / "ndk" / ndk)
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    llvm_strip = sdk / "ndk" / ndk / "toolchains/llvm/prebuilt" / host / "bin" / (
        "llvm-strip.exe" if os.name == "nt" else "llvm-strip")
    if args.bash_dir:
        if not args.bash_dir.is_dir():
            parser.error("--bash-dir must name an existing directory")
        env["PATH"] = str(args.bash_dir.resolve()) + os.pathsep + env.get("PATH", "")
    # Explicit flags below must determine the edition, even in a shell used for
    # a previous regional/compat build. Never inherit an external signing asset.
    for name in ("buzinLegacy", "buzinNoDownload", "buzinRegionalAppstoreAssets"):
        env.pop("ORG_GRADLE_PROJECT_" + name, None)
    output = (args.output or root / "build/android-coverage-matrix").resolve()
    output.mkdir(parents=True, exist_ok=True)
    fixture = output / "regional-debug-assets"
    fixture.mkdir(exist_ok=True)
    shutil.copyfile(root / "app/src/main/assets/AppstoreAuthenticationKey.pem", fixture / "AppstoreAuthenticationKey.pem")
    gradle = [str(root / "gradlew.bat")] if os.name == "nt" else ["bash", str(root / "gradlew")]
    results = {"emulator_api": api, "selected_variants": variants, "variants": {}, "all_four_target_met": False}

    def run(command, logfile):
        print("Running " + logfile.name, flush=True)
        with logfile.open("w", encoding="utf-8") as log:
            subprocess.run(list(map(str, command)), cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)

    def save():
        results["all_four_target_met"] = set(results["variants"]) == set(VARIANTS) and all(value.get("target_met") for value in results["variants"].values())
        (output / "matrix.json").write_text(json.dumps(results, indent=2), encoding="utf-8")

    try:
        for name in variants:
            legacy, regional = VARIANTS[name]
            suffix = ("-legacy" if legacy else "") + ("-nodownload" if regional else "")
            build = root / ("app/build" + suffix + "-coverage")
            profiles = output/"execution"/name if args.output else root / ("build/emulator-checks" + suffix)
            report = output / name
            report.mkdir(exist_ok=True)
            flags = ["-PbuzinCoverage=true", "-PbuzinAbis=x86_64", "-PbuzinLegacy=" + str(legacy).lower(), "-PbuzinNoDownload=" + str(regional).lower(), "-PbuzinCoverageData="+str(profiles)]
            if regional:
                flags.append("-PbuzinRegionalAppstoreAssets=" + str(fixture))
            sources_before_build = source_identity(root)
            run(gradle + flags + [":receiver-core:check", ":dlna-domain:check", ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:lintDebug", "--console=plain"], report / "build.log")
            native = build / "native-tests"
            run([sys.executable, root / "tools/build_native_tests.py", "--abi", "x86_64", "--api", 22 if legacy else 25, "--sdk-root", sdk, "--output", native], report / "native-build.log")
            apk = build / "outputs/apk/debug/app-debug.apk"
            test_apk = build / "outputs/apk/androidTest/debug/app-debug-androidTest.apk"
            identity=coverage_identity(root,build,apk,test_apk)
            snapshot=report/"raw"/uuid.uuid4().hex
            snapshot.mkdir(parents=True)
            archive_compiled_inputs(root,build,apk,test_apk,snapshot,identity,expected_sources=sources_before_build)
            verify_packaged_native_library(snapshot / "inputs/app.apk", snapshot / "inputs/libairplay_native.so", llvm_strip)
            shutil.copyfile(native/"native_core_tests",snapshot/"inputs/native_core_tests")
            compiled = seal_compiled_snapshot(snapshot, name, identity, sources_before_build)
            package = "com.buzincast.tv.deat" if regional else "com.buzincast.tv"
            # Modern and compat share a package but have different version codes.
            # Use clean emulator installs instead of relying on downgrade flags or
            # carrying settings/database state from a different APK variant.
            installed = set(subprocess.check_output([str(adb), "-s", args.serial, "shell", "pm", "list", "packages", package], env=env, text=True).splitlines())
            for candidate in (package + ".test", package):
                if "package:" + candidate in installed:
                    run([adb, "-s", args.serial, "uninstall", candidate], report / ("uninstall-test.log" if candidate.endswith(".test") else "uninstall-app.log"))
            command = [sys.executable, root / "tools/test_emulator.py", "--serial", args.serial, "--adb", adb, "--apk", snapshot/"inputs/app.apk", "--test-apk", snapshot/"inputs/test.apk", "--native-tests", snapshot/"inputs/native_core_tests", "--coverage", "--extended", "--package", package, "--output", profiles]
            if args.instrumentation_timeout is not None:
                command.extend(("--instrumentation-timeout", str(args.instrumentation_timeout)))
            if regional:
                command.append("--no-download")
            if api >= 23:
                command.append("--diagnostic-networks")
            run(command, report / "runtime.log")
            for execution_name in ("core.exec","domain.exec"):
                shutil.copyfile(snapshot/execution_name,profiles/"coverage"/execution_name)
            run(gradle + flags + ["-PbuzinCoverageMappings="+str(snapshot), ":app:fullCoverageReport", "--console=plain"], report / "jacoco.log")
            command = [sys.executable, root / "tools/collect_native_coverage.py", "--profiles", profiles, "--output", report,
                       "--native-binary",snapshot/"inputs/libairplay_native.so","--native-source-dir",snapshot/"sources/native"]
            if legacy:
                command.append("--legacy")
            if regional:
                command.append("--no-download")
            # Set both booleans explicitly; the collector also accepts CI env defaults.
            env["BUZIN_LEGACY"] = str(legacy).lower()
            env["BUZIN_NODOWNLOAD"] = str(regional).lower()
            run(command, report / "native-report.log")
            shutil.copyfile(build / "reports/jacoco/fullCoverageReport/fullCoverageReport.xml", report / "android.xml")
            measured = json.loads((report / "summary.json").read_text(encoding="utf-8"))
            if (coverage_identity(root,build,apk,test_apk,archive=snapshot)!=identity or
                    compiled_auxiliary_hashes(snapshot)!=compiled["compiled_auxiliary_sha256"]):
                raise RuntimeError("Archived inputs changed during testing/reporting; rebuild and rerun "+name)
            for file in (profiles/"coverage").iterdir():
                if file.suffix in (".ec",".exec",".profraw"):
                    shutil.copyfile(file,snapshot/file.name)
            shutil.copyfile(profiles/"results.json",snapshot/"results.json")
            manifest={**compiled,"emulator_api":api}
            (snapshot/"manifest.json").write_text(json.dumps(manifest,indent=2),encoding="utf-8")
            results["variants"][name] = {
                "target_met": measured["target_met"],
                "package": package,
                **identity,
                "raw_snapshot":snapshot.relative_to(output).as_posix(),
                "android_and_jvm": measured["android_and_jvm"],
                "native": measured["native"],
            }
            save()
            print(name + ": target_met=" + str(measured["target_met"]), flush=True)
    finally:
        save()
    if args.require_100 and not all(results["variants"][name]["target_met"] for name in variants):
        raise SystemExit("100% target not met; inspect " + str(output / "matrix.json"))


if __name__ == "__main__":
    main()
