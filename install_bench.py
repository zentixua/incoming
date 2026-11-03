"""Adapt the retained Stage A diagnostic to paired 15 kt runs in disposable worktrees."""
from pathlib import Path
import sys
root = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]).resolve()
out.mkdir(parents=True, exist_ok=True)
source = Path('/mnt/data/projects/incoming/mod/run/wt-MT/mod/run/mt-evidence/part2/MtRuinBench.java').read_text()
source = source.replace('original solver', 'paired solver')
source = source.replace('/mnt/data/projects/incoming/mod/run/wt-MT/mod/run/mt-evidence/part2', str(out))
source = source.replace('new double[]{15,100,1000}', 'new double[]{15}')
a = source.index('            for(double kt:new double[]{100,1000}){')
b = source.index('\n            }', a) + len('\n            }')
source = source[:a] + source[b:]
(root / 'mod/src/devtest/java/ua/zentix/incoming/nuclear/world/MtRuinBench.java').write_text(source)
(out / 'MtRuinBench.java').write_text(source)
