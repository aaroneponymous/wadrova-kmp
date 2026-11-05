# Development state

Last updated 2026-11-05. Describes only what the code, tests and recorded checks support. Update it at the end of each milestone.

## Branches

The remote is `origin` (private GitHub `wadrova-kmp`). `fe5a92a` and `fb0f152` on `feature/brand-identity-integration` were not pushed as of 2026-11-03; the other branches match `origin`.

| Branch | Head | Contents |
|---|---|---|
| `main` | `4d8bb04` | Up to the Mirror redesign. Does **not** include the Wardrobe work below. |
| `screen-wardrobe` | `4888e98` | Completed Wardrobe and Outfit Builder checkpoint. Not merged into `main`. |
| `feature/brand-identity-integration` | Phase 1 `fe5a92a`, then the Phase 2 splash and sample-wardrobe removal commit | Brand Identity Phase 1 and Phase 2 splash, and the sample wardrobe removed; all pending physical-iPhone checks. Not merged. |
| `foundation`, `feature/wardrobe-outfit-builder`, `feature/openai-mirror-mvp` | older | Earlier milestones, superseded by the branches above. |
| `feature/m4.4-garment-import` | `20c3b70` | M4.4 Add a piece › Extract from photo and durable garment imports (below). Committed, not pushed. |
| `feature/m4.5-garment-review` | `73a8488` | M4.5 review, save to wardrobe, finalize and corrections (below), then the Wadrova Studio design and S0 findings (`docs/WADROVA_STUDIO_DESIGN.md`, docs only). Pushed up to `2a46af2`; `73a8488` is local. See `docs/HANDOFF_M4_5_TO_NEXT_AGENT.md`. |
| `feature/m4.5a-transparent-cutouts` | from `73a8488` | M4.5A transparent garment cutouts (below). Committed locally, not pushed or merged. |

### Brand Identity Phase 1 commit (on `feature/brand-identity-integration`)

Implemented and building, pending physical-iPhone approval:
- New: `tools/brand/generate_brand_assets.py`, `shared/.../ui/Brand.kt`, six vectors in `shared/src/commonMain/composeResources/drawable/`, Android `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml`, `values/ic_launcher_background.xml`, iOS `app-icon-dark-1024.png` and `app-icon-tinted-1024.png`, this file.
- Changed: Android adaptive icon XML and the 10 `mipmap-*` PNGs, iOS `AppIcon.appiconset` (`Contents.json`, `app-icon-1024.png`), `PrimaryNavigation.kt` (lockup on the Home placeholder), `Theme.kt` (comment only), `CLAUDE.md` (full working guide instead of a pointer to `AGENTS.md`).
- Deleted: the template `drawable/ic_launcher_background.xml` and `drawable-v24/ic_launcher_foreground.xml`.

### Brand Phase 2 splash (committed with the sample-wardrobe removal)

- New: `ui/BrandSplash.kt`, `data/BrandIntroStore.kt`, `ui/ReducedMotion.kt` and `ui/LeaveForegroundEffect.kt` with Android and iOS actuals, `composeResources/files/wadrova-splash-{light,dark}.json`, iOS `LaunchBackground.colorset`, Android `values/themes.xml` and `values-v31/themes.xml`, tests `BrandSplashTest` and `BrandIntroStoreTest`.
- Changed: `App.kt` (splash ViewModel and overlay, `replayIntro` parameter), `LocalRepositories` and both `createLocalRepositories`, `MainActivity.kt`, `MainViewController.kt`, `AndroidManifest.xml` (theme), both iOS Info.plists (`UILaunchScreen`), `libs.versions.toml` and `shared/build.gradle.kts` (Compottie), the brand generator.

### Sample wardrobe removed (committed with the Phase 2 splash)

The app no longer ships sample garments. They were 15 illustrated pieces defined in code (`sampleWardrobe`), never stored in the database, so removing the code removes them from every install; nothing needed deleting.
- **Existing data:** saved looks that used sample pieces keep those ids (no foreign key) and show them as "no longer in your wardrobe". The look can be saved again once they're removed. No database migration or cleanup runs. User garments, archived garments, photos, looks, Mirror data and settings are untouched.
- **Removed:** `sampleWardrobe`, `WardrobeItem.isSample` and every "Sample" label or branch (card badge, Garment Details eyebrow and note, the Your Items note, Mirror's try-on filter). Also removed: the catalog parameter of `SqlDelightSavedLookRepository` (looks now validate only against stored garments) and the eight illustrations only samples used. The six category illustrations for pieces without photos stay.
- **Empty states:**
  - **Your Items:** Add a piece in the empty state.
  - **Your Outfits:** unchanged.
  - **Builder picker:** "Your collection starts here." with Add a piece, which leaves the builder the usual way (asking first about unsaved changes to a saved look) and opens the garment form.
- **Tests:** a test-only `testGarments` fixture replaces the samples. `NoSampleWardrobeTest` covers new installs, restarts and an earlier install with mixed looks on a real database, plus state tests for the empty picker.
- **Verified (2026-11-03):**
  - **Builds and tests:** both builds pass; 269 host and 277 iOS simulator tests pass.
  - **Emulator, upgrade from the Phase 1 build:** a look mixing a photographed user piece with two samples kept its user piece and photo. It showed two unavailable pieces and saved once they were removed, and the change survived a restart.
  - **Emulator, fresh install:** empty Your Items, Your Outfits and picker; first piece added with a photo from the picker route; first look saved; both persisted across a restart. With every piece archived, the archived list is still reachable.
  - **iOS:** builds and tests only; no Simulator interaction.

## M4.5A: Transparent garment cutouts (implemented, awaiting review)

Imported garments used to keep only their cutout flattened onto white (a JPEG), which showed as a white box on the Studio, selected and Sand surfaces. Each newly saved imported piece now also keeps the real cutout as a transparent PNG. Scope ends there: no backfill of existing garments, no Studio Clean, no AI imagery, no edge changes and no backend change.

- **Schema:** migration `7.sqm` (database version 8) adds the nullable `user_garment.cutout_image_id`, a `GeneratedImageStore` id. Existing garments migrate with it null. Manual garments never get one (`insertGarment` writes NULL; imported pieces use `insertImportedGarment`).
- **Sanitized PNG:** `data/CutoutPng.kt` `sanitizedCutoutPng` decodes the verified server PNG itself, over platform zlib (`Zlib.android.kt`, `Zlib.ios.kt`). It accepts only 8-bit RGBA, non-interlaced, with no color profile, at most 4096×4096 px and matching the candidate's recorded size. It sets RGB to 0 wherever alpha is 0 and writes a minimal lossless PNG (IHDR, IDAT, IEND). Visible pixels and all alpha values are kept bit for bit. Nothing is premultiplied or color-managed, so both platforms produce the same bytes. The server's PNG keeps photo color under its transparent pixels; that color is never stored.
- **Saving:** `GarmentReviewViewModel.save` is unchanged up to the transaction: cutouts are downloaded and verified, and each is stored as the flattened `photo_id` JPEG as before. Then, under `GeneratedImageGuard`, each cutout is sanitized and saved, and `saveReviewedPieces` links `cutout_image_id` in the same transaction as the garment, provenance and decisions.
  - A cutout that can't be kept (unexpected format, wrong size, storage failure) leaves the piece with its photo only.
  - The finalize manifest, decisions, garment ids and provenance are unchanged.
  - Cutouts from a failed attempt are deleted, and a repeated save keeps the first cutout and deletes the new one.
- **Display:** `GarmentVisual` (cards, selected cards, Garment Details, Outfit Builder, moodboard, Mirror's garment picker) and the edit-form preview show, in order: Mirror extraction, cutout, photo, illustration. Each falls back to the next when its file is missing. Cutouts load through `PhotoBitmapLoader.loadCutout` with the thumbnail-sized cache (24). No layout, scaling or background changed.
- **Lifecycle:** replacing or removing the photo clears the cutout and deletes its file (same rule as the extraction). Details-only edits and Mirror's extraction leave it alone.
- **Cleanup:** `MirrorRepository.removeUnreferencedImages` (run at every launch) counts `cutout_image_id` as a reference. It runs under the same `GeneratedImageGuard` as the save, so a cutout written but not yet linked can't be swept. One guard instance is wired in `createLocalRepositories` and shared through `LocalRepositories.generatedImageGuard`.
- **Verified (2026-11-05):**
  - **Tests:** 399 host (`testAndroidHostTest`) and 413 iOS simulator (`iosSimulatorArm64Test`, default simulator iPhone 17 / iOS 27.0) pass, plus the same 413-test binary on a temporary iPhone 16 / iOS 18.5 simulator (deployment target 18.2; the simulator was deleted afterwards). 416 connected-device tests pass on `Pixel_9_Pro_Fold` (API 36) and on `Pixel_9_Pro` (API 37).
  - **Builds:** `assembleDebug`, `compileKotlinIosArm64` and `xcodebuild` (iPhone 17 simulator) pass.
  - **Pixel checks:** the codec decodes a Pillow-written cutout (the backend's encoder and settings) exactly. Android `BitmapFactory` (unpremultiplied) reads every stored pixel exactly. The display decoder (`decodeToImageBitmap`, Android and iOS/Skia) keeps alpha exactly and opaque colors exactly, with half-transparent colors within 2 levels from premultiplied decoding.
  - **Not verified:** no emulator or iPhone visual check, because that needs a real server import, which wasn't authorized. No older Android image is installed (API 24–35). Grid scrolling and memory with many cutouts were not measured. Real SAM 3 cutout quality isn't claimed: tests use synthetic and Pillow fixtures.

## M4.5: Review pieces, save to wardrobe, finalize, corrections (implemented, awaiting review)

Built against `wadrova-backend` `3460189` (candidates, assets, corrections and finalize in `docs/API.md`).

- **Review:** Review pieces loads every candidate of the session (`include=superseded,below_threshold`) and groups revisions into lineages by `parent_candidate_id` (the API has no lineage id on a candidate). Lineages whose current revision is a detection are the pieces; below-threshold masks overlapping a piece are offered only as alternative outlines. Each cutout is downloaded and checked against its SHA-256; a failure shows "Image unavailable" with a retry, never a stand-in.
- **Choices persist** in `garment_import_review` (migration `5.sqm`, database version 6): selected, pinned revision, name, category, color, pending correction. Each row gets its local garment id when created. Selecting a piece pins the revision on screen, so a revision that arrives later doesn't replace it; a correction the user asked for does move the pin.
- **Details:** name (starts from the model phrase), category (pre-filled only from a resolved suggestion), color (always the user's choice, required by the garment schema). Brand and material are left empty.
- **Saving:** the selected cutouts are downloaded (or reused if already verified), stored through `GarmentPhotoStore.importBytes` (the same decode, orientation and white-flattening path as picked photos), then one transaction writes the `user_garment` rows, `garment_import_source` provenance (candidate and lineage-per-import each unique) and the finalize manifest (`garment_import_decision`), and moves the import to `FINALIZE_PENDING`. Photos stored by an attempt that fails before the transaction are deleted; a repeated save writes nothing new. The source photo copy is deleted once finished.
- **Finalize:** before the first request the stored decisions are reconciled with the server's current candidates (a lineage that appeared after saving was never accepted, so it's added as rejected), then frozen: `garment_import.finalize_sent_at` (migration `6.sqm`, database version 7) is written before the request leaves. From then on every attempt, including after a relaunch or a lost response, sends exactly the stored decisions to `POST /v1/imports/{id}/finalize` (no Idempotency-Key, as the API specifies). `missing_decisions` after freezing becomes "Saved, not synced" with **Add new pieces as not kept**, the only way the decisions are extended (the refused request stored nothing). `finalized_differently` and other refusals become "Saved, not synced" with **Try again**, which resends the same decisions. The import completes only when the server answers `finalized`; saved garments are never removed. Finishing with nothing selected sends an all-rejected manifest.
- **Corrections:** choosing another version (pins it), choosing an alternative outline (`select_alternative`, immediate), tapping points on the photo (`points`, mapped from the displayed photo to source pixels; the local photo is the exact upload) and describing the piece again (`reprompt`). Each correction's Idempotency-Key is stored before sending and the correction is followed by polling, also after leaving or relaunching. **Missing from the API:** cancelling a single correction (corrections are only cancelled with their import or by finalizing).
- **Verified (2026-11-04):**
  - **Tests and builds:** 376 host, 387 iOS simulator and 390 connected-device tests pass; `assembleDebug`, `compileKotlinIosArm64` and `xcodebuild` (iPhone 17 simulator) pass. The emulator upgraded M4.4 → M4.5 (database 5 → 6) and then to database 7, with its garment intact.
  - **Live, Android emulator over the private tailnet (Funnel off), `kmp-app-dev`, CC0 street-style photo, 1 import (`imp_1813…`):** real SAM 3 found 7 pieces; Review showed the verified cutouts (Pants with a resolved Bottoms suggestion, Coat with Outerwear). Saving with missing colors was refused and opened the first piece. With colors chosen, 2 pieces were saved; the server recorded the finalization as 2 accepted (mapped to the local `user-…` garment ids) and 5 rejected, covering all 7 lineages. An identical finalize replayed from the Mac, in a different order, returned 200 with `Idempotent-Replay: true` and the same decisions. Wardrobe showed exactly 3 garments (2 new with their cutout photos) before and after a force-stop and relaunch, with no pending import, and both new pieces built a look in the Outfit Builder.
  - **Not verified live:** corrections (only against the fake server; this import was finalized, and no second import was used), and the frozen-decision paths (lost response, `missing_decisions`, `finalized_differently`), covered by tests only.
  - **Physical iPhone (owner-reported):** the project owner reported on 2026-11-04 that they verified the M4.5 end-to-end experience on their physical iPhone. The agent didn't observe or reproduce that test and has no logs or step details from it. In the agent's own pass the iPhone couldn't reach the private endpoint (no Tailscale) and Funnel stayed off. The `kmp-app-dev` imports made from the phone (6 earlier, 4 from 02:36–02:38Z) were not opened or changed; whether any used personal photos is for the owner to say.

## M4.4: Extract from photo and durable garment imports (implemented, awaiting review)

Built against `wadrova-backend` `3460189` (`docs/API.md`, Mobile imports). On 2026-11-03 the deployed Mac API reported `schema_revision` `0007`.

- **Entry point:** the Wardrobe's + button and the empty-state "Add a piece" open a sheet: Extract from photo or Add manually (the existing form, unchanged). The builder's empty-picker "Add a piece" still goes straight to the manual form.
- **Picking starts the import.** No consent, preview or submit step. Cancelling the picker creates nothing; an unreadable photo shows "Couldn't use this photo". No camera capture (no camera integration exists).
- **Durability:** the photo is stored through `GarmentPhotoStore` (oriented, metadata-free 1600 px JPEG plus thumbnail). Its display bytes are read back, hashed (SHA-256) and recorded in `garment_import` (migration `4.sqm`, database version 5) before any network work. Those exact bytes are the upload; a changed or missing file is never sent. Picking the same bytes while an import is open reopens it. Garment-photo cleanup now keeps import photos.
- **Upload:** `POST /v1/imports` with the raw body, `Idempotency-Key` and `X-Client-Import-Ref` = the local id (`kmp-imp-<uuid>`), and `X-Wadrova-SHA256`. Retries reuse the same key and bytes, so a lost response is resolved by the server's replay. Transport and 5xx errors back off exponentially (2 s doubling to 60 s, with jitter) and stop after 6 consecutive failures; they resume on the next foreground, launch, saved connection or "Try again". 401 stops and asks to reconnect; 400/413/409 rejects the upload for good.
- **Processing:** `GET /v1/imports/{id}` polling at the server's `poll_after_seconds` (2–30 s), foreground only. queued/processing → "Finding your pieces", ready → "Ready to review · N pieces found", failed → the server's safe message, 404 → "No longer available".
- **Leaving and relaunch:** imports keep going when the page is left; the Wardrobe shows them under Imports at the top of Your Items. At launch, `UPLOADING` rows go back to waiting and every import is resumed. Polling pauses in the background. A running upload gets iOS's background-task grace (about 30 s); nothing runs after a force-quit, and the next launch resends.
- **Cancel / remove:** recorded first (`CANCEL_PENDING`), then `POST /v1/imports/{id}/cancel`. An import never sent is removed locally. One whose upload response was lost is found through `GET /v1/imports?state=active` by `client_ref`. A failed import is cancelled on the server too, which is how it's dismissed there. Removing deletes the local photo copy; the server keeps its copy (no deletion API yet).
- **Review:** "Review pieces" opens a placeholder for M4.5 that shows the server's piece count and says nothing has been added. No candidates are fetched or shown.
- **Credentials:** `CredentialStore` with iOS Keychain (`AfterFirstUnlockThisDeviceOnly`) and Android Keystore AES-GCM files under `noBackupFilesDir`. The Mirror token moved out of `app_setting` (on first read). The Wadrova address (https only) and token are entered on a development connection page; the token is never shown again and is checked with `GET /v1/me`.
- **Cancel without the server:** an import whose upload attempts were all definitely refused (401 or another 4xx, which store nothing) is removed locally. Only an attempt with an unknown outcome (lost response, timeout, 5xx, killed process) makes a cancel go to the server.
- **Mirror token (2026-11-04):** the token was exposed in an agent session, so it was rotated in `mirror_backend/.env` (now mode 600) and the backend restarted. Mirror Settings now masks the token field and never refills it with the saved token (blank keeps it), so the token stays out of the screen and the accessibility tree.
- **Verified (2026-11-04):**
  - **Builds and tests:** `assembleDebug`, `compileKotlinIosArm64` and `xcodebuild` (iPhone 17 simulator) pass. 335 host, 345 iOS simulator and 348 connected-device (`Pixel_9_Pro`) tests pass.
  - **Android emulator:** upgrade over the earlier install kept the garment and photo, and the Mirror token moved to the Keystore. Rotated Mirror token entered through the masked field: "Connected and ready" (status only, no OpenAI call), still connected after a force-stop and relaunch, and the token value never appeared in the UI tree. Add a piece sheet; picker cancel creates nothing; CC0 photo with no connection stays local; import survives a force-stop; Back chain; cancelling an unsent import removes it and its photo. The emulator reached the deployed API over Tailscale HTTPS; the revoked drill token got 401 and nothing was uploaded.
  - **iOS simulator (dedicated "Wadrova M4.4 Test" device, driven by a throwaway XCUITest harness outside the repo):** the pre-M4.4 build (`fb0f152`) saved a garment with a photo; installing this build over it kept both. Add a piece sheet; picker cancel creates nothing; manual add still opens the form; picking the CC0 photo with no token stays local; the import survives terminate and relaunch. **Keychain, in the real app:** a fake token was written, read back after relaunch ("saved; leave blank to keep"), refused by the server (401, nothing created), removed, and still gone after relaunch. This found a bug fixed here: every Keychain read failed with errSecParam (-50) because `kSecReturnData`'s boolean arrived as the integer 1 through Kotlin bridging. It's now added on the CF side.
  - **Keychain unit tests:** still can't run in the Kotlin/Native simulator test host (errSecNotAvailable, -25291; an ad-hoc entitlement-signed test binary is refused by the simulator). The in-app check above covers it.
  - **Live upload (2026-11-04, Android emulator, `kmp-app-dev` dev user, CC0 street-style photo, 2 imports, worker running):** `GET /v1/me` worked from the Mac and through the app ("Connected as kmp-app-dev."). The upload started on its own once the connection was saved, and the import reached ready with 7 pieces within seconds. The server's original matched the app's stored 1600 px copy (same SHA-256, 610,388 bytes), `client_ref` was the local id, and processing took 1 attempt. Replaying the same key and bytes from the Mac returned 200 with `Idempotent-Replay: true` and the same import, and there was still 1 import. The import survived a force-stop and relaunch, and Review pieces opened the placeholder. Discard cancelled it on the server and removed it locally with its photo. A second import showed "in line to be processed" (queued) and then reached ready before it could be cancelled, so it was discarded the same way. Both imports are cancelled; none was finalized.
  - **Not done:** any physical iPhone or Android device check.

## Wardrobe and Outfit Builder (implemented)

Covered by unit tests, and checked by touch on the Android emulator during development.

- **Your Items:** the user's garments only; category filter chips with counts (hidden while the wardrobe is empty); selection for a new look; archived pieces listed separately and restorable, also when every piece is archived. An empty wardrobe shows "Your collection starts here." with Add a piece.
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

**Phase 2 (implemented and committed, pending physical-iPhone check): animated splash.** Only the splash; the logo reveal, Style Shift loop and lens pulse aren't used yet.
- **Files:** `wadrova-splash-light.json` and `-dark.json` are Lottie 5.7.0, 1080 × 1920, 60 fps, 244 frames (4.07 s). They contain 11 shape layers and one solid background layer, with only opacity, position and scale keyframed. There are no assets, fonts, masks, mattes, effects or expressions. The final frame matches the approved primary symbol and stacked lockup to within 0.03 px. The generator copies both files unchanged into `composeResources/files/`.
- **Library:** Compottie 2.3.3 (`io.github.alexzhirkevich:compottie`, Maven Central, released 2026-10-05). It is built against Kotlin 2.4.0 and Compose 1.12.1 and publishes Android, `iosArm64` and `iosSimulatorArm64`. Expressions are switched off. `compottie-core` alone would need an expression engine wired in through internal APIs, so the main artifact is used. It also brings in okio, keight and atomicfu.
- **Native launch:** Android uses `Theme.Wadrova` with an Ivory window background and, on Android 12+, an Ivory system splash around the launcher icon. iOS uses a `UILaunchScreen` filled with the generated single-appearance `LaunchBackground` colour (Ivory even in system dark mode, like the app). No Lottie plays before the app starts.
- **In-app splash:** `BrandSplash` is an overlay drawn over the app, which starts underneath as usual: the same ViewModels and the same start tab (Mirror). It isn't a navigation destination. `BrandSplashViewModel` reads one `app_setting` row (`brand_intro_seen`), with no schema change. Until that row is read, the splash shows plain Ivory, which lasts a few frames.
- **When it plays:**
  - **First eligible cold launch:** the full splash plays once and is recorded when it completes, is skipped, gets Back, or the app goes to the background.
  - **Later launches:** straight to the app, after a 220 ms fade of the Ivory cover.
  - **Reduced motion** (Android "Remove animations", iOS Reduce Motion): the splash is skipped and the launch isn't recorded.
  - **Failures:** a load failure, a 2 s load timeout or an unreadable flag also goes straight to the app.
- **Behaviour:**
  - **Skip:** a Skip button sits top-right, inside the safe area.
  - **Back** skips (Android).
  - **Touches** don't reach the app underneath.
  - **Rotation** resumes the animation, because the clock lives in the ViewModel.
  - **Timing:** progress is played at the composition's own keyframe timing; no speed changes.
- **Appearance:** the app is light-only (iOS forces `.light`, Android uses light system bars), so the light splash always plays. `BrandSplash(dark = true)` and the bundled dark file are ready for when a dark theme exists. Showing the dark splash now would flash from graphite into the light app.
- **Replaying for testing, keeping app data:**
  - **Android:** `adb shell am start -n com.example.wadrova/.MainActivity --ez replayIntro true`
  - **iOS:** add `-WadrovaReplayIntro YES` under Xcode › Scheme › Run › Arguments, then remove it again.
- **Verification (2026-11-03):**
  - **Builds and tests:** both builds pass, plus 256 host, 264 iOS simulator and 266 connected-device tests.
  - **Emulator (`Pixel_9_Pro`):**
    - Interactions checked: first launch, a routine relaunch, Skip, Back, Home then return, touch blocking, reduced motion, rotation mid-intro, and 360 dp, 16:9 and 1840 × 2208 screens.
    - Captured frames match lottie-web 5.12.2 reference frames (the brand preview's player) at mean colour difference 0.04–0.27, and play at 60 fps of animation time.
  - **iPhone 17 simulator:** screenshots only, no touch. They show the first launch (frames match the reference), a routine relaunch, the replay argument, an Ivory launch screen in system dark mode, and Reduce Motion skipping the splash.
  - **Not yet done:** a physical iPhone has not been checked.
- **Performance (emulator, debug build):**
  - The splash renders at about 34 fps (median frame 45 ms) on the emulator. Smoothness needs checking on real devices.
  - Cold start `TotalTime` is unchanged (median 1.13 s against 1.09 s before, within noise).
  - Memory is about +4.6 MB PSS while the splash plays.
  - The debug APK is +1.10 MB (dex +4.06 MB uncompressed). The debug iOS simulator app is 68.7 → 82.4 MB, mostly the unoptimised Kotlin/Native debug binary.
- **Known gaps:**
  - On Android 12+ the system splash shows the static symbol, then the in-app splash starts on an empty Ivory frame and the leaves slide in. The brand preview's "app open from icon" handoff isn't built.
  - The splash still carries the deferred lens hairline from the master art.

## Known issues

- **iOS:** interaction has not been checked for the Wardrobe iterations since the picker and save changes, nor the brand icons. The dark and tinted icons are compiled into the app but haven't been seen on a device.
- **Unit tests only:** save and delete failure messages can't be forced on a device.
- **Save sheet:** Back or swiping it down closes the sheet and drops the typed name.
- **Unimplemented product rule:** `docs/product.md` lists a swap mode that preserves position.
- **Stale READMEs:** the README's verification section dates from the Mirror MVP.
- **Emulator data persists:** `-no-snapshot-save` only skips saving the RAM snapshot. Installed apps and their data stayed across emulator restarts in this session.
- **Launcher ring:** the Android emulator launcher draws a coloured ring around sideloaded debug app icons. This comes from the launcher, not the icon art.

## Asset locations

- **Brand masters:** `../wadrova-brand-svg/` (53 SVGs, 6 Lottie files in `motion/`, `previews/identity-review.html`, `previews/motion-preview.html`, `README.md`). Colourways are named for the ground they sit on.
- **Generated brand files:** `shared/src/commonMain/composeResources/drawable/wadrova_*.xml`, `shared/src/commonMain/composeResources/files/wadrova-splash-*.json`, `androidApp/src/main/res/{drawable,values,mipmap-*}/ic_launcher*`, `iosApp/iosApp/Assets.xcassets/{AppIcon.appiconset,LaunchBackground.colorset}/`.
- **Fonts:** `shared/src/commonMain/composeResources/font/`.
- **Mirror backend:** `mirror_backend/`. Never commit or print its `.env`.
