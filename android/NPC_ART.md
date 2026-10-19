# NPC portrait art

Six states, `npc_<state>.png`, in **drawable-nodpi** — not a density bucket.
In a density folder Android pre-scales the bitmap for the device with a
bilinear resample before Compose sees it, which destroys pixel art no matter
what `filterQuality` the composable asks for.

| file | when it shows |
|---|---|
| `npc_idle.png` | nothing happening |
| `npc_asking.png` | a question is on screen |
| `npc_working.png` | the agent is training |
| `npc_pleased.png` | placement improved since last round |
| `npc_disappointed.png` | placement dropped |
| `npc_stuck.png` | 3+ training runs with no improvement |

**Spec:** 48×48 px, transparent background, PNG. Larger is fine if it is an
exact multiple of 48 (96, 144, …) so the on-screen size stays an integer
scale — a non-integer scale reintroduces the blur the nodpi folder avoids.

The files here now are placeholders drawn by `tools/npc_draw.py` — a hooded
archivist with spectacles, three tones per material, lit from the upper left,
with a one-pixel outline grown from the silhouette. Re-run that script to
alter them, or replace the PNGs outright keeping the same names; nothing in
the code needs to change either way.
