# AGENTS Instructions

## Testing

- The full unit test suite can be slow. Run only the tests relevant to your changes.
- For code changes in a module, run that module's unit tests:
  - Example: `./gradlew :app:testDebugUnitTest`.
- To run a single test class or method, use Gradle's `--tests` filter. Examples:
  - `./gradlew :app:testDebugUnitTest --tests "li.crescio.penates.diana.recorder.AndroidRecorderTest"`
  - `./gradlew :app:testDebugUnitTest --tests "li.crescio.penates.diana.recorder.AndroidRecorderTest.someMethod"`
- Only run the full suite (`./gradlew test`) when changes affect multiple modules or wide areas of the codebase.
- Documentation-only or build script changes may skip tests.
