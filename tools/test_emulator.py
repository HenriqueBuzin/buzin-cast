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


class Suite:
    def __init__(self, args):
        if not re.fullmatch(r"emulator-\d+", args.serial):
            raise ValueError("Only an emulator-<port> serial is allowed; physical TVs are forbidden")
        self.args = args
        self.out = Path(args.output)
        self.out.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.checkpoint = 0
        if args.coverage:
            for profile in (self.out/"coverage").glob("*"):
                if re.fullmatch(r"\d+-(android\.ec|native\.profraw|native-unit\.profraw)",profile.name): profile.unlink()

    def adb(self, *args, binary=False):
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
                raise RuntimeError("ADB failed: "+error+("" if binary else result.stdout.decode("utf-8","replace")))
            return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def key(self, code):
        self.adb("shell", "input", "keyevent", code)

    def ui(self):
        dumped=self.adb("shell", "uiautomator", "dump", "/data/local/tmp/buzin-test.xml")
        if "dumped" not in dumped:
            return ET.Element("hierarchy")
        xml=self.adb("shell", "cat", "/data/local/tmp/buzin-test.xml")
        if not xml.lstrip().startswith("<"):
            return ET.Element("hierarchy")
        return ET.fromstring(xml)

    def wait(self, predicate, message, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(.3)
        raise AssertionError(message)

    def keyboard(self):
        # mIsInputViewShown in the IME's own dump can remain true while hidden.
        return bool(re.search(r"mInputShown=true\b",self.adb("shell","dumpsys","input_method")))

    def shot(self, name):
        (self.out / (name + ".png")).write_bytes(self.adb("exec-out","screencap","-p",binary=True))

    @staticmethod
    def texts(node):
        return " ".join(n.get("text","") for n in node.iter())

    def focused(self):
        tree = self.ui()
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
            tree = self.ui()
            focused = next((n for n in tree.iter() if n.get("focused")=="true"), None)
            if focused is not None and any(label in labels for label in owned_labels(focused)):
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
            target.write_bytes(self.adb("exec-out", "run-as", PKG, "cat", "files/coverage/"+filename, binary=True))

    def start(self, screen):
        self.key(224)  # Wake the isolated emulator before waiting for Compose frames.
        output=self.adb("shell","am","start","-W","-S","-n",f"{PKG}/{BASE}DebugTvPreviewActivity","--es","screen",screen)
        assert "Error:" not in output,output
        self.wait(lambda: len(list(self.ui().iter()))>15, "Screen did not compose",45)

    def profile_focus(self):
        for _ in range(15):
            self.key(61)
            if "Cinema" in self.focused():
                return
        raise AssertionError("Cannot reach profile by remote navigation")

    def setting(self, key, value):
        result=self.adb("shell","am","broadcast","-n",f"{PKG}/{BASE}SettingsReceiver",
                        "-a",BASE+"SET_SETTING","--es","key",key,"--es","value",str(value))
        assert "result=-1" in result, result

    def debug(self, command="state", **extras):
        args=["shell","am","broadcast","-n",f"{PKG}/{BASE}DebugMediaReceiver","--es","command",command]
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
        for attempt in range(3):
            self.start("settings"); self.profile_focus();self.key(20)
            initial_focus=self.focused()
            assert ("name" in initial_focus.lower() or "nome" in initial_focus.lower()),initial_focus
            self.key(23)
            self.wait(self.keyboard,f"OK did not open keyboard (attempt {attempt+1})")
            self.adb("shell","input","text","_AUTOTEST")
            self.key(4); self.wait(lambda:not self.keyboard(),"Back did not close keyboard")
            self.key(4)
            self.wait(lambda: not any(n.get("class")=="android.widget.EditText" for n in self.ui().iter()),"Dialog did not close")
            assert "_AUTOTEST" not in self.texts(self.ui()),"Cancel saved the draft"
            assert ("name" in self.focused().lower() or "nome" in self.focused().lower()),"Focus did not return to name"
            self.key(20);assert not self.keyboard()

    def relay_form(self):
        self.start("tools")
        self.wait(lambda: "Conectar a outra TV" in self.texts(self.ui()),"Tools not available")
        self.activate("Conectar a outra TV")
        self.wait(lambda: sum(n.get("class")=="android.widget.EditText" for n in self.ui().iter())==3,"Relay form did not open")
        # Composing the fields precedes the dialog's initial focus effect on
        # slower API 28 emulators. Send each key only after that focus lands.
        self.wait(lambda: "IP" in self.focused(), "Relay form did not focus the address")
        assert not self.keyboard()
        for key,label in [(20,"Porta"),(20,"Código"),(19,"Porta"),(19,"IP")]:
            self.key(key)
            self.wait(lambda: label in self.focused(), f"Relay focus did not move to {label} after key {key}")
            assert not self.keyboard()
        self.key(23);self.wait(self.keyboard,"OK did not enable form input")
        self.key(4);self.wait(lambda:not self.keyboard(),"Back did not hide IME")
        self.key(20)
        self.wait(lambda: "Porta" in self.focused(), "Relay focus did not return to port after closing the keyboard")
        assert not self.keyboard()
        self.shot("relay-one-form")

    def profiles(self):
        names = ["Cinema", "Apresentação", "Jogos", "Padrão"]
        def selected():
            return {c.get("text") for n in self.ui().iter()
                    if n.get("selected")=="true" or n.get("checked")=="true"
                    for c in n.iter() if c.get("text") in names}
        def saved():
            xml=self.adb("shell","run-as",PKG,"cat","shared_prefs/settings.xml")
            return next((n.text for n in ET.fromstring(xml) if n.get("name")=="experience_profile"), "Padrão")
        self.start("settings")
        assert selected()=={saved()}, "Settings did not show the saved or default profile"
        for name in names:
            self.activate(name)
            self.wait(lambda: saved()==name,"Profile did not apply: "+name)
            self.wait(lambda: selected()=={name},"Profile selection was not highlighted: "+name)
            self.start("settings")
            assert selected()=={name}, "Profile selection was lost after relaunch: "+name

    def about_version(self):
        package=self.adb("shell","dumpsys","package",PKG)
        version=re.search(r"\bversionName=(\S+)",package).group(1)
        self.start("home")
        self.activate("About BuzinCast","Sobre o BuzinCast")
        self.wait(lambda:any(n.get("text") in ("Version "+version,"Versão "+version) for n in self.ui().iter()),
                  "About does not show installed version "+version)
        assert "Buzin Solutions" in self.texts(self.ui()), "About is missing company attribution"
        assert "buzin.solutions" in self.texts(self.ui()), "About is missing the official website"
        assert any(n.get("text") in ("Close", "Fechar") for n in self.ui().iter()), "About must keep Close visible without scrolling"
        self.shot("about-company")
        self.activate("Open source and licenses","Código aberto e licenças")
        self.wait(lambda:"GNU GPL v3" in self.texts(self.ui()) and "jqssun" in self.texts(self.ui()),
                  "Third-party credits and license are missing")
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
        for label,command in (("Reproduzir","play"),("Pausar","pause"),("Próxima","next"),("Anterior","previous")):
            self.activate(label,description=True)
            self.wait(lambda: "Comando: "+command in self.texts(self.ui()),"Audio command did not reach the player: "+command)
        assert "00:30" in self.texts(self.ui())
        self.shot("shared-audio-controls")

    def launcher_entry(self):
        self.setting("auto_start","true")
        component=f"{PKG}/{BASE}MainActivity"
        self.adb("shell","am","start","-S","-n",component)
        self.wait(lambda: len(list(self.ui().iter()))>15,"Launcher activity did not compose")
        self.wait(lambda:self.debug().get("serverState")=="RUNNING","Launcher did not auto-start receiver")
        assert not self.keyboard(),"Launcher unexpectedly opened the keyboard"
        # Exercise real activity stop/start and service rebinding, not a preview.
        self.key(3)
        self.adb("shell","am","start","-n",component)
        self.wait(lambda: len(list(self.ui().iter()))>15,"Launcher did not resume")
        assert self.debug().get("serverState")=="RUNNING"
        self.shot("launcher-entry-resume")

    def calibration(self):
        self.start("tools")
        original=self.debug()["margin"]
        def open_preview():
            self.debug("calibrate")
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
        self.adb("shell","am","start","-S","-n",f"{PKG}/{BASE}DebugTvPlaybackActivity")
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
            self.activate("Playback speed","Velocidade de reprodução",description=True)
            self.activate("2.0")
            self.wait(lambda:sum(n.get("text")=="2.0" for n in self.ui().iter())>=2,"Speed did not update")
            assert not self.debug()["playing"],"Changing speed resumed paused media"
            self.key(4)
            assert self.debug()["activeVideo"],"Closing speed menu stopped playback"
            for kind in ("srt","vtt"):
                caption="Legenda "+kind.upper()+" Buzin"
                self.debug("subtitlefixture",kind=kind)
                self.wait(lambda:caption in self.debug().get("caption",""),"Subtitle decoder did not emit "+kind)
                self.wait(lambda:caption in self.texts(self.ui()),"Subtitle was not rendered in the player")
                self.shot("rendered-caption-"+kind)
            self.key(127)
            self.activate("CC")
            self.activate("Sem legenda")
            self.wait(lambda:not self.debug().get("caption"),"Removing captions left stale text")
            self.key(127)
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
        self.adb("shell","am","start","-S","-n",f"{PKG}/{BASE}DebugTvPlaybackActivity")
        time.sleep(2)
        self.debug("startserver")
        self.wait(lambda:self.debug().get("serverState")=="RUNNING","Native receiver failed smoke startup")
        self.debug("record")
        state=self.debug("nativefixture")
        assert state.get("sessionProtocol")=="AIRPLAY",state
        assert state.get("resolution")=="64x48",state
        self.wait(lambda:self.debug().get("recording"),"Synthetic mirror was not recorded")
        self.debug("stop")
        self.wait(lambda:self.debug().get("captureMessage", "").startswith("Salvo:"),"Recording did not finalize")
        state=self.debug()
        filename=state["captureMessage"][len("Salvo: "):]
        assert any(file["name"]==filename and file["bytes"]>100 for file in state["recordings"]),state
        state=self.debug("nativefixture",hevc=True)
        assert state.get("resolution")=="64x48",state
        self.debug("native-disconnect")
        self.debug("mirror-start")
        time.sleep(.4)
        tree=self.ui()
        descriptions=[node.get("content-desc","").lower() for node in tree.iter()]
        assert not any("picture-in-picture" in text or "sair da tela cheia" in text or "exit fullscreen" in text for text in descriptions),descriptions
        self.shot("native-recording-smoke")
        assert "Ferramentas" not in self.texts(tree) and "Tools" not in self.texts(tree),self.texts(tree)
        self.key(4)  # Remote Back remains available when overlay icons are absent.
        self.wait(lambda:any(label in self.texts(self.ui()) for label in ("Ferramentas","Tools")),"Remote Back did not leave fullscreen")
        self.debug("native-disconnect")

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

    def instrumentation(self):
        apk=str(Path(self.args.test_apk).resolve())
        if self.args.adb.lower().endswith(".exe") and os.name!="nt":
            apk=subprocess.check_output(["wslpath","-w",apk],text=True).strip()
        self.adb("install", "--no-streaming", "-r", apk)
        # adb command timeout is intentionally higher for the complete test runner.
        command=[self.args.adb,"-s",self.args.serial,"shell","am","instrument","-w", "-r"]
        if self.args.test_class:
            # adb shell parses the remote command; quote a possible Class#method selector.
            import shlex
            command.extend(["-e", "class", shlex.quote(self.args.test_class)])
        command.append("com.buzincast.tv.test/androidx.test.runner.AndroidJUnitRunner")
        result=subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300, check=True)
        output=result.stdout.decode("utf-8","replace")
        (self.out/"instrumentation.txt").write_text(output,encoding="utf-8")
        assert re.search(r"OK \([1-9]\d* tests?\)",output) and "FAILURES" not in output,output
        self.results.append({"test":"Android socket integration", "passed":True, "runner":output})
        if self.args.coverage:
            self.checkpoint += 1
            for filename in ("android.ec", "native.profraw"):
                target=self.out/"coverage"/f"{self.checkpoint}-{filename}"
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(self.adb("exec-out","run-as",PKG,"cat","files/coverage/"+filename,binary=True))

    def run(self):
        self.adb("wait-for-device")
        self.wait(lambda:self.adb("shell","getprop","sys.boot_completed").strip()=="1","Emulator did not boot",120)
        if self.args.apk:
            apk=str(Path(self.args.apk).resolve())
            if self.args.adb.lower().endswith(".exe") and os.name!="nt":
                apk=subprocess.check_output(["wslpath","-w",apk],text=True).strip()
            self.adb("install","--no-streaming","-r",apk)
        api=int(self.adb("shell","getprop","ro.build.version.sdk").strip())
        if api>=33: self.adb("shell","pm","grant",PKG,"android.permission.POST_NOTIFICATIONS")
        if api>=37: self.adb("shell","pm","grant",PKG,"android.permission.ACCESS_LOCAL_NETWORK")
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
            if self.args.test_apk:
                self.instrumentation()
            cases=[("normal settings",lambda:self.scan_settings(False)),("advanced/custom settings",lambda:self.scan_settings(True)),
                   ("cancel and restore focus",self.edit_cancel),("single relay form",self.relay_form),
                   ("device-aware profiles",self.profiles),("about version",self.about_version),("shared audio controls",self.audio_controls),("native/player recovery and ownership",self.media_recovery),("native codecs and recording smoke",self.native_capture),("launcher entry and resume",self.launcher_entry),("guided calibration save/cancel and bounds",self.calibration)]
            extended=("media remote controls and rendered captions",self.media_controls)
            if self.args.extended or (self.args.case and any(pattern in extended[0] for pattern in self.args.case)):
                cases.append(extended)
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
        finally:
            (self.out/"results.json").write_text(json.dumps(self.results,indent=2,ensure_ascii=False),encoding="utf-8")
            (self.out/"logcat.txt").write_text(self.adb("logcat","-d","-t","1500"),encoding="utf-8")
            self.adb("shell","am","force-stop",PKG)
            self.adb("shell","wm","size","reset");self.adb("shell","wm","density","reset")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial",required=True)
    parser.add_argument("--adb",default="adb")
    parser.add_argument("--apk")
    parser.add_argument("--native-tests", help="Standalone native C++ unit executable built for this emulator ABI")
    parser.add_argument("--test-apk", help="Run Android instrumentation integration suite first")
    parser.add_argument("--test-class", help="Select an AndroidJUnitRunner class or Class#method with --test-apk")
    parser.add_argument("--coverage", action="store_true", help="Require Android and native coverage checkpoints after every scenario")
    parser.add_argument("--output",default="build/emulator-checks")
    parser.add_argument("--case",action="append",help="Run matching case descriptions; repeat to select multiple cases")
    parser.add_argument("--extended",action="store_true",help="Include additional scenarios still under validation; see docs/WORK_CHECKPOINT.md")
    Suite(parser.parse_args()).run()
