import { Image, Platform, Text } from "react-native";

/** Shared brand slot: supplied web artwork; existing native text remains unchanged. */
export function BrandWordmark() {
  if (Platform.OS !== "web") return <Text numberOfLines={1} className="font-display text-xl text-md-on-surface dark:text-dark-md-on-surface">BaoTin</Text>;
  return <Image
    source={require("../../assets/branding/web-wordmark.png")}
    accessibilityLabel="BaoTin"
    resizeMode="contain"
    style={{ width: 112, height: 36 }}
  />;
}
