import { DeviceEventEmitter, NativeModules, Platform } from "react-native";

export type NativeModelState = "missing" | "downloading" | "paused" | "verifying" | "ready" | "loading" | "warming" | "failed";

export type ModelStatus = {
  state: NativeModelState;
  downloadedBytes: number;
  totalBytes: number;
  message?: string;
  engine?: "mnn" | "gguf";
  format?: string;
  modelName?: string;
};

export type NativeDownloadProgress = {
  downloadedBytes: number;
  totalBytes: number;
  currentFile: string;
  phase: "downloading" | "paused" | "verifying";
  speedBytesPerSecond: number;
  etaSeconds: number;
};

export type RecommendedGgufModel = {
  id: string;
  displayName: string;
  description: string;
  format: string;
  bytes: number;
  recommendedRamGb: number;
};

export type PerformanceMetrics = {
  hasGeneration: boolean;
  generationMs: number;
  generatedSteps: number;
  stepsPerSecond: number;
  stopped: boolean;
  totalPssKb: number;
  nativePssKb: number;
  javaHeapUsedKb: number;
};

type MnnNativeModule = {
  getModelStatus(): Promise<ModelStatus>;
  getPerformanceMetrics(): Promise<PerformanceMetrics>;
  startModelDownload(): void;
  pauseModelDownload(): void;
  resumeModelDownload(): void;
  initializeModel(): Promise<{ loadMs: number; warmupMs: number }>;
  generate(prompt: string, runId: string): Promise<boolean>;
  stopGeneration(): void;
  releaseModel(): void;
  deleteModel(): void;
  importGguf(uri: string, displayName: string, expectedBytes: number): Promise<{ id: string; name: string; bytes: number; format: string }>;
  selectMnnModel(): Promise<void>;
  getRecommendedGgufModels(): Promise<RecommendedGgufModel[]>;
  startRecommendedGgufDownload(modelId: string): void;
};

const nativeModule = NativeModules.MnnLocalAi as MnnNativeModule | undefined;

export const isNativeMnnAvailable = Platform.OS === "android" && Boolean(nativeModule);

function requireNativeModule() {
  if (!nativeModule) {
    throw new Error("يتطلب الاستدلال المحلي إصدار Android مخصصًا، وليس Expo Go أو معاينة الويب.");
  }
  return nativeModule;
}

export const mnnLocalAi = {
  getModelStatus: () => requireNativeModule().getModelStatus(),
  getPerformanceMetrics: () => requireNativeModule().getPerformanceMetrics(),
  startModelDownload: () => requireNativeModule().startModelDownload(),
  pauseModelDownload: () => requireNativeModule().pauseModelDownload(),
  resumeModelDownload: () => requireNativeModule().resumeModelDownload(),
  initializeModel: () => requireNativeModule().initializeModel(),
  generate: (prompt: string, runId: string) => requireNativeModule().generate(prompt, runId),
  stopGeneration: () => requireNativeModule().stopGeneration(),
  releaseModel: () => requireNativeModule().releaseModel(),
  deleteModel: () => requireNativeModule().deleteModel(),
  importGguf: (uri: string, displayName: string, expectedBytes: number) => requireNativeModule().importGguf(uri, displayName, expectedBytes),
  selectMnnModel: () => requireNativeModule().selectMnnModel(),
  getRecommendedGgufModels: () => requireNativeModule().getRecommendedGgufModels(),
  startRecommendedGgufDownload: (modelId: string) => requireNativeModule().startRecommendedGgufDownload(modelId),
  onDownloadProgress(listener: (progress: NativeDownloadProgress) => void) {
    return DeviceEventEmitter.addListener("MnnLocalAiDownloadProgress", listener);
  },
  onDownloadCompleted(listener: () => void) {
    return DeviceEventEmitter.addListener("MnnLocalAiDownloadCompleted", listener);
  },
  onNativeError(listener: (event: { message: string; runId?: string }) => void) {
    return DeviceEventEmitter.addListener("MnnLocalAiError", listener);
  },
  onToken(listener: (event: { runId: string; token: string }) => void) {
    return DeviceEventEmitter.addListener("MnnLocalAiToken", listener);
  },
  onGenerationCompleted(listener: (event: { runId: string; stopped: boolean } & PerformanceMetrics) => void) {
    return DeviceEventEmitter.addListener("MnnLocalAiGenerationCompleted", listener);
  },
};
