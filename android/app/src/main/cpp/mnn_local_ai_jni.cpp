#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "llm/llm.hpp"
#include "llama.h"

namespace {
constexpr const char* kTag = "MnnLocalAi";
JavaVM* g_vm = nullptr;
jclass g_module_class = nullptr;
jmethodID g_emit_token = nullptr;
jmethodID g_emit_completed = nullptr;
jmethodID g_emit_error = nullptr;
std::mutex g_model_mutex;
std::unique_ptr<MNN::Transformer::Llm> g_model;
llama_model* g_gguf_model = nullptr;
llama_context* g_gguf_context = nullptr;
std::atomic<bool> g_llama_backend_initialized{false};
std::atomic<bool> g_stop_requested{false};
std::atomic<bool> g_generating{false};

void log_error(const std::string& message) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

void release_gguf_locked() {
  if (g_gguf_context != nullptr) {
    llama_free(g_gguf_context);
    g_gguf_context = nullptr;
  }
  if (g_gguf_model != nullptr) {
    llama_model_free(g_gguf_model);
    g_gguf_model = nullptr;
  }
}

std::string apply_gguf_chat_template(const std::string& prompt) {
  if (g_gguf_model == nullptr) throw std::runtime_error("نموذج GGUF غير جاهز");
  const char* chat_template = llama_model_chat_template(g_gguf_model, nullptr);
  if (chat_template == nullptr) return "User: " + prompt + "\nAssistant:";
  llama_chat_message message{"user", prompt.c_str()};
  const int32_t required = llama_chat_apply_template(chat_template, &message, 1, true, nullptr, 0);
  if (required <= 0) return "User: " + prompt + "\nAssistant:";
  std::vector<char> output(static_cast<size_t>(required) + 1, '\0');
  const int32_t written = llama_chat_apply_template(chat_template, &message, 1, true, output.data(), static_cast<int32_t>(output.size()));
  if (written <= 0) return "User: " + prompt + "\nAssistant:";
  return std::string(output.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize_gguf_prompt(const std::string& prompt) {
  const llama_vocab* vocab = llama_model_get_vocab(g_gguf_model);
  const int32_t required = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
  if (required <= 0) throw std::runtime_error("تعذر تحويل مطالبة GGUF إلى رموز");
  std::vector<llama_token> tokens(static_cast<size_t>(required));
  if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), required, true, true) < 0) {
    throw std::runtime_error("تعذر تجهيز مطالبة GGUF");
  }
  return tokens;
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
  std::lock_guard<std::mutex> lock(g_model_mutex);
  g_model.reset();
  release_gguf_locked();
  if (g_llama_backend_initialized.exchange(false, std::memory_order_acq_rel)) llama_backend_free();
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
    release_gguf_locked();
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
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeLoadGguf(JNIEnv* env, jobject, jstring model_path) {
  std::lock_guard<std::mutex> lock(g_model_mutex);
  if (g_generating.load(std::memory_order_acquire)) return JNI_FALSE;
  const char* raw_path = env->GetStringUTFChars(model_path, nullptr);
  const std::string path(raw_path ? raw_path : "");
  if (raw_path != nullptr) env->ReleaseStringUTFChars(model_path, raw_path);
  try {
    g_model.reset();
    release_gguf_locked();
    if (!g_llama_backend_initialized.exchange(true, std::memory_order_acq_rel)) llama_backend_init();
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_gguf_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_gguf_model == nullptr) throw std::runtime_error("تعذر قراءة أوزان GGUF أو أن البنية غير مدعومة");
    auto context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    const auto hardware_threads = std::thread::hardware_concurrency();
    context_params.n_threads = static_cast<int32_t>(std::max(1u, std::min(4u, hardware_threads == 0 ? 1u : hardware_threads)));
    context_params.n_threads_batch = context_params.n_threads;
    context_params.no_perf = false;
    g_gguf_context = llama_init_from_model(g_gguf_model, context_params);
    if (g_gguf_context == nullptr) throw std::runtime_error("تعذر إنشاء سياق GGUF؛ قد لا تكفي ذاكرة الجهاز");
    g_stop_requested.store(false, std::memory_order_release);
    return JNI_TRUE;
  } catch (const std::exception& error) {
    log_error(std::string("GGUF model load failed: ") + error.what());
    release_gguf_locked();
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
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeWarmupGguf(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_model_mutex);
  if (g_gguf_model == nullptr || g_gguf_context == nullptr || g_generating.load(std::memory_order_acquire)) return JNI_FALSE;
  try {
    llama_memory_clear(llama_get_memory(g_gguf_context), false);
    auto tokens = tokenize_gguf_prompt("Hello");
    if (tokens.size() > llama_n_ctx(g_gguf_context)) throw std::runtime_error("سياق GGUF أصغر من مطالبة warm-up");
    auto batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    return llama_decode(g_gguf_context, batch) == 0 ? JNI_TRUE : JNI_FALSE;
  } catch (const std::exception& error) {
    log_error(std::string("GGUF warm-up failed: ") + error.what());
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_mnnlocalai_mnn_MnnLocalAiModule_nativeGenerateGguf(JNIEnv* env, jobject module, jstring prompt, jstring run_id) {
  {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_gguf_model == nullptr || g_gguf_context == nullptr || g_generating.exchange(true, std::memory_order_acq_rel)) return JNI_FALSE;
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
      std::lock_guard<std::mutex> lock(g_model_mutex);
      const auto chat_prompt = apply_gguf_chat_template(prompt_string);
      auto tokens = tokenize_gguf_prompt(chat_prompt);
      if (tokens.size() >= llama_n_ctx(g_gguf_context)) throw std::runtime_error("المطالبة أطول من سياق نموذج GGUF الحالي (2048 رمزًا)");
      llama_memory_clear(llama_get_memory(g_gguf_context), true);
      auto sampler_params = llama_sampler_chain_default_params();
      sampler_params.no_perf = false;
      std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sampler_params), llama_sampler_free);
      if (!sampler) throw std::runtime_error("تعذر إنشاء sampler لنموذج GGUF");
      llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
      llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
      if (llama_decode(g_gguf_context, batch) != 0) throw std::runtime_error("فشل تحليل مطالبة GGUF");
      const llama_vocab* vocab = llama_model_get_vocab(g_gguf_model);
      for (int index = 0; index < 256; ++index) {
        if (g_stop_requested.load(std::memory_order_acquire)) {
          stopped = true;
          break;
        }
        const llama_token token = llama_sampler_sample(sampler.get(), g_gguf_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece_buffer[1024];
        const int32_t piece_size = llama_token_to_piece(vocab, token, piece_buffer, sizeof(piece_buffer), 0, true);
        if (piece_size < 0) throw std::runtime_error("تعذر فك رمز GGUF الناتج");
        CallbackStreamBuffer buffer(worker_env, module_global, run_id_string);
        std::ostream output(&buffer);
        output.write(piece_buffer, piece_size);
        output.flush();
        llama_token generated_token = token;
        batch = llama_batch_get_one(&generated_token, 1);
        if (llama_decode(g_gguf_context, batch) != 0) throw std::runtime_error("فشل توليد GGUF");
        ++generated_steps;
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
  release_gguf_locked();
}
