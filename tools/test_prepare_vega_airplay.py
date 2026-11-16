#!/usr/bin/env python3
"""Verify actual source staging, cache reuse and protection of manual edits."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='buzin-vega-core-') as folder:
    destination = Path(folder) / 'UxPlay'
    command = [sys.executable, str(ROOT / 'tools/prepare_vega_airplay.py'), '--destination', str(destination)]
    subprocess.run(command, check=True)
    report = (destination.parent / 'uxplay-source.json').read_bytes()
    subprocess.run(command, check=True)
    assert report == (destination.parent / 'uxplay-source.json').read_bytes()
    source = destination / 'lib/raop.c'
    before = source.read_bytes()
    source.write_bytes(before + b'\n/* local edit */\n')
    rejected = subprocess.run(command, capture_output=True, text=True)
    assert rejected.returncode != 0 and 'modified' in rejected.stderr
    assert source.read_bytes() == before + b'\n/* local edit */\n'
print('Isolated UxPlay: canonical patches, repeatability and edit protection passed')
