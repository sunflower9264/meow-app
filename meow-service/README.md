# Meow Voice Service

Unified service for VAD (Silero) and Edge TTS.

## Features

- **VAD**: Silero VAD for voice activity detection
- **TTS**: Microsoft Edge TTS (free, no API key required)

## Installation

```bash
cd meow-service
pip install -r requirements.txt
```

## Running

```bash
python app.py
```

Service will run on `http://127.0.0.1:8765`

## API Endpoints

### VAD Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/vad` | POST | Single audio VAD detection |
| `/vad/session/start` | POST | Start streaming VAD session |
| `/vad/session/process` | POST | Process audio chunk |
| `/vad/session/end` | POST | End VAD session |

### TTS Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/tts/voices` | GET | List available voices |
| `/tts` | POST | Convert text to speech |
| `/tts/stream` | POST | Streaming audio response |

## Models

Models are loaded from `../xiaozhi-server/models/`:
- `snakers4_silero-vad/` - Silero VAD models

## Common Chinese Voices

- `zh-CN-XiaoxiaoNeural` - Female, young, gentle
- `zh-CN-YunxiNeural` - Male, young
- `zh-CN-YunyangNeural` - Male, adult
- `zh-CN-XiaoyiNeural` - Female, child
- `zh-CN-XiaohanNeural` - Female, calm
