# Equinox v0.5 — Step 06 FIXED

This is the corrected Step 06 project.

Fix applied:
- Replaced invalid `DoubleArray.ifEmpty { ... }` usage in `Sonifier.kt` with a type-safe `let { ... }` fallback.

Build in Android Studio as before. Application ID remains `de.equinox.v05`, so the APK installs as an update to the existing Equinox app.
