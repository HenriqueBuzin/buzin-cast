"""Replay frozen coverage APKs on another emulator API without rebuilding them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import uuid

from merge_android_coverage import IDENTITY_KEYS, validate_snapshots
from run_android_coverage import VARIANTS, coverage_identity, filesystem_path, source_identity


def copy_compiled_snapshot(source, target):
    """Copy compiled mappings, never execution data or a prior success manifest."""
    expected = coverage_identity(source, source, source / "inputs/app.apk", source / "inputs/test.apk", archive=source)
    expected_sources = source_identity(source, archive=source)
    native_tests = source / "inputs/native_core_tests"
    native_hash = hashlib.sha256(native_tests.read_bytes()).hexdigest()
    filesystem_path(target).mkdir(parents=True, exist_ok=False)
    filesystem_path(target / "inputs").mkdir()
    for name in ("app.apk", "test.apk", "libairplay_native.so", "native_core_tests"):
        shutil.copyfile(filesystem_path(source / "inputs" / name), filesystem_path(target / "inputs" / name))
    for directory in ("classes", "sources"):
        shutil.copytree(filesystem_path(source / directory), filesystem_path(target / directory))
    for name in ("core.exec", "domain.exec"):
        shutil.copyfile(filesystem_path(source / name), filesystem_path(target / name))
    actual = coverage_identity(target, target, target / "inputs/app.apk", target / "inputs/test.apk", archive=target)
    if actual != expected or source_identity(target, archive=target) != expected_sources:
        raise RuntimeError("Replay archive changed the frozen compiled inputs or sources")
    if hashlib.sha256((target / "inputs/native_core_tests").read_bytes()).hexdigest() != native_hash:
        raise RuntimeError("Replay archive changed the native test executable")
    return expected, native_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--snapshot", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk-root", type=Path)
    parser.add_argument("--collect-only", action="store_true",
                        help="Archive the completed runtime and raw profiles without running Gradle or merging reports")
    parser.add_argument("--require-100", action="store_true")
    args = parser.parse_args()
    if args.collect_only and args.require_100:
        parser.error("--require-100 requires a coverage report and cannot be used with --collect-only")
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicit emulator-<port> serial is permitted")
    sources = [path.resolve() for path in args.snapshot]
    prepared = []
    for source in sources:
        manifest, _ = validate_snapshots([source])
        if not manifest.get("archived_mappings") or not manifest.get("source_files_sha256") or not (source / "inputs/native_core_tests").is_file():
            parser.error("Replay requires archived mappings, source fingerprints and the frozen native test executable")
        prepared.append((source, manifest))
    names = [manifest["variant"] for _, manifest in prepared]
    if len(set(names)) != len(names):
        parser.error("Select exactly one frozen snapshot per variant")
    sdk = args.sdk_root or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and platform.system() == "Windows":
        sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
    if not sdk:
        parser.error("Pass --sdk-root or set ANDROID_HOME")
    sdk = Path(sdk).resolve()
    adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    api = int(subprocess.check_output([str(adb), "-s", args.serial, "shell", "getprop", "ro.build.version.sdk"], text=True).strip())
    abi = subprocess.check_output([str(adb), "-s", args.serial, "shell", "getprop", "ro.product.cpu.abi"], text=True).strip()
    if abi != "x86_64":
        parser.error("The frozen coverage snapshots require an x86_64 emulator")
    minimum = max(22 if VARIANTS[name][0] else 25 for name in names)
    if api < minimum:
        parser.error(f"Selected variants require API {minimum} or newer; got {api}")
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    result_path = output / "replay.json"
    if result_path.exists():
        parser.error("Choose a new output directory to preserve the previous replay")
    result = {"emulator_api": api, "selected_variants": names, "variants": {}, "all_four_target_met": False}

    def save():
        result["all_four_target_met"] = set(result["variants"]) == set(VARIANTS) and all(
            item.get("target_met") is True for item in result["variants"].values())
        result_path.write_text(json.dumps(result, indent=2), encoding="utf-8")

    def run(command, logfile):
        print("Running " + str(logfile.relative_to(output)), flush=True)
        with logfile.open("w", encoding="utf-8") as log:
            subprocess.run(list(map(str, command)), cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)

    save()
    try:
        for source, manifest in prepared:
            name = manifest["variant"]
            legacy, regional = VARIANTS[name]
            report = output / name
            report.mkdir()
            snapshot = report / "raw" / uuid.uuid4().hex
            identity, native_hash = copy_compiled_snapshot(source, snapshot)
            if any(identity[key] != manifest[key] for key in IDENTITY_KEYS) or source_identity(snapshot, archive=snapshot) != manifest["source_files_sha256"]:
                raise RuntimeError("Frozen snapshot changed before replay")
            package = "com.buzincast.tv.deat" if regional else "com.buzincast.tv"
            installed = set(subprocess.check_output([str(adb), "-s", args.serial, "shell", "pm", "list", "packages", package], text=True).splitlines())
            for candidate in (package + ".test", package):
                if "package:" + candidate in installed:
                    run([adb, "-s", args.serial, "uninstall", candidate], report / ("uninstall-test.log" if candidate.endswith(".test") else "uninstall-app.log"))
            execution = report / "execution"
            command = [sys.executable, root / "tools/test_emulator.py", "--serial", args.serial, "--adb", adb,
                       "--apk", snapshot / "inputs/app.apk", "--test-apk", snapshot / "inputs/test.apk",
                       "--native-tests", snapshot / "inputs/native_core_tests", "--coverage", "--extended",
                       "--package", package, "--output", execution]
            if regional:
                command.append("--no-download")
            run(command, report / "runtime.log")
            if coverage_identity(snapshot, snapshot, snapshot / "inputs/app.apk", snapshot / "inputs/test.apk", archive=snapshot) != identity:
                raise RuntimeError("Replay inputs changed during runtime")
            if hashlib.sha256((snapshot / "inputs/native_core_tests").read_bytes()).hexdigest() != native_hash:
                raise RuntimeError("Replay native tests changed during runtime")
            for file in (execution / "coverage").iterdir():
                if file.suffix in (".ec", ".profraw"):
                    shutil.copyfile(file, snapshot / file.name)
            shutil.copyfile(execution / "results.json", snapshot / "results.json")
            replay_manifest = {**manifest, "emulator_api": api, "replayed_from": str(source), "native_tests_sha256": native_hash}
            (snapshot / "manifest.json").write_text(json.dumps(replay_manifest, indent=2), encoding="utf-8")
            validate_snapshots([snapshot])
            item = {"raw_snapshot": str(snapshot.relative_to(output)),
                    **{key: identity[key] for key in IDENTITY_KEYS}}
            result["variants"][name] = item
            save()
            if args.collect_only:
                continue
            run([sys.executable, root / "tools/merge_android_coverage.py", "--snapshot", source,
                 "--snapshot", snapshot, "--output", report / "merged", "--sdk-root", sdk], report / "report.log")
            summary = json.loads((report / "merged/summary.json").read_text(encoding="utf-8"))
            item.update(target_met=summary["target_met"], android_and_jvm=summary["android_and_jvm"],
                        native=summary["native"])
            save()
    finally:
        save()
    if args.require_100 and (set(result["variants"]) != set(names) or any(
            item.get("target_met") is not True for item in result["variants"].values())):
        raise SystemExit("100% target not met; inspect " + str(result_path))


if __name__ == "__main__":
    main()
