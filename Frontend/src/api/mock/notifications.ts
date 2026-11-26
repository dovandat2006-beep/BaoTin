import { notifications } from "../../data/mock";

export type MockNotification = {
  id: string;
  type: string;
  title: string;
  body: string;
  time: string;
  read: boolean;
};

export type MockNotificationPreferences = {
  risk: boolean;
  report: boolean;
  push: boolean;
  inApp: boolean;
};

export type MockNotificationsClient = {
  snapshot(): MockNotification[];
  snapshotPreferences(): MockNotificationPreferences;
  list(): Promise<MockNotification[]>;
  markRead(id: string): Promise<void>;
  markAllRead(): Promise<void>;
  preferences(): Promise<MockNotificationPreferences>;
  updatePreference(key: keyof MockNotificationPreferences, value: boolean): Promise<MockNotificationPreferences>;
};

const initialPreferences: MockNotificationPreferences = { risk: true, report: true, push: true, inApp: true };

/**
 * Notification demo source. It never reaches the network and is intentionally
 * shaped like the current UI record, not like a verified backend contract.
 */
export function createMockNotifications(seed: MockNotification[] = notifications): MockNotificationsClient {
  let items = seed.map((item) => ({ ...item }));
  let preferences = { ...initialPreferences };

  const snapshot = () => items.map((item) => ({ ...item }));

  return {
    snapshot,
    snapshotPreferences: () => ({ ...preferences }),

    async list(): Promise<MockNotification[]> {
      return snapshot();
    },

    async markRead(id: string): Promise<void> {
      items = items.map((item) => item.id === id ? { ...item, read: true } : item);
    },

    async markAllRead(): Promise<void> {
      items = items.map((item) => ({ ...item, read: true }));
    },

    async preferences(): Promise<MockNotificationPreferences> {
      return { ...preferences };
    },

    async updatePreference(key, value): Promise<MockNotificationPreferences> {
      preferences = { ...preferences, [key]: value };
      return { ...preferences };
    }
  };
}
