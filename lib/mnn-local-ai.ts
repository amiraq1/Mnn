import { DeviceEventEmitter, NativeModules, Platform } from "react-native";

export type NativeModelState = "missing" | "downloading" | "verifying" | "ready" | "loading" | "warming" | "failed";

export type ModelStatus = {
  state: NativeModelState;
  downloadedBytes: number;
  totalBytes: number;
  message?: string;
};

export type NativeDownloadProgress = {
  downloadedBytes: number;
  totalBytes: number;
  currentFile: string;
  phase: "downloading" | "verifying";
};

type MnnNativeModule = {
  getModelStatus(): Promise<ModelStatus>;
  startModelDownload(): void;
  initializeModel(): Promise<{ loadMs: number; warmupMs: number }>;
  generate(prompt: string, runId: string): Promise<boolean>;
  stopGeneration(): void;
  releaseModel(): void;
  deleteModel(): void;
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
  startModelDownload: () => requireNativeModule().startModelDownload(),
  initializeModel: () => requireNativeModule().initializeModel(),
  generate: (prompt: string, runId: string) => requireNativeModule().generate(prompt, runId),
  stopGeneration: () => requireNativeModule().stopGeneration(),
  releaseModel: () => requireNativeModule().releaseModel(),
  deleteModel: () => requireNativeModule().deleteModel(),
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
  onGenerationCompleted(listener: (event: { runId: string; stopped: boolean }) => void) {
    return DeviceEventEmitter.addListener("MnnLocalAiGenerationCompleted", listener);
  },
};
