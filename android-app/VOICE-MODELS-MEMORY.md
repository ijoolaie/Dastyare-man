# Voice models, persistent identity, and history

## Speech recognition choices

The app offers three locally executed Persian speech-recognition choices:

1. **Vosk Persian** — existing offline Vosk Android engine and `vosk-model-small-fa-0.42` model.
2. **Whisper Tiny multilingual** — sherpa-onnx runtime with the quantized multilingual Whisper Tiny encoder, decoder, and token table. Persian transcription uses language code `fa`.
3. **Shenava Rizeh Persian** — sherpa-onnx runtime with the Persian-first Shenava Rizeh CTC model and its matching token table. The model card notes that its feature-normalization metadata must be preserved.

The selected model ID is stored in Android SharedPreferences. Model files are downloaded on first use into the app-private `files/asr-models` directory; they are not bundled into the APK. After download, recognition runs locally without network access. Switching models does not remove previously downloaded models.

The sherpa-onnx Android dependency is pinned to v1.13.5. The runtime is Apache-2.0. Whisper weights are served by the sherpa-onnx model repository; Shenava Rizeh is published under Apache-2.0. Keep each upstream model's license and attribution with any redistribution.

## Memory and history

`RayaMemoryStore` stores the assistant identity/profile and up to 300 voice-history turns in the app-private SQLite database `raya_memory.db`.

The initial identity profile is:

- Assistant name: Raya (رایا)
- App name: Dastyare-man (دستیار من)
- Creator: GitHub project owner handle `ijoolaie` (editable in Settings)
- Purpose: Persian personal Android assistant for voice interaction and user-authorized device actions
- Behavior principles: honesty, privacy, and no false claims of completed actions

The user can edit the identity fields and inspect or clear the displayed local voice history from Settings. The server conversation ID is also persisted locally so a voice conversation can continue after the service restarts.

For voice requests, the app sends the recognized text plus the assistant identity context to the configured `/api/chat` server. Audio recognition itself remains on-device. Voice history and identity profile are stored locally; the app does not upload the local SQLite database.

## Device limitations

Whisper Tiny and Shenava Rizeh use offline, utterance-based recognition. The microphone stream is segmented using a simple energy/silence detector before inference. Real-time factor, wake-word accuracy, memory use, and thermal behavior must be measured on the target Galaxy S8 before claiming production readiness. Android Text-to-Speech Persian voice availability is separate from speech recognition and depends on installed TTS voices.
