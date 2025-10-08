# Splash screen concept

## Task list
1. Update WORKLOG.md with a splash-screen initiative entry and seed this thought note for collaboration.
2. Audit the Compose startup flow to confirm how a splash state can gate the main application surface.
3. Illustrate a huntress silhouette in vector form, anchored by a lunar backdrop inspired by Diana mythology.
4. Build a reusable Compose `SplashScreen` that renders the vector art alongside brand copy.
5. Bind the BuildConfig version string into the splash layout using a compact monospace treatment.
6. Export the illustration as a standalone SVG asset and describe usage expectations.
7. Refresh WORKLOG.md and this note with implementation outcomes so the documentation stays in sync.

## Visual direction
- Palette leans into deep violets and moonlit golds already present in the Diana branding.
- Illustration evokes a poised archer framed by a crescent glow to telegraph focus and readiness.
- Typography contrasts a refined display for the product name with a utilitarian monospace build stamp.

## Implementation snapshot
- `SplashScreen` Compose surface wraps the illustration, tagline, and build version badge.
- Startup flow in `MainActivity` holds the main UI behind a short-lived splash state to avoid abrupt transitions while Firebase bootstraps.
- The SVG asset in `docs/assets/diana_splash.svg` mirrors the on-device vector so design teams can iterate without decompiling the app.

## Follow-ups
- Consider animating subtle bow tension or gradient shimmer using Compose `InfiniteTransition` once performance impact is profiled.
- Evaluate showing session-sync progress on the splash if remote bootstrap time grows.
