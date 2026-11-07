#include <jni.h>
#include <onnxruntime_c_api.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Separator {
    const OrtApi *api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    OrtEnv *env = nullptr;
    OrtSessionOptions *options = nullptr;
    OrtSession *session = nullptr;
    OrtMemoryInfo *memory = nullptr;
    std::string output_name;
    void check(OrtStatus *status) {
        if (!status) return;
        std::string message(api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
    ~Separator() {
        if (!api) return;
        if (session) api->ReleaseSession(session);
        if (options) api->ReleaseSessionOptions(options);
        if (memory) api->ReleaseMemoryInfo(memory);
        if (env) api->ReleaseEnv(env);
    }
};
void fail(JNIEnv *env, const char *text) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), text); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_team_replika_engine_LocalSeparation_open(JNIEnv *env, jobject, jstring path, jint threads) {
    try {
        auto engine = std::make_unique<Separator>();
        if (!engine->api) throw std::runtime_error("ONNX Runtime API 17 is unavailable");
        auto api = engine->api;
        engine->check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "replika-separation", &engine->env));
        engine->check(api->CreateSessionOptions(&engine->options));
        engine->check(api->SetIntraOpNumThreads(engine->options, threads));
        engine->check(api->SetInterOpNumThreads(engine->options, 1));
        engine->check(api->SetSessionGraphOptimizationLevel(engine->options, ORT_ENABLE_ALL));
        const char *p = env->GetStringUTFChars(path, nullptr);
        auto status = api->CreateSession(engine->env, p, engine->options, &engine->session);
        env->ReleaseStringUTFChars(path, p);
        engine->check(status);
        engine->check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &engine->memory));
        OrtAllocator *allocator = nullptr;
        engine->check(api->GetAllocatorWithDefaultOptions(&allocator));
        char *name = nullptr;
        engine->check(api->SessionGetOutputName(engine->session, 0, allocator, &name));
        engine->output_name = name;
        allocator->Free(allocator, name);
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_team_replika_engine_LocalSeparation_run(JNIEnv *env, jobject, jlong handle, jfloatArray input) {
    try {
        auto engine = reinterpret_cast<Separator *>(handle);
        if (!engine) throw std::runtime_error("Separation model is closed");
        const int count = env->GetArrayLength(input);
        if (count < 1600 || count > 96000) throw std::runtime_error("Select 0.1 to 6 seconds for separation");
        std::vector<float> samples(count);
        env->GetFloatArrayRegion(input, 0, count, samples.data());
        const int64_t shape[] = {1, count};
        auto api = engine->api;
        auto release = [api](OrtValue *value) { if (value) api->ReleaseValue(value); };
        using Value = std::unique_ptr<OrtValue, decltype(release)>;
        OrtValue *rawInput = nullptr;
        engine->check(api->CreateTensorWithDataAsOrtValue(engine->memory, samples.data(), count * sizeof(float), shape, 2,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &rawInput));
        Value in(rawInput, release);
        const OrtValue *inputs[] = {in.get()};
        const char *names[] = {"mixture"};
        const char *outputs[] = {engine->output_name.c_str()};
        OrtValue *rawOutput = nullptr;
        auto status = api->Run(engine->session, nullptr, names, inputs, 1, outputs, 1, &rawOutput);
        Value result(rawOutput, release);
        engine->check(status);
        OrtTensorTypeAndShapeInfo *rawInfo = nullptr;
        engine->check(api->GetTensorTypeAndShape(result.get(), &rawInfo));
        auto releaseInfo = [api](OrtTensorTypeAndShapeInfo *info) { api->ReleaseTensorTypeAndShapeInfo(info); };
        std::unique_ptr<OrtTensorTypeAndShapeInfo, decltype(releaseInfo)> info(rawInfo, releaseInfo);
        size_t dimensions = 0;
        engine->check(api->GetDimensionsCount(info.get(), &dimensions));
        if (dimensions != 3) throw std::runtime_error("Invalid separated audio dimensions");
        int64_t dims[3];
        engine->check(api->GetDimensions(info.get(), dims, 3));
        if (dims[0] != 1 || dims[1] != 2 || dims[2] < count || dims[2] > count + 512)
            throw std::runtime_error("Invalid separated audio shape");
        float *data = nullptr;
        engine->check(api->GetTensorMutableData(result.get(), reinterpret_cast<void **>(&data)));
        auto array = env->NewObjectArray(2, env->FindClass("[F"), nullptr);
        if (!array) return nullptr;
        for (int i = 0; i < 2; ++i) {
            auto source = env->NewFloatArray(count);
            if (!source) return nullptr;
            env->SetFloatArrayRegion(source, 0, count, data + i * dims[2]);
            env->SetObjectArrayElement(array, i, source);
            env->DeleteLocalRef(source);
        }
        return array;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_team_replika_engine_LocalSeparation_release(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Separator *>(handle); }
