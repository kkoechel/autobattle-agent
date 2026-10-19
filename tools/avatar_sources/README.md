# Avatar sources

The three card artworks the pixel avatars are derived from, kept here so
`tools/pixelate_avatar.py` can be re-run without fetching anything.

| file | card | id |
|---|---|---|
| `491_unstoppable_force.png` | Unstoppable Force | 491 |
| `722_fairy_cleaner.png` | Fairy Cleaner | 722 |
| `947_fever_dreams.png` | Fever Dreams | 947 |

**These are the main game's art, copied, not moved.** autobattle.online is
read-only from this repo — the card sync is pull-only and nothing here writes
to it — and that holds for artwork too.

Fetched from `https://autobattle.online/uploads/card-art/<id>/<hash>.webp`,
the paths being whatever `image_url` carried in the synced catalogue at the
time; converted from WebP to PNG so Pillow in any version can open them.

A note for whoever fetches the next one: downloading these on the laptop does
not work. The sandbox lets the request through — the response is a 200 and the
headers arrive — but severs the body, so curl exits 23 ("failed writing body")
and a shell redirect leaves a zero-byte file. Relay through a host instead:

```sh
ssh root@<subgames> "curl -sS 'https://autobattle.online<path>' | base64 -w0" \
  | base64 -d > out.webp
```

Re-run the pixelation with:

```sh
python3 -I tools/pixelate_avatar.py tools/avatar_sources/*.png \
  --out android/app/src/main/res/drawable-nodpi --preview 8
```
