import {Text,View} from 'react-native';
import {TopBar} from './TopBar';
/** Development-only; mount under the existing theme and safe-area providers. */
export function TopBarPreview(){return <View className="gap-6"><TopBar compact unreadCount={0} accountLabel="Tài khoản xem trước" onOpenAccount={()=>{}} onOpenNotifications={()=>{}}/><TopBar compact unreadCount={125} accountLabel="Tài khoản xem trước" onOpenAccount={()=>{}} onOpenNotifications={()=>{}}/><Text className="font-sans text-sm text-ink dark:text-dark-ink">Preview only: empty/unread, hover, keyboard focus, pressed, options and persisted theme. Loading/errors belong to the existing notification and download flows; the bar does not invent async status.</Text></View>}
