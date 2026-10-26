"""Compare synthetic acceptance backups without printing record or photo content."""
import argparse
from datetime import date
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
        assert manifest['formatVersion'] in (1, 2, 3, 4, 5, 6, 7), 'Unknown format'
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
        settings = data['settings']
        if data['dataVersion'] >= 3:
            assert 'targetSleepMinutes' in settings, 'Missing sleep goal'
        goal = settings.get('targetSleepMinutes')
        assert goal is None or (type(goal) is int and 1 <= goal <= 1440), 'Invalid sleep goal'
        version = data['dataVersion']
        if version >= 4:
            assert 'targetWorkMinutes' in settings, 'Missing work goal'
            assert isinstance(data.get('workRecords'), list), 'Missing work records'
        work_goal = settings.get('targetWorkMinutes')
        assert work_goal is None or (type(work_goal) is int and 1 <= work_goal <= 1440), 'Invalid work goal'
        if version >= 5:
            for row in data['sessions']:
                assert 'effort' in row and 'effortText' in row, 'Missing workout effort'
                effort, text = row['effort'], row['effortText']
                assert effort is None or effort in ('EASY', 'MODERATE', 'HARD', 'VERY_HARD', 'CUSTOM'), 'Invalid effort'
                if effort == 'CUSTOM':
                    assert type(text) is str and text == text.strip() and 1 <= len(text) <= 40, 'Invalid custom effort'
                else:
                    assert text is None, 'Unexpected custom effort'
        keys = {'meals', 'sleeps', 'exercises', 'sessions', 'items', 'sets', 'media'}
        if version >= 4:
            keys.add('workRecords')
        if version >= 6:
            keys.update(('todos', 'habits'))
            validate_todos(data)
        if version >= 7:
            keys.add('mealPhotos')
            validate_meal_photos(data)
        assert set(manifest['counts']) == keys, 'Missing or unknown count'
        counts = {key: len(data[key]) for key in keys}
        assert counts == manifest['counts'], 'Count mismatch'
        assert sum(counts.get(key, 0) for key in ('meals', 'sleeps', 'sessions', 'workRecords', 'todos', 'habits')) <= 10000, 'Record limit'
        for key, value in data.items():
            if isinstance(value, list):
                if key == 'mealPhotos':
                    assert len(value) == len({(row['mealId'], row['photoId']) for row in value}), 'Duplicate meal photo'
                    value.sort(key=lambda row: (row['mealId'], row['sortOrder']))
                else:
                    assert len(value) == len({row['id'] for row in value}), 'Duplicate ID'
                    value.sort(key=lambda row: row['id'])
        return data, {name: sha for name, sha in hashes.items() if name.startswith('media/')}, counts


def validate_meal_photos(data):
    assert isinstance(data.get('mealPhotos'), list), 'Missing meal photos'
    meals = {row['id']: row for row in data['meals']}
    media = {row['id'] for row in data['media']}
    links = {}
    for row in data['mealPhotos']:
        assert row['mealId'] in meals and row['photoId'] in media, 'Invalid meal photo reference'
        assert type(row['sortOrder']) is int and 0 <= row['sortOrder'] <= 8, 'Invalid photo order'
        links.setdefault(row['mealId'], []).append(row)
    for meal in meals.values():
        photos = sorted(links.get(meal['id'], []), key=lambda p: p['sortOrder'])
        assert len(photos) <= 9 and [p['sortOrder'] for p in photos] == list(range(len(photos))), 'Photo order gap/duplicate'
        assert len({p['photoId'] for p in photos}) == len(photos), 'Duplicate photo'
        assert meal.get('photoId') == (photos[0]['photoId'] if photos else None), 'Cover mismatch'
    assert {row['photoId'] for row in data['mealPhotos']} == media, 'Unreferenced/missing media'


def validate_todos(data):
    def local_date(value):
        assert type(value) is str and len(value) == 10, 'Invalid local date'
        parsed = date.fromisoformat(value)
        assert date(1900, 1, 1) <= parsed <= date(2199, 12, 31) and parsed.isoformat() == value, 'Local date range'

    def common(row):
        assert type(row['title']) is str and row['title'] == row['title'].strip() and 1 <= len(row['title']) <= 120, 'Invalid title'
        minute = row['scheduledMinute']
        assert minute is None or type(minute) is int and 0 <= minute <= 1439, 'Invalid minute'
        assert type(row['createdAtUtcMs']) is int and type(row['updatedAtUtcMs']) is int, 'Invalid timestamp'
        assert row['createdAtUtcMs'] <= row['updatedAtUtcMs'], 'Invalid timestamps'

    assert isinstance(data['todos'], list) and isinstance(data['habits'], list), 'Missing todo/habit arrays'
    habits = {row['id']: row for row in data['habits']}
    for row in habits.values():
        common(row)
        local_date(row['startDate'])
        assert row['status'] in ('ACTIVE', 'PAUSED', 'ARCHIVED'), 'Invalid habit status'
        frequency, interval, mask = row['frequency'], row['intervalDays'], row['weekdaysMask']
        assert type(interval) is int and type(mask) is int, 'Invalid frequency values'
        assert (frequency == 'DAILY' and interval == 1 and mask == 0 or
                frequency == 'INTERVAL' and 1 <= interval <= 365 and mask == 0 or
                frequency == 'WEEKLY' and interval == 1 and 1 <= mask <= 127), 'Invalid frequency'
    generated = set()
    for row in data['todos']:
        common(row)
        local_date(row['localDate'])
        habit_id, habit_date = row['habitId'], row['habitDate']
        assert (habit_id is None) == (habit_date is None), 'Incomplete habit source'
        if habit_id is not None:
            local_date(habit_date)
            assert habit_id in habits and (habit_id, habit_date) not in generated, 'Invalid or duplicate habit source'
            generated.add((habit_id, habit_date))
        for key in ('completedAtUtcMs', 'deletedAtUtcMs'):
            value = row[key]
            assert value is None or type(value) is int and row['createdAtUtcMs'] <= value <= row['updatedAtUtcMs'], 'Invalid todo timestamp'


def migrate_for_comparison(data, target_version):
    """Only defined additive fields; never remove or normalize record values."""
    version = data['dataVersion']
    assert 1 <= version < target_version <= 7, 'Undefined migration'
    if version == 1:
        assert all(row.get('satietyText') is None for row in data['meals']), 'Unexpected v1 custom text'
        for row in data['meals']:
            row['satietyText'] = None
    if version < 3 and target_version >= 3:
        assert data['settings'].get('targetSleepMinutes') is None, 'Unexpected legacy sleep goal'
        data['settings']['targetSleepMinutes'] = None
    if version < 4 and target_version >= 4:
        assert not data.get('workRecords'), 'Unexpected legacy work records'
        assert data['settings'].get('targetWorkMinutes') is None, 'Unexpected legacy work goal'
        data['workRecords'] = []
        data['settings']['targetWorkMinutes'] = None
    if version < 5 and target_version >= 5:
        for row in data['sessions']:
            assert row.get('effort') is None and row.get('effortText') is None, 'Unexpected legacy effort'
            row['effort'] = None
            row['effortText'] = None
    if version < 6 and target_version >= 6:
        assert not data.get('todos') and not data.get('habits'), 'Unexpected legacy todo/habit data'
        data['todos'] = []
        data['habits'] = []
    if version < 7 and target_version >= 7:
        assert not data.get('mealPhotos'), 'Unexpected legacy meal photos'
        data['mealPhotos'] = [{'mealId': row['id'], 'photoId': row['photoId'], 'sortOrder': 0} for row in data['meals'] if row.get('photoId') is not None]
        data['mealPhotos'].sort(key=lambda row: (row['mealId'], row['sortOrder']))
    data['dataVersion'] = target_version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before')
    parser.add_argument('after')
    parser.add_argument('--ignore-export-clock', action='store_true',
                        help='Allow only settings export timestamp and updatedAt timestamp to change')
    migrations = parser.add_mutually_exclusive_group()
    migrations.add_argument('--allow-v1-to-v2', action='store_true',
                        help='Compare a v1 backup with its additive v2 migration (satietyText=null)')
    migrations.add_argument('--allow-v2-to-v3', action='store_true',
                            help='Compare v2 with its additive v3 migration (targetSleepMinutes=null)')
    migrations.add_argument('--allow-v1-to-v3', action='store_true',
                            help='Compare v1 with the defined v1->v2->v3 nullable additions')
    for version in (1, 2, 3):
        migrations.add_argument(f'--allow-v{version}-to-v4', action='store_true',
                                help=f'Compare v{version} with its defined forward migration to v4')
    for version in (1, 2, 3, 4):
        migrations.add_argument(f'--allow-v{version}-to-v5', action='store_true',
                                help=f'Compare v{version} with additive workout effort fields in v5')
    for version in (1, 2, 3, 4, 5):
        migrations.add_argument(f'--allow-v{version}-to-v6', action='store_true',
                                help=f'Compare v{version} with additive empty todo/habit tables in v6')
    for version in range(1, 7):
        migrations.add_argument(f'--allow-v{version}-to-v7', action='store_true', help='Compare ordered single-photo migration to v7')
    args = parser.parse_args()
    before, before_photos, counts = inspect(args.before)
    after, after_photos, after_counts = inspect(args.after)
    versions = [before['dataVersion'], after['dataVersion']]
    migration = ([1, 2] if args.allow_v1_to_v2 else [2, 3] if args.allow_v2_to_v3
                 else [1, 3] if args.allow_v1_to_v3 else [1, 4] if args.allow_v1_to_v4
                 else [2, 4] if args.allow_v2_to_v4 else [3, 4] if args.allow_v3_to_v4 else None)
    for version in (1, 2, 3, 4):
        if getattr(args, f'allow_v{version}_to_v5'):
            migration = [version, 5]
    for version in (1, 2, 3, 4, 5):
        if getattr(args, f'allow_v{version}_to_v6'):
            migration = [version, 6]
    for version in range(1, 7):
        if getattr(args, f'allow_v{version}_to_v7'):
            migration = [version, 7]
    if migration:
        assert versions == migration, 'Wrong versions for explicitly requested migration'
        migrate_for_comparison(before, migration[1])
        if migration[1] >= 7:
            counts['mealPhotos'] = len(before['mealPhotos'])
        if migration[0] < 4 and migration[1] >= 4:
            counts['workRecords'] = 0
        if migration[0] < 6 and migration[1] >= 6:
            counts['todos'] = 0
            counts['habits'] = 0
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
