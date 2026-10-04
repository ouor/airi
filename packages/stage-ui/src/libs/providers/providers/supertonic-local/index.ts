import { errorMessageFrom } from '@moeru/std'
import { isStageCapacitor } from '@proj-airi/stage-shared'
import { z } from 'zod'

import { decodeBase64 } from '../native-bridge/base64'
import { defineProvider } from '../registry'
import { isSupertonicAvailable, SupertonicTts } from './native'

export const SUPERTONIC_PROVIDER_ID = 'supertonic-local'
export const SUPERTONIC_MODEL_ID = 'supertonic-3'

/** Preset voice styles that ship with the Supertonic 3 model. */
export const SUPERTONIC_VOICES = [
  { id: 'F1', name: 'Female 1', gender: 'female' },
  { id: 'F2', name: 'Female 2', gender: 'female' },
  { id: 'F3', name: 'Female 3', gender: 'female' },
  { id: 'F4', name: 'Female 4', gender: 'female' },
  { id: 'F5', name: 'Female 5', gender: 'female' },
  { id: 'M1', name: 'Male 1', gender: 'male' },
  { id: 'M2', name: 'Male 2', gender: 'male' },
  { id: 'M3', name: 'Male 3', gender: 'male' },
  { id: 'M4', name: 'Male 4', gender: 'male' },
  { id: 'M5', name: 'Male 5', gender: 'male' },
] as const

/** Language codes that Supertonic 3 accepts. `na` lets the model handle untagged text. */
export const SUPERTONIC_LANGUAGES: { code: string, title: string }[] = [
  { code: 'ko', title: '한국어' },
  { code: 'en', title: 'English' },
  { code: 'ja', title: '日本語' },
  { code: 'na', title: 'Auto' },
  { code: 'ar', title: 'العربية' },
  { code: 'bg', title: 'Български' },
  { code: 'cs', title: 'Čeština' },
  { code: 'da', title: 'Dansk' },
  { code: 'de', title: 'Deutsch' },
  { code: 'el', title: 'Ελληνικά' },
  { code: 'es', title: 'Español' },
  { code: 'et', title: 'Eesti' },
  { code: 'fi', title: 'Suomi' },
  { code: 'fr', title: 'Français' },
  { code: 'hi', title: 'हिन्दी' },
  { code: 'hr', title: 'Hrvatski' },
  { code: 'hu', title: 'Magyar' },
  { code: 'id', title: 'Bahasa Indonesia' },
  { code: 'it', title: 'Italiano' },
  { code: 'lt', title: 'Lietuvių' },
  { code: 'lv', title: 'Latviešu' },
  { code: 'nl', title: 'Nederlands' },
  { code: 'pl', title: 'Polski' },
  { code: 'pt', title: 'Português' },
  { code: 'ro', title: 'Română' },
  { code: 'ru', title: 'Русский' },
  { code: 'sk', title: 'Slovenčina' },
  { code: 'sl', title: 'Slovenščina' },
  { code: 'sv', title: 'Svenska' },
  { code: 'tr', title: 'Türkçe' },
  { code: 'uk', title: 'Українська' },
  { code: 'vi', title: 'Tiếng Việt' },
]

export const SUPERTONIC_DEFAULT_VOICE_SETTINGS = {
  language: 'ko',
  speed: 1.05,
  steps: 5,
}

const voiceSettingsSchema = z.object({
  language: z.string().default(SUPERTONIC_DEFAULT_VOICE_SETTINGS.language),
  speed: z.number().default(SUPERTONIC_DEFAULT_VOICE_SETTINGS.speed),
  steps: z.number().int().default(SUPERTONIC_DEFAULT_VOICE_SETTINGS.steps),
})

type SupertonicVoiceSettings = z.infer<typeof voiceSettingsSchema>

interface SupertonicConfig {
  voiceSettings?: Partial<SupertonicVoiceSettings>
}

function readSpeechRequest(init?: RequestInit) {
  if (typeof init?.body !== 'string')
    throw new Error('Invalid request body')

  const body = JSON.parse(init.body) as { input?: string, voice?: string }
  return { input: body.input ?? '', voice: body.voice || SUPERTONIC_VOICES[0].id }
}

export const providerSupertonicLocal = defineProvider<SupertonicConfig, typeof SUPERTONIC_PROVIDER_ID>({
  id: SUPERTONIC_PROVIDER_ID,
  order: 0,
  name: 'Supertonic TTS',
  nameLocalize: ({ t }) => t('settings.pages.providers.provider.supertonic-local.title'),
  description: 'On-device multilingual text-to-speech using Supertonic 3. Runs on Android only.',
  descriptionLocalize: ({ t }) => t('settings.pages.providers.provider.supertonic-local.description'),
  tasks: ['text-to-speech'],
  icon: 'i-lobe-icons:speaker',
  requiresCredentials: false,
  isAvailableBy: () => isStageCapacitor() && isSupertonicAvailable(),
  createProviderConfig: () => z.object({
    voiceSettings: voiceSettingsSchema.default(SUPERTONIC_DEFAULT_VOICE_SETTINGS),
  }),
  createProvider(config) {
    const settings = { ...SUPERTONIC_DEFAULT_VOICE_SETTINGS, ...config.voiceSettings }

    return {
      speech: () => ({
        baseURL: 'http://supertonic-local/v1/',
        model: SUPERTONIC_MODEL_ID,
        fetch: async (_input: RequestInfo | URL, init?: RequestInit) => {
          const { input, voice } = readSpeechRequest(init)
          const result = await SupertonicTts.synthesize({
            text: input,
            voice,
            lang: settings.language,
            speed: settings.speed,
            steps: settings.steps,
          })

          return new Response(decodeBase64(result.audio), {
            status: 200,
            headers: { 'Content-Type': 'audio/wav' },
          })
        },
      }),
    }
  },
  validationRequiredWhen: () => false,
  validators: {
    validateConfig: [
      () => ({
        id: `${SUPERTONIC_PROVIDER_ID}:check-models`,
        name: 'Model files',
        validator: async () => {
          try {
            const status = await SupertonicTts.getStatus()
            if (status.ready)
              return { errors: [], reason: '', reasonKey: '', valid: true }

            const reason = 'Download the Supertonic model files first.'
            return { errors: [{ error: new Error(reason) }], reason, reasonKey: '', valid: false }
          }
          catch (error) {
            const reason = errorMessageFrom(error) ?? 'The Supertonic plugin is not available.'
            return { errors: [{ error }], reason, reasonKey: '', valid: false }
          }
        },
      }),
    ],
  },
  extraMethods: {
    listModels: async () => [{
      id: SUPERTONIC_MODEL_ID,
      name: 'Supertonic 3',
      provider: SUPERTONIC_PROVIDER_ID,
      description: '',
      contextLength: 0,
      deprecated: false,
    }],
    voiceCatalogConfig: ({ voiceSettings }) => ({ language: voiceSettings?.language }),
    listVoices: async (config) => {
      const code = config.voiceSettings?.language ?? SUPERTONIC_DEFAULT_VOICE_SETTINGS.language
      const language = SUPERTONIC_LANGUAGES.find(item => item.code === code) ?? { code, title: code }

      return SUPERTONIC_VOICES.map(voice => ({
        id: voice.id,
        name: `${voice.name} (${voice.id})`,
        provider: SUPERTONIC_PROVIDER_ID,
        gender: voice.gender,
        languages: [language],
      }))
    },
  },
})
