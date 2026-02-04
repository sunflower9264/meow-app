<template>
  <div class="chat-view">
    <header class="header">
      <button @click="goBack" class="back-btn">←</button>
      <span class="status" :class="{ online: isConnected }">
        {{ isConnected ? '在线' : '离线' }}
      </span>
    </header>

    <main class="chat" ref="messagesRef">
      <div v-if="messages.length === 0" class="empty">
        <span class="empty-icon">🐱</span>
        <p>有什么可以帮你？</p>
      </div>

      <div
        v-for="msg in messages"
        :key="msg.id"
        :class="['msg', msg.role]"
      >
        <div class="msg-content">
          <template v-if="msg.type === 'text'">{{ msg.content }}</template>
          <template v-else-if="msg.type === 'audio'">
            <button @click="playAudio(msg)" class="play-btn">▶</button>
          </template>
        </div>
        <span class="msg-time">{{ formatTime(msg.timestamp) }}</span>
      </div>
    </main>

    <div v-if="currentSentence" class="typing">{{ currentSentence }}</div>

    <footer class="input-bar">
      <button @click="toggleMode" class="mode-btn">
        {{ inputMode === 'text' ? '🎤' : '⌨️' }}
      </button>

      <template v-if="inputMode === 'text'">
        <input
          v-model="inputText"
          @keyup.enter="sendText"
          placeholder="说点什么..."
          class="input"
          :disabled="!isConnected"
        />
        <button
          @click="sendText"
          :disabled="!isConnected || !inputText.trim()"
          class="send-btn"
        >
          ↑
        </button>
      </template>

      <template v-else>
        <button
          @mousedown="startRecording"
          @mouseup="stopRecording"
          @mouseleave="stopRecording"
          @touchstart.prevent="startRecording"
          @touchend.prevent="stopRecording"
          :class="['voice-btn', { recording: isRecording }]"
          :disabled="!canRecord"
        >
          {{ isRecording ? `${recordingTime}s` : '按住说话' }}
        </button>
      </template>
    </footer>

    <audio ref="audioPlayer" @ended="onAudioEnded"></audio>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useWebSocketStore } from '@/stores/websocket'
import { getOpusPlayer } from '@/utils/opusPlayer'

// Router
const router = useRouter()
const route = useRoute()

// Stores
const websocketStore = useWebSocketStore()
const { isConnected } = storeToRefs(websocketStore)

// Debug: 监控连接状态变化
watch(isConnected, (newVal) => {
  console.log('Connection status changed:', newVal)
}, { immediate: true })

// Opus stream player
const opusPlayer = getOpusPlayer()

// State
const messages = ref([])
const messagesRef = ref(null)
const inputText = ref('')
const isRecording = ref(false)
const recordingTime = ref(0)
const currentSentence = ref('')
const audioPlayer = ref(null)  // Still used for playAudio function
const lastPlayedUrl = ref('')  // Still used for playAudio function
const recordingState = ref(null) // Stores { mediaRecorder, stream, timer }
const micPermission = ref('unknown') // 'unknown', 'granted', 'denied', 'prompt'
const inputMode = ref('text') // 'text' or 'voice'

// Computed
const canRecord = computed(() => isConnected.value && micPermission.value !== 'denied')

// WebSocket message handlers
websocketStore.onMessage((data) => {
  console.log('Received message:', data)
  handleMessage(data)
})

// Methods
function goBack() {
  router.push('/')
}

function handleMessage(data) {
  const msg = {
    id: Date.now() + Math.random(),
    timestamp: Date.now(),
    ...data
  }

  if (data.type === 'text') {
    // AI text response
    msg.type = 'text'
    msg.role = 'assistant'
    msg.content = data.text
    messages.value.push(msg)
    scrollToBottom()
  } else if (data.type === 'stt') {
    // Speech recognition result
    msg.type = 'text'
    msg.role = 'user'
    msg.content = data.text
    messages.value.push(msg)
    scrollToBottom()
  } else if (data.type === 'tts') {
    // TTS audio from backend - raw Opus frames, decode and play via Web Audio API
    if (data.data && data.data.length > 0) {
      const audioData = base64ToArrayBuffer(data.data)
      opusPlayer.feed(audioData)
    }
  } else if (data.type === 'sentence') {
    // Streaming sentence from AI
    if (data.eventType === 'sentence_start') {
      currentSentence.value = data.text
    } else if (data.eventType === 'sentence_end') {
      // Add AI message bubble when sentence ends
      messages.value.push({
        id: Date.now() + Math.random(),
        type: 'text',
        role: 'assistant',
        content: data.text,
        timestamp: Date.now()
      })
      currentSentence.value = ''
      scrollToBottom()
    }
  }
}

function sendText() {
  if (!inputText.value.trim() || !isConnected.value) return

  websocketStore.send({
    type: 'text',
    text: inputText.value
  })

  // Add user message immediately
  messages.value.push({
    id: Date.now(),
    type: 'text',
    role: 'user',
    content: inputText.value,
    timestamp: Date.now()
  })

  inputText.value = ''
  scrollToBottom()
}

async function startRecording() {
  // Check and request microphone permission first
  if (micPermission.value === 'denied') {
    alert('麦克风权限被拒绝。请在浏览器设置中允许麦克风访问。')
    return
  }

  if (micPermission.value !== 'granted') {
    const granted = await requestMicPermission()
    if (!granted) return
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm' })

    mediaRecorder.ondataavailable = async (event) => {
      if (event.data.size > 0) {
        const arrayBuffer = await event.data.arrayBuffer()
        const base64 = arrayBufferToBase64(arrayBuffer)
        websocketStore.send({
          type: 'audio',
          format: 'webm',
          data: base64,
          isLast: true
        })
      }
    }

    mediaRecorder.start()
    isRecording.value = true
    recordingTime.value = 0

    // Start timer
    const timer = setInterval(() => {
      recordingTime.value++
    }, 1000)

    // Store state for cleanup
    recordingState.value = { mediaRecorder, stream, timer }
  } catch (error) {
    console.error('Error starting recording:', error)
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      micPermission.value = 'denied'
      alert('麦克风权限被拒绝。请在浏览器设置中允许麦克风访问。')
    } else {
      alert('无法访问麦克风: ' + error.message)
    }
  }
}

function stopRecording() {
  if (recordingState.value) {
    const { mediaRecorder, stream, timer } = recordingState.value

    mediaRecorder.stop()
    stream.getTracks().forEach(track => track.stop())
    clearInterval(timer)

    isRecording.value = false
    recordingTime.value = 0
    recordingState.value = null
  }
}

function playAudio(msg) {
  if (msg.audioUrl) {
    // Clean up previous URL
    if (lastPlayedUrl.value) {
      URL.revokeObjectURL(lastPlayedUrl.value)
    }

    lastPlayedUrl.value = msg.audioUrl
    audioPlayer.value.src = msg.audioUrl
    audioPlayer.value.play().catch(err => console.error('Audio play error:', err))

    msg.played = true
  }
}

function onAudioEnded() {
  // No longer needed for TTS - kept for potential future use
}

function scrollToBottom() {
  setTimeout(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  }, 100)
}

function toggleMode() {
  inputMode.value = inputMode.value === 'text' ? 'voice' : 'text'
}

function formatTime(timestamp) {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// Audio utilities
function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}

function base64ToArrayBuffer(base64) {
  const binaryString = atob(base64)
  const bytes = new Uint8Array(binaryString.length)
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i)
  }
  return bytes.buffer
}

// Microphone permission handling
async function requestMicPermissionOnLoad() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    micPermission.value = 'denied'
    return
  }

  try {
    // Check permission status if Permissions API is available
    if (navigator.permissions) {
      const result = await navigator.permissions.query({ name: 'microphone' })
      micPermission.value = result.state
      result.onchange = () => {
        micPermission.value = result.state
      }

      // If not granted, request permission
      if (result.state !== 'granted') {
        await requestMicPermission()
      }
    } else {
      // Permissions API not supported, try to request directly
      await requestMicPermission()
    }
  } catch (error) {
    // Permissions API not supported, try to request directly
    await requestMicPermission()
  }
}

async function requestMicPermission() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    alert('您的浏览器不支持麦克风访问')
    return false
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    // Permission granted, immediately release the stream
    stream.getTracks().forEach(track => track.stop())
    micPermission.value = 'granted'
    return true
  } catch (error) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      micPermission.value = 'denied'
      alert('麦克风权限被拒绝。请在浏览器设置中允许麦克风访问，然后刷新页面。')
    } else {
      console.error('Microphone permission error:', error)
      alert('无法访问麦克风: ' + error.message)
    }
    return false
  }
}

// Lifecycle
onMounted(async () => {
  // Initialize opus player
  await opusPlayer.init()
  // Request microphone permission on page load
  await requestMicPermissionOnLoad()
  // Use relative URL to leverage Vite proxy (ws://localhost:5173/ws/conversation -> ws://localhost:9090/ws/conversation)
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${wsProtocol}//${window.location.host}/ws/conversation`
  websocketStore.connect(wsUrl)
})

onUnmounted(async () => {
  websocketStore.disconnect()
  await opusPlayer.destroy()
})
</script>

<style>
/*
 * DESIGN DIRECTION: Minimalist Zen / 禅意极简
 *
 * Aesthetic: Severe minimalism with warmth
 * Differentiation: This avoids generic UI by using extreme restraint,
 * asymmetric spacing, and a single-character logo instead of icons/illustrations.
 *
 * Typography: Noto Serif SC (display) + system sans-serif (body)
 * Color: Pure black/white with warm gray accents
 * Motion: Single breathing animation on status indicator
 */

@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600&display=swap');
</style>

<style scoped>
/* Chat View Container */
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #ffffff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  color: #1a1a1a;
  -webkit-font-smoothing: antialiased;
}

/* Header */
.header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 20px 24px;
  border-bottom: 1px solid #f0f0f0;
}

.back-btn {
  position: absolute;
  left: 24px;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border: none;
  border-radius: 50%;
  font-size: 1.2rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.back-btn:hover {
  background: #f0f0f0;
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

/* Chat Area */
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

/* Empty State */
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

/* Messages */
.msg {
  display: flex;
  flex-direction: column;
  max-width: 75%;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
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

.msg-time {
  font-size: 0.7rem;
  color: #a0a0a0;
  margin-top: 4px;
  padding: 0 8px;
}

/* Play Button */
.msg .play-btn {
  background: none;
  border: 1px solid currentColor;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  cursor: pointer;
  font-size: 0.75rem;
  transition: 0.2s ease;
}

.msg .play-btn:hover {
  background: #1a1a1a;
  color: #ffffff;
  border-color: #1a1a1a;
}

/* Typing Indicator */
.typing {
  padding: 12px 24px;
  font-size: 0.85rem;
  color: #666666;
  background: #fafafa;
  text-align: center;
  letter-spacing: 0.02em;
}

/* Input Bar */
.input-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #f0f0f0;
  background: #ffffff;
}

.mode-btn {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border: none;
  border-radius: 50%;
  font-size: 1.1rem;
  cursor: pointer;
  transition: 0.2s ease;
  flex-shrink: 0;
}

.mode-btn:hover {
  background: #f0f0f0;
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
}

.input::placeholder {
  color: #a0a0a0;
}

.input:focus {
  border-color: #1a1a1a;
  background: #ffffff;
}

.input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-btn {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #1a1a1a;
  color: #ffffff;
  border: none;
  border-radius: 50%;
  font-size: 1.2rem;
  cursor: pointer;
  transition: 0.2s ease;
  flex-shrink: 0;
}

.send-btn:hover:not(:disabled) {
  transform: scale(1.05);
}

.send-btn:active:not(:disabled) {
  transform: scale(0.95);
}

.send-btn:disabled {
  background: #f0f0f0;
  color: #a0a0a0;
  cursor: not-allowed;
}

/* Voice Button */
.voice-btn {
  flex: 1;
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

/* Responsive */
@media (max-width: 480px) {
  .header {
    padding: 16px 20px;
  }

  .back-btn {
    left: 20px;
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
}
</style>
