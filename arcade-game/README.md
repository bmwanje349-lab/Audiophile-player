# Turbo Trails — Colorful Arcade Racer

A standalone Android arcade driving game with a bright, playful 3D-style road, touch steering, traffic, collectible coins, score tracking, crash screen, and one-tap restart.

## Gameplay
- Tap the **left** or **right** side near the bottom to switch lanes.
- Avoid traffic cars and collect gold coins.
- The road scrolls faster as your distance grows.
- After a crash, tap the restart panel to play again.
- Portrait layout; designed for touch screens and low-memory phones.

## Project structure
This Android project lives entirely in `arcade-game/`. It has its own Gradle settings, application ID (`com.bmwanje.arcaderush`), manifest, and Java game code. The APK build job runs Gradle from this folder only; it does not build or package the music-player app.

## Build an APK using GitHub Actions (phone-friendly)
1. Open the repository's **Actions** tab.
2. Select **Turbo Trails Android APK**.
3. Choose **Run workflow** on branch `colorful-arcade-game` if manual dispatch is available.
4. Wait for the run to finish successfully.
5. Open the completed run and download the **Turbo-Trails-debug-APK** artifact.
6. Extract the ZIP and install `app-debug.apk` on your Android phone. If Android asks, allow installs from the browser or file manager you used.

A push to the `colorful-arcade-game` branch that changes this project also starts a build. No Codespace or local Android Studio setup is required.

## Notes
- This first version uses custom Canvas drawing for its cars, road, scenery, HUD, and effects, so it does not need a large 3D engine or network access at runtime.
- The visuals are a lightweight 3D-style arcade illusion rather than a polygonal 3D engine.
- The APK is a debug build for testing. A signed release build can be prepared later.
