#include "runtime.h"
#include <jni.h>
#include <mutex>
#include <stdexcept>
#include <unordered_map>

struct EngineState {
    meshmind::Runtime engine;
    std::mutex mutex;
};
static std::mutex registry_mutex;
static std::unordered_map<jlong, std::shared_ptr<EngineState>> registry;
static jlong next_handle = 1;
static std::shared_ptr<EngineState> acquire(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto entry = registry.find(handle);
    if (entry == registry.end()) throw std::runtime_error("Inference engine is closed");
    return entry->second;
}
static std::string utf8(JNIEnv * env, jbyteArray bytes) {
    if (!bytes) throw std::invalid_argument("Missing UTF-8 bytes");
    const auto n = env->GetArrayLength(bytes);
    if (n > 1024 * 1024) throw std::invalid_argument("Input exceeds 1 MiB");
    std::string result(n, '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
static jbyteArray bytes(JNIEnv * env, const std::string & text) {
    auto result = env->NewByteArray(text.size());
    if (result) env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return result;
}
static void fail(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jlong JNICALL Java_org_meshmind_app_BonsaiEngine_nativeCreate(JNIEnv * env, jobject) {
    try {
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (registry.size() >= 16) throw std::runtime_error("Too many inference engines; close unused instances");
        const jlong handle = next_handle++;
        registry.emplace(handle, std::make_shared<EngineState>());
        return handle;
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_meshmind_app_BonsaiEngine_nativeLoad(
    JNIEnv * env, jobject, jlong handle, jbyteArray path, jint context, jint threads, jboolean efficient,
    jbyteArray format, jobjectArray required_tokens, jbyteArray adapter_path) {
    try {
        auto state = acquire(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        auto & engine = state->engine;
        if (!required_tokens) throw std::invalid_argument("Missing special token requirements");
        std::vector<std::string> tokens;
        int count = env->GetArrayLength(required_tokens);
        if (count > 32) throw std::invalid_argument("Too many special token requirements");
        for (int i = 0; i < count; ++i) {
            auto token = static_cast<jbyteArray>(env->GetObjectArrayElement(required_tokens, i));
            tokens.push_back(utf8(env, token));
            env->DeleteLocalRef(token);
        }
        engine.load(utf8(env, path), context, threads, efficient, utf8(env, format), tokens);
        auto adapter = utf8(env, adapter_path);
        if (!adapter.empty()) {
            try { engine.load_adapter(adapter); }
            catch (...) { engine.unload(); throw; }
        }
        return bytes(env, engine.information());
    } catch (const std::bad_alloc &) { fail(env, "Out of memory; unload model and reduce context"); }
    catch (const std::exception & e) { fail(env, e.what()); }
    return nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_meshmind_app_BonsaiEngine_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens, jfloat temperature,
    jfloat top_p, jint top_k, jint seed, jboolean thinking, jboolean refusal_reduction, jobject sink) {
    try {
        auto state = acquire(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        auto & engine = state->engine;
        if (!roles || !contents) throw std::invalid_argument("Missing message arrays");
        int count = env->GetArrayLength(roles);
        if (count != env->GetArrayLength(contents) || count > 200 || !sink) throw std::invalid_argument("Invalid messages or callback");
        std::vector<common_chat_msg> messages;
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            common_chat_msg message;
            message.role = utf8(env, role);
            message.content = utf8(env, content);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
            if (message.role != "user" && message.role != "assistant" && message.role != "system") throw std::invalid_argument("Unsupported role");
            messages.push_back(std::move(message));
        }
        auto cls = env->GetObjectClass(sink);
        auto callback = env->GetMethodID(cls, "onBytes", "([B)Z");
        env->DeleteLocalRef(cls);
        if (!callback) return nullptr;
        meshmind::Settings s;
        s.max_tokens = max_tokens; s.temperature = temperature; s.top_p = top_p;
        s.top_k = top_k; s.seed = seed; s.thinking = thinking;
        s.refusal_reduction = refusal_reduction;
        auto stats = engine.generate(messages, s, [&](const std::string & piece) {
            auto chunk = bytes(env, piece);
            if (!chunk) return false;
            bool keep = env->CallBooleanMethod(sink, callback, chunk);
            env->DeleteLocalRef(chunk);
            return keep && !env->ExceptionCheck();
        });
        if (env->ExceptionCheck()) return nullptr;
        return bytes(env, stats.json());
    } catch (const std::bad_alloc &) { fail(env, "Out of memory during generation; reduce context"); }
    catch (const std::exception & e) { fail(env, e.what()); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_org_meshmind_app_BonsaiEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    try { acquire(handle)->engine.cancel(); } catch (const std::exception &) { }
}
extern "C" JNIEXPORT void JNICALL Java_org_meshmind_app_BonsaiEngine_nativePrepare(JNIEnv * env, jobject, jlong handle) {
    try { acquire(handle)->engine.prepare(); } catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_org_meshmind_app_BonsaiEngine_nativeReset(JNIEnv * env, jobject, jlong handle) {
    try {
        auto state = acquire(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        state->engine.reset_context();
    } catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_org_meshmind_app_BonsaiEngine_nativeUnload(JNIEnv * env, jobject, jlong handle) {
    try {
        auto state = acquire(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        state->engine.unload();
    } catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_org_meshmind_app_BonsaiEngine_nativeClose(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<EngineState> state;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto entry = registry.find(handle);
        if (entry == registry.end()) return;
        state = entry->second;
        registry.erase(entry);
    }
    state->engine.cancel();
}
