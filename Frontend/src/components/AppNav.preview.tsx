import { useState } from 'react';
import { Text, View } from 'react-native';
import { AppNav, type AppSection } from './AppNav';

/** Development-only preview: mount inside the existing theme/safe-area providers. */
export function AppNavPreview() {
  const [active, setActive] = useState<AppSection>('scan');
  return <View className="flex-1 bg-canvas dark:bg-dark-canvas">
    <View className="flex-row flex-1"><AppNav active={active} onChange={setActive} authenticated adminAllowed /><View className="flex-1 gap-4 p-5">
      <Text className="font-medium text-lg text-ink dark:text-dark-ink">Navigation states</Text>
      <Text className="font-sans text-sm text-ink dark:text-dark-ink">Default: inactive destinations. Hover: point at a destination. Focus: use Tab. Pressed: hold a destination. Selected: {active}.</Text>
      <Text className="font-sans text-sm text-muted dark:text-dark-muted">Disabled, loading and error are not navigation states in the current synchronous callback contract. Protected destinations open the existing sign-in flow; unauthorized administration is absent. Success is the selected destination, not a fabricated toast.</Text>
    </View></View>
  </View>;
}
