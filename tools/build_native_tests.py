"""Build Android native tests; --legacy uses the API17 probe's NDK and C++17."""
import argparse, os, re, subprocess, platform, hashlib, json
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--abi",choices=["x86","x86_64","armeabi-v7a","arm64-v8a"])
parser.add_argument("--api",type=int)
parser.add_argument("--legacy",action="store_true",help="NDK r23/C++17, no coverage or sanitizer runtime; API17 by default")
mirror_mode=parser.add_mutually_exclusive_group()
mirror_mode.add_argument("--mirror-crypto",action="store_true",help="Build the shared mirror CTR regression with production crypto on API17; uses trap-only UBSan.")
mirror_mode.add_argument("--mirror-transport",action="store_true",help="Test mirror TCP EOF, reconnect and owner join using production C sources.")
parser.add_argument("--plist-library",type=Path,help="For --mirror-transport: libplist.a from the same runtime ABI.")
parser.add_argument("--openssl-prefix",type=Path,help="API17 OpenSSL install prefix containing include/ and lib/libcrypto.a.")
parser.add_argument("--adb",default="adb")
parser.add_argument("--adb-server-port",type=int,default=5037)
parser.add_argument("--serial",help="For a mirror regression: run on an isolated API17 TCP emulator after building.")
parser.add_argument("--sdk-root",type=Path)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args();root=Path(__file__).resolve().parents[1]
mirror_test=args.mirror_crypto or args.mirror_transport
if mirror_test and (not args.legacy or not args.openssl_prefix):parser.error("Mirror regressions require --legacy and --openssl-prefix.")
if args.mirror_transport and (not args.plist_library or not args.plist_library.is_file()):parser.error("--mirror-transport requires --plist-library for the selected ABI.")
if args.plist_library and not args.mirror_transport:parser.error("--plist-library applies only to --mirror-transport.")
if not mirror_test and (args.openssl_prefix or args.serial):parser.error("--openssl-prefix and --serial require a mirror regression.")
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
if mirror_test:
    prefix=args.openssl_prefix.resolve()
    if not (prefix/"include/openssl/evp.h").is_file() or not (prefix/"lib/libcrypto.a").is_file():parser.error("Use a built API17 OpenSSL prefix for the selected ABI.")
    source=root/"app/src/main/cpp/third_party/UxPlay/lib"
    fixture=root/("fireos3-runtime/src/test/cpp/MirrorTransportTest.cpp" if args.mirror_transport else "vega/test/MirrorCryptoTest.cpp")
    label="mirror-transport" if args.mirror_transport else "mirror-crypto"
    names=("mirror_buffer","crypto","utils")+( ("raop_rtp_mirror","raop_ntp","byteutils","logger","netutils") if args.mirror_transport else () )
    expected="Mirror TCP lifecycle: 12 scenarios passed" if args.mirror_transport else "Mirror AES-CTR: 66 partition/re-key cases passed"
    result={"built":False,"passed":None,"api":args.api,"abi":args.abi,"physicalValidation":False,"fairPlayNegotiation":False,"scope":"Production mirror TCP EOF/reconnect/owner join and descriptor cleanup" if args.mirror_transport else "Shared production CTR packet boundaries, in-place decode and re-key; not FairPlay or physical validation",
            "openssl_sha256":hashlib.sha256((prefix/"lib/libcrypto.a").read_bytes()).hexdigest(),
            "stack_protector":"strong with global guard" if args.abi=="x86" and args.api<18 else "strong with target default guard",
            "source_sha256":{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (*[source/(name+".c") for name in names],fixture)}}
    if args.mirror_transport:result["plist_sha256"]=hashlib.sha256(args.plist_library.read_bytes()).hexdigest()
    def save():(args.output/(label+"-result.json")).write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    save()
    common=["--target="+triple+str(args.api),"--sysroot="+str(toolchain/"sysroot"),"-g","-O1","-fstack-protector-strong","-fsanitize=undefined","-fsanitize-trap=undefined",
            "-ffile-prefix-map="+str(root)+"=.","-I"+str(source),"-I"+str(root/"app/src/main/cpp/third_party/libplist/include"),"-I"+str(prefix/"include")]
    if args.abi=="x86" and args.api<18:common.append("-mstack-protector-guard=global")
    c_compiler=toolchain/"bin"/("clang.exe" if platform.system()=="Windows" else "clang")
    with (args.output/"build.log").open("w",encoding="utf-8") as log:
        objects=[]
        for name in names:
            target=args.output/(name+".o")
            subprocess.run([str(c_compiler),*common,"-std=c11","-D_GNU_SOURCE","-Wno-deprecated-declarations","-c",str(source/(name+".c")),"-o",str(target)],stdout=log,stderr=subprocess.STDOUT,check=True)
            objects.append(str(target))
        executable=args.output/(label.replace("-","_")+"_tests")
        libraries=[str(args.plist_library)] if args.mirror_transport else []
        subprocess.run([str(compiler),*common,"-std=c++17","-static-libstdc++",str(fixture),*objects,*libraries,str(prefix/"lib/libcrypto.a"),"-ldl","-llog","-pthread","-o",str(executable)],stdout=log,stderr=subprocess.STDOUT,check=True)
    result.update(built=True,binary_sha256=hashlib.sha256(executable.read_bytes()).hexdigest());save()
    if args.serial:
        local=re.fullmatch(r"127\.0\.0\.1:(\d+)",args.serial)
        if not local or not 5555<=int(local[1])<=5683 or int(local[1])%2!=1 or args.api!=17 or not 1<=args.adb_server_port<=65535:parser.error("Run only on an isolated API17 TCP emulator.")
        def adb(*command):return subprocess.check_output([args.adb,"-P",str(args.adb_server_port),"-s",args.serial,*map(str,command)],timeout=60).decode("utf-8","replace")
        if adb("shell","getprop","ro.kernel.qemu").strip()!="1" or adb("shell","getprop","ro.build.version.sdk").strip()!="17" or adb("shell","getprop","ro.product.cpu.abi").strip()!=args.abi:parser.error("Emulator API/ABI differs from the native test target.")
        remote="/data/local/tmp/buzin-"+label+"-tests"
        try:
            result["passed"]=False;save();adb("push",executable,remote);adb("shell","chmod","755",remote)
            # Old adbd does not propagate the program's exit code. Check the
            # shell status too, including failures during C++/OpenSSL teardown.
            output=adb("shell",remote+"; result=$?; echo MIRROR_CRYPTO_EXIT=$result");(args.output/"run.txt").write_text(output,encoding="utf-8")
            if expected not in output or not re.search(r"^MIRROR_CRYPTO_EXIT=0$",output.replace("\r",""),re.M):raise AssertionError("Native mirror regression failed; inspect run.txt")
            result["passed"]=True;save();print(output.strip())
        finally:adb("shell","rm","-f",remote)
    else:print("Built "+label+" test; execution is not yet verified.")
    raise SystemExit(0)
instrumentation=[] if args.legacy else ["-fprofile-instr-generate","-fcoverage-mapping","-fcoverage-mcdc","-fprofile-update=atomic",
         "-fsanitize=undefined","-fsanitize-trap=undefined","-fno-omit-frame-pointer"]
callback_inputs=[]
if not args.legacy:
    # Compile the production C code with its normal dialect and coverage
    # identifiers. Fault injection is linked only into the unit executable.
    c_compiler=toolchain/"bin"/("clang.exe" if platform.system()=="Windows" else "clang")
    includes=["-I"+str(root/"app/src/main/cpp"),
              "-I"+str(root/"app/src/main/cpp/third_party/UxPlay/lib")]
    callback_objects=[]
    for source in (root/"app/src/main/cpp/android_raop_callbacks.c",
                   root/"app/src/main/cpp/android_dnssd_shim.c",
                   root/"app/src/main/cpp/third_party/UxPlay/lib/utils.c",
                   root/"app/src/test/cpp/callback_thread_key_contracts.c",
                   root/"app/src/test/cpp/dnssd_allocation_contracts.c"):
        callback_object=args.output/(source.stem+".o")
        subprocess.run([str(c_compiler),"--target="+triple+str(args.api),
            "--sysroot="+str(toolchain/"sysroot"),"-std=c23","-O0","-g",*instrumentation,
            "-ffile-prefix-map="+str(root/"app/src/main/cpp")+"=.",*includes,
            "-c",str(source),"-o",str(callback_object)],check=True)
        callback_objects.append(str(callback_object))
    callback_inputs=["-DBUZIN_CALLBACK_KEY_CONTRACTS","-DBUZIN_CLOCK_CONTRACTS","-DBUZIN_DNSSD_ALLOC_CONTRACTS",
                     *includes,*callback_objects,"-Wl,--wrap=pthread_key_create","-Wl,--wrap=clock_gettime",
                     "-Wl,--wrap=calloc","-Wl,--wrap=strndup","-Wl,--wrap=malloc","-Wl,--wrap=free"]
command=[str(compiler),"--target="+triple+str(args.api),"--sysroot="+str(toolchain/"sysroot"),
         "-std=c++17" if args.legacy else "-std=c++23","-O1","-g","-static-libstdc++","-pthread",*instrumentation,
         "-ffile-prefix-map="+str(root/"app/src/main/cpp")+"=.","-I"+str(root/"app/src/main/cpp"),
         str(root/"app/src/test/cpp/native_core_tests.cpp"),*callback_inputs,
         "-latomic","-llog","-o",str(args.output/"native_core_tests")]
subprocess.run(command,check=True)
print("Native unit executable: "+str(args.output/"native_core_tests"))
