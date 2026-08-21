import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    exclude: ["vendor/**", "node_modules/**", "dist/**"],
  },
});
