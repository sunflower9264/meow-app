<template>
  <div class="chat-view">
    <header class="header">
      <span class="status" :class="{ online: isConnected }">
        {{ isConnected ? '在线' : '离线' }}
      </span>
    </header>

    <main class="chat" ref="messagesRef">
      <div v-if="messages.length === 0 && !currentSTTText && !currentSentence" class="empty">
        <span class="empty-icon">🐱</span>
        <p>有什么可以帮你？</p>
      </div>

      <div v-for="msg in messages" :key="msg.id" :class="['msg', msg.role]">
        <div class="msg-content">{{ msg.content }}</div>

        <audio
          v-if="msg.role === 'assistant' && msg.audioUrl"
          :src="msg.audioUrl"
          @play="onMessageAudioPlay"
          @pause="onMessageAudioPause"
          @ended="onMessageAudioEnded"
          controls
          preload="metadata"
          class="msg-audio"
        ></audio>

        <div
          v-else-if="msg.role === 'assistant' && isMessageWaitingAudio(msg.id)"
          class="msg-audio-pending"
        >
          语音生成中...
        </div>

        <span class="msg-time">{{ formatTime(msg.timestamp) }}</span>
      </div>

      <div v-if="currentSTTText" class="msg user">
        <div class="msg-content typing-bubble">{{ currentSTTText }}<span class="cursor">|</span></div>
      </div>

      <div v-if="currentSentence" class="msg assistant">
        <div class="msg-content typing-bubble">{{ currentSentence }}<span class="cursor">|</span></div>
      </div>
    </main>

    <footer class="input-bar">
      <button
        v-if="showTerminateButton"
        @click="terminateCurrentResponse"
        class="terminate-btn"
        :disabled="!isConnected"
      >
        终止
      </button>

      <template v-else>
        <button @click="toggleMode" class="mode-btn" :disabled="isInputBusy">
          {{ inputMode === 'text' ? '语音' : '键盘' }}
        </button>

        <template v-if="inputMode === 'text'">
          <input
            v-model="inputText"
            @keyup.enter="sendText"
            placeholder="说点什么..."
            class="input"
            :disabled="isInputBusy"
          />
          <button
            @click="sendText"
            :disabled="isInputBusy || !inputText.trim()"
            class="send-btn"
          >
            发送
          </button>
        </template>

        <template v-else>
          <button
            @mousedown.prevent="startRecording($event)"
            @mouseup.prevent="stopRecording(undefined, $event)"
            @touchstart.prevent="startRecording($event)"
            @touchend.prevent="stopRecording(undefined, $event)"
            @touchcancel.prevent="stopRecording(true, $event)"
            :class="['voice-btn', { recording: isRecording }]"
            :disabled="!canRecord"
          >
            {{ isRecording ? `松开发送 (${recordingTime}s)` : '按住说话' }}
          </button>
        </template>
      </template>
    </footer>

    <div v-if="isRecording" class="recording-modal">
      <div
        ref="recordingDialogRef"
        :class="['recording-dialog', { cancelling: recordingWillCancel }]"
      >
        <p class="recording-title">正在录音</p>
        <p class="recording-tip">
          {{ recordingWillCancel ? '松开取消发送' : `上滑到弹窗内取消 · 松开发送 · ${recordingTime}s` }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { OpusDecoder } from 'opus-decoder'
import { useWebSocketStore } from '@/stores/websocket'
import { getOpusPlayer } from '@/utils/opusPlayer'
import { createAudioInputBinaryFrame } from '@/utils/wsBinaryProtocol'

const websocketStore = useWebSocketStore()
const { isConnected } = storeToRefs(websocketStore)
const opusPlayer = getOpusPlayer()

const TTS_IDLE_TIMEOUT_MS = 700
const LLM_AUDIO_RECHECK_TIMEOUT_MS = 500
const TTS_START_WAIT_TIMEOUT_MS = 10000

const messages = ref([])
const messagesRef = ref(null)
const inputText = ref('')
const inputMode = ref('text')
const isRecording = ref(false)
const recordingTime = ref(0)
const currentSentence = ref('')
const currentSTTText = ref('')
const recordingState = ref(null)
const recordingDialogRef = ref(null)
const micPermission = ref('unknown')
const recordingWillCancel = ref(false)

const hasSentMessage = ref(false)
const isLlmStreaming = ref(false)
const isTtsStreaming = ref(false)
const isStreamAudioPlaying = ref(false)
const isMessageAudioPlaying = ref(false)
const activeResponse = ref(null)
const currentMessageAudioElement = ref(null)

let responseCounter = 0
let ttsIdleTimer = null
let llmGraceTimer = null
let wsUrl = ''
let recordingTouchIdentifier = null
let unsubscribeStreamPlaybackState = null
let unsubscribeStreamPlaybackEnded = null
let unsubscribeStreamPlaybackPaused = null

const canRecord = computed(() => {
  return isConnected.value && micPermission.value !== 'denied' && !hasSentMessage.value
})

const showTerminateButton = computed(() => {
  if (hasSentMessage.value) {
    return true
  }
  return isStreamAudioPlaying.value || isMessageAudioPlaying.value
})

const isInputBusy = computed(() => {
  return !isConnected.value || hasSentMessage.value
})

watch(isConnected, (connected) => {
  if (!connected) {
    isTtsStreaming.value = false
    isStreamAudioPlaying.value = false
    isMessageAudioPlaying.value = false
  }
})

const unsubscribeWs = websocketStore.onMessage((data) => {
  handleMessage(data)
})

function createResponseTracking() {
  return {
    id: ++responseCounter,
    assistantMessageId: null,
    llmFinished: false,
    llmFinishedAt: 0,
    ttsPackets: [],
    lastTtsAt: 0
  }
}

function clearResponseTimers() {
  if (ttsIdleTimer) {
    clearTimeout(ttsIdleTimer)
    ttsIdleTimer = null
  }

  if (llmGraceTimer) {
    clearTimeout(llmGraceTimer)
    llmGraceTimer = null
  }
}

function beginResponseTracking() {
  clearResponseTimers()
  activeResponse.value = createResponseTracking()
  hasSentMessage.value = true
  isLlmStreaming.value = true
  isTtsStreaming.value = false
}

function ensureActiveResponse() {
  if (!activeResponse.value) {
    activeResponse.value = createResponseTracking()
    hasSentMessage.value = true
  }
  return activeResponse.value
}

function finishResponseTracking(expectedResponseId = null) {
  if (
    expectedResponseId &&
    activeResponse.value &&
    activeResponse.value.id !== expectedResponseId
  ) {
    return
  }

  activeResponse.value = null
  hasSentMessage.value = false
  isLlmStreaming.value = false
  isTtsStreaming.value = false
  clearResponseTimers()
}

function scheduleTtsIdleCheck() {
  if (ttsIdleTimer) {
    clearTimeout(ttsIdleTimer)
  }

  ttsIdleTimer = setTimeout(() => {
    const response = activeResponse.value
    if (!response) {
      isTtsStreaming.value = false
      return
    }

    const idleFor = Date.now() - response.lastTtsAt
    if (idleFor >= TTS_IDLE_TIMEOUT_MS) {
      isTtsStreaming.value = false
      tryFinalizeActiveResponse()
      return
    }

    scheduleTtsIdleCheck()
  }, TTS_IDLE_TIMEOUT_MS)
}

function scheduleLlmGraceCheck(delayMs = LLM_AUDIO_RECHECK_TIMEOUT_MS) {
  if (llmGraceTimer) {
    clearTimeout(llmGraceTimer)
  }

  llmGraceTimer = setTimeout(() => {
    tryFinalizeActiveResponse()
  }, delayMs)
}

async function tryFinalizeActiveResponse() {
  const response = activeResponse.value
  if (!response || !response.llmFinished || isTtsStreaming.value || isStreamAudioPlaying.value) {
    return
  }

  const waitingForFirstTts = response.ttsPackets.length === 0
  if (waitingForFirstTts) {
    const llmFinishedAt = response.llmFinishedAt || Date.now()
    const waitedMs = Date.now() - llmFinishedAt
    if (waitedMs < TTS_START_WAIT_TIMEOUT_MS) {
      scheduleLlmGraceCheck()
      return
    }
  }

  const responseId = response.id
  const messageId = response.assistantMessageId
  const ttsPackets = response.ttsPackets.slice()

  // 先结束当前请求状态，避免“音频已播完但终止按钮仍显示”
  // 后续音频文件生成在后台继续，不阻塞 UI 状态收敛。
  finishResponseTracking(responseId)

  if (ttsPackets.length > 0 && messageId) {
    try {
      const audioUrl = await buildAudioUrlFromPackets(ttsPackets)
      if (audioUrl) {
        attachAudioUrlToMessage(messageId, audioUrl)
      }
    } catch (error) {
      console.error('Failed to build playback audio from TTS packets:', error)
    }
  }
}

function attachAudioUrlToMessage(messageId, audioUrl) {
  const targetMessage = messages.value.find((msg) => msg.id === messageId)
  if (!targetMessage) {
    URL.revokeObjectURL(audioUrl)
    return
  }

  if (targetMessage.audioUrl) {
    URL.revokeObjectURL(targetMessage.audioUrl)
  }

  targetMessage.audioUrl = audioUrl
}

function appendAssistantMessage(content) {
  const text = content?.trim()
  if (!text) {
    return
  }

  const response = ensureActiveResponse()
  const messageId = Date.now() + Math.random()

  messages.value.push({
    id: messageId,
    type: 'text',
    role: 'assistant',
    content: text,
    timestamp: Date.now(),
    audioUrl: ''
  })

  response.assistantMessageId = messageId
  response.llmFinished = true
  response.llmFinishedAt = Date.now()
  isLlmStreaming.value = false
  currentSentence.value = ''

  scrollToBottom()
  scheduleLlmGraceCheck()
}

function parseOpusPacketFrames(packetData) {
  const frames = []
  let offset = 0

  while (offset + 2 <= packetData.length) {
    const frameLength =
      (packetData[offset] & 0xff) |
      ((packetData[offset + 1] & 0xff) << 8)
    offset += 2

    if (frameLength <= 0 || offset + frameLength > packetData.length) {
      break
    }

    frames.push(packetData.slice(offset, offset + frameLength))
    offset += frameLength
  }

  return frames
}

async function buildAudioUrlFromPackets(ttsPackets) {
  const frames = []
  for (const packet of ttsPackets) {
    const packetFrames = parseOpusPacketFrames(new Uint8Array(packet))
    if (packetFrames.length > 0) {
      frames.push(...packetFrames)
    }
  }

  if (frames.length === 0) {
    return ''
  }

  const decoder = new OpusDecoder({
    sampleRate: 24000,
    channels: 1
  })

  await decoder.ready

  const pcmChunks = []
  let totalSamples = 0
  let sampleRate = 24000
  const batchSize = 6

  try {
    for (let i = 0; i < frames.length; i += batchSize) {
      const batch = frames.slice(i, i + batchSize)
      const decoded = decoder.decodeFrames(batch)

      if (decoded?.errors?.length) {
        console.warn('Opus decode warnings:', decoded.errors)
      }

      const channelData = decoded?.channelData?.[0]
      if (channelData && channelData.length > 0) {
        const copy = new Float32Array(channelData.length)
        copy.set(channelData)
        pcmChunks.push(copy)
        totalSamples += copy.length
      }

      if (decoded?.sampleRate) {
        sampleRate = decoded.sampleRate
      }
    }
  } finally {
    decoder.free()
  }

  if (totalSamples === 0) {
    return ''
  }

  const mergedPcm = new Float32Array(totalSamples)
  let offset = 0
  for (const chunk of pcmChunks) {
    mergedPcm.set(chunk, offset)
    offset += chunk.length
  }

  const wavBuffer = encodeFloatPcmToWav(mergedPcm, sampleRate)
  const blob = new Blob([wavBuffer], { type: 'audio/wav' })
  return URL.createObjectURL(blob)
}

async function handleMessage(data) {
  if (data.type === 'text') {
    appendAssistantMessage(data.text || '')
    return
  }

  if (data.type === 'stt') {
    const isFinal = data.isFinal ?? data.final ?? true
    if (isFinal) {
      const text = (data.text || currentSTTText.value || '').trim()
      if (text) {
        messages.value.push({
          id: Date.now() + Math.random(),
          type: 'text',
          role: 'user',
          content: text,
          timestamp: Date.now()
        })
      }
      currentSTTText.value = ''
      scrollToBottom()
      return
    }

    currentSTTText.value = data.text || currentSTTText.value
    scrollToBottom()
    return
  }

  if (data.type === 'llm_token') {
    const response = ensureActiveResponse()

    if (data.finished) {
      if (data.accumulated) {
        appendAssistantMessage(data.accumulated)
      } else {
        response.llmFinished = true
        response.llmFinishedAt = Date.now()
        isLlmStreaming.value = false
        currentSentence.value = ''
        scheduleLlmGraceCheck()
      }
      scrollToBottom()
      return
    }

    isLlmStreaming.value = true
    currentSentence.value = data.accumulated || ''
    scrollToBottom()
    return
  }

  if (data.type === 'tts') {
    const response = ensureActiveResponse()
    const payload = data.binary && data.data instanceof ArrayBuffer
      ? data.data
      : new ArrayBuffer(0)

    if (payload.byteLength > 0) {
      response.ttsPackets.push(payload.slice(0))
    }

    response.lastTtsAt = Date.now()
    isTtsStreaming.value = true
    scheduleTtsIdleCheck()

    opusPlayer.feed(payload, Boolean(data.finished))
    updateStreamPlayingState()
    return
  }

  if (data.type === 'error') {
    console.error('WebSocket error message:', data.message)
    finishResponseTracking()
  }
}

function sendText() {
  const text = inputText.value.trim()
  if (!text || isInputBusy.value) {
    return
  }

  beginResponseTracking()

  websocketStore.send({
    type: 'text',
    text
  })

  messages.value.push({
    id: Date.now() + Math.random(),
    type: 'text',
    role: 'user',
    content: text,
    timestamp: Date.now()
  })

  inputText.value = ''
  scrollToBottom()
}

function isMessageWaitingAudio(messageId) {
  const response = activeResponse.value
  if (!response || response.assistantMessageId !== messageId || !response.llmFinished) {
    return false
  }

  return isTtsStreaming.value || response.ttsPackets.length > 0
}

function toggleMode() {
  if (hasSentMessage.value) {
    return
  }
  inputMode.value = inputMode.value === 'text' ? 'voice' : 'text'
}

async function terminateCurrentResponse() {
  if (!showTerminateButton.value) {
    return
  }

  if (currentMessageAudioElement.value) {
    currentMessageAudioElement.value.pause()
    currentMessageAudioElement.value = null
    isMessageAudioPlaying.value = false
  }

  stopRecording(true)
  websocketStore.send({ type: 'terminate' })
  currentSentence.value = ''
  currentSTTText.value = ''
  finishResponseTracking()
  opusPlayer.stop()
  updateStreamPlayingState()
}

function scrollToBottom() {
  setTimeout(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  }, 100)
}

function formatTime(timestamp) {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function updateStreamPlayingState() {
  isStreamAudioPlaying.value = opusPlayer.isPlaying()
}

function handleStreamPlaybackStateChange(playing) {
  isStreamAudioPlaying.value = Boolean(playing)
}

function handleStreamPlaybackEnded() {
  isStreamAudioPlaying.value = false
  tryFinalizeActiveResponse()
}

function handleStreamPlaybackPaused() {
  isStreamAudioPlaying.value = false
}

function onMessageAudioPlay(event) {
  const element = event?.target
  if (!(element instanceof HTMLAudioElement)) {
    return
  }

  if (currentMessageAudioElement.value && currentMessageAudioElement.value !== element) {
    currentMessageAudioElement.value.pause()
  }

  currentMessageAudioElement.value = element
  isMessageAudioPlaying.value = true
}

function onMessageAudioPause(event) {
  const element = event?.target
  if (!(element instanceof HTMLAudioElement)) {
    return
  }

  if (!currentMessageAudioElement.value || currentMessageAudioElement.value === element) {
    isMessageAudioPlaying.value = false
  }
}

function onMessageAudioEnded(event) {
  const element = event?.target
  if (!(element instanceof HTMLAudioElement)) {
    return
  }

  if (currentMessageAudioElement.value === element) {
    currentMessageAudioElement.value = null
  }

  isMessageAudioPlaying.value = false
}

function extractClientPoint(event) {
  if (!event) {
    return null
  }

  if (event.touches && event.touches.length > 0) {
    const sameTouch = recordingTouchIdentifier !== null
      ? Array.from(event.touches).find((touch) => touch.identifier === recordingTouchIdentifier)
      : event.touches[0]
    if (sameTouch) {
      return { x: sameTouch.clientX, y: sameTouch.clientY }
    }
  }

  if (event.changedTouches && event.changedTouches.length > 0) {
    const sameTouch = recordingTouchIdentifier !== null
      ? Array.from(event.changedTouches).find((touch) => touch.identifier === recordingTouchIdentifier)
      : event.changedTouches[0]
    if (sameTouch) {
      return { x: sameTouch.clientX, y: sameTouch.clientY }
    }
  }

  if (typeof event.clientX === 'number' && typeof event.clientY === 'number') {
    return { x: event.clientX, y: event.clientY }
  }

  return null
}

function isPointInsideRecordingDialog(clientX, clientY) {
  if (!recordingDialogRef.value) {
    return false
  }

  const rect = recordingDialogRef.value.getBoundingClientRect()
  return clientX >= rect.left &&
    clientX <= rect.right &&
    clientY >= rect.top &&
    clientY <= rect.bottom
}

function resolveReleaseCancelState(event) {
  const point = extractClientPoint(event)
  if (!point) {
    return recordingWillCancel.value
  }

  return recordingWillCancel.value || isPointInsideRecordingDialog(point.x, point.y)
}

function updateRecordingCancelState(event) {
  if (!isRecording.value || !recordingState.value) {
    return
  }

  if (event?.touches && event.cancelable) {
    event.preventDefault()
  }

  const point = extractClientPoint(event)
  if (!point) {
    return
  }

  recordingWillCancel.value = isPointInsideRecordingDialog(point.x, point.y)
}

function handleRecordingPointerUp(event) {
  stopRecording(undefined, event)
}

function setupRecordingPointerListeners() {
  window.addEventListener('mousemove', updateRecordingCancelState)
  window.addEventListener('mouseup', handleRecordingPointerUp)
  window.addEventListener('touchmove', updateRecordingCancelState, { passive: false })
  window.addEventListener('touchend', handleRecordingPointerUp)
  window.addEventListener('touchcancel', handleRecordingPointerUp)
}

function teardownRecordingPointerListeners() {
  window.removeEventListener('mousemove', updateRecordingCancelState)
  window.removeEventListener('mouseup', handleRecordingPointerUp)
  window.removeEventListener('touchmove', updateRecordingCancelState)
  window.removeEventListener('touchend', handleRecordingPointerUp)
  window.removeEventListener('touchcancel', handleRecordingPointerUp)
}

async function startRecording(event) {
  if (isRecording.value || recordingState.value || hasSentMessage.value) {
    return
  }

  if (micPermission.value === 'denied') {
    alert('麦克风权限被拒绝。请在浏览器设置中允许麦克风访问。')
    return
  }

  if (micPermission.value !== 'granted') {
    const granted = await requestMicPermission()
    if (!granted) {
      return
    }
  }

  try {
    recordingTouchIdentifier = event?.touches?.length ? event.touches[0].identifier : null
    recordingWillCancel.value = false

    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const preferredMimeType = getPreferredRecordingMimeType()
    const mediaRecorder = preferredMimeType
      ? new MediaRecorder(stream, { mimeType: preferredMimeType })
      : new MediaRecorder(stream)

    const recordingSession = {
      chunks: [],
      cancelled: false
    }

    mediaRecorder.ondataavailable = (dataEvent) => {
      if (dataEvent.data && dataEvent.data.size > 0) {
        recordingSession.chunks.push(dataEvent.data)
      }
    }

    mediaRecorder.onstop = async () => {
      if (recordingSession.cancelled || recordingSession.chunks.length === 0) {
        recordingSession.chunks.length = 0
        return
      }

      try {
        const sourceBlob = new Blob(
          recordingSession.chunks,
          { type: mediaRecorder.mimeType || preferredMimeType || 'audio/webm' }
        )
        const wavBuffer = await convertBlobToWav(sourceBlob)

        beginResponseTracking()
        const finalFrame = createAudioInputBinaryFrame('wav', wavBuffer, true)
        websocketStore.sendBinary(finalFrame)
      } catch (error) {
        console.error('Error converting recording to wav:', error)
        finishResponseTracking()
        alert('语音处理失败，请重试')
      }
    }

    mediaRecorder.start()
    isRecording.value = true
    recordingTime.value = 0
    setupRecordingPointerListeners()

    const timer = setInterval(() => {
      recordingTime.value += 1
    }, 1000)

    recordingState.value = {
      mediaRecorder,
      stream,
      timer,
      recordingSession
    }
  } catch (error) {
    console.error('Error starting recording:', error)
    teardownRecordingPointerListeners()
    recordingWillCancel.value = false
    recordingTouchIdentifier = null
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      micPermission.value = 'denied'
      alert('麦克风权限被拒绝。请在浏览器设置中允许麦克风访问。')
    } else {
      alert(`无法访问麦克风: ${error.message}`)
    }
  }
}

function stopRecording(forceCancel, event) {
  if (!recordingState.value) {
    return
  }

  const shouldCancel = typeof forceCancel === 'boolean'
    ? forceCancel
    : resolveReleaseCancelState(event)

  const { mediaRecorder, stream, timer, recordingSession } = recordingState.value
  recordingSession.cancelled = shouldCancel
  if (shouldCancel) {
    recordingSession.chunks.length = 0
  }

  if (mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }

  stream.getTracks().forEach((track) => track.stop())
  clearInterval(timer)

  isRecording.value = false
  recordingTime.value = 0
  recordingWillCancel.value = false
  recordingTouchIdentifier = null
  teardownRecordingPointerListeners()
  recordingState.value = null
}

async function requestMicPermissionOnLoad() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    micPermission.value = 'denied'
    return
  }

  try {
    if (navigator.permissions) {
      const result = await navigator.permissions.query({ name: 'microphone' })
      micPermission.value = result.state
      result.onchange = () => {
        micPermission.value = result.state
      }

      if (result.state !== 'granted') {
        await requestMicPermission()
      }
      return
    }
  } catch {
    // Ignore and fallback to direct request.
  }

  await requestMicPermission()
}

async function requestMicPermission() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    alert('您的浏览器不支持麦克风访问')
    return false
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    stream.getTracks().forEach((track) => track.stop())
    micPermission.value = 'granted'
    return true
  } catch (error) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      micPermission.value = 'denied'
      alert('麦克风权限被拒绝。请在浏览器设置中允许麦克风访问，然后刷新页面。')
    } else {
      console.error('Microphone permission error:', error)
      alert(`无法访问麦克风: ${error.message}`)
    }
    return false
  }
}

function getPreferredRecordingMimeType() {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return ''
  }

  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus']
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || ''
}

async function convertBlobToWav(blob) {
  const AudioContextClass = window.AudioContext || window.webkitAudioContext
  if (!AudioContextClass) {
    throw new Error('当前浏览器不支持AudioContext')
  }

  const sourceBuffer = await blob.arrayBuffer()
  const audioContext = new AudioContextClass()

  try {
    const audioBuffer = await audioContext.decodeAudioData(sourceBuffer.slice(0))
    return encodeAudioBufferToWav(audioBuffer)
  } finally {
    await audioContext.close().catch(() => {})
  }
}

function encodeAudioBufferToWav(audioBuffer) {
  const monoData = toMonoChannelData(audioBuffer)
  return encodeFloatPcmToWav(monoData, Math.round(audioBuffer.sampleRate))
}

function toMonoChannelData(audioBuffer) {
  if (audioBuffer.numberOfChannels === 1) {
    return audioBuffer.getChannelData(0)
  }

  const channelCount = audioBuffer.numberOfChannels
  const mono = new Float32Array(audioBuffer.length)

  for (let channel = 0; channel < channelCount; channel += 1) {
    const channelData = audioBuffer.getChannelData(channel)
    for (let i = 0; i < channelData.length; i += 1) {
      mono[i] += channelData[i]
    }
  }

  for (let i = 0; i < mono.length; i += 1) {
    mono[i] /= channelCount
  }

  return mono
}

function encodeFloatPcmToWav(pcmData, sampleRate) {
  const channelCount = 1
  const bytesPerSample = 2
  const blockAlign = channelCount * bytesPerSample
  const byteRate = sampleRate * blockAlign
  const pcmByteLength = pcmData.length * bytesPerSample

  const wavBuffer = new ArrayBuffer(44 + pcmByteLength)
  const view = new DataView(wavBuffer)

  writeAscii(view, 0, 'RIFF')
  view.setUint32(4, 36 + pcmByteLength, true)
  writeAscii(view, 8, 'WAVE')
  writeAscii(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, channelCount, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, byteRate, true)
  view.setUint16(32, blockAlign, true)
  view.setUint16(34, 16, true)
  writeAscii(view, 36, 'data')
  view.setUint32(40, pcmByteLength, true)

  let offset = 44
  for (let i = 0; i < pcmData.length; i += 1) {
    const sample = Math.max(-1, Math.min(1, pcmData[i]))
    const pcm = sample < 0 ? sample * 0x8000 : sample * 0x7fff
    view.setInt16(offset, pcm, true)
    offset += 2
  }

  return wavBuffer
}

function writeAscii(view, offset, text) {
  for (let i = 0; i < text.length; i += 1) {
    view.setUint8(offset + i, text.charCodeAt(i))
  }
}

function buildWsUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/conversation`
}

function revokeAllAudioUrls() {
  if (currentMessageAudioElement.value) {
    currentMessageAudioElement.value.pause()
    currentMessageAudioElement.value = null
  }
  isMessageAudioPlaying.value = false

  for (const message of messages.value) {
    if (message.audioUrl) {
      URL.revokeObjectURL(message.audioUrl)
      message.audioUrl = ''
    }
  }
}

onMounted(async () => {
  await opusPlayer.init()
  updateStreamPlayingState()
  unsubscribeStreamPlaybackState = opusPlayer.onPlaybackStateChange(handleStreamPlaybackStateChange)
  unsubscribeStreamPlaybackEnded = opusPlayer.onPlaybackEnded(handleStreamPlaybackEnded)
  unsubscribeStreamPlaybackPaused = opusPlayer.onPlaybackPaused(handleStreamPlaybackPaused)
  await requestMicPermissionOnLoad()

  wsUrl = buildWsUrl()
  websocketStore.connect(wsUrl)
})

onUnmounted(() => {
  unsubscribeWs()
  clearResponseTimers()
  stopRecording(true)
  finishResponseTracking()
  revokeAllAudioUrls()
  if (unsubscribeStreamPlaybackState) {
    unsubscribeStreamPlaybackState()
    unsubscribeStreamPlaybackState = null
  }
  if (unsubscribeStreamPlaybackEnded) {
    unsubscribeStreamPlaybackEnded()
    unsubscribeStreamPlaybackEnded = null
  }
  if (unsubscribeStreamPlaybackPaused) {
    unsubscribeStreamPlaybackPaused()
    unsubscribeStreamPlaybackPaused = null
  }

  websocketStore.disconnect()
  opusPlayer.destroy().catch((error) => {
    console.error('Failed to destroy opus player:', error)
  })
})
</script>

<style>
@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600&display=swap');
</style>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #ffffff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  color: #1a1a1a;
  -webkit-font-smoothing: antialiased;
}

.header {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 24px;
  border-bottom: 1px solid #f0f0f0;
}

.status {
  font-size: 0.75rem;
  font-weight: 500;
  color: #a0a0a0;
  letter-spacing: 0.05em;
  transition: 0.2s ease;
}

.status.online {
  color: #1a1a1a;
}

.chat {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.chat::-webkit-scrollbar {
  width: 4px;
}

.chat::-webkit-scrollbar-track {
  background: transparent;
}

.chat::-webkit-scrollbar-thumb {
  background: #f0f0f0;
  border-radius: 2px;
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: #a0a0a0;
}

.empty-icon {
  font-size: 3rem;
  opacity: 0.6;
}

.empty p {
  font-size: 1rem;
  font-weight: 400;
  letter-spacing: 0.1em;
}

.msg {
  display: flex;
  flex-direction: column;
  max-width: 75%;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(8px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.msg.user {
  align-self: flex-end;
  align-items: flex-end;
}

.msg.assistant {
  align-self: flex-start;
  align-items: flex-start;
}

.msg-content {
  padding: 12px 20px;
  font-size: 0.95rem;
  line-height: 1.6;
  border-radius: 20px;
  word-break: break-word;
}

.msg.user .msg-content {
  background: #1a1a1a;
  color: #ffffff;
  border-bottom-right-radius: 4px;
}

.msg.assistant .msg-content {
  background: #fafafa;
  color: #1a1a1a;
  border-bottom-left-radius: 4px;
}

.msg-audio {
  margin-top: 8px;
  width: min(320px, 70vw);
  max-width: 100%;
  height: 36px;
}

.msg-audio-pending {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 12px;
  background: #f6f6f6;
  color: #8a8a8a;
  font-size: 0.75rem;
}

.msg-time {
  font-size: 0.7rem;
  color: #a0a0a0;
  margin-top: 4px;
  padding: 0 8px;
}

.typing-bubble {
  position: relative;
}

.typing-bubble .cursor {
  animation: blink 0.8s infinite;
  color: #007bff;
  font-weight: bold;
}

@keyframes blink {
  0%,
  50% {
    opacity: 1;
  }

  51%,
  100% {
    opacity: 0;
  }
}

.input-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #f0f0f0;
  background: #ffffff;
}

.mode-btn {
  height: 44px;
  min-width: 64px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border: none;
  border-radius: 22px;
  font-size: 0.85rem;
  cursor: pointer;
  transition: 0.2s ease;
  flex-shrink: 0;
}

.mode-btn:hover:not(:disabled) {
  background: #f0f0f0;
}

.mode-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.input {
  flex: 1;
  height: 44px;
  padding: 0 20px;
  background: #fafafa;
  border: 2px solid transparent;
  border-radius: 22px;
  font-size: 0.95rem;
  font-family: inherit;
  color: #1a1a1a;
  outline: none;
  transition: 0.2s ease;
  min-width: 0;
}

.input::placeholder {
  color: #a0a0a0;
}

.input:focus {
  border-color: #1a1a1a;
  background: #ffffff;
}

.input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.send-btn {
  height: 44px;
  min-width: 64px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #1a1a1a;
  color: #ffffff;
  border: none;
  border-radius: 22px;
  font-size: 0.85rem;
  cursor: pointer;
  transition: 0.2s ease;
  flex-shrink: 0;
}

.send-btn:hover:not(:disabled) {
  transform: scale(1.03);
}

.send-btn:disabled {
  background: #f0f0f0;
  color: #a0a0a0;
  cursor: not-allowed;
}

.voice-btn {
  flex: 1;
  width: 100%;
  min-width: 0;
  height: 44px;
  background: #fafafa;
  border: 2px solid transparent;
  border-radius: 22px;
  font-size: 0.95rem;
  font-family: inherit;
  color: #666666;
  cursor: pointer;
  transition: 0.2s ease;
  user-select: none;
}

.voice-btn:hover:not(:disabled) {
  background: #f0f0f0;
}

.voice-btn.recording {
  background: #1a1a1a;
  color: #ffffff;
  border-color: #1a1a1a;
}

.voice-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.terminate-btn {
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 23px;
  background: #e74c3c;
  color: #ffffff;
  font-size: 0.95rem;
  font-family: inherit;
  cursor: pointer;
  transition: 0.2s ease;
}

.terminate-btn:hover:not(:disabled) {
  background: #cf3e2f;
}

.terminate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.recording-modal {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.28);
  --input-bar-safe-height: 84px;
  pointer-events: none;
}

.recording-dialog {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(env(safe-area-inset-bottom, 0px) + var(--input-bar-safe-height) + 10px);
  z-index: 1001;
  width: min(280px, calc(100vw - 32px));
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.16);
  border: 1px solid #ececec;
  padding: 18px 16px;
  text-align: center;
  transition: 0.18s ease;
}

.recording-dialog.cancelling {
  box-shadow: 0 10px 30px rgba(231, 76, 60, 0.24);
  border-color: #e74c3c;
  background: #fff6f4;
}

.recording-title {
  font-size: 1rem;
  color: #1a1a1a;
  margin-bottom: 6px;
}

.recording-tip {
  font-size: 0.82rem;
  color: #666666;
}

@media (max-width: 480px) {
  .header {
    padding: 16px 20px;
  }

  .chat {
    padding: 16px;
  }

  .input-bar {
    padding: 12px 16px;
  }

  .msg {
    max-width: 85%;
  }

  .msg-audio {
    width: min(280px, 78vw);
  }

  .recording-modal {
    --input-bar-safe-height: 76px;
  }
}
</style>
