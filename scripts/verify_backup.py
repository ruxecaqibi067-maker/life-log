"""Compare synthetic acceptance backups without printing record or photo content."""
import argparse
import hashlib
import json
import zipfile


def inspect(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        assert len(names) == len(set(names)), 'Duplicate ZIP entry'
        assert len(entries) <= 10002, 'Too many ZIP entries'
        assert sum(entry.file_size for entry in entries) <= 2 * 1024**3, 'Expanded size limit'
        assert archive.getinfo('manifest.json').file_size <= 4 * 1024**2, 'Manifest size limit'
        assert archive.getinfo('data.json').file_size <= 32 * 1024**2, 'Data size limit'
        manifest = json.loads(archive.read('manifest.json'))
        assert manifest['formatVersion'] in (1, 2), 'Unknown format'
        files = manifest['files']
        assert len(files) == len({item['path'] for item in files}), 'Duplicate manifest path'
        assert {item['path'] for item in files} == set(names) - {'manifest.json'}, 'Incomplete manifest'
        hashes = {}
        for item in files:
            name = item['path']
            assert '\\' not in name and not name.startswith('/') and '..' not in name.split('/'), 'Unsafe path'
            assert archive.getinfo(name).file_size == item['byteSize'], 'Size mismatch'
            digest = hashlib.sha256()
            with archive.open(name) as stream:
                for chunk in iter(lambda: stream.read(65536), b''):
                    digest.update(chunk)
            assert digest.hexdigest() == item['sha256'], 'Hash mismatch'
            hashes[name] = digest.hexdigest()
        data = json.loads(archive.read('data.json'))
        assert data['dataVersion'] == manifest['formatVersion'], 'Manifest/data version mismatch'
        counts = {key: len(data[key]) for key in manifest['counts']}
        assert counts == manifest['counts'], 'Count mismatch'
        for key, value in data.items():
            if isinstance(value, list):
                assert len(value) == len({row['id'] for row in value}), 'Duplicate ID'
                value.sort(key=lambda row: row['id'])
        return data, {name: sha for name, sha in hashes.items() if name.startswith('media/')}, counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before')
    parser.add_argument('after')
    parser.add_argument('--ignore-export-clock', action='store_true',
                        help='Allow only settings export timestamp and updatedAt timestamp to change')
    parser.add_argument('--allow-v1-to-v2', action='store_true',
                        help='Compare a v1 backup with its additive v2 migration (satietyText=null)')
    args = parser.parse_args()
    before, before_photos, counts = inspect(args.before)
    after, after_photos, after_counts = inspect(args.after)
    versions = [before['dataVersion'], after['dataVersion']]
    if args.allow_v1_to_v2:
        assert versions == [1, 2], 'Expected exactly a v1 to v2 comparison'
        assert all(row.get('satietyText') is None for row in before['meals']), 'Unexpected v1 custom text'
        before['dataVersion'] = 2
        for row in before['meals']:
            row['satietyText'] = None
    if args.ignore_export_clock:
        for data in (before, after):
            for key in ('lastSuccessfulExportAtUtcMs', 'updatedAtUtcMs'):
                data['settings'].pop(key, None)
    assert before == after, 'Record/settings semantics differ'
    assert before_photos == after_photos, 'Photo bytes differ'
    assert counts == after_counts, 'Counts differ'
    print(json.dumps({'result': 'PASS', 'counts': counts,
                      'identicalPhotos': len(before_photos),
                      'comparedVersions': versions,
                      'ignoredExportClock': args.ignore_export_clock}))


if __name__ == '__main__':
    main()
