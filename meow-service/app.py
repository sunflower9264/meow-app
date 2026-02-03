"""
Meow Voice Service
Unified service for VAD (Silero), ASR (FunASR/SenseVoice) and Edge TTS
Models are loaded from xiaozhi-server/models directory
"""
import os
import io
import sys
import base64
import logging
from typing import Optional, List
from base64 import b64decode, b64encode
from pathlib import Path

import torch
import numpy as np
import edge_tts
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Meow Voice Service")

# Model paths (local models directory)
MODELS_DIR = Path(__file__).parent / "models"
VAD_MODEL_PATH = MODELS_DIR / "snakers4_silero-vad"
ASR_MODEL_PATH = MODELS_DIR / "iic" / "SenseVoiceSmall"

# Global models
vad_model = None
vad_utils = None
asr_model = None
SAMPLE_RATE = 16000


# ============== VAD Models ==============

class VADRequest(BaseModel):
    """VAD request model"""
    audio_data: str  # Base64 encoded audio data
    threshold: float = 0.5
    threshold_low: float = 0.15


class VADResponse(BaseModel):
    """VAD response model"""
    has_voice: bool
    probability: float


class VADStreamRequest(BaseModel):
    """VAD stream request model"""
    audio_chunk: str  # Base64 encoded audio chunk
    session_id: str
    threshold: float = 0.5
    threshold_low: float = 0.15


# Session management for streaming VAD
vad_sessions = {}


# ============== ASR Models ==============

class ASRRequest(BaseModel):
    """ASR request model"""
    audio_data: str  # Base64 encoded audio data (PCM 16-bit, 16kHz)
    format: str = "pcm"  # pcm, wav, webm, opus
    language: str = "zh"


class ASRResponse(BaseModel):
    """ASR response model"""
    text: str
    language: str = "zh"


# ============== TTS Models ==============

class TTSRequest(BaseModel):
    """TTS request model"""
    text: str
    voice: str = "zh-CN-XiaoxiaoNeural"
    rate: str = "+0%"
    pitch: str = "+0Hz"
    volume: str = "+0%"


class TTSResponse(BaseModel):
    """TTS response model"""
    audio_data: str  # Base64 encoded audio
    format: str = "mp3"


class VoicesListResponse(BaseModel):
    """Voices list response"""
    voices: list


# ============== Startup ==============

def check_and_download_vad_model() -> bool:
    """
    Check if VAD model exists, download if missing.
    Returns True if model is available, False otherwise.
    """
    # Check if local VAD model directory exists and has required files
    required_files = [
        VAD_MODEL_PATH / "hubconf.py",
        VAD_MODEL_PATH / "src" / "silero_vad" / "model.py",
        VAD_MODEL_PATH / "src" / "silero_vad" / "data" / "silero_vad.jit"
    ]

    model_exists = VAD_MODEL_PATH.exists() and all(f.exists() for f in required_files)

    if model_exists:
        logger.info(f"VAD model found at {VAD_MODEL_PATH}")
        return True

    # Model missing, need to download
    logger.warning(f"VAD model not found at {VAD_MODEL_PATH}, attempting to download...")
    try:
        # Create parent directories
        VAD_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)

        logger.info("Downloading Silero VAD model from GitHub (snakers4/silero-vad)...")
        model, utils = torch.hub.load(
            repo_or_dir='snakers4/silero-vad',
            model='silero_vad',
            force_reload=True,
            onnx=False,
            source='github'
        )

        # The downloaded model is cached by torch.hub, we can load from cache
        logger.info("VAD model downloaded successfully")
        return True

    except Exception as e:
        logger.error(f"Failed to download VAD model: {e}")
        return False


def check_and_download_asr_model() -> bool:
    """
    Check if ASR model exists, download if missing.
    Returns True if model is available, False otherwise.
    """
    # Check if local ASR model directory exists and has required files
    required_files = [
        ASR_MODEL_PATH / "config.yaml",
        ASR_MODEL_PATH / "model.pt"
    ]

    model_exists = ASR_MODEL_PATH.exists() and all(f.exists() for f in required_files)

    if model_exists:
        logger.info(f"ASR model found at {ASR_MODEL_PATH}")
        return True

    # Model missing, need to download
    logger.warning(f"ASR model not found at {ASR_MODEL_PATH}, attempting to download...")
    try:
        # Create parent directories
        ASR_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)

        logger.info("Downloading FunASR SenseVoiceSmall model from HuggingFace...")
        logger.info("This may take several minutes depending on your network connection...")

        from funasr import AutoModel

        # Download the model (disable_update=False to allow download)
        # Use the official model ID from ModelScope
        model = AutoModel(
            model="iic/SenseVoiceSmall",
            vad_kwargs={"max_single_segment_time": 30000},
            disable_update=False,
            hub="ms",  # Use ModelScope hub
        )

        logger.info("ASR model downloaded successfully")
        return True

    except Exception as e:
        logger.error(f"Failed to download ASR model: {e}")
        logger.error("You may need to download the model manually:")
        logger.error("  git clone https://www.modelscope.cn/iic/SenseVoiceSmall.git")
        logger.error(f"  and place it in {ASR_MODEL_PATH.parent}")
        return False


@app.on_event("startup")
async def load_models():
    """Load models on startup, with automatic download if missing"""
    global vad_model, vad_utils, asr_model

    logger.info(f"Models directory: {MODELS_DIR}")

    # Ensure models directory exists
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    # ===== Load Silero VAD model =====
    logger.info("=" * 50)
    logger.info("Checking Silero VAD model...")

    # First check and download if needed
    if not check_and_download_vad_model():
        logger.error("VAD model is not available. VAD features will be disabled.")

    # Try to load VAD model (prefer local, fallback to cached)
    try:
        if VAD_MODEL_PATH.exists():
            logger.info(f"Loading Silero VAD model from {VAD_MODEL_PATH}...")
            vad_model, vad_utils = torch.hub.load(
                repo_or_dir=str(VAD_MODEL_PATH),
                model='silero_vad',
                force_reload=False,
                onnx=False,
                source='local'
            )
            logger.info("Silero VAD model loaded successfully (local)")
        else:
            # Load from torch hub cache
            logger.info("Loading Silero VAD model from cache...")
            vad_model, vad_utils = torch.hub.load(
                repo_or_dir='snakers4/silero-vad',
                model='silero_vad',
                force_reload=False,
                onnx=False
            )
            logger.info("Silero VAD model loaded successfully (cache)")
    except Exception as e:
        logger.warning(f"Failed to load VAD model: {e}")
        logger.warning("VAD features will not be available")

    # ===== Load FunASR (SenseVoice) model =====
    logger.info("=" * 50)
    logger.info("Checking FunASR SenseVoice model...")

    # Check and download if needed
    if not check_and_download_asr_model():
        logger.error("ASR model is not available. ASR features will be disabled.")

    # Load ASR model
    try:
        logger.info(f"Loading FunASR (SenseVoice) model from {ASR_MODEL_PATH}...")
        from funasr import AutoModel

        # Check if model exists locally
        if ASR_MODEL_PATH.exists() and (ASR_MODEL_PATH / "config.yaml").exists():
            asr_model = AutoModel(
                model=str(ASR_MODEL_PATH),
                vad_kwargs={"max_single_segment_time": 30000},
                disable_update=True,
                hub="hf",
            )
        else:
            # Try loading from ModelScope
            logger.info("Loading ASR model from ModelScope...")
            asr_model = AutoModel(
                model="iic/SenseVoiceSmall",
                vad_kwargs={"max_single_segment_time": 30000},
                disable_update=True,
                hub="ms",
            )

        logger.info("FunASR (SenseVoice) model loaded successfully")
    except Exception as e:
        logger.error(f"Failed to load ASR model: {e}")
        logger.error("ASR features will not be available")
        asr_model = None

    logger.info("=" * 50)
    logger.info("Model loading completed!")
    logger.info(f"  VAD Model: {'Loaded' if vad_model is not None else 'Not Available'}")
    logger.info(f"  ASR Model: {'Loaded' if asr_model is not None else 'Not Available'}")
    logger.info("=" * 50)


# ============== Health Check ==============

@app.get("/health")
async def health():
    """Health check endpoint"""
    return {
        "status": "ok",
        "service": "meow-voice-service",
        "vad_loaded": vad_model is not None,
        "asr_loaded": asr_model is not None,
        "models_dir": str(MODELS_DIR),
        "models_exist": MODELS_DIR.exists()
    }


# ============== ASR Endpoints ==============

@app.post("/asr", response_model=ASRResponse)
async def speech_to_text(request: ASRRequest):
    """
    Convert speech to text using FunASR (SenseVoice)
    Supports PCM (16-bit, 16kHz) and other formats
    """
    if asr_model is None:
        raise HTTPException(status_code=503, detail="ASR model not loaded")

    try:
        import asyncio
        
        # Decode base64 audio data
        audio_bytes = b64decode(request.audio_data)
        
        # Convert audio based on format
        if request.format == "pcm":
            # PCM 16-bit to float32
            audio_int16 = np.frombuffer(audio_bytes, dtype=np.int16)
            audio_float32 = audio_int16.astype(np.float32) / 32768.0
            audio_data = audio_float32.tobytes()
        elif request.format in ["webm", "opus"]:
            # For WebM/Opus, use raw bytes - FunASR can handle it
            audio_data = audio_bytes
        else:
            # For other formats, pass raw bytes
            audio_data = audio_bytes

        # Run ASR in thread pool to avoid blocking
        result = await asyncio.to_thread(
            asr_model.generate,
            input=audio_data,
            cache={},
            language=request.language,
            use_itn=True,
            batch_size_s=60,
        )

        # Extract text from result
        if result and len(result) > 0:
            text = result[0].get("text", "")
        else:
            text = ""

        logger.info(f"ASR result: {text}")
        return ASRResponse(text=text, language=request.language)

    except Exception as e:
        logger.error(f"ASR processing failed: {e}")
        raise HTTPException(status_code=500, detail=f"ASR processing failed: {str(e)}")


# ============== VAD Endpoints ==============

@app.post("/vad", response_model=VADResponse)
async def detect_voice_activity(request: VADRequest):
    """
    Detect voice activity in audio data
    """
    if vad_model is None:
        raise HTTPException(status_code=503, detail="VAD model not loaded")

    try:
        # Decode base64 audio data
        audio_bytes = b64decode(request.audio_data)

        # Convert to numpy array (assuming 16-bit PCM)
        audio_int16 = np.frombuffer(audio_bytes, dtype=np.int16)
        audio_float32 = audio_int16.astype(np.float32) / 32768.0

        # Get speech probability from VAD model
        with torch.no_grad():
            speech_prob = vad_model(torch.from_numpy(audio_float32), SAMPLE_RATE).item()

        # Determine if voice is present using dual threshold
        has_voice = speech_prob >= request.threshold

        return VADResponse(has_voice=has_voice, probability=speech_prob)

    except Exception as e:
        logger.error(f"VAD detection error: {e}")
        raise HTTPException(status_code=500, detail=f"VAD detection failed: {str(e)}")


@app.post("/vad/session/start")
async def start_vad_session(session_id: str):
    """Start a new VAD streaming session"""
    vad_sessions[session_id] = {
        "audio_buffer": [],
        "voice_window": [],
        "last_voice_state": False,
        "silence_frames": 0,
        "has_voice": False
    }
    return {"status": "started", "session_id": session_id}


@app.post("/vad/session/process")
async def process_vad_stream(request: VADStreamRequest):
    """Process audio chunk in streaming VAD session"""
    if vad_model is None:
        raise HTTPException(status_code=503, detail="VAD model not loaded")

    if request.session_id not in vad_sessions:
        raise HTTPException(status_code=404, detail="Session not found")

    try:
        session = vad_sessions[request.session_id]

        # Decode base64 audio chunk
        audio_bytes = b64decode(request.audio_chunk)
        audio_int16 = np.frombuffer(audio_bytes, dtype=np.int16)
        audio_float32 = audio_int16.astype(np.float32) / 32768.0

        # Get speech probability
        with torch.no_grad():
            speech_prob = vad_model(torch.from_numpy(audio_float32), SAMPLE_RATE).item()

        # Dual threshold logic with hysteresis
        current_voice = speech_prob >= request.threshold

        # Update sliding window (last 5 frames)
        session["voice_window"].append(current_voice)
        if len(session["voice_window"]) > 5:
            session["voice_window"].pop(0)

        # Require at least 3 frames with voice to confirm
        voice_confirmed = sum(session["voice_window"]) >= 3

        # Track silence for speech end detection
        speech_ended = False
        if voice_confirmed:
            session["has_voice"] = True
            session["silence_frames"] = 0
            session["last_voice_state"] = True
        elif speech_prob <= request.threshold_low:
            session["silence_frames"] += 1
            # Speech ended after ~1 second of silence (16 frames at 60ms)
            if session["silence_frames"] >= 16:
                speech_ended = True
                session["has_voice"] = False
        else:
            session["silence_frames"] = 0

        return {
            "has_voice": voice_confirmed,
            "probability": speech_prob,
            "speech_ended": speech_ended,
            "session_active": True
        }

    except Exception as e:
        logger.error(f"VAD stream processing error: {e}")
        raise HTTPException(status_code=500, detail=f"VAD stream processing failed: {str(e)}")


@app.post("/vad/session/end")
async def end_vad_session(request: dict):
    """End VAD streaming session"""
    session_id = request.get("session_id")
    if session_id in vad_sessions:
        session_data = vad_sessions.pop(session_id)
        return {
            "status": "ended",
            "session_id": session_id,
            "had_voice": session_data.get("has_voice", False)
        }
    return {"status": "not_found", "session_id": session_id}


# ============== TTS Endpoints ==============

@app.get("/tts/voices", response_model=VoicesListResponse)
async def list_voices():
    """List available voices"""
    try:
        voices = []
        async for voice in edge_tts.list_voices():
            if "zh-CN" in voice.get("Locale", "") or "en-US" in voice.get("Locale", ""):
                voices.append({
                    "name": voice["Name"],
                    "locale": voice["Locale"],
                    "gender": voice.get("Gender", ""),
                    "description": voice.get("Description", "")
                })
        return VoicesListResponse(voices=voices[:20])
    except Exception as e:
        logger.error(f"Failed to list voices: {e}")
        return VoicesListResponse(voices=[])


@app.post("/tts", response_model=TTSResponse)
async def text_to_speech(request: TTSRequest):
    """
    Convert text to speech using Microsoft Edge TTS
    """
    try:
        logger.info(f"TTS request: text='{request.text[:50]}...', voice={request.voice}")

        # Create communicate object
        communicate = edge_tts.Communicate(
            text=request.text,
            voice=request.voice,
            rate=request.rate,
            pitch=request.pitch,
            volume=request.volume
        )

        # Generate audio
        audio_data = b""
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_data += chunk["data"]

        # Encode to base64
        audio_base64 = base64.b64encode(audio_data).decode("utf-8")

        return TTSResponse(audio_data=audio_base64, format="mp3")

    except Exception as e:
        logger.error(f"TTS generation failed: {e}")
        raise HTTPException(status_code=500, detail=f"TTS generation failed: {str(e)}")


@app.post("/tts/stream")
async def text_to_speech_stream(request: TTSRequest):
    """
    Convert text to speech with TRUE streaming response
    Uses Server-Sent Events (SSE) to stream audio chunks as they're generated
    """
    import traceback
    from fastapi.responses import StreamingResponse
    
    async def generate_audio():
        try:
            text_preview = request.text[:50] if len(request.text) > 50 else request.text
            logger.info(f"TTS stream request: text='{text_preview}', voice={request.voice}")

            # Create communicate object
            communicate = edge_tts.Communicate(
                text=request.text,
                voice=request.voice,
                rate=request.rate,
                pitch=request.pitch,
                volume=request.volume
            )

            # Stream audio chunks as they're generated
            total_bytes = 0
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_chunk = chunk["data"]
                    total_bytes += len(audio_chunk)
                    yield audio_chunk

            logger.info(f"TTS stream completed: {total_bytes} bytes")
            
        except Exception as e:
            logger.error(f"TTS stream generation failed: {e}")
            logger.error(f"Full traceback:\n{traceback.format_exc()}")
            # Can't raise HTTP exception in generator, just log and stop
            return
    
    return StreamingResponse(
        generate_audio(),
        media_type="audio/mpeg",
        headers={"X-Content-Type-Options": "nosniff"}
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8765)
