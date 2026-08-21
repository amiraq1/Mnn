import { useEffect, useMemo, useRef, useState } from "react";
import * as DocumentPicker from "expo-document-picker";
import {
  ActivityIndicator,
  Alert,
  FlatList,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { formatBytes, MODEL_SPEC, MODEL_TOTAL_BYTES } from "@/lib/model-spec";
import { isNativeMnnAvailable, mnnLocalAi, type ModelStatus, type NativeDownloadProgress, type PerformanceMetrics, type RecommendedGgufModel } from "@/lib/mnn-local-ai";

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  pending?: boolean;
};

const initialStatus: ModelStatus = {
  state: "missing",
  downloadedBytes: 0,
  totalBytes: MODEL_TOTAL_BYTES,
};

export default function HomeScreen() {
  const [status, setStatus] = useState<ModelStatus>(initialStatus);
  const [progress, setProgress] = useState<NativeDownloadProgress | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [isGenerating, setIsGenerating] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const [metrics, setMetrics] = useState<PerformanceMetrics | null>(null);
  const [chatStarted, setChatStarted] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [recommendedModels, setRecommendedModels] = useState<RecommendedGgufModel[]>([]);
  const [showCatalog, setShowCatalog] = useState(false);
  const activeRunId = useRef<string | null>(null);

  const percent = useMemo(() => {
    const total = progress?.totalBytes ?? status.totalBytes;
    const downloaded = progress?.downloadedBytes ?? status.downloadedBytes;
    return total > 0 ? Math.min(100, Math.round((downloaded / total) * 100)) : 0;
  }, [progress, status]);

  useEffect(() => {
    if (!isNativeMnnAvailable) return;
    void refreshStatus();
    void loadRecommendedModels();
    const subscriptions = [
      mnnLocalAi.onDownloadProgress((event) => {
        setProgress(event);
        setStatus((current) => ({ ...current, state: event.phase === "verifying" ? "verifying" : "downloading", downloadedBytes: event.downloadedBytes, totalBytes: event.totalBytes }));
      }),
      mnnLocalAi.onDownloadCompleted(() => {
        setProgress(null);
        void refreshStatus();
      }),
      mnnLocalAi.onToken(({ runId, token }) => {
        if (activeRunId.current !== runId) return;
        setMessages((current) => current.map((message) => message.id === runId ? { ...message, content: `${message.content}${token.replace("<eop>", "")}` } : message));
      }),
      mnnLocalAi.onGenerationCompleted(({ runId, ...nextMetrics }) => {
        if (activeRunId.current !== runId) return;
        activeRunId.current = null;
        setIsGenerating(false);
        setMetrics(nextMetrics);
        setMessages((current) => current.map((message) => message.id === runId ? { ...message, pending: false } : message));
      }),
      mnnLocalAi.onNativeError(({ message, runId }) => {
        if (runId && activeRunId.current === runId) {
          activeRunId.current = null;
          setIsGenerating(false);
        }
        setStatus((current) => ({ ...current, state: "failed", message }));
      }),
    ];
    return () => subscriptions.forEach((subscription) => subscription.remove());
    // تبدأ اشتراكات الجسر مرة واحدة فقط؛ دوال الشاشة المعلنة هنا مرفوعة ولا تتغير بين عمليات العرض.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function refreshStatus() {
    try {
      const next = await mnnLocalAi.getModelStatus();
      setStatus(next);
      if (next.state === "ready") await initializeModel();
    } catch (error) {
      setStatus((current) => ({ ...current, state: "failed", message: error instanceof Error ? error.message : "تعذر فحص حالة النموذج" }));
    }
  }

  async function loadRecommendedModels() {
    try {
      setRecommendedModels(await mnnLocalAi.getRecommendedGgufModels());
    } catch {
      setRecommendedModels([]);
    }
  }

  async function initializeModel() {
    setStatus((current) => ({ ...current, state: "loading" }));
    try {
      const timing = await mnnLocalAi.initializeModel();
      setStatus((current) => ({ ...current, state: "ready", message: `تحميل ${timing.loadMs}ms · warm-up ${timing.warmupMs}ms` }));
    } catch (error) {
      setStatus((current) => ({ ...current, state: "failed", message: error instanceof Error ? error.message : "تعذر تحضير النموذج" }));
    }
  }

  async function refreshMetrics() {
    try {
      setMetrics(await mnnLocalAi.getPerformanceMetrics());
    } catch {
      // تبقى آخر قياسات معروضة إذا كانت نسخة Android لا تدعم الميزة بعد.
    }
  }

  function startDownload() {
    try {
      setStatus((current) => ({ ...current, state: "downloading", message: undefined }));
      mnnLocalAi.startModelDownload();
    } catch (error) {
      setStatus((current) => ({ ...current, state: "failed", message: error instanceof Error ? error.message : "تعذر بدء التنزيل" }));
    }
  }

  async function importGguf() {
    try {
      const selection = await DocumentPicker.getDocumentAsync({ type: "*/*", copyToCacheDirectory: false, multiple: false });
      if (selection.canceled) return;
      const asset = selection.assets[0];
      if (!asset.name.toLowerCase().endsWith(".gguf")) {
        Alert.alert("صيغة غير مدعومة", "اختر ملف نموذج بامتداد .gguf. لا يمكن لمحرك GGUF فتح ملفات MNN أو ONNX.");
        return;
      }
      if (asset.size && asset.size < 16 * 1024 * 1024) {
        Alert.alert("ملف صغير جدًا", "لا يبدو الملف المحدد نموذج GGUF كاملاً.");
        return;
      }
      setIsImporting(true);
      setChatStarted(false);
      setStatus((current) => ({ ...current, state: "loading", message: "يجري استيراد نموذج GGUF والتحقق من رأسه" }));
      await mnnLocalAi.importGguf(asset.uri, asset.name, asset.size ?? 0);
      await refreshStatus();
    } catch (error) {
      setStatus((current) => ({ ...current, state: "failed", message: error instanceof Error ? error.message : "تعذر استيراد نموذج GGUF" }));
    } finally {
      setIsImporting(false);
    }
  }

  function startRecommendedDownload(model: RecommendedGgufModel) {
    setChatStarted(false);
    setShowCatalog(false);
    setProgress(null);
    setStatus({ state: "downloading", downloadedBytes: 0, totalBytes: model.bytes, engine: "gguf", format: model.format, modelName: model.displayName, message: "يجري تجهيز تنزيل متدرج مع التحقق من SHA-256" });
    mnnLocalAi.startRecommendedGgufDownload(model.id);
  }

  async function sendMessage() {
    const prompt = input.trim();
    if (!prompt || status.state !== "ready" || isGenerating) return;
    const runId = `assistant-${Date.now()}`;
    activeRunId.current = runId;
    setInput("");
    setMessages((current) => [...current, { id: `user-${Date.now()}`, role: "user", content: prompt }, { id: runId, role: "assistant", content: "", pending: true }]);
    setIsGenerating(true);
    try {
      const started = await mnnLocalAi.generate(prompt, runId);
      if (!started) throw new Error("النموذج مشغول أو غير محمّل بعد");
    } catch (error) {
      activeRunId.current = null;
      setIsGenerating(false);
      setMessages((current) => current.map((message) => message.id === runId ? { ...message, content: error instanceof Error ? error.message : "تعذر بدء التوليد", pending: false } : message));
    }
  }

  function stopGeneration() {
    mnnLocalAi.stopGeneration();
  }

  function confirmDelete() {
    Alert.alert("حذف النموذج المحلي", "سيحذف التطبيق النموذج المحدد من التخزين وسيستلزم استيراده أو تنزيله مجددًا.", [
      { text: "إلغاء", style: "cancel" },
      { text: "حذف", style: "destructive", onPress: () => { mnnLocalAi.deleteModel(); setMessages([]); void refreshStatus(); } },
    ]);
  }

  if (!isNativeMnnAvailable) {
    return <NativeBuildNotice />;
  }

  if (status.state !== "ready" || !chatStarted) {
    return (
      <ScreenContainer className="px-5 py-6" edges={["top", "bottom", "left", "right"]}>
        <View style={styles.setupLayout}>
          <View style={styles.logoMark}><Text style={styles.logoGlyph}>◈</Text></View>
          <Text style={styles.title}>MNN Local AI</Text>
          <Text style={styles.subtitle}>محادثة خاصة تعمل محليًا على جهازك بعد تنزيل النموذج.</Text>
          <View style={styles.specCard}>
            <Text style={styles.specTitle}>{status.modelName ?? MODEL_SPEC.displayName}</Text>
            <Text style={styles.specText}>{status.engine === "gguf" ? `${status.format ?? "GGUF"} مستورد محليًا؛ يُشغّل عبر llama.cpp دون رفع الملف إلى خادم.` : "تنزيل متدرج مع استئناف تلقائي والتحقق من SHA-256 لكل ملف."}</Text>
            <Text style={styles.specText}>المساحة: {formatBytes(status.totalBytes || MODEL_TOTAL_BYTES)} · ذاكرة موصى بها: {status.engine === "gguf" ? "4GB+ بحسب النموذج" : `${MODEL_SPEC.recommendedRamGb}GB`}</Text>
          </View>
          <StatusPanel status={status} progress={progress} percent={percent} />
          {status.state === "ready" ? (
            <PrimaryButton label="بدء محادثة" onPress={() => setChatStarted(true)} />
          ) : status.state === "missing" || status.state === "failed" ? (
            <View style={{ width: "100%", gap: 10 }}><PrimaryButton label={status.state === "failed" ? "إعادة محاولة تنزيل MNN" : "تنزيل نموذج MNN المدمج"} onPress={startDownload} /><Pressable accessibilityRole="button" accessibilityLabel="فتح كتالوج نماذج GGUF" onPress={() => setShowCatalog(true)} style={({ pressed }) => [detailStyles.secondaryButton, pressed && styles.pressed]}><Text style={detailStyles.secondaryButtonText}>استعراض نماذج GGUF الموصى بها</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel="استيراد نموذج GGUF" disabled={isImporting} onPress={() => void importGguf()} style={({ pressed }) => [detailStyles.secondaryButton, pressed && styles.pressed, isImporting && { opacity: 0.6 }]}><Text style={detailStyles.secondaryButtonText}>{isImporting ? "يجري استيراد GGUF" : "استيراد ملف GGUF من الجهاز"}</Text></Pressable></View>
          ) : status.state === "downloading" || status.state === "verifying" || status.state === "loading" || status.state === "warming" ? (
            <View style={styles.pendingButton}><ActivityIndicator color="#FFFFFF" /><Text style={styles.pendingButtonText}>يجري تجهيز النموذج</Text></View>
          ) : null}
          {status.state === "failed" ? <Text style={styles.errorText}>{status.message}</Text> : null}
          <ModelCatalogSheet visible={showCatalog} models={recommendedModels} onClose={() => setShowCatalog(false)} onSelect={startRecommendedDownload} />
        </View>
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer className="flex-1" edges={["top", "left", "right"]}>
      <View style={styles.header}>
        <View><Text style={styles.headerTitle}>MNN Local AI</Text><Text style={styles.headerStatus}>جاهز عبر {status.engine === "gguf" ? "GGUF / llama.cpp" : "MNN"} {status.message ? `· ${status.message}` : ""}</Text></View>
        <Pressable accessibilityRole="button" accessibilityLabel="تفاصيل النموذج المحلي" onPress={() => { setShowDetails(true); void refreshMetrics(); }} style={({ pressed }) => [styles.headerAction, pressed && styles.pressed]}><Text style={styles.headerActionText}>إدارة</Text></Pressable>
      </View>
      <FlatList
        data={messages}
        keyExtractor={(message) => message.id}
        contentContainerStyle={styles.messageList}
        renderItem={({ item }) => <MessageBubble message={item} />}
      />
      <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} keyboardVerticalOffset={70}>
        <View style={styles.composer}>
          <TextInput value={input} onChangeText={setInput} editable={!isGenerating} placeholder="اكتب رسالتك…" placeholderTextColor="#74809A" multiline textAlign="right" style={styles.input} returnKeyType="send" onSubmitEditing={() => void sendMessage()} />
          <Pressable accessibilityRole="button" accessibilityLabel={isGenerating ? "إيقاف التوليد" : "إرسال"} onPress={isGenerating ? stopGeneration : () => void sendMessage()} style={({ pressed }) => [styles.sendButton, pressed && styles.pressed]}>
            <Text style={styles.sendButtonText}>{isGenerating ? "■" : "↑"}</Text>
          </Pressable>
        </View>
      </KeyboardAvoidingView>
      <ModelDetailsSheet visible={showDetails} status={status} metrics={metrics} onClose={() => setShowDetails(false)} onRecheck={() => { setShowDetails(false); void refreshStatus(); void refreshMetrics(); }} onDelete={confirmDelete} />
    </ScreenContainer>
  );
}

function StatusPanel({ status, progress, percent }: { status: ModelStatus; progress: NativeDownloadProgress | null; percent: number }) {
  const label = status.state === "downloading" ? "يجري تنزيل النموذج" : status.state === "verifying" ? "يجري التحقق من الملف" : status.state === "loading" ? "يجري تحميل الأوزان" : status.state === "warming" ? "يجري warm-up" : "النموذج غير محمّل";
  return <View style={styles.statusPanel}><View style={styles.statusRow}><Text style={styles.statusLabel}>{label}</Text><Text style={styles.statusValue}>{percent}%</Text></View><View style={styles.progressTrack}><View style={[styles.progressFill, { width: `${percent}%` }]} /></View><Text style={styles.statusDetails}>{progress ? `${progress.currentFile} · ${formatBytes(progress.downloadedBytes)} من ${formatBytes(progress.totalBytes)}` : status.message ?? "يمكن استئناف التنزيل من آخر بايت مكتمل."}</Text></View>;
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === "user";
  return <View style={[styles.messageRow, isUser ? styles.userRow : styles.assistantRow]}><View style={[styles.messageBubble, isUser ? styles.userBubble : styles.assistantBubble]}><Text style={[styles.messageText, isUser ? styles.userText : styles.assistantText]}>{message.content || (message.pending ? "…" : "")}</Text></View></View>;
}

function ModelDetailsSheet({ visible, status, metrics, onClose, onRecheck, onDelete }: { visible: boolean; status: ModelStatus; metrics: PerformanceMetrics | null; onClose: () => void; onRecheck: () => void; onDelete: () => void }) {
  const stateLabel = status.state === "ready" ? "جاهز ومحلي" : status.state === "failed" ? "يتطلب معالجة" : "قيد التجهيز";
  const timing = metrics?.hasGeneration ? `${Math.round(metrics.generationMs)} ms${metrics.stopped ? " · تم الإيقاف" : ""}` : "أرسل رسالة لبدء القياس";
  const rate = metrics?.hasGeneration ? `${metrics.stepsPerSecond.toFixed(1)} خطوة/ث` : "—";
  return <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}><View style={detailStyles.backdrop}><View style={detailStyles.sheet}><View style={detailStyles.handle} /><Text style={detailStyles.title}>تفاصيل النموذج</Text><DetailRow label="النموذج" value={status.modelName ?? MODEL_SPEC.displayName} /><DetailRow label="المحرك والصيغة" value={status.engine === "gguf" ? "llama.cpp · GGUF" : "MNN · MNN LLM"} /><DetailRow label="الحالة" value={stateLabel} /><DetailRow label="التخزين المحلي" value={`${formatBytes(status.downloadedBytes)} من ${formatBytes(status.totalBytes)}`} /><DetailRow label="النزاهة" value={status.engine === "gguf" ? "فحص ترويسة GGUF عند الاستيراد" : "SHA-256 لكل ملف"} /><View style={detailStyles.metricsBlock}><Text style={detailStyles.metricsTitle}>أداء آخر توليد</Text><DetailRow label="زمن التوليد" value={timing} /><DetailRow label="خطوات التوليد" value={metrics?.hasGeneration ? `${metrics.generatedSteps}` : "—"} /><DetailRow label="معدل التوليد" value={rate} /><DetailRow label="ذاكرة العملية (PSS)" value={formatKilobytes(metrics?.totalPssKb)} /><DetailRow label="ذاكرة المحرك الأصلية" value={formatKilobytes(metrics?.nativePssKb)} /><DetailRow label="ذاكرة Java heap" value={formatKilobytes(metrics?.javaHeapUsedKb)} /></View><DetailRow label="التوافق" value={status.engine === "gguf" ? "GGUF للإصدار 2 أو 3 · Android 8.0+" : `Android ${MODEL_SPEC.minimumAndroidApi}+ · ${MODEL_SPEC.recommendedRamGb}GB RAM موصى بها`} /><View style={detailStyles.actions}><Pressable accessibilityRole="button" onPress={onRecheck} style={({ pressed }) => [detailStyles.secondaryButton, pressed && styles.pressed]}><Text style={detailStyles.secondaryButtonText}>تحديث القياسات</Text></Pressable><Pressable accessibilityRole="button" onPress={() => { onClose(); onDelete(); }} style={({ pressed }) => [detailStyles.dangerButton, pressed && styles.pressed]}><Text style={detailStyles.dangerButtonText}>حذف النموذج</Text></Pressable></View><Pressable accessibilityRole="button" onPress={onClose} style={({ pressed }) => [detailStyles.closeButton, pressed && styles.pressed]}><Text style={detailStyles.closeButtonText}>إغلاق</Text></Pressable></View></View></Modal>;
}

function ModelCatalogSheet({ visible, models, onClose, onSelect }: { visible: boolean; models: RecommendedGgufModel[]; onClose: () => void; onSelect: (model: RecommendedGgufModel) => void }) {
  return <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}><View style={detailStyles.backdrop}><View style={[detailStyles.sheet, { maxHeight: "82%" }]}><View style={detailStyles.handle} /><Text style={detailStyles.title}>نماذج GGUF الموصى بها</Text><Text style={detailStyles.catalogIntro}>تُنزل النماذج إلى جهازك مع استئناف النقل والتحقق من SHA-256. اختر نموذجًا يناسب ذاكرة هاتفك.</Text><FlatList data={models} keyExtractor={(item) => item.id} contentContainerStyle={{ gap: 10 }} renderItem={({ item }) => <View style={detailStyles.catalogCard}><Text style={detailStyles.catalogName}>{item.displayName}</Text><Text style={detailStyles.catalogDescription}>{item.description}</Text><View style={detailStyles.catalogMeta}><Text style={detailStyles.catalogMetaText}>{formatBytes(item.bytes)}</Text><Text style={detailStyles.catalogMetaText}>{item.format} · RAM {item.recommendedRamGb}GB+</Text></View><Pressable accessibilityRole="button" onPress={() => onSelect(item)} style={({ pressed }) => [detailStyles.catalogButton, pressed && styles.pressed]}><Text style={detailStyles.catalogButtonText}>تنزيل واستخدام هذا النموذج</Text></Pressable></View>} ListEmptyComponent={<Text style={detailStyles.catalogDescription}>يجري تحميل الكتالوج…</Text>} /><Pressable accessibilityRole="button" onPress={onClose} style={({ pressed }) => [detailStyles.closeButton, pressed && styles.pressed]}><Text style={detailStyles.closeButtonText}>إغلاق</Text></Pressable></View></View></Modal>;
}

function formatKilobytes(value?: number) {
  return value && value > 0 ? `${(value / 1024).toFixed(1)} MB` : "غير متاح بعد";
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return <View style={detailStyles.row}><Text style={detailStyles.value}>{value}</Text><Text style={detailStyles.label}>{label}</Text></View>;
}

function PrimaryButton({ label, onPress }: { label: string; onPress: () => void }) {
  return <Pressable accessibilityRole="button" onPress={onPress} style={({ pressed }) => [styles.primaryButton, pressed && styles.pressed]}><Text style={styles.primaryButtonText}>{label}</Text></Pressable>;
}

function NativeBuildNotice() {
  return <ScreenContainer className="p-6" edges={["top", "bottom", "left", "right"]}><View style={styles.noticeLayout}><View style={styles.logoMark}><Text style={styles.logoGlyph}>◈</Text></View><Text style={styles.title}>MNN Local AI</Text><Text style={styles.subtitle}>تظهر هذه المعاينة واجهة التطبيق فقط. يحتاج الاستدلال المحلي إلى ملف APK مخصص يتضمن طبقة MNN الأصلية.</Text></View></ScreenContainer>;
}

const detailStyles = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: "flex-end", backgroundColor: "rgba(11, 16, 32, 0.42)" },
  sheet: { backgroundColor: "#F7F9FC", borderTopLeftRadius: 26, borderTopRightRadius: 26, paddingHorizontal: 20, paddingBottom: 28, paddingTop: 10, gap: 12 },
  handle: { width: 40, height: 5, borderRadius: 3, backgroundColor: "#C9D4E5", alignSelf: "center", marginBottom: 4 },
  title: { fontSize: 20, fontWeight: "800", color: "#152033", textAlign: "right", marginBottom: 4 },
  metricsBlock: { backgroundColor: "#ECF2FF", borderRadius: 16, paddingHorizontal: 14, paddingVertical: 4, marginVertical: 2 },
  metricsTitle: { color: "#0B5FFF", fontSize: 14, fontWeight: "800", textAlign: "right", marginTop: 8 },
  catalogIntro: { color: "#58657D", fontSize: 13, lineHeight: 19, textAlign: "right" },
  catalogCard: { borderRadius: 16, backgroundColor: "#FFFFFF", borderColor: "#DCE5F0", borderWidth: 1, padding: 14, gap: 8 },
  catalogName: { color: "#152033", fontSize: 15, fontWeight: "800", textAlign: "right" },
  catalogDescription: { color: "#58657D", fontSize: 12, lineHeight: 18, textAlign: "right" },
  catalogMeta: { flexDirection: "row-reverse", justifyContent: "space-between", gap: 8 },
  catalogMetaText: { color: "#4A5B78", fontSize: 11, fontWeight: "700" },
  catalogButton: { minHeight: 42, borderRadius: 12, backgroundColor: "#0B5FFF", alignItems: "center", justifyContent: "center", marginTop: 2 },
  catalogButtonText: { color: "#FFFFFF", fontSize: 13, fontWeight: "800" },
  row: { flexDirection: "row-reverse", justifyContent: "space-between", gap: 12, paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: "#DCE5F0" },
  label: { color: "#58657D", fontSize: 13, textAlign: "right" },
  value: { color: "#1C2941", fontSize: 13, fontWeight: "600", textAlign: "left", flex: 1 },
  actions: { flexDirection: "row-reverse", gap: 10, marginTop: 10 },
  secondaryButton: { flex: 1, minHeight: 46, borderRadius: 14, backgroundColor: "#E7EEFC", alignItems: "center", justifyContent: "center" },
  secondaryButtonText: { color: "#0B5FFF", fontWeight: "800" },
  dangerButton: { flex: 1, minHeight: 46, borderRadius: 14, backgroundColor: "#FDE8E8", alignItems: "center", justifyContent: "center" },
  dangerButtonText: { color: "#C62828", fontWeight: "800" },
  closeButton: { minHeight: 44, borderRadius: 14, backgroundColor: "#FFFFFF", borderWidth: 1, borderColor: "#D6E0EE", alignItems: "center", justifyContent: "center" },
  closeButtonText: { color: "#36445E", fontWeight: "700" },
});

const styles = StyleSheet.create({
  setupLayout: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }, noticeLayout: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18, paddingHorizontal: 24 }, logoMark: { width: 88, height: 88, borderRadius: 28, backgroundColor: "#0B5FFF", alignItems: "center", justifyContent: "center", shadowColor: "#0B5FFF", shadowOpacity: 0.25, shadowRadius: 20, elevation: 7 }, logoGlyph: { color: "#FFFFFF", fontSize: 46, lineHeight: 56 }, title: { fontSize: 30, fontWeight: "800", color: "#152033", textAlign: "center" }, subtitle: { color: "#58657D", fontSize: 16, lineHeight: 24, textAlign: "center", maxWidth: 350 }, specCard: { width: "100%", borderRadius: 20, backgroundColor: "#FFFFFF", borderColor: "#DFE7F5", borderWidth: 1, padding: 18, gap: 7 }, specTitle: { color: "#152033", fontSize: 16, fontWeight: "700", textAlign: "right" }, specText: { color: "#58657D", fontSize: 13, lineHeight: 19, textAlign: "right" }, statusPanel: { width: "100%", padding: 16, borderRadius: 18, backgroundColor: "#ECF2FF", gap: 10 }, statusRow: { flexDirection: "row-reverse", justifyContent: "space-between" }, statusLabel: { color: "#22314D", fontWeight: "700" }, statusValue: { color: "#0B5FFF", fontWeight: "800" }, progressTrack: { backgroundColor: "#CDD9F4", height: 8, borderRadius: 4, overflow: "hidden" }, progressFill: { height: "100%", borderRadius: 4, backgroundColor: "#0B5FFF" }, statusDetails: { color: "#58657D", fontSize: 12, textAlign: "right" }, primaryButton: { width: "100%", minHeight: 52, borderRadius: 16, backgroundColor: "#0B5FFF", alignItems: "center", justifyContent: "center" }, primaryButtonText: { color: "#FFFFFF", fontWeight: "800", fontSize: 16 }, pendingButton: { width: "100%", minHeight: 52, borderRadius: 16, backgroundColor: "#577DCB", flexDirection: "row-reverse", gap: 10, alignItems: "center", justifyContent: "center" }, pendingButtonText: { color: "#FFFFFF", fontWeight: "800", fontSize: 16 }, errorText: { color: "#C62828", textAlign: "center", fontSize: 13, lineHeight: 20 }, header: { paddingHorizontal: 20, paddingVertical: 14, backgroundColor: "#F7F9FC", borderBottomColor: "#E1E8F2", borderBottomWidth: StyleSheet.hairlineWidth, flexDirection: "row-reverse", justifyContent: "space-between", alignItems: "center" }, headerTitle: { color: "#152033", fontSize: 18, fontWeight: "800", textAlign: "right" }, headerStatus: { color: "#16794B", fontSize: 12, marginTop: 3, textAlign: "right" }, headerAction: { paddingHorizontal: 13, paddingVertical: 9, borderRadius: 12, backgroundColor: "#E7EEFC" }, headerActionText: { color: "#0B5FFF", fontWeight: "700", fontSize: 13 }, messageList: { padding: 16, gap: 10 }, messageRow: { flexDirection: "row", width: "100%" }, userRow: { justifyContent: "flex-end" }, assistantRow: { justifyContent: "flex-start" }, messageBubble: { maxWidth: "84%", borderRadius: 18, paddingHorizontal: 14, paddingVertical: 11 }, userBubble: { backgroundColor: "#0B5FFF", borderBottomRightRadius: 4 }, assistantBubble: { backgroundColor: "#FFFFFF", borderColor: "#E0E7F0", borderWidth: 1, borderBottomLeftRadius: 4 }, messageText: { fontSize: 16, lineHeight: 24, textAlign: "right" }, userText: { color: "#FFFFFF" }, assistantText: { color: "#1C2941" }, composer: { flexDirection: "row-reverse", alignItems: "flex-end", gap: 10, paddingHorizontal: 14, paddingTop: 10, paddingBottom: 14, backgroundColor: "#F7F9FC", borderTopColor: "#E1E8F2", borderTopWidth: StyleSheet.hairlineWidth }, input: { flex: 1, minHeight: 48, maxHeight: 120, borderRadius: 18, paddingHorizontal: 15, paddingVertical: 11, backgroundColor: "#FFFFFF", borderColor: "#D6E0EE", borderWidth: 1, color: "#152033", fontSize: 16, lineHeight: 22 }, sendButton: { width: 48, height: 48, borderRadius: 24, backgroundColor: "#0B5FFF", alignItems: "center", justifyContent: "center" }, sendButtonText: { color: "#FFFFFF", fontSize: 22, fontWeight: "800", lineHeight: 24 }, pressed: { transform: [{ scale: 0.97 }], opacity: 0.88 },
});
