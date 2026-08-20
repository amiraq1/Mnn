import { describe, expect, it } from "vitest";

import { MODEL_SPEC, MODEL_TOTAL_BYTES } from "../lib/model-spec";

describe("MNN model specification", () => {
  it("declares all required runtime artifacts with SHA-256 integrity hashes", () => {
    const required = ["config.json", "embeddings_bf16.bin", "llm.mnn", "llm.mnn.weight", "llm_config.json", "tokenizer.txt"];
    const available = MODEL_SPEC.artifacts.map((artifact) => artifact.name);
    expect(available).toEqual(expect.arrayContaining(required));
    MODEL_SPEC.artifacts.forEach((artifact) => {
      expect(artifact.bytes).toBeGreaterThan(0);
      expect(artifact.sha256).toMatch(/^[a-f0-9]{64}$/);
    });
  });

  it("creates direct artifact URLs and totals the expected download size", () => {
    expect(MODEL_SPEC.urlFor("llm.mnn.weight")).toContain("llm.mnn.weight?download=true");
    expect(MODEL_TOTAL_BYTES).toBe(MODEL_SPEC.artifacts.reduce((total, artifact) => total + artifact.bytes, 0));
    expect(MODEL_TOTAL_BYTES).toBeGreaterThan(500 * 1024 * 1024);
  });
});
