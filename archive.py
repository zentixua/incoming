"""Commit evidence through a private index, preserving the production checkout and orphan lineage."""
from pathlib import Path
import os, subprocess
root = Path(__file__).resolve().parent
repo = root.parents[3]
assert (repo / 'AGENTS.md').exists(), repo
index = root.parent / 'evidence.index'
env = dict(os.environ, GIT_INDEX_FILE=str(index))
def git(*args, data=None):
    return subprocess.check_output(['git', '-C', str(repo), *args], input=data, env=env).decode().strip()
git('read-tree', '--empty')
for path in sorted(root.rglob('*')):
    if not path.is_file():
        continue
    name = path.relative_to(root).as_posix()
    oid = git('hash-object', '-w', '--', str(path))
    git('update-index', '--add', '--cacheinfo', f'100644,{oid},{name}')
tree = git('write-tree')
ref = 'refs/heads/look-gate/mt-b2'
old = subprocess.run(['git', '-C', str(repo), 'rev-parse', '--verify', ref], capture_output=True, text=True)
parents = ['-p', old.stdout.strip()] if old.returncode == 0 else []
commit = git('commit-tree', tree, *parents, data=b'MT-B2 partial structural repair evidence\n\nCo-Authored-By: Codex <noreply@openai.com>\n')
git('update-ref', ref, commit)
print(commit)
