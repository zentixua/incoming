#!/usr/bin/env python3
"""Check CurseForge files by SHA1 before uploading; never treat lookup failure as absence.

The read API uses a separate key: https://docs.curseforge.com/rest-api/#get-mod-files
Upload receipts use https://docs.github.com/en/rest/deployments/deployments
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def find_file(project, jar, request):
    sha1 = hashlib.sha1(jar.read_bytes()).hexdigest()
    md5 = hashlib.md5(jar.read_bytes()).hexdigest()
    index = 0
    while True:
        page = request(f"/mods/{project}/files?index={index}&pageSize=50")
        for file in page["data"]:
            hashes = {entry["algo"]: entry["value"].lower() for entry in file["hashes"]}
            if hashes.get(1) == sha1 or hashes.get(2) == md5:
                if file["fileStatus"] not in (4, 10):
                    raise ValueError(f"CurseForge file {file['id']} already exists with status "
                                     f"{file['fileStatus']}; resolve moderation/release state before retrying")
                return file["id"]
            if file["fileName"] == jar.name:
                raise ValueError(f"CurseForge already has {jar.name} with different contents")
        pagination = page["pagination"]
        count = pagination["resultCount"]
        index += count
        if index >= pagination["totalCount"]:
            return None
        if not count:
            raise ValueError("CurseForge returned an incomplete file list")


def github(path, data=None):
    command = ["gh", "api", f"repos/{{owner}}/{{repo}}/{path}"]
    if data is not None:
        command += ["--method", "POST", "--input", "-"]
    return json.loads(subprocess.check_output(command, input=json.dumps(data) if data is not None else None, text=True))


def reserve_upload(project, jar, version, api=github):
    """Persist intent before upload: public CurseForge listings can omit files still in moderation.

    Deployments survive runner/cache expiration. A previous ambiguous attempt must be reconciled,
    never automatically uploaded again. The workflow's publish concurrency serializes reservations.
    """
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    environment = f"curseforge-{project}"
    page = 1
    while True:
        query = urlencode({"ref": f"v{version}", "environment": environment, "per_page": 100, "page": page})
        deployments = api(f"deployments?{query}")
        for deployment in deployments:
            payload = deployment.get("payload") or {}
            if isinstance(payload, str):
                payload = json.loads(payload)
            if payload.get("store") != "curseforge":
                continue
            if payload.get("sha256") != digest:
                raise ValueError(f"A different jar was already reserved for CurseForge version {version}")
            statuses = api(f"deployments/{deployment['id']}/statuses?per_page=1")
            state = statuses[0]["state"] if statuses else "pending"
            if state == "success":
                print(f"CurseForge: this jar was already submitted (deployment {deployment['id']})")
                return False, None
            if state == "inactive":
                # An owner explicitly reconciled the previous attempt and allowed a retry.
                return create_reservation(project, jar, version, digest, environment, api)
            raise ValueError(f"CurseForge upload deployment {deployment['id']} is {state}; verify its outcome "
                             "before retrying. After confirming no file was submitted, mark that deployment "
                             "inactive; after confirming the exact jar was submitted, mark it success")
        if len(deployments) < 100:
            return create_reservation(project, jar, version, digest, environment, api)
        page += 1


def create_reservation(project, jar, version, digest, environment, api):
    deployment = api("deployments", {"ref": f"v{version}", "environment": environment,
                     "auto_merge": False, "required_contexts": [], "production_environment": True,
                     "description": f"Incoming {version}: CurseForge upload",
                     "payload": {"store": "curseforge", "project": project, "version": version,
                                 "filename": jar.name, "sha256": digest}})
    api(f"deployments/{deployment['id']}/statuses", {"state": "in_progress", "auto_inactive": False,
                                                   "description": "Jar upload reserved; outcome not yet recorded"})
    return True, deployment["id"]


def record_upload(deployment_id, outcome, api=github):
    if not deployment_id.isdigit():
        raise ValueError("Invalid upload deployment ID")
    success = outcome == "success"
    api(f"deployments/{deployment_id}/statuses", {
        "state": "success" if success else "failure", "auto_inactive": False,
        "description": "Jar submitted; await CurseForge moderation" if success else "Upload outcome uncertain; verify before retrying",
        "log_url": f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"})


def main():
    if sys.argv[1] == "--record":
        record_upload(sys.argv[2], sys.argv[3])
        return
    project = os.environ["CURSEFORGE_ID"]
    key = os.environ["CURSEFORGE_API_KEY"]

    def request(path):
        req = Request("https://api.curseforge.com/v1" + path,
                      headers={"x-api-key": key, "User-Agent": "Incoming release workflow"})
        with urlopen(req, timeout=30) as response:
            return json.load(response)

    # A missing/private project or denied API lookup must stop the workflow before any upload.
    file_id = find_file(project, Path(sys.argv[1]), request)
    upload, deployment_id = (False, None) if file_id else reserve_upload(project, Path(sys.argv[1]), os.environ["VERSION"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"upload={str(upload).lower()}\ndeployment_id={deployment_id or ''}\n")
    if file_id:
        print(f"CurseForge: existing file {file_id}; no upload needed")
    elif upload:
        print(f"CurseForge: upload reserved by deployment {deployment_id}")
    else:
        print("CurseForge: existing submission receipt; no upload needed")


if __name__ == "__main__":
    main()
