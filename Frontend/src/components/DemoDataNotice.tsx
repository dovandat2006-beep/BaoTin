import { faCircleInfo } from "@fortawesome/free-solid-svg-icons/faCircleInfo";
import { Alert } from "./ui/Alert";

/**
 * Shared source label for screens that still render in-memory demo data.
 * Passing it through one component keeps the title and accessibility shape
 * consistent without coupling the screens to the Alert implementation.
 */
export function DemoDataNotice({ description }: { description: string }) {
  return <Alert icon={faCircleInfo} tone="neutral" title="Dữ liệu minh họa" description={description} />;
}
