"""Install the pinned stable CMake from Kitware, verifying its release asset SHA256.

Linux/WSL and Windows are supported. Only cmake.dir is changed in ignored local.properties.
"""
import hashlib
import json
import os
import platform
import re
import shutil
import tarfile
import urllib.request
import zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
version=re.search(r'^cmake = "([^"]+)"',(root/"gradle/libs.versions.toml").read_text(),re.M).group(1)
system=platform.system().lower()
if system not in ("linux","windows") or platform.machine().lower() not in ("x86_64","amd64"):
    raise SystemExit("Install Kitware CMake "+version+" manually and set cmake.dir for this platform")
name="cmake-"+version+"-"+("linux-x86_64.tar.gz" if system=="linux" else "windows-x86_64.zip")
base=Path(os.environ.get("BUZIN_TOOLCHAIN_HOME",str(root/"build/toolchains"))).resolve()
base.mkdir(parents=True,exist_ok=True)
folder=base/name.replace(".tar.gz","").replace(".zip","")
if not (folder/"bin"/("cmake.exe" if system=="windows" else "cmake")).is_file():
    request=urllib.request.Request("https://api.github.com/repos/Kitware/CMake/releases/tags/v"+version,headers={"User-Agent":"Buzin-Cast-build"})
    release=json.load(urllib.request.urlopen(request,timeout=60))
    asset=next(asset for asset in release["assets"] if asset["name"]==name)
    expected=asset.get("digest","").removeprefix("sha256:") if hasattr(str,"removeprefix") else asset.get("digest","").replace("sha256:","",1)
    if not re.fullmatch(r"[0-9a-f]{64}",expected): raise SystemExit("Official release asset has no SHA256 digest")
    archive=base/name
    with urllib.request.urlopen(asset["browser_download_url"],timeout=60) as source,archive.open("wb") as target:
        shutil.copyfileobj(source,target)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected: raise SystemExit("CMake checksum mismatch")
    def safe(name):
        resolved=(base/name).resolve()
        if base not in resolved.parents: raise SystemExit("Unsafe archive path: "+name)
    if name.endswith(".zip"):
        with zipfile.ZipFile(archive) as source:
            for member in source.namelist(): safe(member)
            source.extractall(base)
    else:
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                safe(member.name)
                if member.issym(): safe(str(Path(member.name).parent/member.linkname))
                if member.islnk(): safe(member.linkname)
            source.extractall(base)
    archive.unlink()
props=root/"local.properties"
text=props.read_text() if props.exists() else ""
text=re.sub(r"(?m)^cmake\.dir=.*\n?","",text)
props.write_text(text.rstrip()+"\ncmake.dir="+folder.as_posix()+"\n")
print("CMake "+version+" configured at "+str(folder))
