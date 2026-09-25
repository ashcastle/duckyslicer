# DuckySlicer promo video

`duckyslicer-promo-ko.mp4` is a 33-second vertical (1080×1920, 60 fps) promo for Reels,
Shorts, and TikTok, with Korean copy. It uses the real Play Store screenshots from
`../google-play/graphics/phone-screenshots/`. The 3D print, the UI interactions, and the
soundtrack are all generated in code.

| Time | Scene | Interaction |
| --- | --- | --- |
| 0–3.5 s | Hook: "3D 프린팅 하려고 PC 켜세요?" → "이제 폰으로." | A nozzle writes the text, a slicing plane cuts it into layers, then the answer slams in |
| 3.5–7 s | Logo reveal | A yellow iris wipe closes into the duck mark, the wordmark staggers in, and the duck blinks |
| 7–12 s | STEP 01 · Prepare | STL/OBJ/3MF chips are pulled into the phone, the user taps **Slice**, and a slicing progress card runs |
| 12–17.5 s | STEP 02 · Preview | The camera zooms into the screen. The finger scrubs the layer slider down to layer 1, then the cube is rebuilt layer by layer with a live Z readout and legend. A match cut returns to the app, and the preview sheet opens |
| 17.5–22.5 s | STEP 03 · Profiles | A card flip leads to the profile list over a scrolling marquee of bundled printer brands. The finger drags **Layer height** from 0.20 to 0.12 mm |
| 22.5–27.5 s | Feature grid | Six cards spring in. The user toggles offline mode and sends G-code to OctoPrint/Klipper |
| 27.5–33 s | Call to action | Three phones fan in and the user taps the "GitHub에서 무료 다운로드" button |

## Preview and rebuild

- **Live preview:** serve `distribution/` (for example, `npx serve distribution`) and open
  `/promo-video/index.html`. Add `?t=14.2` to freeze on one moment.
- **Rebuild the MP4:** run `./build.sh`. It fetches the fonts from npm, renders every frame
  in headless Chromium, synthesizes `out/audio.wav`, and muxes the result with
  loudness normalization (-14 LUFS).
  - Requirements: Node with `playwright`, Python 3 with `numpy` and `scipy`, and `ffmpeg`
    (or set `FFMPEG=/path/to/ffmpeg`).
- **Stills for review:** `node render.mjs stills 3.5 12 20` writes PNGs to `out/stills/`.

The animation is a pure function of time: `render(t)` in `index.html`. As a result, the
frames are deterministic. Sound cues are declared next to the visuals in the same file
(`sfx(...)`) and exported to `audio.py` through `out/sfx.json`, so the sound stays in sync
when you change the timing.
