# Friday (Android voice assistant)

Personal voice assistant in Kotlin. Free-tier LLMs with automatic failover.

## Status

- [x] Week 1: typed chat and push-to-talk -> speech-to-text -> LLM (Gemini, Groq fallback) -> text-to-speech
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
- `Settings.kt` - API keys and model names saved on the phone
- `Updater.kt` - checks the site for a newer build, downloads and verifies it
- `llm/LlmProvider.kt` - interface every model backend implements
- `llm/LlmRouter.kt` - tries providers in order, falls over on failure
- `llm/GeminiProvider.kt`, `llm/GroqProvider.kt` - the two free backends
