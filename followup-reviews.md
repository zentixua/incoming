# Independent review of the RUINZONE throughput/order follow-up

Two fresh Codex CLI sessions reviewed the code at xhigh. They received code/rules, not the PR description or author's conclusions. The first review reported no confirmed findings before the later snapshot-cache work. It is not an approval of those later changes.

The second review covered the diff that became `940bd87e5` and found one P2:

> Snapshot eviction protected submitted worker tasks but not windows still collecting disk reads or waiting for worker admission. Missing-heightmap editor chunks are consumed in batches of four, so eviction could repeatedly discard the partial window. Also, preparation returned zero as apparent memory headroom, causing unnecessary eviction while another strike was preparing.

Author fix in `3b68a5ade`:

- Track and protect pending disk/on-site windows until submission, completion, cancellation or unload.
- Keep actual memory headroom independent of preparation's admission gate.
- Add `snapshotTrimProtectsIncompleteDiskWindows`: it fails on the prior code with a partially read window, then passes unchanged with the fix, including cleanup after cancellation.

The second review was read-only and ran no build/tests/heavy jobs during the performance measurement. Its original full transcript stays local because it includes project context. No third review under the repository's maximum; this report records the finding and author resolution, not an independent approval of the later fix.
