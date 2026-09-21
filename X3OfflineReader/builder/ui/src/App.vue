<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { open } from '@tauri-apps/plugin-dialog'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputNumber from 'primevue/inputnumber'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'

type Voice = { id: string; preview: boolean; previewType: 'cached' | 'live' | 'none' }
type Provider = { id: string; label: string; online: boolean; defaultVoice: string; throughputLabel: string; benchmarkCharactersPerHour: number; optimizedParallelism: number; voices: Voice[] }
type CacheStats = { sentenceFiles: number; sentenceBytes: number; unfinishedDirectories: number; unfinishedBytes: number }
type Checkpoint = { id: string; state: string; percent: number; message: string; elapsedSeconds?: number; provider: string; online: boolean; output: string; inputs: string[] }
type BridgeState = { version: string; providers: Provider[]; cache: CacheStats; checkpoint?: Checkpoint }
type BuilderEvent = { type: string; percent?: number; message?: string; seconds?: number; estimateSeconds?: number; taskElapsedSeconds?: number; runElapsedSeconds?: number; outputs?: string[] }

const page = ref<'build' | 'voices' | 'tasks' | 'storage'>('build')
const loading = ref(true)
const busy = ref(false)
const pausing = ref(false)
const status = ref('正在连接本地转换核心…')
const progress = ref(0)
const logs = ref<string[]>([])
const logView = ref<HTMLElement>()
const outputs = ref<string[]>([])
const providers = ref<Provider[]>([])
const checkpoint = ref<Checkpoint>()
const cache = reactive<CacheStats>({ sentenceFiles: 0, sentenceBytes: 0, unfinishedDirectories: 0, unfinishedBytes: 0 })
const startedAt = ref(0)
const elapsedSeconds = ref(0)
const initialEstimate = ref(0)
const projectedTotal = ref(0)
const completedTaskSeconds = ref<number | null>(null)
const completedRunSeconds = ref<number | null>(null)
const showDataNotice = ref(false)
let timer = 0
let unlisten: UnlistenFn | undefined
let previewAudio: HTMLAudioElement | undefined
let previewRequestId = 0
let lastEtaProgress = -1

const form = reactive({
  inputs: [] as string[], output: '', outputToSource: true, separate: false,
  title: '', author: '', language: 'zh-CN', tts: 'melo', voice: 'ZH', cover: '',
  meloWorkers: 2, edgeWorkers: 8, sherpaWorkers: 4, windowsWorkers: 2, pauseMs: 250, maxTrackMinutes: 20, rate: 1, allowOnlineTts: false
})
const lastInputDirectory = ref('')
const lastCoverDirectory = ref('')
const preferencesStorageKey = 'tingdu-builder-preferences'
const dataNoticeStorageKey = 'tingdu-builder-data-notice-v1'

const nav = [
  { id: 'build', icon: 'pi-sparkles', label: '制作新书' },
  { id: 'voices', icon: 'pi-volume-up', label: '音色试听' },
  { id: 'tasks', icon: 'pi-clock', label: '任务与续作' },
  { id: 'storage', icon: 'pi-database', label: '存储管理' }
] as const
const selectedProvider = computed(() => providers.value.find(item => item.id === form.tts))
const selectedVoice = computed(() => selectedProvider.value?.voices.find(item => item.id === form.voice))
const voiceOptions = computed(() => selectedProvider.value?.voices.map(item => ({ label: item.id, value: item.id, preview: item.preview, previewType: item.previewType })) ?? [])
const providerOptions = computed(() => providers.value.map(item => ({ label: `${item.label} · ${item.throughputLabel}`, value: item.id, online: item.online })))
const canStart = computed(() => !busy.value && form.inputs.length > 0 && (form.outputToSource || !!form.output))
const remaining = computed(() => Math.max(0, Math.round(projectedTotal.value - elapsedSeconds.value)))
const progressLabel = computed(() => busy.value ? `${progress.value}%` : progress.value === 100 ? '已完成' : '等待开始')

watch(() => form.tts, id => {
  stopPreviewAudio()
  const provider = providers.value.find(item => item.id === id)
  if (provider && !provider.voices.some(item => item.id === form.voice)) form.voice = provider.defaultVoice
  savePrefs()
})
watch(() => form.voice, () => stopPreviewAudio())
watch(() => ({ ...form, inputs: undefined }), savePrefs, { deep: true })
watch(() => [...form.inputs], applyRecognizedMetadata)

onMounted(async () => {
  loadPrefs()
  showDataNotice.value = localStorage.getItem(dataNoticeStorageKey) !== 'accepted'
  try {
    unlisten = await listen<BuilderEvent>('builder-event', event => { void handleEvent(event.payload) })
    await refreshState()
    status.value = '准备就绪'
  } catch (error) {
    status.value = `无法连接转换核心：${errorText(error)}`
  } finally { loading.value = false }
  timer = window.setInterval(() => {
    if (!busy.value || !startedAt.value) return
    elapsedSeconds.value = Math.floor((Date.now() - startedAt.value) / 1000)
  }, 1000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer); stopPreviewAudio(); unlisten?.() })

async function refreshState() {
  const state = await invoke<BridgeState>('bridge_state')
  providers.value = state.providers
  checkpoint.value = state.checkpoint
  Object.assign(cache, state.cache)
  if (!providers.value.some(item => item.id === form.tts)) form.tts = providers.value[0]?.id ?? 'melo'
  const provider = selectedProvider.value
  if (provider && !provider.voices.some(item => item.id === form.voice)) form.voice = provider.defaultVoice
}

async function addInputs() {
  const chosen = await open({ multiple: true, defaultPath: lastInputDirectory.value || undefined, filters: [{ name: '电子书', extensions: ['epub', 'txt'] }] })
  if (!chosen) return
  const values = Array.isArray(chosen) ? chosen : [chosen]
  lastInputDirectory.value = directory(values[0])
  for (const value of values) if (!form.inputs.includes(value)) form.inputs.push(value)
}
async function chooseOutput() {
  const chosen = await open({ directory: true, multiple: false, defaultPath: form.output || undefined })
  if (typeof chosen === 'string') form.output = chosen
}
async function chooseCover() {
  const chosen = await open({ multiple: false, defaultPath: lastCoverDirectory.value || undefined, filters: [{ name: '图片', extensions: ['jpg', 'jpeg', 'png', 'webp'] }] })
  if (typeof chosen === 'string') { form.cover = chosen; lastCoverDirectory.value = directory(chosen) }
}
function moveInput(index: number, delta: number) {
  const next = index + delta
  if (next < 0 || next >= form.inputs.length) return
  ;[form.inputs[index], form.inputs[next]] = [form.inputs[next], form.inputs[index]]
}
function removeInput(index: number) { form.inputs.splice(index, 1) }

async function startBuild(resume = false) {
  if (busy.value) return
  const provider = selectedProvider.value
  if (!resume && provider?.online) {
    const ok = window.confirm('该音色需要把待朗读正文发送到在线服务。确认你拥有内容授权并同意继续吗？')
    if (!ok) return
    form.allowOnlineTts = true
  }
  busy.value = true; pausing.value = false; progress.value = 0
  logs.value = []; outputs.value = []; completedTaskSeconds.value = null; completedRunSeconds.value = null; startedAt.value = Date.now(); elapsedSeconds.value = 0
  initialEstimate.value = 0; projectedTotal.value = 0; lastEtaProgress = -1
  status.value = resume ? '正在恢复上次任务…' : '正在准备转换…'
  try {
    if (resume) await invoke('resume_build')
    else await invoke('start_build', { request: { ...form } })
  } catch (error) {
    busy.value = false
    startedAt.value = 0
    status.value = `启动失败：${errorText(error)}`
  }
}
async function pause(closeAfter = false) {
  if (!busy.value || pausing.value) return
  pausing.value = true; status.value = '正在安全保存断点，请稍候…'
  try { await invoke('pause_build', { closeAfter }) }
  catch (error) { pausing.value = false; status.value = `暂停失败：${errorText(error)}` }
}
async function previewVoice(provider = form.tts, voice = form.voice) {
  stopPreviewAudio()
  const requestId = ++previewRequestId
  const item = providers.value.find(value => value.id === provider)?.voices.find(value => value.id === voice)
  if (!item?.preview) { status.value = '该音色暂时没有可用试听'; return }
  status.value = item.previewType === 'live' ? `正在生成 ${voice} 的本机实时试听…` : `正在播放 ${voice} 的预生成试听…`
  try {
    const result = await invoke<{ mimeType: string; audioBase64: string }>('preview_audio', { provider, voice })
    if (requestId !== previewRequestId) return
    const audio = new Audio(`data:${result.mimeType};base64,${result.audioBase64}`)
    previewAudio = audio
    audio.addEventListener('ended', () => { if (previewAudio === audio) previewAudio = undefined }, { once: true })
    await audio.play()
  } catch (error) { if (requestId === previewRequestId) status.value = `试听失败：${errorText(error)}` }
}
function stopPreviewAudio() {
  previewRequestId++
  if (!previewAudio) return
  previewAudio.pause()
  previewAudio.currentTime = 0
  previewAudio.src = ''
  previewAudio.load()
  previewAudio = undefined
}
async function cacheAction(action: 'prune' | 'stale' | 'clear') {
  if (busy.value) return
  if (action === 'stale' && !window.confirm('确定删除超过 30 天的未完成临时任务吗？对应断点将无法继续；电子书源文件和已输出的 .opbook 不会删除。')) return
  if (action === 'clear' && !window.confirm('确定清空句子音频缓存吗？未完成任务的断点不会删除。')) return
  status.value = '正在整理存储…'
  try {
    const result = await invoke<CacheStats & { deletedFiles: number; deletedBytes: number }>('cache_action', { action })
    Object.assign(cache, result)
    status.value = `整理完成：删除 ${result.deletedFiles} 个文件，释放 ${humanBytes(result.deletedBytes)}`
  } catch (error) { status.value = `整理失败：${errorText(error)}` }
}
function acceptDataNotice() {
  localStorage.setItem(dataNoticeStorageKey, 'accepted')
  showDataNotice.value = false
}
function clearInterfaceData() {
  if (busy.value || !window.confirm('确定清除界面设置和最近 50 项来源识别历史吗？当前任务、断点、缓存、源文件和输出文件不会删除。')) return
  localStorage.removeItem(preferencesStorageKey)
  localStorage.removeItem(metadataStorageKey)
  lastInputDirectory.value = ''
  lastCoverDirectory.value = ''
  activeInputIdentity = ''
  status.value = '界面设置与来源识别历史已清除'
}
async function handleEvent(event: BuilderEvent) {
  if (event.type === 'estimate') {
    const estimatedTotal = elapsedSeconds.value + (event.seconds ?? 0)
    initialEstimate.value = estimatedTotal
    projectedTotal.value = estimatedTotal
    return
  }
  if (event.percent != null) {
    const next = Math.max(progress.value, event.percent)
    if (next > progress.value) observeEta(next)
    progress.value = next
  }
  if (event.estimateSeconds && !projectedTotal.value) projectedTotal.value = event.estimateSeconds
  if (event.message) {
    status.value = event.message
    logs.value.push(`[${formatDuration(elapsedSeconds.value)}] ${event.message}`)
    await nextTick()
    if (logView.value) logView.value.scrollTop = logView.value.scrollHeight
  }
  if (event.type === 'complete') {
    rememberCompletedMetadata()
    completedRunSeconds.value = event.runElapsedSeconds ?? elapsedSeconds.value
    completedTaskSeconds.value = event.taskElapsedSeconds ?? completedRunSeconds.value
    busy.value = false; pausing.value = false; progress.value = 100; outputs.value = event.outputs ?? []
    startedAt.value = 0; elapsedSeconds.value = 0; initialEstimate.value = 0; projectedTotal.value = 0; lastEtaProgress = -1
    form.inputs.splice(0); form.title = ''; form.author = ''; form.cover = ''
    status.value = '转换完成，可以把 .opbook 传入手表'
    await refreshState()
  } else if (event.type === 'paused') {
    busy.value = false; pausing.value = false; status.value = event.message ?? '进度已安全保存，可随时恢复'
    await refreshState()
  } else if (event.type === 'failed') {
    busy.value = false; pausing.value = false; status.value = `转换失败：${event.message ?? '未知错误'}`
    await refreshState()
  }
}

function observeEta(percent: number) {
  if (percent <= lastEtaProgress) return
  lastEtaProgress = percent
  if (percent < 10 || percent >= 100 || elapsedSeconds.value < 3) return
  const observed = Math.max(elapsedSeconds.value + 1, Math.round(elapsedSeconds.value * 100 / percent))
  if (!projectedTotal.value) { projectedTotal.value = observed; return }
  const minimum = Math.max(elapsedSeconds.value + 1, Math.round(projectedTotal.value / 2))
  const maximum = Math.max(minimum, projectedTotal.value * 2)
  const bounded = Math.max(minimum, Math.min(maximum, observed))
  projectedTotal.value = Math.max(elapsedSeconds.value + 1, Math.round(projectedTotal.value * .85 + bounded * .15))
}

const excludedPreferenceKeys = new Set([
  'inputs', 'title', 'author', 'cover', 'meloWorkers', 'sherpaWorkers', 'windowsWorkers'
])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function loadPrefs() {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(preferencesStorageKey) ?? '{}')
    if (!isRecord(saved)) return
    lastInputDirectory.value = typeof saved.lastInputDirectory === 'string' ? saved.lastInputDirectory : ''
    lastCoverDirectory.value = typeof saved.lastCoverDirectory === 'string' ? saved.lastCoverDirectory : ''
    const mutableForm = form as unknown as Record<string, unknown>
    for (const [key, currentValue] of Object.entries(form)) {
      if (excludedPreferenceKeys.has(key)) continue
      const savedValue = saved[key]
      if (typeof savedValue === typeof currentValue) mutableForm[key] = savedValue
    }
  } catch { /* ignore damaged preferences */ }
}
function savePrefs() {
  const preferences = Object.fromEntries(
    Object.entries(form).filter(([key]) => !excludedPreferenceKeys.has(key))
  )
  localStorage.setItem(preferencesStorageKey, JSON.stringify({ ...preferences, lastInputDirectory: lastInputDirectory.value, lastCoverDirectory: lastCoverDirectory.value }))
}
type CompletedMetadata = { title: string; author: string }
const metadataStorageKey = 'tingdu-builder-completed-metadata-v1'
let activeInputIdentity = ''
function inputIdentity(inputs = form.inputs) {
  return JSON.stringify(inputs.map(path => path.replaceAll('/', '\\').toLocaleLowerCase()).sort())
}
function isCompletedMetadata(value: unknown): value is CompletedMetadata {
  return isRecord(value) && typeof value.title === 'string' && typeof value.author === 'string'
}
function completedMetadata(): Record<string, CompletedMetadata> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(metadataStorageKey) ?? '{}')
    if (!isRecord(parsed)) return {}
    return Object.fromEntries(
      Object.entries(parsed).filter((entry): entry is [string, CompletedMetadata] => isCompletedMetadata(entry[1]))
    )
  } catch { return {} }
}
function applyRecognizedMetadata() {
  const identity = inputIdentity()
  if (identity === activeInputIdentity) return
  activeInputIdentity = identity
  const recognized = identity === '[]' ? undefined : completedMetadata()[identity]
  form.title = recognized?.title ?? ''
  form.author = recognized?.author ?? ''
  form.cover = ''
}
function rememberCompletedMetadata() {
  try {
    const identity = inputIdentity()
    if (identity === '[]') return
    const history = completedMetadata()
    delete history[identity]
    history[identity] = { title: form.title.trim(), author: form.author.trim() }
    const recent = Object.fromEntries(Object.entries(history).slice(-50))
    localStorage.setItem(metadataStorageKey, JSON.stringify(recent))
  } catch { /* metadata history is an optional convenience */ }
}
function basename(path: string) { return path.split(/[\\/]/).pop() ?? path }
function directory(path: string) { const index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); return index > 0 ? path.slice(0, index) : '' }
function humanBytes(bytes: number) { if (!bytes) return '0 B'; const units = ['B', 'KB', 'MB', 'GB', 'TB']; const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024))); return `${(bytes / 1024 ** index).toFixed(index > 1 ? 1 : 0)} ${units[index]}` }
function formatDuration(seconds: number) { const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60; return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}` }
function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark"><i class="pi pi-book" /><i class="pi pi-volume-up wave" /></div>
        <div><strong>听读工坊</strong><span>把文字变成随身听读</span></div>
      </div>
      <nav>
        <button v-for="item in nav" :key="item.id" :class="{ active: page === item.id }" @click="page = item.id">
          <i class="pi" :class="item.icon" /><span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="sidebar-foot">
        <span class="status-dot" :class="{ working: busy }" />
        <div><small>本地转换核心</small><strong>{{ busy ? '正在工作' : loading ? '连接中' : '已就绪' }}</strong></div>
      </div>
    </aside>

    <main>
      <header class="topbar">
        <div>
          <p class="eyebrow">X3 OFFLINE READER</p>
          <h1>{{ nav.find(item => item.id === page)?.label }}</h1>
        </div>
      </header>

      <Message v-if="status && (status.includes('失败') || status.includes('无法'))" severity="error" :closable="false">{{ status }}</Message>
      <Message v-if="showDataNotice" severity="info" :closable="false">
        工坊默认在本机处理源文件，并在用户数据目录保存断点、缓存、界面设置和最多 50 项来源识别历史。只有选用在线 Edge 音色并逐次确认后，待朗读正文才会发送给在线服务；清理操作不会删除电子书源文件或已输出的 .opbook。
        <Button label="知道了" size="small" text @click="acceptDataNotice" />
      </Message>

      <section v-if="page === 'build'" class="page-grid build-grid">
        <Card class="hero-card span-2">
          <template #content>
            <div class="hero-content">
              <div><p class="eyebrow">从这里开始</p><h2>今天想听哪本书？</h2><p>可添加多本 EPUB 或 TXT，排序后合并成一部多卷有声书，也可分别导出。</p></div>
              <Button label="添加电子书" icon="pi pi-plus" size="large" :disabled="busy" @click="addInputs" />
            </div>
          </template>
        </Card>

        <Card class="span-2">
          <template #title><span class="section-title"><b>1</b> 来源与顺序</span></template>
          <template #content>
            <div v-if="form.inputs.length" class="source-list">
              <div v-for="(input, index) in form.inputs" :key="input" class="source-row">
                <span class="source-index">{{ index + 1 }}</span>
                <div><strong>{{ basename(input) }}</strong><small>{{ input }}</small></div>
                <div class="row-actions">
                  <Button icon="pi pi-arrow-up" text rounded :disabled="busy || index === 0" @click="moveInput(index, -1)" />
                  <Button icon="pi pi-arrow-down" text rounded :disabled="busy || index === form.inputs.length - 1" @click="moveInput(index, 1)" />
                  <Button icon="pi pi-times" text rounded severity="danger" :disabled="busy" @click="removeInput(index)" />
                </div>
              </div>
            </div>
            <button v-else class="empty-drop" @click="addInputs"><i class="pi pi-file-plus" /><strong>选择 EPUB / TXT</strong><span>支持一次添加多本并调整顺序</span></button>
          </template>
        </Card>

        <Card>
          <template #title><span class="section-title"><b>2</b> 书籍信息</span></template>
          <template #content>
            <div class="form-stack">
              <label><span>书名 <small>曾转换来源可自动回填</small></span><InputText v-model="form.title" placeholder="请填写书名" :disabled="busy" /></label>
              <label><span>作者</span><InputText v-model="form.author" placeholder="请填写作者" :disabled="busy" /></label>
              <label><span>封面 <small>可选</small></span><div class="input-action"><InputText v-model="form.cover" readonly placeholder="自动生成暖色封面" /><Button icon="pi pi-image" outlined :disabled="busy" @click="chooseCover" /></div></label>
              <label class="switch-row"><div><span>分别导出</span><small>每本书单独生成一个文件</small></div><ToggleSwitch v-model="form.separate" :disabled="busy" /></label>
            </div>
          </template>
        </Card>

        <Card>
          <template #title><span class="section-title"><b>3</b> 声音与节奏</span></template>
          <template #content>
            <div class="form-stack">
              <label><span>语音引擎</span><Select v-model="form.tts" :options="providerOptions" optionLabel="label" optionValue="value" :disabled="busy" fluid><template #option="slot"><span>{{ slot.option.label }}</span><Tag :value="slot.option.online ? '在线' : '离线'" :severity="slot.option.online ? 'warn' : 'success'" /></template></Select></label>
              <label><span>音色</span><div class="input-action"><Select v-model="form.voice" :options="voiceOptions" optionLabel="label" optionValue="value" :disabled="busy" fluid /><Button icon="pi pi-play" aria-label="试听" :disabled="busy || !selectedVoice?.preview" @click="previewVoice()" /></div></label>
              <div class="two-fields">
                <label><span>朗读速度</span><InputNumber v-model="form.rate" :min="0.5" :max="2" :step="0.05" :minFractionDigits="2" :maxFractionDigits="2" :disabled="busy" /></label>
                <label><span>句间停顿</span><InputNumber v-model="form.pauseMs" suffix=" ms" :min="0" :max="3000" :step="50" :disabled="busy" /></label>
              </div>
              <label v-if="form.tts === 'edge'"><span>在线并发 <small>1–12；默认 8，网络稳定时可提高</small></span><InputNumber v-model="form.edgeWorkers" :min="1" :max="12" :step="1" :disabled="busy" /></label>
              <p class="hint"><i class="pi pi-info-circle" /> Track 时长由工坊自动管理，不需要手动设置。</p>
            </div>
          </template>
        </Card>

        <Card class="span-2 output-card">
          <template #title><span class="section-title"><b>4</b> 输出与转换</span></template>
          <template #content>
            <div class="output-row">
              <label class="switch-row compact"><div><span>输出到源目录</span><small>生成“书名 [作者].opbook”</small></div><ToggleSwitch v-model="form.outputToSource" :disabled="busy" /></label>
              <div v-if="!form.outputToSource" class="output-picker"><InputText v-model="form.output" readonly placeholder="请选择输出文件夹" /><Button label="选择目录" icon="pi pi-folder-open" outlined :disabled="busy" @click="chooseOutput" /></div>
              <Button v-if="!busy" label="开始转换" icon="pi pi-sparkles" size="large" :disabled="!canStart" @click="startBuild(false)" />
              <div v-else class="pause-actions"><Button label="保存进度" icon="pi pi-pause" outlined :loading="pausing" @click="pause(false)" /><Button label="保存并关闭" icon="pi pi-save" severity="secondary" :disabled="pausing" @click="pause(true)" /></div>
            </div>
          </template>
        </Card>

        <Card v-if="busy || progress > 0" class="span-2 progress-card">
          <template #content>
            <div class="progress-head"><div><p class="eyebrow">当前任务</p><h3>{{ status }}</h3></div><strong>{{ progressLabel }}</strong></div>
            <ProgressBar :value="progress" :showValue="false" />
            <div v-if="completedTaskSeconds !== null" class="time-grid completed-time"><span>任务总时长 <b>{{ formatDuration(completedTaskSeconds) }}</b></span><span>本次执行总时长 <b>{{ formatDuration(completedRunSeconds ?? 0) }}</b></span></div>
            <div v-else class="time-grid"><span>已用时 <b>{{ formatDuration(elapsedSeconds) }}</b></span><span>预计总用时 <b>{{ projectedTotal ? formatDuration(projectedTotal) : '--' }}</b></span><span>预计剩余 <b>{{ projectedTotal ? formatDuration(remaining) : '--' }}</b></span></div>
            <div v-if="logs.length" ref="logView" class="log-view"><div v-for="(line, index) in logs" :key="`${index}-${line}`">{{ line }}</div></div>
            <div v-if="outputs.length" class="output-results"><strong>已生成</strong><span v-for="item in outputs" :key="item">{{ item }}</span></div>
          </template>
        </Card>
      </section>

      <section v-else-if="page === 'voices'" class="voice-page">
        <div class="page-intro"><div><p class="eyebrow">VOICE GALLERY</p><h2>先听，再决定陪你读书的声音</h2><p>离线模型使用预生成短句；Windows 系统语音会在本机实时生成试听。</p></div></div>
        <div class="voice-grid">
          <Card v-for="provider in providers" :key="provider.id" class="voice-provider">
            <template #title><div class="provider-title"><span>{{ provider.label }}</span><Tag :value="provider.online ? '在线' : '离线'" :severity="provider.online ? 'warn' : 'success'" /></div></template>
            <template #content>
              <div class="voice-list">
                <button v-for="voice in provider.voices" :key="voice.id" class="voice-item" :disabled="!voice.preview" @click="previewVoice(provider.id, voice.id)">
                  <span class="play-circle"><i class="pi" :class="voice.preview ? 'pi-play' : 'pi-lock'" /></span><span><strong>{{ voice.id }}</strong><small>{{ voice.previewType === 'live' ? '本机实时生成试听' : voice.previewType === 'cached' ? '自然语速预生成试听' : '试听尚未准备' }}</small></span>
                </button>
              </div>
            </template>
          </Card>
        </div>
      </section>

      <section v-else-if="page === 'tasks'" class="single-page">
        <Card>
          <template #title>未完成任务</template>
          <template #content>
            <div v-if="checkpoint" class="checkpoint-card">
              <div class="checkpoint-icon"><i class="pi pi-bookmark" /></div>
              <div><Tag :value="checkpoint.state === 'paused' ? '已保存' : checkpoint.state" severity="info" /><h3>{{ basename(checkpoint.inputs[0] ?? '未完成任务') }}</h3><p>{{ checkpoint.message }}</p><small>{{ checkpoint.output }}</small></div>
              <div class="checkpoint-progress"><strong>{{ checkpoint.percent }}%</strong><ProgressBar :value="checkpoint.percent" :showValue="false" /><Button label="从断点继续" icon="pi pi-play" :disabled="busy" @click="startBuild(true)" /></div>
            </div>
            <div v-else class="empty-state"><i class="pi pi-check-circle" /><h3>没有待续任务</h3><p>转换过程中可随时保存进度，下次从这里继续。</p></div>
          </template>
        </Card>
      </section>

      <section v-else class="single-page storage-page">
        <div class="storage-cards">
          <Card><template #content><i class="pi pi-wave-pulse metric-icon coral" /><span>句子音频缓存</span><strong>{{ humanBytes(cache.sentenceBytes) }}</strong><small>{{ cache.sentenceFiles.toLocaleString() }} 个可复用片段</small></template></Card>
          <Card><template #content><i class="pi pi-folder metric-icon ochre" /><span>未完成工作区</span><strong>{{ humanBytes(cache.unfinishedBytes) }}</strong><small>{{ cache.unfinishedDirectories }} 个任务目录</small></template></Card>
        </div>
        <Card>
          <template #title>缓存清理</template>
          <template #content>
            <p class="storage-note">工坊默认保留最近使用的句子音频以加速重复转换；超过 20 GB 或 180 天会自动整理。未完成任务的断点与缓存分开保存。断点和缓存依赖本机路径与运行环境，不作为可移植备份导出；电子书源文件和生成结果由你在原目录自行保管。</p>
            <div class="storage-actions"><Button label="按默认规则整理" icon="pi pi-sparkles" :disabled="busy" @click="cacheAction('prune')" /><Button label="清理过期临时任务" icon="pi pi-clock" outlined :disabled="busy" @click="cacheAction('stale')" /><Button label="清空句子缓存" icon="pi pi-trash" severity="danger" outlined :disabled="busy" @click="cacheAction('clear')" /></div>
          </template>
        </Card>
        <Card>
          <template #title>界面设置与历史</template>
          <template #content>
            <p class="storage-note">保存在本机的界面偏好包含最近选择目录；来源识别历史最多保留 50 项文件路径、书名和作者。清除后不会影响转换任务、断点、缓存、源文件或输出文件。</p>
            <div class="storage-actions"><Button label="清除界面设置与历史" icon="pi pi-history" severity="danger" outlined :disabled="busy" @click="clearInterfaceData" /></div>
          </template>
        </Card>
      </section>
    </main>
  </div>
</template>
