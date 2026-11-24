"""Automated TV UI + native/player regression suite. Refuses physical devices.

python tools/test_emulator.py --serial emulator-5554 --apk app-debug.apk
Standard library only. Uses an isolated emulator, synthetic silent audio and
debug-only fixtures. It never discovers or connects to TVs on the LAN.
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import threading
import time
import wave
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PKG = "com.buzincast.tv"
BASE = "io.github.jqssun.airplay."
LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

# Coverage snapshots require the complete instrumented/native/extended UI run.
COVERAGE_REQUIRED_RESULTS = frozenset({
    "Android socket integration", "C++ unit concurrency and timing",
    "edition recording policy", "normal settings", "advanced/custom settings",
    "cancel and restore focus", "single relay form", "device-aware profiles",
    "about version", "shared audio controls", "native/player recovery and ownership",
    "native codecs and recording smoke", "launcher entry and resume",
    "guided calibration save/cancel and bounds", "media remote controls and rendered captions",
})


class UiUnavailable(AssertionError):
    """No trustworthy accessibility snapshot is available yet."""


def validate_instrumentation_output(output, require_all=False):
    completed=re.search(r"OK \(([1-9]\d*) tests?\)",output)
    assert completed and "FAILURES" not in output,output
    if require_all:
        statuses=[int(value) for value in re.findall(r"INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)",output)]
        count=int(completed.group(1))
        assert set(statuses)<= {0,1} and statuses.count(0)==count and statuses.count(1)==count, (
            "Coverage runs must execute every selected instrumentation test without skips or assumptions:\n"+output)


def positive_seconds(value):
    seconds = int(value)
    if seconds <= 0:
        raise argparse.ArgumentTypeError("Instrumentation timeout must be positive")
    return seconds


class Suite:
    def __init__(self, args):
        if not re.fullmatch(r"emulator-\d+", args.serial):
            raise ValueError("Only an emulator-<port> serial is allowed; physical TVs are forbidden")
        self.args = args
        self.package = args.package
        if getattr(args, "diagnostic_networks", False) and not (args.coverage and args.apk and args.test_apk):
            raise ValueError("Diagnostic network coverage requires both APKs and --coverage")
        if self.package == "com.buzincast.tv.deat" and not args.no_download:
            raise ValueError("DE/AT must be tested with --no-download")
        self.out = Path(args.output)
        self.out.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.checkpoint = 0
        if args.coverage:
            for profile in (self.out/"coverage").glob("*"):
                if re.fullmatch(r"\d+-(android\.ec|native\.profraw|native-unit\.profraw)",profile.name): profile.unlink()

    def adb(self, *args, binary=False, empty_on_silent_failure=False):
        timeout = 240 if args and args[0] == "install" else 45
        for attempt in range(3):
            result = subprocess.run([self.args.adb, "-s", self.args.serial, *map(str,args)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
            error=result.stderr.decode("utf-8","replace")
            # This failure occurs before a command reaches the emulator, so retrying
            # cannot repeat an already executed UI/media operation.
            if result.returncode and any(text in error for text in ("cannot connect to daemon", "daemon still not running")) and attempt<2:
                time.sleep(1); continue
            if result.returncode:
                if empty_on_silent_failure and not (result.stdout or result.stderr):
                    return b"" if binary else ""
                raise RuntimeError("ADB failed: "+error+("" if binary else result.stdout.decode("utf-8","replace")))
            return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def key(self, code):
        self.adb("shell", "input", "keyevent", code)

    def check_system_ui(self):
        windows = self.adb("shell", "dumpsys", "window")
        if re.search(r"^\s*mCurrentFocus=[^\r\n]*Application Not Responding: com\.android\.systemui(?:[}\s]|$)",
                     windows, re.MULTILINE):
            (self.out / "emulator-window-failure.txt").write_text(windows, encoding="utf-8")
            raise RuntimeError("Emulator System UI is not responding and is blocking the test window")

    def ui(self):
        # An empty dump during a transition must not count as proof that a
        # dialog/caption/keyboard disappeared. Never return a fabricated tree.
        for attempt in range(3):
            # Older Android images can crash the dumper on a disappearing node
            # and return no output. Retry without reading the previous XML.
            dumped=self.adb("shell", "uiautomator", "dump", "/data/local/tmp/buzin-test.xml",
                            empty_on_silent_failure=True)
            if "dumped" in dumped:
                xml=self.adb("exec-out", "cat", "/data/local/tmp/buzin-test.xml", binary=True)
                try:
                    tree=ET.fromstring(xml)
                    if len(list(tree.iter()))>1:
                        return tree
                except ET.ParseError:
                    pass
            if attempt<2:
                time.sleep(.3)
        raise UiUnavailable("Could not capture a nonempty accessibility hierarchy")

    def wait(self, predicate, message, timeout=25):
        deadline = time.monotonic() + timeout
        unavailable = None
        while time.monotonic() < deadline:
            try:
                value = predicate()
                unavailable = None
                ready = len(value) > 0 if isinstance(value, ET.Element) else bool(value)
                if ready:
                    return value
            except UiUnavailable as error:
                # Retry within the caller's startup/transition deadline, without
                # letting a missing tree satisfy an absence assertion.
                unavailable = str(error)
            time.sleep(.3)
        raise AssertionError(message + (": " + unavailable if unavailable else ""))

    def keyboard(self):
        # mIsInputViewShown in the IME's own dump can remain true while hidden.
        return bool(re.search(r"mInputShown=true\b",self.adb("shell","dumpsys","input_method")))

    def shot(self, name):
        (self.out / (name + ".png")).write_bytes(self.adb("exec-out","screencap","-p",binary=True))

    @staticmethod
    def texts(node):
        return " ".join(n.get("text","") for n in node.iter())

    def focused(self):
        tree = self.wait(self.ui, "Accessibility unavailable while reading focus", timeout=15)
        return next((self.texts(n) for n in tree.iter() if n.get("focused")=="true"), "")

    def activate(self, *labels, description=False):
        # Traverse focus order and activate with OK. A TV emulator can expose
        # accessibility bounds while ignoring injected touchscreen coordinates.
        attribute = "content-desc" if description else "text"
        def owned_labels(node):
            yield node.get(attribute)
            for child in node:
                if child.get("focusable") != "true":
                    yield from owned_labels(child)
        for _ in range(40):
            tree = self.wait(self.ui, f"Accessibility unavailable while focusing {labels}", timeout=15)
            focused = next((n for n in tree.iter() if n.get("focused")=="true"), None)
            if focused is not None and any(label in labels for label in owned_labels(focused)):
                print(f"ACTIVATE {labels} after {_} tabs",flush=True)
                self.key(23)
                return
            self.key(61)
        raise AssertionError(f"Cannot focus {labels} by remote navigation: {self.texts(self.ui())}")

    def collect_coverage(self):
        if not self.args.coverage:
            return
        self.debug("coverage")
        self.checkpoint += 1
        for filename in ("android.ec", "native.profraw"):
            target = self.out / "coverage" / f"{self.checkpoint}-{filename}"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(self.adb("exec-out", "run-as", self.package, "cat", "files/coverage/"+filename, binary=True))

    def start(self, screen):
        self.key(224)  # Wake the isolated emulator before waiting for Compose frames.
        output=self.adb("shell","am","start","-W","-S","-n",f"{self.package}/{BASE}DebugTvPreviewActivity","--es","screen",screen)
        assert "Error:" not in output,output
        self.wait(lambda: len(list(self.ui().iter()))>15, "Screen did not compose",45)

    def profile_focus(self):
        for _ in range(15):
            self.key(61)
            if "Cinema" in self.focused():
                return
        raise AssertionError("Cannot reach profile by remote navigation")

    def setting(self, key, value):
        result=self.adb("shell","am","broadcast","-n",f"{self.package}/{BASE}SettingsReceiver",
                        "-a",BASE+"SET_SETTING","--es","key",key,"--es","value",str(value))
        assert "result=-1" in result, result

    def debug(self, command="state", **extras):
        args=["shell","am","broadcast","-n",f"{self.package}/{BASE}DebugMediaReceiver","--es","command",command]
        for key,value in extras.items():
            args += ["--ez" if isinstance(value,bool) else "--es",key,str(value).lower() if isinstance(value,bool) else str(value)]
        output=self.adb(*args)
        assert "result=-1" in output,output
        match=re.search(r'data="(\{.*\})"',output)
        assert match,output
        return json.loads(match.group(1))

    def scan_settings(self, advanced):
        self.setting("developer_options",str(advanced).lower())
        self.setting("audio_auto_buffer","false")
        self.setting("resolution","1921x1081" if advanced else "auto")
        self.start("settings")
        self.profile_focus()
        for index in range(80 if advanced else 50):
            self.key(20)
            assert not self.keyboard(),f"Keyboard opened while browsing at step {index}"
        end=self.focused()
        assert ("logging" in end.lower() or "registro" in end.lower()) if advanced else ("developer" in end.lower() or "desenvolvedor" in end.lower()),end
        self.shot("settings-advanced" if advanced else "settings-normal")

    def edit_cancel(self):
        def name_has_focus():
            label=self.focused().lower()
            return "name" in label or "nome" in label
        def closed_dialog():
            tree = self.ui()
            if not any(node.get("class") == "android.widget.EditText" for node in tree.iter()):
                return tree
        for attempt in range(3):
            self.start("settings"); self.profile_focus();self.key(20)
            self.wait(name_has_focus,"Remote Down did not focus server name",10)
            self.key(23)
            self.wait(self.keyboard,f"OK did not open keyboard (attempt {attempt+1})")
            self.adb("shell","input","text","_AUTOTEST")
            self.key(4); self.wait(lambda:not self.keyboard(),"Back did not close keyboard")
            self.key(4)
            settled = self.wait(closed_dialog, "Dialog did not close")
            assert "_AUTOTEST" not in self.texts(settled), "Cancel saved the draft"
            self.wait(name_has_focus,"Focus did not return to name",10)
            self.key(20);assert not self.keyboard()

    def relay_form(self):
        self.start("tools")
        connect_labels = ("Connect to another TV", "Conectar a outra TV")
        self.wait(lambda: any(label in self.texts(self.ui()) for label in connect_labels),"Tools not available")
        self.activate(*connect_labels)
        self.wait(lambda: sum(n.get("class")=="android.widget.EditText" for n in self.ui().iter())==3,"Relay form did not open")
        # Composing the fields precedes the dialog's initial focus effect on
        # slower API 28 emulators. Send each key only after that focus lands.
        def has_focus(*labels):
            focused = self.focused().casefold()
            return any(label.casefold() in focused for label in labels)

        port_labels = ("Port", "Porta")
        code_labels = ("Connection code", "Código de conexão")
        self.wait(lambda: has_focus("IP"), "Relay form did not focus the address")
        assert not self.keyboard()
        for key,labels in [(20,port_labels),(20,code_labels),(19,port_labels),(19,("IP",))]:
            self.key(key)
            self.wait(lambda: has_focus(*labels), f"Relay focus did not move to {labels} after key {key}")
            assert not self.keyboard()
        self.key(23);self.wait(self.keyboard,"OK did not enable form input")
        self.key(4);self.wait(lambda:not self.keyboard(),"Back did not hide IME")
        self.key(20)
        self.wait(lambda: has_focus(*port_labels), "Relay focus did not return to port after closing the keyboard")
        assert not self.keyboard()
        self.shot("relay-one-form")

    def saved_profile(self):
        xml = self.adb("shell", "run-as", self.package, "cat", "shared_prefs/settings.xml")
        try:
            settings = ET.fromstring(xml)
        except ET.ParseError as error:
            # SharedPreferences rewrites the file while a profile changes. An
            # incomplete read cannot prove either the old or the new selection.
            raise UiUnavailable("Profile preferences are not readable yet") from error
        if settings.tag != "map":
            raise UiUnavailable("Profile preferences have an unexpected root")
        return next((node.text for node in settings if node.get("name") == "experience_profile"), "Padrão")

    def profiles(self):
        labels = {
            "Cinema": ("Cinema",),
            "Apresentação": ("Presentation", "Apresentação"),
            "Jogos": ("Games", "Jogos"),
            "Padrão": ("Default", "Padrão"),
        }
        profile_by_label = {label: profile for profile, options in labels.items() for label in options}
        def selected():
            return {profile_by_label[c.get("text")] for n in self.ui().iter()
                    if n.get("selected")=="true" or n.get("checked")=="true"
                    for c in n.iter() if c.get("text") in profile_by_label}
        saved = self.saved_profile
        self.start("settings")
        self.wait(lambda: selected()=={saved()}, "Settings did not show the saved or default profile")
        for name, options in labels.items():
            self.activate(*options)
            self.wait(lambda: saved()==name,"Profile did not apply: "+name)
            self.wait(lambda: selected()=={name},"Profile selection was not highlighted: "+name)
            self.start("settings")
            # The activity can expose the settings hierarchy before the persisted
            # preference Flow has emitted. Require the saved selection to render,
            # rather than sampling the initial default composition once.
            self.wait(lambda: selected()=={name}, "Profile selection was lost after relaunch: "+name)

    def about_version(self):
        package=self.adb("shell","dumpsys","package",self.package)
        version=re.search(r"\bversionName=(\S+)",package).group(1)
        self.start("home")
        self.activate("About BuzinCast","Sobre o BuzinCast")
        def installed_version_view():
            view = self.ui()
            if any(n.get("text") in ("Version "+version,"Versão "+version) for n in view.iter()):
                return view
        view = self.wait(installed_version_view, "About does not show installed version "+version)
        text = self.texts(view)
        assert "Buzin Solutions" in text, "About is missing company attribution"
        assert "buzin.solutions" in text, "About is missing the official website"
        assert any(n.get("text") in ("Close", "Fechar") for n in view.iter()), "About must keep Close visible without scrolling"
        self.shot("about-company")
        self.activate("Open source and licenses","Código aberto e licenças")
        def license_details_visible():
            text = self.texts(self.ui())
            return "GNU GPL v3" in text and "jqssun" in text
        self.wait(license_details_visible, "Third-party credits and license are missing")
        self.shot("about-licenses")
        self.activate("Back to About","Voltar ao Sobre")
        self.wait(lambda:"buzin.solutions" in self.texts(self.ui()), "Cannot return from licenses to About")
        self.activate("Close","Fechar")
        self.wait(lambda:"buzin.solutions" not in self.texts(self.ui()), "Close did not dismiss About")
        self.activate("About BuzinCast","Sobre o BuzinCast")
        self.wait(lambda:"buzin.solutions" in self.texts(self.ui()), "About did not reopen")
        self.key(4)
        self.wait(lambda:"buzin.solutions" not in self.texts(self.ui()), "Back did not dismiss About")

    def audio_controls(self):
        self.start("audio")
        play_pause = ("Play/Pause", "Reproduzir ou pausar")
        for labels,command in ((play_pause,"play"),(play_pause,"pause"),(("Next","Próxima"),"next"),(("Previous","Anterior"),"previous")):
            self.activate(*labels,description=True)
            self.wait(lambda: "Comando: "+command in self.texts(self.ui()),"Audio command did not reach the player: "+command)
        assert "00:30" in self.texts(self.ui())
        self.shot("shared-audio-controls")

    def launcher_entry(self):
        self.setting("auto_start","true")
        component=f"{self.package}/{BASE}MainActivity"
        self.adb("shell","am","start","-S","-n",component)
        about_labels = ("About BuzinCast", "Sobre o BuzinCast")
        home_labels = (*about_labels, "Ready to connect", "Pronto para conectar")

        def has_home_controls(tree):
            return any(n.get("text") in home_labels for n in tree.iter())

        def home_ready():
            # On a short viewport the focusable About button starts below the
            # fold. The receiver status identifies Home before activating it.
            return has_home_controls(self.ui())
        self.wait(home_ready,"Launcher did not present the home controls",45)
        self.wait(lambda:self.debug().get("serverState")=="RUNNING","Launcher did not auto-start receiver")
        assert not self.keyboard(),"Launcher unexpectedly opened the keyboard"
        # Exercise real activity stop/start and service rebinding, not a preview.
        self.key(3)
        self.adb("shell","am","start","-n",component)
        self.wait(home_ready,"Launcher did not resume its home controls",45)
        assert self.debug().get("serverState")=="RUNNING"
        self.shot("launcher-entry-resume")
        self.activate(*about_labels)
        self.wait(lambda:"buzin.solutions" in self.texts(self.ui()),"Launcher home controls are not interactive")
        self.activate("Close","Fechar")

        def about_closed():
            tree = self.ui()
            return has_home_controls(tree) and "buzin.solutions" not in self.texts(tree)

        self.wait(about_closed,"Launcher About did not close")

    def local_network_service_permission(self):
        permission = LOCAL_NETWORK_PERMISSION

        def check_permission(granted):
            state = self.adb("shell", "dumpsys", "package", self.package)
            assert f"{permission}: granted={str(granted).lower()}" in state, (
                f"Android did not report local-network permission as granted={granted}")

        def grant():
            self.adb("shell", "pm", "grant", self.package, permission)

        self.adb("shell", "am", "force-stop", self.package)
        try:
            self.adb("shell", "pm", "revoke", self.package, permission)
            check_permission(False)
            component = f"{self.package}/{BASE}DebugTvPlaybackActivity"
            launch = self.adb("shell", "am", "start", "-W", "-n", component)
            assert "Status: ok" in launch, launch
            self.wait(lambda: self.debug()["serverState"] == "STOPPED", "Receiver did not bind")
            self.debug("startserver")
            self.wait(lambda: self.debug()["serverState"] == "ERROR", "Receiver started without local-network permission")
            self.collect_coverage()

            grant()
            check_permission(True)
            self.debug("startserver")
            self.wait(lambda: self.debug()["serverState"] == "RUNNING", "Receiver did not recover after the permission grant")
        finally:
            grant()

    def local_network_permission_dialog(self):
        permission = LOCAL_NETWORK_PERMISSION

        def tap_when_visible(matches, message):
            def find():
                return next(((node,) for node in self.ui().iter() if matches(node)), None)

            node, = self.wait(find, message, 45)
            left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
            assert right > left and bottom > top, node.get("bounds")
            self.adb("shell", "input", "tap", (right + left) // 2, (bottom + top) // 2)

        def tap_permission_button(button):
            tap_when_visible(lambda node: node.get("resource-id", "").endswith(f":id/{button}"),
                             f"Android did not display {button}")

        self.setting("auto_start", "true")
        self.adb("shell", "am", "force-stop", self.package)
        try:
            self.adb("shell", "pm", "revoke", self.package, permission)
            self.adb("shell", "pm", "clear-permission-flags", self.package, permission, "user-set", "user-fixed")
            component = f"{self.package}/{BASE}MainActivity"
            self.adb("shell", "am", "start", "-W", "-n", component)
            tap_permission_button("permission_deny_button")
            self.wait(lambda: self.debug()["serverState"] == "STOPPED", "Receiver started before permission was granted")
            self.collect_coverage()

            tap_when_visible(lambda node: node.get("package") == self.package and
                             node.get("text") in ("Allow", "Permitir"),
                             "The app did not offer to request permission again")
            tap_permission_button("permission_allow_button")
            self.wait(lambda: self.debug()["serverState"] == "RUNNING",
                      "Receiver did not auto-start after permission was granted")
        finally:
            self.adb("shell", "pm", "grant", self.package, permission)

    def calibration(self):
        self.start("tools")
        original=self.debug()["margin"]
        def open_preview():
            self.activate("Calibrate picture", "Calibrar imagem", "Bild kalibrieren")
            self.wait(lambda: re.search(r"mCurrentFocus=.*CalibrationActivity",self.adb("shell","dumpsys","window")),"Calibration did not open")
        def saved(value):
            self.wait(lambda:self.debug().get("margin")==value,"Calibration did not save margin: "+str(value))
        try:
            self.setting("tv_margin","2")
            open_preview(); self.key(22); self.key(22); self.key(23); saved(4)
            open_preview(); self.key(21); self.key(4); saved(4)
            open_preview()
            for _ in range(15): self.key(21)
            self.key(23); saved(0)
            open_preview()
            for _ in range(15): self.key(22)
            self.shot("calibration-upper-bound")
            self.key(23); saved(10)
        finally:
            self.setting("tv_margin",original)

    def media_controls(self):
        # Silent PCM keeps this test deterministic without external media,
        # downloads or a dependency on video hardware in the host emulator.
        self.adb("shell","am","start","-S","-n",f"{self.package}/{BASE}DebugTvPlaybackActivity")
        buffer=io.BytesIO()
        with wave.open(buffer,"wb") as wav:
            wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(8000);wav.writeframes(b"\0\0"*8000*120)
        body=buffer.getvalue()
        class Media(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200);self.send_header("Content-Type","audio/wav")
                self.send_header("Content-Length",str(len(body)));self.end_headers()
                try:self.wfile.write(body)
                except (BrokenPipeError,ConnectionResetError):pass
            def log_message(self,*_):pass
        server=ThreadingHTTPServer(("0.0.0.0",0),Media)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            self.debug("startserver")
            self.debug("playurl",url=f"http://10.0.2.2:{server.server_port}/controls.wav")
            self.wait(lambda:self.debug().get("positionMs",0)>1000,"Media did not start")
            self.key(127)  # MEDIA_PAUSE: remote keys must reach the actual player.
            self.wait(lambda:not self.debug().get("playing"),"Remote pause did not apply")
            before=self.debug()["positionMs"]
            self.key(90)
            self.wait(lambda:self.debug().get("positionMs",0)>before+8000,"Remote forward did not seek")
            after=self.debug()["positionMs"]
            self.key(89)
            self.wait(lambda:self.debug().get("positionMs",0)<after-8000,"Remote rewind did not seek")
            for _ in range(40):
                tree = self.wait(self.ui, "Accessibility unavailable while focusing the playback slider")
                if any(n.get("class") == "android.widget.SeekBar" and n.get("focused") == "true" for n in tree.iter()):
                    break
                self.key(61)
            else:
                raise AssertionError("The playback slider did not receive remote focus")
            before = self.debug()["positionMs"]
            self.key(22)
            self.wait(lambda: self.debug()["positionMs"] > before + 8000, "Right did not seek on the focused slider")
            after = self.debug()["positionMs"]
            self.key(21)
            self.wait(lambda: self.debug()["positionMs"] < after - 8000, "Left did not seek on the focused slider")
            assert not self.debug()["playing"], "Seeking with the slider resumed paused media"
            self.activate("Playback speed","Velocidade de reprodução",description=True)
            self.activate("2.0")
            self.wait(lambda:sum(n.get("text")=="2.0" for n in self.ui().iter())>=2,"Speed did not update")
            assert not self.debug()["playing"],"Changing speed resumed paused media"
            assert not self.keyboard(),"Unexpected IME before dismissing playback speed selector"
            self.key(4)
            self.wait(lambda: not any(n.get("text") in ("Playback speed","Velocidade de reprodução") for n in self.ui().iter()),
                      "Back did not dismiss the playback speed selector")
            assert self.debug()["activeVideo"],"Closing speed menu stopped playback"
            for kind in ("srt","vtt"):
                caption="Legenda "+kind.upper()+" Buzin"
                self.debug("subtitlefixture",kind=kind)
                self.wait(lambda:caption in self.debug().get("caption",""),"Subtitle decoder did not emit "+kind)
                self.wait(lambda:caption in self.texts(self.ui()),"Subtitle was not rendered in the player")
                self.shot("rendered-caption-"+kind)
            self.key(127)
            self.activate("CC")
            self.activate("No subtitles", "Sem legenda")
            self.wait(lambda:not self.debug().get("caption"),"Removing captions left stale text")
            self.key(127)
            scales = [
                ("Best fit", "Ajustar à tela", "Optimale Anpassung"),
                ("Stretch", "Esticar", "Strecken"),
                ("Crop", "Preencher com corte", "Zuschneiden"),
                ("100%", "100 %"),
            ]
            for current, following in zip(scales, scales[1:] + scales[:1]):
                self.activate(*current, description=True)
                self.wait(lambda: any(n.get("content-desc") in following for n in self.ui().iter()),
                          f"Content scale did not change to {following[0]}")
            assert not self.debug()["playing"], "Changing content scale resumed paused media"
            self.activate("Lock controls","Bloquear controles",description=True)
            self.activate("Unlock controls","Desbloquear controles",description=True)
            self.key(126)
            self.wait(lambda:self.debug().get("playing"),"Remote play did not resume")
            self.key(86)
            self.wait(lambda:not self.debug().get("activeVideo"),"Remote stop did not release media")
        finally:
            server.shutdown();server.server_close()

    def media_recovery(self):
        self.start("home")  # Android requires a foreground app to start the receiver service.
        buffer=io.BytesIO()
        with wave.open(buffer,"wb") as wav:
            wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(8000);wav.writeframes(b"\0\0"*8000*45)
        body=buffer.getvalue(); ready=threading.Event()
        class Media(BaseHTTPRequestHandler):
            def do_GET(self):
                if not ready.is_set():
                    self.connection.close();return
                self.send_response(200);self.send_header("Content-Type","audio/wav")
                self.send_header("Content-Length",str(len(body)));self.end_headers()
                try:self.wfile.write(body)
                except (BrokenPipeError,ConnectionResetError):pass
            def log_message(self,*_):pass
        server=ThreadingHTTPServer(("0.0.0.0",0),Media)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            url=f"http://10.0.2.2:{server.server_port}/silent.wav"
            self.debug("startserver")
            self.wait(lambda:self.debug().get("serverState")=="RUNNING","Native AirPlay service did not start")
            self.debug("playurl",url=url)
            self.wait(lambda:self.debug().get("sessionPhase")=="RECOVERING","Network failure did not enter recovery")
            ready.set()
            self.wait(lambda:self.debug().get("positionMs",0)>1000,"Playback did not recover",timeout=30)
            first=self.debug()["sessionGeneration"]
            self.debug("playurl",url=url)
            self.wait(lambda:self.debug().get("sessionGeneration",0)>first,"Replacement did not get a new token")
            self.debug("native-stop");self.debug("native-disconnect")
            assert self.debug().get("activeVideo"),"Late AirPlay event stopped local media"
            self.debug("stopvideo")
            self.wait(lambda:not self.debug().get("activeVideo"),"Stop did not release media")
            self.debug("airplayurl",url=url)
            self.wait(lambda:self.debug().get("sessionProtocol")=="AIRPLAY","AirPlay URL did not claim session")
            self.debug("native-stop")
            self.wait(lambda:not self.debug().get("activeVideo"),"AirPlay stop did not end its own session")
        finally:
            server.shutdown();server.server_close()

    def native_capture(self):
        self.adb("shell","am","start","-S","-n",f"{self.package}/{BASE}DebugTvPlaybackActivity")
        time.sleep(2)
        self.debug("startserver")
        self.wait(lambda:self.debug().get("serverState")=="RUNNING","Native receiver failed smoke startup")
        before=self.debug()
        previous_files={item["name"] for item in before["recordings"]}
        assert before.get("recordingEnabled") == (not self.args.no_download),before
        assert before.get("downloadEnabled") == (not self.args.no_download),before
        self.debug("record")
        if self.args.no_download:
            policy=self.debug()
            assert not policy.get("captureArmed") and not policy.get("recording"),policy
        state=self.debug("nativefixture")
        assert state.get("sessionProtocol")=="AIRPLAY",state
        assert state.get("resolution")=="64x48",state
        if not self.args.no_download:
            self.wait(lambda:self.debug().get("recording"),"Synthetic mirror was not recorded")
        self.debug("stop")
        if not self.args.no_download:
            self.wait(lambda:not self.debug().get("captureArmed"),"Recording did not finalize")
        state=self.debug()
        if self.args.no_download:
            assert not state.get("captureArmed") and not state.get("recording"),state
            assert state["recordings"] == before["recordings"],state
        else:
            assert not state.get("recording"),state
            assert any(file["name"] not in previous_files and file["bytes"]>100 for file in state["recordings"]),state
        state=self.debug("nativefixture",hevc=True)
        assert state.get("resolution")=="64x48",state
        self.debug("native-disconnect")
        self.debug("mirror-start")
        tree=self.wait(self.ui,"Fullscreen did not expose a complete accessibility hierarchy",45)
        descriptions=[node.get("content-desc","").lower() for node in tree.iter()]
        assert not any("picture-in-picture" in text or "sair da tela cheia" in text or "exit fullscreen" in text for text in descriptions),descriptions
        self.shot("native-recording-smoke")
        assert "Ferramentas" not in self.texts(tree) and "Tools" not in self.texts(tree),self.texts(tree)
        self.key(4)  # Remote Back remains available when overlay icons are absent.
        self.wait(lambda:any(label in self.texts(self.ui()) for label in ("Ferramentas","Tools")),"Remote Back did not leave fullscreen")
        self.debug("native-disconnect")

    def edition_policy(self):
        self.start("tools")
        text=self.texts(self.ui())
        recording=any(label in text for label in ("Record mirroring","Gravar espelhamento","Prepare recording","Preparar gravação"))
        assert recording == (not self.args.no_download),"Recording UI does not match the edition"
        self.shot("edition-policy")

    def native_unit_tests(self):
        binary=str(Path(self.args.native_tests).resolve())
        if self.args.adb.lower().endswith(".exe") and os.name!="nt":
            binary=subprocess.check_output(["wslpath","-w",binary],text=True).strip()
        remote="/data/local/tmp/buzin-cast-tests"
        self.adb("shell","mkdir","-p",remote)
        self.adb("push",binary,remote+"/native_core_tests")
        self.adb("shell","chmod","755",remote+"/native_core_tests")
        self.adb("shell","rm","-f",remote+"/native-unit.profraw")
        output=self.adb("shell","LLVM_PROFILE_FILE="+remote+"/native-unit.profraw",remote+"/native_core_tests")
        (self.out/"native-unit.txt").write_text(output,encoding="utf-8")
        result = re.search(r"^Native tests: ([1-9]\d*), failures: 0$", output.replace("\r", ""), re.MULTILINE)
        assert result, output
        cases = int(result.group(1))
        directory=self.out/"coverage";directory.mkdir(parents=True,exist_ok=True)
        (directory/"0-native-unit.profraw").write_bytes(self.adb("exec-out","cat",remote+"/native-unit.profraw",binary=True))
        shutil.copyfile(self.args.native_tests,self.out/"native_core_tests")
        self.results.append({"test":"C++ unit concurrency and timing", "passed":True,"cases":cases})
        print(f"PASS C++ unit tests ({cases} cases, UBSan)", flush=True)

    def run_instrumentation(self, transcript, test_class=None, network=None):
        if self.args.coverage:
            self.adb("shell", "run-as", self.package, "rm", "-f", "files/coverage/android.ec", "files/coverage/native.profraw")
        # adb command timeout is intentionally higher for the complete test runner.
        command=[self.args.adb,"-s",self.args.serial,"shell","am","instrument","-w", "-r"]
        if test_class:
            # adb shell parses the remote command; quote a possible Class#method selector.
            import shlex
            command.extend(["-e", "class", shlex.quote(test_class)])
        if network:
            command.extend(["-e", "networkDiagnosticState", network])
        command.append(self.package+".test/androidx.test.runner.AndroidJUnitRunner")
        # Stream progress so a timeout/nonzero exit preserves the last started
        # test without turning partial execution into passing evidence.
        timeout = getattr(self.args, "instrumentation_timeout", 900)
        if timeout <= 0:
            raise ValueError("Instrumentation timeout must be positive")
        with transcript.open("wb") as progress:
            subprocess.run(command, stdout=progress, stderr=subprocess.STDOUT, timeout=timeout, check=True)
        output=transcript.read_text(encoding="utf-8",errors="replace")
        validate_instrumentation_output(output,require_all=self.args.coverage)
        if self.args.coverage:
            self.instrumentation_checkpoint()
        return output

    def instrumentation_checkpoint(self):
        self.checkpoint += 1
        for filename in ("android.ec", "native.profraw"):
            target=self.out/"coverage"/f"{self.checkpoint}-{filename}"
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(self.adb("exec-out","run-as",self.package,"cat","files/coverage/"+filename,binary=True))

    def instrumentation(self):
        apk=str(Path(self.args.test_apk).resolve())
        if self.args.adb.lower().endswith(".exe") and os.name!="nt":
            apk=subprocess.check_output(["wslpath","-w",apk],text=True).strip()
        self.adb("install", "--no-streaming", "-r", apk)
        output = self.run_instrumentation(self.out/"instrumentation.txt", self.args.test_class)
        result = {"test":"Android socket integration", "passed":True, "runner":output}
        if self.args.test_class:
            result["test_class"] = self.args.test_class
        self.results.append(result)

    def diagnostic_networks(self):
        selection = BASE + "NetworkDiagnosticsContractTest#publicReportMatchesTheDefaultAndroidNetwork"
        states = {"wifi": "wifi_on", "data": "mobile_data"}
        initial = {radio: self.adb("shell", "settings", "get", "global", key).strip() for radio, key in states.items()}
        assert set(initial.values()) <= {"0", "1"}, f"Unrecognized emulator radio state: {initial}"
        assert "Active default network: none" not in self.adb("shell", "dumpsys", "connectivity"), "The test needs an online emulator"
        operations = self.adb("shell", "cmd", "appops", "get", self.package, "ACTIVATE_VPN")
        mode = re.search(r"^ACTIVATE_VPN: (\w+)", operations, re.MULTILINE)
        vpn_mode = mode.group(1) if mode else "default"
        diagnostic = {}
        self.adb("shell", "am", "force-stop", self.package)
        try:
            self.adb("shell", "cmd", "appops", "set", self.package, "ACTIVATE_VPN", "allow")
            diagnostic["vpn"] = self.run_instrumentation(self.out/"diagnostic-vpn.txt", selection, "vpn")
            self.adb("shell", "am", "force-stop", self.package)
            try:
                for radio in states:
                    self.adb("shell", "svc", radio, "disable")
                self.wait(lambda: "Active default network: none" in self.adb("shell", "dumpsys", "connectivity"),
                          "Test emulator still has a default network")
                diagnostic["offline"] = self.run_instrumentation(self.out/"diagnostic-offline.txt", selection, "offline")
            finally:
                for radio, enabled in initial.items():
                    self.adb("shell", "svc", radio, "enable" if enabled == "1" else "disable")
        finally:
            self.adb("shell", "am", "force-stop", self.package)
            self.adb("shell", "cmd", "appops", "set", self.package, "ACTIVATE_VPN", vpn_mode)
        self.wait(lambda: "Active default network: none" not in self.adb("shell", "dumpsys", "connectivity"),
                  "Test emulator default network did not recover")
        assert all("OK (1 test)" in transcript for transcript in diagnostic.values()), diagnostic
        entry = next(result for result in self.results if result["test"] == "Android socket integration")
        entry["diagnostic_networks"] = diagnostic
        print("PASS Android VPN and offline diagnostics", flush=True)

    def run(self):
        self.adb("wait-for-device")
        self.wait(lambda:self.adb("shell","getprop","sys.boot_completed").strip()=="1","Emulator did not boot",120)
        if self.args.apk:
            apk=str(Path(self.args.apk).resolve())
            if self.args.adb.lower().endswith(".exe") and os.name!="nt":
                apk=subprocess.check_output(["wslpath","-w",apk],text=True).strip()
            self.adb("install","--no-streaming","-r",apk)
        api=int(self.adb("shell","getprop","ro.build.version.sdk").strip())
        if api>=33: self.adb("shell","pm","grant",self.package,"android.permission.POST_NOTIFICATIONS")
        if api>=37: self.adb("shell","pm","grant",self.package,LOCAL_NETWORK_PERMISSION)
        # First-run immersive hints obscure the fixture on API 35+; these are
        # emulator-only setup choices, not app permissions or skipped assertions.
        self.adb("shell","settings","put","secure","immersive_mode_confirmations","confirmed")
        self.adb("shell","svc","power","stayon","true")
        self.key(224)
        # Emulator hosts can report a hardware keyboard after key injection.
        # Keep software IME available so edit/Back assertions are deterministic.
        self.adb("shell","settings","put","secure","show_ime_with_hard_keyboard","1")
        self.setting("approve_connections","false"); self.setting("require_pin","false"); self.setting("launch_on_connect","false")
        self.adb("shell","wm","size","1920x1080");self.adb("shell","wm","density","320")
        try:
            if self.args.native_tests:
                self.native_unit_tests()
            self.check_system_ui()
            if self.args.test_apk:
                self.instrumentation()
            cases=[("edition recording policy",self.edition_policy),("normal settings",lambda:self.scan_settings(False)),("advanced/custom settings",lambda:self.scan_settings(True)),
                   ("cancel and restore focus",self.edit_cancel),("single relay form",self.relay_form),
                   ("device-aware profiles",self.profiles),("about version",self.about_version),("shared audio controls",self.audio_controls),("native/player recovery and ownership",self.media_recovery),("native codecs and recording smoke",self.native_capture),("launcher entry and resume",self.launcher_entry),("guided calibration save/cancel and bounds",self.calibration)]
            extended=("media remote controls and rendered captions",self.media_controls)
            if self.args.extended or (self.args.case and any(pattern in extended[0] for pattern in self.args.case)):
                cases.append(extended)
            if api >= 37:
                cases.append(("local network permission and receiver recovery", self.local_network_service_permission))
                cases.append(("local network permission dialog retry", self.local_network_permission_dialog))
            selected=[(name,test) for name,test in cases if not self.args.case or any(pattern in name for pattern in self.args.case)]
            assert selected,"No test matches --case"
            for name,test in selected:
                start=time.monotonic()
                try:test()
                except Exception as error:
                    self.shot("failure");self.results.append({"test":name,"passed":False,"error":str(error)});raise
                self.results.append({"test":name,"passed":True,"seconds":round(time.monotonic()-start,2)})
                self.collect_coverage()
                print("PASS "+name,flush=True)
            if getattr(self.args, "diagnostic_networks", False):
                self.diagnostic_networks()
        finally:
            (self.out/"results.json").write_text(json.dumps(self.results,indent=2,ensure_ascii=False),encoding="utf-8")
            (self.out/"logcat.txt").write_text(self.adb("logcat","-d","-t","1500"),encoding="utf-8")
            self.adb("shell","am","force-stop",self.package)
            self.adb("shell","wm","size","reset");self.adb("shell","wm","density","reset")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial",required=True)
    parser.add_argument("--adb",default="adb")
    parser.add_argument("--apk")
    parser.add_argument("--package", choices=["com.buzincast.tv","com.buzincast.tv.deat"], default=PKG)
    parser.add_argument("--no-download", action="store_true", help="Assert recording/download policy is disabled")
    parser.add_argument("--native-tests", help="Standalone native C++ unit executable built for this emulator ABI")
    parser.add_argument("--test-apk", help="Run Android instrumentation integration suite first")
    parser.add_argument("--test-class", help="Select an AndroidJUnitRunner class or Class#method with --test-apk")
    parser.add_argument("--instrumentation-timeout",type=positive_seconds,default=900,
                        help="Maximum seconds for the complete Android test suite (default: 900); partial progress is retained on failure")
    parser.add_argument("--coverage", action="store_true", help="Require Android and native coverage checkpoints after every scenario")
    parser.add_argument("--diagnostic-networks", action="store_true", help="Add VPN and offline coverage on a disposable emulator")
    parser.add_argument("--output",default="build/emulator-checks")
    parser.add_argument("--case",action="append",help="Run matching case descriptions; repeat to select multiple cases")
    parser.add_argument("--extended",action="store_true",help="Include additional scenarios still under validation; see docs/WORK_CHECKPOINT.md")
    Suite(parser.parse_args()).run()
