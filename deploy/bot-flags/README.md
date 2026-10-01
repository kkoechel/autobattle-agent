# Agent dispositions

Four agents running one algorithm on partitioned data still converge —
partitioning only delays it. Each of these gives an agent a different
temperament, so they explore differently rather than explore different
corners identically.

Theme and seed partitions keep them off each other's ground; the flags below
decide how each one *behaves* on the ground it has.

| agent | temperament | the bet |
|---|---|---|
| quill | **new cards only** | seeds solely from cards added in the last 14 days, with the measured and combo generators off. Tests what just shipped, before anyone has built around it. No seed partition: it should see every new card, not a quarter of them. A low floor (0.45) because a new-card deck has to be allowed to place badly — refusing to install anything that screens under the established decks is how a new card never gets tried at all |
| ember | patient | rotates rarely, refines hard. Tests depth: how far one deck goes if you keep at it |
| vane  | contrarian | overlap capped at 30 instead of 50. Forced further from the field than anything else we run |
| rook  | wide net | screens twice the candidates at a low bar. Tests whether the floor is throwing away good decks |

Cross-agent novelty needs no configuration: each agent's exemption set is only
its own deck, so every other agent's deck sits in the field and is checked at
that agent's own overlap cap. No agent can clone another.
