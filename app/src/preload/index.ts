import { contextBridge, ipcRenderer } from 'electron'
import { IPC, type ApiRequest, type AppState, type Settings, type WhfBridge } from '../shared/ipc'

const bridge: WhfBridge = {
  request: (req: ApiRequest) => ipcRenderer.invoke(IPC.apiRequest, req),
  getSettings: () => ipcRenderer.invoke(IPC.settingsGet),
  setSettings: (patch: Partial<Settings>) => ipcRenderer.invoke(IPC.settingsSet, patch),
  copilotLogin: () => ipcRenderer.invoke(IPC.copilotLogin),
  getState: () => ipcRenderer.invoke(IPC.appState),
  onStateChanged: (listener: (state: AppState) => void) => {
    const handler = (_e: unknown, state: AppState): void => listener(state)
    ipcRenderer.on(IPC.appStateChanged, handler)
    return () => ipcRenderer.removeListener(IPC.appStateChanged, handler)
  },
}

contextBridge.exposeInMainWorld('whf', bridge)
