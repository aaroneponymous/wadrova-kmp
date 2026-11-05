# Wadrova Studio: architecture and implementation plan (M4.5B, proposed)

Written 2026-11-04. Research and planning only: no code, schema, backend, commit or paid call was made. Every file
reference was read at the commits below. Claims about the OpenAI API come from official documentation fetched today
and are cited in section 4. Anything not confirmed there is marked **UNVERIFIED**.

**Revision 2026-11-04 (owner review): factual API corrections only.** No product decision, milestone or decision
D1–D16 changed. Corrections:
- Transparent output is documented for PNG and WebP (4.2).
- `input_fidelity=high` is not assumed for Sunburst (4.2, 5.1).
- Sunburst pricing is token-based, and the per-image figures are labelled as `gpt-image-2` derivations, not Sunburst
  prices (8.1, 8.2).
- Zero Data Retention eligibility is not the same as enablement (4.4).

| Repo | Branch | HEAD | State |
|---|---|---|---|
| `wadrova_kmp` | `feature/m4.5-garment-review` (tracks `origin`) | `2a46af2` (impl. `a906cc7`) | clean |
| `wadrova-backend` | `feature/mobile-import-api` (tracks `origin`) | `1ad57e6` (code `3460189`, schema `0007`) | clean |

**Handoff correction.** `HANDOFF_M4_5_TO_NEXT_AGENT.md` §A says M4.5 is "not pushed". The branch now tracks
`origin/feature/m4.5-garment-review`, which matches the project memory ("pushed 2026-11-04; not merged").

---

## 0. Summary of recommendations

1. **Entry point B: save first, then Studio.** Studio starts from **Garment Details** of a saved garment. The M4.5
   review, save and finalize path is untouched.
2. **Deterministic first, generative optional.**
   - The default "Clean" image (Strategy A) keeps the real garment pixels.
   - "Studio (AI)" (Strategy B) is an opt-in enhancement, always labelled as AI-generated.
3. **The first slice ships without OpenAI.** Imported garments are stored as white-flattened JPEGs, so they draw as
   white boxes on the Outfit Builder's moodboard. Storing the real transparent cutout fixes that and is a visible win
   on its own.
4. **The server is the source and the gateway.** The RGBA cutout already lives on the backend (asset of the accepted
   candidate). Studio jobs reference it by candidate id: the phone uploads nothing new, and the OpenAI key stays on a
   Mac-side `studio` worker pool. The Razer SAM 3 pool is untouched.
5. **Additive schema only.**
   - KMP: database 7 → 8 adds a `garment_image` table and a `studio_request` table, plus one nullable column,
     `user_garment.display_image_id`.
   - Backend: `0008` adds `studio_renders`, two asset kinds and quota accounting.
   - Nothing existing is rewritten.
6. **Fidelity is checked, measured and disclosed, not assumed.**
   - Each AI result carries automated warnings (color ΔE, silhouette, lettering).
   - The comparison view is mandatory before accepting.
   - Shipping needs a human-reviewed benchmark that passes owner-approved thresholds.

---

## 1. Existing architecture relevant to Studio

### 1.1 How a garment's image is stored and shown today

| Concern | Where | What it does |
|---|---|---|
| Garment row | `shared/src/commonMain/sqldelight/.../Garment.sq` `user_garment` | `photo_id` (garment photo) and `extracted_image_id` (Mirror's approved OpenAI background removal). No delete, only `archived_at`. |
| Domain | `domain/UserGarment.kt` | `photoId`, `extractedImageId` ("kept alongside the photo rather than replacing it") |
| Photo files | `data/GarmentPhotoStore.kt` (`AndroidGarmentPhotoStore`, `IosGarmentPhotoStore`) | Re-encoded **JPEG** 1600 px display and 640 px thumbnail. `importBytes` **flattens transparency onto white**. Ids are immutable. |
| Generated files | `data/GeneratedImageStore.kt` (`AndroidGeneratedImageStore`: `noBackupFilesDir/generated_images/<id>.png`; iOS: backup-excluded) | Stores raw bytes as `.png`, keeping alpha. Ids are immutable ("regenerating can't overwrite an approved image"). |
| Display resolution | `ui/GarmentVisual.kt` `GarmentVisual` | `extractedImageId` → `photoId` → illustration. `PhotoBitmapLoader` caches by id. |
| Call sites | `GarmentDetailsScreen.kt:109` (4:5 tile on `WadrovaColors.Studio`), `WardrobeComponents.kt:157` (cards), `OutfitBuilderScreen.kt:328`, `Moodboard.kt:214,267` (flat-lay on `WadrovaColors.Sand`), `MirrorScreens.kt:640`, `GarmentFormScreen.kt:286` | All go through `GarmentVisual` or the same loaders |
| Photo replace | `data/GarmentRepository.kt` `update(... PhotoChange)` | When the photo changes, the extraction is cleared and deleted ("can't outlive it"). Old files are deleted after commit. |
| Extraction set | `GarmentRepository.setExtractedImage` | Replaces `extracted_image_id` and deletes the previous file |
| Orphan cleanup | `MirrorRepository.removeUnreferencedImages` (run from `MirrorViewModel` init, `MirrorState.kt:108`, which runs at every launch because the app opens on Mirror) | Deletes every generated file not referenced by `mirror_model`, `try_on_result` or `user_garment.extracted_image_id`. **Any new image reference must be added here, or launch deletes it.** |
| Photo cleanup | `GarmentRepository.removeUnreferencedPhotos` (`WardrobeUiState.kt:484`) | Keeps `user_garment.photo_id` and `garment_import.photo_id` |

### 1.2 The M4.5 import path (must stay as it is)

- **Save.** `ui/ImportReviewState.kt` `GarmentReviewViewModel.save` (line 373) proceeds in this order:
  1. Refetches candidates.
  2. Builds the manifest.
  3. Takes each verified RGBA cutout (`state.images` or `api.assetContent`, SHA-256 checked) and runs
     `photos.importBytes(bytes)`: **flattened onto white JPEG**.
  4. Calls `GarmentImportRepository.saveReviewedPieces`, one transaction writing `user_garment`,
     `garment_import_source`, `garment_import_decision` and `FINALIZE_PENDING`.
- **Finalize.** `ui/ImportState.kt` `GarmentImportViewModel.finalize` (frozen manifest, `finalize_sent_at`, completion
  only on `status: finalized`).
- **Provenance that survives completion.**
  - `garment_import_source(garment_id, import_id, server_import_id, lineage_id, candidate_id UNIQUE, revision, photo_id)`.
  - The completed `garment_import` row keeps `server_session_id`.
  - The source photo copy **is deleted** at completion.
- **Consequences for Studio:**
  - On the device, an imported garment has only a white-background JPEG of its cutout. There is no alpha channel and
    no original photo.
  - The full-resolution RGBA cutout and the original photo remain on the server: no retention exists yet, and M4.6
    plans one.
  - The device can still name the exact server candidate through `garment_import_source.candidate_id`.
  - The server knows, through `import_decisions` (`accepted`, `client_garment_id`), which candidate became which
    local garment.

### 1.3 A precedent: Mirror's OpenAI garment extraction

- `mirror_backend/app/images.py` `ImageService.extract_garment` calls `client.images.edit(model=settings.wadrova_image_model, image=[photo], prompt=GARMENT_EXTRACTION, size="auto", background="transparent", quality=..., output_format="png")`.
  The model comes from `.env` (not read: credentials rule). The code default is `"gpt-image-2.5-sunburst"`, which is
  confirmed as a current OpenAI model in section 4.
- The KMP flow is in `ui/MirrorState.kt`:
  - `startExtraction`, then `ConsentRequest.GARMENT_EXTRACTION`.
  - `extract`, then the candidate stored in `GeneratedImageStore`.
  - `useExtractedImage`, then `setExtractedImage`.
- The UI is `ui/MirrorScreens.kt` `ExtractionScreen`: previews on neutral, dark and bright backgrounds, "Use Extracted
  Image", "Regenerate", and consent copy at line 748.
- **What to reuse:**
  - The consent-per-send pattern.
  - Immutable generated ids.
  - Accept only after preview.
  - Provenance stored with the result (`try_on_result.generation_model/quality`).
- **What not to reuse:**
  - It is synchronous: the HTTP request is held for up to 300 s (`GENERATION_TIMEOUT_MILLIS`).
  - It has no idempotency, so a lost response pays again.
  - It uses a LAN dev token, keeps no server record and sends the whole photo.

**Interaction to resolve.** For an imported garment, `photo_id` is already the cutout, so Mirror's "extraction" sends
the cutout to OpenAI to remove a background that is already white. Studio's display choice has to define how it ranks
against `extracted_image_id` (section 6).

### 1.4 Backend pieces Studio would reuse (`wadrova-backend`)

| Mechanism | File | Reuse |
|---|---|---|
| Postgres job queue, leases, fencing, pools | `jobs.py` (`enqueue`, `claim`, `heartbeat`, `complete`, `fail`, `cancel`), `models.Job` (`kind`, `pool`, `payload`, `max_attempts`) | New kinds `studio_clean` and `studio_generate` in a **new pool `studio`**. SAM 3 workers claim only their own pool (decision 14), so the Razer is unaffected. |
| Attempt-aware artifact ledger | `artifacts.py` (decision 15) | Result images are staged under `…/a<attempt>/…` and published only by the fenced completion. Dead attempts' files are swept by `maintenance`. |
| Assets served through the API, hash-verified | `assets.py`; `GET /v1/assets/{id}/content` (decision 6); KMP `WadrovaApi.assetContent` refuses mismatched bytes | Same path for Studio results |
| Idempotency per (owner, route, key) | `idempotency.py` (decision 8) | `POST /v1/studio-renders` |
| Ownership | Every row has `owner_id`; cross-user access → 404 | Same |
| Image libraries | `pyproject.toml`: `pillow>=11`, `numpy>=2` | Enough for the deterministic pipeline and the basic fidelity metrics. No new dependency for Strategy A. |
| Asset kinds | `models.Asset` CHECK `kind IN ('source_original','candidate_mask','candidate_cutout','candidate_preview')` | Needs migration `0008` to add `studio_clean` and `studio_generated` |
| Existing sketch | `docs/MOBILE_GARMENT_IMPORT_ARCHITECTURE.md` §6b, milestone row M4.7 | This plan refines it. That sketch assumed the cutout lives in `extracted_image_id`; in the KMP code it is `photo_id` (flattened), so the sketch's local model needs correcting. |

Cutout facts (decision 20): RGBA cropped to the bbox, **original pixels only**. Mask edges come from 288×288 logits
upsampled (unmeasured edge quality). Masks are in the EXIF-oriented frame.

---

## 2. Product UX and entry point

### 2.1 Entry point: B (save, then Studio). Recommended.

| | A: generate during review | **B: Studio from a saved garment** |
|---|---|---|
| M4.5 save and finalize | Must wait for, or be entangled with, minutes-long paid jobs. The review state and the frozen manifest would need to carry Studio state. | Untouched. Studio needs only a saved garment and its provenance. |
| Failure isolation | A failed render blocks or complicates "Save" | A render can fail forever with no effect on the wardrobe |
| Cost | Users may render pieces they then reject | Renders only pieces the user kept |
| Server binding | The candidate isn't accepted yet | `import_decisions` proves the candidate was accepted as `client_garment_id` |
| Works for older garments | No | Yes: every imported garment with provenance |
| Friction | One flow | One more tap per garment, later |

There is no strong product reason for A. The only gain is "polished images straight away", and a post-save line
covers it without coupling the flows: *"2 pieces saved. You can make a Studio image from each piece's page."*

**Eligibility in v1:**
- The garment has `garment_import_source` provenance (it was imported).
- Its import is `COMPLETED` (finalize confirmed).

A piece that shows "Saved, not synced" is not yet eligible; the UI says "Studio is available once this piece has
synced". Manually added garments, which have no server cutout, are out of scope for v1 (decision D5).

### 2.2 Surfaces

| Surface | Change |
|---|---|
| **Garment Details** (`GarmentDetailsScreen`) | Under the image tile: an **image switcher** of chips (Photo · Clean · Studio·AI, showing only the ones that exist), the label "Shown in your wardrobe", and an action **"Studio image"** that opens the Studio page. AI images carry a small "AI" badge wherever they are shown full size. |
| **Studio page** (new overlay, same pattern as import pages: route held by a ViewModel and drawn over the Wardrobe tab in `App.kt`) | Holds the states in 2.3 |
| **Your Items / Outfit Builder / Moodboard** | No new UI. `GarmentVisual` resolves the garment's selected display image. Transparent Clean and Studio images composite on Sand and Studio backgrounds, which removes the white-box problem. |
| **Garment Review** | No change in v1, apart from the optional post-save line |
| **Edit garment** (`GarmentFormScreen`) | The preview uses the same resolution. Replacing the photo follows decision D7. |
| **Mirror try-on** | Keeps using `PHOTO` or `EXTRACTED`. A Studio (AI) image is **not** offered as try-on input by default (AI-on-AI drift; decision D9). |

### 2.3 Studio page states

```
                ┌────────────── Idle ──────────────┐
                │ Original (cutout on neutral)     │
                │ [Make clean image]  (free, no    │
                │                      consent)    │
                │ [Generate Studio image · AI]     │
                │ Remaining today: 3 of 5          │
                └───────┬──────────────────┬───────┘
          Clean request │                  │ AI request
                        │          ┌───────▼────────┐
                        │          │ Consent dialog │ decline → Idle (nothing sent)
                        │          └───────┬────────┘
                ┌───────▼──────────────────▼───────┐
                │ Queued / Processing               │ "You can leave this page; we'll keep it."
                │ [Cancel]                          │ (Cancel = stop if not started; a running
                └───────┬───────────────┬──────────┘   AI job finishes and is discarded)
                        │ succeeded     │ failed
                ┌───────▼────────┐   ┌──▼─────────────────────────┐
                │ Preview +      │   │ Failure: plain reason,     │
                │ Compare        │   │ retryable? [Try again]     │
                │ fidelity notes │   │ quota? "Try tomorrow"      │
                │ [Use this]     │   │ refused? no retry          │
                │ [Discard]      │   └────────────────────────────┘
                │ [Regenerate]   │ → consent again (AI) → Processing
                └───┬───────┬────┘
            accept  │       │ discard
     ┌──────────────▼┐   ┌──▼────────────┐
     │ Saved to piece│   │ Back to Idle  │ (preview file deleted; server told "rejected")
     │ "Show in      │   └───────────────┘
     │  wardrobe?"   │
     │ [Yes] [Keep   │
     │  current]     │
     └───────────────┘
```

- **Original image.** The garment's current cutout, drawn on the Studio background. Once the transparent cutout
  exists (S2), the real alpha is used.
- **Consent (AI only).** A dialog before **each** request, Regenerate included. It matches Mirror and product.md.
  Copy: *"Wadrova will send this piece's cutout (the garment only, from your import) from the Wadrova server to OpenAI
  to create a product-style image. AI may change details, and areas hidden in your photo will be invented. Nothing
  changes on your piece unless you choose to use the result."* The copy is versioned (`studio-consent-v1`) and the
  version is sent with the request.
  - **Clean** sends nothing to a third party, and the server already holds the cutout. No dialog; the page still
    states that the image is processed on the Wadrova server.
- **Processing.** A durable state that survives leaving and relaunching. It shows queued and running, and is polled
  in the foreground only, like imports.
- **Generated preview and comparison.** Required before accepting, in two modes:
  - Side by side on the same background.
  - A single frame with a **press-and-hold** to show the original. Both are scaled to the same garment bounding box
    so shape changes are visible.

  Fidelity notes appear above the actions (section 7.4), for example "Color looks different (noticeable)", "Lettering
  could not be matched" or "Shape differs from your photo". A permanent line on AI results reads: *"AI-generated.
  Parts not visible in your photo were created by AI."*
- **Accept ("Use this").** Stores the image as a garment image with provenance, then asks whether to show it in the
  wardrobe. The default is *Keep current* for AI images and *Show* for Clean images (decision D8).
- **Reject ("Discard").** Deletes the local preview and records `rejected` on the server so the result can be deleted
  there.
- **Regenerate.** A new request with a new idempotency key: AI asks for consent again and counts against the quota.
  The previous preview stays visible until the new one is ready, and is then replaced (only one pending preview per
  garment).
- **Failure.** Shows a plain reason with a retry when that is safe. A failure never changes the garment.
- **Selecting the displayed image.** On Garment Details, chips switch between the accepted images. Switching is a
  local, instant, reversible database write. Removing an accepted Studio image (overflow → "Remove Studio image")
  confirms through a `pending*` dialog, per the conventions in `CLAUDE.md`.

---

## 3. Deterministic versus generative

### 3.1 Strategy A: deterministic cleanup ("Clean")

A server job, `studio_clean`, CPU only, using Pillow and numpy, versioned as `clean-v1`. Inputs: the candidate's RGBA
cutout. Optionally, the source photo and mask for edge recovery.

1. **Alpha refinement.**
   - Erode by about 1 px, then feather (Gaussian σ ≈ 0.75 px at 1600 px scale) to hide the jagged edges of upsampled
     288² logits.
   - Remove islands below 0.2 % of the mask area, and fill pinholes below 0.05 %.
   - Clamp near-transparent fringe.
2. **Color decontamination at the edge.** Pull edge RGB toward the interior color so the source background doesn't
   halo.
3. **Canvas standardization.**
   - Crop to the alpha bounding box.
   - Scale so the longer side fills 86 % of a fixed canvas (proposed 1600×2000, matching the app's 4:5 detail tile)
     and center it.
   - Footwear and bags sit on a baseline at 88 % height.
4. **Output** a transparent PNG, the primary artifact: the app composites it on any background. An optional variant
   adds a soft synthetic contact shadow on the neutral background (decision D10).
5. **No pixel invention.** Occlusions such as an arm, hair or a bag strap stay as holes or flat edges. Optional later
   step: "fill small holes up to N px by edge-aware diffusion" (`clean-v2`). It is still deterministic, but it is
   invention, so it would be disclosed.

On-device versus server: the server is recommended. It is one implementation instead of Android `Bitmap` plus iOS
CoreGraphics, it is reproducible and versioned, it reads the full-resolution cutout and source, and the metrics can be
computed in the same place. The cost is that Clean needs the network, which is acceptable for an optional feature.

### 3.2 Strategy B: generative ("Studio · AI")

A server job, `studio_generate`, run by the `studio` pool, calling the OpenAI image edit endpoint (section 4). The
prompt is versioned (`studio-gen-v1`), with a category-specific framing:
- Tops and outerwear: ghost-mannequin or flat-lay front view.
- Bottoms: flat-lay.
- Footwear: three-quarter side.
- Bags: front.

It asks to preserve color, pattern, logos and lettering, hardware, stitching and silhouette, and forbids redesign,
added items, people and text.

### 3.3 Which input to send (Strategy B)

| Input | Fidelity | Clean product look | Privacy | Verdict |
|---|---|---|---|---|
| Transparent RGBA cutout | Good on visible pixels. Fully transparent pixels still carry arbitrary RGB, and whether the model reads alpha as "empty" or sees fringe colors depends on the model (UNVERIFIED for the current model). | Good | **Best**: garment pixels only | Candidate |
| **Cutout composited on neutral background** (flat light gray, ~#F2F2F0, padded to the output aspect) | Same pixels. Removes the alpha ambiguity, and gives an exact background color to detect in the output. | Good | Best | **Recommended default** (to confirm by benchmark) |
| Original photo crop around the garment | Most context (lighting, folds), but includes the wearer's body, skin, face or hair, and other garments. The model tends to reproduce or "keep" those. | Worse: has to remove more | **Worst**: sends parts of the person | Reject for v1 |
| Original crop + segmentation mask | For current GPT image models, OpenAI documents the mask as guidance rather than an exact pixel lock (see section 4). Still sends the person. | Mixed | Worst | Benchmark only, no product use |
| Multiple reference images | Would help hidden sides, but each garment has one photo today | n/a | n/a | Later, if multi-photo import exists |

**The core tension.** A worn-garment cutout has perspective, folds, occlusions and an unseen back and inside. A
"product photo" re-poses the garment (flattened, symmetrical, full front). Re-posing **requires inventing** the
regions that were hidden. More "product-like" therefore means less "verifiably yours". Hence:
- The product rule is "never claim invented regions are verified". The UI labels every AI image, and provenance stores
  that it is generative.
- The prompt offers a **"keep pose" style** (`studio_preserve`): it cleans light, background and wrinkles while
  keeping the cutout's pose and outline. That gives the most fidelity and allows a silhouette check. A **"product
  pose" style** (`studio_flatlay`) is more attractive and less faithful. The benchmark decides whether both ship
  (decision D3).

### 3.4 Comparison

| | A: Clean | B: Studio · AI |
|---|---|---|
| Visual quality | Real photo look. Lighting, wrinkles and occlusion holes remain. Consistent framing across the wardrobe. | Polished e-commerce look and consistent lighting |
| Fidelity | **Exact** on visible pixels; nothing added | Approximate. Color drift, simplified patterns, garbled or altered lettering, smoothed wear and invented hidden regions are expected failure modes |
| Implementation cost | Low: one CPU job with Pillow and numpy, no third party | Medium–high: key handling, quotas, consent, fidelity checks, cost accounting, content refusals |
| Inference cost | About zero (milliseconds to seconds of Mac CPU) | Per-image OpenAI charge (section 8) |
| Latency | Under 2 s of processing plus queue and poll | Tens of seconds to minutes (section 8) |
| Failure modes | Bad masks show plainly (holes, bits of neighbors), and edge feathering can soften fine details like fringes and laces | Silent identity drift (looks good, isn't your item), refusals, timeouts, paid-but-lost results |
| Determinism | Same input → same bytes (versioned) | Non-deterministic; regenerate gives a different image |
| Honesty | Can be labelled "your photo, cleaned" | Must be labelled "AI-generated" |

**Recommendation.** Clean is the default image enhancement and ships first. Studio · AI is optional, gated by a feature
flag and benchmark thresholds, and never automatic.

---

## 4. OpenAI API: current capabilities and limits

Checked 2026-11-04 against official docs only. `platform.openai.com/docs` now redirects to
`developers.openai.com/api/docs`. Two pages returned 403 and **could not be read**: `openai.com/api/pricing` and
`openai.com/policies/usage-policies`. Re-verify everything here when S0b starts; this lineup changed during 2026.

### 4.1 Models ([models](https://developers.openai.com/api/docs/models), [deprecations](https://developers.openai.com/api/docs/deprecations))

| Model | Status | Relevance |
|---|---|---|
| `gpt-image-2.5-sunburst` (snapshot `gpt-image-2.5-sunburst-2026-09-08`) | Current. OpenAI calls it its "most capable model for image generation and editing", recommended where "editing precision matters most". It is the **default model of `/v1/images/edits`**. | **Proposed for Studio, pinned to the dated snapshot.** It is also `mirror_backend`'s code default, which settles D14's naming question. |
| `gpt-image-2.5-flare` (snapshot `-2026-09-08`) | Current; "fast, high-quality everyday" | Benchmark as the cheaper and faster option |
| `gpt-image-2.6-sunburst` / `-flare` | Listed with no dated snapshot and no Batch support. Preview or GA status isn't stated. | Not for production pinning yet |
| `gpt-image-2` (`-2026-04-21`) | Still available; processes every input at high fidelity automatically | Fallback |
| `gpt-image-1.5`, `gpt-image-1-mini`, `chatgpt-image-latest` | **Shut down 2026-12-01** | Don't use |
| `gpt-image-1`, `dall-e-2`, `dall-e-3` | Shut down | — |

### 4.2 `/v1/images/edits` ([reference](https://developers.openai.com/api/reference/resources/images/methods/edit), [guide](https://developers.openai.com/api/docs/guides/image-generation))

- **Images.** 1–16 input images (file id or URL/base64), each under 50 MB. The first image is capped at 16.7 MP.
  PNG and WebP appear in the examples; a full list of accepted formats is **UNVERIFIED**.
- **Mask.**
  - One mask, applied to the first image. It must match the image's size and format and have an alpha channel.
  - **It is soft guidance:** "Masking with GPT Image is entirely prompt-based"; the model "may not follow its exact
    shape with complete precision." The original-crop-plus-mask input therefore can't lock garment pixels.
  - Which alpha region is edited is **UNVERIFIED** on the current page.
- **Prompt.** Up to 32,000 characters.
- **`input_fidelity` (`low`/`high`).**
  - Documented for `gpt-image-1.5`.
  - Must be **omitted** for `gpt-image-2`, which is always high fidelity.
  - **Not documented for `gpt-image-2.5-sunburst`. Do not assume `input_fidelity=high` is supported.** Studio omits
    the parameter unless the pinned model's documentation lists it. S0b may probe whether it is rejected or ignored
    and record the result. No surcharge is documented.
- **`size`.**
  - Standard options are `1024x1024`, `1024x1536` and `1536x1024`.
  - The 2 and 2.5 families also take custom sizes: multiples of 16, aspect 1:3 to 3:1, up to 3840×2160 (above
    2560×1440 is "experimental").
  - A 4:5 size such as `1280x1600` therefore matches the app's detail tile directly. That needs verifying in S0b.
- **`quality`.** `low`, `medium`, `high` or `auto`; `xhigh` and `max` only on 2.5.
- **`background`.** `transparent`, `opaque` or `auto`. **Transparent output is supported with `output_format` png or
  webp**; it is labelled "preview" for `gpt-image-2`. S0b measures edge quality on garments rather than whether the
  option works. If transparent edges prove poor, use an opaque background in a known neutral color, then server-side
  matting against that color.
- **Output and other options.**
  - `output_format`: png, jpeg, webp or gif. `output_compression` applies to jpeg and webp.
  - `n` from 1 to 10. Studio pins **1**.
  - `moderation`: `auto` or `low`. Proposal: keep `auto`.
  - `safety_identifier`: up to 128 characters; replaces `user`. Send a **salted hash of the owner id**, never an email
    address or token.
  - `stream` with `partial_images` from 0 to 3, each partial costing an extra 100 output tokens.
- **New on 2.5 and 2.6.** `markup`, a transparent annotation layer that can't be combined with `mask`, and
  `complexity` (OpenAI warns that higher values can add "unwanted changes"). Studio should leave both unset.

### 4.3 Responses API `image_generation` tool ([guide](https://developers.openai.com/api/docs/guides/tools-image-generation))

- Multi-turn editing works through `previous_response_id`.
- The mainline model **rewrites the prompt** (`revised_prompt`), which adds an uncontrolled step between our
  fidelity-focused prompt and the image model.
- Cached image input ($2/1M) applies only here.
- The docs are inconsistent: model pages list `v1/responses` as unsupported while describing tool use.
- **Use the Images API for Studio.** It is single-shot, its parameters are explicit, and it rewrites nothing. Revisit
  the Responses tool only if a conversational "fix the collar" refinement becomes a product goal.

### 4.4 Data handling ([your data](https://developers.openai.com/api/docs/guides/your-data), [provenance](https://developers.openai.com/api/docs/guides/content-provenance))

- Image edit and generation data **isn't used for training**.
- It is kept **30 days** for abuse monitoring, with no application state.
- Zero Data Retention is available "with limitations" for some models, including several 2.5 variants.
  **Eligibility does not mean it is enabled.** ZDR has to be arranged with OpenAI for the organization or project,
  and confirmed, before anything relies on it. Until then, assume the default 30-day retention and never promise ZDR
  to users.
- All image inputs are scanned for CSAM, and flagged images are kept even under ZDR.
- Provenance: OpenAI's API docs describe C2PA and SynthID *checking*, but **don't state** that API edit outputs carry
  C2PA metadata; the product announcement suggests it. **UNVERIFIED.** Studio therefore records its own provenance
  and stores bytes unmodified.
- Organization verification may be required before GPT Image models can be used. It is an operator setup step.

### 4.5 Documented limitations relevant to fidelity

- The guide says the model "can still struggle with precise text placement and clarity", which covers logos and
  lettering.
- Brand elements may not stay consistent across generations.
- Masks aren't followed exactly.

This confirms the design premise: **generation can't be trusted to preserve exact detail**, so Clean stays the
faithful default and AI results carry flags and a mandatory comparison.

**Rejected idea: re-compositing original pixels onto the AI output.** One way to get both polish and fidelity is to
paste the original cutout pixels back over the AI output wherever the silhouettes align. It works only for the
`preserve` style, where the outline is unchanged, and the result is a hybrid image that isn't clearly either. Keep it
as a benchmark condition only, not a v1 feature.

---

## 5. Backend and KMP contracts (proposed)

### 5.1 Backend resources (`wadrova-backend`, new migration `0008`)

All routes are under `/v1`, use bearer auth, are owner-scoped (cross-user → 404) and use the existing error envelope.
Flags:
- `WADROVA_STUDIO_ENABLED=false`: Clean and Studio both off. Endpoints return `404 not_found`, so the app hides the
  feature.
- `WADROVA_STUDIO_GENERATIVE_ENABLED=false`: AI off with Clean on. AI requests get `403 feature_disabled`.

**Create a render.**
```
POST /v1/studio-renders
Idempotency-Key: kmp-stu-<uuid>              (required; the local studio_request.id)
{
  "source": {"type": "candidate", "candidate_id": "cnd_…"},
  "client_garment_id": "user-…",
  "pipeline": "clean" | "generative",
  "style": "clean_v1" | "studio_preserve_v1" | "studio_flatlay_v1",
  "consent": {"copy_version": "studio-consent-v1", "accepted_at": "2026-…Z"}   // required iff generative
}
→ 202 {
  "id": "str_…", "status": "queued", "pipeline": "generative", "style": "studio_preserve_v1",
  "source": {"candidate_id": "cnd_…", "asset_id": "ast_…", "sha256": "…"},
  "client_garment_id": "user-…", "poll_after_seconds": 5,
  "quota": {"generative_remaining_today": 4},
  "result": null, "fidelity": null, "error": null, "created_at": "…"
}
```

Server checks, under one transaction in the idempotency pattern:
1. The candidate is owned by the caller.
2. An `import_decisions` row is `accepted`, with this `candidate_id` and this `client_garment_id`, on a finalized
   import. This enforces entry point B and binds the render to the garment.
3. At most one non-terminal render per `(owner, client_garment_id)` → otherwise `409 invalid_state` (with the
   existing render's id in `details`).
4. Quota (5.3) → `429 rate_limited` with `details.retry_after` and `retryable: true`.

A replay returns the same render (200, `Idempotent-Replay: true`, like imports). The same key with a different body
→ `409 idempotency_conflict`.

**Read, list, cancel, decide.**
```
GET  /v1/studio-renders/{id}
GET  /v1/studio-renders?client_garment_id=user-…&limit=20     // recovers a lost POST response; newest first
POST /v1/studio-renders/{id}/cancel                            // idempotent, no body
POST /v1/studio-renders/{id}/decision  {"decision": "accepted" | "rejected"}   // idempotent; same value → replay, other → 409
```

Statuses are `queued | running | succeeded | failed | cancelled`, derived from the job (as imports are), plus
`decision` (`null | accepted | rejected`) and `decided_at`. On success:
```
"result": {"asset_id": "ast_…", "kind": "studio_generated", "sha256": "…", "media_type": "image/png",
           "width": 1024, "height": 1536, "has_alpha": true, "content_url": "/v1/assets/ast_…/content"},
"provenance": {"pipeline": "generative", "style": "studio_preserve_v1", "prompt_version": "studio-gen-v1",
               "model": "<exact model id or snapshot returned by OpenAI>", "parameters": {"quality": "…", "size": "…",
               "background": "transparent"}, "input": {"asset_id": "…", "sha256": "…",
               "preprocess": "neutral-composite-v1"}, "generated_at": "…"},
"fidelity": {"version": "fid-v1", "color_delta_e00": 4.1, "color_level": "noticeable",
             "silhouette_iou": 0.88, "shape_level": "ok", "text_detected_source": false, "flags": ["color_shift"]}
```
Error codes (user-safe, mirroring decision 28): `source_unavailable`, `content_refused` (provider policy refusal,
not retryable), `provider_unavailable` (retryable), `generation_failed`, `quota_exceeded`, `cancelled`. The
provider's raw error text and request id stay in the server's logs and job row only.

**Assets** reuse `GET /v1/assets/{id}/content` with SHA-256 verification and `Cache-Control: private, no-store`. The
`assets.kind` CHECK gains `studio_clean` and `studio_generated`.

### 5.2 Jobs, workers and credentials

- New job kinds, `studio_clean` and `studio_generate`, both in pool `studio`. The API enqueues them in the creating
  transaction (`jobs.enqueue`).
- **The worker** is a new compose service `studio-worker` on the Mac, running `wadrova-worker` with
  `WADROVA_WORKER_POOL=studio`. It has direct DB and S3 access, like `maintenance`.
  - **Only this service** receives `OPENAI_API_KEY`, through a Docker secret or an env file outside the repo.
  - The `api`, `maintenance` and Razer processes never see the key.
  - The worker protocol (`/internal/worker/v1`) and the SAM 3 pools are unchanged.
- The worker dispatches on `claim.kind` (today `worker.py:74` picks extraction or correction). It adds a
  `StudioAdapter` interface with two implementations: `OpenAIStudioAdapter` and `FakeStudioAdapter` (tests, CI and
  demos; never calls the network). This mirrors `inference.InferenceAdapter`.
- **Retry policy for paid calls.** `max_attempts=2` for generative jobs.
  - Before calling OpenAI, the attempt writes `payload.provider_call_started_at` (its own committed update), so any
    later attempt knows a charge may already exist.
  - Retry only on errors where no image was produced: connection refused, 429, 5xx.
  - Never retry `content_refused` or 400s.
  - A timeout after the request was sent is treated as possibly billed: one retry at most, and it is charged to the
    quota.
  - Clean jobs keep the normal `job_max_attempts`.
- **Fenced publish.** The result PNG and an optional preview are staged through the attempt ledger. The completion
  transaction (fenced) creates the asset rows, writes `result`, `provenance` and `fidelity`, and flips the ledger. A
  stale worker's result is never published, though it may have been paid for; that cost is recorded on the job.
- **Cancellation.** A `queued` render → `jobs.cancel` (the conditional update from decision 29). A `running` render
  can't be interrupted, because the provider call is synchronous. The job is cancelled, its completion is refused by
  fencing, the paid result is discarded, and the user sees "Cancelled". The UI says so before the user cancels a
  running AI render.
- **Rejected or cancelled results.** Their assets become eligible for deletion after a grace period. This is new
  deletion code for published assets, kept small and studio-only. It is the first piece of M4.6 retention (decision
  D12).

### 5.3 Cost controls and rate limiting

- Per-user generative quota, counted from `studio_renders` rows under a lock on the user row (the same lock pattern
  as decision 32):
  - Default proposal: 5 per rolling 24 h and 30 per 30 days.
  - Failures that the provider didn't bill don't count.
- Global daily spend cap: `WADROVA_STUDIO_DAILY_BUDGET_USD`, compared with the sum of estimated per-request cost (a
  table keyed by model, quality and size from section 8). When reached → `429 quota_exceeded` for everyone, and an
  operator log line.
- One in-flight render per garment, and at most 2 per user.
- `n=1` always. Quality and size are pinned server-side; the client chooses only `style`.
- Clean renders have a generous separate limit (for example 100 per day) to stop abuse; they cost nothing.

### 5.4 KMP integration

| Layer | New or changed | Notes |
|---|---|---|
| `data/WadrovaApi.kt` | `createStudioRender`, `getStudioRender`, `studioRenders(clientGarmentId)`, `cancelStudioRender`, `decideStudioRender`; reuse `assetContent` | Same request conventions; pinned by `KtorWadrovaApiTest` cases. `FakeWadrovaServer` gains Studio routes. |
| `domain/Studio.kt` | `StudioPipeline`, `StudioStyle`, `StudioRequestState`, `StudioRequest`, `FidelityReport`, `GarmentImage`, `GarmentImageKind` (`CUTOUT`, `CLEAN`, `STUDIO_AI`) | Pure rules: eligibility, which display image wins, fidelity-flag wording |
| `sqldelight/.../Studio.sq` + `7.sqm` | Tables from section 6 | `SchemaMigrationTest`: version 7 → 8 keeps garments, photos and extractions |
| `data/StudioRepository.kt` | Create or reuse a request (key stored **before** send), state updates, accept (one transaction: insert `garment_image`, optionally set `display_image_id`, delete the request row), discard | Files staged before the transaction are deleted on failure, as in `saveReviewedPieces` |
| `data/GarmentRepository.kt` | `setDisplayImage(garmentId, garmentImageId?)`; `update` applies D7 to photo changes; domain `UserGarment` gains `displayImage: GarmentImageRef?` | |
| `MirrorRepository.removeUnreferencedImages` | Add `garment_image.image_id` and `studio_request.preview_image_id` to the referenced set | **Critical:** otherwise every launch deletes Studio files |
| `ui/StudioState.kt` | `StudioViewModel` (one driver per request, backoff 2 s → 60 s with jitter, stop after 6, foreground polling at `poll_after_seconds` clamped 2–30 s, resume on launch and foreground: the same as `GarmentImportViewModel`), `StudioUiState` | Saving and deciding flags block repeated taps |
| `ui/StudioScreens.kt` | Studio page, compare view, consent dialog, failure states | Reuse `ActionButton`, `WadrovaColors`, Mirror's `ImageTile` look |
| `ui/GarmentVisual.kt`, `ui/WardrobeItem.kt` | `WardrobeItem.displayImageId` (generated-store id). Resolution: selected display image → `extractedImageId` → photo → illustration | One function, unit-tested |
| `ui/GarmentDetailsScreen.kt` | Image switcher chips, the "Studio image" action, the AI badge | |
| `App.kt` | Create `StudioViewModel`; draw the Studio overlay; hide the tab bar while it is open (like `importOpen`) | |

**Recovery** follows imports:
- The local id is both the `Idempotency-Key` and the stored request key.
- An unknown outcome after a send (lost response) is resolved by `GET /v1/studio-renders?client_garment_id=…`.
- A 401 asks to reconnect.
- `400/403/404/409` are definite refusals; `429` waits for `retry_after`.

---

## 6. Local data model (database 7 → 8, additive)

```sql
-- 7.sqm
CREATE TABLE garment_image (
    id TEXT NOT NULL PRIMARY KEY,              -- local id (gim-<uuid>)
    garment_id TEXT NOT NULL,
    kind TEXT NOT NULL,                        -- CUTOUT | CLEAN | STUDIO_AI
    image_id TEXT NOT NULL UNIQUE,             -- GeneratedImageStore id (PNG, alpha kept)
    sha256 TEXT NOT NULL,                      -- of the stored bytes (= server asset sha256)
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    source_candidate_id TEXT,                  -- server candidate the image derives from
    source_sha256 TEXT,                        -- the exact server input (cutout asset hash)
    server_asset_id TEXT,
    server_render_id TEXT UNIQUE,              -- null for CUTOUT
    pipeline TEXT NOT NULL,                    -- sam3-cutout | clean | generative
    pipeline_version TEXT NOT NULL,            -- candidate revision | clean-v1 | studio-gen-v1
    style TEXT,
    generation_model TEXT,                     -- exact model id/snapshot (generative only)
    parameters TEXT,                           -- JSON from server provenance
    fidelity TEXT,                             -- JSON from server, as shown at acceptance
    consent_copy_version TEXT,                 -- generative only
    created_at INTEGER NOT NULL                -- accepted (stored) at
);
CREATE INDEX garment_image_garment ON garment_image(garment_id);

-- An in-flight or awaiting-decision request; deleted when accepted or discarded.
CREATE TABLE studio_request (
    id TEXT NOT NULL PRIMARY KEY,              -- also the Idempotency-Key
    garment_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    pipeline TEXT NOT NULL,
    style TEXT NOT NULL,
    consent_copy_version TEXT,
    consented_at INTEGER,
    state TEXT NOT NULL,                       -- SEND_PENDING | SENDING | QUEUED | RUNNING | READY | FAILED | CANCEL_PENDING | CANCELLED
    send_attempts INTEGER NOT NULL,
    server_render_id TEXT UNIQUE,
    problem TEXT,
    problem_message TEXT,
    preview_image_id TEXT,                     -- downloaded, verified result awaiting Use/Discard
    result_json TEXT,                          -- server result + provenance + fidelity
    decision_pending TEXT,                     -- ACCEPTED | REJECTED, until the server confirms
    poll_after_seconds INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX studio_request_one_open_per_garment ON studio_request(garment_id)
    WHERE state NOT IN ('FAILED', 'CANCELLED');

ALTER TABLE user_garment ADD COLUMN display_image_id TEXT;   -- garment_image.id; NULL = today's behavior
```

**How each representation is held:**

| Representation | Where |
|---|---|
| Original photograph | Server only (`source_original`). The local copy is deleted after completion (unchanged). For manual garments, `user_garment.photo_id` is the user's photo. |
| Segmented cutout (white-flattened) | `user_garment.photo_id` (unchanged, still the fallback) |
| Segmented cutout (transparent, true alpha) | `garment_image(kind=CUTOUT)`, downloaded and SHA-verified from the provenance candidate (S2) |
| Deterministic clean image | `garment_image(kind=CLEAN)` |
| AI Studio image | `garment_image(kind=STUDIO_AI)`; may hold several (history) |
| Mirror extraction | `user_garment.extracted_image_id` (unchanged) |
| Selected display image | `user_garment.display_image_id`; NULL keeps today's behavior: extraction → photo → illustration |
| Provenance and status | `garment_image` columns (accepted); `studio_request` (in flight); server `studio_renders` (authoritative job record) |

**Why this is safe:**
- Only `CREATE TABLE` and `ADD COLUMN`. No existing row is rewritten.
- `display_image_id` is NULL for every existing garment, so they render exactly as before.
- A missing generated file (restore without files) falls back down the chain, as today.

**Rules:**
- **Never destructive.** Accepting a Studio image never touches `photo_id`, `extracted_image_id` or another
  `garment_image`.
- Removing an image deletes only that `garment_image` row and its file, and clears `display_image_id` if it pointed
  there (one transaction, file deleted after commit).
- **Photo replaced (D7).** Proposal: `CUTOUT` and `CLEAN` images derive from the server cutout, not the local photo,
  so they stay. But the user is saying the photo changed, so `display_image_id` is reset to NULL and the new photo
  shows. The Studio images stay in the switcher, labelled "from your import". The alternative is to delete them, to
  match the extraction rule.
- `garment_image.garment_id` has no FK, matching the existing schema style. Garments are never deleted, only
  archived.
- Orphan cleanup must include both new image columns (section 5.4).

---

## 7. Garment-fidelity evaluation

### 7.1 Reference dataset (about 60 garments)

Constraints:
- Only non-personal images: CC0 or licensed street-style photos, and garments the team owns photographed by the team
  with consent. No new photos are processed until the owner approves the dataset.
- The **paired** subset is the critical part. The same physical garment is photographed **worn** (Studio's real input)
  and **laid flat or on a hanger under even light** (ground truth for color and hidden regions). Without pairs,
  invention cannot be measured, only plausibility.

| Group | Count | Of which paired | Must include |
|---|---|---|---|
| Jackets and coats | 8 | 4 | Zips, contrasting lining, quilting, toggles |
| Shirts and knitwear | 10 | 5 | Button plackets, cable knit, ribbing, sheer fabric |
| Trousers and jeans | 8 | 4 | Denim wash and fades, pleats, rivets, cargo pockets |
| Dresses | 6 | 3 | Florals, pleats, a merged top+bottom failure case |
| Sneakers and other footwear | 8 | 4 | Brand marks, laces, suede vs leather, boots |
| Bags and accessories | 8 | 4 | Hardware, monogram, chain strap, belt, scarf |
| Patterned and logo-bearing | 12 | 6 | Stripes, checks/plaid, small all-over prints, large graphic tees, lettering, embroidered logos |

Each item is annotated with category, visible-text strings (ground truth), distinctive features (a checklist of
countable features such as "2 chest pockets, 6 buttons, red tab"), occlusion level (none, partial, heavy) and wear
marks.

### 7.2 Conditions benchmarked

1. Cutout, white-flattened (today's baseline)
2. `clean-v1`
3. Generative × input {RGBA, neutral composite, crop+mask (benchmark only)}
4. Generative × style {preserve, flat-lay}
5. Generative × fidelity setting {high, low} where the model supports it (section 4)

### 7.3 Automated measurements (computed, not judged)

The generated garment is isolated by requesting a transparent background, or by thresholding the known neutral
background.

| Criterion | Metric | Notes |
|---|---|---|
| Color | CIEDE2000 between area-weighted dominant palettes (k-means, k = 3–5, in CIELAB, over the opaque region; specular highlights and deep shadows excluded by L* percentiles). The median ΔE00 of matched clusters is reported. | Proposed bands: ≤ 3 ok, 3–6 noticeable, > 6 different. On paired items, also against the flat-lay reference, because the worn photo's lighting isn't ground truth either. |
| Shape | Silhouette IoU after similarity alignment (moments-based scale, rotation, translation), plus the aspect-ratio delta | Meaningful only for `preserve`; reported but not gated for `flat-lay` |
| Pattern | Normalized cross-correlation of the FFT magnitude spectra of aligned interior patches (stripe frequency and orientation), plus LPIPS or DISTS on patches for paired items | Weak. Human review is primary. |
| Identity | Retrieval: embed each output (DINOv2 ViT-S/14 or CLIP, CPU) and check that its nearest neighbor among all dataset sources is its own source (top-1 and top-3 accuracy, cosine margin) | Pose-robust identity proxy. Needs a model dependency in the lab only, not in production v1. |
| Lettering | OCR (Tesseract in the lab; Apple Vision or ML Kit are not needed) on source and output, normalized edit distance against ground-truth strings | Any changed or garbled string is a **critical** failure |
| Artifacts | Alpha-edge roughness, leftover background pixels, added objects (connected components outside the main silhouette) | |
| Latency | p50 and p95 of queue wait, provider time and end-to-end (request → preview downloaded) | |
| Cost | Provider usage per request (token counts returned by the API) × the price table | Logged per job |

### 7.4 Production fidelity checks (fid-v1, server-side, CPU only)

These are only a subset, cheap and dependency-free: color ΔE00 bands, silhouette IoU (preserve style), and a
background-leak check. They become user-facing flags in the comparison view:

| Flag | Shown as |
|---|---|
| `color_shift` (ΔE > 3) | "Color may differ from your photo" |
| `color_different` (ΔE > 6) | "Color looks different", and *Use this* asks for a second confirm |
| `shape_changed` (IoU < 0.8, preserve) | "Shape differs from your photo" |
| `text_unverifiable` (always, when the source shows lettering, once OCR is added) | "Lettering may be changed" |

The flags are honest about their limits: *"These checks can miss changes. Compare carefully."* They never claim
"verified".

### 7.5 Human review

- **Blinded rubric**, 1–5 per criterion, reusing the vision lab Experiment 001 fields (completeness, edges, color and
  texture, contamination, usability) and adding:
  - **"Is this the same item?"** (1–5)
  - **Invented-detail count**: features added, removed or changed against the feature checklist
  - **Critical failure**: yes/no (wrong color family, changed or garbled logo or text, missing or extra pocket,
    button or strap, different garment type)
- **Pairwise preference.** For each item, Clean vs AI vs today's cutout, randomized left and right, with ties allowed.
- **Raters.** At least two (the owner plus one). Cohen's κ is reported. Disagreements are adjudicated.
- **Automated vs human.** Automated metrics screen and track regressions. **Ship gates use human labels**:
  attractiveness without identity is a failure.

### 7.6 Proposed ship gates for Studio · AI (owner to set; decision D4)

- "Same item" ≥ 4 on ≥ 80 % of items
- Critical failures ≤ 10 % overall and 0 % undisclosed lettering changes (lettering flag always shown)
- Median ΔE00 ≤ 4 on paired items
- AI preferred over Clean on ≥ 60 % of non-critical items. Otherwise AI isn't worth its cost and risk.
- p95 end-to-end below the latency budget, and cost per **successful** image (passes "same item" ≥ 4 with no critical
  failure) below the budget (section 8)

---

## 8. Cost and latency

### 8.1 Prices ([pricing](https://developers.openai.com/api/docs/pricing), [image guide](https://developers.openai.com/api/docs/guides/image-generation))

- **Token prices.** GPT Image 2, 2.5 and 2.6 all charge **$8 per 1M image input tokens**, **$30 per 1M image output
  tokens** and $5 per 1M text input tokens. Batch halves this to $4 and $15 (not available on 2.6). Regional data
  residency adds 10 %.
- **Billing is per token.** No per-image price is published for `gpt-image-2.5-sunburst`; the 2.5 page says its
  token rates match GPT Image 2, but the calculator doesn't cover 2.5. The per-image figures below are **OpenAI's
  published figures for `gpt-image-2`**. They are **not a confirmed Sunburst price**: Sunburst's per-image cost depends
  on the tokens it actually uses, which S0b must read from each response's `usage`. Portrait output for
  `gpt-image-2`:

  | Quality | Output cost per image |
  |---|---|
  | low | $0.005 |
  | medium | $0.041 |
  | high | $0.165 |

  Square high is $0.211. `xhigh` and `max` (2.5 only) are **unpriced: UNVERIFIED**.
- **Input tokens come on top.** A high-fidelity input image is probably a few thousand tokens, roughly $0.01–0.05 per
  request. That figure is **an estimate**: S0b must read the `usage` field of real responses.

### 8.2 Planning estimates (to replace with S0b measurements)

Every Sunburst and Flare figure below is **derived from `gpt-image-2`'s published per-image output cost plus an
estimated input cost**. They are budgeting assumptions, not prices.

| Configuration | Per attempt (derived estimate) | If 60 % of attempts pass "same item" | Notes |
|---|---|---|---|
| 2.5-sunburst, high, portrait | ~$0.18–0.22 (est.) | ~$0.30–0.37 per successful image (est.) | Likely Studio default |
| 2.5-sunburst, medium | ~$0.05–0.09 (est.) | ~$0.09–0.15 (est.) | Test whether fidelity holds |
| 2.5-flare, medium | similar tokens, faster | — | Speed option |
| Clean (`clean-v1`) | ≈ $0 (Mac CPU) | — | Always available |
| S0b benchmark (60 items × ~4 generative conditions × 1–2 samples) | ≤ ~$100 at high quality | — | Proposed budget cap for D6. **Batch API at 50 %** fits the benchmark (24 h window), not the product. |

Quota example, using the same derived estimate of about $0.20: 5 per day is at most about $1 per active user per day,
and 30 per month is at most about $6 per user per month. The global daily cap (D11) is the real safety net. It must
be computed from measured token usage, not these estimates.

### 8.3 Latency

- OpenAI says complex prompts can take **up to 2 minutes**.
- PNG is slower than JPEG, square images are fastest on `gpt-image-2`, and `low` quality is faster.
- Planning budget for an AI render:
  - Expected: under 60 s of provider time at p50 and up to 120 s at p95.
  - Plus queue wait, plus 2–30 s of poll granularity.
  - **The UI must therefore be asynchronous**, which the job design already ensures: the user can leave and come
    back.
- Clean: under 2 s of processing; end to end it is dominated by polling (first poll at about 2 s).
- Rate limits: the default Build tier allows **20 images per minute** (250K TPM) for `gpt-image-2` and 2.5. With one
  `studio-worker` processing serially, or with small concurrency, that is ample for development. 429s map to the
  retry policy in 5.2.
- Streaming partial images could show progress, but each partial adds cost and complexity. It isn't in v1.

---

## 9. Privacy, consent and provenance

- **New third-party flow.** Today `docs/product.md` says personal images leave the device only through Mirror and
  Extract from photo, and that Extract images go "never to OpenAI". Studio · AI would send a server-held cutout from
  the Wadrova server to OpenAI. **product.md and the signup disclosure must be updated and approved before Studio · AI
  is enabled** (decision D1).
- **Data minimization.** Send the garment cutout only, never the original photo, crop or face. A cutout from a worn
  photo can still include skin at a neckline, cuff or hem, tattoos or jewelry. The consent copy says "the garment
  cutout from your photo", not "no personal data".
- **Consent.**
  - It is per request, Regenerate included, and the copy is versioned.
  - The server refuses generative requests without a `consent` block. It can't verify the dialog was shown, which is
    the same limit as finalize, and the docs record that.
  - The local `garment_image.consent_copy_version` records what was agreed.
- **Credentials.**
  - The OpenAI key lives only in the `studio-worker` environment on the Mac (Docker secret or a file outside the repo,
    never logged).
  - The app never sees it.
  - The existing rules apply: no keys, tokens or `wdr_` values in logs, URLs or errors.
- **Provider data handling** (section 4): what OpenAI retains, for how long, and whether Zero Data Retention applies
  to image endpoints is recorded in section 4 and repeated in the disclosure. Never promise more than the
  documentation supports.
- **Provenance and labelling.**
  - Every accepted image stores pipeline, version, model, parameters, input asset hash and server render id.
  - AI images keep any provider-embedded provenance metadata: the app stores bytes unmodified in
    `GeneratedImageStore`.
  - They are badged "AI" in full-size views.
  - They are never presented as the user's photo, a product, or a try-on (honesty rules).
- **Retention.**
  - Rejected and cancelled results are deleted server-side after a grace period.
  - Accepted results stay until M4.6 deletion and retention exist; don't promise deletion before then.
  - Locally, Studio files are backup-excluded like other generated images and removable by the user.
- **Testing.** Only the curated non-personal dataset. **Never** the 10 owner-iPhone imports on `kmp-app-dev` (privacy
  classification pending).

---

## 10. Failure scenarios and recovery

| Scenario | Behavior |
|---|---|
| Studio flag off or backend unreachable | The Studio action is hidden (flag) or disabled with "Can't reach Wadrova". The garment and its display are unaffected. |
| Garment not yet synced (`FINALIZE_PENDING`/refused) | "Studio is available once this piece has synced" (server would 404/409 the candidate binding) |
| Lost POST response | Same Idempotency-Key resend → replay; if the local row is ambiguous (`SENDING` at relaunch), find by `client_garment_id` list, like imports |
| App killed while queued/running | `studio_request` persists; on launch, the driver resumes polling; result downloaded when ready |
| Result asset hash mismatch | Refused (`assetContent`), shown as "Image unavailable", retry download; never stored |
| Provider timeout / 5xx / 429 | Worker retries at most once (5.2); then `failed` with `provider_unavailable` (retryable by user, consumes quota only if billed) |
| Content policy refusal | `failed`/`content_refused`; "This image couldn't be generated." No automatic retry |
| Worker lease lost mid-call | Fencing refuses the stale completion; next attempt sees `provider_call_started_at`; result possibly paid but never published; counted in cost logs |
| User cancels while running | Job cancelled; paid result discarded; UI warned beforehand |
| Accept transaction fails | Staged file deleted; preview stays; user can retry *Use this* |
| Decision POST fails | `decision_pending` kept; driver retries; local accept already effective (server decision is for retention only) |
| Quota exhausted | `429` → "You've reached today's Studio limit" with time; Clean still available |
| Backup restored without files | `display_image_id` points to a missing file → `GarmentVisual` falls back (extraction → photo → illustration); Details offers "Image missing on this device" with re-download if the server still has it |
| Server retention later deletes source cutout | New Studio requests → `source_unavailable`; existing local images unaffected |
| Generated image looks right but isn't | Mitigated, not solved: fidelity flags, mandatory compare, AI label, default "keep current display", removable at any time |
| Orphan generated files | Covered once the cleanup reference set includes the new columns (test) |

---

## 11. Milestones (each independently testable and shippable)

| # | Increment | Repo | Paid calls | Test evidence |
|---|---|---|---|---|
| **S0** | **Benchmark harness, offline.** Dataset manifest + annotation schema; `clean-v1` reference implementation; metrics (ΔE00, IoU, FFT, OCR, retrieval); rubric UI/CSV. Run conditions 1–2 on approved CC0/owned images | `wadrova_vision_lab` | No | Lab unit tests; human rubric for Clean |
| **S0b** | **Paid benchmark** of conditions 3–5 with an owner-approved budget cap and exact model/params | lab | **Yes, after approval** | Report vs ship gates (§7.6), cost/latency table |
| **S1** | **Local model**: `7.sqm` (`garment_image`, `studio_request`, `display_image_id`), domain types, display-resolution function, `GarmentVisual` + Details switcher (Photo/Extraction only), cleanup references | KMP | No | `SchemaMigrationTest` 7→8 (and 1–6 → 8), resolution unit tests, cleanup test, all existing tests green |
| **S2** | **Transparent cutout backfill**: for each imported garment with provenance, fetch the provenance candidate's RGBA cutout (via stored `server_session_id` → candidates → cutout asset, SHA-verified) and store as `garment_image(CUTOUT)`; offer "Transparent" as display; Moodboard composites cleanly. Does **not** touch save/finalize | KMP | No | Fake-server tests; emulator check with CC0 import; moodboard screenshots |
| **S3** | **Backend Clean**: migration `0008` (`studio_renders`, asset kinds, quota table/columns), routes, `studio` pool worker with `clean-v1`, flags default off | backend | No | pytest: idempotency, ownership/binding, one-in-flight, cancel, fencing, migration up/down, OpenAPI |
| **S4** | **KMP Studio (Clean)**: `WadrovaApi` methods, `StudioRepository`, `StudioViewModel` driver, Studio page, compare, accept/discard, display selection | KMP | No | `KtorWadrovaApiTest`, VM tests (lost response, relaunch, duplicate tap, hash mismatch, accept failure), host + iOS + device tests; emulator against Mac stack (with approval to start it) |
| **S5** | **Backend generative**: `StudioAdapter` + `FakeStudioAdapter` + `OpenAIStudioAdapter` (not exercised live), prompt `studio-gen-v1`, preprocessing, fid-v1 checks, quotas, budget cap, retry policy, rejected-asset deletion | backend | No (fake adapter only) | pytest with fake adapter incl. timeout-after-send, refusal, lease loss, quota races |
| **S6** | **KMP generative UI**: consent dialog (versioned), AI badge, fidelity flags, quota display, regenerate | KMP | No | VM/UI-state tests against fake server |
| **S7** | **Live enablement**: docs + product.md + disclosure update; key provisioned to `studio-worker` only; flags on for the dev user; one supervised live run on CC0 garments within budget | both | **Yes, after approval** | Recorded run: ids, latency, cost, fidelity, human check |
| **S8** | Retention hooks for Studio artifacts (part of M4.6) | backend | No | |

S1–S4 deliver a complete, free, deterministic Studio; S5–S7 add AI only if S0b clears the gates.

---

## 12. Decisions needed from the project owner

| # | Decision | Recommendation |
|---|---|---|
| D1 | Approve a new third-party flow (server-held cutout → OpenAI) and the product.md / disclosure wording | Approve with per-request consent and cutout-only input |
| D2 | Entry point | B: saved garments only, from Garment Details |
| D3 | Styles to offer: `preserve` (faithful), `flat-lay`/ghost-mannequin (attractive, more invention), or both | Decide after S0b; start with `preserve` |
| D4 | Ship gates and thresholds (§7.6) | As proposed, owner adjusts |
| D5 | Manual (non-imported) garments in Studio | Out of v1; later via "extract from this photo" |
| D6 | Model, quality, size, fidelity parameters and budget for S0b | Per §4/§8 after verification; cap e.g. ≤ 150 images |
| D7 | Photo replaced → Studio images: keep (labelled) or delete | Keep, reset display to the new photo |
| D8 | Default display after accepting: Clean → show; AI → keep current | As stated |
| D9 | May Mirror try-on use a Studio · AI image as input? | No (AI-on-AI drift); Clean/Transparent yes |
| D10 | Clean background: transparent only, or also neutral + synthetic shadow | Transparent asset; app draws background; shadow later |
| D11 | Quotas and global budget | 5/day, 30/month per user; daily USD cap set by owner |
| D12 | Server deletion of rejected/cancelled Studio results ahead of full M4.6 retention | Yes, studio-scoped |
| D13 | Require finalize-confirmed (`COMPLETED`) imports for Studio | Yes (server binding via `import_decisions`) |
| D14 | Studio model setting: shared with Mirror's `WADROVA_IMAGE_MODEL`, or separate | Separate `WADROVA_STUDIO_MODEL`, pinned to a dated snapshot (`gpt-image-2.5-sunburst-2026-09-08`); not 2.6 until it has snapshots |
| D15 | Approval to start the Mac stack / create test imports for S2–S4 emulator checks | Ask at that milestone |
| D16 | Accept OpenAI's data terms (30-day abuse retention, no training) for Studio, or require ZDR first | Accept for development with non-personal data; decide ZDR before real users |

**Next step on approval:** S0 (offline lab harness) and S1 (local model) can proceed in parallel; neither calls a paid API or touches finalize.

---

## Appendix A. S0 offline baseline results (2026-11-04)

S0 ran fully offline, with no paid API, no backend access and no personal photos.

- **Inputs.**
  - 8 procedurally generated garments with known ground truth.
  - 2 regions of the approved CC0 photo `Street_Style_of_2018_2019_in_Tehran.jpg`, with its SHA-256 checked against
    `../wadrova-backend/docs/REAL_INFERENCE_VERIFICATION.md`.
- **Masks.** The synthetic masks come from the known garment shapes and the CC0 masks from a deterministic color
  threshold. Both pass through a 288-cell grid to emulate SAM 3 logit resolution. **None is SAM 3 output**, so these
  results say nothing about real SAM 3 mask quality.
- **Cutouts** are built the way `processing.encode_candidate_artifacts` builds them: crop to the box, binary
  `putalpha`.
- **Emulation limits.** The M4.5 JPEG path was emulated with Pillow, not the platform encoders. App tiles were Python
  simulations, not device screenshots.
- **Reproducibility.** The harness and report are kept outside the repository and are deterministic: two runs gave
  identical bytes.

| Finding | Result |
|---|---|
| How transparency is lost | `GarmentReviewViewModel.save` → `GarmentPhotoStore.importBytes`. Android `write` draws onto white and saves JPEG 88; iOS `encodeRendition` draws into an opaque white context and saves JPEG 0.88. The RGBA bytes are never persisted. |
| White box | R1 is 8–43 % transparent per garment. In the M4.5 JPEG all of that becomes white: faint on Studio cards, obvious on selected (CobaltSoft) cards and on the Sand moodboard. |
| M4.5 JPEG fidelity | Mean garment color ΔE00 ≤ 0.38. Local interior ΔE00 mean 0.4–2.1, up to 15.6 at lettering and stripe edges. PSNR 35–43 dB. Smooth-fabric grain softened (high-frequency energy 0.76–0.89×). |
| Transparent cutout on Sand | Garment interior 100 % pixel-identical to the cutout |
| Edge halos | 4–72 % of boundary pixels carry background color (fringe worst). Detail finer than one logit cell (~5.6 px at 1600 px) is lost upstream: chain strap, 4 px straps, fringe. |
| Color-based edge decontamination | Safe on solid garments (removes background only). **Unsafe on patterns:** stripes lost 796 garment px vs 338 background, gingham 551 vs 9. Island removal deleted real chain and fringe fragments. **Not suitable as a default.** |
| Fixed product canvas | Cutouts are small (67×111 to 500×852 px), so a 1600×2000 canvas needs 2–15.5× upscaling. Resampled, not pixel-identical. Prefer display-time framing (the existing 4:5 tile with `ContentScale.Fit`). |
| Hidden pixels | **Every alpha-0 pixel of the server cutout keeps the original photo's RGB** (`putalpha` does not clear it). A stored copy should zero RGB where alpha = 0. |
| Sizes (10 cutouts) | PNG as served 2,243 KB; M4.5 JPEG display + thumbnail 586 KB; lossless WebP 1,268 KB (exact round trip); WebP q90 187 KB (lossy, max RGB error 13–25) |
| Format | PNG is recommended. On-device WebP encoding is limited: lossless needs Android API 30+, and iOS support is unverified. |

**Follow-up: M4.5A.** The owner approved transparent cutouts for new imports:
- A nullable `user_garment.cutout_image_id` (database 8).
- A sanitized lossless PNG.
- Display order: extraction → cutout → photo → illustration.

Backfill, Clean and AI remain separately authorized milestones.
