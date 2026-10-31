# Wadrova

Mobile personal styling app for iOS and Android, built with Kotlin Multiplatform and Compose Multiplatform. See [docs/product.md](docs/product.md) for the product brief.

The app UI is still the template screen; the outfit composition rules in `shared` aren't connected to it yet. `com.example.wadrova` is a deliberate development placeholder for the application ID.

## Structure

- `shared/`: shared Kotlin code and Compose UI
- `androidApp/`: Android entry point
- `iosApp/`: iOS entry point (open in Xcode)

## Commands

```bash
./gradlew :androidApp:assembleDebug       # Android debug build
./gradlew :shared:testAndroidHostTest     # shared tests on the Android host
./gradlew :shared:iosSimulatorArm64Test   # shared tests on the iOS simulator

# iOS app build; replace the simulator name with an available one
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## Verification status

Template plus outfit composition rules, 2026-10-31, on Xcode 27.0 and JDK 21:

| Check | Result |
|---|---|
| Android debug build | Passed; APK produced |
| Android host tests | Passed |
| iOS simulator tests | Passed |
| iOS simulator build and launch | Passed on iPhone 17 (iOS 27.0) |
| Android emulator install and launch | Passed on Pixel 9 Pro (API 37); template screen shown |
| Android physical device | Not run |
| iOS physical device | Not run; `TEAM_ID` is unset |
