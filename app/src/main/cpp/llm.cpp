#include <jni.h>
#include <llama.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ~Engine() { if (ctx) llama_free(ctx); if (model) llama_free_model(model); }
};
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jlong JNICALL
Java_team_replika_engine_LocalLlm_open(JNIEnv *env, jobject, jstring path, jint threads) {
    try {
        static std::once_flag init;
        std::call_once(init, [] { llama_backend_init(); });
        auto engine = std::make_unique<Engine>();
        const char *p = env->GetStringUTFChars(path, nullptr);
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        engine->model = llama_load_model_from_file(p, params);
        env->ReleaseStringUTFChars(path, p);
        if (!engine->model) throw std::runtime_error("Cannot load GGUF model");
        auto context = llama_context_default_params();
        context.n_ctx = 1024; context.n_batch = 1024; context.n_ubatch = 128;
        context.n_threads = threads; context.n_threads_batch = threads;
        engine->ctx = llama_new_context_with_model(engine->model, context);
        if (!engine->ctx) throw std::runtime_error("Cannot allocate LLM context");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_team_replika_engine_LocalLlm_generate(JNIEnv *env, jobject, jlong handle, jbyteArray prompt, jint maxTokens, jobject active) {
    try {
        auto *engine = reinterpret_cast<Engine *>(handle);
        if (!engine) throw std::runtime_error("LLM is closed");
        int size = env->GetArrayLength(prompt);
        std::string input(size, '\0');
        env->GetByteArrayRegion(prompt, 0, size, reinterpret_cast<jbyte *>(input.data()));
        int n = -llama_tokenize(engine->model, input.data(), size, nullptr, 0, true, true);
        if (n <= 0 || n + maxTokens > 1024) throw std::runtime_error("LLM context limit exceeded");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(engine->model, input.data(), size, tokens.data(), n, true, true) != n)
            throw std::runtime_error("Tokenization failed");
        llama_kv_cache_clear(engine->ctx);
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(llama_sampler_init_greedy(), llama_sampler_free);
        auto batch = llama_batch_get_one(tokens.data(), n);
        auto method = env->GetMethodID(env->GetObjectClass(active), "invoke", "()Ljava/lang/Object;");
        auto boolClass = env->FindClass("java/lang/Boolean");
        auto boolMethod = env->GetMethodID(boolClass, "booleanValue", "()Z");
        std::string output;
        llama_token token;
        for (int i = 0; i < maxTokens; ++i) {
            auto flag = env->CallObjectMethod(active, method);
            if (env->ExceptionCheck()) return nullptr;
            bool running = env->CallBooleanMethod(flag, boolMethod);
            env->DeleteLocalRef(flag);
            if (!running) break;
            if (llama_decode(engine->ctx, batch) != 0) throw std::runtime_error("LLM decode failed");
            token = llama_sampler_sample(sampler.get(), engine->ctx, -1);
            if (llama_token_is_eog(engine->model, token)) break;
            std::vector<char> piece(256);
            int length = llama_token_to_piece(engine->model, token, piece.data(), piece.size(), 0, false);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(engine->model, token, piece.data(), piece.size(), 0, false);
            }
            if (length < 0) throw std::runtime_error("Invalid token");
            output.append(piece.data(), length);
            batch = llama_batch_get_one(&token, 1);
        }
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_team_replika_engine_LocalLlm_release(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
