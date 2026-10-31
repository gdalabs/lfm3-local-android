#include "runtime.h"
#include <iostream>
#include <fstream>
#include <cstdlib>
int main(int argc, char ** argv) {
    if (argc < 2) { std::cerr << "Usage: meshmind-bench MODEL [PROMPT] [MAX_TOKENS] [CANCEL_AFTER]\n"; return 2; }
    try {
        meshmind::Runtime engine;
        engine.load(argv[1], 2048, 2, true);
        std::cerr << engine.information() << '\n';
        common_chat_msg msg; msg.role = "user";
        msg.content = argc > 2 ? argv[2] : "What is 2 + 2? Answer with just the number.";
        if (argc > 5) { std::ofstream out(argv[5]); out << engine.format_prompt({msg}, false); }
        meshmind::Settings settings;
        settings.temperature = argc > 6 ? std::stof(argv[6]) : 0;
        settings.max_tokens = argc > 3 ? std::stoi(argv[3]) : 32;
        int cancel_after = argc > 4 ? std::stoi(argv[4]) : 0;
        int generated = 0;
        auto stats = engine.generate({msg}, settings, [&](const std::string & piece) {
            std::cout << piece << std::flush;
            return !cancel_after || ++generated < cancel_after;
        });
        std::cout << '\n';
        std::cerr << stats.json() << '\n';
        engine.unload();
    } catch (const std::exception & e) { std::cerr << e.what() << '\n'; return 1; }
}
