# مصادر تقنية معتمدة

يستخدم المشروع مستودع **MNN** الرسمي كمصدر لمحرك الاستدلال وبنية Android. توضح وثائق المشروع أن تفعيل LLM يتطلب خيارات البناء `MNN_LOW_MEMORY` و`MNN_BUILD_LLM` و`MNN_SUPPORT_TRANSFORMER_FUSE`، مع دعم بناء Android عبر NDK. كما يوفر التطبيق الرسمي لـ MNN مرجعاً لمسار تطبيق Android المحلي. [1] [2]

يعتمد التطبيق نموذج **taobao-mnn/Qwen2.5-0.5B-Instruct-MNN**، وهو نموذج Qwen2.5-0.5B-Instruct مُحوّل ومكمّم بأربع بتات. تحفظ مصفوفة التنزيل داخل التطبيق قيمة SHA-256 لكل ملف من ملفات التشغيل المطلوبة، ولا يعد التنزيل مكتملًا إلا بعد تحقق الملف المحلي من القيمة المطابقة. [3]

يستخدم بناء APK أدوات Android الرسمية لتثبيت Platform API 36 وBuild Tools وNDK وCMake عبر مدير SDK، ويُثبّت NDK محدداً للإبقاء على البناء قابلاً للإعادة. [4] [5]

## المراجع

[1]: https://github.com/alibaba/MNN/blob/master/transformers/README.md "MNN-LLM الرسمي"
[2]: https://github.com/alibaba/MNN/blob/master/apps/Android/MnnLlmChat/README.md "تطبيق MNN Chat لأندرويد"
[3]: https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN "نموذج Qwen2.5-0.5B-Instruct-MNN"
[4]: https://developer.android.com/tools/sdkmanager "مدير Android SDK الرسمي"
[5]: https://developer.android.com/studio/projects/install-ndk "تثبيت NDK وCMake لأندرويد"
