# Hermes Remote

Hermes Remote is an unofficial Android client for a Hermes gateway that I run myself. I built it because the browser dashboard's terminal input is hard to use on a phone: the on-screen keyboard and the PTY do not get along, and the terminal screen is not the same thing as the conversation history.

The app keeps the chat native. Messages come from the gateway's REST API; a hidden WebView holds the PTY connection only so the app can send input. It is not an official Hermes app and it is not on Google Play.

## What it does

- Connects to a self-hosted Hermes gateway with basic auth, or directly when the gateway has no login.
- Shows sessions and recent messages, with older messages loaded as you scroll up.
- Renders assistant messages as Markdown (tables, code blocks, links, lists).
- Sends single-line and multi-line messages from a native input bar.
- Shows the optional session-lock plugin's leases and lets you release them; a live lease needs a confirmation.
- Supports an accent colour and an English/Chinese UI, and tries to keep the connection alive for a short time when the screen locks.

## Requirements

- Android 8.0 or newer.
- A reachable Hermes gateway. The app uses the REST API and the `/api/pty` WebSocket; it does not bundle a gateway or a server address.
- The `session-lock` plugin is optional. Without it the lock screens stay hidden.

## Build

You need JDK 17 and Android SDK platform 35 with build-tools 35.0.0. Create `local.properties` with your SDK path, then run:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## What I tested

The JVM unit tests cover URL handling, the login JSON, message filtering and paging, and the session-lock payloads. I have used the app with my own gateway and phone; I have not tested every screen on every ROM or Hermes version. The lock screen, language switch and lock-name display are the parts most likely to need fixes on another device.

## Limits

- Files, models and cron pages are not included; this is a chat client with a small settings screen.
- The lock-screen keep-alive is a short foreground service, not a permanent background connection.
- The app expects the Hermes API shape it was built against; a different gateway version may need small changes.
- No APK or signing key is committed here.

## License

MIT. Hermes is a Nous Research project; this client is not affiliated with it.
