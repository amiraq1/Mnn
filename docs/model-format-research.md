# بحث توافق صيغ النماذج المحلية

## النتيجة التصميمية

يتطلب دعم كل صيغة محرك استدلال محليًا مخصصًا؛ لا يمكن اعتبار ملف GGUF أو ONNX نموذج MNN صالحًا لمجرد استيراده إلى نفس الجسر. سيبقي التطبيق مسار MNN الحالي للنماذج المحولة بصيغة MNN، ويعامل GGUF وONNX كمحركات اختيارية مستقلة مع فحص صريح للصيغة ومتطلبات الذاكرة قبل التشغيل.

| الصيغة | المحرك المقترح | ملاحظة Android |
|---|---|---|
| MNN LLM | MNN المدمج حاليًا | يحتاج مجلدًا صالحًا يحوي `config.json` وملفات النموذج المرتبطة به. |
| GGUF | llama.cpp عبر JNI | توثق llama.cpp بناء Android عبر CMake وNDK؛ يتطلب ذلك مكتبة أصلية إضافية ولا يشارك واجهة MNN نفسها. |
| ONNX | ONNX Runtime Mobile | يحتاج نموذجًا بتنسيق ONNX؛ ملاءمة الذاكرة والتوافق مع Execution Provider يعتمدان على النموذج والجهاز. |

## مصادر رسمية

1. توثق MNN Chat Android دعم عائلات نماذج متعددة ضمن تطبيق Android المرجعي، مع الاعتماد على تكوينات تشغيل خاصة بكل نموذج: [MNN Chat Android](https://github.com/alibaba/MNN/blob/master/apps/Android/MnnLlmChat/README.md).
2. توثق llama.cpp بناء Android باستخدام Android NDK وCMake، مع ملاحظات حول ABI ومستوى Android وخيارات البناء: [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).
3. توضح ONNX Runtime Mobile أن النموذج يجب أن يكون بصيغة ONNX، وأنه يجب أن يلائم مساحة وذاكرة الجهاز، مع اختيار Execution Provider بحسب النموذج والجهاز: [Deploy on mobile](https://onnxruntime.ai/docs/tutorials/mobile/).
