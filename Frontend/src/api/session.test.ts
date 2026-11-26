import * as SecureStore from "expo-secure-store";
import { readRefreshToken, saveRefreshToken, clearRefreshToken } from "./session";
jest.mock("expo-secure-store", () => ({ setItemAsync: jest.fn(), getItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
test("native refresh credentials stay in secure storage", async () => {
 await saveRefreshToken("secret"); await readRefreshToken(); await clearRefreshToken();
 expect(SecureStore.setItemAsync).toHaveBeenCalledWith("baotin-refresh", "secret");
 expect(SecureStore.getItemAsync).toHaveBeenCalledWith("baotin-refresh");
 expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith("baotin-refresh");
});
