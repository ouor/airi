<script setup lang="ts">
import type {
  PluginListenerHandle,
  SherpaAsrStatus,
} from '@proj-airi/stage-ui/libs/providers/providers/sherpa-onnx/native'
import type { TranscriptionProviderWithExtraOptions } from '@xsai-ext/providers/utils'

import { errorMessageFrom } from '@moeru/std'
import {
  ProviderBasicSettings,
  ProviderSettingsContainer,
  ProviderSettingsLayout,
  TranscriptionPlayground,
} from '@proj-airi/stage-ui/components'
import { selectProviderMetadata } from '@proj-airi/stage-ui/libs/providers/metadata'
import {
  SHERPA_ONNX_LANGUAGES,
  SHERPA_ONNX_MODELS,
  SHERPA_ONNX_PROVIDER_ID,
} from '@proj-airi/stage-ui/libs/providers/providers/sherpa-onnx'
import {
  isSherpaAsrAvailable,
  SherpaAsr,
} from '@proj-airi/stage-ui/libs/providers/providers/sherpa-onnx/native'
import { useHearingStore } from '@proj-airi/stage-ui/stores/modules/hearing'
import { useProviderConfigStore } from '@proj-airi/stage-ui/stores/providers/config'
import { useProviderStore } from '@proj-airi/stage-ui/stores/providers/provider'
import { Button, Callout, FieldSelect } from '@proj-airi/ui'
import { computedAsync } from '@vueuse/core'
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

const providerId = SHERPA_ONNX_PROVIDER_ID
const { t } = useI18n()
const router = useRouter()
const hearingStore = useHearingStore()
const providersStore = useProviderStore()
const providerConfigStore = useProviderConfigStore()

const available = isSherpaAsrAvailable()
const statuses = reactive<Record<string, SherpaAsrStatus | undefined>>({})
const downloadingModel = ref<string>()
const downloadPercent = ref(0)
const errorMessage = ref('')
const playgroundModel = ref<string>()

let progressListener: PluginListenerHandle | undefined

const providerMetadata = computedAsync(async () => {
  const definition = providersStore.getProviderDefinition(providerId)
  return await selectProviderMetadata(definition, t, { id: providerId })
}, undefined)

const language = computed({
  get: () => providerConfigStore.getProviderConfig(providerId)?.language as string | undefined ?? 'ko',
  set: (value: string) => {
    void providerConfigStore.patchProviderConfig(providerId, { language: value })
  },
})

const languageOptions = SHERPA_ONNX_LANGUAGES.map(item => ({ label: item.title, value: item.code }))

const readyModels = computed(() => SHERPA_ONNX_MODELS.filter(model => statuses[model.id]?.ready))
const readyModelOptions = computed(() => readyModels.value.map(model => ({ label: model.name, value: model.id })))

function statusLabel(modelId: string) {
  const key = 'settings.pages.providers.provider.sherpa-onnx-transcription.model.status'
  if (!available)
    return t(`${key}.unavailable`)
  if (downloadingModel.value === modelId)
    return t(`${key}.downloading`, { percent: downloadPercent.value })
  if (statuses[modelId]?.ready)
    return t(`${key}.ready`)

  return t(`${key}.missing`)
}

async function refreshStatuses() {
  for (const model of SHERPA_ONNX_MODELS)
    statuses[model.id] = await SherpaAsr.getStatus({ model: model.id })

  if (!playgroundModel.value || !statuses[playgroundModel.value]?.ready)
    playgroundModel.value = readyModels.value[0]?.id

  await syncProviderStatus()
}

/**
 * Registers the provider and refreshes its validation status after the model files change.
 * The hearing module lists models only for providers with the configured status.
 */
async function syncProviderStatus() {
  const ready = readyModels.value.length > 0
  if (ready)
    providerConfigStore.markProviderAdded(providerId)

  await providersStore.validateProvider(providerId, { force: true })
  if (ready)
    await providersStore.fetchModelsForProvider(providerId)
}

async function handleDownload(modelId: string) {
  errorMessage.value = ''
  downloadingModel.value = modelId
  downloadPercent.value = 0
  try {
    await SherpaAsr.downloadModel({ model: modelId })
  }
  catch (error) {
    errorMessage.value = errorMessageFrom(error) ?? 'Download failed'
  }
  finally {
    downloadingModel.value = undefined
    await refreshStatuses()
  }
}

async function handleCancel() {
  await SherpaAsr.cancelDownload()
}

async function handleDelete(modelId: string) {
  errorMessage.value = ''
  await SherpaAsr.deleteModel({ model: modelId })
  await refreshStatuses()
}

async function handleGenerateTranscription(file: File) {
  if (!playgroundModel.value)
    throw new Error('Download a model first')

  const provider = await providersStore.getProviderInstance(providerId) as TranscriptionProviderWithExtraOptions<string, unknown>
  return await hearingStore.transcription(providerId, provider, playgroundModel.value, file, 'json')
}

onMounted(async () => {
  await providersStore.initializeProvider(providerId)
  if (!available)
    return

  progressListener = await SherpaAsr.addListener('downloadProgress', (progress) => {
    downloadPercent.value = progress.percent
  })
  await refreshStatuses()
})

onUnmounted(() => {
  void progressListener?.remove()
})
</script>

<template>
  <ProviderSettingsLayout
    :provider-name="providerMetadata?.localizedName ?? ''"
    :provider-icon="providerMetadata?.icon"
    :provider-icon-color="providerMetadata?.iconColor"
    :on-back="() => router.back()"
  >
    <div flex="~ col md:row gap-6">
      <ProviderSettingsContainer class="w-full md:w-[40%]">
        <ProviderBasicSettings
          :title="t('settings.pages.providers.common.section.basic.title')"
          :description="t('settings.pages.providers.common.section.basic.description')"
        >
          <Callout :label="t('settings.pages.providers.provider.sherpa-onnx-transcription.model.label')">
            <p>{{ t('settings.pages.providers.provider.sherpa-onnx-transcription.model.description') }}</p>
          </Callout>

          <div
            v-for="model in SHERPA_ONNX_MODELS"
            :key="model.id"
            class="flex flex-col gap-2 rounded-lg bg-neutral-100 p-3 dark:bg-neutral-800/60"
          >
            <div class="flex items-center justify-between gap-2">
              <div class="min-w-0">
                <div class="text-sm font-medium">
                  {{ model.name }} ({{ model.sizeMegabytes }} MB)
                </div>
                <div class="text-xs text-neutral-500 dark:text-neutral-400">
                  {{ model.description }}
                </div>
                <div class="text-xs text-neutral-600 dark:text-neutral-300">
                  {{ statusLabel(model.id) }}
                </div>
              </div>

              <div v-if="available" class="shrink-0">
                <Button
                  v-if="downloadingModel === model.id"
                  size="sm"
                  :label="t('settings.pages.providers.provider.sherpa-onnx-transcription.model.cancel')"
                  @click="handleCancel"
                />
                <Button
                  v-else-if="!statuses[model.id]?.ready"
                  size="sm"
                  color="primary"
                  variant="primary"
                  icon="i-solar:download-minimalistic-bold-duotone"
                  :disabled="downloadingModel != null"
                  :label="t('settings.pages.providers.provider.sherpa-onnx-transcription.model.download')"
                  @click="handleDownload(model.id)"
                />
                <Button
                  v-else
                  size="sm"
                  color="red"
                  icon="i-solar:trash-bin-trash-bold-duotone"
                  :label="t('settings.pages.providers.provider.sherpa-onnx-transcription.model.delete')"
                  @click="handleDelete(model.id)"
                />
              </div>
            </div>

            <div v-if="downloadingModel === model.id" class="h-2 w-full overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-700">
              <div class="h-full bg-primary-500 transition-all" :style="{ width: `${downloadPercent}%` }" />
            </div>
          </div>

          <p v-if="errorMessage" class="text-sm text-red-500">
            {{ errorMessage }}
          </p>

          <FieldSelect
            v-model="language"
            :label="t('settings.pages.providers.provider.sherpa-onnx-transcription.fields.field.language.label')"
            :description="t('settings.pages.providers.provider.sherpa-onnx-transcription.fields.field.language.description')"
            :options="languageOptions"
          />
        </ProviderBasicSettings>
      </ProviderSettingsContainer>

      <div flex="~ col gap-4" class="w-full md:w-[60%]">
        <FieldSelect
          v-if="readyModelOptions.length > 1"
          v-model="playgroundModel"
          :label="t('settings.pages.providers.provider.sherpa-onnx-transcription.model.label')"
          :options="readyModelOptions"
        />
        <TranscriptionPlayground
          :generate-transcription="handleGenerateTranscription"
          :api-key-configured="readyModels.length > 0"
        />
      </div>
    </div>
  </ProviderSettingsLayout>
</template>

<route lang="yaml">
  meta:
    layout: settings
    stageTransition:
      name: slide
</route>
