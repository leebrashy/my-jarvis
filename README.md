# JARVIS Android

Native Android starter app with a JARVIS-inspired interface, tap-to-speak input, English (UK) text-to-speech, and AI chat.

## GitHub cloud build
Upload the **contents of the JarvisAndroid folder** into the root of your GitHub repository, including `.github/workflows/android.yml`. Do not upload the ZIP as a single file.

Then open the repository's Actions tab and run **Build JARVIS Android APK**. When complete, download the `JARVIS-Android-APK` artifact and extract `app-debug.apk`.

## AI setup
On first launch, open Settings and enter an OpenAI API key. API usage is billed separately; ChatGPT subscriptions do not include API credit. Keep the key private. This prototype stores the key encrypted on-device, but a public app should use a secure backend.

## Limitations
- Voice input starts only when SPEAK is tapped; no always-on wake word.
- No smart-home or general phone-control features yet.
- Conversation history lasts for the current app session.
- Speech features depend on Android speech services and installed UK voices.
- This is a debug APK, not a Play Store release.
