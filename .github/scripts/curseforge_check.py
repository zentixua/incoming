#!/usr/bin/env python3
"""Check CurseForge files by SHA1 before uploading; never treat lookup failure as absence.

The read API uses a separate key: https://docs.curseforge.com/rest-api/#get-mod-files
"""
import hashlib
import json
import os
from pathlib import Path
import sys
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


def main():
    project = os.environ["CURSEFORGE_ID"]
    key = os.environ["CURSEFORGE_API_KEY"]

    def request(path):
        req = Request("https://api.curseforge.com/v1" + path,
                      headers={"x-api-key": key, "User-Agent": "Incoming release workflow"})
        with urlopen(req, timeout=30) as response:
            return json.load(response)

    # A missing/private project or denied API lookup must stop the workflow before any upload.
    file_id = find_file(project, Path(sys.argv[1]), request)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"upload={'false' if file_id else 'true'}\n")
    print(f"CurseForge: {'existing file ' + str(file_id) if file_id else 'no matching published file'}")


if __name__ == "__main__":
    main()
