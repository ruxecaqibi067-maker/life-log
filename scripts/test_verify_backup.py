"""Synthetic regression checks for the explicit additive backup comparisons."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import verify_backup


class VerifyBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def archive(self, name, version, goal=None, text='synthetic', photo=b'photo'):
        data = {'dataVersion': version, 'meals': [{'id': 'meal', 'text': text}],
                **{key: [] for key in ('sleeps', 'exercises', 'sessions', 'items', 'sets', 'media')},
                'settings': {'themeMode': 'DARK', 'lastSuccessfulExportAtUtcMs': 1, 'updatedAtUtcMs': 2}}
        if version >= 2:
            data['meals'][0]['satietyText'] = None
        if version >= 3:
            data['settings']['targetSleepMinutes'] = goal
        files = {'data.json': json.dumps(data).encode(), 'media/synthetic.jpg': photo}
        manifest = {'formatVersion': version, 'counts': {key: len(value) for key, value in data.items() if isinstance(value, list)},
                    'files': [{'path': key, 'byteSize': len(value), 'sha256': hashlib.sha256(value).hexdigest()}
                              for key, value in files.items()]}
        path = self.root / name
        with zipfile.ZipFile(path, 'w') as archive:
            for key, value in files.items():
                archive.writestr(key, value)
            archive.writestr('manifest.json', json.dumps(manifest))
        return path

    def compare(self, before, after, *flags):
        return subprocess.run([sys.executable, str(Path(verify_backup.__file__)), str(before), str(after), *flags],
                              capture_output=True, text=True)

    def test_all_defined_forward_migrations_require_explicit_flags(self):
        for old, new in ((1, 2), (2, 3), (1, 3)):
            before = self.archive(f'before-{old}.zip', old)
            after = self.archive(f'after-{new}.zip', new)
            self.assertNotEqual(0, self.compare(before, after).returncode)
            result = self.compare(before, after, f'--allow-v{old}-to-v{new}')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([old, new], json.loads(result.stdout)['comparedVersions'])

    def test_actual_records_and_photos_still_must_match(self):
        before = self.archive('before.zip', 2)
        for name, changes in (('record', {'text': 'changed'}), ('photo', {'photo': b'changed'}), ('goal', {'goal': 480})):
            after = self.archive(f'{name}.zip', 3, **changes)
            self.assertNotEqual(0, self.compare(before, after, '--allow-v2-to-v3').returncode)

    def test_goal_types_and_range_are_strict(self):
        for index, value in enumerate((0, -1, 1441, 2147483648, 1.5, 1.0, True, '480', {})):
            with self.assertRaises(AssertionError):
                verify_backup.inspect(self.archive(f'bad-{index}.zip', 3, goal=value))
        for index, value in enumerate((None, 1, 480, 1440)):
            data, _, _ = verify_backup.inspect(self.archive(f'good-{index}.zip', 3, goal=value))
            self.assertEqual(value, data['settings']['targetSleepMinutes'])

    def test_wrong_direction_or_mismatched_flag_is_rejected(self):
        v1, v2, v3 = (self.archive(f'v{version}.zip', version) for version in (1, 2, 3))
        self.assertNotEqual(0, self.compare(v1, v2, '--allow-v1-to-v3').returncode)
        self.assertNotEqual(0, self.compare(v3, v2, '--allow-v2-to-v3').returncode)
        self.assertEqual(0, self.compare(v3, v3).returncode)


if __name__ == '__main__':
    unittest.main()
