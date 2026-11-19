"""Build Android native tests; --legacy uses the API17 probe's NDK and C++17."""
import argparse, os, re, subprocess, platform
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--abi",choices=["x86","x86_64","armeabi-v7a","arm64-v8a"])
parser.add_argument("--api",type=int)
parser.add_argument("--legacy",action="store_true",help="NDK r23/C++17, no coverage or sanitizer runtime; API17 by default")
parser.add_argument("--sdk-root",type=Path)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args();root=Path(__file__).resolve().parents[1]
version="23.2.8568313" if args.legacy else re.search(r'^ndk = "([^"]+)"',(root/"gradle/libs.versions.toml").read_text(),re.M).group(1)
args.api=args.api or (17 if args.legacy else 25)
args.abi=args.abi or ("x86" if args.legacy else "x86_64")
if args.api < 21 and (not args.legacy or args.abi in ("x86_64","arm64-v8a")):
    parser.error("API17 requires --legacy with x86 or armeabi-v7a.")
sdk_value=args.sdk_root or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk_value and platform.system()=="Windows": sdk_value=Path(os.environ["LOCALAPPDATA"])/"Android/Sdk"
if not sdk_value: parser.error("Pass --sdk-root or set ANDROID_HOME.")
host={"Windows":"windows-x86_64","Linux":"linux-x86_64","Darwin":"darwin-x86_64"}[platform.system()]
toolchain=Path(sdk_value)/("ndk/"+version+"/toolchains/llvm/prebuilt/"+host)
compiler=toolchain/"bin"/("clang++.exe" if platform.system()=="Windows" else "clang++")
triple={"x86":"i686-linux-android","x86_64":"x86_64-linux-android","armeabi-v7a":"armv7a-linux-androideabi","arm64-v8a":"aarch64-linux-android"}[args.abi]
args.output.mkdir(parents=True,exist_ok=True)
instrumentation=[] if args.legacy else ["-fprofile-instr-generate","-fcoverage-mapping","-fcoverage-mcdc","-fprofile-update=atomic",
         "-fsanitize=undefined","-fsanitize-trap=undefined","-fno-omit-frame-pointer"]
callback_inputs=[]
if not args.legacy:
    # Compile the unchanged production C callbacks, with their normal dialect
    # and coverage identifiers. Only the unit executable wraps the TLS syscall.
    c_compiler=toolchain/"bin"/("clang.exe" if platform.system()=="Windows" else "clang")
    includes=["-I"+str(root/"app/src/main/cpp"),
              "-I"+str(root/"app/src/main/cpp/third_party/UxPlay/lib")]
    callback_objects=[]
    for source in (root/"app/src/main/cpp/android_raop_callbacks.c",
                   root/"app/src/test/cpp/callback_thread_key_contracts.c"):
        callback_object=args.output/(source.stem+".o")
        subprocess.run([str(c_compiler),"--target="+triple+str(args.api),
            "--sysroot="+str(toolchain/"sysroot"),"-std=c23","-O0","-g",*instrumentation,
            "-ffile-prefix-map="+str(root/"app/src/main/cpp")+"=.",*includes,
            "-c",str(source),"-o",str(callback_object)],check=True)
        callback_objects.append(str(callback_object))
    callback_inputs=["-DBUZIN_CALLBACK_KEY_CONTRACTS","-DBUZIN_CLOCK_CONTRACTS",*includes,*callback_objects,
                     "-Wl,--wrap=pthread_key_create","-Wl,--wrap=clock_gettime"]
command=[str(compiler),"--target="+triple+str(args.api),"--sysroot="+str(toolchain/"sysroot"),
         "-std=c++17" if args.legacy else "-std=c++23","-O1","-g","-static-libstdc++","-pthread",*instrumentation,
         "-ffile-prefix-map="+str(root/"app/src/main/cpp")+"=.","-I"+str(root/"app/src/main/cpp"),
         str(root/"app/src/test/cpp/native_core_tests.cpp"),*callback_inputs,
         "-latomic","-llog","-o",str(args.output/"native_core_tests")]
subprocess.run(command,check=True)
print("Native unit executable: "+str(args.output/"native_core_tests"))
