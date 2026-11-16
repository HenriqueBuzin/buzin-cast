"""Locate build-matching NDK LLVM tools and the unstripped coverage library."""
import argparse
import os
import re
import subprocess
import sys
import platform
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--profiles",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
parser.add_argument("--require-100",action="store_true")
parser.add_argument("--native-binary",type=Path,help="Use an immutable archived application library")
parser.add_argument("--native-source-dir",type=Path)
parser.add_argument("--jacoco",type=Path,help="Use a matching archived/merged Android report")
parser.add_argument("--legacy",action="store_true",default=os.environ.get("BUZIN_LEGACY")=="true")
parser.add_argument("--no-download",action="store_true",default=os.environ.get("BUZIN_NODOWNLOAD")=="true")
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
sdk_value=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk_value and platform.system()=="Windows": sdk_value=Path(os.environ["LOCALAPPDATA"])/"Android/Sdk"
if not sdk_value: parser.error("Set ANDROID_HOME or ANDROID_SDK_ROOT.")
sdk=Path(sdk_value)
ndk=re.search(r'^ndk = "([^"]+)"', (root/"gradle/libs.versions.toml").read_text(), re.M).group(1)
host={"Windows":"windows-x86_64","Linux":"linux-x86_64","Darwin":"darwin-x86_64"}[platform.system()]
llvm=sdk/("ndk/"+ndk+"/toolchains/llvm/prebuilt/"+host+"/bin")
app_build="build"+("-legacy" if args.legacy else "")+("-nodownload" if args.no_download else "")+"-coverage"
binaries=[args.native_binary] if args.native_binary else list((root/("app/"+app_build+"/intermediates/cxx/Debug")).glob("*/obj/x86_64/libairplay_native.so"))
binaries=[binary for binary in binaries if b"__llvm_covmap" in subprocess.check_output([str(llvm/"llvm-readelf"),"-S",str(binary)])]
if not binaries: raise SystemExit("Instrumented x86_64 native library missing; build with -PbuzinCoverage=true")
binary=max(binaries,key=lambda path:path.stat().st_mtime)
jacoco=args.jacoco or root/("app/"+app_build+"/reports/jacoco/fullCoverageReport/fullCoverageReport.xml")
command=[sys.executable,str(root/"tools/report_coverage.py"),"--jacoco",str(jacoco),"--native-binary",str(binary),"--llvm-bin",str(llvm),"--profiles",str(args.profiles),"--output",str(args.output)]
if args.native_source_dir: command += ["--native-source-dir",str(args.native_source_dir)]
# Unit profiles are merged as execution evidence, while the APK library alone
# defines which functions ship. Adding the test executable as another object
# would also count API17-only helpers that are not present in these four APKs.
if args.require_100: command.append("--require-100")
subprocess.run(command,check=True)
