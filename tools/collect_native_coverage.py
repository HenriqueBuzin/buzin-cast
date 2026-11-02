"""Locate build-matching NDK LLVM tools and the unstripped coverage library."""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--profiles",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
parser.add_argument("--require-100",action="store_true")
parser.add_argument("--legacy",action="store_true",default=os.environ.get("BUZIN_LEGACY")=="true")
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
ndk=re.search(r'^ndk = "([^"]+)"', (root/"gradle/libs.versions.toml").read_text(), re.M).group(1)
llvm=sdk/("ndk/"+ndk+"/toolchains/llvm/prebuilt/linux-x86_64/bin")
app_build="build-legacy-coverage" if args.legacy else "build-coverage"
binaries=list((root/("app/"+app_build+"/intermediates/cxx/Debug")).glob("*/obj/x86_64/libairplay_native.so"))
binaries=[binary for binary in binaries if b"__llvm_covmap" in subprocess.check_output([str(llvm/"llvm-readelf"),"-S",str(binary)])]
if not binaries: raise SystemExit("Instrumented x86_64 native library missing; build with -PbuzinCoverage=true")
binary=max(binaries,key=lambda path:path.stat().st_mtime)
command=[sys.executable,str(root/"tools/report_coverage.py"),"--jacoco",str(root/("app/"+app_build+"/reports/jacoco/fullCoverageReport/fullCoverageReport.xml")),"--native-binary",str(binary),"--llvm-bin",str(llvm),"--profiles",str(args.profiles),"--output",str(args.output)]
for unit in args.profiles.rglob("native_core_tests"): command += ["--native-object",str(unit)]
if args.require_100: command.append("--require-100")
subprocess.run(command,check=True)
