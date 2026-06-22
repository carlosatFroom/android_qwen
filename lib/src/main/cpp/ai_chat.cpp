#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <climits>
#include <string>
#include <vector>
#include <unistd.h>
#include <sched.h>
#include <dirent.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "ggml.h"
#include "mtmd.h"
#include "mtmd-helper.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;
constexpr int   N_BIG_THREADS_MAX       = 6;   // cap worker threads to the big-core cluster

constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;

static int                                g_context_size      = 8192;
static float                              g_sampler_temp       = 0.3f;
static bool                               g_enable_thinking    = false;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static mtmd_context                     * g_mtmd_ctx = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

// Read a core's advertised max frequency (kHz); -1 if unreadable.
static long read_cpu_max_freq(int cpu) {
    char path[128];
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    long khz = -1;
    if (fscanf(f, "%ld", &khz) != 1) khz = -1;
    fclose(f);
    return khz;
}

// On big.LITTLE phones the OS parks the big cores and migrates inference onto the little
// cores when the screen is off, throttling generation 5-20x. We pin the worker threads to
// the big cluster (every core faster than the slowest cluster) so tokens keep flowing at
// full speed. Falls back to an unpinned thread count when the topology can't be read.
//
// The CPU backends are loaded at runtime (multiple arch variants), so ggml's threadpool
// affinity API isn't linkable here. Instead we apply the affinity ourselves with
// sched_setaffinity over every thread in /proc/self/task — which covers the OpenMP workers.
struct ThreadPlan {
    int  n_threads = 1;
    bool cpumask[GGML_MAX_N_THREADS] = {};
    bool has_mask = false;
};

static ThreadPlan g_thread_plan;

static ThreadPlan plan_big_core_threads() {
    ThreadPlan plan;
    const int fallback = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                  (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));

    const long n_conf = sysconf(_SC_NPROCESSORS_CONF); // includes currently-offline big cores
    if (n_conf <= 0 || n_conf > GGML_MAX_N_THREADS) {
        plan.n_threads = fallback;
        return plan;
    }

    long max_freq = 0, min_freq = LONG_MAX;
    std::vector<long> freq((size_t) n_conf, -1);
    bool any = false;
    for (int i = 0; i < n_conf; i++) {
        freq[i] = read_cpu_max_freq(i);
        if (freq[i] > 0) {
            any = true;
            max_freq = std::max(max_freq, freq[i]);
            min_freq = std::min(min_freq, freq[i]);
        }
    }

    if (!any || max_freq == min_freq) {
        // Uniform SoC or sysfs unreadable: don't pin, just use a sane thread count.
        plan.n_threads = fallback;
        return plan;
    }

    int big = 0;
    for (int i = 0; i < n_conf; i++) {
        if (freq[i] > min_freq) { plan.cpumask[i] = true; big++; }
    }

    if (big < N_THREADS_MIN) {
        // Too few big cores to bother pinning; let the scheduler decide.
        for (bool &b : plan.cpumask) b = false;
        plan.n_threads = fallback;
        return plan;
    }

    plan.has_mask  = true;
    plan.n_threads = std::min(N_BIG_THREADS_MAX, big);
    return plan;
}

// Pin every thread of this process to the big-core cluster. Re-applied at the start of each
// generation so OpenMP workers (created lazily on the first decode) and any threads the OS
// tried to migrate to the little cores get herded back onto the big cores.
static void pin_threads_to_big_cores() {
    if (!g_thread_plan.has_mask) return;

    cpu_set_t set;
    CPU_ZERO(&set);
    int n = 0;
    for (int i = 0; i < GGML_MAX_N_THREADS && i < CPU_SETSIZE; i++) {
        if (g_thread_plan.cpumask[i]) { CPU_SET(i, &set); n++; }
    }
    if (n == 0) return;

    DIR *d = opendir("/proc/self/task");
    if (!d) {
        sched_setaffinity(0, sizeof(set), &set); // at least pin the caller
        return;
    }
    int pinned = 0;
    for (struct dirent *e = readdir(d); e != nullptr; e = readdir(d)) {
        if (e->d_name[0] == '.') continue;
        const int tid = atoi(e->d_name);
        if (tid > 0 && sched_setaffinity(tid, sizeof(set), &set) == 0) pinned++;
    }
    closedir(d);
    LOGi("%s: pinned %d threads to %d big cores", __func__, pinned, n);
}

static llama_context *init_context(llama_model *model, const int n_ctx) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup: size the pool to the big-core cluster and remember the mask so
    // generation can keep the workers pinned there even when the screen-off governor parks
    // the big cores.
    g_thread_plan = plan_big_core_threads();
    const int n_threads = g_thread_plan.n_threads;
    LOGi("%s: Using %d threads (big-core pinning: %s)", __func__, n_threads,
         g_thread_plan.has_mask ? "on" : "off");

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model, g_context_size);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(g_sampler_temp);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeSetEnableThinking(JNIEnv *, jobject, jboolean enable) {
    g_enable_thinking = enable;
    LOGi("Thinking mode %s", g_enable_thinking ? "enabled" : "disabled");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeSetTemperature(JNIEnv *, jobject, jfloat temp) {
    g_sampler_temp = temp;
    // Recreate sampler if model is loaded
    if (g_model) {
        if (g_sampler) common_sampler_free(g_sampler);
        g_sampler = new_sampler(g_sampler_temp);
    }
    LOGi("Temperature set to %.2f", g_sampler_temp);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeSetContextSize(JNIEnv *, jobject, jint size) {
    g_context_size = size;
    LOGi("Context size set to %d", g_context_size);
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " +/- " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " +/- " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

// Token accounting for the most recent user request (ollama-style prompt_eval_count / eval_count).
// prompt_eval is snapshotted when the prompt finishes decoding; eval_count is incremented per
// generated token, so both stay correct even if context-shifting moves current_position mid-run.
static int g_last_prompt_eval = 0;
static int g_last_eval_count = 0;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeGetContextPosition(JNIEnv *, jobject) {
    return current_position;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeGetContextSize(JNIEnv *, jobject) {
    return g_context_size;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeGetPromptEvalCount(JNIEnv *, jobject) {
    return g_last_prompt_eval;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeGetEvalCount(JNIEnv *, jobject) {
    return g_last_eval_count;
}

/**
 * Context shifting by discarding the older half of the tokens appended after system prompt
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

// Apply the model's chat template, tolerant of templates that only work one way: some
// (e.g. Qwen) support the legacy non-jinja path but their jinja template crashes on
// system-only input; others (e.g. gemma4) have a "custom" template the legacy path rejects
// with "this custom template is not supported, try using --jinja". So try the requested mode
// and, if it throws, retry with the opposite mode. Returns the full params so callers can
// recover the generation format (used to parse the model's reply back into clean content).
static common_chat_params apply_template(common_chat_templates_inputs &inputs) {
    try {
        return common_chat_templates_apply(g_chat_templates.get(), inputs);
    } catch (const std::exception &e) {
        LOGw("%s: template apply failed (use_jinja=%d): %s — retrying with use_jinja=%d",
             __func__, inputs.use_jinja, e.what(), !inputs.use_jinja);
        inputs.use_jinja = !inputs.use_jinja;
        return common_chat_templates_apply(g_chat_templates.get(), inputs);
    }
}

// The chat format captured from the most recent generation prompt, plus the parser params
// derived from it. The model's raw output (e.g. gpt-oss/harmony channel markers, <think>
// blocks) is parsed back through these so only clean content reaches the caller. Reasoning
// is kept inline (wrapped) when thinking is on, and dropped when it's off.
static common_chat_params        g_chat_params;
static common_chat_parser_params g_parser_params;

static void rebuild_parser_params() {
    g_parser_params = common_chat_parser_params(g_chat_params); // copies format + generation_prompt
    if (!g_chat_params.parser.empty()) {
        g_parser_params.parser.load(g_chat_params.parser);
    }
    // AUTO extracts reasoning into reasoning_content (which we drop). DEEPSEEK_LEGACY +
    // reasoning_in_content keeps it inline (in <think> tags) for display when thinking is on.
    g_parser_params.reasoning_format = g_enable_thinking
        ? COMMON_REASONING_FORMAT_DEEPSEEK_LEGACY
        : COMMON_REASONING_FORMAT_AUTO;
    g_parser_params.reasoning_in_content = g_enable_thinking;
    g_parser_params.parse_tool_calls = false;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;

    // Only use jinja for non-system messages; Qwen's jinja template
    // requires at least one user message, so it crashes on system-only input.
    const bool use_jinja = g_enable_thinking && (role != ROLE_SYSTEM);
    const bool add_ass = (role == ROLE_USER);

    // Replicate common_chat_format_single logic with enable_thinking support
    common_chat_templates_inputs inputs;
    inputs.use_jinja = use_jinja;
    inputs.enable_thinking = use_jinja && g_enable_thinking;

    std::string fmt_past_msg;
    if (!chat_msgs.empty()) {
        // For fmt_past_msg, check if we can use jinja: Qwen's template requires
        // at least one user message, so fall back to non-jinja when only system msgs exist.
        bool past_use_jinja = use_jinja;
        if (past_use_jinja) {
            bool has_user = false;
            for (const auto& m : chat_msgs) {
                if (m.role != ROLE_SYSTEM) { has_user = true; break; }
            }
            if (!has_user) past_use_jinja = false;
        }

        common_chat_templates_inputs past_inputs;
        past_inputs.use_jinja = past_use_jinja;
        past_inputs.enable_thinking = g_enable_thinking;
        past_inputs.messages = chat_msgs;
        past_inputs.add_generation_prompt = false;
        fmt_past_msg = apply_template(past_inputs).prompt;
    }

    std::ostringstream ss;
    if (add_ass && !fmt_past_msg.empty() && fmt_past_msg.back() == '\n') {
        ss << "\n";
    }

    inputs.messages = chat_msgs;
    inputs.messages.push_back(new_msg);
    inputs.add_generation_prompt = add_ass;
    auto new_params = apply_template(inputs);
    const std::string &fmt_new_msg = new_params.prompt;
    ss << fmt_new_msg.substr(fmt_past_msg.size(), fmt_new_msg.size() - fmt_past_msg.size());

    // Capture the format of the prompt the model will actually generate against, so the
    // reply can be parsed back into clean content (stripping channel/think control tokens).
    if (add_ass) {
        g_chat_params = new_params;
        rebuild_parser_params();
    }

    auto formatted = ss.str();
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::string g_gen_text;            // raw model output, fed to the chat parser
static common_chat_msg g_stream_msg;      // last parsed message, for computing content deltas
static bool g_pending_stop = false;       // emit a final flushed delta, then stop next call

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    g_gen_text.clear();
    g_stream_msg = common_chat_msg{};
    g_pending_stop = false;
    g_last_eval_count = 0;
}

// Content-only models (e.g. Qwen with no special channels) emit plain text, so we stream the
// raw pieces verbatim — byte-identical to the pre-parser behaviour and zero parser overhead.
// Anything else (gpt-oss/harmony channels, <think> blocks) must be parsed to strip markers.
static bool format_needs_parsing() {
    return g_chat_params.format != COMMON_CHAT_FORMAT_CONTENT_ONLY;
}

// Parse the accumulated raw output and return the newly-revealed clean content. With
// reasoning_in_content set, reasoning is folded into content_delta; otherwise it lands in
// reasoning_content_delta, which we intentionally discard. Defensive against parser throws so
// a malformed partial can never crash the headless server mid-stream.
static std::string parse_content_delta(bool is_partial) {
    try {
        common_chat_msg msg = common_chat_parse(g_gen_text, is_partial, g_parser_params);
        if (msg.empty()) return std::string();
        std::string delta;
        for (const auto &d : common_chat_msg_diff::compute_diffs(g_stream_msg, msg)) {
            delta += d.content_delta;
        }
        g_stream_msg = msg;
        return delta;
    } catch (const std::exception &e) {
        LOGw("%s: chat parse failed: %s", __func__, e.what());
        return std::string();
    }
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        if (start_pos + i + cur_batch_size >= g_context_size - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == (int)tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = g_context_size - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Herd worker threads (incl. OpenMP) back onto the big cores before this turn's compute.
    pin_threads_to_big_cores();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = g_context_size - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    current_position += user_prompt_size;
    stop_generation_position = current_position + user_prompt_size + n_predict;
    g_last_prompt_eval = current_position; // full prompt (system + history + user) decoded so far

    // The jinja template with enable_thinking already includes <think>...</think>
    // in the generation prompt tokens, so no seeding is needed here.

    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // A final flushed delta was emitted on the previous call after EOG; now actually stop.
    if (g_pending_stop) {
        g_pending_stop = false;
        return nullptr;
    }

    // Infinite text generation via context shifting
    if (current_position >= g_context_size - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;
    g_last_eval_count++;

    // Stop if next token is EOG. Do a final (non-partial) parse to flush any content the
    // parser was holding back, store the clean reply in history, then emit the last delta and
    // stop on the following call.
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        if (format_needs_parsing()) {
            cached_token_chars += parse_content_delta(/*is_partial=*/false);
            chat_add_and_format(ROLE_ASSISTANT, g_stream_msg.content);
        } else {
            chat_add_and_format(ROLE_ASSISTANT, g_gen_text);
        }
        if (!cached_token_chars.empty() && is_valid_utf8(cached_token_chars.c_str())) {
            jstring result = env->NewStringUTF(cached_token_chars.c_str());
            cached_token_chars.clear();
            g_pending_stop = true; // emit this trailing content now, stop next call
            return result;
        }
        return nullptr;
    }

    // If not EOG, accumulate the raw piece, then derive the newly-revealed clean content
    // (parsed for channelled models, verbatim for content-only ones).
    std::string piece = common_token_to_piece(g_context, new_token_id);
    g_gen_text += piece;
    cached_token_chars += format_needs_parsing() ? parse_content_delta(/*is_partial=*/true) : piece;

    // Create and return a valid UTF-8 Java string, buffering incomplete multi-byte sequences.
    jstring result;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeLoadMmproj(JNIEnv *env, jobject, jstring jpath) {
    const auto *path = env->GetStringUTFChars(jpath, nullptr);
    LOGi("Loading mmproj from: %s", path);

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                  (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                  N_THREADS_HEADROOM));

    // Redirect stderr to logcat so clip/mtmd diagnostics are visible
    int stderr_pipe[2];
    int old_stderr = -1;
    if (pipe(stderr_pipe) == 0) {
        old_stderr = dup(STDERR_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);
        close(stderr_pipe[1]);
    }

    g_mtmd_ctx = mtmd_init_from_file(path, g_model, params);

    // Capture and log stderr output
    if (old_stderr >= 0) {
        fflush(stderr);
        dup2(old_stderr, STDERR_FILENO);
        close(old_stderr);
        char buf[4096];
        ssize_t n = read(stderr_pipe[0], buf, sizeof(buf) - 1);
        close(stderr_pipe[0]);
        if (n > 0) {
            buf[n] = '\0';
            LOGe("mmproj stderr: %s", buf);
        }
    }

    env->ReleaseStringUTFChars(jpath, path);

    if (!g_mtmd_ctx) {
        LOGe("Failed to initialize mtmd context");
        return 1;
    }

    LOGi("Vision support: %s", mtmd_support_vision(g_mtmd_ctx) ? "yes" : "no");
    return 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeIsVisionLoaded(JNIEnv *, jobject) {
    return g_mtmd_ctx != nullptr && mtmd_support_vision(g_mtmd_ctx);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPromptWithImage(
        JNIEnv *env, jobject, jstring jprompt, jbyteArray jimageData, jint n_predict) {
    reset_short_term_states();
    pin_threads_to_big_cores();

    const auto *user_prompt = env->GetStringUTFChars(jprompt, nullptr);

    // Build prompt with media marker before user text
    const char *marker = mtmd_default_marker();
    std::string prompt_with_image = std::string(marker) + "\n" + user_prompt;
    env->ReleaseStringUTFChars(jprompt, user_prompt);

    // Format through chat template
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    std::string formatted;
    if (has_chat_template) {
        formatted = chat_add_and_format(ROLE_USER, prompt_with_image);
    } else {
        formatted = prompt_with_image;
    }

    // Get image bytes from Java
    jsize image_len = env->GetArrayLength(jimageData);
    auto *image_bytes = env->GetByteArrayElements(jimageData, nullptr);

    // Newer mtmd returns a wrapper {bitmap, video_ctx} by value and takes a trailing
    // `placeholder` flag (false = actually decode the image, not just reserve a slot).
    auto bitmap_wrapper = mtmd_helper_bitmap_init_from_buf(
        g_mtmd_ctx,
        reinterpret_cast<const unsigned char *>(image_bytes),
        image_len,
        false
    );
    auto *bitmap = bitmap_wrapper.bitmap;
    env->ReleaseByteArrayElements(jimageData, image_bytes, JNI_ABORT);

    if (!bitmap) {
        LOGe("Failed to create bitmap from image data");
        return 3;
    }

    // Tokenize text + image
    mtmd_input_text input_text;
    input_text.text = formatted.c_str();
    input_text.add_special = has_chat_template;
    input_text.parse_special = has_chat_template;

    auto *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[] = { bitmap };

    int32_t tokenize_result = mtmd_tokenize(g_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);

    if (tokenize_result != 0) {
        LOGe("mtmd_tokenize failed: %d", tokenize_result);
        mtmd_input_chunks_free(chunks);
        return 4;
    }

    LOGi("Vision prompt: %zu chunks, %zu total tokens",
         mtmd_input_chunks_size(chunks), mtmd_helper_get_n_tokens(chunks));

    // Evaluate all chunks (text + image)
    llama_pos new_n_past = 0;
    int32_t eval_result = mtmd_helper_eval_chunks(
        g_mtmd_ctx, g_context, chunks,
        current_position, 0, BATCH_SIZE, true, &new_n_past
    );
    mtmd_input_chunks_free(chunks);

    if (eval_result != 0) {
        LOGe("mtmd_helper_eval_chunks failed: %d", eval_result);
        return 5;
    }

    current_position = new_n_past;
    stop_generation_position = current_position + n_predict;
    g_last_prompt_eval = current_position; // text + image tokens decoded

    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free vision context
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
