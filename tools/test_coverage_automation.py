"""Regression checks for coverage automation's device and evidence boundaries."""
import argparse
import json
import shutil
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from tempfile import TemporaryDirectory
from pathlib import Path
from unittest.mock import Mock, patch

from test_emulator import Suite, UiUnavailable, validate_instrumentation_output
from run_android_coverage import archive_compiled_inputs, coverage_identity, source_identity, filesystem_path
from merge_android_coverage import IDENTITY_KEYS, validate_snapshots
from report_coverage import coverage_target_met


class CoverageAutomationTest(unittest.TestCase):
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
            results = [{"test":name,"passed":True,"runner":runner} for name in
                ("Android socket integration","C++ unit concurrency and timing","media remote controls and rendered captions")]
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
            second=snapshots[1]/"manifest.json"
            valid=second.read_text(encoding="utf-8")
            for change in ({"variant":"deat"},{"class_files_sha256":"changed"}):
                second.write_text(json.dumps({**json.loads(valid),**change}),encoding="utf-8")
                with self.assertRaisesRegex(ValueError,"different variants or compiled inputs"):
                    validate_snapshots(snapshots)
            second.write_text(valid,encoding="utf-8")
            results[0]["runner"]=runner.replace("STATUS_CODE: 0","STATUS_CODE: -4")
            (snapshots[1]/"results.json").write_text(json.dumps(results),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"skipped tests"):
                validate_snapshots(snapshots)

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
