"""Package source, the installable APK, public demo and measured evidence; no private audio/cache."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    files = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    selected = {name for name in files if name and not name.startswith(('artifacts/', '.idea/', '.deps/', '.models/')) and name != 'local.properties'}
    selected.add('artifacts/replika-0.6-bundled-debug.apk')
    for folder in ('demo', 'test-results'):
        selected.update(p.relative_to(ROOT).as_posix() for p in (ROOT / 'artifacts' / folder).rglob('*') if p.is_file())
    # Never silently ship a missing source file or an earlier APK.
    apk = ROOT / 'artifacts/replika-0.6-bundled-debug.apk'
    assert sha256(apk) == sha256(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'), 'Rebuild/copy final APK first'
    manifest = {name: sha256(ROOT / name) for name in sorted(selected)}
    output = ROOT / 'artifacts/replika-0.6-submission.zip'
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for name in sorted(selected):
            # APK/MP4 are already compressed; keep packaging fast and reproducible in content.
            archive.write(ROOT / name, 'replika/' + name, compress_type=zipfile.ZIP_STORED if name.endswith(('.apk', '.mp4', '.png')) else zipfile.ZIP_DEFLATED)
        archive.writestr('replika/SHA256SUMS.json', json.dumps(manifest, indent=2, ensure_ascii=False))
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        assert 'replika/docs/TASK_REQUIREMENTS.md' in archive.namelist()
    print(json.dumps({'path': str(output), 'files': len(selected), 'bytes': output.stat().st_size, 'sha256': sha256(output)}, indent=2))

if __name__ == '__main__':
    main()
