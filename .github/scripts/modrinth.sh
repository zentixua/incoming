#!/usr/bin/env bash
# Modrinth page from repository files (called by .github/workflows/publish.yml):
#   modrinth.sh page draft <jar>    no project: draft (is_draft, no versions); existing: fields, icon, gallery;
#                                   only while project hidden (draft, rejected, processing)
#   modrinth.sh page release <jar>  same for reviewed project (not draft/rejected); never creates a new one
#   modrinth.sh submit              send draft (or rejected project) for review: status processing
# page writes project ID and upload=true to GITHUB_OUTPUT if jar (by sha1) is not yet in project.
# Fields: docs/store/modrinth.json (missing key leaves site field unchanged, null clears it); page text:
# docs/store/description.md. Required media must all exist before the page is changed. Already published images
# (file sha1 appears in their Modrinth CDN URL) too: reruns reconcile the configured gallery,
# retaining one entry per image and removing obsolete or duplicate site entries.
# Find project by stable project_id (or slug for a new project): drafts are visible only to their team.
# Environment: MODRINTH_TOKEN; MODRINTH_API (default https://api.modrinth.com/v2; test stub);
# STORE_JSON, BODY_MD override field/text paths.
set -euo pipefail

API=${MODRINTH_API:-https://api.modrinth.com/v2}
STORE_JSON=${STORE_JSON:-docs/store/modrinth.json}
BODY_MD=${BODY_MD:-docs/store/description.md}
BODY_RU_MD=${BODY_RU_MD-docs/store/description.ru.md}
OUT=${GITHUB_OUTPUT:-/dev/null}
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is not set}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Token in header file, not curl arguments.
(umask 077 && printf 'Authorization: %s\n' "$MODRINTH_TOKEN" > "$tmp/auth")
UA="User-Agent: github.com/${GITHUB_REPOSITORY:-local} publish workflow"

# call METHOD PATH "CODES" [curl arguments]: response in $tmp/body, status in $code; unlisted status fails with response.
call() {
  local method=$1 path=$2 ok=$3
  shift 3
  code=$(curl -sS -o "$tmp/body" -w '%{http_code}' -X "$method" -H @"$tmp/auth" -H "$UA" "$@" "$API$path") || code=000
  case " $ok " in *" $code "*) return 0 ;; esac
  echo "::error::Modrinth: $method $path — HTTP $code"
  cat "$tmp/body" >&2 || true
  echo >&2
  exit 1
}

# image FILE LIMIT validates image; ext/ctype used for upload.
image() {
  ext=${1##*.}
  ext=${ext,,}
  case $ext in
    png) ctype=image/png ;;
    jpg|jpeg) ctype=image/jpeg ;;
    webp) ctype=image/webp ;;
    gif) ctype=image/gif ;;
    *) echo "::error::$1: png, jpg, webp or gif required"; exit 1 ;;
  esac
  if [ "$(stat -c %s "$1")" -gt "$2" ]; then
    echo "::error::$1 exceeds $(($2 / 1024)) KiB — Modrinth limit"
    exit 1
  fi
}

slug=$(jq -r .slug "$STORE_JSON")
project_id=$(jq -r '.project_id // empty' "$STORE_JSON")
call GET "/project/${project_id:-$slug}" "200 404"
if [ "$code" = 404 ] && [ -n "$project_id" ]; then
  echo "::error::Modrinth: configured project $project_id is unavailable; refusing to create another draft"
  exit 1
fi
status=none
if [ "$code" = 200 ]; then
  cp "$tmp/body" "$tmp/project.json"
  status=$(jq -r .status "$tmp/project.json")
fi

submit() {
  case $status in
    none) echo "::error::Modrinth project $slug does not exist — run draft first"; exit 1 ;;
    draft|rejected) ;;
    processing) echo "::notice::Modrinth: $slug already under review"; return ;;
    *) echo "::notice::Modrinth: $slug is $status, nothing to submit"; return ;;
  esac
  if [ "$(jq '.versions | length' "$tmp/project.json")" = 0 ]; then
    echo "::error::Modrinth: draft $slug has no versions — run draft first"
    exit 1
  fi
  # 400: Modrinth validation found page issues, listed in response.
  call PATCH "/project/$(jq -r .id "$tmp/project.json")" 204 -H 'Content-Type: application/json' \
    --data '{"status":"processing"}'
  echo "Modrinth: [$slug](https://modrinth.com/mod/$slug) submitted for review" >> "$SUMMARY"
}

page() {
  local step=$1 jar=$2 id sha file i n q existing_url
  # Validate every configured image before creating/editing the page or deleting any gallery entry.
  while IFS= read -r file; do
    if [ ! -f "$file" ]; then
      echo "::error::Modrinth: required media file is missing: $file"
      exit 1
    fi
    image "$file" 5242880
  done < <(jq -r '(.icon // empty), (.gallery[]?.file)' "$STORE_JSON")
  file=$(jq -r '.icon // empty' "$STORE_JSON")
  if [ -n "$file" ]; then image "$file" 524288; fi
  # Different paths can contain the same image. Reject them before any remote mutation.
  local -A gallery_files=()
  while IFS= read -r file; do
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    if [ -n "${gallery_files[$sha]:-}" ]; then
      echo "::error::Modrinth: duplicate gallery image: $file and ${gallery_files[$sha]}"
      exit 1
    fi
    gallery_files[$sha]=$file
  done < <(jq -r '.gallery[]?.file' "$STORE_JSON")
  bash "$(dirname "$0")/store_body.sh" "$BODY_MD" "$BODY_RU_MD" > "$tmp/description.md"
  case "$step:$status" in
    draft:none)
      # New projects must be drafts: Modrinth otherwise requires versions; only team sees them before review.
      jq --rawfile body "$tmp/description.md" '{slug, title, description, body: $body, categories,
          additional_categories: (.additional_categories // []), client_side, server_side, license_id,
          source_url, issues_url, wiki_url, discord_url, project_type: "mod", is_draft: true, initial_versions: []}
        | with_entries(select(.value != null))' "$STORE_JSON" > "$tmp/data.json"
      call POST /project 200 -F "data=<$tmp/data.json;type=application/json"
      cp "$tmp/body" "$tmp/project.json"
      status=$(jq -r .status "$tmp/project.json")
      echo "::notice::Modrinth: draft $slug created"
      ;;
    draft:draft | draft:rejected | draft:processing | release:approved | release:unlisted | release:private | \
      release:archived | release:processing)
      jq --rawfile body "$tmp/description.md" '. as $s
        | [ "slug", "title", "description", "client_side", "server_side", "categories", "additional_categories", "license_id",
            "source_url", "issues_url", "wiki_url", "discord_url" ]
        | map(select(. as $k | $s | has($k)) | {(.): $s[.]}) | add + {body: $body}' "$STORE_JSON" > "$tmp/edit.json"
      call PATCH "/project/$(jq -r .id "$tmp/project.json")" 204 -H 'Content-Type: application/json' \
        --data-binary @"$tmp/edit.json"
      ;;
    release:none) echo "::error::Modrinth project $slug does not exist — run draft and submit first"; exit 1 ;;
    release:*) echo "::error::Modrinth: $slug is $status; release requires submit and review first"; exit 1 ;;
    *) echo "::error::Modrinth: $slug is $status; draft only while hidden, use release for publication"; exit 1 ;;
  esac
  id=$(jq -r .id "$tmp/project.json")

  # Icon: absent file waits until later; same file already on site stays unchanged.
  file=$(jq -r '.icon // empty' "$STORE_JSON")
  if [ -n "$file" ] && [ -f "$file" ]; then
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    if jq -e --arg s "$sha" '(.icon_url // "") | contains($s)' "$tmp/project.json" > /dev/null; then
      echo "icon: unchanged"
    else
      image "$file" 524288
      call PATCH "/project/$id/icon?ext=$ext" 204 -H "Content-Type: $ctype" --data-binary @"$file"
      echo "icon: $file"
    fi
  elif [ -n "$file" ]; then
    echo "::notice::Modrinth: icon $file not yet in repository"
  fi

  # Upload new images once; update retained images before removing obsolete entries.
  # Empty, null or absent gallery text leaves existing text unchanged; only non-empty text replaces it.
  # The project JSON fields above use a separate null-clears contract.
  # https://docs.modrinth.com/api/operations/modifygalleryimage/
  n=$(jq '.gallery // [] | length' "$STORE_JSON")
  local retained=() removed=() matches url key
  for ((i = 0; i < n; i++)); do
    file=$(jq -r ".gallery[$i].file" "$STORE_JSON")
    if [ ! -f "$file" ]; then
      echo "::notice::Modrinth: image $file not yet in repository"
      continue
    fi
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    q=$(jq -r --argjson i "$i" '.gallery[$i] | "ordering=\($i)&featured=\(.featured // false)"
      + (if (.title // "") != "" then "&title=\(.title | @uri)" else "" end)
      + (if (.description // "") != "" then "&description=\(.description | @uri)" else "" end)' "$STORE_JSON")
    matches=$(jq -c --arg s "$sha" '[.gallery[]?
      | select(((.url // "") + " " + (.raw_url // "")) | contains($s))]' "$tmp/project.json")
    existing_url=$(jq -r '.[0].url // empty' <<<"$matches")
    if [ "$(jq 'length' <<<"$matches")" -gt 1 ]; then
      # A replacement must preserve the same absent/empty/null text semantics as PATCH.
      for key in title description; do
        if [ -z "$(jq -r --argjson i "$i" --arg k "$key" '.gallery[$i][$k] // empty' "$STORE_JSON")" ]; then
          q+=$(jq -r --arg k "$key" '.[0][$k] // "" | if . == "" then "" else "&\($k)=\(. | @uri)" end' <<<"$matches")
        fi
      done
      # Labrinth deletes both CDN files, including a raw_url shared by another entry.
      # Remove the whole duplicate group BEFORE uploading its one replacement.
      # https://github.com/modrinth/code/blob/862c64e699b02664b289cf18f01422e356be4c2f/apps/labrinth/src/routes/v3/projects/mod.rs#L3389-L3396
      while IFS= read -r url; do
        call DELETE "/project/$id/gallery?url=$(jq -rn --arg u "$url" '$u | @uri')" 204
        removed+=("$url")
        echo "gallery: replacing duplicate $url"
      done < <(jq -r '.[].url' <<<"$matches")
      existing_url=
    fi
    if [ -n "$existing_url" ]; then
      call PATCH "/project/$id/gallery?url=$(jq -rn --arg u "$existing_url" '$u | @uri')&$q" 204
      echo "Gallery metadata updated: $file"
      retained+=("$existing_url")
      continue
    fi
    image "$file" 5242880
    call POST "/project/$id/gallery?ext=$ext&$q" 204 -H "Content-Type: $ctype" --data-binary @"$file"
    echo "gallery: $file"
  done

  # Keep only the selected entry, not every old URL sharing a configured image's hash.
  # New uploads are absent from this pre-update snapshot and therefore cannot be removed here.
  local k found
  while IFS= read -r url; do
    found=false
    for k in "${retained[@]}" "${removed[@]}"; do
      if [ "$url" = "$k" ]; then found=true; break; fi
    done
    if [ "$found" = false ]; then
      call DELETE "/project/$id/gallery?url=$(jq -rn --arg u "$url" '$u | @uri')" 204
      echo "gallery: removed $url"
    fi
  done < <(jq -r '.gallery[]? | .url // empty' "$tmp/project.json")

  version "$jar" "$step"
}

version() {
  local jar=$1 step=${2:-release} id sha upload=true
  if [ "$status" = none ]; then
    echo "::error::Modrinth: project $slug does not exist; run draft first"
    exit 1
  fi
  if [ "$step" = release ]; then
    case $status in
      approved|unlisted|private|archived|processing) ;;
      *) echo "::error::Modrinth: $slug is $status; finish submission before releasing"; exit 1 ;;
    esac
  fi
  id=$(jq -r .id "$tmp/project.json")
  # An existing jar is not uploaded again when a run is retried.
  sha=$(sha1sum "$jar" | cut -d' ' -f1)
  call GET "/version_file/$sha?algorithm=sha1" "200 404"
  if [ "$code" = 200 ]; then
    if [ "$(jq -r .project_id "$tmp/body")" != "$id" ]; then
      echo "::error::$(basename "$jar") already uploaded to Modrinth in another project: $(jq -r .project_id "$tmp/body")"
      exit 1
    fi
    upload=false
    echo "::notice::Modrinth: $(basename "$jar") already uploaded — version $(jq -r .version_number "$tmp/body")"
  fi

  { echo "id=$id"; echo "upload=$upload"; } >> "$OUT"
  echo "Modrinth: [$slug](https://modrinth.com/mod/$slug), $(jq -r .status "$tmp/project.json"), step $step" >> "$SUMMARY"
}

case ${1:-} in
  page) page "${2:?draft or release}" "${3:?jar}" ;;
  submit) submit ;;
  version) version "${2:?jar}" ;;
  *) echo "usage: $0 page draft|release <jar> | submit" >&2; exit 2 ;;
esac
