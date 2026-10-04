<script setup lang="ts">
import type { PluginListenerHandle, SupertonicStatus } from '@proj-airi/stage-ui/libs/providers/providers/supertonic-local/native'
import type { SpeechProvider } from '@xsai-ext/providers/utils'

import { errorMessageFrom } from '@moeru/std'
import {
  SpeechPlayground,
  SpeechProviderSettings,
} from '@proj-airi/stage-ui/components'
import {
  SUPERTONIC_DEFAULT_VOICE_SETTINGS,
  SUPERTONIC_LANGUAGES,
  SUPERTONIC_MODEL_ID,
} from '@proj-airi/stage-ui/libs/providers/providers/supertonic-local'
import {
  isSupertonicAvailable,
  SupertonicTts,
} from '@proj-airi/stage-ui/libs/providers/providers/supertonic-local/native'
import { useSpeechStore } from '@proj-airi/stage-ui/stores/modules/speech'
import { useProviderConfigStore } from '@proj-airi/stage-ui/stores/providers/config'
import { useProviderStore } from '@proj-airi/stage-ui/stores/providers/provider'
import { Button, Callout, FieldRange, FieldSelect } from '@proj-airi/ui'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const providerId = 'supertonic-local'
const speechStore = useSpeechStore()
const providersStore = useProviderStore()
const providerConfigStore = useProviderConfigStore()
const { t } = useI18n()

const available = isSupertonicAvailable()
const status = ref<SupertonicStatus>()
const downloadPercent = ref<number>()
const errorMessage = ref('')
const voicesLoading = ref(false)

let progressListener: PluginListenerHandle | undefined

const availableVoices = computed(() => speechStore.availableVoices[providerId] || [])

const languageOptions = SUPERTONIC_LANGUAGES.map(language => ({
  label: language.title,
  value: language.code,
}))

const statusLabel = computed(() => {
  if (!available)
    return t('settings.pages.providers.provider.supertonic-local.model.status.unavailable')
  if (downloadPercent.value != null)
    return t('settings.pages.providers.provider.supertonic-local.model.status.downloading', { percent: downloadPercent.value })
  if (status.value?.ready)
    return t('settings.pages.providers.provider.supertonic-local.model.status.ready')

  return t('settings.pages.providers.provider.supertonic-local.model.status.missing')
})

const totalMegabytes = computed(() => Math.round((status.value?.totalBytes ?? 0) / 1024 / 1024))

async function refreshStatus() {
  status.value = await SupertonicTts.getStatus()
}

/**
 * Registers the provider and refreshes its validation status after the model files change.
 * The speech module lists models only for providers with the configured status.
 */
async function syncProviderStatus() {
  await providersStore.initializeProvider(providerId)
  if (status.value?.ready)
    providerConfigStore.markProviderAdded(providerId)

  await providersStore.validateProvider(providerId, { force: true })
  if (status.value?.ready)
    await providersStore.fetchModelsForProvider(providerId)
}

async function reloadVoices() {
  voicesLoading.value = true
  try {
    await speechStore.loadVoicesForProvider(providerId)
  }
  finally {
    voicesLoading.value = false
  }
}

async function handleDownload() {
  errorMessage.value = ''
  downloadPercent.value = 0
  try {
    await SupertonicTts.downloadModels()
    await refreshStatus()
    await syncProviderStatus()
    await reloadVoices()
  }
  catch (error) {
    errorMessage.value = errorMessageFrom(error) ?? 'Download failed'
  }
  finally {
    downloadPercent.value = undefined
    await refreshStatus()
  }
}

async function handleCancel() {
  await SupertonicTts.cancelDownload()
}

async function handleDelete() {
  errorMessage.value = ''
  await SupertonicTts.deleteModels()
  await refreshStatus()
  await syncProviderStatus()
}

async function handleGenerateSpeech(input: string, voiceId: string, _useSSML: boolean) {
  const provider = await providersStore.getProviderInstance(providerId) as SpeechProvider
  const config = providerConfigStore.getProviderConfig(providerId)
  return await speechStore.speech(provider, SUPERTONIC_MODEL_ID, input, voiceId, { ...config })
}

onMounted(async () => {
  if (!available)
    return

  progressListener = await SupertonicTts.addListener('downloadProgress', (progress) => {
    downloadPercent.value = progress.percent
  })

  await refreshStatus()
  if (status.value?.downloading)
    downloadPercent.value = Math.round((status.value.downloadedBytes / status.value.totalBytes) * 100)

  await syncProviderStatus()
  await reloadVoices()
})

onUnmounted(() => {
  void progressListener?.remove()
})
</script>

<template>
  <SpeechProviderSettings
    :provider-id="providerId"
    :default-model="SUPERTONIC_MODEL_ID"
    :additional-settings="SUPERTONIC_DEFAULT_VOICE_SETTINGS"
    hide-api-key
  >
    <template #basic-settings>
      <div class="flex flex-col gap-3">
        <Callout :label="t('settings.pages.providers.provider.supertonic-local.model.label')">
          <p>{{ t('settings.pages.providers.provider.supertonic-local.model.description') }}</p>
        </Callout>

        <div class="flex items-center justify-between gap-2 text-sm">
          <span class="text-neutral-600 dark:text-neutral-300">
            {{ statusLabel }}
            <template v-if="totalMegabytes > 0">
              ({{ totalMegabytes }} MB)
            </template>
          </span>

          <div v-if="available" class="flex gap-2">
            <Button
              v-if="downloadPercent != null"
              size="sm"
              :label="t('settings.pages.providers.provider.supertonic-local.model.cancel')"
              @click="handleCancel"
            />
            <Button
              v-else-if="!status?.ready"
              size="sm"
              color="primary"
              variant="primary"
              icon="i-solar:download-minimalistic-bold-duotone"
              :label="t('settings.pages.providers.provider.supertonic-local.model.download')"
              @click="handleDownload"
            />
            <Button
              v-else
              size="sm"
              color="red"
              icon="i-solar:trash-bin-trash-bold-duotone"
              :label="t('settings.pages.providers.provider.supertonic-local.model.delete')"
              @click="handleDelete"
            />
          </div>
        </div>

        <div v-if="downloadPercent != null" class="h-2 w-full overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800">
          <div class="h-full bg-primary-500 transition-all" :style="{ width: `${downloadPercent}%` }" />
        </div>

        <p v-if="errorMessage" class="text-sm text-red-500">
          {{ errorMessage }}
        </p>
      </div>
    </template>

    <template #voice-settings="{ voiceSettings }">
      <FieldSelect
        v-model="voiceSettings.language"
        :label="t('settings.pages.providers.provider.supertonic-local.fields.field.language.label')"
        :description="t('settings.pages.providers.provider.supertonic-local.fields.field.language.description')"
        :options="languageOptions"
      />
      <FieldRange
        v-model="voiceSettings.speed"
        :label="t('settings.pages.providers.provider.supertonic-local.fields.field.speed.label')"
        :description="t('settings.pages.providers.provider.supertonic-local.fields.field.speed.description')"
        :min="0.7" :max="2" :step="0.05"
      />
      <FieldRange
        v-model="voiceSettings.steps"
        :label="t('settings.pages.providers.provider.supertonic-local.fields.field.steps.label')"
        :description="t('settings.pages.providers.provider.supertonic-local.fields.field.steps.description')"
        :min="2" :max="16" :step="1"
      />
    </template>

    <template #playground>
      <SpeechPlayground
        :available-voices="availableVoices"
        :generate-speech="handleGenerateSpeech"
        :api-key-configured="status?.ready ?? false"
        :voices-loading="voicesLoading"
        :default-text="t('settings.pages.providers.provider.supertonic-local.playground.default-text')"
      />
    </template>
  </SpeechProviderSettings>
</template>

<route lang="yaml">
  meta:
    layout: settings
    stageTransition:
      name: slide
</route>
