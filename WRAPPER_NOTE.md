# Gradle Wrapper

The build invokes the repository-root Gradle Wrapper, pinned to Gradle 8.9 using the distribution SHA-256 in `gradle/wrapper/gradle-wrapper.properties`.

Expected layout:
- `gradlew` (executable POSIX script)
- `gradlew.bat` (Windows script)
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

GitHub Actions is configured to run `./gradlew --version` as a preflight and to use `./gradlew` for build and test tasks. The wrapper JAR was recognized by `gradle/actions/setup-gradle` as a known valid Gradle Wrapper JAR. The CI run after the executable-bit repair is the verification that the command now launches and completes the Android build.
