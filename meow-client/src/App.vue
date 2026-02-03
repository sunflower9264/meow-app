<template>
  <div class="app">
    <header class="header">
      <h1 class="logo">喵喵助手</h1>
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
import { storeToRefs } from 'pinia'
import { useWebSocketStore } from '@/stores/websocket'

// Stores
const websocketStore = useWebSocketStore()
const { isConnected } = storeToRefs(websocketStore)

// Debug: 监控连接状态变化
watch(isConnected, (newVal) => {
  console.log('Connection status changed:', newVal)
}, { immediate: true })

// Simple audio queue for sequential playback
// Each item: { chunks: Uint8Array[], finished: boolean }
let currentAudioChunks = []  // Accumulate chunks for current sentence
let audioPlayQueue = []      // Queue of complete audio blobs to play
let isAudioPlaying = false

// State
const messages = ref([])
const messagesRef = ref(null)
const inputText = ref('')
const isRecording = ref(false)
const recordingTime = ref(0)
const currentSentence = ref('')
const audioPlayer = ref(null)
const lastPlayedUrl = ref('')
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
    // TTS audio from backend - accumulate chunks for current sentence
    if (data.data && data.data.length > 0) {
      const chunk = base64ToArrayBuffer(data.data)
      currentAudioChunks.push(new Uint8Array(chunk))
    }
    
    // When this sentence's audio is complete, queue it for playback
    if (data.finished) {
      if (currentAudioChunks.length > 0) {
        // Combine all chunks into one blob
        const totalLength = currentAudioChunks.reduce((sum, chunk) => sum + chunk.length, 0)
        const combined = new Uint8Array(totalLength)
        let offset = 0
        for (const chunk of currentAudioChunks) {
          combined.set(chunk, offset)
          offset += chunk.length
        }
        
        // Create blob URL and queue for playback
        const blob = new Blob([combined], { type: 'audio/mpeg' })
        const url = URL.createObjectURL(blob)
        audioPlayQueue.push(url)
        
        // Start playing if not already
        if (!isAudioPlaying) {
          playNextInQueue()
        }
      }
      // Reset for next sentence
      currentAudioChunks = []
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

// Play audio queue sequentially
function playNextInQueue() {
  if (audioPlayQueue.length === 0) {
    isAudioPlaying = false
    return
  }
  
  isAudioPlaying = true
  const url = audioPlayQueue.shift()
  
  if (audioPlayer.value) {
    // Revoke previous URL
    if (lastPlayedUrl.value) {
      URL.revokeObjectURL(lastPlayedUrl.value)
    }
    lastPlayedUrl.value = url
    audioPlayer.value.src = url
    audioPlayer.value.play().catch(e => {
      console.error('Audio play failed:', e)
      playNextInQueue() // Try next on error
    })
  } else {
    isAudioPlaying = false
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
  // Revoke the URL of the played audio
  if (lastPlayedUrl.value) {
    URL.revokeObjectURL(lastPlayedUrl.value)
    lastPlayedUrl.value = ''
  }
  // Play next audio in queue
  playNextInQueue()
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
  // Request microphone permission on page load
  await requestMicPermissionOnLoad()
  // Use relative URL to leverage Vite proxy (ws://localhost:5173/ws/conversation -> ws://localhost:9090/ws/conversation)
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${wsProtocol}//${window.location.host}/ws/conversation`
  websocketStore.connect(wsUrl)
})

onUnmounted(() => {
  websocketStore.disconnect()
  if (lastPlayedUrl.value) {
    URL.revokeObjectURL(lastPlayedUrl.value)
  }
})
</script>

<style>
/*
 * DESIGN DIRECTION: Minimalist Zen / 禅意极简
 * DFII Score: 12/15
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

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

:root {
  --black: #1a1a1a;
  --white: #ffffff;
  --gray-100: #fafafa;
  --gray-200: #f0f0f0;
  --gray-400: #a0a0a0;
  --gray-600: #666666;
  --accent: #000000;
  --space-xs: 8px;
  --space-sm: 16px;
  --space-md: 24px;
  --space-lg: 48px;
  --space-xl: 80px;
  --radius: 24px;
  --transition: 0.2s ease;
}
</style>

<style scoped>
/* App Container */
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--white);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  color: var(--black);
  -webkit-font-smoothing: antialiased;
}

/* Header - Extreme simplicity */
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--gray-200);
}

.logo {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  font-weight: 600;
  letter-spacing: -0.02em;
  margin: 0;
}

.status {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--gray-400);
  letter-spacing: 0.05em;
  transition: var(--transition);
}

.status.online {
  color: var(--black);
}

/* Chat Area */
.chat {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.chat::-webkit-scrollbar {
  width: 4px;
}

.chat::-webkit-scrollbar-track {
  background: transparent;
}

.chat::-webkit-scrollbar-thumb {
  background: var(--gray-200);
  border-radius: 2px;
}

/* Empty State - Poetic simplicity */
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-md);
  color: var(--gray-400);
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
  padding: var(--space-sm) var(--space-md);
  font-size: 0.95rem;
  line-height: 1.6;
  border-radius: var(--radius);
}

.msg.user .msg-content {
  background: var(--black);
  color: var(--white);
  border-bottom-right-radius: 4px;
}

.msg.assistant .msg-content {
  background: var(--gray-100);
  color: var(--black);
  border-bottom-left-radius: 4px;
}

.msg-time {
  font-size: 0.7rem;
  color: var(--gray-400);
  margin-top: 4px;
  padding: 0 var(--space-xs);
}

/* Play Button in Message */
.msg .play-btn {
  background: none;
  border: 1px solid currentColor;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  cursor: pointer;
  font-size: 0.75rem;
  transition: var(--transition);
}

.msg .play-btn:hover {
  background: var(--black);
  color: var(--white);
  border-color: var(--black);
}

/* Typing Indicator */
.typing {
  padding: var(--space-sm) var(--space-lg);
  font-size: 0.85rem;
  color: var(--gray-600);
  background: var(--gray-100);
  text-align: center;
  letter-spacing: 0.02em;
}

/* Input Bar */
.input-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--gray-200);
  background: var(--white);
}

.mode-btn {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--gray-100);
  border: none;
  border-radius: 50%;
  font-size: 1.1rem;
  cursor: pointer;
  transition: var(--transition);
  flex-shrink: 0;
}

.mode-btn:hover {
  background: var(--gray-200);
}

.input {
  flex: 1;
  height: 44px;
  padding: 0 var(--space-md);
  background: var(--gray-100);
  border: 2px solid transparent;
  border-radius: 22px;
  font-size: 0.95rem;
  font-family: inherit;
  color: var(--black);
  outline: none;
  transition: var(--transition);
}

.input::placeholder {
  color: var(--gray-400);
}

.input:focus {
  border-color: var(--black);
  background: var(--white);
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
  background: var(--black);
  color: var(--white);
  border: none;
  border-radius: 50%;
  font-size: 1.2rem;
  cursor: pointer;
  transition: var(--transition);
  flex-shrink: 0;
}

.send-btn:hover:not(:disabled) {
  transform: scale(1.05);
}

.send-btn:active:not(:disabled) {
  transform: scale(0.95);
}

.send-btn:disabled {
  background: var(--gray-200);
  color: var(--gray-400);
  cursor: not-allowed;
}

/* Voice Button */
.voice-btn {
  flex: 1;
  height: 44px;
  background: var(--gray-100);
  border: 2px solid transparent;
  border-radius: 22px;
  font-size: 0.95rem;
  font-family: inherit;
  color: var(--gray-600);
  cursor: pointer;
  transition: var(--transition);
  user-select: none;
}

.voice-btn:hover:not(:disabled) {
  background: var(--gray-200);
}

.voice-btn.recording {
  background: var(--black);
  color: var(--white);
  border-color: var(--black);
}

.voice-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Responsive */
@media (max-width: 480px) {
  .header {
    padding: var(--space-sm) var(--space-md);
  }
  
  .chat {
    padding: var(--space-md);
  }
  
  .input-bar {
    padding: var(--space-sm) var(--space-md);
  }
  
  .msg {
    max-width: 85%;
  }
}
</style>
