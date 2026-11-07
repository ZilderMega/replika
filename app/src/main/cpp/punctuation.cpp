#include <jni.h>
#include <onnxruntime_c_api.h>
#include <algorithm>
#include <array>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Punctuation {
    const OrtApi *api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    OrtEnv *env = nullptr;
    OrtSession *session = nullptr;
    OrtSessionOptions *options = nullptr;
    OrtMemoryInfo *memory = nullptr;
    void check(OrtStatus *status) const {
        if (!status) return;
        std::string message(api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
    ~Punctuation() {
        if (!api) return;
        if (session) api->ReleaseSession(session);
        if (options) api->ReleaseSessionOptions(options);
        if (memory) api->ReleaseMemoryInfo(memory);
        if (env) api->ReleaseEnv(env);
    }
};
void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_team_replika_engine_LocalPunctuation_open(JNIEnv *env, jobject, jstring path, jint threads) {
    try {
        auto engine = std::make_unique<Punctuation>();
        if (!engine->api) throw std::runtime_error("ONNX Runtime API 17 is unavailable");
        auto api = engine->api;
        engine->check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "replika-punctuation", &engine->env));
        engine->check(api->CreateSessionOptions(&engine->options));
        engine->check(api->SetIntraOpNumThreads(engine->options, threads));
        engine->check(api->SetInterOpNumThreads(engine->options, 1));
        engine->check(api->SetSessionGraphOptimizationLevel(engine->options, ORT_ENABLE_ALL));
        const char *p = env->GetStringUTFChars(path, nullptr);
        auto status = api->CreateSession(engine->env, p, engine->options, &engine->session);
        env->ReleaseStringUTFChars(path, p);
        engine->check(status);
        engine->check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &engine->memory));
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_team_replika_engine_LocalPunctuation_classify(JNIEnv *env, jobject, jlong handle, jlongArray input) {
    try {
        auto engine = reinterpret_cast<Punctuation *>(handle);
        if (!engine) throw std::runtime_error("Punctuation model is closed");
        const int count = env->GetArrayLength(input);
        if (count < 2 || count > 512) throw std::runtime_error("Invalid punctuation token count");
        std::vector<jlong> javaIds(count);
        env->GetLongArrayRegion(input, 0, count, javaIds.data());
        std::vector<int64_t> ids(javaIds.begin(), javaIds.end()), mask(count, 1), types(count, 0);
        const int64_t shape[] = {1, count};
        const auto api = engine->api;
        auto releaseValue = [api](OrtValue *value) { if (value) api->ReleaseValue(value); };
        using Value = std::unique_ptr<OrtValue, decltype(releaseValue)>;
        std::array<Value, 3> values = {Value(nullptr, releaseValue), Value(nullptr, releaseValue), Value(nullptr, releaseValue)};
        std::vector<int64_t> *buffers[] = {&ids, &mask, &types};
        std::array<const OrtValue *, 3> inputs{};
        for (int i = 0; i < 3; ++i) {
            OrtValue *value = nullptr;
            engine->check(api->CreateTensorWithDataAsOrtValue(engine->memory, buffers[i]->data(), count * sizeof(int64_t),
                shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &value));
            values[i].reset(value); inputs[i] = value;
        }
        const char *names[] = {"input_ids", "attention_mask", "token_type_ids"};
        const char *outputs[] = {"logits"};
        OrtValue *output = nullptr;
        auto status = api->Run(engine->session, nullptr, names, inputs.data(), 3, outputs, 1, &output);
        Value result(output, releaseValue);
        engine->check(status);
        OrtTensorTypeAndShapeInfo *rawInfo = nullptr;
        engine->check(api->GetTensorTypeAndShape(result.get(), &rawInfo));
        auto releaseInfo = [api](OrtTensorTypeAndShapeInfo *info) { api->ReleaseTensorTypeAndShapeInfo(info); };
        std::unique_ptr<OrtTensorTypeAndShapeInfo, decltype(releaseInfo)> info(rawInfo, releaseInfo);
        size_t dimensions = 0;
        engine->check(api->GetDimensionsCount(info.get(), &dimensions));
        if (dimensions != 3) throw std::runtime_error("Invalid punctuation output shape");
        int64_t dims[3];
        engine->check(api->GetDimensions(info.get(), dims, 3));
        if (dims[0] != 1 || dims[1] != count || dims[2] != 33) throw std::runtime_error("Invalid punctuation output labels");
        float *logits = nullptr;
        engine->check(api->GetTensorMutableData(result.get(), reinterpret_cast<void **>(&logits)));
        std::vector<jint> labels(count);
        for (int i = 0; i < count; ++i) {
            const auto row = logits + i * 33;
            labels[i] = static_cast<jint>(std::max_element(row, row + 33) - row);
        }
        auto array = env->NewIntArray(count);
        if (array) env->SetIntArrayRegion(array, 0, count, labels.data());
        return array;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_team_replika_engine_LocalPunctuation_release(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Punctuation *>(handle);
}
