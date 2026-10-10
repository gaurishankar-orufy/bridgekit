# BridgeKit

Pluggable Android features that talk through one notification center, so a module can be removed from a build without stubs.

| Library | Contents | Use it in |
|---|---|---|
| `bridgekit-core` | `NotificationCenter`, `Notification`, `BridgeFeature`, `ActivityHolder` | every feature, the host |
| `bridgekit-ui` | `SlotRegistry`, `Slot()`, `Overlays()`, `bridgeRoutes()`, `navigateIfExists()` | features that draw Compose UI, the host |
| `bridgekit-platform` | BOM, one version for both | the host |

```kotlin
implementation(platform("com.bridge.kit:bridgekit-platform:0.1.0"))
implementation("com.bridge.kit:bridgekit-core")   // or bridgekit-ui for UI features
```

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

UI: features call `SlotRegistry.set(...)`, `addOverlay(...)`, `addRoute(...)` in `onAttach`; the host draws them with `Slot(name) { fallback }`, `Overlays(layer)` and `bridgeRoutes()` inside its `NavHost`.

## Rules
- Do not add a ProGuard `-keep` rule for `BridgeFeature`; it stops R8 from optimising `ServiceLoader`.
- Handlers run on the main thread; start background work yourself.
- With the default `listensTo = null`, match on `category` and `name` together.
- Register routes only in `onAttach`; navigate with `navigateIfExists`.

## Build
```bash
./gradlew testDebugUnitTest publishToMavenLocal
```
