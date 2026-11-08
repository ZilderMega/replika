"""Package signed release, public source, documentation and reproducible evidence."""
import hashlib
import json
import re
import datetime
import xml.etree.ElementTree as ET
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = '0.6.0'

def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def write_zip(output, names):
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        hashes = {}
        for name in sorted(names):
            file=ROOT/name
            if not file.is_file():
                raise FileNotFoundError(name)
            hashes[name]=sha256(file)
            archive.write(file,'replika/'+name,compress_type=zipfile.ZIP_STORED if file.suffix in ('.apk','.mp4','.png','.pptx','.pdf') else zipfile.ZIP_DEFLATED)
        archive.writestr('replika/SHA256SUMS.json',json.dumps(hashes,ensure_ascii=False,indent=2))
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
    return {'name':output.name,'bytes':output.stat().st_size,'sha256':sha256(output)}

def main():
    artifacts=ROOT/'artifacts'
    apk=artifacts/f'replika-{VERSION}-release.apk'
    assert sha256(apk)==sha256(ROOT/'app/build/outputs/apk/release/app-release.apk'),'Rebuild/copy final release first'
    with zipfile.ZipFile(apk) as bundle:
        manifest=json.loads(bundle.read('assets/models/manifest.json'))['sha256']
        manifest.update(json.loads((ROOT/'docs/model-manifest.json').read_text('utf-8'))['extra_sha256'])
        for name, expected in manifest.items():
            with bundle.open('assets/models/'+name) as stream:
                assert hashlib.file_digest(stream,'sha256').hexdigest()==expected, f'Model checksum mismatch: {name}'
    signature=(artifacts/'test-results/submission-release-signature.txt').read_text('utf-8-sig')
    assert 'Verifies' in signature
    certificate=re.search(r'certificate SHA-256 digest: ([0-9a-f]{64})',signature).group(1)
    badging=(artifacts/'test-results/submission-release-badging.txt').read_text('utf-8-sig')
    assert 'application-debuggable' not in badging
    junit=[ET.parse(p).getroot() for p in (ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml')]
    assert junit and sum(int(r.get('failures',0))+int(r.get('errors',0)) for r in junit)==0
    release={'version':VERSION,'application_id':'team.replika','apk':{'name':apk.name,'bytes':apk.stat().st_size,'sha256':sha256(apk),'debuggable':False,'certificate_sha256':certificate,'bundled_models_verified':len(manifest)},'source_branch':subprocess.check_output(['git','branch','--show-current'],cwd=ROOT,text=True).strip(),'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'packaged_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'release_jvm_tests':sum(int(r.get('tests',0)) for r in junit),'validation':'docs/SUBMISSION_VALIDATION.md','video_status':'Existing public debug demonstrations included; author records fresh release video separately.'}
    (artifacts/'release-manifest.json').write_text(json.dumps(release,ensure_ascii=False,indent=2),'utf-8')
    raw=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    source={n for n in raw if n and not n.startswith(('artifacts/','.idea/','.deps/','.models/','.private-validation/')) and n!='local.properties'}
    forbidden=lambda n: any(p in n.split('/') for p in ('.git','.private-validation','.models','.deps','build','node_modules')) or n.endswith(('.jks','.keystore')) or n.split('/')[-1] in ('.env','signing.json','local.properties')
    assert not any(forbidden(n) for n in source)
    source_zip=artifacts/f'replika-{VERSION}-source.zip'
    src_info=write_zip(source_zip,source)
    selected=set(source)
    selected.add(apk.relative_to(ROOT).as_posix())
    selected.add('artifacts/release-manifest.json')
    for folder in ('demo','test-results'):
        selected.update(p.relative_to(ROOT).as_posix() for p in (artifacts/folder).rglob('*') if p.is_file())
    assert not any(forbidden(n) for n in selected)
    output=artifacts/f'replika-{VERSION}-submission.zip'
    result={'source':src_info,'submission':write_zip(output,selected),'apk':{'name':apk.name,'bytes':apk.stat().st_size,'sha256':sha256(apk)}}
    (artifacts/'package-manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
