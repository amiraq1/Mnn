#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>

#include "llm/llm.hpp"

namespace {
constexpr const char* kTag = "MnnLocalAi";
JavaVM* g_vm = nullptr;
jclass g_module_class = nullptr;
jmethodID g_emit_token = nullptr;
jmethodID g_emit_completed = nullptr;
jmethodID g_emit_error = nullptr;
std::mutex g_model_mutex;
std::unique_ptr<MNN::Transformer::Llm> g_model;
std::atomic<bool> g_stop_requested{false};
std::atomic<bool> g_generating{false};

void log_error(const std::string& message) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

class CallbackStreamBuffer final : public std::streambuf {
 public:
  CallbackStreamBuffer(JNIEnv* env, jobject module, const std::string& run_id)
      : env_(env), module_(module), run_id_(run_id) {}

 protected:
  std::streamsize xsputn(const char* source, std::streamsize count) override {
    emit(source, static_cast<size_t>(count));
    return count;
  }

  int overflow(int character) override {
    if (character != EOF) {
      const char value = static_cast<char>(character);
      emit(&value, 1);
    }
    return character;
  }

 private:
  void emit(const char* source, size_t count) {
    if (count == 0 || g_stop_requested.load(std::memory_order_acquire)) return;
    const std::string token(source, count);
    jstring j_run_id = env_->NewStringUTF(run_id_.c_str());
    jstring j_token = env_->NewStringUTF(token.c_str());
    env_->CallVoidMethod(module_, g_emit_token, j_run_id, j_token);
    env_->DeleteLocalRef(j_token);
    env_->DeleteLocalRef(j_run_id);
    if (env_->ExceptionCheck()) {
      env_->ExceptionClear();
      log_error("JavaScript token callback raised an exception");
      g_stop_requested.store(true, std::memory_order_release);
    }
  }

  JNIEnv* env_;
  jobject module_;
  std::string run_id_;
};

void emit_error(JNIEnv* env, jobject module, const std::string& run_id, const std::string& message) {
  jstring j_run_id = run_id.empty() ? nullptr : env->NewStringUTF(run_id.c_str());
  jstring j_message = env->NewStringUTF(message.c_str());
  env->CallVoidMethod(module, g_emit_error, j_run_id, j_message);
  env->DeleteLocalRef(j_message);
  if (j_run_id != nullptr) env->DeleteLocalRef(j_run_id);
  if (env->ExceptionCheck()) env->ExceptionClear();
}

void emit_completed(JNIEnv* env, jobject module, const std::string& run_id, bool stopped,
                    int64_t generation_ms, int64_t generated_steps) {
  jstring j_run_id = env->NewStringUTF(run_id.c_str());
  env->CallVoidMethod(module, g_emit_completed, j_run_id, static_cast<jboolean>(stopped),
                      static_cast<jlong>(generation_ms), static_cast<jlong>(generated_steps));
  env->DeleteLocalRef(j_run_id);
  if (env->ExceptionCheck()) env->ExceptionClear();
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass local_class = env->FindClass("com/app/mnnlocalai/mnn/MnnLocalAiModule");
  if (local_class == nullptr) return JNI_ERR;
  g_module_class = static_cast<jclass>(env->NewGlobalRef(local_class));
  env->DeleteLocalRef(local_class);
  g_emit_token = env->GetMethodID(g_module_class, "emitTokenFromNative", "(Ljava/lang/String;Ljava/lang/String;)V");
  g_emit_completed = env->GetMethodID(g_module_class, "emitGenerationCompletedFromNative", "(Ljava/lang/String;ZJJ)V");
  g_emit_error = env->GetMethodID(g_module_class, "emitErrorFromNative", "(Ljava/lang/String;Ljava/lang/String;)V");
  return (g_emit_token && g_emit_completed && g_emit_error) ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
  if (g_vm != nullptr && g_module_class != nullptr) {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) env->DeleteGlobalRef(g_module_class);
  }
  g_module_class = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeLoad(JNIEnv* env, jobject, jstring config_path) {
  std::lock_guard<std::mutex> lock(g_model_mutex);
  if (g_generating.load(std::memory_order_acquire)) return JNI_FALSE;
  const char* raw_path = env->GetStringUTFChars(config_path, nullptr);
  const std::string path(raw_path ? raw_path : "");
  if (raw_path != nullptr) env->ReleaseStringUTFChars(config_path, raw_path);
  try {
    g_model.reset(MNN::Transformer::Llm::createLLM(path));
    if (!g_model || !g_model->load()) {
      g_model.reset();
      log_error("MNN LLM load returned false");
      return JNI_FALSE;
    }
    g_stop_requested.store(false, std::memory_order_release);
    return JNI_TRUE;
  } catch (const std::exception& error) {
    log_error(std::string("MNN model load failed: ") + error.what());
    g_model.reset();
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeWarmup(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_model_mutex);
  if (!g_model || g_generating.load(std::memory_order_acquire)) return JNI_FALSE;
  try {
    std::ostringstream sink;
    g_model->response("hi", &sink, "<eop>", 1);
    g_model->reset();
    return JNI_TRUE;
  } catch (const std::exception& error) {
    log_error(std::string("MNN warm-up failed: ") + error.what());
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeGenerate(JNIEnv* env, jobject module, jstring prompt, jstring run_id) {
  {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (!g_model || g_generating.exchange(true, std::memory_order_acq_rel)) return JNI_FALSE;
  }
  g_stop_requested.store(false, std::memory_order_release);
  const char* raw_prompt = env->GetStringUTFChars(prompt, nullptr);
  const char* raw_run_id = env->GetStringUTFChars(run_id, nullptr);
  const std::string prompt_string(raw_prompt ? raw_prompt : "");
  const std::string run_id_string(raw_run_id ? raw_run_id : "");
  if (raw_prompt != nullptr) env->ReleaseStringUTFChars(prompt, raw_prompt);
  if (raw_run_id != nullptr) env->ReleaseStringUTFChars(run_id, raw_run_id);
  jobject module_global = env->NewGlobalRef(module);

  std::thread([module_global, prompt_string, run_id_string]() {
    JNIEnv* worker_env = nullptr;
    bool attached = false;
    const jint environment = g_vm->GetEnv(reinterpret_cast<void**>(&worker_env), JNI_VERSION_1_6);
    if (environment == JNI_EDETACHED) {
      if (g_vm->AttachCurrentThread(&worker_env, nullptr) != JNI_OK) {
        g_generating.store(false, std::memory_order_release);
        return;
      }
      attached = true;
    }
    bool stopped = false;
    int64_t generated_steps = 0;
    const auto generation_started = std::chrono::steady_clock::now();
    try {
      CallbackStreamBuffer buffer(worker_env, module_global, run_id_string);
      std::ostream output(&buffer);
      {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        if (!g_model) throw std::runtime_error("النموذج غير جاهز");
        g_model->response(prompt_string, &output, "<eop>", 0);
      }
      for (int generated = 0; generated < 256; ++generated) {
        if (g_stop_requested.load(std::memory_order_acquire)) {
          stopped = true;
          break;
        }
        std::lock_guard<std::mutex> lock(g_model_mutex);
        g_model->generate(1);
        ++generated_steps;
        if (g_model->stoped()) break;
      }
      stopped = stopped || g_stop_requested.load(std::memory_order_acquire);
      const auto generation_finished = std::chrono::steady_clock::now();
      const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_finished - generation_started).count();
      emit_completed(worker_env, module_global, run_id_string, stopped, generation_ms, generated_steps);
    } catch (const std::exception& error) {
      emit_error(worker_env, module_global, run_id_string, error.what());
    }
    worker_env->DeleteGlobalRef(module_global);
    g_generating.store(false, std::memory_order_release);
    if (attached) g_vm->DetachCurrentThread();
  }).detach();
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeStopGeneration(JNIEnv*, jobject) {
  g_stop_requested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeRelease(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_model_mutex);
  if (g_generating.load(std::memory_order_acquire)) return;
  g_model.reset();
}
