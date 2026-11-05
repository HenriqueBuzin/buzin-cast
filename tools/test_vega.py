#!/usr/bin/env python3
"""Smoke real do Vega em VVD; recusa aparelhos físicos e não anuncia receptores."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

COMPONENT = "com.buzincast.tv.main"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--vpkg", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("build/vega-checks"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use somente um serial emulator-N do VVD, nunca uma TV física")
    sdk = shutil.which("vega") or str(Path.home() / "vega/bin/vega")
    if not args.vpkg.is_file():
        parser.error("VPKG ausente: compile a arquitetura do VVD primeiro")
    args.output.mkdir(parents=True, exist_ok=True)
    history = []

    def run(arguments, timeout=30):
        result = subprocess.run([sdk, *arguments], capture_output=True, text=True,
                                timeout=timeout, env=os.environ.copy())
        history.append({"args": arguments, "exit": result.returncode,
                        "stdout": result.stdout, "stderr": result.stderr})
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout

    def shell(*command):
        return run(["exec", "vda", "-s", args.serial, "shell", *command])

    def app_shell(*command):
        return shell("-c", COMPONENT, *command)

    report = {"serial": args.serial, "package": str(args.vpkg.resolve()),
              "checks": {}, "physicalValidation": False}
    try:
        devices = run(["exec", "vda", "devices"])
        if not re.search(rf"^{re.escape(args.serial)}\s+device\b", devices, re.M):
            raise RuntimeError("O VVD indicado não está conectado")
        # Terminate only this app, preserving settings and all other packages.
        running = shell("vlcm", "list", "--app-id", COMPONENT)
        if COMPONENT in running:
            shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
        run(["run-app", str(args.vpkg.resolve()), COMPONENT, "-d", args.serial], timeout=120)
        app_shell("rm", "-f", "/data/network-probe.json")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        for attempt in range(10):
            try:
                native = json.loads(app_shell("cat", "/data/network-probe.json"))
                break
            except (RuntimeError, json.JSONDecodeError):
                if attempt == 9:
                    raise
                time.sleep(0.5)
        expected = ["tcpLoopback", "udpLoopback", "wildcardBind", "multicastMembership",
                    "rtspParser", "malformedRtspRejected", "aesCtrVector"]
        if any(native.get(key) is not True for key in expected):
            raise RuntimeError(f"Diagnóstico nativo reprovado: {native}")
        report["checks"]["remoteToNativeSandbox"] = native
        # Return to Home, move to About, return and run the diagnostic again.
        shell("inputd-cli", "button_press", "KEY_BACK")
        shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        shell("inputd-cli", "button_press", "KEY_BACK")
        shell("inputd-cli", "button_press", "KEY_LEFT")
        app_shell("rm", "-f", "/data/network-probe.json")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        repeated = json.loads(app_shell("cat", "/data/network-probe.json"))
        if repeated != native:
            raise RuntimeError("Retorno pelo controle não restaurou a navegação")
        report["checks"]["remoteReturnAndRepeat"] = True
        report["passed"] = True
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except Exception as error:
        report.update(passed=False, error=str(error))
        raise
    finally:
        (args.output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (args.output / "commands.json").write_text(json.dumps(history, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
