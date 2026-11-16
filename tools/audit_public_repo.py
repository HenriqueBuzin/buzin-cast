"""Check public source/history without printing matched private values.

This is a focused guard, not a guarantee that every kind of secret is detected.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    'private_key': rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    'token': rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{30,})',
}
DOCUMENT_PATTERNS = {
    'home_network': rb'(?<![\d.])192\.168\.\d{1,3}\.\d{1,3}(?![\d.])',
    'personal_path': rb'(?:[A-Z]:[\\/]Users[\\/][^\\/\s`]+|/Users/[^/\s`]+)',
}


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def scan():
    findings = []
    paths = set(git('ls-files', '-c', '-o', '--exclude-standard', '-z').decode().split('\0'))
    for name in sorted(paths - {''}):
        path = ROOT / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        if b'\0' in data:
            continue
        patterns = PATTERNS | (DOCUMENT_PATTERNS if path.suffix.lower() in ('.md', '.ps1', '.json') else {})
        for kind, pattern in patterns.items():
            lines = [index + 1 for index, line in enumerate(data.splitlines()) if re.search(pattern, line)]
            if lines:
                findings.append({'file': name, 'kind': kind, 'lines': lines})
    return findings


def history():
    # Logical branches/tags only. Local Codex tree snapshots and reflogs are
    # private recovery data and are not pushed by the release workflow.
    refs = git('for-each-ref', '--format=%(refname)', 'refs/heads', 'refs/remotes', 'refs/tags').decode().splitlines()
    findings = []
    patterns = {
        'private_key': r'BEGIN (RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY',
        'token': r'(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{30,})',
        'document_private_data': r'(192\.168\.[0-9]+\.[0-9]+|[A-Z]:[\\/]Users[\\/]|/Users/)',
    }
    for kind, pattern in patterns.items():
        args = ['log', *refs, '--format=commit %H', '--name-only', '-G', pattern]
        if kind == 'document_private_data':
            args += ['--', '*.md', '*.ps1', '*.json']
        result = git(*args).decode().strip()
        if result:
            findings.append({'kind': kind, 'locations': result.splitlines()})
    return findings


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--history', action='store_true')
    options = parser.parse_args()
    report = {'current': scan()}
    if options.history:
        report['history'] = history()
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1 if any(report.values()) else 0)
