#pragma once
#include "llama.h"
#include "chat.h"
#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace meshmind {
struct Settings {
    int max_tokens = 384;
    float temperature = 0.1f;
    float top_p = 1.0f;
    int top_k = 50;
    uint32_t seed = 42;
    bool thinking = false;
    bool refusal_reduction = false;
};
struct Stats {
    double load_ms = 0, ttft_ms = 0, elapsed_ms = 0;
    int generated = 0, prompt_tokens = 0;
    long peak_rss_kib = 0;
    bool cancelled = false;
    std::string finish_reason;
    std::string json() const;
};
class Runtime {
public:
    Runtime();
    ~Runtime();
    void load(const std::string & path, int context, int threads, bool efficient,
              const std::string & expected_format = "", const std::vector<std::string> & special_tokens = {});
    Stats generate(const std::vector<common_chat_msg> & messages, const Settings & settings,
                   const std::function<bool(const std::string &)> & emit);
    void cancel() { cancelled.store(true); }
    void load_adapter(const std::string & path);
    void prepare() { cancelled.store(false); }
    void unload();
    void reset_context();
    std::string information() const;
    std::string format_prompt(const std::vector<common_chat_msg> & messages, bool thinking) const;
private:
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_adapter_lora * adapter = nullptr;
    common_chat_templates_ptr templates;
    std::atomic<bool> cancelled{false};
    double load_ms = 0;
};
}
