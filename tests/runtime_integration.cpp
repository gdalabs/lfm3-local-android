#include "runtime.h"
#include <iostream>
#include <stdexcept>

static void require(bool value, const char * message) {
    if (!value) throw std::runtime_error(message);
}
int main(int argc, char ** argv) {
    if (argc != 2 && argc != 3) return 2;
    try {
        meshmind::Runtime engine;
        engine.load(argv[1], 2048, 2, true, "AD-Q4_K_M", {"<|im_start|>", "<|im_end|>"});
        require(engine.information().find("lfm3") != std::string::npos, "Architecture mismatch");
        common_chat_msg message; message.role = "user";
        message.content = "日本語の挨拶を一言だけ答えてください。";
        const auto prompt = engine.format_prompt({message}, false);
        require(prompt.find("<think></think>") != std::string::npos, "Thinking-off template mismatch");
        meshmind::Settings settings; settings.temperature = 0; settings.max_tokens = 16;
        auto generate = [&] {
            engine.prepare(); std::string output;
            auto stats = engine.generate({message}, settings, [&](const std::string & piece) { output += piece; return true; });
            require(stats.generated > 0 && !stats.cancelled, "No actual inference output");
            require(!output.empty(), "Empty output"); return output;
        };
        const auto first = generate();
        require(first.find("こんにちは") != std::string::npos || first.find("おはよう") != std::string::npos ||
                first.find("こんばんは") != std::string::npos, "Expected a Japanese greeting");
        std::cout << "Greeting: " << first << '\n';
        require(first == generate(), "Context reset changed deterministic output");
        engine.prepare();
        auto cancelled = engine.generate({message}, settings, [](const std::string &) { return false; });
        require(cancelled.cancelled && cancelled.finish_reason == "cancelled", "Cancellation failed");
        require(first == generate(), "Generation after cancellation did not reset");
        settings.max_tokens = 0;
        bool rejected = false;
        try { engine.generate({message}, settings, [](const std::string &) { return true; }); }
        catch (const std::invalid_argument &) { rejected = true; }
        require(rejected, "Invalid output limit accepted");
        engine.unload();
        settings.max_tokens = 16;
        rejected = false;
        try { engine.generate({message}, settings, [](const std::string &) { return true; }); }
        catch (const std::runtime_error &) { rejected = true; }
        require(rejected, "Generation without a model accepted");
        engine.prepare(); engine.load(argv[1], 512, 2, true, "AD-Q4_K_M");
        settings.max_tokens = 1024; rejected = false;
        try { engine.generate({message}, settings, [](const std::string &) { return true; }); }
        catch (const std::runtime_error &) { rejected = true; }
        require(rejected, "Context overflow accepted");
        engine.unload();
        if (argc == 3) {
            engine.prepare(); engine.load(argv[2], 512, 2, true, "AD-Q6_K");
            settings.max_tokens = 16;
            auto stats = engine.generate({message}, settings, [](const std::string &) { return true; });
            require(stats.generated > 0 && !stats.cancelled, "Q6 inference failed");
            engine.unload();
        }
        std::cout << "PASS: LFM3 load, actual Japanese output, reset, cancellation, recovery, validation, unload/reload, context overflow\n";
    } catch (const std::exception & error) { std::cerr << error.what() << '\n'; return 1; }
}
