# Unit Tests

JVM unit tests under `app/src/test/`. There is **no `androidTest` source set** —
no instrumented or Compose UI tests exist, and none are expected. Anything that
needs a device is verified by hand.

## Running them — read this first

`gradle.properties` sets `org.gradle.caching=true`. A bare

```
./gradlew testDebugUnitTest
```

can report `BUILD SUCCESSFUL in 1s` with `FROM-CACHE` or `UP-TO-DATE` **without
executing a single test**. It is not a hypothetical: it happened repeatedly
during the work that produced this file, and a green run that proves nothing is
worse than a red one.

Always verify with:

```
./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache
```

Gradle prints **no test count when everything passes** — only on failure. To
confirm a run actually happened, count the XML results:

```powershell
$x = Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml" | ForEach-Object { [xml]$c = Get-Content $_.FullName; $c.testsuite }
"tests=$(($x | Measure-Object -Property tests -Sum).Sum) failures=$(($x | Measure-Object -Property failures -Sum).Sum) errors=$(($x | Measure-Object -Property errors -Sum).Sum)"
```

## Pitfalls

Each of these produced a confusing failure before it was understood.

### Relaxed mocks answer Flow calls with an empty flow

`mockk(relaxed = true)` on `PreferencesManager` returns an **empty** flow for
every Flow-returning member. A ViewModel `init` that calls `.first()` on one then
throws `NoSuchElementException` before the test body starts, and the failure
points at the ViewModel rather than at the mock.

Stub every flow the `init` path touches. `DashboardViewModelVpnActionTest` does
this and says why in a comment — copy that shape.

### An unbounded polling loop makes `runTest` never finish

`DashboardViewModel` launches a `while (true) { delay(n); … }` loop from `init` into
`viewModelScope`; `VpnViewModel` launches `while (isActive) { …; delay(n) }` from its
own `init`. Both are unbounded under `runTest`, because the test's own scope stays
active regardless of which condition guards the loop. With `Dispatchers.setMain(testDispatcher)`
in `@Before`, `runTest` adopts the same scheduler, so its closing "advance to
idle" never terminates — it keeps running the loop and allocating until the heap
is gone.

**The stacktrace lies.** It points at `kotlin.reflect…ProtoBuf`, which is merely
whatever allocation was in flight when memory ran out. Raising the heap does not
help; a genuinely unbounded loop exhausts any size.

Cancel the `viewModelScope` **inside** the test body, before `runTest` performs
its final advance:

- `VpnViewModelTest` routes every ViewModel through a `ViewModelStore` and calls
  `clear()` — public API, no reflection.
- `DashboardViewModelVpnActionTest` uses a reflective `clearViewModel()` helper.

Either works; they solve the same hazard.

### `android.util.*` returns null under plain JUnit

`app/build.gradle.kts` sets `isReturnDefaultValues = true`, so framework calls
return type defaults instead of throwing. `Base64.decode(...)` yields `null`, and
`String(null, UTF_8)` then throws — meaning a class calling it directly always
fails under test, no matter what the assertions say.

Production code that reaches for a framework class is not unit testable. Put the
dependency behind an interface: see `util/Base64Decoder.kt` with
`AndroidBase64Decoder` for production and `java.util.Base64` in the test.

### Time needs to be injectable

Boundary cases such as "exactly five minutes from now" cannot be tested against
`Instant.now()` without racing the wall clock. `util/Clock.kt` is a `fun
interface` bound in `AppModule`; view models take it as a constructor parameter
and tests hand in a fixed instant.

### PowerShell splits `-PappVersionName=1.2.3` at the dots

Gradle property arguments containing dots must be quoted:
`"-PappVersionName=1.2.3"`. Unquoted, PowerShell passes fragments and Gradle
reports a missing task.

## Conventions

- JUnit4 with MockK, Turbine and `kotlinx-coroutines-test`.
- One test class per production class, named `<Class>Test`, in the mirrored
  package.
- Test names are backticked sentences describing behaviour, not method names —
  `a failed save restores the previous state`, not `testSaveError`.
- Assert against real behaviour, not against the mock. A test that only verifies
  a mock was called passes even when the thing it guards is broken. Where the
  bug lives in serialisation, assert the serialised bytes —
  `SleepConfigRepositoryTest` reads the `RequestBody` back through an okio
  `Buffer` for exactly that reason.
- Seed a state that differs from the default before asserting a transition. An
  assertion that a value equals its own initial value proves nothing.

## Adding a test for a ViewModel

1. `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`,
   `Dispatchers.resetMain()` in `@After`.
2. Stub every `PreferencesManager` flow the `init` path calls `.first()` on.
3. If the ViewModel starts a polling loop in `init`, cancel its scope inside the
   test body — see the pitfall above.
4. Collect one-shot events with Turbine (`vm.snackbarEvent.test { … }`); assert
   `expectNoEvents()` when the point is that exactly one event fires.
