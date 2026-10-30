"""ci_reuse.find accepts only a successful same-repository build run that marked this tree and still has its jar."""
import unittest

from ci_reuse import find

REPO = "zentixua/incoming"


class Fixture:
    def __init__(self):
        self.markers = []
        self.runs = {}
        self.jars = {}

    def add(self, run_id, created="2026-10-30T10:00:00Z", fork=False, expired=False, event="pull_request",
            conclusion="success", status="completed", path=".github/workflows/build.yml", jar=True, jar_expired=False):
        self.markers.append({"expired": expired, "created_at": created, "workflow_run": {
            "id": run_id, "repository_id": 1, "head_repository_id": 2 if fork else 1}})
        self.runs[run_id] = {"id": run_id, "path": path, "event": event, "status": status, "conclusion": conclusion,
                             "repository": {"full_name": REPO},
                             "head_repository": {"full_name": "someone/incoming" if fork else REPO}}
        self.jars[run_id] = [{"expired": jar_expired}] if jar else []

    def api(self, path):
        if "/actions/artifacts?name=tested-tree-abc" in path:
            return {"artifacts": self.markers}
        if path.endswith("/artifacts?name=incoming-jar"):
            return {"artifacts": self.jars[int(path.split("/runs/")[1].split("/")[0])]}
        return self.runs[int(path.rsplit("/", 1)[1])]


class FindTest(unittest.TestCase):
    def test_no_marker_means_full_checks(self):
        self.assertIsNone(find("abc", REPO, Fixture().api))

    def test_successful_pull_request_run_is_reused(self):
        f = Fixture()
        f.add(7)
        self.assertEqual(7, find("abc", REPO, f.api))

    def test_unusable_runs_are_skipped(self):
        for kwargs in ({"fork": True}, {"expired": True}, {"conclusion": "failure"}, {"status": "in_progress"},
                       {"event": "workflow_dispatch"}, {"path": ".github/workflows/publish.yml"}, {"jar": False},
                       {"jar_expired": True}):
            f = Fixture()
            f.add(5, **kwargs)
            self.assertIsNone(find("abc", REPO, f.api), kwargs)

    def test_newest_usable_run_wins_over_failed_newer_one(self):
        f = Fixture()
        f.add(3, created="2026-10-30T09:00:00Z")
        f.add(4, created="2026-10-30T10:00:00Z")
        f.add(9, created="2026-10-30T11:00:00Z", conclusion="failure")
        self.assertEqual(4, find("abc", REPO, f.api))


if __name__ == "__main__":
    unittest.main()
