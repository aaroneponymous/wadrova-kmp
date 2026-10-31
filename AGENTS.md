# AGENTS.md

Read `README.md` for commands and `docs/product.md` for the product brief and honesty rules.

## Before calling work done

Run these and report the actual results:

- `./gradlew :androidApp:assembleDebug`
- `./gradlew :shared:testAndroidHostTest`
- `./gradlew :shared:iosSimulatorArm64Test`
- For changes that affect the iOS app, build it with `xcodebuild` as shown in the README.

Never claim a build or interaction was verified unless you ran it.

## Rules

- Code in `shared/src/commonMain/kotlin/com/example/wadrova/domain/` stays free of UI and platform code.
- The React prototype in the sibling `wadrova_base44` directory is a reference. Preserve its intended behavior instead of translating it mechanically, and flag ambiguous rules instead of inventing product behavior. Don't modify it.
- Don't add modules, layers, dependencies, or abstractions until a current feature needs them.
- Follow the honesty and privacy rules in `docs/product.md`.
- `com.example.wadrova` stays as the application ID for now. Don't rename it without being asked.
