"""Regression checks for the staged-content release guard; no network or real credentials."""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("public_check", Path(__file__).with_name("check-public.py"))
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)
ROOT = Path(__file__).resolve().parents[1]


class StagedContentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        for name in ["LICENSE", "README.md", "fixtures/sample-track.json"]:
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        self.git("add", ".")

    def git(self, *args):
        subprocess.run(["git", "-C", str(self.repo), *args], check=True, capture_output=True)

    def test_accepts_synthetic_public_baseline(self):
        self.assertEqual(CHECKER.check(self.repo), [])

    def test_reads_staged_secret_even_if_worktree_is_cleaned(self):
        path = self.repo / "config.py"
        path.write_text('API_KEY = "' + "sk-" + "A1b2C3" * 6 + '"\n')
        self.git("add", "config.py")
        path.write_text("API_KEY = None\n")
        self.assertTrue(any("provider credential" in reason for _, reason in CHECKER.check(self.repo)))

    def test_rejects_private_file_and_changed_fixture(self):
        (self.repo / ".env").write_text("APP_MODE=local\n")
        path = self.repo / "fixtures/sample-track.json"
        data = json.loads(path.read_text())
        data["points"][0]["latitude"] = 1.0
        path.write_text(json.dumps(data))
        self.git("add", ".")
        results = CHECKER.check(self.repo)
        self.assertTrue(any(name == ".env" for name, _ in results))
        self.assertTrue(any("synthetic fixture" in reason for _, reason in results))


if __name__ == "__main__":
    unittest.main()
