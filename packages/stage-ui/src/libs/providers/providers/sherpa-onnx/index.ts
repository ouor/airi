import { errorMessageFrom } from '@moeru/std'
import { toPCM16FromFloat32 } from '@proj-airi/audio/encoding'
import { isStageCapacitor } from '@proj-airi/stage-shared'
import { z } from 'zod'

import { encodeBase64 } from '../native-bridge/base64'
import { defineProvider } from '../registry'
import { isSherpaAsrAvailable, SherpaAsr } from './native'

export const SHERPA_ONNX_PROVIDER_ID = 'sherpa-onnx-transcription'

/** Sample rate that every bundled sherpa-onnx model expects. */
const MODEL_SAMPLE_RATE = 16000

export interface SherpaAsrModelInfo {
  id: string
  name: string
  description: string
  sizeMegabytes: number
}

/** Model ids must match `SherpaAsrModel.kt`. */
export const SHERPA_ONNX_MODELS: SherpaAsrModelInfo[] = [
  {
    id: 'korean-zipformer',
    name: 'Korean Zipformer',
    description: 'Korean only. Small and fast. Trained on KsponSpeech.',
    sizeMegabytes: 81,
  },
  {
    id: 'sense-voice',
    name: 'SenseVoice',
    description: 'Korean, English, Japanese, Chinese, and Cantonese. Adds punctuation.',
    sizeMegabytes: 228,
  },
  {
    id: 'qwen3-asr',
    name: 'Qwen3-ASR 0.6B',
    description: 'Korean and many other languages. Natural spacing, but takes several seconds per sentence.',
    sizeMegabytes: 941,
  },
]

/** SenseVoice gave the best accuracy for its speed in on-device tests with Korean speech. */
export const SHERPA_ONNX_DEFAULT_MODEL = 'sense-voice'

/** SenseVoice language hints. `auto` lets the model detect the language. */
export const SHERPA_ONNX_LANGUAGES = [
  { code: 'ko', title: '한국어' },
  { code: 'auto', title: 'Auto' },
  { code: 'en', title: 'English' },
  { code: 'ja', title: '日本語' },
  { code: 'zh', title: '中文' },
  { code: 'yue', title: '粵語' },
]

interface SherpaOnnxConfig {
  language?: string
}

/**
 * Decodes any recorded audio format and resamples it to 16 kHz mono.
 * `decodeAudioData` resamples to the sample rate of its context.
 */
async function toModelPcm16(file: Blob): Promise<Uint8Array> {
  const context = new OfflineAudioContext(1, 1, MODEL_SAMPLE_RATE)
  const audio = await context.decodeAudioData(await file.arrayBuffer())
  return toPCM16FromFloat32(audio.getChannelData(0))
}

function readAudioFile(init?: RequestInit): Blob {
  if (!(init?.body instanceof FormData))
    throw new Error('Invalid transcription request body')

  const file = init.body.get('file')
  if (!(file instanceof Blob))
    throw new Error('The transcription request has no audio file')

  return file
}

export const providerSherpaOnnxTranscription = defineProvider<SherpaOnnxConfig, typeof SHERPA_ONNX_PROVIDER_ID>({
  id: SHERPA_ONNX_PROVIDER_ID,
  order: 0,
  name: 'sherpa-onnx',
  nameLocalize: ({ t }) => t('settings.pages.providers.provider.sherpa-onnx-transcription.title'),
  description: 'Offline speech recognition on this Android device with sherpa-onnx.',
  descriptionLocalize: ({ t }) => t('settings.pages.providers.provider.sherpa-onnx-transcription.description'),
  tasks: ['speech-to-text', 'automatic-speech-recognition', 'asr', 'stt'],
  icon: 'i-solar:microphone-3-bold-duotone',
  requiresCredentials: false,
  isAvailableBy: () => isStageCapacitor() && isSherpaAsrAvailable(),
  capabilities: {
    transcription: { protocol: 'http', generateOutput: true, streamOutput: false, streamInput: false },
  },
  createProviderConfig: () => z.object({
    language: z.string().default('ko'),
  }),
  createProvider(config) {
    const language = config.language ?? 'ko'

    return {
      transcription: (model?: string) => ({
        baseURL: 'http://sherpa-onnx-local/v1/',
        model: model || SHERPA_ONNX_DEFAULT_MODEL,
        fetch: async (_input: RequestInfo | URL, init?: RequestInit) => {
          const pcm = await toModelPcm16(readAudioFile(init))
          const result = await SherpaAsr.transcribe({
            model: model || SHERPA_ONNX_DEFAULT_MODEL,
            audio: encodeBase64(pcm),
            sampleRate: MODEL_SAMPLE_RATE,
            language,
          })

          return Response.json({ text: result.text })
        },
      }),
    }
  },
  validationRequiredWhen: () => false,
  validators: {
    validateConfig: [
      () => ({
        id: `${SHERPA_ONNX_PROVIDER_ID}:check-models`,
        name: 'Model files',
        validator: async () => {
          try {
            const statuses = await Promise.all(SHERPA_ONNX_MODELS.map(model => SherpaAsr.getStatus({ model: model.id })))
            if (statuses.some(status => status.ready))
              return { errors: [], reason: '', reasonKey: '', valid: true }

            const reason = 'Download a sherpa-onnx model first.'
            return { errors: [{ error: new Error(reason) }], reason, reasonKey: '', valid: false }
          }
          catch (error) {
            const reason = errorMessageFrom(error) ?? 'The sherpa-onnx plugin is not available.'
            return { errors: [{ error }], reason, reasonKey: '', valid: false }
          }
        },
      }),
    ],
  },
  extraMethods: {
    listModels: async () => SHERPA_ONNX_MODELS.map(model => ({
      id: model.id,
      name: model.name,
      provider: SHERPA_ONNX_PROVIDER_ID,
      description: model.description,
      contextLength: 0,
      deprecated: false,
    })),
  },
})
