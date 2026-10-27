"""Filesystem/download-failure tests; fixtures are NOT usable speech models. No network calls."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'download_vosk_model.sh'

class ModelDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        self.script = self.root / 'scripts/download_vosk_model.sh'
        shutil.copyfile(SCRIPT, self.script)
        self.model = self.root / 'app/src/main/assets/model-ru-small'
        self.model.mkdir(parents=True)
        (self.model / 'old-marker').write_text('existing model')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        fake = self.bin / 'curl'
        fake.write_text('''#!/usr/bin/env python3
import os,sys,shutil
if os.environ.get("SIMULATE_HTTP_ERROR") == "1": sys.exit(22)
shutil.copyfile(os.environ["FIXTURE_ARCHIVE"],sys.argv[sys.argv.index("-o")+1])
''')
        fake.chmod(0o700)
        self.env = os.environ.copy()
        self.env['PATH'] = str(self.bin) + os.pathsep + self.env['PATH']
        self.fixture = self.root / 'fixture.zip'
        self.env['FIXTURE_ARCHIVE'] = str(self.fixture)

    def run_script(self):
        return subprocess.run(['sh', str(self.script)], env=self.env, capture_output=True, text=True, timeout=10)

    def test_http_failure_preserves_existing_model(self):
        self.env['SIMULATE_HTTP_ERROR'] = '1'
        self.assertEqual(22, self.run_script().returncode)
        self.assertEqual('existing model', (self.model / 'old-marker').read_text())

    def test_incomplete_archive_preserves_existing_model(self):
        with zipfile.ZipFile(self.fixture, 'w') as z:
            z.writestr('vosk-model-small-ru-0.22/uuid', 'not enough')
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertTrue((self.model / 'old-marker').exists())

    def test_structurally_complete_fixture_installs_without_extra_nesting(self):
        with zipfile.ZipFile(self.fixture, 'w') as z:
            for file in ['am/final.mdl', 'conf/mfcc.conf', 'graph/HCLr.fst', 'graph/Gr.fst']:
                z.writestr('vosk-model-small-ru-0.22/' + file, 'synthetic fixture')
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.model / 'am/final.mdl').is_file())
        self.assertTrue((self.model / 'uuid').read_text().strip())
        self.assertFalse((self.model / 'old-marker').exists())
        self.assertFalse(list(self.model.parent.glob('.model-stage.*')))

if __name__ == '__main__':
    unittest.main()
