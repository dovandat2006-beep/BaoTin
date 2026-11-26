import AsyncStorage from "@react-native-async-storage/async-storage";
import { createContext, type ReactNode, useContext, useEffect, useMemo } from "react";
import { useColorScheme } from "nativewind";
import { darkColors, lightColors } from "./palettes";

const STORAGE_KEY = "baotin.color-scheme";

type ThemeContextValue = {
  isDark: boolean;
  colors: Record<keyof typeof lightColors, string>;
  toggleTheme: () => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function AppThemeProvider({ children }: { children: ReactNode }) {
  const { colorScheme, setColorScheme } = useColorScheme();
  const isDark = colorScheme === "dark";

  useEffect(() => {
    AsyncStorage.getItem(STORAGE_KEY).then((stored) => {
      if (stored === "light" || stored === "dark") setColorScheme(stored);
    }).catch(() => undefined);
  }, []);

  const value = useMemo<ThemeContextValue>(() => ({
    isDark,
    colors: isDark ? darkColors : lightColors,
    toggleTheme: () => {
      const next = isDark ? "light" : "dark";
      setColorScheme(next);
      AsyncStorage.setItem(STORAGE_KEY, next).catch(() => undefined);
    }
  }), [isDark, setColorScheme]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useAppTheme() {
  const value = useContext(ThemeContext);
  if (!value) throw new Error("useAppTheme must be used inside AppThemeProvider");
  return value;
}

