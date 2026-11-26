const palette = require('./color-tokens.json');
const kebab = (key) => key.replace(/[A-Z]/g, (letter) => '-' + letter.toLowerCase());
const nativePlatform = process.env.NATIVEWIND_OS && process.env.NATIVEWIND_OS !== "web";
/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: "class",
  content: ["./App.{js,jsx,ts,tsx}", "./src/**/*.{js,jsx,ts,tsx}"],
  presets: [require("nativewind/preset")],
  theme: {
    extend: {
      colors: {
        ...Object.fromEntries(Object.entries(palette.light).map(([key, value]) => [kebab(key), value])),
        ...Object.fromEntries(Object.entries(palette.dark).map(([key, value]) => [`dark-${kebab(key)}`, value]))
      },
      fontFamily: {
        sans: ["Roboto_400Regular"],
        medium: ["Roboto_500Medium"],
        bold: ["Roboto_700Bold"],
        display: ["Roboto_600SemiBold"]
      },
      borderRadius: {
        sm: "6px",
        md: "8px",
        lg: "10px",
        card: "14px",
        shell: "18px"
      }
    }
  },
  // CSS interop supports :focus on native, but drops the unsupported
  // :focus-visible condition and would otherwise apply rings unconditionally.
  // Keep the browser's keyboard-only focus-visible behavior unchanged.
  plugins: nativePlatform ? [require("tailwindcss/plugin")(({ addVariant }) => {
    addVariant("focus-visible", "&:focus");
  })] : []
};
