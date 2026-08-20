cmake_minimum_required(VERSION 3.22.1)
project(mnnlocalai)

set(MNN_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../vendor/MNN")
set(MNN_BUILD_SHARED_LIBS ON CACHE BOOL "Build MNN as Android shared libraries" FORCE)
set(MNN_SEP_BUILD ON CACHE BOOL "Build the LLM runtime as a separate shared library" FORCE)
set(MNN_BUILD_LLM ON CACHE BOOL "Build the MNN LLM engine" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "Enable MNN low-memory runtime defaults" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "Enable optimized quantized CPU weight path" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "Enable Transformer graph fusion" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "Do not build conversion tools in the mobile app" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "Do not build MNN demos in the mobile app" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "Do not build MNN command-line tools in the mobile app" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "Do not build LLM demo binaries in the mobile app" FORCE)
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "Keep model downloads in the Kotlin downloader" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "Send MNN logging to Android logcat" FORCE)

add_subdirectory("${MNN_ROOT}" "${CMAKE_BINARY_DIR}/mnn")

add_library(mnnlocalai SHARED mnn_local_ai_jni.cpp)
target_include_directories(mnnlocalai PRIVATE
  "${MNN_ROOT}/include"
  "${MNN_ROOT}/transformers/llm/engine/include"
)
target_compile_features(mnnlocalai PRIVATE cxx_std_17)
target_link_options(mnnlocalai PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(mnnlocalai android log MNN llm)
