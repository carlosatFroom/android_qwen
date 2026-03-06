# Plan0: Android App for Running Qwen3.5-2B Locally

## Goal

Build a native Android app that runs Qwen3.5-2B locally on a phone with 12GB of RAM, using llama.cpp for inference and Jetpack Compose for the UI.

---

## Model

- **Model**: Qwen3.5-2B
- **Source**: https://huggingface.co/Qwen/Qwen3.5-2B
- **Quantized GGUF (recommended)**: Unsloth provides a Q8_0 quantization at only ~2GB:
  https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q8_0.gguf?download=true
  - Q8_0 at 2GB is an excellent deal — near-full precision quality at a very manageable size for 12GB RAM.
- **Format**: GGUF (required by llama.cpp)

## Reasoning Mode

Qwen3.5 0.8B, 2B, 4B, and 9B have reasoning **disabled by default**. It can be enabled using a llama.cpp flag:

```
--chat-template-kwargs '{"enable_thinking":true}'
```

This means the app should expose a toggle for reasoning mode, passing the appropriate template kwargs to the llama.cpp backend when enabled.

Source: https://unsloth.ai/docs/models/qwen3.5

---

## Architecture

- **Inference engine**: llama.cpp (C++ via JNI)
- **UI**: Kotlin + Jetpack Compose
- **Target ABI**: arm64-v8a (all modern phones with 12GB RAM are 64-bit ARM)
- **Build tools**: Android Studio + NDK + CMake

### Why llama.cpp (not Ollama)

Ollama is a server process designed for desktop/server use. llama.cpp provides:
- A C/C++ library that compiles directly for Android via NDK
- An official Android example (`examples/llama.android/`) to build from
- JNI bindings for direct Kotlin integration
- No background server process needed

---

## Key Components

### 1. Model Management
- Model file (~2GB) is too large to bundle in the APK
- Download on first launch, or let the user pick/sideload a GGUF file
- Store in `getExternalFilesDir()` on the device
- Show download progress in the UI

### 2. Inference Backend (llama.cpp via JNI)
- Use the official `llama.android` example from the llama.cpp repo as a starting point
- It includes: CMake build for Android NDK, JNI bindings, basic Compose UI
- Extend the JNI bridge to support:
  - Streaming token output (token-by-token callbacks)
  - Chat template kwargs (for reasoning mode toggle)
  - Configurable parameters (temperature, top_p, context length, etc.)

### 3. Android UI (Jetpack Compose)
- **Chat screen**: Message bubbles, streaming text output, send button
- **Settings screen**: Temperature, system prompt, context length, reasoning mode toggle
- **Model screen**: Download/select GGUF model file
- **Reasoning toggle**: When enabled, passes `{"enable_thinking":true}` to the chat template

---

## Project Structure

```
app/
├── src/main/
│   ├── java/com/example/qwenchat/
│   │   ├── MainActivity.kt
│   │   ├── ui/
│   │   │   ├── ChatScreen.kt
│   │   │   ├── SettingsScreen.kt
│   │   │   └── ModelScreen.kt
│   │   ├── llama/
│   │   │   └── LlamaModel.kt          # Kotlin wrapper around JNI
│   │   └── data/
│   │       └── ModelManager.kt         # Download/locate GGUF file
│   └── cpp/
│       ├── CMakeLists.txt              # Builds llama.cpp + JNI bridge
│       └── llama_jni.cpp               # JNI bridge code
├── libs/
│   └── llama.cpp/                      # Git submodule
└── build.gradle.kts
```

---

## Memory Budget (12GB device)

| Component            | Estimate     |
|----------------------|-------------|
| Model weights (Q8_0) | ~2 GB       |
| KV cache (2048 ctx)  | ~0.5-1 GB   |
| Android OS + system  | ~3-4 GB     |
| App + UI overhead    | ~0.2 GB     |
| **Total**            | **~6-7 GB** |

Plenty of headroom. Could even increase context length significantly.

## Expected Performance

- Modern ARM chips (Snapdragon 8 Gen 2+): ~15-30 tokens/sec for 2B model
- Older chips: slower but still usable (~5-15 tok/s)
- Q8_0 will be slightly slower than Q4 due to larger model, but quality is noticeably better

---

## Quickest Path to Prototype

1. Clone llama.cpp repo
2. Open `examples/llama.android/` in Android Studio
3. Download the Unsloth Q8_0 GGUF to the phone
4. Build and run — working chat in ~1 hour
5. Add reasoning mode toggle
6. Iterate on UI (chat bubbles, settings, model management)

---

## Open Questions

- [ ] Confirm llama.cpp `main` branch fully supports Qwen3.5 architecture
- [ ] Decide on model delivery: download in-app vs. user sideloads the file
- [ ] Determine minimum Android API level to target
- [ ] Evaluate whether to support multiple model files (let user swap models)
