# Development state

Last updated 2026-11-03. Describes only what the code, tests and recorded checks support. Update it at the end of each milestone.

## Branches

There is no Git remote; nothing has been pushed.

| Branch | Head | Contents |
|---|---|---|
| `main` | `4d8bb04` | Up to the Mirror redesign. Does **not** include the Wardrobe work below. |
| `screen-wardrobe` | `4888e98` | Completed Wardrobe and Outfit Builder checkpoint. Not merged into `main`. |
| `feature/brand-identity-integration` | one commit on `4888e98` | Brand Identity Phase 1, committed and pending physical-iPhone approval (see below). Not merged. |
| `foundation`, `feature/wardrobe-outfit-builder`, `feature/openai-mirror-mvp` | older | Earlier milestones, superseded by the branches above. |

### Brand Identity Phase 1 commit (on `feature/brand-identity-integration`)

Implemented and building, pending physical-iPhone approval:
- New: `tools/brand/generate_brand_assets.py`, `shared/.../ui/Brand.kt`, six vectors in `shared/src/commonMain/composeResources/drawable/`, Android `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml`, `values/ic_launcher_background.xml`, iOS `app-icon-dark-1024.png` and `app-icon-tinted-1024.png`, this file.
- Changed: Android adaptive icon XML and the 10 `mipmap-*` PNGs, iOS `AppIcon.appiconset` (`Contents.json`, `app-icon-1024.png`), `PrimaryNavigation.kt` (lockup on the Home placeholder), `Theme.kt` (comment only), `CLAUDE.md` (full working guide instead of a pointer to `AGENTS.md`).
- Deleted: the template `drawable/ic_launcher_background.xml` and `drawable-v24/ic_launcher_foreground.xml`.

## Wardrobe and Outfit Builder (implemented)

Covered by unit tests, and checked by touch on the Android emulator during development.

- **Your Items:** sample pieces (labelled "Sample") and user garments; category filter chips with counts; selection for a new look; archived pieces listed separately and restorable.
- **Garments:** add and edit with name, category, colour, brand, material and an optional photo from the system picker. Photos are stored as re-encoded display and thumbnail copies, outside cloud backups. A piece used in a saved look can't change category. Discarding an edit deletes any newly picked photo.
- **Garment Details:** read-only page with Edit and "Style this piece".
- **Outfit Builder:** opened from Your Items selection, Your Outfits (+ or an existing look) or Garment Details. Fixed header and Add pieces / Save look bar. Picker sheet that remembers its category within one Builder visit (category shortcuts override it).
- **Composition rules:** one top, bottom, outerwear and shoes each; a dress replaces top and bottom; accessories stack. Any pick that would replace a piece asks first ("Replace X with Y?"), from every entry point.
- **Moodboard:** category-aware editorial layout (`ui/Moodboard.kt`) showing every available piece; Your Outfits cards use a compact version with up to 4 pieces (3 on narrow cards) and a "+N" marker.
- **Saving:** Save as new, Update existing and variations (`— Variation`, then `— Variation 2`...). Separate names per save mode, explained validation, a short success notice, and Your Outfits scrolls to the saved look.
- **Integrity:** saved-look editing never touches the new-look selection; modified looks ask before discarding; emptied looks can't overwrite the saved record; missing garment references are shown and must be removed before saving; looks can be deleted after confirmation, leaving garments and photos intact.
- **Storage:** SQLDelight database version 4 with migrations from versions 1–3.

## Mirror (implemented, limited verification)

Code unchanged since `4d8bb04`. Mirror is the start tab.

- Flows: create a personal model from 3–5 reference photos, remove a garment's background (from the garment form), try a garment on an approved model, and Settings (backend address and dev token, with a connection check).
- Every send needs an explicit consent dialog; results are used only after approval.
- Images go through the local FastAPI backend in `mirror_backend/`, which holds the OpenAI key. Live generation stays off unless enabled in the backend's `.env` (see its README).
- **Limitations:** needs the backend running and reachable (`adb reverse` on Android, the Mac's `.local` name for an iPhone on Wi-Fi). The README's verification table records the flows passing only against a local stub on the Android emulator; image quality and physical-device runs aren't documented.

## Vision Lab and garment extraction

`../wadrova_vision_lab/` is a separate Python repo (its own Git history, `uv`, `pytest`). Nothing from it is in the app.

- **001, single-garment background removal:** BiRefNet General is the working research baseline (9 of 10 accepted in human review). Not approved for product use: hangers are kept, high memory use (16 GB peak), small sample, licensing unreviewed.
- **002, worn-outfit extraction:** the BiRefNet baseline was rejected for all 10 photos.
- **003, fashion human parsing:** FASHN Human Parser is a clear improvement (7 of 25 usable as is, 8 need cleanup), but layered garments merge. Human review is pending, and the model isn't commercially cleared.
- Its README still describes Experiment 001 as "first real-image run pending"; the experiment folders are newer.

In the app, garment background removal exists only through Mirror's OpenAI-backed extraction.

## Brand identity

**Phase 1 (implemented and committed, pending physical-iPhone approval):**
- **Launcher icons:**
  - **Android:** adaptive icon using the primary ivory symbol on brand Ivory, with a themed-icon monochrome layer (primary mono art) and PNGs for API 24–25 rendered from the official ivory app icon (square, and the same art on a circle for the round icon).
  - **iOS:** square full-bleed light (ivory), dark (graphite) and tinted (reversed) icons.
- **Shared marks:** `WadrovaLockup` and `WadrovaSymbol` in `ui/Brand.kt`. The symbol switches to the compact art below 48 dp.

All derived files come from `tools/brand/generate_brand_assets.py`, which reproduces them byte for byte (rechecked 2026-11-03 by regenerating into a scratch copy: all 22 files identical).

**Verification (2026-11-03):**
- **Builds and tests pass:** `:androidApp:assembleDebug`, `:shared:testAndroidHostTest` (239 tests, forced rerun), `:shared:iosSimulatorArm64Test` (247 tests, forced rerun), `:shared:compileKotlinIosArm64`, and `xcodebuild` for the iPhone 17 simulator.
- **iOS:** the built `Assets.car` contains the light, dark and tinted 1024 icons.
- **Android emulator (`Pixel_9_Pro`):** after the switch to the primary symbol, the launcher shows the primary-symbol adaptive icon. On the Home tab the lockup is 28 dp tall (84 px at 3x), in the master's proportions and exact brand colours, and isn't clipped.
- **Android masks (offline renders of the generated layers):** circle, squircle, rounded square, square and teardrop all show the whole symbol inside the safe zone. The monochrome layer renders as the primary mono art, with the lens as a hole, in light and dark themed colours. The emulator's themed-icons switch couldn't be turned on from adb, so the themed icon hasn't been seen on a launcher.
- **Not yet done:** physical-iPhone approval of the light, dark and tinted icons and the Home lockup.

**Rendering fidelity (measured at launcher sizes against the master SVGs):**
- **Lens hairline:** where the device rasterises the vector itself (the Android adaptive icon, the legacy PNGs, the in-app lockup), a faint grey edge shows around the lens: about 40 luminance levels on a 1 px edge. The generated output is pixel-identical to the source, so the cause is in the master art: the lens sits on top of shape A and shares its outer edge, and its 0.7-unit stroke is too thin at small sizes to hide A's edge. iOS is unaffected, because the system scales down the 1024 PNG. Only a change to the master art fixes it. **Deferred** by decision on 2026-11-03: no master or platform-specific workaround for now. When the master is corrected, rerun the generator.
- **Ivory wedge** between shape A's corner and shape B: part of the designed silhouette. It is under 0.5 px wide for its first tenth and then opens into the notch. It's not an artifact.
- **Placement:** no icon mask clips the symbol. On Android the symbol's furthest corner is 31 dp from the centre (safe radius 33 dp). The master tiles sit the symbol 1.5% below centre; the generator centres it exactly on Android (about 1 dp of difference).

**Open decisions:**
- **Symbol per platform: decided 2026-11-03.** Both platforms use the official primary symbol, with an ivory standard icon. iOS keeps its graphite dark and reversed tinted variants. The compact symbol stays available in the app only through `WadrovaSymbol` below 48 dp. Comparison sheets: `../Claude outputs/brand-phase1-verification/`.
- **Colour tokens:** UI tokens still differ slightly from the brand palette: app Cobalt `#5966E9` vs brand `#5A60E8`, app Ivory `#FAFAF8` vs brand `#F5F4F0`.

**Phase 2 (planned, not started): motion.** The six Lottie files are simple shape animations: no expressions, masks, text or images. The plan:
- **Library:** add one Compose Multiplatform Lottie renderer (e.g. Compottie), after checking it works with Compose 1.12.2 and Kotlin 2.4.21.
- **Native launch screens:** keep them instant with an Ivory background.
- **Brand splash:** play it once per cold start, dismissed when the app is ready. The full animation is 4.07 s; don't hold launch for it.
- **Reduced motion:** honour it by skipping the splash or jumping to its final frame.

## Known issues

- **iOS:** interaction has not been checked for the Wardrobe iterations since the picker and save changes, nor the brand icons. The dark and tinted icons are compiled into the app but haven't been seen on a device.
- **Unit tests only:** save and delete failure messages can't be forced on a device.
- **Unreachable empty state:** the picker's empty-category message can't appear, because sample pieces exist in every category.
- **Save sheet:** Back or swiping it down closes the sheet and drops the typed name.
- **Unimplemented product rule:** `docs/product.md` lists a swap mode that preserves position.
- **Stale READMEs:** the README's verification section dates from the Mirror MVP.
- **Launcher ring:** the Android emulator launcher draws a coloured ring around sideloaded debug app icons. This comes from the launcher, not the icon art.

## Asset locations

- **Brand masters:** `../wadrova-brand-svg/` (53 SVGs, 6 Lottie files in `motion/`, `previews/identity-review.html`, `previews/motion-preview.html`, `README.md`). Colourways are named for the ground they sit on.
- **Generated brand files:** `shared/src/commonMain/composeResources/drawable/wadrova_*.xml`, `androidApp/src/main/res/{drawable,values,mipmap-*}/ic_launcher*`, `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`.
- **Fonts:** `shared/src/commonMain/composeResources/font/`.
- **Mirror backend:** `mirror_backend/`. Never commit or print its `.env`.
