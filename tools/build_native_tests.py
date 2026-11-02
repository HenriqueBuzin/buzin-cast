"""Build standalone C++23 tests for Android with the same pinned NDK as the APK."""
import argparse, os, re, subprocess
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--abi",choices=["x86_64","armeabi-v7a","arm64-v8a"],default="x86_64")
parser.add_argument("--api",type=int,default=25)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args();root=Path(__file__).resolve().parents[1]
version=re.search(r'^ndk = "([^"]+)"',(root/"gradle/libs.versions.toml").read_text(),re.M).group(1)
sdk=Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
bin=sdk/("ndk/"+version+"/toolchains/llvm/prebuilt/linux-x86_64/bin")
triple={"x86_64":"x86_64-linux-android","armeabi-v7a":"armv7a-linux-androideabi","arm64-v8a":"aarch64-linux-android"}[args.abi]
compiler=bin/(triple+str(args.api)+"-clang++")
args.output.mkdir(parents=True,exist_ok=True)
command=[str(compiler),"-std=c++23","-O1","-g","-static-libstdc++","-fprofile-instr-generate","-fcoverage-mapping","-fcoverage-mcdc","-fprofile-update=atomic",
         "-fsanitize=undefined","-fsanitize-trap=undefined","-fno-omit-frame-pointer",
         "-ffile-prefix-map="+str(root/"app/src/main/cpp")+"=.","-I"+str(root/"app/src/main/cpp"),
         str(root/"app/src/test/cpp/native_core_tests.cpp"),"-o",str(args.output/"native_core_tests")]
subprocess.run(command,check=True)
print("Native unit executable: "+str(args.output/"native_core_tests"))
