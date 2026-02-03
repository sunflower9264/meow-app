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

| Type | Fields | Description |
|------|--------|-------------|
| `text` | `text` | Send text message |
| `audio` | `format`, `data`, `isLast` | Send audio chunk (base64) |

### Server -> Client

| Type | Fields | Description |
|------|--------|-------------|
| `text` | `text`, `role` | Text message response |
| `stt` | `text` | Speech-to-text result |
| `tts` | `data`, `format` | Text-to-speech audio (base64) |
| `sentence` | `eventType`, `text` | Sentence boundary event |

## Development Server

The Vite dev server runs on `http://localhost:5173` and proxies WebSocket connections to the backend at `ws://localhost:9090`.

## Browser Compatibility

- Modern browsers with WebSocket support
- MediaRecorder API (Chrome, Firefox, Safari)
- Requires HTTPS or localhost for microphone access
