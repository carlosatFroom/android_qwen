## Project: Android Qwen Chat

### Build Environment
- **JAVA_HOME**: `/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home` (Java 17 required; system default Java 25 is incompatible with Gradle)
- **Android SDK**: `~/Library/Android/sdk` (set in `local.properties`)
- **Android API**: compileSdk/targetSdk = 36, minSdk = 33
- **NDK**: 29.0.13113456 (r29-beta1)
- **CMake**: 3.31.6
- **Gradle**: 8.14.3, AGP 8.13.2, Kotlin 2.3.0
- **Build command**: `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew assembleDebug`
- **ABI filter**: arm64-v8a only

### Architecture
- Two modules: `app` (Compose UI, package `com.example.qwenchat`) and `lib` (JNI/C++ inference, package `com.arm.aichat`)
- llama.cpp is a git submodule at `./llama.cpp/`
- CMake `LLAMA_SRC` path: `${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp/`
- JNI methods added to lib must be prefixed `native` in Kotlin (e.g., `nativeSetTemperature`) to avoid clashing with public interface overrides
- XML theme uses `android:Theme.Material.Light.NoActionBar` (Compose handles its own Material3 theming)

### Vision / Multimodal
- Requires an **mmproj GGUF file** matching the exact text model variant (e.g., Qwen3.5-**2B** mmproj for the 2B text model — using the 4B mmproj will fail with an n_embd mismatch)
- Images are downscaled to max 1024px and re-compressed as JPEG 85% before sending to the model (reduces token count and processing time)
- EXIF orientation is applied during downscaling and in the UI preview to prevent rotation artifacts
- Vision inference runs **CPU-only** (`use_gpu = false`); the Vulkan backend compiles but crashes the Adreno driver during `vkCreateComputePipelines` on the Samsung S24 Ultra (Snapdragon 8 Gen 3). Vulkan config is commented out in `lib/build.gradle.kts` — re-enable when Samsung ships a driver fix
- `mtmd_init_from_file` diagnostic logs go to stderr, not logcat; the `nativeLoadMmproj` JNI function redirects stderr during init to capture clip/mtmd error output

# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
