#include "runtime.h"
#include "gguf.h"
#include "sampling.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <sys/resource.h>

namespace meshmind {
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point a) { return std::chrono::duration<double, std::milli>(Clock::now() - a).count(); }
static std::string meta(llama_model * model, const char * key) {
    int n = llama_model_meta_val_str(model, key, nullptr, 0);
    if (n < 0) return {};
    std::vector<char> value(n + 1);
    llama_model_meta_val_str(model, key, value.data(), value.size());
    return value.data();
}
Runtime::Runtime() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}
Runtime::~Runtime() { unload(); }
void Runtime::reset_context() { if (ctx) llama_memory_clear(llama_get_memory(ctx), true); }
void Runtime::unload() {
    templates.reset();
    if (ctx) llama_free(ctx);
    ctx = nullptr;
    if (adapter) llama_adapter_lora_free(adapter);
    adapter = nullptr;
    if (model) llama_model_free(model);
    model = nullptr;
}
void Runtime::load(const std::string & path, int context, int threads, bool efficient,
                   const std::string & expected_format, const std::vector<std::string> & special_tokens) {
    if (context < 512 || context > 4096 || threads < 1 || threads > 8)
        throw std::invalid_argument("Use context 512-4096 and threads 1-8");
    unload();
    const auto start = Clock::now();
    try {
        std::unique_ptr<gguf_context, decltype(&gguf_free)> header(
            gguf_init_from_file(path.c_str(), {true, nullptr}), gguf_free);
        if (!header) throw std::runtime_error("Invalid GGUF header");
        const auto architecture = gguf_find_key(header.get(), "general.architecture");
        if (architecture < 0 || gguf_get_kv_type(header.get(), architecture) != GGUF_TYPE_STRING ||
            std::string(gguf_get_val_str(header.get(), architecture)) != "lfm3")
            throw std::runtime_error("Unsupported GGUF architecture; this backend supports lfm3 only");
        if (!expected_format.empty() && expected_format != "AD-Q4_K_M" && expected_format != "AD-Q6_K")
            throw std::runtime_error("Unsupported LFM3 quantization manifest");
        bool q4 = false, q6 = false;
        for (int64_t i = 0; i < gguf_get_n_tensors(header.get()); ++i) {
            auto type = gguf_get_tensor_type(header.get(), i);
            if (type == GGML_TYPE_Q4_K) q4 = true;
            if (type == GGML_TYPE_Q6_K) q6 = true;
            if (type != GGML_TYPE_F32 && type != GGML_TYPE_F16 && type != GGML_TYPE_BF16 &&
                type != GGML_TYPE_Q4_K && type != GGML_TYPE_Q5_K && type != GGML_TYPE_Q6_K &&
                type != GGML_TYPE_Q8_0 && type != GGML_TYPE_Q4_0 && type != GGML_TYPE_Q5_0 &&
                type != GGML_TYPE_IQ4_XS)
                throw std::runtime_error("Unsupported LFM3 tensor type");
        }
        if ((!q4 && !q6) || (expected_format == "AD-Q4_K_M" && !q4) ||
            (expected_format == "AD-Q6_K" && !q6))
            throw std::runtime_error("Quantized tensor types do not match the selected manifest");
        header.reset();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.check_tensors = true;
        mp.progress_callback = [](float, void * data) { return !static_cast<Runtime *>(data)->cancelled.load(); };
        mp.progress_callback_user_data = this;
        model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) throw std::runtime_error("Model load failed: invalid GGUF, unsupported operators, cancellation, or insufficient RAM");
        if (meta(model, "general.architecture") != "lfm3")
            throw std::runtime_error("This backend is validated only for lfm3; install a compatible backend for this architecture");
        if (!llama_model_chat_template(model, nullptr))
            throw std::runtime_error("GGUF has no embedded chat template");
        for (const auto & token : special_tokens) {
            llama_token ids[8];
            int count = llama_tokenize(llama_model_get_vocab(model), token.data(), token.size(), ids, 8, false, true);
            if (count != 1) throw std::runtime_error("Required special token is incompatible: " + token);
        }
        templates = common_chat_templates_init(model, "", "");
        auto cp = llama_context_default_params();
        cp.n_ctx = context;
        cp.n_batch = efficient ? 64 : 128;
        cp.n_ubatch = cp.n_batch;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.no_perf = false;
        cp.abort_callback = [](void * data) { return static_cast<Runtime *>(data)->cancelled.load(); };
        cp.abort_callback_data = this;
        ctx = llama_init_from_model(model, cp);
        if (!ctx) throw std::runtime_error("Context allocation failed; reduce context window or enable memory-efficient mode");
        load_ms = ms(start);
    } catch (...) { unload(); throw; }
}
std::string Runtime::information() const {
    if (!model) return "No model loaded";
    return "lfm3 | CPU | llama.cpp 19cce691 | " + meta(model, "general.name") +
           " | context " + std::to_string(llama_n_ctx(ctx));
}
void Runtime::load_adapter(const std::string & path) {
    if (!ctx || !model) throw std::runtime_error("Load the base model before its adapter");
    if (adapter) throw std::runtime_error("An adapter is already loaded");
    adapter = llama_adapter_lora_init(model, path.c_str());
    if (!adapter) throw std::runtime_error("Refusal adapter load failed; no generation was started");
}
Stats Runtime::generate(const std::vector<common_chat_msg> & messages, const Settings & s,
                        const std::function<bool(const std::string &)> & emit) {
    if (!ctx) throw std::runtime_error("Load a model first");
    if (messages.empty() || messages.size() > 200 || s.max_tokens < 1 || s.max_tokens > 1024 ||
        !std::isfinite(s.temperature) || s.temperature < 0 || s.temperature > 2 ||
        !std::isfinite(s.top_p) || s.top_p <= 0 || s.top_p > 1 || s.top_k < 1 || s.top_k > 200)
        throw std::invalid_argument("Invalid generation settings or message count");
    if (s.refusal_reduction && !adapter) throw std::runtime_error("Refusal reduction requires the verified adapter");
    float scale = 1.0f;
    if (llama_set_adapters_lora(ctx, s.refusal_reduction ? &adapter : nullptr,
                               s.refusal_reduction ? 1 : 0, s.refusal_reduction ? &scale : nullptr) != 0)
        throw std::runtime_error("Could not apply refusal adapter settings");
    reset_context();
    auto prompt = format_prompt(messages, s.thinking);
    if (prompt.size() > 1024 * 1024) throw std::runtime_error("Conversation is too large; reset it");
    auto vocab = llama_model_get_vocab(model);
    int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (count <= 0 || count + s.max_tokens > static_cast<int>(llama_n_ctx(ctx)))
        throw std::runtime_error("Conversation exceeds context window; reset conversation or reduce maximum output tokens");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
        throw std::runtime_error("Tokenizer failed");
    common_params_sampling sp;
    sp.temp = s.temperature;
    sp.top_p = s.top_p;
    sp.top_k = s.top_k;
    sp.min_p = 0;
    sp.seed = s.seed;
    sp.penalty_present = 0.0f;
    sp.penalty_repeat = 1.1f;
    std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(common_sampler_init(model, sp), common_sampler_free);
    for (auto token : tokens) common_sampler_accept(sampler.get(), token, false);
    Stats stats;
    stats.load_ms = load_ms;
    stats.prompt_tokens = count;
    const auto start = Clock::now();
    auto decode = [&](llama_token * data, int n) {
        int rc = llama_decode(ctx, llama_batch_get_one(data, n));
        if (rc && !cancelled.load()) throw std::runtime_error("Decode failed (code " + std::to_string(rc) + "); reduce context or memory use");
    };
    const int batch = llama_n_batch(ctx);
    for (int offset = 0; offset < count && !cancelled.load(); offset += batch)
        decode(tokens.data() + offset, std::min(batch, count - offset));
    stats.finish_reason = "length";
    for (int i = 0; i < s.max_tokens && !cancelled.load(); ++i) {
        llama_token token = common_sampler_sample(sampler.get(), ctx, -1);
        common_sampler_accept(sampler.get(), token, true);
        if (llama_vocab_is_eog(vocab, token)) { stats.finish_reason = "eos"; break; }
        std::vector<char> piece(256);
        int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (n < 0) throw std::runtime_error("Token conversion failed");
        if (stats.generated == 0) stats.ttft_ms = ms(start);
        ++stats.generated;
        if (!emit(std::string(piece.data(), n))) { cancelled.store(true); break; }
        if (i + 1 < s.max_tokens) decode(&token, 1);
    }
    stats.elapsed_ms = ms(start);
    stats.cancelled = cancelled.load();
    if (stats.cancelled) stats.finish_reason = "cancelled";
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    stats.peak_rss_kib = usage.ru_maxrss;
    return stats;
}
std::string Stats::json() const {
    std::ostringstream out;
    double decode_ms = elapsed_ms - ttft_ms;
    out << "{\"load_ms\":" << load_ms << ",\"ttft_ms\":" << ttft_ms << ",\"elapsed_ms\":" << elapsed_ms
        << ",\"generated_tokens\":" << generated << ",\"prompt_tokens\":" << prompt_tokens
        << ",\"tokens_per_second\":" << (decode_ms > 0 && generated > 1 ? (generated - 1) * 1000.0 / decode_ms : 0)
        << ",\"process_peak_rss_kib\":" << peak_rss_kib << ",\"cancelled\":" << (cancelled ? "true" : "false")
        << ",\"finish_reason\":\"" << finish_reason << "\"}";
    return out.str();
}
std::string Runtime::format_prompt(const std::vector<common_chat_msg> & messages, bool thinking) const {
    if (!templates) throw std::runtime_error("Load a model before formatting chat");
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.enable_thinking = thinking;
    inputs.chat_template_kwargs["enable_thinking"] = thinking ? "true" : "false";
    return common_chat_templates_apply(templates.get(), inputs).prompt.str();
}
}
