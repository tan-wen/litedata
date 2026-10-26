#!/usr/bin/env python3
"""Release-side builder: JDK25/Maven+Docker required here, never on the target server."""
import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import tarfile
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

SOURCE = Path(__file__).resolve().parent
REPO = SOURCE.parents[1]


def run(*args, **kwargs):
    return subprocess.run(list(args), cwd=REPO, check=True, **kwargs)


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true', help='Use an already verified target JAR')
    parser.add_argument('--engine-image', help='Use an existing DataX-equipped engine image instead of rebuilding it')
    parser.add_argument('--with-images', action='store_true', help='Include all external runtime images for offline install')
    parser.add_argument('--output', type=Path, default=REPO / 'output/production')
    args = parser.parse_args()
    if not args.skip_build:
        wrapper = str(REPO / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
        run(wrapper, '-B', '-ntp', 'verify')
    jars = [p for p in (REPO / 'target').glob('litedata-*.jar') if not p.name.endswith('.jar.original')]
    if len(jars) != 1:
        raise SystemExit('Expected exactly one Spring Boot target/litedata-*.jar')
    image = args.engine_image or 'litedata/engine-build:3.4.0-v1'
    if not args.engine_image:
        run('docker', 'build', '-t', image, '-f', 'deploy/dolphinscheduler/Dockerfile', '.')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    name = 'litedata-0.1.0-compose-' + datetime.now().strftime('%Y%m%d-%H%M%S')
    stage = output / name
    stage.mkdir()
    for directory in ('bin', 'config', 'engine'):
        shutil.copytree(SOURCE / directory, stage / directory, ignore=shutil.ignore_patterns('__pycache__'))
    for filename in ('compose.yaml', '.env.example', '.dockerignore', 'README.md', 'VERIFICATION.md'):
        shutil.copy2(SOURCE / filename, stage / filename)
    (stage / 'app').mkdir()
    (stage / 'artifacts').mkdir()
    shutil.copy2(jars[0], stage / 'app/litedata.jar')
    for filename in ('LICENSE', 'NOTICE'):
        shutil.copy2(REPO / filename, stage / filename)
    shutil.copy2(REPO / 'deploy/mysql-platform/dolphinscheduler_mysql.sql', stage / 'engine/dolphinscheduler_mysql.sql')
    for filename in ('run_pipeline.py', 'run_sql_task.py'):
        shutil.copy2(REPO / 'deploy/dolphinscheduler' / filename, stage / 'engine' / filename)
    # Extract binaries without running the donor's daemon or mounting any user credentials.
    with tempfile.TemporaryDirectory(prefix='litedata-release-') as temp:
        root = Path(temp)
        container = subprocess.check_output(['docker', 'create', image], text=True).strip()
        try:
            for source, destination in (
                ('/opt/datax', root / 'datax'),
                ('/opt/dolphinscheduler/standalone-server/libs/mysql-connector-j-8.4.0.jar', root / 'mysql-connector-j-8.4.0.jar'),
                ('/opt/dolphinscheduler/plugins/task-plugins/dolphinscheduler-task-shell.jar', root / 'dolphinscheduler-task-shell.jar'),
            ):
                run('docker', 'cp', container + ':' + source, str(destination))
        finally:
            run('docker', 'rm', container, stdout=subprocess.DEVNULL)
        def linux_permissions(entry):
            entry.uid = entry.gid = 0
            entry.uname = entry.gname = 'root'
            entry.mode = 0o755 if entry.isdir() or entry.name.endswith(('.sh', '.py')) else 0o644
            return entry
        with tarfile.open(stage / 'artifacts/engine-runtime.tar.gz', 'w:gz') as archive:
            archive.add(root / 'datax', arcname='datax', filter=linux_permissions)
            for filename in ('mysql-connector-j-8.4.0.jar', 'dolphinscheduler-task-shell.jar'):
                archive.add(root / filename, arcname='litedata-runtime/' + filename, filter=linux_permissions)
    for path in stage.rglob('*'):
        if path.is_file() and path.suffix in ('.sh', '.py', '.yml', '.yaml', '.md', '.cnf', '.conf', '.sql'):
            path.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
        if path.is_file():
            path.chmod(0o755 if path.suffix == '.sh' else 0o644)
    donor = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    (stage / 'BUILD-INFO.json').write_text(json.dumps({
        'builtAtUtc': datetime.now(timezone.utc).isoformat(), 'sourceCommit': commit,
        'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=REPO)),
        'jar': jars[0].name, 'java': 25, 'scheduler': '3.4.0', 'doris': '4.1.3',
        'dataxRef': '9a1f88751e24314b083a74f1b83ef56d69ce98bd',
        'engineDonorImageId': donor['Id'], 'engineDonorRepoDigests': donor.get('RepoDigests', []),
        'mavenVerifyRunByPackager': not args.skip_build,
    }, ensure_ascii=False, indent=2), encoding='utf-8')
    if args.with_images:
        images = ['mysql:8.4.11', 'eclipse-temurin:25-jre', 'apache/doris:all-in-one-4.1.3']
        for external in images:
            # Pulls are intentional for a newly assembled offline package.
            run('docker', 'pull', external)
        run('docker', 'build', '-t', 'litedata/scheduler:3.4.0-v1', '-f', str(stage / 'engine/Dockerfile'), str(stage))
        (stage / 'images').mkdir()
        run('docker', 'save', '-o', str(stage / 'images/runtime-images.tar'), *images, 'litedata/scheduler:3.4.0-v1')
    lines = []
    for path in sorted(stage.rglob('*')):
        # Operators may tune config; immutable runtime payloads remain verified at every start.
        if path.is_file() and path.relative_to(stage).parts[0] not in ('config',):
            lines.append(digest_file(path) + '  ' + path.relative_to(stage).as_posix())
    (stage / 'SHA256SUMS').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    archive_path = output / (name + '.zip')
    with zipfile.ZipFile(archive_path, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in stage.rglob('*'):
            if path.is_file():
                entry = zipfile.ZipInfo.from_file(path, name + '/' + path.relative_to(stage).as_posix())
                # Windows chmod does not preserve POSIX 0644. Explicitly encode Linux permissions,
                # otherwise MySQL ignores a world-writable mounted configuration after unzip.
                entry.create_system = 3
                entry.external_attr = (stat.S_IFREG | (0o755 if path.suffix == '.sh' else 0o644)) << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                with path.open('rb') as src, archive.open(entry, 'w', force_zip64=True) as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
    digest = digest_file(archive_path)
    archive_path.with_suffix('.zip.sha256').write_text(digest + '  ' + archive_path.name + '\n')
    print('Release directory: ' + str(stage))
    print('Deployment ZIP: ' + str(archive_path))


if __name__ == '__main__':
    main()
