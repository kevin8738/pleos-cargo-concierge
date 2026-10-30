#!/usr/bin/env python3
"""Read-only, stdlib-only publication integrity check. No ADB/network/imported harness."""
from pathlib import Path
import hashlib
import json
import re
import sys
import wave


def main():
    base = Path(__file__).resolve().parents[1]
    manifest = json.loads((base/'manifest.json').read_text())
    checks = []
    def check(name, condition):
        if not condition:
            raise ValueError(name)
        checks.append(name)
    check('unique publication paths', len({r['publishedPath'] for r in manifest['files']}) == len(manifest['files']))
    for row in manifest['files']:
        path = base/row['publishedPath']
        check('safe path '+row['publishedPath'], path.resolve().is_relative_to(base.resolve()) and path.is_file() and not path.is_symlink())
        raw = path.read_bytes()
        check('published bytes '+row['publishedPath'], len(raw) == row['publishedBytes'] and hashlib.sha256(raw).hexdigest() == row['publishedSha256'])
        if row['byteIdentical']:
            check('source/public identity '+row['publishedPath'], row['sourceSha256'] == row['publishedSha256'])
    result = json.loads((base/'integration-01/integration-result.json').read_text())
    expected = {'motionOracle', 'clockEndpointsConsistent', 'mixedPcmSignalInEachResponse', 'parkedArrivalAndReport'}
    check('original overall false', result['boundedObservationComplete'] is False)
    check('original four false fields', {k for k,v in result['componentChecks'].items() if v is False} == expected)
    check('native attribution/overlap/focus unknown', all(result[k] is None for k in ('nativeSpeechAttributed', 'actualAcousticOverlap', 'audioFocusPriorityValidated')))
    check('original cleanup true', json.loads((base/'integration-01/cleanup.json').read_text())['verified'] is True)
    audit = json.loads((base/'integration-verification/verification.json').read_text())
    check('independent consistency result preserved', len(audit['checks']) == 57 and audit['verification_error_count'] == 0 and audit['original_verdict'] is False)
    capture = base/'integration-01/capture'
    metadata = json.loads((capture/'metadata.json').read_text())
    pcm = (capture/'audio.s16le.pcm').read_bytes()
    check('original PCM hash', hashlib.sha256(pcm).hexdigest() == metadata['pcm_sha256'])
    check('original packet count/bytes', len(metadata['packets']) == metadata['packet_count'] == 15545
          and sum(p['bytes'] for p in metadata['packets']) == len(pcm) == 9026060)
    with wave.open(str(capture/'audio.wav'), 'rb') as wav:
        check('WAV exact received PCM', (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (2, 2, 48000)
              and wav.readframes(wav.getnframes()) == pcm)
    check('sparse duration preserved', metadata['pcm_duration_seconds'] == 47.010729166666664
          and metadata['wall_seconds'] == 179.977896 and metadata['requested_seconds'] == 180)
    for md in base.rglob('*.md'):
        for target in re.findall(r'\]\(([^)]+)\)', md.read_text()):
            if '://' not in target and not target.startswith('#'):
                check('local Markdown link '+str(md.relative_to(base))+':'+target, (md.parent/target.split('#')[0]).is_file())
    print(json.dumps({'publicationIntegrityChecks': len(checks), 'errors': 0,
                      'originalOverall': False, 'applicationIntegrationPass': False,
                      'mediaApprovalGrantedByThisCheck': False, 'runtimeInvoked': False}))


if __name__ == '__main__':
    main()
