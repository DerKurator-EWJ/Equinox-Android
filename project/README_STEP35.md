# Equinox v0.6 — Step 35: Product UI / Motion + Haptics

This step is a product-design pass on top of Step 34. It does not change the learning core.

## Main changes
- Unified canvas remains the only main visual surface.
- Bottom control bar is now a floating rounded product control rather than a developer toolbar.
- Settings open as an animated bottom sheet over the live canvas instead of replacing the whole screen.
- Dim scrim + rounded sheet + grabber keep visual continuity with the unified canvas.
- Settings navigation uses compact capsule controls.
- Buttons and toggles receive consistent rounded styling and press motion.
- Haptic ticks are used for mode/settings interactions and blend-slider engagement.
- Press animations use short scale/alpha motion; settings sheet uses eased slide/fade transitions.
- Android navigation inset is still respected.
- Lab remains available, but visually subordinate to the product surface.

## Philosophy
The normal app should feel like one living canvas with controls appearing only when requested. Engineering diagnostics stay behind the settings/lab layer.

## Build note
The full Android APK was not compiled in the generation environment because Android SDK/Gradle toolchains are not available there. Build in Android Studio as before.
