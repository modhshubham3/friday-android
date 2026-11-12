# Jarvis (Android voice assistant)

Personal voice assistant in Kotlin. Free-tier LLMs with automatic failover.

## Status

- [x] Week 1: push-to-talk -> speech-to-text -> LLM (Gemini, Groq fallback) -> text-to-speech
- [ ] Week 2: tool calling (alarm, call, flashlight, music)
- [ ] Week 3: "Jarvis" wake word + foreground service
- [ ] Week 4: lock screen + battery-optimization handling

## Setup (no Android Studio needed)

1. Push to `main`. GitHub Actions builds the APK (about 3-5 minutes).
2. On the phone, open the repo's Releases page and download `jarvis.apk` from "Latest build".
3. Install it (allow "install unknown apps" for the browser when asked).
4. Open the app, tap Settings, and paste the API key. Keys stay on the phone only.

## Layout

- `MainActivity.kt` - UI, SpeechRecognizer, TextToSpeech
- `Settings.kt` - API keys and model names saved on the phone
- `llm/LlmProvider.kt` - interface every model backend implements
- `llm/LlmRouter.kt` - tries providers in order, falls over on failure
- `llm/GeminiProvider.kt`, `llm/GroqProvider.kt` - the two free backends
