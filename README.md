# Nuclear preparation control evidence

Main UI reference: `36c9b5bdd`. Candidate UI captures: `d7def3da2`; the captured ICBM UI rendering is unchanged in final control `720f8ec79`. EN frames are 1280px wide, RU frames 854px wide. Baseline, Preparing, Ready, Paused and keyboard begin/release were captured in the same flat-world scenario. Display status counts are deterministic fixtures; server correctness is tested separately.

Raw final combined log: `checks/integrated-checks.log` (1793 units and 52 GameTests). Final own-scope independent verdict: `REVIEW-final.md`. Intermediate console regression: `checks/console-regression.log`. All media remain on this orphan branch, outside the production diff.

No 100-strike bench: no render, particle or draw-count changes. One-second WAVs preserve captures; this UI/server change adds no audible effects. Full-yield backend readiness and normal-carrier source reuse are not proven by display fixtures or small authority tests.
