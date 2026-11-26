import { type ReactNode, useEffect, useRef, useState } from "react";
import { AccessibilityInfo, Animated, Easing } from "react-native";

export function useReducedMotion() {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    AccessibilityInfo.isReduceMotionEnabled().then(setReduced).catch(() => undefined);
    const subscription = AccessibilityInfo.addEventListener("reduceMotionChanged", setReduced);
    return () => subscription.remove();
  }, []);
  return reduced;
}

export function MotionScreen({ children, motionKey }: { children: ReactNode; motionKey: string }) {
  const progress = useRef(new Animated.Value(1)).current;
  const reduced = useReducedMotion();

  useEffect(() => {
    progress.setValue(0);
    Animated.timing(progress, {
      toValue: 1,
      duration: reduced ? 80 : 180,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true
    }).start();
  }, [motionKey, progress, reduced]);

  return (
    <Animated.View style={{ opacity: progress }}>
      {children}
    </Animated.View>
  );
}

export function Reveal({ children, revealKey }: { children: ReactNode; revealKey: string }) {
  const progress = useRef(new Animated.Value(0)).current;
  const reduced = useReducedMotion();
  useEffect(() => {
    progress.setValue(0);
    Animated.timing(progress, { toValue: 1, duration: reduced ? 80 : 180, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start();
  }, [progress, reduced, revealKey]);
  return <Animated.View style={{ opacity: progress }}>{children}</Animated.View>;
}
