declare module "*.css";

declare const process: {
  env: {
    EXPO_PUBLIC_API_URL?: string;
    EXPO_PUBLIC_DEMO_MODE?: string;
    EXPO_PUBLIC_WEB_API_PROXY?: string;
    EXPO_PUBLIC_OAUTH_WEB_RETURN_URI?: string;
    EXPO_PUBLIC_OAUTH_NATIVE_RETURN_URI?: string;
    EXPO_PUBLIC_OAUTH_PARENT_ORIGINS?: string;
    NODE_ENV?: "development" | "test" | "production";
  };
};
