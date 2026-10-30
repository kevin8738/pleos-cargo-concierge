"""Independent read-only audit of frozen synthetic input and actual core output."""
import csv
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
from collections import Counter

Q = Path(__file__).resolve().parent
LANE = Q / 'alert-analysis'
ROOT = Q.parents[2]
G = 9.80665
WHEELBASE = struct.unpack('f', struct.pack('f', 2.9))[0]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def audit(run_id):
    out = LANE / 'out' / run_id
    checks, errors, cases = [], [], []

    def check(ok, claim):
        checks.append(claim)
        if not ok:
            errors.append(claim)

    frozen = LANE / 'frozen-files.tsv'
    for line in frozen.read_text().splitlines():
        digest, name = line.split('\t')
        check(sha(ROOT / name) == digest, 'frozen:' + name)
    cert = json.loads((out / 'selftest.json').read_text())
    check(cert['status'] == 'PASS' and cert['freeze_sha256'] == sha(frozen), 'matching_selftest')
    summaries = [json.loads(x) for x in (out / 'summary.jsonl').read_text().splitlines()]
    check(len(summaries) == 54, 'all_54_cases_retained')
    check(len({(x['scenario'], x['preset']) for x in summaries}) == 54, 'unique_cases')
    for summary in summaries:
        label = summary['scenario'] + '/' + summary['preset']
        check(summary['status'] == 'PASS_COMPUTATIONAL_CHECKS_ONLY', label + ':execution')
        if summary['status'] != 'PASS_COMPUTATIONAL_CHECKS_ONLY':
            continue
        folder = out / 'scenarios' / summary['scenario'] / summary['preset']
        check(json.loads((folder / 'summary.json').read_text()) == summary, label + ':summary_copy')
        rows = [json.loads(x) for x in (folder / 'events.jsonl').read_text().splitlines()]
        check(len(rows) == summary['dispatches'], label + ':dispatch_count')
        check([x['seq'] for x in rows] == list(range(len(rows))), label + ':sequence')
        inp = LANE / 'inputs' / (summary['scenario'] + '.csv')
        check(sha(inp) == summary['input_sha256'], label + ':input_hash')
        with inp.open() as f:
            input_rows = list(csv.DictReader(f))
        motion = [x for x in rows if x['phase'] == 'driving' and x['action']['kind'] in ('speed', 'steering', 'brake')]
        check(len(motion) == 3 * len(input_rows), label + ':input_event_count')
        for sample, group in zip(input_rows, zip(*[iter(motion)] * 3)):
            a, b, c = group
            expected_time = 1000 + int(sample['t_ms'])
            check(a['now_ms'] == b['now_ms'] == c['now_ms'] == expected_time
                  and a['action']['kind'] == 'speed' and b['action']['kind'] == 'steering' and c['action']['kind'] == 'brake'
                  and a['action']['mps'] == float(sample['speed_mps'])
                  and b['action']['degrees'] == float(sample['steering_deg'])
                  and c['action']['pressed'] == (sample['brake_pressed'] == 'true')
                  and c['action']['percent'] is None,
                  label + ':input_sequence:' + sample['t_ms'])
        # All frozen profiles have one source, >=50ms spacing and no gaps.
        # This independently evaluates the stated formula, not badge/audio policy.
        speed, steering, ema, previous_speed, previous_ms = 0.0, 0.0, 0.0, None, None
        max_error, computed_peak = 0.0, 0.0
        for row in motion:
            a = row['action']
            if a['kind'] == 'speed':
                speed = a['mps']
                if previous_ms is not None:
                    dt = (row['now_ms'] - previous_ms) / 1000
                    check(.05 <= dt <= .25, label + ':bounded_dt:' + str(row['seq']))
                    ema = .5 * ((speed - previous_speed) / dt) + .5 * ema
                previous_speed, previous_ms = speed, row['now_ms']
            elif a['kind'] == 'steering':
                steering = a['degrees']
            lateral = abs(speed * speed * math.tan(math.radians(steering / 15))) / WHEELBASE / G
            longitudinal = ema / G
            expected = (lateral, longitudinal, math.hypot(lateral, longitudinal))
            actual = row['G']
            check(actual is not None and len(actual) == 3, label + ':G_present:' + str(row['seq']))
            if actual is not None:
                max_error = max(max_error, *(abs(x-y) for x, y in zip(actual, expected)))
            computed_peak = max(computed_peak, expected[2])
        check(max_error < 1e-11, label + ':independent_G_arithmetic')
        check(abs(computed_peak - summary['maxG']) < 1e-11, label + ':independent_peak')
        speech, chime, candidates, suppressions = 0, 0, 0, Counter()
        last_audio, first_voice, first_total, first_trip = None, None, None, None
        for row in rows:
            if row['phase'] != 'driving':
                continue
            effects = row['effects']
            ds = sum(x['kind'] == 'Speak' for x in effects)
            dc = sum(x['kind'] == 'Chime' for x in effects)
            speech += ds
            chime += dc
            candidates += row['candidate_total']
            suppressions.update(row['suppressed_delta'])
            check(row['admitted_delta'] == [ds, dc] and row['cumulative_admitted'] == [speech, chime], label + ':counter:' + str(row['seq']))
            check(ds + dc <= 1 and speech <= 3 and speech + chime <= 6, label + ':budget:' + str(row['seq']))
            check(row['candidate_total'] == ds + dc + sum(row['suppressed_delta'].values()), label + ':conservation:' + str(row['seq']))
            if ds + dc:
                check(last_audio is None or row['now_ms'] - last_audio >= 8000, label + ':cooldown:' + str(row['seq']))
                last_audio = row['now_ms']
            if speech == 3 and first_voice is None:
                first_voice = row['now_ms']
            if speech + chime == 6 and first_total is None:
                first_total = row['now_ms']
            if row['suppressed_delta'].get('TRIP_LIMIT', 0) and first_trip is None:
                first_trip = row['now_ms']
        check((speech, chime, candidates, dict(suppressions)) == (summary['admitted_speech_requests'], summary['admitted_chime_requests'], summary['gate_candidate_requests'], summary['suppression_counts']), label + ':summary_totals')
        check((first_voice, first_total, first_trip) == (summary['first_voice3_ms'], summary['first_audible6_ms'], summary['first_trip_limit_suppression_ms']), label + ':first_budget_times')
        fresh = sum(bool(x['longitudinal_fresh']) for x in motion if x['action']['kind'] == 'speed')
        check(fresh == summary['fresh_differences'], label + ':fresh_difference_count')
        check(summary['real_world_comparison'] == 'UNAVAILABLE_SYNTHETIC' and summary['field_observations'] == summary['listening_observations'] == 0, label + ':scope')
        cases.append({'case': label, 'max_g_error': max_error, 'independent_maxG': computed_peak,
                      'speech_requests': speech, 'chime_requests': chime, 'candidate_requests': candidates,
                      'first_voice3_ms': first_voice, 'first_audible6_ms': first_total, 'first_trip_limit_ms': first_trip})
    for line in (out / 'result-manifest.tsv').read_text().splitlines():
        digest, name = line.split('\t')
        check(sha(out / name) == digest, 'result_hash:' + name)
    return {'status': 'PASS_COMPUTATIONAL_SCOPE' if not errors else 'FAIL', 'run_id': run_id,
            'freeze_sha256': sha(frozen), 'checks': len(checks), 'errors': errors, 'cases': cases,
            'physical_accuracy_or_listening_validated': False, 'prior_182_test_count_changed': False}


if __name__ == '__main__':
    result = audit(sys.argv[1])
    target = Q / ('alert-independent-' + sys.argv[1] + '.json')
    with target.open('x') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(json.dumps({k: result[k] for k in ('status', 'run_id', 'checks', 'errors')}, ensure_ascii=False))
    raise SystemExit(bool(result['errors']))
