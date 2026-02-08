# Meow H5 Client

Mobile web client for Meow voice assistant built with Vue 3.

## Features

- Real-time text chat via WebSocket
- Voice recording with MediaRecorder API
- Text-to-speech audio playback
- Mobile-responsive design
- Dual streaming support (text + audio streams)

## Installation

```bash
cd meow-client
npm install
```

## Running

Development mode:
```bash
npm run dev
```

Build for production:
```bash
npm run build
```

## Architecture

- **Vue 3** with Composition API
- **Pinia** for state management
- **WebSocket** for real-time communication
- **Vite** for development and building

## WebSocket Message Types

### Client -> Server

Text frames:

| Type | Fields | Description |
|------|--------|-------------|
| `text` | `text` | Send text message |

Binary frames:

| Frame Type | Header | Payload | Description |
|-----------|--------|---------|-------------|
| `1` (audio input) | `magic=0x4D`, `flags(final)`, `format` | raw recorder bytes | Send voice chunks to server |

### Server -> Client

Text frames:

| Type | Fields | Description |
|------|--------|-------------|
| `text` | `text`, `role` | Text message response |
| `stt` | `text` | Speech-to-text result |
| `llm_token` | `token`, `accumulated`, `finished` | Streaming LLM token updates |

Binary frames:

| Frame Type | Header | Payload | Description |
|-----------|--------|---------|-------------|
| `2` (tts output) | `magic=0x4D`, `flags(final)`, `format=opus` | raw opus frame packet | Stream TTS audio to client |

## Development Server

The Vite dev server runs on `http://localhost:5173` and proxies WebSocket connections to the backend at `ws://localhost:9090`.

## Browser Compatibility

- Modern browsers with WebSocket support
- MediaRecorder API (Chrome, Firefox, Safari)
- Requires HTTPS or localhost for microphone access
