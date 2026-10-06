# Gradle Wrapper

The project uses the official Gradle Wrapper (generated with Gradle's own `wrapper` task):

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

The distribution is pinned to Gradle 8.9 with its SHA-256 checksum. The GitHub Actions workflow
invokes `./gradlew` directly.
