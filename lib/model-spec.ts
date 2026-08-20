export type ModelArtifact = {
  name: string;
  bytes: number;
  sha256: string;
};

const repository = "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main";

export const MODEL_SPEC = {
  id: "qwen2.5-0.5b-instruct-mnn-q4",
  displayName: "Qwen2.5 0.5B — MNN Q4",
  minimumAndroidApi: 26,
  recommendedRamGb: 4,
  artifacts: [
    {
      name: "config.json",
      bytes: 159,
      sha256: "7636b063425bdbc0e2e429cb23af7f594b5ba145bab2045dfda852416d9285de",
    },
    {
      name: "embeddings_bf16.bin",
      bytes: 272269312,
      sha256: "4e96b0df6d274768cbb7e72404011853d23349999b658dc2f4dfb3c431ea223f",
    },
    {
      name: "llm.mnn",
      bytes: 566264,
      sha256: "480da511e603bd82f8d4af4e1f778ad72baadf8307f3585465ad9a94daca1a88",
    },
    {
      name: "llm.mnn.json",
      bytes: 2808932,
      sha256: "245ce4289f456dcb371a8f8deabf75c3c4ee75f34b19e0d9723ba09b2fbacf8c",
    },
    {
      name: "llm.mnn.weight",
      bytes: 277967498,
      sha256: "7ed0f4dcdd31dca15fcb548d2fc8b63b0014031fbd5f627508435726f90c75da",
    },
    {
      name: "llm_config.json",
      bytes: 272,
      sha256: "ec05709b4261d59b510a0b7a636c6dcb6c5635c08fee7eb3c4f04188b509694b",
    },
    {
      name: "tokenizer.txt",
      bytes: 3193477,
      sha256: "b86f1298a0d6a1b2f312946c2f674f883f1d134ccabc79c42dd4c6b5beadf37b",
    },
  ] satisfies ModelArtifact[],
  urlFor(name: string) {
    return `${repository}/${name}?download=true`;
  },
};

export const MODEL_TOTAL_BYTES = MODEL_SPEC.artifacts.reduce((sum, artifact) => sum + artifact.bytes, 0);

export function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "غير معروف";
  const units = ["بايت", "ك.ب", "م.ب", "ج.ب"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const amount = bytes / Math.pow(1024, index);
  return `${amount.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}
