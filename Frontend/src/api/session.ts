import * as SecureStore from "expo-secure-store";
const key = "baotin-refresh";
export const readRefreshToken = () => SecureStore.getItemAsync(key);
export const saveRefreshToken = (token: string) => SecureStore.setItemAsync(key, token);
export const clearRefreshToken = () => SecureStore.deleteItemAsync(key);
