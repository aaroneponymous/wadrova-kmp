# Wadrova KMP: working guide

Personal styling app for Android and iOS, built with Kotlin Multiplatform and Compose Multiplatform. Read `docs/DEVELOPMENT_STATE.md` for what is built and what is open, and `docs/product.md` for the product brief, honesty rules and privacy rules. `AGENTS.md` holds the same core rules for other tools.

## Layout

Sibling folders under `../` (only this one is part of this repository):
- `wadrova_kmp/`: this app.
- `wadrova_base44/`: the React prototype. Reference only; never modify it.
- `wadrova_vision_lab/`: separate Python research repo for garment extraction. Findings move into the app as decisions, not code.
- `wadrova-brand-svg/`: master brand SVGs and Lottie files. Not a Git repo; never modify it.

In this repo:
- `shared/src/commonMain/kotlin/com/example/wadrova/`
  - `domain/`: pure Kotlin rules (outfit composition, garments, looks). No Compose or platform code.
  - `data/`: SQLDelight repositories, photo and generated-image stores, the Mirror backend client (Ktor).
  - `ui/`: Compose screens plus their state. `App.kt` wires tabs, screens and global dialogs.
- `shared/src/commonMain/sqldelight/`: `.sq` schemas and `1.sqm`–`3.sqm` migrations (database version 4).
- `shared/src/commonMain/composeResources/`: fonts (Manrope, Instrument Serif) and generated brand vectors.
- `shared/src/{androidMain,iosMain}/`: photo stores, photo pickers, back handling, database drivers.
- `androidApp/`, `iosApp/`: thin platform entry points. `mirror_backend/`: local FastAPI server for Mirror (see its README).
- `tools/brand/generate_brand_assets.py`: regenerates every derived brand file from `../wadrova-brand-svg/`.

## State and UI conventions

- Screen state is an immutable data class held by a ViewModel. Wardrobe puts its logic in pure transitions on `WardrobeUiState`, called as `update { it.someTransition() }`, so it is unit-testable; `WardrobeViewModel` only runs repository work. Mirror keeps `MirrorUiState` simpler and its flow logic in `MirrorViewModel` methods (`MirrorState.kt`).
- Navigation is hand-rolled: a five-tab bar (`PrimaryTab`, opens on Mirror) plus a `Screen` enum per tab. There is no navigation library.
- Wardrobe drafts: `draftIds` is the new-look selection from Your Items; `editing: LookEdit?` is a saved look open in the Builder and exists only while the Builder is open. `builderIds` is whichever is active. Never let a saved look's pieces or id leak into `draftIds`.
- Outfit rules live in `domain/OutfitComposition.kt` (`toggleInOutfit`, `replacedByAdding`, `canSaveLook`, `nextVariationName`). Reuse them; don't re-encode category rules in UI.
- Destructive or replacing actions confirm first through a `pending*` state field rendered as a dialog. Repeated submissions are blocked by a `saving`/`deleting` flag set before suspending.
- Repositories validate and write in one transaction; failures keep the user's form or draft so they can retry. Success is reported only after persistence.
- Design tokens are in `ui/Theme.kt` (`WadrovaColors`, typography). Reuse `ActionButton`, `GarmentCard`, `CategoryChips`, `GarmentVisual` and `Moodboard` before adding new components.
- Brand marks come only from `WadrovaLockup` / `WadrovaSymbol` in `ui/Brand.kt`. Never redraw the logo or edit the generated XML; rerun the generator.
- Match the surrounding code: short KDoc on non-obvious decisions, no new modules, layers or dependencies without a current need.
- Schema changes need a new `.sqm` migration and a `SchemaMigrationTest` case.

## Commands

Run from `wadrova_kmp/`. Before calling work done, run the first four and report the real results:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :shared:testAndroidHostTest
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:compileKotlinIosArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build      # when iOS is affected
./gradlew :shared:connectedAndroidDeviceTest                       # needs a running emulator or device
python3 tools/brand/generate_brand_assets.py                       # after brand collection changes; needs rsvg-convert
```

Android touch checks in past milestones used the `Pixel_9_Pro` AVD started with `-no-snapshot-save`, so test data is discarded on shutdown. If Gradle reports a missing resource right after moving or renaming one, rerun with `--rerun --no-build-cache`.

## Privacy, credentials and local data

- Never print, log, commit or copy `.env` values, the OpenAI key or the `X-Wadrova-Dev-Token`. The app stores the token in its local database `app_setting` table, so never dump that table.
- Never make OpenAI or other paid API calls unless the user explicitly asks. Mirror's live generation is off unless enabled in the backend's `.env`.
- Wardrobes, photos and databases on the user's devices are private. Test with isolated data (cleared emulator app, sample pieces, synthetic images). Don't copy them off a device without explicit permission, and delete any copy afterwards.
- Personal images leave the device only through Mirror, after an explicit consent dialog.
- Keep `com.example.wadrova` as the application ID unless asked.

## Workflow

- Read the relevant code and check `git status` and recent commits before starting. Don't assume a previous session finished something.
- Work on a feature branch. Commit, merge, rebase or push only when the user asks. This repo has no remote configured.
- Never claim a build, test, or device check passed unless you ran it. Report emulator, simulator and physical-device results separately; iOS interaction has mostly been verified only by the user on their iPhone.
- Flag ambiguous product rules instead of inventing behavior, and keep honesty rules: label samples, never fake try-on results, products or prices.
