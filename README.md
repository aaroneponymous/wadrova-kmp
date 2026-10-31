# Wadrova

Mobile personal styling app for iOS and Android, built with Kotlin Multiplatform and Compose Multiplatform. See [docs/product.md](docs/product.md) for the product brief.

The app has a Digital Wardrobe and Outfit Builder with illustrated sample pieces and user-created garments with optional photos. Garments and looks are saved to a local SQLite database, and photos to app-private files excluded from cloud backups. `com.example.wadrova` is a deliberate development placeholder for the application ID.

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

## Verification status

Local garment photos, 2026-10-31, on Xcode 27.0 and JDK 21:

| Check | Result |
|---|---|
| Android debug build, host tests, and on-device tests | Passed on Pixel 9 Pro emulator (API 37) |
| iOS simulator build and tests | Passed on iOS simulator |
| Android system picker and photo lifecycle | Passed by driving the running app |
| Android and iOS upgrade over version 2 data | Passed by installing over existing data |
| iOS photo lifecycle | Passed by a temporary test using real stores and repositories across processes |
| iOS system picker | Not run; no way to tap the simulator here |
| Physical devices and real backup and restore | Not run |
