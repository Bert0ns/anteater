# Golden fixtures

Synthetic images with the signals the Python lab gives them. The Kotlin port runs its detectors on the same files and checks that it agrees (`docs/design.md`, section 10, step 5).

Every image here is generated from fixed seeds by `lab/src/cribrum_lab/fixtures.py`. **Never add personal photos**, or anything derived from them. To regenerate after a detector change (this needs the MobileCLIP model, which `scan` downloads):

```bash
cd lab
uv run python -m cribrum_lab.fixtures
```

## `golden/expected.json`

```jsonc
{
  "format_version": 1,
  "tolerance": {"score": 0.05, "embedding_cosine": 0.99},
  "embedding_model": "mobileclip2_s0",
  "images": {
    "DCIM/Camera/texture.jpg": {           // path relative to golden/: folders matter
      "width": 640, "height": 480,          // as displayed, EXIF orientation applied
      "has_camera_exif": true,
      "content_hash": "…",                  // xxHash3 64-bit of the file, hex
      "perceptual_hash": "…",               // 64-bit pHash, hex
      "embedding": "…",                     // base64 of 512 little-endian float32, L2-normalized
      "signals": [
        {"detector": "blur", "version": 1, "category": "blurry", "score": 0.12, "evidence": {…}}
      ]
    }
  }
}
```

Group detectors (exact and near duplicates, bursts) run over all the images at once, as they do on a phone. Decoders and resamplers differ between Pillow and Android, so scores must match within `tolerance.score` and embeddings within `tolerance.embedding_cosine`. Hashes, sizes, categories and roles must match exactly. Numbers in `evidence` are informative only.

## `decode/`

Three-frame animated GIF and WebP of 64 × 48 pixels for the android decoding tests, made by `lab/scripts/make_decode_fixtures.py`. They are not part of `expected.json`: the lab has no expected values for them. Never add personal photos here either.

## `videos/`

Synthetic videos of about a second each, ffmpeg's test pattern and a 48 kHz sine tone, a few KB each (20 KB for the ProRes one), made by `lab/scripts/make_video_fixtures.py` with ffmpeg on the PATH. `expected.json` holds what both metadata readers must read, from the parameters each file is made with: the lab's `tests/test_video_metadata.py` reads them with PyAV, and the app's `VideoMetadataDeviceTest` with the phone's parsers. Capture times are local time in the zone the file names, and `captured_instant` is the same moment as epoch milliseconds in UTC (`docs/design.md`, section 7). `ntsc.mp4` runs at 30000/1001 fps. `prores.mov` is ProRes, which the phone may not know: it reads it or marks it unreadable (`phone_may_not_read`). `clip.webm` is in `phone_frames_may_not_read`: the phone reads its metadata but may give no frame of it. `hdr.mp4` is HDR with PQ and `hlg.mp4` with HLG, the way the maintainer's camera records it. `audio.mp4` has no video track: the lab drops it, and the phone marks it unreadable. `broken.mp4` has a video head and nothing a parser can read.

`frames.json` holds the frames the lab samples from each video it reads, at the times of the rule (`docs/design.md` §5), and their MobileCLIP embeddings, written by `python -m cribrum_lab.fixtures` with the image fixtures. The lab's `tests/test_fixtures.py` checks it, and the app's `VideoFramesDeviceTest` embeds the same frames on a phone and compares, within the cosine floors under `tolerance`. Never add personal videos here.

`takes.json` holds made-up clips, each a path, a capture time, a length and sometimes a few short frame vectors, with no file behind them, and the `TAKES` signals the lab gives them (`docs/design.md` §5). They cover the gap measured from the end of a take, a gap of exactly `MAX_GAP`, a short take, frames that break a chain, a sent copy and a clip with no time. `python -m cribrum_lab.fixtures` writes it, the lab's `tests/test_fixtures.py` checks it, and the app's `TakesDetectorTest` runs its port on the same clips and must give the same signals.
