# Final source ownership correction (df40)

Source df40a1e369306d21cad94677aadd99e77e235eba, base3efac357c. Pure table values/timing and single-source renderer geometry are unchanged fromc156; the caller now retains its admitted future/result until cloud expiry. Shared reuse/pending capacity stays8. This is not a total retained-memory cap: live owners retain table payload, approximately25KiB at the supported maximum, plus object/front/workspace overhead.

Queued native constructors complete between12 deterministic frame scans over24distinct sources. Original452 flickers010101010101; c156's bounded but unowned cache starves the last consumer000000000000; df40's retained requests produce000111111111. This is a controlled ordering regression, not24-nuke GPU performance or a universal admission latency guarantee. Read c156-red.xml, green-xml and fixture sources. Release/reacquire preserves exactlambda/top and does not invalidate another owner.

Full localbuild1850 tests passes, native nuke_stem_clock1/1 passes, exactCI41943641063 passes; merge treeb87277f5c72357e753fb95892fedfc7e6dc6fc7c equalsdf40. CI ZIPa7fa92ec0dd23c8eca174475a4f57d74835c11219916cbda9305c5a1879da476; JAR3cb9666c52723f93d702b19ff96a8763954a4bb73091fa4c3a3d9203f5221685. All134 model/client-nuclear compiled class entries match CI.

Private changed-path probe:500000 iterations perphase, original source with only renaming and controlled executor, current production Request.ready/tau/top/foot lookup. Candidate0B/call,33.64021threadCPU ns; original~400B/call,154.7295/154.8347ns. Excludes fullframe/shader/background construction and is not an FPS claim. Rawgreen XML and source underfixture/. The c156bench elsewhere is historical; finaldf40 cost is pending.

Same independent second reviewer RAIR8B explicitly accepted single-source452→c156 frame reuse fordf40, with originalpins retained. See reviews/RAIR8B-source-and-frames-review.md. No overall server/readiness/real-world-calibration claim.
