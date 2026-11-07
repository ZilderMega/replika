"""Extract timed human words from the official AMI manual annotation archive (CC BY 4.0)."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = 'https://groups.inf.ed.ac.uk/ami/AMICorpusAnnotations/ami_public_manual_1.6.2.zip'

def main():
    archive = ROOT / '.deps/ami-public-manual-1.6.2.zip'
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != 'b56e5babb2496b8795deeeda7e71178d7fbc9963f94276cf2a3f4b56ebbc9f9d':
        raise ValueError('AMI manual annotation archive checksum mismatch')
    with zipfile.ZipFile(archive) as source:
        for stem, begin, end in [('ami-development', 49, 79), ('ami-holdout', 79, 110)]:
            words = []
            for speaker in 'ABCD':
                path = f'words/ES2002a.{speaker}.words.xml'
                for word in ET.fromstring(source.read(path)).iter('w'):
                    if word.get('punc') == 'true' or word.get('starttime') is None or word.get('endtime') is None or not word.text:
                        continue
                    a, b = float(word.get('starttime')), float(word.get('endtime'))
                    if begin <= (a+b)/2 < end:
                        words.append(dict(text=word.text, start_ms=max(0, round((a-begin)*1000)),
                                          end_ms=min((end-begin)*1000, round((b-begin)*1000)), speaker_id=speaker))
            words.sort(key=lambda w: (w['start_ms'], w['end_ms'], w['speaker_id']))
            record = dict(fixture=stem+'.wav', source=SOURCE, archive_sha256=digest,
                          original_start_ms=begin*1000, license='CC BY 4.0; AMI Consortium',
                          annotation='Human word times and transcription, punctuation XML nodes excluded. Simultaneous words retained; not ASR output.', words=words)
            (ROOT/'artifacts/test-results/corpus'/f'{stem}-words.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
            print(stem, len(words), 'human timed words')

if __name__ == '__main__':
    main()
