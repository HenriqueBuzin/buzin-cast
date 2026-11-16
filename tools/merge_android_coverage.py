"""Merge raw execution evidence for one unchanged APK across emulator APIs.

Requires immutable snapshots produced by run_android_coverage.py. Refuses to
combine editions or compiled inputs; percentages are never averaged. Reuses
archived class/native mappings (or matching live mappings for older snapshots)
to recalculate every metric.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

from run_android_coverage import VARIANTS, coverage_identity, source_identity
from test_emulator import validate_instrumentation_output


IDENTITY_KEYS=("apk_sha256","test_apk_sha256","class_files_sha256","class_counts","native_library_sha256")


def validate_snapshots(snapshots):
    if not snapshots:
        raise ValueError("At least one snapshot is required")
    manifests=[]
    for snapshot in snapshots:
        manifest=json.loads((snapshot/"manifest.json").read_text(encoding="utf-8"))
        if manifest.get("variant") not in VARIANTS or any(key not in manifest for key in IDENTITY_KEYS):
            raise ValueError("Snapshot is missing its variant or compiled-input identity")
        minimum=22 if VARIANTS[manifest["variant"]][0] else 25
        if not isinstance(manifest.get("emulator_api"),int) or manifest["emulator_api"]<minimum:
            raise ValueError("Snapshot has an invalid emulator API for its variant")
        results=json.loads((snapshot/"results.json").read_text(encoding="utf-8"))
        required={"Android socket integration","C++ unit concurrency and timing","media remote controls and rendered captions"}
        if not results or not all(result.get("passed") is True for result in results) or not required.issubset({result.get("test") for result in results}):
            raise ValueError("Snapshot must contain a completed instrumented/native/extended UI run")
        runner=next(result for result in results if result["test"]=="Android socket integration")
        try:
            validate_instrumentation_output(runner.get("runner",""),require_all=True)
        except AssertionError as error:
            raise ValueError("Snapshot instrumentation contains missing, failed or skipped tests") from error
        if not list(snapshot.glob("*.ec")) or not list(snapshot.glob("*.profraw")):
            raise ValueError("Snapshot is missing raw Android or native execution data")
        if manifest.get("archived_mappings"):
            if manifest.get("source_files_sha256") and source_identity(snapshot,archive=snapshot) != manifest["source_files_sha256"]:
                raise ValueError("Archived production sources do not match the compilation fingerprint")
            identity=coverage_identity(snapshot,snapshot,snapshot/"inputs/app.apk",snapshot/"inputs/test.apk",archive=snapshot)
            if any(identity[key]!=manifest[key] for key in IDENTITY_KEYS):
                raise ValueError("Archived compiled inputs do not match snapshot identity")
            if not all((snapshot/file).is_file() for file in ("core.exec","domain.exec")):
                raise ValueError("Archived JVM execution data is missing")
        manifests.append(manifest)
    first=manifests[0]
    for manifest in manifests[1:]:
        if manifest["variant"]!=first["variant"] or any(manifest[key]!=first[key] for key in IDENTITY_KEYS):
            raise ValueError("Cannot merge different variants or compiled inputs")
    return first,sorted({manifest["emulator_api"] for manifest in manifests})


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot",type=Path,action="append",required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--sdk-root",type=Path)
    parser.add_argument("--require-100",action="store_true")
    args=parser.parse_args()
    snapshots=list(dict.fromkeys(path.resolve() for path in args.snapshot))
    # Check all metadata before copying profiles or running Gradle.
    manifest,apis=validate_snapshots(snapshots)
    root=Path(__file__).resolve().parents[1]
    legacy,regional=VARIANTS[manifest["variant"]]
    suffix=("-legacy" if legacy else "")+("-nodownload" if regional else "")
    build=root/("app/build"+suffix+"-coverage")
    apk=build/"outputs/apk/debug/app-debug.apk"
    test_apk=build/"outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    expected={key:manifest[key] for key in IDENTITY_KEYS}
    archived=next((snapshot for snapshot in snapshots if (snapshot/"inputs/libairplay_native.so").is_file()),None)
    if manifest.get("archived_mappings") and archived is None:
        raise SystemExit("Snapshot declares archived mappings but its compiled inputs are missing")
    if coverage_identity(root,build,apk,test_apk,archive=archived)!=expected:
        raise SystemExit("Current compiled mappings do not match the snapshots; restore/rebuild the matching APK")
    output=args.output.resolve()
    execution=output/"execution"/uuid.uuid4().hex
    execution.mkdir(parents=True)
    for index,snapshot in enumerate(snapshots):
        target=execution/str(index)
        target.mkdir()
        for pattern in ("*.ec","*.profraw","*.exec"):
            for file in snapshot.glob(pattern):
                shutil.copyfile(file,target/file.name)
    env=os.environ.copy()
    if args.sdk_root:
        env["ANDROID_HOME"]=str(args.sdk_root.resolve())
    for name in ("buzinLegacy","buzinNoDownload","buzinRegionalAppstoreAssets"):
        env.pop("ORG_GRADLE_PROJECT_"+name,None)
    env["BUZIN_LEGACY"]=str(legacy).lower()
    env["BUZIN_NODOWNLOAD"]=str(regional).lower()
    flags=["-PbuzinCoverage=true","-PbuzinAbis=x86_64","-PbuzinLegacy="+str(legacy).lower(),"-PbuzinNoDownload="+str(regional).lower(),"-PbuzinCoverageData="+str(execution)]
    if archived is not None:
        flags.append("-PbuzinCoverageMappings="+str(archived))
    if regional:
        fixture=output/"regional-debug-assets"
        fixture.mkdir(exist_ok=True)
        shutil.copyfile(root/"app/src/main/assets/AppstoreAuthenticationKey.pem",fixture/"AppstoreAuthenticationKey.pem")
        flags.append("-PbuzinRegionalAppstoreAssets="+str(fixture))
    gradle=[str(root/"gradlew.bat")] if os.name=="nt" else ["bash",str(root/"gradlew")]
    with (output/"jacoco.log").open("w",encoding="utf-8") as log:
        checks=[] if archived is not None else [":receiver-core:check",":dlna-domain:check"]
        subprocess.run(gradle+flags+checks+[":app:fullCoverageReport","--console=plain"],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    if coverage_identity(root,build,apk,test_apk,archive=archived)!=expected:
        raise SystemExit("Compiled mappings changed while reporting; merged result is invalid")
    shutil.copyfile(build/"reports/jacoco/fullCoverageReport/fullCoverageReport.xml",output/"android.xml")
    command=[sys.executable,str(root/"tools/collect_native_coverage.py"),"--profiles",str(execution),"--output",str(output)]
    if archived is not None:
        command += ["--native-binary",str(archived/"inputs/libairplay_native.so"),"--native-source-dir",str(archived/"sources/native"),"--jacoco",str(output/"android.xml")]
    if legacy: command.append("--legacy")
    if regional: command.append("--no-download")
    if args.require_100: command.append("--require-100")
    # Retain provenance even when the strict metric check returns nonzero.
    (output/"merged-inputs.json").write_text(json.dumps({"variant":manifest["variant"],"emulator_apis":apis,"snapshot_count":len(snapshots),**expected},indent=2),encoding="utf-8")
    subprocess.run(command,cwd=root,env=env,check=True)


if __name__=="__main__":
    main()
