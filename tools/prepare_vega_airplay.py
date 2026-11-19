#!/usr/bin/env python3
"""Stage pinned UxPlay plus Buzin patches without changing shared submodules."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/cpp/third_party/UxPlay'
PATCHES = ROOT / 'app/src/main/cpp/patches/UxPlay'
REVISION = 'df67c212a433cf6dda3676dd40c097900d24e645'
DESTINATION = ROOT / 'vega/generated/UxPlay'


def git(*args, cwd):
    return subprocess.check_output(['git', *args], cwd=cwd)


def tree_digest(root):
    result = hashlib.sha256()
    for path in sorted(root.rglob('*')):
        if '.git' in path.relative_to(root).parts or path.is_dir():
            continue
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            raise RuntimeError('Unexpected symlink in staged UxPlay')
        result.update(relative.encode() + b'\0' + hashlib.sha256(path.read_bytes()).digest())
    return result.hexdigest()


def prepare(destination=DESTINATION):
    patches = sorted(PATCHES.glob('*.patch'))
    if len(patches) != 17:
        raise RuntimeError('Update the pinned patch inventory before preparing Vega')
    specification = {
        'upstreamCommit': REVISION,
        'patches': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in patches},
    }
    recorded = git('ls-tree', 'HEAD', 'app/src/main/cpp/third_party/UxPlay', cwd=ROOT).decode()
    if REVISION not in recorded.split():
        raise RuntimeError('Project UxPlay revision changed; review the Vega source pin')
    manifest = destination.parent / 'uxplay-source.json'
    if destination.exists() and manifest.exists():
        previous = json.loads(manifest.read_text())
        if all(previous.get(key) == value for key, value in specification.items()):
            if tree_digest(destination) != previous.get('sourceSha256'):
                raise RuntimeError('Staged UxPlay was modified; preserve changes before regenerating')
            print('Pinned Vega UxPlay source is ready')
            return
    if destination.exists():
        raise RuntimeError('Existing staged UxPlay differs; preserve it and remove it before regeneration')
    # git archive reads the pinned object, never the Android-patched worktree.
    archive = git('archive', '--format=tar', REVISION, cwd=SOURCE)
    stage = destination.with_name('UxPlay-preparing')
    if stage.exists():
        raise RuntimeError('Incomplete prior staging exists; inspect it before retrying')
    stage.mkdir(parents=True)
    try:
        with tarfile.open(fileobj=io.BytesIO(archive)) as package:
            package.extractall(stage, filter='data')
        subprocess.run(['git', 'init', '--quiet'], cwd=stage, check=True)
        for patch in patches:
            subprocess.run(['git', 'apply', '--check', '--unidiff-zero', str(patch)], cwd=stage, check=True)
            subprocess.run(['git', 'apply', '--unidiff-zero', str(patch)], cwd=stage, check=True)
        shutil.rmtree(stage / '.git')
        specification['sourceSha256'] = tree_digest(stage)
        stage.rename(destination)
        manifest.write_text(json.dumps(specification, indent=2) + '\n')
    except Exception:
        shutil.rmtree(stage, ignore_errors=True)
        raise
    print('Prepared isolated Vega UxPlay source with 17 patches')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, default=DESTINATION)
    args = parser.parse_args()
    prepare(args.destination.resolve())
