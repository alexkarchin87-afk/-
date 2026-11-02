"""Exercise the guard against real repository mutations in isolated copies."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('architecture_map', ROOT / 'tools/generate_architecture_map.py')
index = importlib.util.module_from_spec(spec)
spec.loader.exec_module(index)


class ArchitectureGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Copy only index inputs, not builds, model assets or caches.
        for pattern in ('settings.gradle.kts', '**/build.gradle.kts', '**/src/main/java/**/*.kt',
                        '**/src/test/**/*.kt', '**/src/main/AndroidManifest.xml', 'architecture/**/*.md',
                        'architecture/components.json', 'ARCHITECTURE_MAP.md'):
            for path in ROOT.glob(pattern):
                target = self.root / path.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)

    def registry(self, mutate):
        path = self.root / 'architecture/components.json'
        value = json.loads(path.read_text())
        mutate(value['components'])
        path.write_text(json.dumps(value))

    def errors(self):
        return '\n'.join(index.inspect(self.root)[-1])

    def test_current_sources_and_deterministic_render(self):
        data = index.inspect(self.root)
        self.assertEqual([], data[-1])
        self.assertEqual((ROOT / 'ARCHITECTURE_MAP.md').read_text(), index.render(data))

    def test_missing_source_and_test_and_readme(self):
        self.registry(lambda cs: cs[0].update(files=cs[0]['files'] + ['missing.kt'],
                                             tests=['missing-test.kt'], readme='missing.md'))
        errors = self.errors()
        for path in ('missing.kt', 'missing-test.kt', 'missing.md'):
            self.assertIn(path, errors)

    def test_dead_readme_link(self):
        path = self.root / 'architecture/notes/README.md'
        with path.open('a') as stream: stream.write('\n[missing](absent.md)\n')
        self.assertIn('Missing Markdown link', self.errors())

    def test_stale_map_cli(self):
        (self.root / 'ARCHITECTURE_MAP.md').write_text('stale')
        result = subprocess.run([sys.executable, str(ROOT / 'tools/generate_architecture_map.py'),
                                 '--root', str(self.root), '--check'], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn('is stale', result.stderr)

    def test_duplicate_owner(self):
        self.registry(lambda cs: cs[1]['files'].append(cs[0]['files'][0]))
        self.assertIn('Duplicate owner', self.errors())

    def test_unregistered_new_file(self):
        target = self.root / 'app/src/main/java/new/New.kt'
        target.parent.mkdir(parents=True)
        target.write_text('package new\nclass New')
        self.assertIn('Unowned source', self.errors())

    def test_forbidden_fully_qualified_dependency(self):
        path = self.root / 'app/src/main/java/com/voiceagent/oneplus13/notes/NotesBuilder.kt'
        with path.open('a') as stream:
            stream.write('\nprivate val forbidden: com.voiceagent.oneplus13.agent.AgentOrchestrator? = null\n')
        self.assertIn('Forbidden boundary: notes -> agent', self.errors())

    def test_forbidden_import(self):
        path = self.root / 'app/src/main/java/com/voiceagent/oneplus13/notes/NotesBuilder.kt'
        with path.open('a') as stream:
            stream.write('\nimport com.voiceagent.oneplus13.agent.AgentOrchestrator\n')
        self.assertIn('Forbidden boundary: notes -> agent', self.errors())

    def test_reverse_module_dependency(self):
        path = self.root / 'core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/STTEngine.kt'
        with path.open('a') as stream:
            stream.write('\nimport com.voiceagent.oneplus13.agent.AgentOrchestrator\n')
        self.assertIn('Undeclared module dependency', self.errors())

    def test_gradle_cycle(self):
        path = self.root / 'core/ai-engines/build.gradle.kts'
        with path.open('a') as stream: stream.write('\ndependencies { implementation(project(":app")) }\n')
        self.assertIn('Gradle cycle', self.errors())

    def test_manifest_missing_implementation(self):
        path = self.root / 'app/src/main/AndroidManifest.xml'
        path.write_text(path.read_text().replace('.MainActivity', '.MissingActivity'))
        self.assertIn('Manifest class absent', self.errors())

    def test_comments_and_strings_do_not_create_edges(self):
        path = self.root / 'app/src/main/java/com/voiceagent/oneplus13/notes/NotesBuilder.kt'
        with path.open('a') as stream:
            stream.write('\n// import com.voiceagent.oneplus13.agent.AgentOrchestrator\n'
                         'private val example = "com.voiceagent.oneplus13.agent.AgentOrchestrator"\n')
        self.assertEqual('', self.errors())


if __name__ == '__main__': unittest.main()
