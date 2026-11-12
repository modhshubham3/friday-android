# Friday (Android voice assistant)

Personal voice assistant in Kotlin. Free-tier LLMs with automatic failover.

## Status

- [x] Typed chat and push-to-talk with Gemini model chain, Groq fallback
- [x] Chat bubbles, 8 colour themes, 4 text sizes, conversation history
- [x] In-app updates
- [x] Five looks (Standard, Animated, Minimal, iOS Dark, iOS Light), icon buttons with motion
- [x] Chats saved on the phone, with a list of old chats
- [x] Wake word service (needs a Picovoice AccessKey; see Settings > Hey Friday setup)
- [x] Tool calling: set an alarm, call a contact (opens the dialer), flashlight
- [ ] Week 2: tool calling (alarm, call, flashlight, music)
- [ ] Week 3: "Friday" wake word + foreground service
- [ ] Week 4: lock screen + battery-optimization handling

## Install and updates

1. First install: open https://friday-android.vercel.app on the phone and download the APK.
2. Open the app, tap Settings, and paste the Gemini API key. Keys stay on the phone only.
3. After that the app updates itself: on launch it reads `version.json` from the site and
   offers any newer build.

Every push to `main` builds the APK in GitHub Actions and commits it to `site/`, which Vercel serves.

## Layout

- `MainActivity.kt` - UI, SpeechRecognizer, TextToSpeech
- `Settings.kt` - API keys, model names, theme and text size saved on the phone
- `Look.kt` - colour themes, looks and text sizes, combined into one Style
- `llm/ModelHealth.kt` - remembers which models just failed so they are skipped for a while
- `voice/` - "Hey Friday": `FridayService` (foreground service), `WakeWord` (Porcupine), `Listener`, `Speaker`, `VoicePrompts`, `VoiceLog`
- `Assistant.kt` - which models to ask and the system prompt, shared by the screen and the voice service
- `tools/PhoneTools.kt` - what Friday can do on the phone: alarm, call, flashlight
- `ChatStore.kt` - saved chats, one JSON file each
- `Updater.kt` - checks the site for a newer build, downloads and verifies it
- `llm/LlmProvider.kt` - interface every model backend implements
- `llm/LlmRouter.kt` - tries providers in order, falls over on failure
- `llm/GeminiProvider.kt`, `llm/GroqProvider.kt` - the two free backends
