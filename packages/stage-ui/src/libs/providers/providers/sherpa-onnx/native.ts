import type { PluginListenerHandle } from '@capacitor/core'

import { Capacitor, registerPlugin } from '@capacitor/core'

/** Native model state reported by the Android sherpa-onnx plugin. */
export interface SherpaAsrStatus {
  ready: boolean
  loaded: boolean
  downloading: boolean
  downloadedBytes: number
  totalBytes: number
}

export interface SherpaAsrDownloadProgress {
  model: string
  file: string
  downloadedBytes: number
  totalBytes: number
  percent: number
}

export interface SherpaAsrTranscribeOptions {
  model: string
  /** Base64 little-endian 16-bit mono PCM. */
  audio: string
  sampleRate: number
  language: string
}

export interface SherpaAsrTranscribeResult {
  text: string
  language: string
  duration: number
}

interface SherpaAsrPlugin {
  getStatus: (options: { model: string }) => Promise<SherpaAsrStatus>
  downloadModel: (options: { model: string }) => Promise<void>
  cancelDownload: () => Promise<void>
  deleteModel: (options: { model: string }) => Promise<void>
  transcribe: (options: SherpaAsrTranscribeOptions) => Promise<SherpaAsrTranscribeResult>
  unload: () => Promise<void>
  addListener: (
    eventName: 'downloadProgress',
    listener: (progress: SherpaAsrDownloadProgress) => void,
  ) => Promise<PluginListenerHandle>
}

/** Bridge to `SherpaAsrPlugin.kt` in the stage-pocket Android app. */
export const SherpaAsr = registerPlugin<SherpaAsrPlugin>('SherpaAsr')

/** The plugin exists only in the stage-pocket Android build. */
export function isSherpaAsrAvailable(): boolean {
  return Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('SherpaAsr')
}

export type { PluginListenerHandle }
