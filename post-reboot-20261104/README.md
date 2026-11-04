# B5 post-reboot persistence corrections

Production PR #565, current merged-base head 7e9438d0ae03de01a147c5204c3d3a52c1a03205. Main3be78e33b included normally, no rebase/force. Production changes corresponding to the new evidence are commits4ec7e0e62 and43ec6e4fb. Earlier evidence remains pinned to its own old heads.

Two fresh independent read-only Sol/xhigh reviews are preserved verbatim. The first found a resident/cold-import progress cycle and order-dependent retired crater totals; the second found a reentrant admission ordering defect in the first correction. The final correction resolves admission-capable getters before checking/charging headroom. No third review was requested/performed.

- mt-s2-b5-fixes.log: 46 touched GameTests passed44.51s, on4ec7e0e62; mt-s2-b5-build.log: full build passed3m52s.
- mt-s2-b5-final.log: 8 focused ledger/cold-admission/work_craft tests passed3.493s, with the final ordering fix; mt-s2-b5-final-build.log: full build passed4m20s. These are pre-main3be78 merge source checks; final merged-head CI is separate.
- The new cold progress fixture invokes actual NuclearWorld.tick and lets deposition free decoder workspace while an external reservation remains fixed. The admission fixture gates the actual NeoForge decoder, checks the cap with the progress reservation still live, and rejects spending room twice. Crater totals use actual streamed NBT in both field orders and a second save/load.
- mt-s2-b5-ci-failed.log preserves the failed intermediate4ec7e0e62 CI run41794658420: unchanged work_craft fixture received no wave. The same unmodified fixture passed in the final local subset; no assertion was weakened. Final merged-head CI41797205660 passed on7e9438d0a; PR#565 then merged as main0d7eb539a. See final-ci.json and final-ci-wait.log.

No new GPU/frame/scene run: rendering/shader sources of these persistence corrections are unchanged. No whole1Mt timing or total-JVM-heap bound is established here, no ceiling/release change. The standalone persistence work does not close OPT prep speed or B3 publication gates.

Final required CI is green on exact7e9438d0ae03de01a147c5204c3d3a52c1a03205, https://github.com/zentixua/incoming/actions/runs/41797205660. Production PR#565 merged as0d7eb539a6fe99ec54b631fc28a991623474b141. This acceptance applies to the standalone persistence changes, not OPT/backend or the1Mt completion criterion.
