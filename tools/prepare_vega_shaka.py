#!/usr/bin/env python3
"""Prepare the official Vega-patched Shaka source in an ignored build directory.

This does not enable adaptive playback or alter an installed application.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "4.16.13"
UPSTREAM_COMMIT = "969d23b040e5e2e19e06d99d27ff6ad2c24fc45f"
PATCH_URL = "https://amzndevresources.com/vega/media-player/shaka-rel-v4.16.13-r1.2-devices_scope.tar.gz"
PATCHED_TREE = "4b7df0f3187c2513416f5832fa690191260eb466"
PATCH_SHA256 = "1c06ca1c8502680f2e41609f7feb7483b5a16a78ec670e71a17eb3813e17d120"
REPOSITORY = "https://github.com/shaka-project/shaka-player.git"


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)


def prepare(destination):
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / "amazon-shaka-patches.tar.gz"
    if not archive.exists():
        partial = archive.with_suffix(".download")
        run("curl", "--fail", "--location", "--retry", "2", PATCH_URL, "--output", str(partial))
        if hashlib.sha256(partial.read_bytes()).hexdigest() != PATCH_SHA256:
            partial.unlink()
            raise RuntimeError("Official Vega patch archive checksum mismatch")
        partial.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != PATCH_SHA256:
        raise RuntimeError("Cached Vega patch archive checksum mismatch")
    source = destination / "shaka-player"
    marker = destination / "prepared-shaka.txt"
    expected = f"{VERSION}\n{UPSTREAM_COMMIT}\n{PATCH_SHA256}\n"
    if marker.exists():
        if marker.read_text() != expected or not (source / ".git").is_dir():
            raise RuntimeError("Existing preparation differs; use a new destination")
        tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=source, text=True).strip()
        if tree != PATCHED_TREE:
            raise RuntimeError("Cached patched source tree differs from the pinned build")
        run("git", "diff", "HEAD", "--exit-code", cwd=source)
        return source
    if not source.exists():
        run("git", "clone", "--depth", "1", "--branch", f"v{VERSION}", REPOSITORY, str(source))
    # Refuse to patch arbitrary versions or a dirty tree.
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    tag = subprocess.check_output(["git", "rev-parse", f"v{VERSION}^{{commit}}"], cwd=source, text=True).strip()
    if head != tag or head != UPSTREAM_COMMIT:
        raise RuntimeError("Source is not the required Shaka release; use a new destination")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=source):
        raise RuntimeError("Source contains local changes; use a new destination")
    extracted = destination / "amazon-patches"
    extracted.mkdir(exist_ok=True)
    with tarfile.open(archive) as package:
        package.extractall(extracted, filter="data")
    patches = sorted(path for path in (extracted / "shaka-rel/shaka-patch").glob("*.patch")
                     if not path.name.startswith("."))
    if len(patches) != 33:
        raise RuntimeError("Unexpected official patch set")
    run("git", "-c", "user.name=Buzin Cast Build", "-c", "user.email=build@buzincast.invalid",
        "am", *map(str, patches), cwd=source)
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=source, text=True).strip()
    if tree != PATCHED_TREE:
        raise RuntimeError("Official patches produced an unexpected source tree")
    marker.write_text(expected)
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=ROOT / "build/vega-adaptive")
    parser.add_argument("--build", action="store_true", help="Build the patched Shaka distribution")
    args = parser.parse_args()
    source = prepare(args.destination.resolve())
    print(f"Prepared Vega Shaka {VERSION}: {source}")
    if args.build:
        run(shutil.which("python3") or "python3", "build/build.py", "--name", "compiled", "--skip-ts", "+@complete", cwd=source)
        output = source / "dist/shaka-player.compiled.js"
        if not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError("Shaka build did not produce the requested distribution")
        report = {
            "version": VERSION,
            "upstreamCommit": UPSTREAM_COMMIT,
            "patchArchiveSha256": PATCH_SHA256,
            "patchedTree": subprocess.check_output(
                ["git", "rev-parse", "HEAD^{tree}"], cwd=source, text=True).strip(),
            "distributionSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "license": "Apache-2.0; preserve upstream LICENSE and bundled notices",
            "runtimeValidated": False,
        }
        (args.destination.resolve() / "build-report.json").write_text(
            json.dumps(report, indent=2) + "\n")
        print(f"Built {output}")


if __name__ == "__main__":
    main()
