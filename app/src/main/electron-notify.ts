import { Notification } from 'electron'
import type { Notify } from './notifications'

export const electronNotify: Notify = (title, body) => {
  if (Notification.isSupported()) new Notification({ title, body }).show()
}
