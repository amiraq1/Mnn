# كتالوج GGUF الموصى به

يستخدم الكتالوج داخل التطبيق ملفات GGUF الرسمية المنشورة في مستودعات الناشرين، ويثبت اسم الملف وحجمه وبصمة SHA-256 لكل تنزيل. تُعرض خيارات Q4 لأنها توازن بين حجم التخزين وسرعة الاستدلال على أجهزة Android.

| المعرّف | النموذج | الملف | الحجم بالبايت | RAM موصى بها | SHA-256 |
|---|---|---|---:|---:|---|
| `qwen-0.5b-q4km` | Qwen2.5 0.5B Instruct | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | 491,400,032 | 4 GB | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| `qwen-1.5b-q4km` | Qwen2.5 1.5B Instruct | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1,117,320,736 | 6 GB | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| `smollm2-1.7b-q4km` | SmolLM2 1.7B Instruct | `smollm2-1.7b-instruct-q4_k_m.gguf` | 1,055,609,536 | 6 GB | `decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33` |

## مصادر البيانات

تمت قراءة بيانات الملفات، الحجم وبصمات LFS من واجهة Hugging Face العامة في 21 أغسطس 2026. يفضّل التطبيق رابط `resolve/main/<filename>?download=true` مع طلبات HTTP Range حتى يمكن استئناف النقل المنقطع.

| المصدر | المستودع |
|---|---|
| Qwen | [Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) |
| Qwen | [Qwen2.5-1.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) |
| Hugging Face | [SmolLM2-1.7B-Instruct-GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF) |
