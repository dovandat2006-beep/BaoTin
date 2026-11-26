import { faCircleInfo } from "@fortawesome/free-solid-svg-icons/faCircleInfo";
import { EmptyState } from "../components/ui";

export function ServiceUnavailableScreen({ service, onContinue, continueLabel = "Kiểm tra URL hoặc tin nhắn" }: { service: "Search" | "Analytics" | "Reports"; onContinue: () => void; continueLabel?: string }) {
  const titles = { Search: "Tra cứu chưa được triển khai", Analytics: "Thống kê quản trị chưa được triển khai", Reports: "Đăng nhập để xem báo cáo" };
  const title = titles[service];
  const description = service === "Reports"
    ? "Đăng nhập để xem và gửi báo cáo."
    : "Vui lòng quay lại sau.";
  return <EmptyState icon={faCircleInfo} title={title} description={description} action={{ label: continueLabel, onPress: onContinue }} />;
}
