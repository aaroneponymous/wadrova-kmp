# Wadrova

Mobile personal styling app for iOS and Android, built with Kotlin Multiplatform and Compose Multiplatform. See [docs/product.md](docs/product.md) for the product brief.

The app has five primary tabs: Home, Wardrobe, Mirror, Stylist, and Discover. Home, Stylist, and Discover are placeholders, so the app opens on Mirror for now. The app has a Digital Wardrobe and Outfit Builder with illustrated sample pieces and user-created garments with optional photos. Garments and looks are saved to a local SQLite database, and photos to app-private files excluded from cloud backups. `com.example.wadrova` is a deliberate development placeholder for the application ID.

## Structure

- `shared/`: shared Kotlin code and Compose UI
- `androidApp/`: Android entry point
- `iosApp/`: iOS entry point (open in Xcode)

## Commands

```bash
./gradlew :androidApp:assembleDebug       # Android debug build
./gradlew :shared:testAndroidHostTest     # shared tests on the Android host
./gradlew :shared:iosSimulatorArm64Test   # shared tests on the iOS simulator
./gradlew :shared:connectedAndroidDeviceTest  # shared tests and photo store tests on a running emulator or device

# iOS app build; replace the simulator name with an available one
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## Mirror

Mirror's image generation needs the local backend in [mirror_backend](mirror_backend/README.md). Live generation is off until it's enabled there. The app connects to `http://127.0.0.1:8787` by default, which works from a simulator, or from Android after `adb reverse tcp:8787 tcp:8787`. Other setups use Mirror Settings in the app.

## Verification status

OpenAI Mirror MVP, 2026-10-31. No request has been sent to OpenAI, so generated image quality is untested.

| Check | Result |
|---|---|
| Android build, host tests, and emulator tests | Passed |
| iOS simulator build and tests | Passed |
| Backend tests, using a fake service and a mock transport | Passed |
| Android upgrade over a real version 3 database | Passed |
| All three flows on the Android emulator | Passed against a local stub returning fixture images |
| Same flows on iOS, and on physical devices | Not run |
