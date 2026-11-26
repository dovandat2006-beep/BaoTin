import { faArrowLeft } from "@fortawesome/free-solid-svg-icons/faArrowLeft";
import { faArrowRight } from "@fortawesome/free-solid-svg-icons/faArrowRight";
import { faFileArrowUp } from "@fortawesome/free-solid-svg-icons/faFileArrowUp";
import { faPaperclip } from "@fortawesome/free-solid-svg-icons/faPaperclip";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { useState } from "react";
import { ScrollView, Text, View } from "react-native";
import { reportCategories } from "../data/mock";
import { scanTypeLabels, scanTypes, type ScanType } from "../lib/risk";
import { useAppTheme } from "../theme/AppTheme";
import { DemoDataNotice } from "./DemoDataNotice";
import { Button, FilterBar, Input, Sheet } from "./ui";

const categoryLabels: Record<(typeof reportCategories)[number], string> = {
  FAKE_SHIPPER: "Giả mạo giao hàng", BANK_IMPERSONATION: "Mạo danh ngân hàng", GOV_IMPERSONATION: "Mạo danh cơ quan nhà nước", PHISHING: "Lừa lấy thông tin", INVESTMENT_SCAM: "Lừa đảo đầu tư", JOB_SCAM: "Lừa đảo việc làm", PRIZE_SCAM: "Lừa đảo trúng thưởng", ACCOUNT_THEFT: "Đánh cắp tài khoản", SOCIAL_IMPERSONATION: "Mạo danh mạng xã hội", MARKETPLACE_SCAM: "Lừa đảo mua bán", OTHER: "Khác"
};
export type NewReport = { target: string; targetType: ScanType; category: string; title: string; description: string; evidenceCount: number };

export function ReportComposer({ visible, demo = false, onClose, onSubmit }: { visible: boolean; demo?: boolean; onClose: () => void; onSubmit: (report: NewReport) => void }) {
  const { colors } = useAppTheme();
  const [step, setStep] = useState(1);
  const [targetType, setTargetType] = useState<ScanType>("URL");
  const [target, setTarget] = useState("");
  const [category, setCategory] = useState<(typeof reportCategories)[number]>("PHISHING");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [evidenceCount, setEvidenceCount] = useState(0);
  const stepValid = step === 1 ? target.trim().length > 5 : title.trim().length > 5 && description.trim().length > 15;

  const submit = () => {
    onSubmit({ target: target.trim(), targetType, category, title: title.trim(), description: description.trim(), evidenceCount });
    setStep(1); setTarget(""); setTitle(""); setDescription(""); setEvidenceCount(0); onClose();
  };
  const footer = <View className="flex-row gap-3">{step === 2 ? <Button label="Quay lại" icon={faArrowLeft} variant="outline" onPress={() => setStep(1)} /> : null}<Button className="min-w-0 flex-1" label={step === 1 ? "Tiếp tục" : demo ? "Lưu bản nháp minh họa" : "Gửi tố giác"} trailingIcon={faArrowRight} disabled={!stepValid} onPress={() => step === 1 ? setStep(2) : submit()} /></View>;

  return (
    <Sheet visible={visible} title="Tố giác lừa đảo" description={step === 1 ? "Đối tượng và phân loại" : "Mô tả và bằng chứng"} closeLabel="Đóng biểu mẫu báo cáo" onClose={onClose} footer={footer}>
      <ScrollView contentContainerStyle={{ padding: 20 }} keyboardShouldPersistTaps="handled">
        {demo ? <View className="mb-5"><DemoDataNotice description="Biểu mẫu chỉ tạo bản nháp minh họa trong phiên này; không gửi dữ liệu hay tệp đính kèm tới máy chủ." /></View> : null}
        {step === 1 ? <View className="gap-5">
          <FilterBar label="Loại đối tượng" options={scanTypes.map((item) => ({ value: item, label: scanTypeLabels[item] }))} value={targetType} onChange={setTargetType} />
          <Input label="Đối tượng" accessibilityLabel="Đối tượng bị báo cáo" value={target} onChangeText={setTarget} autoCapitalize="none" placeholder="Nhập giá trị cần báo cáo" />
          <FilterBar label="Danh mục" options={reportCategories.map((item) => ({ value: item, label: categoryLabels[item] }))} value={category} onChange={setCategory} />
        </View> : <View className="gap-5">
          <Input label="Tiêu đề" accessibilityLabel="Tiêu đề báo cáo" value={title} onChangeText={setTitle} placeholder="Tóm tắt sự việc" />
          <Input label="Mô tả" accessibilityLabel="Mô tả báo cáo" value={description} onChangeText={setDescription} multiline numberOfLines={5} placeholder="Mô tả thời điểm, cách tiếp cận và hành động đã xảy ra" />
          <View className="rounded-[10px] border border-dashed border-control p-4 dark:border-dark-control"><View className="flex-row items-center"><View className="w-5"><FontAwesomeIcon icon={faPaperclip} size={14} color={colors.accent} /></View><Text className="ml-2 font-medium text-sm text-ink dark:text-dark-ink">Bằng chứng: {evidenceCount} / 5 file</Text></View><Text className="mt-2 font-sans text-xs leading-5 text-muted dark:text-dark-muted">JPG, PNG, WEBP hoặc PDF. Tối đa 10 MB mỗi file.</Text><Button className="mt-3" label="Thêm file giả lập" icon={faFileArrowUp} variant="outline" disabled={evidenceCount >= 5} onPress={() => setEvidenceCount((count) => Math.min(5, count + 1))} /></View>
        </View>}
      </ScrollView>
    </Sheet>
  );
}
