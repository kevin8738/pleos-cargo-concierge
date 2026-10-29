"""Only prepare frozen mathematical inputs; never execute the app/core engine."""
from pathlib import Path
import hashlib
import math
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BASE_SPEED = [(0, 0), (12, 30), (108, 30), (120, 0)]
HARD_SPEED = [(0, 0), (20, 50), (60, 50), (61, 5), (70, 5), (85, 30), (108, 30), (120, 0)]
CASES = [(name, 100) for name in ('gentle', 'repeated_boundary', 'sustained_over', 'hard_brake')] + [('repeated_boundary', 50), ('repeated_boundary', 250)]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def segment(knots, t):
    for (a, x), (b, y) in zip(knots, knots[1:]):
        if a <= t < b:
            return x + (y - x) * (t - a) / (b - a), (y - x) / (b - a)
    return knots[-1][1], 0.0


def values(name, t):
    speed, slope = segment(HARD_SPEED if name == 'hard_brake' else BASE_SPEED, t)
    steer = 0.0
    if name == 'gentle' and 12 <= t <= 108:
        steer = 20 * math.sin(2 * math.pi * (t - 12) / 48)
    elif name == 'repeated_boundary':
        for start in (20, 36, 52, 68, 84, 100):
            u = t - start
            if 0 <= u < 1:
                steer = 80 * u
            elif 1 <= u < 5:
                steer = 80.0
            elif 5 <= u < 6:
                steer = 80 * (6 - u)
    elif name == 'sustained_over':
        steer = segment([(0, 0), (20, 0), (21, 100), (99, 100), (100, 0), (120, 0)], t)[0]
    return speed / 3.6, steer, slope < 0


def prepare():
    folder = HERE / 'inputs'
    if folder.exists():
        raise SystemExit('inputs already exist; refusing to overwrite')
    folder.mkdir()
    rows = []
    for name, period in CASES:
        path = folder / f'{name}_{period}ms.csv'
        with path.open('x', encoding='utf-8', newline='') as out:
            out.write('t_ms,speed_mps,steering_deg,brake_pressed\n')
            for ms in range(0, 120001, period):
                speed, steer, brake = values(name, ms / 1000)
                out.write(f'{ms},{speed:.12f},{steer:.12f},{str(brake).lower()}\n')
        rows.append(f'{sha(path)}  inputs/{path.name}\n')
    with (HERE / 'inputs.sha256').open('x') as out:
        out.writelines(rows)
    print(f'Prepared {len(rows)} D-assumption input files; no engine execution.')


def freeze():
    target = HERE / 'frozen-files.tsv'
    if target.exists():
        raise SystemExit('freeze exists; refusing to replace')
    paths = [HERE / name for name in ('spec.md', 'RUN.md', 'analysis.init.gradle', 'prepare_inputs.py', 'src/AlertAnalysis.kt', 'inputs.sha256')]
    paths += sorted((HERE / 'inputs').glob('*.csv'))
    if len(list((HERE / 'inputs').glob('*.csv'))) != 6:
        raise SystemExit('expected exactly six prepared CSVs')
    paths += sorted((ROOT / 'app/core/src/main/kotlin').rglob('*.kt'))
    paths += [ROOT / 'app' / name for name in ('core/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties', 'gradlew', 'app/src/main/kotlin/ai/vipseat/cargoconcierge/platform/SettingsStore.kt', 'app/src/main/kotlin/ai/vipseat/cargoconcierge/runtime/CargoSession.kt')]
    with target.open('x', encoding='utf-8') as out:
        for path in sorted(paths):
            if not path.is_file() or path.is_symlink():
                raise SystemExit('missing or symlink freeze member')
            out.write(f'{sha(path)}\t{path.relative_to(ROOT).as_posix()}\n')
    print(f'Frozen {len(paths)} files; frozen-files.tsv sha256={sha(target)}')


if __name__ == '__main__':
    if sys.argv[1:] == ['prepare']:
        prepare()
    elif sys.argv[1:] == ['freeze']:
        freeze()
    else:
        raise SystemExit('usage: prepare_inputs.py prepare|freeze')
