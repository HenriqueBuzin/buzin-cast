"""Consolidate real JaCoCo + LLVM coverage. Never substitutes a target for a result.

Use --require-100 in a strict acceptance job; a missing report always fails.
LLVM has no bytecode-instruction metric, so native code reports source regions,
functions, lines and branches instead. Third-party native code is not compiled
with instrumentation. No application source file is filtered out here.
"""
import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


def ratio(covered, total):
    return {"covered": covered, "total": total, "percent": round(100*covered/total, 4) if total else None}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jacoco", type=Path, required=True)
    parser.add_argument("--native-object", type=Path, action="append", default=[])
    parser.add_argument("--native-binary", type=Path, required=True)
    parser.add_argument("--profiles", type=Path, required=True)
    parser.add_argument("--llvm-bin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-100", action="store_true")
    args=parser.parse_args()
    for name in ("jacoco","native_binary","profiles","llvm_bin","output"):
        setattr(args,name,getattr(args,name).resolve())
    args.native_object=[path.resolve() for path in args.native_object]
    args.output.mkdir(parents=True,exist_ok=True)
    profiles=sorted(args.profiles.rglob("*.profraw"))
    if not profiles: raise SystemExit("No native runtime profiles collected")
    merged=args.output/"native.profdata"
    subprocess.run([str(args.llvm_bin/"llvm-profdata"),"merge","-sparse",*map(str,profiles),"-o",str(merged)],check=True)
    source_dir=Path(__file__).resolve().parents[1]/"app/src/main/cpp"
    command=[str(args.llvm_bin/"llvm-cov"),"export",str(args.native_binary),"-instr-profile="+str(merged),"--compilation-dir="+str(source_dir)]
    command += ["--object="+str(binary) for binary in args.native_object]
    native=json.loads(subprocess.check_output(command,cwd=source_dir))
    owned={source.name for source in source_dir.iterdir() if source.suffix in (".c",".cpp",".h") and source.name!="coverage_bridge.cpp"}
    measured=[file["filename"] for file in native["data"][0]["files"] if Path(file["filename"]).name in owned]
    required={"native_bridge.cpp","audio_engine.cpp","android_raop_callbacks.c","android_dnssd_shim.c"}
    if not required.issubset({Path(name).name for name in measured}): raise SystemExit("App-owned native sources missing from coverage")
    # LLVM also emits instantiated STL/vendor headers. Restrict by an explicit
    # inventory of app-owned source files, never by which files were executed.
    data=native["data"][0]
    data["files"]=[file for file in data["files"] if file["filename"] in measured]
    data["functions"]=[function for function in data.get("functions",[]) if any(name in measured for name in function.get("filenames",[]))]
    # llvm-cov's export totals can still include the test executable's entry
    # points. Sum only production-file summaries, never the binary-wide totals.
    totals={}
    for file in data["files"]:
        for name,value in file["summary"].items():
            if "covered" not in value: continue
            metric=totals.setdefault(name,{"count":0,"covered":0})
            metric["count"]+=value["count"]; metric["covered"]+=value["covered"]
    data["totals"]=totals
    (args.output/"native.json").write_text(json.dumps(native),encoding="utf-8")
    root=ET.parse(args.jacoco).getroot()
    kotlin={node.get("type"):ratio(int(node.get("covered")),int(node.get("covered"))+int(node.get("missed"))) for node in root.findall("counter")}
    native_metrics={name:ratio(value["covered"],value["count"]) for name,value in native["data"][0]["totals"].items() if "covered" in value}
    sources=[{"file":package.get("name")+"/"+source.get("name"),"metrics":{counter.get("type"):ratio(int(counter.get("covered")),int(counter.get("covered"))+int(counter.get("missed"))) for counter in source.findall("counter")}} for package in root.findall("package") for source in package.findall("sourcefile")]
    result={"android_and_jvm":kotlin,"native":native_metrics,"sources":sources,
            "native_sources":[file["filename"] for file in native["data"][0]["files"]]}
    result["target_met"]=bool(kotlin and native_metrics) and all(metric["covered"]==metric["total"] for group in (kotlin,native_metrics) for metric in group.values())
    (args.output/"summary.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
    print(json.dumps({key:value for key,value in result.items() if key not in ("sources","native_sources")},indent=2))
    if args.require_100 and not result["target_met"]: raise SystemExit("100% acceptance target not met; see summary.json")


if __name__=="__main__": main()
