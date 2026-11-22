import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("maintenance", pathlib.Path(__file__).with_name("extended_maintenance.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class StableMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="morphe-maintenance-")
        self.repo = pathlib.Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def git(self, *args):
        return m.run("git", *args, cwd=self.repo)

    def fixture(self):
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Maintenance fixture")
        self.git("config", "user.email", "fixture@invalid.example")
        (self.repo / "identity").write_text("Morphe")
        (self.repo / "upstream-feature").write_text("stable 1")
        self.git("add", ".")
        self.git("commit", "-m", "stable baseline")
        self.git("branch", "upstream-stable")
        (self.repo / "identity").write_text("Morphe Extended")
        (self.repo / "native-downloader").write_text("retain integration")
        self.git("add", ".")
        self.git("commit", "-m", "private fork")
        return self.git("rev-parse", "HEAD")

    def candidate(self, conflict=False):
        self.git("checkout", "upstream-stable")
        (self.repo / ("identity" if conflict else "upstream-feature")).write_text("stable 2")
        self.git("add", ".")
        self.git("commit", "-m", "next stable")
        candidate = self.git("rev-parse", "HEAD")
        self.git("checkout", "main")
        return candidate

    def test_extended_version_is_numeric_and_matches_stable_baseline(self):
        m.validate_extended_version("1.33.0.3", "v1.33.0")
        for version in ("1.33.0-extended.3", "1.33.0", "1.33.0.0", "1.34.0.3", "1.33.0.03"):
            with self.assertRaises(ValueError):
                m.validate_extended_version(version, "v1.33.0")

    def test_unchanged_baseline_with_older_release_requires_publication(self):
        state = m.delivery_state("1.34.0.1", {"tag_name": "v1.33.0.7"}, None, None)
        self.assertEqual("PUBLISH", state)

    def test_matching_published_release_requires_independent_proof(self):
        release, proof = self.delivery_fixture()
        self.assertEqual("VERIFY", m.delivery_state("1.34.0.1", release, None, "source"))
        self.assertEqual("COMPLETE", m.delivery_state("1.34.0.1", release, proof, "source"))
        for altered in ({"tagCommit": "other"}, {"releaseId": 2},
                        {"independentDownloadVerified": False}, {"sha256": "other"}):
            with self.subTest(altered=altered):
                self.assertEqual("VERIFY", m.delivery_state("1.34.0.1", release, proof | altered, "source"))
        self.assertEqual("VERIFY", m.delivery_state("1.34.0.1", release | {"assets": []}, proof, "source"))
        self.assertEqual("VERIFY", m.delivery_state("1.34.0.1", release | {"draft": True}, proof, "source"))

    def delivery_fixture(self):
        release = {"id": 1, "tag_name": "v1.34.0.1", "draft": False,
                   "prerelease": False, "published_at": "date", "assets": [
                       {"name": "Mamu-1.34.0.1.apk", "digest": "sha256:hash", "size": 10},
                       {"name": "release-verification.json"}]}
        proof = {"version": "1.34.0.1", "releaseId": 1, "tagCommit": "source",
                 "sourceHead": "source", "packagedRevision": "source", "sha256": "hash", "bytes": 10,
                 "independentDownloadVerified": True, "allAssetBytesMatched": True,
                 "committedSourceVerified": True, "signerSha256": m.SIGNER}
        return release, proof

    def test_inspect_reports_resumable_merge_then_unpublished_unchanged_baseline(self):
        fork = self.fixture()
        candidate = self.candidate()
        self.git("tag", "v1.34.0", candidate)
        self.git("remote", "add", "upstream", str(self.repo))
        m.prepare_merge(self.repo, candidate)
        (self.repo / "app").mkdir()
        (self.repo / "app/gradle.properties").write_text("version = 1.34.0.1\n")
        config = {"upstreamCommit": candidate, "upstreamTag": "v1.34.0"}
        original_run = m.run
        def run(*args, **kwargs):
            if args[0] == "gh":
                if m.UPSTREAM in args[-1]:
                    return json.dumps({"tag_name": "v1.34.0", "draft": False,
                                       "prerelease": False, "published_at": "date"})
                return json.dumps({"tag_name": "v1.33.0.7"})
            return original_run(*args, cwd=self.repo)
        with patch.object(m, "ROOT", self.repo), patch.object(m, "require_origins", return_value=config), \
                patch.object(m, "run", side_effect=run):
            state = m.inspect_stable()
            self.assertFalse(state["changed"])
            self.assertEqual(candidate, state["mergeInProgress"])
            self.assertTrue(state["deliveryNeeded"])
            self.assertEqual(fork, self.git("rev-parse", "HEAD"))
            self.git("commit", "-m", "finish merge")
            state = m.inspect_stable()
            self.assertIsNone(state["mergeInProgress"])
            self.assertFalse(state["changed"])
            self.assertTrue(state["deliveryNeeded"])
            self.assertEqual("PUBLISH", state["deliveryState"])

    def test_only_published_stable_tags_are_accepted(self):
        stable = {"tag_name": "v1.33.0", "draft": False, "prerelease": False, "published_at": "date"}
        self.assertEqual("v1.33.0", m.stable_tag(stable))
        for changed in ({"draft": True}, {"prerelease": True}, {"tag_name": "v1.34.0-dev.6"},
                        {"tag_name": "v1.34.0-beta.1"}, {"published_at": None}):
            with self.assertRaises(ValueError):
                m.stable_tag(stable | changed)

    def test_three_way_merge_preserves_fork_history_and_features(self):
        fork = self.fixture()
        candidate = self.candidate()
        m.prepare_merge(self.repo, candidate)
        self.assertEqual("Morphe Extended", (self.repo / "identity").read_text())
        self.assertEqual("retain integration", (self.repo / "native-downloader").read_text())
        self.assertEqual("stable 2", (self.repo / "upstream-feature").read_text())
        self.assertEqual(fork, self.git("rev-parse", "HEAD"))
        self.assertEqual(candidate, self.git("rev-parse", "MERGE_HEAD"))

    def test_dirty_work_is_preserved_without_starting_a_merge(self):
        fork = self.fixture()
        candidate = self.candidate()
        (self.repo / "user-work").write_text("preserve")
        with self.assertRaises(ValueError):
            m.prepare_merge(self.repo, candidate)
        self.assertEqual(fork, self.git("rev-parse", "HEAD"))
        self.assertEqual("preserve", (self.repo / "user-work").read_text())
        self.assertFalse((self.repo / ".git/MERGE_HEAD").exists())

    def test_conflicting_identity_stops_promotion_and_keeps_both_merge_parents(self):
        fork = self.fixture()
        candidate = self.candidate(conflict=True)
        with self.assertRaises(subprocess.CalledProcessError):
            m.prepare_merge(self.repo, candidate)
        self.assertIn("UU identity", self.git("status", "--porcelain"))
        self.assertEqual(fork, self.git("rev-parse", "HEAD"))
        self.assertEqual(candidate, self.git("rev-parse", "MERGE_HEAD"))

    def test_exact_inputs_include_launcher_and_license_generation(self):
        paths = ["app/src/main/AndroidManifest.xml", "buildSrc/build.gradle.kts",
                 "buildSrc/src/main/kotlin/GenerateLauncherIconsTask.kt",
                 "patcher-core/build.gradle.kts", "patcher-core/src/main/kotlin/Host.kt",
                 "app/schemas/app.morphe.manager.data.room.AppDatabase/19.json",
                 "app/proguard-rules.pro", "app/aboutlibraries/licenses/runtime.json",
                 "app/licenses/licenses.json", "app/licenses/texts/MIT.txt", "NOTICE",
                 "buildSrc/src/main/kotlin/LicensesAssetsTask.kt", "docs/note.md"]
        for name in paths:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("input")
        with patch.object(m, "ROOT", self.repo), patch.object(m, "run", return_value="\n".join(paths)):
            self.assertEqual(sorted(paths[:-1]), m.input_paths())

    def test_packaged_license_text_is_bound_to_its_generation_and_source(self):
        extras = {"libraries": [{"name": "Morphe Extended", "licenses": ["MIT"]}],
                  "licenses": {"MIT": {"name": "MIT", "text": "MIT.txt"}}}
        root = self.repo
        (root / "app/licenses/texts").mkdir(parents=True)
        (root / "app/licenses/licenses.json").write_text(json.dumps(extras))
        (root / "app/licenses/texts/MIT.txt").write_bytes(b"license text")
        (root / "NOTICE").write_bytes(b"required notice")
        generated = root / "app/build/generated/assets/releaseLicensesAssets/licenses/licenses.json"
        generated.parent.mkdir(parents=True)
        generated.write_text(json.dumps(extras))
        apk = root / "manager.apk"
        def package(text):
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/licenses/licenses.json", generated.read_bytes())
                archive.writestr("assets/licenses/MIT.txt", text)
                archive.writestr("assets/licenses/NOTICE.txt", b"required notice")
        with patch.object(m, "ROOT", root):
            package(b"license text")
            self.assertIn("MIT.txt", m.verify_license_assets(apk))
            package(b"altered text")
            with self.assertRaisesRegex(ValueError, "Packaged license text differs"):
                m.verify_license_assets(apk)

    def test_host_frameworks_must_match_the_exact_pinned_dependency_bytes(self):
        dependency = self.repo / "dependency.jar"
        apk = self.repo / "manager.apk"
        with zipfile.ZipFile(dependency, "w") as archive:
            archive.writestr("frameworks/android.bin", b"framework")
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("frameworks/android.bin", b"framework")
        verified = m.verify_host_frameworks(apk, dependency)
        self.assertEqual({"frameworks/android.bin"}, verified["assets"].keys())
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("frameworks/android.bin", b"different")
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            m.verify_host_frameworks(apk, dependency)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("other.bin", b"framework")
        with self.assertRaisesRegex(ValueError, "resource set differs"):
            m.verify_host_frameworks(apk, dependency)

    def test_changed_sources_or_uncommitted_blob_cannot_pass_artifact_gate(self):
        source = self.repo / "app.kt"
        source.write_text("built source")
        manifest = {"inputs": {"app.kt": {"sha256": m.hashlib.sha256(source.read_bytes()).hexdigest(), "gitBlob": "built"}}}
        with patch.object(m, "ROOT", self.repo), patch.object(m, "input_paths", return_value=["app.kt"]):
            m.verify_inputs(manifest, {"app.kt": "built"})
            with self.assertRaises(ValueError):
                m.verify_inputs(manifest, {"app.kt": "different"})
            source.write_text("changed after build")
            with self.assertRaises(ValueError):
                m.verify_inputs(manifest)


if __name__ == "__main__":
    unittest.main()
