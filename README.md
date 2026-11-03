# OL stage 2 evidence — 3 November 2026

- [Conventional lighting experiment: rejected](dust-comparison.md) · [decision](OL-dust-proposal.md)
- [Nuclear wall comparison, 300/700 m](nuke-comparison.md) · [proposal](OL-nuke-proposal.md)
- [Offline sound audition and measured headroom](audio.md)
- [Final audio tests](audio-final-tests.log) · [file integration](audio-file-check.log) · [PR CI](pr-562-ci.log)

PR #562 is tools-only and remains a draft. No nuclear code was changed. The conventional trial is preserved only as a rejected patch, not a merge candidate. No rendering change remains, so no final-head scene bench was run. Original PNGs are unedited. The invalid first conventional shot after returning from the nuke site is omitted; its reason is recorded in invalid-captures.txt. All local originals remain on disk.

Incoming capture commit: 341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e. OL: Stink-O, MIT, 8ae6409af90335e461ba473c92a3e56798f30d38; upstream shaders ran only in its standalone preview. Its source license is retained as ORBITAL-LASER-LICENSE. Synthetic OL terrain is not Horizon gameplay. Sound recording credits are in SOUND-CREDITS.md. This orphan evidence branch is excluded from the implementation PR diff.

Capture scripts use task-local paths and require the pinned OL checkout and its built preview. GPU commands must run through the project's queued lease/laptop_job wrapper. Sandbox coordinates, measured ages and preview arguments are in each capture.json. The scripts never operate Artem's playing instance.
