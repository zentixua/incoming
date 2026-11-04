from pathlib import Path
import subprocess

source = Path('mod/src/devtest/java/ua/zentix/incoming/gametest/GridGameTests.java')
original = source.read_bytes()
text = original.decode()
start = text.index('        Blackouts.useClock(level.getServer(), cleanup);', text.index('public static void cityQueueRunsManyChunksPerTick'))
end = text.index('                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "extinguishing is in progress"))', start)
reverted = text[:start] + '''        // Negative control: original boundary, pending restoration charged to measured cascade.
        Blackouts.useClock(level.getServer(), counting);
        long[] before = BlackoutWorld.get(level).totals();
        Blackouts.blackout(level, cityCenter, CITY_RADIUS, 1000, -1);
        h.startSequence()
''' + text[end:]
log = Path('mod/run/flake3/red-boundary.log')
try:
    source.write_text(reverted)
    with log.open('wb') as output:
        result = subprocess.run(['./gradlew', 'runGameTestServer', '-PgametestOnly=grid_city'], cwd='mod', stdout=output, stderr=subprocess.STDOUT)
    output = log.read_text()
    if result.returncode == 0 or 'chunks per unit of work: quenching' not in output or '1 required tests failed' not in output:
        raise RuntimeError(f'Negative control did not reproduce target failure; exit={result.returncode}, see {log}')
    print('EXPECTED RED: original measurement boundary fails the unchanged throughput assertion', flush=True)
    for line in output.splitlines():
        if 'GRIDBENCH' in line or 'cityqueuerunsmanychunkspertick failed' in line:
            print(line, flush=True)
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original
    print('Final source restored byte-for-byte', flush=True)
with Path('mod/run/flake3/green-boundary.log').open('wb') as output:
    subprocess.run(['./gradlew', 'runGameTestServer', '-PgametestOnly=grid_city'], cwd='mod', stdout=output, stderr=subprocess.STDOUT, check=True)
print('GREEN: restored final source passes grid_city', flush=True)
