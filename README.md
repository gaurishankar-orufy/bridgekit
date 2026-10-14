# BridgeKit

Pluggable Android features that talk through one notification center, so a module can be removed from a build without stubs.

| Library | Contents | Use it in |
|---|---|---|
| `bridgekit-core` | `NotificationCenter`, `Notification`, `BridgeFeature`, `ActivityHolder` | every feature, the host |
| `bridgekit-ui` | `SlotRegistry`, `Slot()`, `Overlays()`, `bridgeRoutes()`, `navigateIfExists()` | features that draw Compose UI, the host |
| `bridgekit-platform` | BOM, one version for both | the host |

[![](https://jitpack.io/v/gaurishankar-orufy/bridgekit.svg)](https://jitpack.io/#gaurishankar-orufy/bridgekit)

## Install

Published through [JitPack](https://jitpack.io/#gaurishankar-orufy/bridgekit); no token needed.

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
maven { url = uri("https://jitpack.io") }
```
```kotlin
implementation(platform("com.github.gaurishankar-orufy.bridgekit:bridgekit-platform:v0.1.0"))
implementation("com.github.gaurishankar-orufy.bridgekit:bridgekit-core")   // or bridgekit-ui for UI features
```
Kotlin packages are `com.bridge.kit.core` and `com.bridge.kit.ui`.

## A feature

```kotlin
class HapticsFeature : BridgeFeature {
    override val id = "haptics"                    // always listened
    override val listensTo = emptySet<String>()    // null (default) = every category; set = those + id
    override fun onAttach(context: Context) = true // return false to stay silent (e.g. disabled in config)
    override fun handle(n: Notification) {
        when (n.category to n.name) {
            "haptics" to "vibrate" -> { /* ... */ n.reply(mapOf("success" to true)) }
        }
    }
}
```
Register it in `src/main/resources/META-INF/services/com.bridge.kit.core.BridgeFeature` (one class name per line).

## The host

```kotlin
// once, on the main thread, after config is loaded and before setContent
NotificationCenter.default.registerAll(ProcessLifecycleOwner.get(), applicationContext)

val handled = NotificationCenter.default.post("haptics", "vibrate", mapOf("ms" to 20))
if (!handled) { /* module not in this build, or disabled */ }
```

## UI

| Kind | Feature calls | Host draws |
|---|---|---|
| Single slot (one owner on screen) | `SlotRegistry.set(this, "bottomBar") { ... }` in `onAttach` | `Slot("bottomBar") { fallback }` |
| Overlay (always there, stacked) | `SlotRegistry.addOverlay(this, "fam", layer) { ... }` in `onAttach` | `Overlays(layer)` |
| Modal (dialog / sheet that comes and goes) | `Modals.show(this, "nfcSheet") { ... }` / `Modals.close("nfcSheet")` | `ModalHost()` once |
| Screen | `SlotRegistry.addRoute("passcode") { ... }` in `onAttach` | `bridgeRoutes()` in `NavHost` |

**Priority, everywhere:** lower number wins (0 = highest). On a tie, the owner's module priority (`BridgeFeature.priority`) decides, then whoever came first. Default for both is 100.

**Modals:** one at a time. The showing modal stays until its feature closes it; priority only decides who goes next. Whether tapping outside or back closes it is up to the feature's own sheet or dialog.

## Rules
- Do not add a ProGuard `-keep` rule for `BridgeFeature`; it stops R8 from optimising `ServiceLoader`.
- Handlers run on the main thread; start background work yourself.
- With the default `listensTo = null`, match on `category` and `name` together.
- Register routes only in `onAttach`; navigate with `navigateIfExists`.

## Build and release
```bash
./gradlew testDebugUnitTest publishToMavenLocal   # local check
```
Release: push a tag (`git tag v0.2.0 && git push origin v0.2.0`). JitPack builds it the first time someone requests that version,
or open the JitPack page above and press "Get it" to build it right away. Build settings are in `jitpack.yml`.

## License
Apache License 2.0, see [LICENSE](LICENSE).
