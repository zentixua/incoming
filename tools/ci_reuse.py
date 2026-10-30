#!/usr/bin/env python3
"""Find a successful build run that already checked this exact tree, so a main push or release reuses its jar.

`ci_reuse.py find <tree>` prints `run=<id>` (GitHub step output) or `run=` when there is none. A run qualifies when
its build job uploaded `tested-tree-<tree>` (build.yml), it is a pull_request or push run of build.yml from this
repository (fork PRs can run their own workflow and name artifacts at will), it completed successfully (“Итог” and
every other job), and its incoming-jar has not expired. Equal trees mean identical sources, workflow included.
"""
import json
import os
import subprocess
import sys


def gh_api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def find(tree, repo, api=gh_api):
    markers = api(f"repos/{repo}/actions/artifacts?name=tested-tree-{tree}&per_page=100")["artifacts"]
    for marker in sorted(markers, key=lambda a: a["created_at"], reverse=True):
        source = marker["workflow_run"]
        if marker["expired"] or source["head_repository_id"] != source["repository_id"]:
            continue
        run = api(f"repos/{repo}/actions/runs/{source['id']}")
        if (run["path"] != ".github/workflows/build.yml" or run["event"] not in ("pull_request", "push")
                or run["status"] != "completed" or run["conclusion"] != "success"
                or run["head_repository"]["full_name"] != repo or run["repository"]["full_name"] != repo):
            continue
        jars = api(f"repos/{repo}/actions/runs/{run['id']}/artifacts?name=incoming-jar")["artifacts"]
        if len(jars) == 1 and not jars[0]["expired"]:
            return run["id"]
    return None


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "find":
        raise SystemExit("usage: ci_reuse.py find <tree>")
    tree = sys.argv[2]
    run = find(tree, os.environ["GITHUB_REPOSITORY"])
    print(f"run={run or ''}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as out:
            out.write(f"Tree {tree[:12]}: " + (f"already checked by run {run}; its jar is reused\n" if run
                                                else "not checked before; full mod checks\n"))
