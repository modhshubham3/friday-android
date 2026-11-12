# Jarvis (Android voice assistant)

Personal voice assistant in Kotlin. Free-tier LLMs with automatic failover.

## Status

- [x] Week 1: push-to-talk -> speech-to-text -> LLM (Gemini, Groq fallback) -> text-to-speech
- [ ] Week 2: tool calling (alarm, call, flashlight, music)
- [ ] Week 3: "Jarvis" wake word + foreground service
- [ ] Week 4: lock screen + battery-optimization handling

## Setup

1. Install Android Studio and open this folder.
2. Copy `local.properties.example` to `local.properties` and add your keys
   (Android Studio adds the `sdk.dir` line to the same file).
3. Connect the phone with USB debugging on and press Run.

`local.properties` is git-ignored. Keys end up inside the APK, so do not share the APK.

## Layout

- `MainActivity.kt` - UI, SpeechRecognizer, TextToSpeech
- `llm/LlmProvider.kt` - interface every model backend implements
- `llm/LlmRouter.kt` - tries providers in order, falls over on failure
- `llm/GeminiProvider.kt`, `llm/GroqProvider.kt` - the two free backends
