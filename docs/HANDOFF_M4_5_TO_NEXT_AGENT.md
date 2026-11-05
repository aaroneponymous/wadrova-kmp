# Handoff: Wadrova KMP after M4.5

Written 2026-11-04 for a fresh Claude Code session. It describes the code as committed; read the code before relying on
any statement here. Paths are relative to the repository root (`wadrova_kmp/`) unless they start with `../`.

## A. Project and repository overview

| | |
|---|---|
| Repository | `wadrova_kmp/` (Git remote `origin`, private GitHub `wadrova-kmp`) |
| Branch | `feature/m4.5-garment-review`, created from M4.4 `20c3b70` |
| M4.5 implementation commit | `a906cc7 feat: complete M4.5 garment review, local save and finalization` |
| Pushed | No. Neither M4.4 (`feature/m4.4-garment-import`, `20c3b70`) nor M4.5 has been pushed or merged. `main` is older (`4d8bb04`). |
| Working guide | `CLAUDE.md` (rules, commands, privacy), `AGENTS.md`, `docs/DEVELOPMENT_STATE.md`, `docs/product.md` |

**Targets.** Kotlin Multiplatform with Compose Multiplatform: Android (`androidApp/`, application id `com.example.wadrova`)
and iOS (`iosApp/iosApp.xcodeproj`, bundle id `com.example.wadrova.Wadrova`; Kotlin targets `iosArm64`,
`iosSimulatorArm64`). All shared code is in one module, `shared/`. There are no other modules.

**Layout of `shared/src/commonMain/kotlin/com/example/wadrova/`.**
- `domain/`: pure Kotlin, no Compose or platform code. Garments, outfit rules (`OutfitComposition.kt`), saved looks,
  Mirror types, and the import model: `GarmentImport.kt` (`GarmentImport`, `ImportState`, `ImportProblem`,
  `ServerImport`) and `ImportReview.kt` (`Candidate`, `CandidateAsset`, `CandidateBox`, `Lineage`, `groupLineages`,
  `ReviewChoice`, `PendingCorrection`, `FinalizeManifest`, `buildManifest`, `fitTapToImage`).
- `data/`: SQLDelight repositories and stores. `GarmentRepository`, `SavedLookRepository` (also defines
  `LocalRepositories`), `MirrorRepository`, `GarmentImportRepository`, `GarmentPhotoStore`, `GeneratedImageStore`,
  `CredentialStore`, `BrandIntroStore`, `BackgroundGrace`, `Sha256`, and two HTTP clients: `MirrorBackend.kt`
  (`KtorMirrorBackend`, for the local Mirror backend) and `WadrovaApi.kt` (`KtorWadrovaApi`, for `wadrova-backend` `/v1`).
- `ui/`: Compose screens and their state holders. Import work is in `ImportState.kt` (`GarmentImportViewModel`,
  `ImportUiState`, `ImportRoute`), `ImportScreens.kt` (Add a piece sheet, Pending Imports, progress page, development
  connection page), `ImportReviewState.kt` (`GarmentReviewViewModel`, `ReviewUiState`) and `ImportReviewScreens.kt`
  (review grid, piece details, corrections).
- `App.kt`: creates every ViewModel over one `LocalRepositories` and wires tabs, screens and dialogs.

**Platform code.** `shared/src/androidMain/` and `shared/src/iosMain/`: photo stores, generated-image stores, credential
stores, SHA-256, photo pickers, back handling, reduced motion, the foreground effect, and `createLocalRepositories()`
(`SavedLookRepository.android.kt`, `SavedLookRepository.ios.kt`). Entry points: `androidApp/.../MainActivity.kt` and
`shared/src/iosMain/kotlin/com/example/wadrova/MainViewController.kt`.

**Dependency wiring.** No DI framework. `createLocalRepositories()` on each platform builds every repository, store and
HTTP client over one SQLDelight driver (`wadrova.db`) and returns `LocalRepositories`. `App.kt` creates ViewModels with
`viewModel { ... }` and passes collaborators by constructor. Tests construct the same classes with fakes.

**Navigation.** Hand-rolled: a five-tab bar (`PrimaryTab` in `ui/PrimaryNavigation.kt`; the app opens on Mirror) and a
`Screen` enum per tab (`ui/WardrobeUiState.kt`: `WARDROBE`, `BUILDER`, `GARMENT_DETAILS`, `GARMENT_FORM`). Import pages
are not a `Screen`: they're `GarmentImportViewModel.state.route` (`ImportRoute.Preparing`, `Progress(importId)`,
`Review(importId)`, `Connection(returnTo)`), drawn over the Wardrobe tab in `App.kt` when the Wardrobe screen is
showing. The tab bar is hidden on import pages (`showsPrimaryBar(..., importOpen)`). Piece details inside the review
are `GarmentReviewViewModel.state.detailLineageId`.

**Persistence.** SQLDelight 2.4.1, database `WadrovaDatabase`, schema in `shared/src/commonMain/sqldelight/com/example/wadrova/data/`
(`Garment.sq`, `SavedLook.sq`, `Mirror.sq`, `GarmentImport.sq`, `GarmentImportReview.sq`) with migrations `1.sqm`–`6.sqm`,
so the database is **version 7**. Images are files in app-private, backup-excluded storage, referenced by opaque ids.

**Build and test entry points** (from `wadrova_kmp/`, also listed in `CLAUDE.md` and `README.md`):
```bash
./gradlew :androidApp:assembleDebug
./gradlew :shared:testAndroidHostTest
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:compileKotlinIosArm64
./gradlew :shared:connectedAndroidDeviceTest     # needs a running emulator or device
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## B. M4.4 and M4.5 implementation state

### Fully implemented
- **Entry.** Wardrobe's + and the empty-state "Add a piece" open `AddPieceSheet`: **Extract from photo** or **Add
  manually**. Manual add is the unchanged `GarmentFormScreen`. The Outfit Builder's empty-picker "Add a piece" still goes
  straight to the manual form (decided for M4.4/M4.5).
- **Photo selection and import creation.** Picking starts the import with no consent or submit step
  (`GarmentImportViewModel.onPhotoPicked`). The photo is stored by `GarmentPhotoStore.import` (oriented, metadata-free
  1600 px display JPEG and 640 px thumbnail), its display bytes are read back and hashed (`sha256Hex`), and a
  `garment_import` row is written (`GarmentImportRepository.createOrReuse`) before any network call. Picking the same
  bytes while an import of them is open reopens it. Picker cancel creates nothing; an unreadable photo shows an error.
- **Upload and recovery.** `POST /v1/imports` with the raw display bytes. The local id (`kmp-imp-<uuid>`) is both the
  `Idempotency-Key` and `X-Client-Import-Ref`, so every retry is the same request. Each import has one driver coroutine
  (`runDriver`); transient failures back off from 2 s, doubling to 60 s with jitter, and stop after 6 consecutive
  failures (`MAX_CONSECUTIVE_FAILURES`). They resume on launch (`resetInterruptedUploads` turns `UPLOADING` back into
  `UPLOAD_PENDING`), on returning to the foreground, on saving the connection, or on Try again. A 401 stops and asks to
  reconnect; 400/413/409 rejects the upload for good. An upload is counted as "may exist on the server" until a definite
  refusal (`GarmentImport.uploadRefused`).
- **Status polling.** `GET /v1/imports/{id}` in the foreground at the server's `poll_after_seconds`, clamped to 2–30 s.
  Server states map to `ImportState` in `GarmentImport.withServer`; unknown statuses read as processing.
- **Pending Imports.** Listed at the top of Your Items (`PendingImports`), from `ImportUiState.pending` (everything not
  `COMPLETED`), with plain-language status (`GarmentImport.status()`).
- **Cancellation.** Recorded locally first (`CANCEL_PENDING`), then `POST /v1/imports/{id}/cancel`. An import that never
  reached the server is removed locally; one whose upload response was lost is found with `GET /v1/imports?state=active`
  by `client_ref`. Removing a failed import also cancels it on the server. Cancelling deletes the local photo copy, not
  the server's.
- **Candidate retrieval and review.** `GarmentReviewViewModel.open` loads every candidate of the session
  (`include=superseded,below_threshold`), groups revisions into lineages (`groupLineages`), and downloads each shown
  cutout through `WadrovaApi.assetContent`, which refuses bytes that don't match the asset's SHA-256. Lineages whose
  current revision is a detection are the pieces; below-threshold masks are offered only as alternative outlines.
  Zero pieces shows "Wadrova didn't find any pieces in this photo".
- **Selection and rejection.** Tapping a cutout toggles selection; selecting pins the revision on screen
  (`ReviewChoice.chosenCandidateId`). Unselected pieces are rejected at finalize. With nothing selected, the primary
  action is "Finish without saving pieces" (confirmation dialog), which sends an all-rejected manifest.
- **Metadata.** Name (starts from the model phrase, editable), category (pre-filled only when the backend resolver
  returned `identity.status = resolved`, editable), color (always the user's choice; required by the existing garment
  schema). Saving with incomplete details is refused and opens the first incomplete piece. Brand and material stay empty.
- **Cutout display.** Review cards and the details page decode the verified bytes in memory (`decodeToImageBitmap`).
  The details page draws the cutout over the dimmed original photo at the candidate's box (`OutlineOnPhoto`). A failed
  image shows "Image unavailable" with a retry.
- **Local garment and image persistence.** `GarmentReviewViewModel.save` stores each verified cutout through
  `GarmentPhotoStore.importBytes` (the same decode, orientation and white-flattening path as picked photos), then calls
  `GarmentImportRepository.saveReviewedPieces`, which writes the `user_garment` rows, `garment_import_source`
  provenance and `garment_import_decision` manifest in one transaction and moves the import to `FINALIZE_PENDING`.
- **Wardrobe and Outfit Builder.** Saved pieces are ordinary `user_garment` rows with `photo_id` set to the stored
  cutout. `App.kt` reloads the wardrobe after a save (`onSaved`), so they appear in Your Items, Garment Details, Edit,
  Archive, the Outfit Builder and Mirror like any other garment.
- **Finalization, replay and conflicts.** See section C.
- **Credentials.** `CredentialStore` (iOS Keychain, Android Keystore-encrypted files). The Mirror development token was
  moved out of `app_setting`; Mirror Settings masks it and never refills the field.

### Implemented, verified only against the fake server
- **Corrections** on the details page: choosing another version (`chooseRevision`), an alternative outline
  (`select_alternative`, synchronous), points tapped on the photo (`points`, asynchronous; `fitTapToImage` maps the tap to
  source-photo pixels, which match the server's oriented frame because the uploaded bytes are the already oriented
  local copy), and "Find it again" (`reprompt`, asynchronous). The Idempotency-Key is stored before sending
  (`PendingCorrection.key`); pending corrections are polled every 2 s, also after leaving or relaunching. A finished
  correction the user asked for becomes the pinned revision. Covered by `GarmentReviewViewModelTest`, not exercised
  against the live backend.

### Partial or not built
- No background upload service: no WorkManager on Android, no background `URLSession` on iOS (see section E).
- Review images are fetched again each time a review opens; nothing is cached on disk.
- No single-correction cancel (the API has none).
- No sign-in: the backend address and access token are entered on the development connection page.
- Server-side deletion and retention don't exist; the server keeps every uploaded photo, including after cancel.
- Camera capture isn't built (no existing camera integration).

## C. Finalization reliability and invariants

All in `ui/ImportState.kt` (`GarmentImportViewModel.finalize`, `uncoveredLineages`, `refuse`, `addNewPiecesAsNotKept`,
`retry`), `data/GarmentImportRepository.kt` and `sqldelight/.../GarmentImportReview.sq`, `GarmentImport.sq`, `6.sqm`.

- **Save before finalize.** The server only records what the app reports (`POST /v1/imports/{id}/finalize`); it can't
  see or check the wardrobe. The garments, photos, provenance and decisions are therefore written first
  (`saveReviewedPieces`, one transaction), and only an import in `FINALIZE_PENDING` is finalized. If finalize never
  succeeds, the garments still exist.
- **Reconcile before the first send.** While `garment_import.finalize_sent_at` is null, the driver fetches the
  session's current candidates and adds any lineage that must be decided but isn't covered as **rejected**
  (`uncoveredLineages` → `addRejectedDecisions`). Those lineages were never accepted, so no saved decision changes.
- **Freeze.** `markFinalizeSent` sets `finalize_sent_at` before the first request leaves (the query only sets it while
  null). From then on `finalize` reads `storedManifest` and sends it unchanged on every attempt: network errors, 5xx,
  timeouts, lost responses and relaunches all resend the same decisions.
- **`missing_decisions` after freezing** sets `ImportProblem.FINALIZE_INCOMPLETE` ("Saved, not synced"). The driver
  doesn't retry it. Only the user's **Add new pieces as not kept** (`addNewPiecesAsNotKept`) extends the decisions,
  which is safe because the refused request stored nothing.
- **`finalized_differently` and other refusals** (`cancelled`, `failed`, `not_ready`, 404, unexpected 4xx) set
  `ImportProblem.FINALIZE_REFUSED` ("Saved, not synced", with the reason). The driver doesn't retry; **Try again**
  (`retry`) clears the refusal and resends the same frozen decisions. There is deliberately no "mark as done".
- **Completion only on confirmation.** `completeImport` runs only when the finalize response has `status:
  "finalized"`. It sets `COMPLETED`, deletes the review rows, keeps provenance and decisions, and the source photo copy
  is then deleted. A refusal never deletes garments.
- **No duplicate garments.** Each lineage's garment id is created once, with its review row
  (`garment_import_review.garment_id`, unique). `saveReviewedPieces` skips garment ids that already exist and returns
  the unused new photo ids for deletion; it does nothing if the import is already `FINALIZE_PENDING` or `COMPLETED`.
  `garment_import_source` has `candidate_id UNIQUE` and `UNIQUE (import_id, lineage_id)`. A repeated Save tap is blocked
  by the `saving` flag. Photos stored by a save that fails before the transaction are deleted; anything left behind is
  removed by `GarmentRepository.removeUnreferencedPhotos` at the next launch.
- **Migration 6 → 7.** `6.sqm` adds `garment_import.finalize_sent_at INTEGER`. Existing imports read as not yet frozen,
  so an import already pending from database 6 is reconciled once before its first send.

**Tests.** `GarmentReviewViewModelTest`: `aLostFinalizeResponseIsResentWithTheSameDecisionsAndNothingIsDuplicated`,
`anUnfinishedImportIsFinishedAfterARelaunch`, `aLineageThatAppearsBeforeTheFirstSendIsReconciledAsRejected`,
`onceSentTheDecisionsNeverChangeByThemselves`, `aConflictingFinalizeStaysUnresolvedUntilWadrovaConfirms`,
`finishingWithNothingSelectedRejectsEveryPiece`, `aRepeatedSaveTapSavesOnce`,
`aFailedDownloadSavesNothingCleansUpAndCanBeRetriedWithoutDuplicates`. `GarmentImportReviewRepositoryTest`:
`savingAgainNeverDuplicatesAndHandsBackTheNewPhotos`, `oneCandidateCantBecomeTwoGarmentsAndAFailedSaveWritesNothing`,
`completingKeepsGarmentsAndProvenanceAndDropsTheReviewRows`. `SchemaMigrationTest`:
`versionSixDatabaseUpgradesWithPendingDecisionsUnfrozen`. `ImportReviewTest` covers `buildManifest`.

## D. Backend integration contract (only what the app uses)

Source of truth: `../wadrova-backend/docs/API.md` at backend commit `3460189` (deployed Mac stack: schema `0007`).
Client: `data/WadrovaApi.kt` (`KtorWadrovaApi`); its request shapes are pinned by `KtorWadrovaApiTest`.

- **Auth.** `Authorization: Bearer wdr_…`, a user token from `wadrova-admin issue-token`; never in a URL or log.
  `GET /v1/me` checks it on the connection page. 401 means missing, unknown, revoked or expired. Only `https://`
  addresses are accepted (`normalizedApiUrl`).
- **Create import.** `POST /v1/imports`: raw JPEG body (not multipart), `Idempotency-Key` (required),
  `X-Wadrova-SHA256` (required), `X-Client-Import-Ref`. 201 for a new import; a replay of the same key and bytes returns
  the same import with **200 and `Idempotent-Replay: true`**. Same key with different bytes → 409 `idempotency_conflict`.
- **Import detail and list.** `GET /v1/imports/{id}`; `GET /v1/imports?state=active&limit=100&cursor=…` (used only to
  find an import whose upload response was lost, by `client_ref`). Statuses: `queued`, `processing`, `ready`, `failed`,
  `cancelled`, `finalized`.
- **Candidates.** `GET /v1/extraction-sessions/{session_id}/candidates?include=superseded,below_threshold`. A candidate
  has no lineage id; lineages are rebuilt from `parent_candidate_id`. `wardrobe_category` is used only when
  `identity.status == resolved`; it's a provisional suggestion either way.
- **Assets.** `GET /v1/assets/{asset_id}/content`. The cutout is an RGBA PNG cropped to the candidate's box; the app
  checks its SHA-256 against the candidate's `cutout.sha256`. Responses are `Cache-Control: private, no-store`.
- **Corrections.** `POST /v1/candidates/{id}/corrections` with `Idempotency-Key` and a body of `select_alternative`
  (201, finished), `points` or `reprompt` (202, worker job). Only the newest revision can be corrected, and one
  correction per candidate may be in flight (409 `invalid_state` otherwise). `GET /v1/corrections/{id}` for status.
  **A correction replay returns its original 201 or 202 with `Idempotent-Replay: true`, unlike import replays (200).**
  The client never infers a replay from the status code; it treats any 2xx as the correction.
- **Cancel.** `POST /v1/imports/{id}/cancel`, no body, no key; naturally idempotent. 409 if finalized. Deletes nothing.
- **Finalize.** `POST /v1/imports/{id}/finalize`, JSON `{"accepted": [{"candidate_id", "client_garment_id"}],
  "rejected": [...]}`, **no Idempotency-Key** (the stored decisions are the record). Every lineage whose current
  revision is a detection exactly once; any revision may be listed, and an older one is recorded as superseded, never
  substituted. An identical resend, in any order, returns 200 with `Idempotent-Replay: true`. Refusals carry
  `error.details.reason` (`missing_decisions` with `candidate_ids`, `finalized_differently`, `not_ready`, `failed`,
  `cancelled`, and 400 reasons for malformed manifests), parsed into `WadrovaApiException.reason` and `candidateIds`.
- **Errors.** `{"error": {"code", "message", "retryable", "request_id", "details"}}`. The client maps 401, 404, 5xx
  (and `retryable`) and other refusals to `ApiFailure`. Transport errors become `UNREACHABLE` with no cause attached,
  so no URL or token can leak through messages.

## E. Platform-specific implementation

| | Android | iOS |
|---|---|---|
| Photo picker | `PickVisualMedia` (no permission) | `PHPickerViewController`; the picked file is copied, then deleted after import |
| Photo store | `AndroidGarmentPhotoStore`, `noBackupFilesDir/garment_photos` | `IosGarmentPhotoStore`, Application Support, excluded from backup |
| Format conversion | `BitmapFactory` decode with subsampling, EXIF orientation applied, transparency flattened onto white, re-encoded JPEG 88 | ImageIO thumbnail with transform, flattened onto white, `UIImageJPEGRepresentation` 0.88 |
| Bytes import (cutouts) | `importBytes` writes a private cache file and imports it the same way | `importBytes` writes a temp file and imports it the same way |
| Credentials | `AndroidCredentialStore`: AES-GCM with an Android Keystore key, files in `noBackupFilesDir/credentials` | `IosCredentialStore`: generic-password Keychain items, `AfterFirstUnlockThisDeviceOnly` |
| SHA-256 | `MessageDigest` | CommonCrypto `CC_SHA256` |
| HTTP engine | Ktor OkHttp | Ktor Darwin |
| Background | Nothing beyond the process staying alive | `IosBackgroundGrace` wraps an upload in a UIKit background task (about 30 s) |

**Lifecycle.** `App.kt` calls `GarmentImportViewModel.onForeground` on `ON_START` and `onBackground` through
`LeaveForegroundEffect`. Polling stops in the background; a running upload is allowed to finish. Nothing runs after a
force-quit; the next launch resumes every import from its stored state.

**Known platform limits.**
- **iOS Keychain in tests:** the Kotlin/Native simulator test runner has no Keychain (`errSecNotAvailable`, -25291), so
  `IosCredentialStoreTest` reports that and checks nothing. The Keychain was checked in the real app on the simulator in
  M4.4, through a throwaway XCUITest harness that isn't in the repository. That check found and fixed a bug: the
  `kSecReturnData` boolean must be added on the CF side.
- **Android emulator:** the system photo picker on the `Pixel_9_Pro` image needs a Done tap even for one photo. After an emulator
  restart its media index may need re-scanning (`adb shell content call --uri content://media --method scan_volume
  --arg external_primary`).
- **Mirror** reaches its own backend over HTTP on the LAN (port 8787); that is separate from the Wadrova API.

## F. Verification history

- **Automated, final M4.5 code:** 376 host tests (`testAndroidHostTest`, rerun at the checkpoint), 387 iOS simulator
  tests (`iosSimulatorArm64Test`, rerun at the checkpoint), 390 connected-device tests on the `Pixel_9_Pro` emulator
  (run after the finalize hardening, not rerun at the checkpoint because the emulator was stopped and no code had
  changed). Builds: `:androidApp:assembleDebug` and `:shared:compileKotlinIosArm64` (rerun at the checkpoint),
  `xcodebuild` for the iPhone 17 simulator (after the hardening).
- **Migrations:** `SchemaMigrationTest` upgrades every earlier version (1–6). On the emulator the app upgraded M4.4 →
  M4.5 (database 5 → 6) and then to database 7 with its existing garment intact.
- **Live, Android emulator, private tailnet, real Mac backend and Razer SAM 3 worker** (test user `kmp-app-dev`, CC0
  street-style photo, import `imp_1813…`): 7 detected pieces; the review showed verified cutouts (Pants with a resolved
  Bottoms suggestion, Coat with Outerwear); saving without colors was refused and opened the first piece; 2 pieces were
  saved. The server recorded **2 accepted and 5 rejected** decisions covering all 7 lineages, with the accepted ones
  mapped to the local garment ids. An identical finalize replayed from the Mac in a different order returned 200 with
  `Idempotent-Replay: true` and the same decisions. Wardrobe showed exactly 3 garments (the 2 new ones with their
  cutout photos) before and after a force-stop and relaunch, with no duplicates and no leftover import; both new pieces
  built a look in the Outfit Builder.
- **Earlier (M4.4):** live upload, idempotent replay, relaunch and cancel on the emulator; Keychain and upgrade checks in
  the iOS simulator app; a real SAM 3 import from a physical iPhone.
- **Physical iPhone, M4.5: owner-reported.** The project owner subsequently confirmed successful physical-iPhone
  end-to-end verification. The previous agent didn't observe or reproduce it and has no steps or logs from it.

## G. Known limitations and deferred issues

- Correction variants were verified against the fake server (`FakeWadrovaServer`), not comprehensively against the
  live backend.
- Review cutouts are downloaded again each time a review opens rather than cached persistently.
- Cancelling a single correction isn't supported by the current API.
- The Android emulator once lost its configured backend address and token while its garments survived; the cause
  wasn't established. Re-entering them on the development connection page fixed it.
- The Mirror backend's development process was started by the previous Claude session; it was stopped and may need
  starting independently (`mirror_backend/README.md`). Its token was rotated on 2026-11-04 after being exposed in a
  session; other Mirror clients need the new value from `mirror_backend/.env`.
- If the app stops after a correction was sent but before its response was stored, that correction can't be replayed
  (its body isn't stored). The review says so, and the result appears as a newer revision on reload if it ran.
- A photo stored by a save that's interrupted before the database transaction stays on disk until the next launch's
  cleanup.
- Color is mandatory for imported pieces because the existing garment schema requires it.
- Completed imports keep their `garment_import` row for provenance; it's hidden from Pending Imports.
- Server-side deletion and retention aren't implemented; never promise them, and test only with non-personal photos.
- The `kmp-app-dev` development token expires on 2026-11-07. The test account also holds 10 imports made from the
  owner's iPhone (6 earlier, 4 later, 2 of them finalized). Their privacy classification is pending with the owner:
  don't open, download, finalize, cancel or modify them.

## H. Current development environment

- **Build and test:** the commands in section A. Emulator AVD used: `Pixel_9_Pro`
  (`emulator -avd Pixel_9_Pro -no-snapshot-save`).
- **Launch:** Android: `./gradlew :androidApp:installDebug`, then `adb shell am start -n com.example.wadrova/.MainActivity`.
  iOS: run `iosApp/iosApp.xcodeproj` from Xcode (simulator or a connected device with the project's team).
- **KMP networking:** `data/WadrovaApi.kt` (`createWadrovaHttpClient` timeouts 15 s connect, 60 s request, 30 s socket;
  `normalizedApiUrl`). The address is a setting (`app_setting` key `wadrova_api_url`) and the token is in
  `CredentialStore` (`Credential.WADROVA_ACCESS_TOKEN`), both entered on the development connection page
  (`ImportConnectionScreen`), reached from an import that says "Connect to continue".
- **How the app reaches the Mac backend:** the Mac serves the API at `127.0.0.1:8000` (Docker) and exposes it to the
  tailnet through Tailscale Serve over HTTPS; see `../wadrova-backend/docs/MAC_BACKEND_SETUP.md` section 4 for the
  hostname. The Android emulator reaches it through the Mac's network; a phone must be on the same tailnet. **Keep the
  private connectivity configuration unchanged: tailnet-only Serve, Funnel off.** Changing exposure needs the owner's
  approval. Note that `tailscale funnel --https=443 off` also deletes the Serve handler, so it must be restored with
  `tailscale serve --bg --https=443 http://127.0.0.1:8000`.
- **State at handoff:** the backend Docker stack was stopped at the owner's request (`docker compose -f
  docker-compose.yml -f docker-compose.mac.yml -p wadrova stop`; volumes kept), the Razer worker was stopped by the
  owner, and the Mirror backend, simulators and emulator were stopped. Coordinate with the backend owner before starting
  them or creating imports.
- **Documentation:** `CLAUDE.md`, `docs/DEVELOPMENT_STATE.md`, `docs/product.md`, this file;
  `../wadrova-backend/docs/API.md` and `../wadrova-backend/docs/MOBILE_GARMENT_IMPORT_ARCHITECTURE.md` for the backend.

## I. What must not be broken

| Invariant | Protected by |
|---|---|
| Existing wardrobe records survive database upgrades | `SchemaMigrationTest` (versions 1–6), `NoSampleWardrobeTest` |
| Manual garment addition keeps working | `GarmentRepositoryTest`, `WardrobeUiStateTest`, `WardrobeViewModelTest`, `ImportStatusTest.addAPieceOpensTheChooserAndManualStillOpensTheForm` |
| Saving selected garments is durable and atomic | `GarmentImportReviewRepositoryTest.savingMakesOrdinaryGarmentsWithProvenanceAndTheManifestInOneStep`, `oneCandidateCantBecomeTwoGarmentsAndAFailedSaveWritesNothing` |
| Retries can't create duplicate garments or imports | `savingAgainNeverDuplicatesAndHandsBackTheNewPhotos`, `aRepeatedSaveTapSavesOnce`, `aFailedDownloadSavesNothingCleansUpAndCanBeRetriedWithoutDuplicates`, `GarmentImportViewModelTest.aLostResponseIsResentWithTheSameKeyAndBytesAndMakesOneImport` |
| Finalization retries can't silently change decisions | `onceSentTheDecisionsNeverChangeByThemselves`, `aLostFinalizeResponseIsResentWithTheSameDecisionsAndNothingIsDuplicated`, `aConflictingFinalizeStaysUnresolvedUntilWadrovaConfirms`, `anUnfinishedImportIsFinishedAfterARelaunch` |
| Source photos and garment cutouts stay distinct | The import's source photo is its own `garment_import.photo_id`; each saved garment gets a new photo id from `importBytes` (`savingMakesOrdinaryGarmentsFromTheVerifiedCutoutsAndFinishesWithTheServer` checks the stored bytes are the cutout's) |
| Candidate revisions and provenance stay intact | `selectionsAndDetailsSurviveARelaunchAndAPinnedRevisionIsntSwitched`, `ImportReviewTest`, `garment_import_source` constraints |
| Imported garments work in the Outfit Builder | They are ordinary `user_garment` rows (`savingMakesOrdinaryGarmentsWithProvenanceAndTheManifestInOneStep` edits and archives them); live-verified on the emulator |
| User wardrobe data is never discarded during recovery | Refusals keep garments (`aConflictingFinalizeStaysUnresolvedUntilWadrovaConfirms`); photo cleanup keeps referenced photos (`savedGarmentPhotosAreKeptByPhotoCleanup`) |
| Images are what the server published | `KtorWadrovaApiTest.assetBytesAreAcceptedOnlyWhenTheyMatchTheirHash`, `aCorruptImageIsShownAsUnavailableNotFaked`, `anImageThatDoesntDecodeIsNotSaved`, platform `importBytes` tests |

## J. Next planned product milestone (context only)

**Wadrova Studio: optional AI product image generation.** The owner's intent: let the user pick an already segmented
garment and optionally turn its cutout into a polished e-commerce-style product image with OpenAI image editing.

Principles from the owner:
- Optional; never a prerequisite for importing a garment.
- The segmented cutout stays the source of truth; generated images are stored separately and never silently replace the
  original imagery.
- Preserve the garment's color, silhouette, pattern, material, stitching, logos and visible details as closely as
  possible.
- The user reviews a result and chooses whether to keep or show it.
- Generation failures must not corrupt or block wardrobe operations.
- OpenAI credentials never live in the mobile client, and images are sent only when the user explicitly asks.

Relevant existing code to read first: `GeneratedImageStore` and `user_garment.extracted_image_id` (Mirror's approved
background-removed image, shown before the photo by `GarmentVisual`), `MirrorRepository`, `KtorMirrorBackend` and the
consent dialogs in `MirrorScreens.kt`.

**This section is context, not authorization.** No API, schema, model choice, pricing or implementation has been
decided. The incoming agent will receive its own scoped assignment.
