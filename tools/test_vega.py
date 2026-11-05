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
        # Query system codecs through the public system media descriptor, then traverse About.
        shell("inputd-cli", "button_press", "KEY_BACK")
        app_shell("rm", "-f", "/data/media-capabilities.json")
        shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        for attempt in range(10):
            try:
                media = json.loads(app_shell("cat", "/data/media-capabilities.json"))
                break
            except (RuntimeError, json.JSONDecodeError):
                if attempt == 9:
                    raise
                time.sleep(0.5)
        for key in ("h264Decoders", "hevcDecoders", "aacDecoders"):
            if type(media.get(key)) is not int or media[key] < 0:
                raise RuntimeError(f"Capacidade de mídia inválida: {media}")
        report["checks"]["mediaDescriptorQuery"] = media

        def playback():
            value = json.loads(app_shell("cat", "/data/playback-state.json"))
            if (type(value.get("seconds")) not in (int, float)
                    or not 0 <= value["seconds"] < 60):
                raise RuntimeError(f"Relógio de reprodução inválido: {value}")
            if value.get("state") == "error":
                raise RuntimeError(f"Falha real do player: {value}")
            return value

        def wait_playback(predicate, timeout=20):
            deadline = time.monotonic() + timeout
            last = None
            while time.monotonic() < deadline:
                try:
                    last = playback()
                except (json.JSONDecodeError, RuntimeError) as error:
                    if "Falha real" in str(error) or "inválido" in str(error):
                        raise
                else:
                    if predicate(last):
                        return last
                time.sleep(0.25)
            raise RuntimeError(f"Player não atingiu o estado esperado: {last}")

        # Use the packaged H.264/AAC sample, not a mock or an internet URL.
        app_shell("rm", "-f", "/data/playback-state.json")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        playing = wait_playback(lambda value: value["state"] == "playing" and value["seconds"] >= 0.5)
        shell("inputd-cli", "button_press", "KEY_ENTER")
        paused = wait_playback(lambda value: value["state"] == "paused")
        time.sleep(0.5)
        held = playback()
        if held["state"] != "paused" or abs(held["seconds"] - paused["seconds"]) > 0.25:
            raise RuntimeError(f"Pausa não manteve o relógio: {paused}, {held}")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        resumed = wait_playback(lambda value: value["state"] == "playing" and value["seconds"] > held["seconds"] + 0.3)
        shell("inputd-cli", "button_press", "KEY_BACK")
        released = wait_playback(lambda value: value["state"] == "released")
        report["checks"]["localPlayback"] = {
            "playing": playing, "paused": held, "resumed": resumed, "released": released,
        }
        # A second native player must initialize after Back released the first.
        shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        app_shell("rm", "-f", "/data/playback-state.json")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        reopened = wait_playback(lambda value: value["state"] == "playing" and value["seconds"] >= 0.25)
        ended = wait_playback(lambda value: value["state"] == "ended" and value["seconds"] >= 7.5)
        shell("inputd-cli", "button_press", "KEY_BACK")
        wait_playback(lambda value: value["state"] == "released")
        report["checks"]["playbackReopened"] = reopened
        report["checks"]["playbackEnded"] = ended
        shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        app_shell("rm", "-f", "/data/playback-state.json")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        wait_playback(lambda value: value["state"] == "playing" and value["seconds"] >= 0.25)
        shell("inputd-cli", "button_press", "KEY_HOMEPAGE")
        report["checks"]["backgroundReleased"] = wait_playback(lambda value: value["state"] == "released")
        run(["device", "launch-app", "--device", args.serial, "--appName", COMPONENT])
        shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_RIGHT")
        shell("inputd-cli", "button_press", "KEY_ENTER")
        shell("inputd-cli", "button_press", "KEY_BACK")
        shell("inputd-cli", "button_press", "KEY_LEFT")
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
        if not report.get("passed"):
            # Preserve the failure and release only this test app's resources.
            try:
                log = shell("loggingctl", "log", "-v", "com.buzincast.tv")
                (args.output / "playback-failure.log").write_text(log)
                shell("vlcm", "terminate-app", "--pkg-id", "com.buzincast.tv")
            except Exception as cleanup_error:
                report["cleanupError"] = str(cleanup_error)
        (args.output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (args.output / "commands.json").write_text(json.dumps(history, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
