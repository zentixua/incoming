# /// script
# dependencies = ["matplotlib"]
# ///
import csv, sys
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
rows = list(csv.reader(open(sys.argv[1])))
kg = rows[0][1]; data = rows[2:]
fig, ax = plt.subplots(3, 1, figsize=(11, 8), sharex=True)
for i, m in enumerate(["20", "50", "100"]):
    r = [x for x in data if x[0] == m]
    t = [float(x[1]) for x in r]
    for col, lab, c in [(2, "3.4.1 (tremor by distance band)", "#999"), (3, "main (body lean only)", "#d62"), (4, "this PR (tremor + body + head)", "#26c")]:
        ax[i].plot(t, [float(x[col]) for x in r], label=lab, color=c, lw=1.4)
    ax[i].set_title(f"Shahed ({kg} kg TNT) at {m} m, blast ahead: camera pitch, degrees")
    ax[i].axhline(0, color="#ccc", lw=0.6); ax[i].grid(alpha=0.25)
ax[0].legend(loc="upper right"); ax[2].set_xlabel("seconds after the front reaches the player")
fig.tight_layout(); fig.savefig(sys.argv[2], dpi=110)
