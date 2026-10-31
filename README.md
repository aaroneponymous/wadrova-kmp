# Wadrova

Mobile personal styling app for iOS and Android, built with Kotlin Multiplatform and Compose Multiplatform. See [docs/product.md](docs/product.md) for the product brief.

The app has a Digital Wardrobe and Outfit Builder using illustrated sample data, and saves looks to a local SQLite database. `com.example.wadrova` is a deliberate development placeholder for the application ID.

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

Save Look with local persistence, 2026-10-31, on Xcode 27.0 and JDK 21:

| Check | Result |
|---|---|
| Android debug build | Passed; APK produced |
| Android host tests | Passed |
| iOS simulator tests | Passed |
| iOS simulator build and launch | Passed on iPhone 17 (iOS 27.0) |
| Android save, reopen, and relaunch after force-stop | Passed by driving the running app on Pixel 9 Pro (API 37) |
| iOS save and reopen | Passed through a temporary Compose UI test across separate processes; no real touch input |
| iOS app reading saved looks after relaunch | Passed by count on the wardrobe screen, using a database written by that test |
| Physical devices | Not run |
