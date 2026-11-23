"""Regression checks for coverage automation's device and evidence boundaries."""
import argparse
import io
import json
import shutil
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from tempfile import TemporaryDirectory
from pathlib import Path
from unittest.mock import Mock, call, patch
from zipfile import ZipFile

from test_emulator import COVERAGE_REQUIRED_RESULTS, Suite, UiUnavailable, validate_instrumentation_output
from run_android_coverage import (
    archive_compiled_inputs, coverage_identity, filesystem_path, source_identity, verify_packaged_native_library,
)
from merge_android_coverage import IDENTITY_KEYS, validate_snapshots
from report_coverage import coverage_target_met
from replay_android_coverage import copy_compiled_snapshot


class CoverageAutomationTest(unittest.TestCase):

    def test_coverage_requires_the_apk_to_match_its_native_mapping(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            mapping = Path(directory) / "libairplay_native.so"
            mapping.write_bytes(b"unstripped library")
            with ZipFile(apk, "w") as package:
                package.writestr("lib/x86_64/libairplay_native.so", b"packaged library")

            def strip(contents):
                def run(command, *, check):
                    self.assertTrue(check)
                    self.assertEqual(["--strip-unneeded", "-o"], command[1:3])
                    self.assertEqual(mapping, Path(command[4]))
                    Path(command[3]).write_bytes(contents)
                return run

            with patch("run_android_coverage.subprocess.run", side_effect=strip(b"packaged library")):
                verify_packaged_native_library(apk, mapping, "llvm-strip")
            with patch("run_android_coverage.subprocess.run", side_effect=strip(b"a different library")):
                with self.assertRaisesRegex(RuntimeError, "does not match"):
                    verify_packaged_native_library(apk, mapping, "llvm-strip")
            mapping.write_bytes(b"packaged library")
            with patch("run_android_coverage.subprocess.run") as run:
                verify_packaged_native_library(apk, mapping, "llvm-strip")
            run.assert_not_called()

    def instrumentation_fixture(self, directory, timeout=None, coverage=False):
        suite=Suite.__new__(Suite)
        suite.args=argparse.Namespace(adb="adb",serial="emulator-5558",test_apk="fixture.apk",
                                      test_class=None,coverage=coverage)
        if timeout is not None:
            suite.args.instrumentation_timeout=timeout
        suite.out=Path(directory);suite.package="com.buzincast.tv"
        suite.results=[];suite.checkpoint=0;suite.adb=Mock(return_value="Success")
        return suite

    def test_instrumentation_streams_progress_and_honors_default_or_selected_timeout(self):
        output=b"INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
        for selected,expected in ((None,900),(123,123)):
            with self.subTest(timeout=selected), TemporaryDirectory() as directory:
                suite=self.instrumentation_fixture(directory,selected)
                def run(command, **kwargs):
                    self.assertEqual(expected,kwargs["timeout"])
                    self.assertTrue(kwargs["check"])
                    kwargs["stdout"].write(output);kwargs["stdout"].flush()
                    self.assertEqual(output,(suite.out/"instrumentation.txt").read_bytes())
                    return subprocess.CompletedProcess(command,0)
                with patch("test_emulator.subprocess.run",side_effect=run):
                    suite.instrumentation()
                self.assertEqual(1,len(suite.results))
                self.assertEqual("Android socket integration",suite.results[0]["test"])
                self.assertTrue(suite.results[0]["passed"])

    def test_instrumentation_timeout_retains_partial_progress_without_pass_or_profiles(self):
        with TemporaryDirectory() as directory:
            suite=self.instrumentation_fixture(directory,123,coverage=True)
            partial=b"INSTRUMENTATION_STATUS: test=still_running\nINSTRUMENTATION_STATUS_CODE: 1\n"
            def run(command, **kwargs):
                kwargs["stdout"].write(partial);kwargs["stdout"].flush()
                raise subprocess.TimeoutExpired(command,kwargs["timeout"])
            with patch("test_emulator.subprocess.run",side_effect=run), self.assertRaises(subprocess.TimeoutExpired):
                suite.instrumentation()
            self.assertEqual(partial,(suite.out/"instrumentation.txt").read_bytes())
            self.assertEqual([],suite.results);self.assertEqual(0,suite.checkpoint)
            self.assertFalse((suite.out/"coverage").exists())
            self.assertEqual([
                call("install", "--no-streaming", "-r", str(Path("fixture.apk").resolve())),
                call("shell", "run-as", suite.package, "rm", "-f",
                     "files/coverage/android.ec", "files/coverage/native.profraw"),
            ], suite.adb.call_args_list)

    def test_instrumentation_records_a_scoped_test_selection(self):
        with TemporaryDirectory() as directory:
            suite = self.instrumentation_fixture(directory)
            selection = "example.ReceiverContract#singleCase"
            suite.args.test_class = selection
            with patch.object(suite, "run_instrumentation", return_value="OK (1 test)") as run:
                suite.instrumentation()
            run.assert_called_once_with(suite.out / "instrumentation.txt", selection)
            self.assertEqual(selection, suite.results[0]["test_class"])

    def test_streaming_does_not_allow_skipped_tests_to_create_a_coverage_pass(self):
        with TemporaryDirectory() as directory:
            suite=self.instrumentation_fixture(directory,coverage=True)
            skipped=b"INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\n"
            def run(command, **kwargs):
                kwargs["stdout"].write(skipped)
                return subprocess.CompletedProcess(command,0)
            with patch("test_emulator.subprocess.run",side_effect=run), self.assertRaisesRegex(AssertionError,"without skips"):
                suite.instrumentation()
            self.assertEqual(skipped,(suite.out/"instrumentation.txt").read_bytes())
            self.assertEqual([],suite.results);self.assertEqual(0,suite.checkpoint)
            self.assertFalse((suite.out/"coverage").exists())

    def test_instrumentation_cli_rejects_nonpositive_timeout_before_device_access(self):
        script=Path(__file__).with_name("test_emulator.py")
        for timeout in ("0","-1"):
            with self.subTest(timeout=timeout):
                result=subprocess.run([sys.executable,str(script),"--serial","emulator-5558",
                                       "--instrumentation-timeout",timeout],capture_output=True,text=True)
                self.assertEqual(2,result.returncode)
                self.assertIn("Instrumentation timeout must be positive",result.stderr)
                self.assertNotIn("Traceback",result.stderr)

    def test_replay_rejects_an_incompatible_api_or_abi_before_installing(self):
        from replay_android_coverage import main
        with TemporaryDirectory(prefix="coverage-replay-guard-") as directory:
            source = Path(directory)/"source"
            (source/"inputs").mkdir(parents=True)
            (source/"inputs/native_core_tests").write_bytes(b"native test fixture")
            manifest = {"variant":"normal", "archived_mappings":True, "source_files_sha256":"a"*64}
            for api,abi in ((22,"x86_64"),(28,"arm64-v8a")):
                with self.subTest(api=api,abi=abi), \
                     patch("replay_android_coverage.validate_snapshots",return_value=(manifest,[28])), \
                     patch("replay_android_coverage.subprocess.check_output",side_effect=[str(api),abi]), \
                     patch("replay_android_coverage.subprocess.run") as run, \
                     patch("sys.stderr",new_callable=io.StringIO), \
                     patch.object(sys,"argv",["replay", "--serial","emulator-5558","--snapshot",str(source),
                         "--sdk-root",directory,"--output",str(Path(directory)/"output")]):
                    with self.assertRaises(SystemExit) as error:
                        main()
                    self.assertEqual(2,error.exception.code)
                    run.assert_not_called()
                    self.assertFalse((Path(directory)/"output").exists())

    def test_replay_copies_frozen_inputs_without_prior_runtime_or_success_metadata(self):
        with TemporaryDirectory(prefix="coverage-replay-") as directory:
            source, target = Path(directory)/"source", Path(directory)/"target"
            for name in ("app", "core", "domain"):
                classes = source/"classes"/name
                classes.mkdir(parents=True)
                (classes/"Fixture.class").write_bytes(b"compiled fixture")
                sources = source/"sources"/name
                sources.mkdir(parents=True)
                (sources/"Fixture.kt").write_text("// mapped source")
            native = source/"sources/native/bridge.cpp"
            native.parent.mkdir()
            native.write_text("// mapped native source")
            inputs = source/"inputs"
            inputs.mkdir()
            for name in ("app.apk", "test.apk", "libairplay_native.so", "native_core_tests"):
                (inputs/name).write_bytes(name.encode())
            for name in ("core.exec", "domain.exec", "old.ec", "old.profraw", "results.json", "manifest.json"):
                (source/name).write_bytes(name.encode())
            (inputs/"old.profraw").write_bytes(b"not a compiled input")
            expected = coverage_identity(source,source,inputs/"app.apk",inputs/"test.apk",archive=source)
            identity, native_hash = copy_compiled_snapshot(source,target)
            self.assertEqual(expected,identity)
            self.assertEqual(64,len(native_hash))
            self.assertEqual(source_identity(source,archive=source),source_identity(target,archive=target))
            self.assertEqual({"inputs","classes","sources","core.exec","domain.exec"}, {file.name for file in target.iterdir()})
            self.assertFalse((target/"inputs/old.profraw").exists())
            with self.assertRaises(FileExistsError):
                copy_compiled_snapshot(source,target)

    def test_collect_only_replay_archives_runtime_without_starting_gradle(self):
        for api in (22, 28):
            with self.subTest(api=api):
                self.assert_collect_only_replay(api)

    def assert_collect_only_replay(self, api):
        import hashlib
        from replay_android_coverage import main

        with TemporaryDirectory(prefix="coverage-collect-only-") as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            (source / "inputs").mkdir(parents=True)
            native = b"test executable"
            (source / "inputs/native_core_tests").write_bytes(native)
            identity = dict.fromkeys(IDENTITY_KEYS, "fixture")
            manifest = {**identity, "variant": "compat", "emulator_api": 28,
                        "archived_mappings": True, "source_files_sha256": "mapped"}

            def freeze(_source, target):
                (target / "inputs").mkdir(parents=True)
                (target / "inputs/native_core_tests").write_bytes(native)
                return identity, hashlib.sha256(native).hexdigest()

            def runtime(command, **_options):
                self.assertEqual("test_emulator.py", Path(command[1]).name)
                self.assertEqual(api >= 23, "--diagnostic-networks" in command)
                execution = Path(command[command.index("--output") + 1])
                profiles = execution / "coverage"
                profiles.mkdir(parents=True)
                (profiles / "run.ec").write_bytes(b"JVM")
                (profiles / "run.profraw").write_bytes(b"native")
                (execution / "results.json").write_text("[]", encoding="utf-8")

            with patch("replay_android_coverage.validate_snapshots", return_value=(manifest, [28])), \
                 patch("replay_android_coverage.copy_compiled_snapshot", side_effect=freeze), \
                 patch("replay_android_coverage.coverage_identity", return_value=identity), \
                 patch("replay_android_coverage.source_identity", return_value="mapped"), \
                 patch("replay_android_coverage.subprocess.check_output", side_effect=[str(api), "x86_64", ""]), \
                 patch("replay_android_coverage.subprocess.run", side_effect=runtime) as run, \
                 patch.object(sys, "argv", ["replay", "--serial", "emulator-5558", "--snapshot",
                     str(source), "--sdk-root", directory, "--output", str(output), "--collect-only"]):
                main()
            run.assert_called_once()
            result = json.loads((output / "replay.json").read_text(encoding="utf-8"))
            item = result["variants"]["compat"]
            self.assertFalse(result["all_four_target_met"])
            self.assertNotIn("target_met", item)
            snapshot = output / item["raw_snapshot"]
            self.assertEqual(b"JVM", (snapshot / "run.ec").read_bytes())
            self.assertEqual(b"native", (snapshot / "run.profraw").read_bytes())
            self.assertEqual(api, json.loads((snapshot / "manifest.json").read_text())["emulator_api"])

    def test_replay_rejects_a_physical_serial_before_reading_snapshots_or_sdk(self):
        script=Path(__file__).with_name("replay_android_coverage.py")
        result=subprocess.run([sys.executable,str(script),"--serial","192.0.2.10:5555",
            "--snapshot","missing","--output","unused"],capture_output=True,text=True)
        self.assertEqual(2,result.returncode)
        self.assertIn("Only an explicit emulator-<port> serial is permitted",result.stderr)
        self.assertNotIn("Traceback",result.stderr)

    @patch("test_emulator.time.sleep")
    def test_focus_query_waits_for_a_real_hierarchy_instead_of_empty_focus(self, _sleep):
        suite = Suite.__new__(Suite)
        suite.ui = Mock(side_effect=[UiUnavailable("opening settings"),
            ET.fromstring('<hierarchy><node focused="true" text="Cinema"/></hierarchy>')])
        self.assertEqual("Cinema", suite.focused())
        self.assertEqual(2, suite.ui.call_count)

    @patch("test_emulator.time.sleep")
    def test_cancel_reads_the_same_settled_settings_hierarchy(self, _sleep):
        suite = Suite.__new__(Suite)
        suite.start = Mock()
        suite.profile_focus = Mock()
        suite.key = Mock()
        suite.adb = Mock()
        suite.focused = Mock(return_value="Server name")
        suite.keyboard = Mock(side_effect=[True, False, False] * 3)
        dialog = ET.fromstring('<hierarchy><node class="android.widget.EditText" text="_AUTOTEST"/></hierarchy>')
        settled = ET.fromstring('<hierarchy><node text="Server name"/></hierarchy>')
        suite.ui = Mock(side_effect=[dialog, UiUnavailable("dialog closing"), settled] * 3)

        suite.edit_cancel()

        self.assertEqual(9, suite.ui.call_count)
        self.assertEqual(3, suite.start.call_count)
        self.assertEqual([call("shell", "input", "text", "_AUTOTEST")] * 3, suite.adb.call_args_list)

    @patch("test_emulator.time.sleep")
    @patch("test_emulator.time.monotonic", side_effect=[0, 0, 20])
    def test_persistent_missing_hierarchy_cannot_be_reported_as_empty_focus(self, _clock, _sleep):
        suite = Suite.__new__(Suite)
        suite.ui = Mock(side_effect=UiUnavailable("missing hierarchy"))
        with self.assertRaisesRegex(AssertionError, "Accessibility unavailable while reading focus.*missing hierarchy"):
            suite.focused()

    @patch("test_emulator.time.sleep")
    def test_activation_waits_for_valid_focus_without_sending_blind_navigation(self, _sleep):
        suite = Suite.__new__(Suite)
        focused = ET.fromstring('<hierarchy><node focused="true" focusable="true" content-desc="Next"/></hierarchy>')
        suite.ui = Mock(side_effect=[UiUnavailable("recomposing"), focused])
        suite.key = Mock()
        suite.activate("Next", description=True)
        self.assertEqual(2, suite.ui.call_count)
        suite.key.assert_called_once_with(23)

    @patch("test_emulator.time.sleep")
    @patch("test_emulator.time.monotonic", side_effect=[0, 0, 20])
    def test_activation_still_fails_when_accessibility_never_becomes_available(self, _clock, _sleep):
        suite = Suite.__new__(Suite)
        suite.ui = Mock(side_effect=UiUnavailable("missing hierarchy"))
        suite.key = Mock()
        with self.assertRaisesRegex(AssertionError, "Accessibility unavailable.*missing hierarchy"):
            suite.activate("Next", description=True)
        suite.key.assert_not_called()

    def test_archived_source_changes_cannot_keep_a_valid_compilation_fingerprint(self):
        with TemporaryDirectory(prefix="coverage-source-fingerprint-") as directory:
            snapshot = Path(directory)
            for name in ("app", "core", "domain"):
                classes = snapshot/"classes"/name
                classes.mkdir(parents=True)
                (classes/"Fixture.class").write_bytes(b"compiled fixture")
                sources = snapshot/"sources"/name
                sources.mkdir(parents=True)
                (sources/"Fixture.kt").write_text("// source fixture", encoding="utf-8")
            native = snapshot/"sources/native/bridge.cpp"
            native.parent.mkdir()
            native.write_text("// native fixture", encoding="utf-8")
            inputs = snapshot/"inputs"
            inputs.mkdir()
            for name in ("app.apk", "test.apk", "libairplay_native.so"):
                (inputs/name).write_bytes(name.encode())
            identity = coverage_identity(snapshot,snapshot,inputs/"app.apk",inputs/"test.apk",archive=snapshot)
            (snapshot/"manifest.json").write_text(json.dumps({"variant":"compat","emulator_api":22,
                "archived_mappings":True,"source_files_sha256":source_identity(snapshot,archive=snapshot),**identity}),encoding="utf-8")
            runner = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
            results = [{"test":name,"passed":True,"runner":runner} for name in sorted(COVERAGE_REQUIRED_RESULTS)]
            (snapshot/"results.json").write_text(json.dumps(results),encoding="utf-8")
            # This gate checks identities; the report tools parse execution bytes later.
            for name in ("android.ec","native.profraw","core.exec","domain.exec"):
                (snapshot/name).write_bytes(b"execution fixture")
            validate_snapshots([snapshot])
            native.write_text("// changed after compilation",encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"Archived production sources"):
                validate_snapshots([snapshot])

    @patch("test_emulator.time.sleep")
    def test_wait_does_not_accept_an_empty_xml_element_as_ready(self, _sleep):
        suite = Suite.__new__(Suite)
        complete = ET.fromstring("<hierarchy><node/></hierarchy>")
        predicate = Mock(side_effect=[ET.fromstring("<hierarchy/>"), complete])
        self.assertIs(complete, suite.wait(predicate, "Screen did not become ready"))
        self.assertEqual(2, predicate.call_count)

    @patch("test_emulator.time.sleep")
    def test_profile_transition_requires_a_complete_persisted_value(self, _sleep):
        suite = Suite.__new__(Suite)
        suite.package = "com.buzincast.tv"
        suite.adb = Mock(side_effect=["", "<map><string", '<map><string name="experience_profile">Cinema</string></map>'])
        self.assertTrue(suite.wait(lambda: suite.saved_profile() == "Cinema", "Profile not saved"))
        self.assertEqual(3, suite.adb.call_count)
        suite.adb = Mock(return_value="<map/>")
        self.assertEqual("Padrão", suite.saved_profile())
        suite.adb = Mock(return_value="<unexpected/>")
        with self.assertRaises(UiUnavailable):
            suite.saved_profile()
        suite.adb = Mock(side_effect=RuntimeError("device disconnected"))
        with self.assertRaisesRegex(RuntimeError, "device disconnected"):
            suite.saved_profile()

    def test_acceptance_requires_every_metric_and_real_nonzero_mapping(self):
        jvm={name:{"covered":1,"total":1} for name in ("INSTRUCTION","BRANCH","LINE","COMPLEXITY","METHOD","CLASS")}
        native={name:{"covered":1,"total":1} for name in ("branches","functions","instantiations","lines","mcdc","regions")}
        self.assertTrue(coverage_target_met(jvm,native))
        self.assertFalse(coverage_target_met({**jvm,"BRANCH":{"covered":0,"total":1}},native))
        for changed_jvm,changed_native in (({key:value for key,value in jvm.items() if key!="BRANCH"},native),
                                           (jvm,{key:value for key,value in native.items() if key!="mcdc"})):
            with self.assertRaisesRegex(ValueError,"metrics missing"):
                coverage_target_met(changed_jvm,changed_native)
        with self.assertRaisesRegex(ValueError,"no mapped code"):
            coverage_target_met(jvm,{**native,"mcdc":{"covered":0,"total":0}})
        with self.assertRaisesRegex(ValueError,"invalid coverage counters"):
            coverage_target_met(jvm,{**native,"mcdc":{"covered":2,"total":1}})

    def test_merging_rejects_changed_editions_mappings_and_incomplete_test_evidence(self):
        with TemporaryDirectory(prefix="coverage-merge-") as directory:
            snapshots=[Path(directory)/"api22",Path(directory)/"api28"]
            runner="INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
            results=[{"test":"Android socket integration","passed":True,"runner":runner},
                     {"test":"C++ unit concurrency and timing","passed":True},
                     {"test":"media remote controls and rendered captions","passed":True}]
            results += [{"test":name,"passed":True} for name in sorted(
                COVERAGE_REQUIRED_RESULTS - {result["test"] for result in results})]
            identity={key:"a"*64 for key in IDENTITY_KEYS}
            identity["class_counts"]={"app":1,"app-kotlin":0,"core":1,"domain":1}
            for snapshot,api in zip(snapshots,[22,28]):
                snapshot.mkdir()
                (snapshot/"manifest.json").write_text(json.dumps({"variant":"compat","emulator_api":api,**identity}),encoding="utf-8")
                (snapshot/"results.json").write_text(json.dumps(results),encoding="utf-8")
                (snapshot/"android.ec").write_bytes(b"raw data is parsed by JaCoCo later")
                (snapshot/"native.profraw").write_bytes(b"raw data is parsed by LLVM later")
            _,apis=validate_snapshots(snapshots)
            self.assertEqual([22,28],apis)
            for missing in COVERAGE_REQUIRED_RESULTS:
                (snapshots[1]/"results.json").write_text(json.dumps(
                    [result for result in results if result["test"] != missing]),encoding="utf-8")
                with self.assertRaisesRegex(ValueError,"completed instrumented/native/extended UI run"):
                    validate_snapshots(snapshots)
            (snapshots[1]/"results.json").write_text(json.dumps(results),encoding="utf-8")
            second=snapshots[1]/"manifest.json"
            valid=second.read_text(encoding="utf-8")
            for change in ({"variant":"deat"},{"class_files_sha256":"changed"}):
                second.write_text(json.dumps({**json.loads(valid),**change}),encoding="utf-8")
                with self.assertRaisesRegex(ValueError,"different variants or compiled inputs"):
                    validate_snapshots(snapshots)
            second.write_text(valid,encoding="utf-8")
            results[0]["test_class"] = "example.ReceiverContract#singleCase"
            (snapshots[1]/"results.json").write_text(json.dumps(results),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"full Android instrumentation suite"):
                validate_snapshots(snapshots)
            del results[0]["test_class"]
            results[0]["runner"]=runner.replace("STATUS_CODE: 0","STATUS_CODE: -4")
            (snapshots[1]/"results.json").write_text(json.dumps(results),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"skipped tests"):
                validate_snapshots(snapshots)

    def test_provenance_reads_long_live_classes_and_preserves_the_full_inventory(self):
        with TemporaryDirectory(prefix="coverage-live-classes-") as directory:
            root=Path(directory)
            build=root/"app/build-coverage"
            snapshot=root/"snapshot"
            leaf="Dagger"+"GeneratedComponent"*9+"$SwitchingProvider.class"
            app_classes=build/"intermediates/classes/debug/transformDebugClassesWithAsm/dirs"
            long_class=app_classes/"io/github/jqssun/airplay"/leaf
            self.assertGreater(len(str(long_class)),260)
            self.assertLess(len(leaf),256)
            classes=[app_classes/"App.class",long_class,
                     build/"tmp/kotlin-classes/debug/Kotlin.class",
                     root/"receiver-core/build/classes/kotlin/main/Core.class",
                     root/"dlna-domain/build/classes/kotlin/main/Domain.class"]
            apk=build/"app.apk";test_apk=build/"test.apk"
            native=build/"intermediates/cxx/Debug/fixture/obj/x86_64/libairplay_native.so"
            try:
                for file in [apk,test_apk,native,*classes]:
                    actual=filesystem_path(file)
                    actual.parent.mkdir(parents=True,exist_ok=True)
                    actual.write_bytes(b"original compiled content")
                for module in ("app","receiver-core","dlna-domain"):
                    source=root/module/"src/main/kotlin/Source.kt"
                    source.parent.mkdir(parents=True,exist_ok=True)
                    source.write_text("// production source",encoding="utf-8")
                    if module!="app":
                        execution=root/module/"build/jacoco/test.exec"
                        execution.parent.mkdir(parents=True,exist_ok=True)
                        execution.write_bytes(b"unit execution data")
                native_source=root/"app/src/main/cpp/native_bridge.cpp"
                native_source.parent.mkdir(parents=True)
                native_source.write_text("// native production source",encoding="utf-8")
                baseline=coverage_identity(root,build,apk,test_apk)
                self.assertEqual({"app":2,"app-kotlin":1,"core":1,"domain":1},baseline["class_counts"])
                filesystem_path(long_class).write_bytes(b"changed generated class")
                self.assertNotEqual(baseline["class_files_sha256"],
                                    coverage_identity(root,build,apk,test_apk)["class_files_sha256"])
                filesystem_path(long_class).write_bytes(b"original compiled content")
                snapshot.mkdir()
                archive_compiled_inputs(root,build,apk,test_apk,snapshot,baseline,
                                        expected_sources=source_identity(root))
                self.assertEqual(baseline,coverage_identity(root,build,apk,test_apk,archive=snapshot))
            finally:
                # Both trees belong to this temporary fixture, including long children.
                for owned in (build,snapshot):
                    self.assertTrue(owned.resolve().is_relative_to(root.resolve()))
                    if filesystem_path(owned).exists():shutil.rmtree(filesystem_path(owned))

    def test_provenance_detects_changed_compiled_inputs_and_missing_module_inventory(self):
        with TemporaryDirectory(prefix="coverage-provenance-") as directory:
            root=Path(directory)
            build=root/"app/build-coverage"
            apk=build/"app.apk"
            test_apk=build/"test.apk"
            classes=[build/"intermediates/classes/debug/transformDebugClassesWithAsm/dirs/App.class",
                     root/"receiver-core/build/classes/kotlin/main/Core.class",
                     root/"dlna-domain/build/classes/kotlin/main/Domain.class"]
            native=build/"intermediates/cxx/Debug/fixture/obj/x86_64/libairplay_native.so"
            for file in [apk,test_apk,native,*classes]:
                file.parent.mkdir(parents=True,exist_ok=True)
                file.write_bytes(b"original compiled content")
            baseline=coverage_identity(root,build,apk,test_apk)
            for name,module in (("app","app"),("core","receiver-core"),("domain","dlna-domain")):
                source=root/module/"src/main/kotlin/Source.kt"
                source.parent.mkdir(parents=True,exist_ok=True)
                source.write_text("// production source",encoding="utf-8")
                if name!="app":
                    execution=root/module/"build/jacoco/test.exec"
                    execution.parent.mkdir(parents=True,exist_ok=True)
                    execution.write_bytes(b"unit execution data")
            native_source=root/"app/src/main/cpp/native_bridge.cpp"
            native_source.parent.mkdir(parents=True)
            native_source.write_text("// native production source",encoding="utf-8")
            snapshot=root/"snapshot"
            snapshot.mkdir()
            source_fingerprint = source_identity(root)
            archive_compiled_inputs(root,build,apk,test_apk,snapshot,baseline,expected_sources=source_fingerprint)
            self.assertEqual(baseline,coverage_identity(root,build,apk,test_apk,archive=snapshot))
            self.assertEqual(source_fingerprint,source_identity(root,archive=snapshot))
            # Match deep Hilt class names beneath a long user-selected report path.
            long_snapshot=root/('report-'+'x'*95)/('cohort-'+'y'*95)/'snapshot'
            self.assertGreater(len(str(long_snapshot/'classes/app/App.class')),260)
            long_snapshot=filesystem_path(long_snapshot)
            try:
                long_snapshot.mkdir(parents=True)
                archive_compiled_inputs(root,build,apk,test_apk,long_snapshot,baseline,expected_sources=source_fingerprint)
                self.assertEqual(baseline,coverage_identity(root,build,apk,test_apk,archive=long_snapshot))
                self.assertEqual(source_fingerprint,source_identity(root,archive=long_snapshot))
            finally:
                shutil.rmtree(filesystem_path(root/('report-'+'x'*95)))
            native_source.write_text("// changed during compilation",encoding="utf-8")
            changed_snapshot=root/"changed-snapshot"
            changed_snapshot.mkdir()
            with self.assertRaisesRegex(RuntimeError,"Production sources changed during compilation"):
                archive_compiled_inputs(root,build,apk,test_apk,changed_snapshot,baseline,expected_sources=source_fingerprint)
            self.assertFalse((changed_snapshot/"inputs").exists())
            native_source.write_text("// native production source",encoding="utf-8")
            for file in [apk,test_apk,native,*classes]:
                file.write_bytes(b"changed compiled content")
                self.assertNotEqual(baseline,coverage_identity(root,build,apk,test_apk))
                file.write_bytes(b"original compiled content")
            (build/"irrelevant.log").write_text("diagnostic only",encoding="utf-8")
            self.assertEqual(baseline,coverage_identity(root,build,apk,test_apk))
            classes[1].unlink()
            self.assertEqual(baseline,coverage_identity(root,build,apk,test_apk,archive=snapshot))
            with self.assertRaisesRegex(RuntimeError,"Compiled application/core/domain classes"):
                coverage_identity(root,build,apk,test_apk)

    @patch("test_emulator.time.sleep")
    def test_wait_retries_unavailable_ui_without_counting_it_as_dialog_absence(self, _sleep):
        suite=Suite.__new__(Suite)
        predicate=Mock(side_effect=[UiUnavailable("transition"),False,"real screen"])
        self.assertEqual("real screen",suite.wait(predicate,"No stable screen"))
        self.assertEqual(3,predicate.call_count)

    def test_coverage_rejects_ignored_or_assumption_skipped_instrumentation_tests(self):
        for skipped in (-3,-4):
            output=f"INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: {skipped}\nOK (1 test)\n"
            with self.assertRaisesRegex(AssertionError,"without skips or assumptions"):
                validate_instrumentation_output(output,require_all=True)
            validate_instrumentation_output(output,require_all=False)

    def test_coverage_requires_one_success_for_every_started_test(self):
        valid="INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        validate_instrumentation_output(valid,require_all=True)
        with self.assertRaises(AssertionError):
            validate_instrumentation_output(valid.replace("OK (1 test)","OK (2 tests)"),require_all=True)
        with self.assertRaises(AssertionError):
            validate_instrumentation_output("FAILURES!!!",require_all=True)

    @patch("test_emulator.time.sleep")
    def test_failed_dump_cannot_return_stale_screen(self, _sleep):
        suite=Suite.__new__(Suite)
        suite.adb=Mock(side_effect=["ERROR: null root node", "UI hierarchy dumped", '<hierarchy><node text="Current screen"/></hierarchy>'])
        tree=suite.ui()
        self.assertEqual("Current screen",Suite.texts(tree).strip())
        # The failed dump must not read the previously written device XML.
        self.assertEqual("uiautomator",suite.adb.call_args_list[1].args[1])

    @patch("test_emulator.time.sleep")
    def test_malformed_or_empty_tree_cannot_prove_dialog_closed(self, _sleep):
        suite=Suite.__new__(Suite)
        suite.adb=Mock(side_effect=["UI hierarchy dumped", "<hierarchy/>", "UI hierarchy dumped", "<broken", "UI hierarchy dumped", '<hierarchy><node text="Dialog remains open"/></hierarchy>'])
        self.assertIn("Dialog remains open",Suite.texts(suite.ui()))

    @patch("test_emulator.time.sleep")
    def test_persistent_dump_failure_is_a_failure_not_empty_success(self, _sleep):
        suite=Suite.__new__(Suite)
        suite.adb=Mock(return_value="ERROR: null root node")
        with self.assertRaisesRegex(AssertionError,"nonempty accessibility hierarchy"):
            suite.ui()

    def test_regional_identity_requires_recording_policy_assertions(self):
        with self.assertRaisesRegex(ValueError,"DE/AT must be tested with --no-download"):
            Suite(argparse.Namespace(serial="emulator-5558",package="com.buzincast.tv.deat",no_download=False))

    def test_matrix_rejects_physical_target_before_any_sdk_or_device_access(self):
        script=Path(__file__).with_name("run_android_coverage.py")
        result=subprocess.run([sys.executable,str(script),"--serial","192.0.2.10:5555"],capture_output=True,text=True)
        self.assertEqual(2,result.returncode)
        self.assertIn("Only an explicit emulator-<port> serial is permitted",result.stderr)
        self.assertNotIn("Traceback",result.stderr)


if __name__=="__main__":
    unittest.main()
