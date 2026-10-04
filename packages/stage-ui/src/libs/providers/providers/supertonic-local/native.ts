import type { PluginListenerHandle } from '@capacitor/core'

import { Capacitor, registerPlugin } from '@capacitor/core'

/** Native model state reported by the Android Supertonic plugin. */
export interface SupertonicStatus {
  ready: boolean
  loaded: boolean
  downloading: boolean
  downloadedBytes: number
  totalBytes: number
}

export interface SupertonicDownloadProgress {
  file: string
  fileIndex: number
  fileCount: number
  downloadedBytes: number
  totalBytes: number
  percent: number
}

export interface SupertonicSynthesizeOptions {
  text: string
  voice: string
  lang: string
  speed: number
  steps: number
}

export interface SupertonicSynthesizeResult {
  /** Base64 16-bit PCM mono WAV. */
  audio: string
  sampleRate: number
  duration: number
}

interface SupertonicTtsPlugin {
  getStatus: () => Promise<SupertonicStatus>
  listVoices: () => Promise<{ voices: string[], languages: string[] }>
  downloadModels: () => Promise<void>
  cancelDownload: () => Promise<void>
  synthesize: (options: SupertonicSynthesizeOptions) => Promise<SupertonicSynthesizeResult>
  unload: () => Promise<void>
  deleteModels: () => Promise<void>
  addListener: (
    eventName: 'downloadProgress',
    listener: (progress: SupertonicDownloadProgress) => void,
  ) => Promise<PluginListenerHandle>
}

/** Bridge to `SupertonicTtsPlugin.kt` in the stage-pocket Android app. */
export const SupertonicTts = registerPlugin<SupertonicTtsPlugin>('SupertonicTts')

/** The plugin exists only in the stage-pocket Android build. */
export function isSupertonicAvailable(): boolean {
  return Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('SupertonicTts')
}

export function decodeBase64(value: string): ArrayBuffer {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++)
    bytes[i] = binary.charCodeAt(i)

  return bytes.buffer
}

export type { PluginListenerHandle }
