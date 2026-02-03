import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useWebSocketStore = defineStore('websocket', () => {
  const ws = ref(null)
  const reconnectAttempts = ref(0)
  const maxReconnectAttempts = 5
  const reconnectDelay = 3000
  const isConnected = ref(false)

  const messageHandlers = []

  function connect(url) {
    if (ws.value?.readyState === WebSocket.OPEN) {
      return
    }

    console.log('Connecting to WebSocket:', url)

    ws.value = new WebSocket(url)

    ws.value.onopen = () => {
      console.log('WebSocket connected')
      reconnectAttempts.value = 0
      isConnected.value = true
    }

    ws.value.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        messageHandlers.forEach(handler => handler(data))
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    }

    ws.value.onerror = (error) => {
      console.error('WebSocket error:', error)
    }

    ws.value.onclose = (event) => {
      console.log('WebSocket closed:', event.code, event.reason)
      isConnected.value = false
      // Attempt to reconnect
      if (reconnectAttempts.value < maxReconnectAttempts) {
        reconnectAttempts.value++
        console.log(`Reconnecting in ${reconnectDelay}ms... (attempt ${reconnectAttempts.value}/${maxReconnectAttempts})`)
        setTimeout(() => connect(url), reconnectDelay)
      }
    }
  }

  function disconnect() {
    if (ws.value) {
      ws.value.close()
      ws.value = null
    }
    isConnected.value = false
    reconnectAttempts.value = 0
  }

  function send(data) {
    if (ws.value?.readyState === WebSocket.OPEN) {
      const message = JSON.stringify({
        ...data,
        timestamp: Date.now()
      })
      ws.value.send(message)
    } else {
      console.error('WebSocket is not connected')
    }
  }

  function onMessage(handler) {
    messageHandlers.push(handler)
    return () => {
      const index = messageHandlers.indexOf(handler)
      if (index > -1) {
        messageHandlers.splice(index, 1)
      }
    }
  }

  return {
    ws,
    isConnected,
    connect,
    disconnect,
    send,
    onMessage
  }
})
