const FRAME_MAGIC = 0x4d // 'M'
const FRAME_TYPE_AUDIO_INPUT = 1
const FRAME_TYPE_TTS_OUTPUT = 2
const FLAG_FINAL = 0x01

const textEncoder = new TextEncoder()
const textDecoder = new TextDecoder()

function toUint8Array(data) {
  if (!data) {
    return new Uint8Array(0)
  }
  if (data instanceof Uint8Array) {
    return data
  }
  if (data instanceof ArrayBuffer) {
    return new Uint8Array(data)
  }
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
  }
  throw new TypeError('Unsupported binary payload type')
}

export function encodeBinaryFrame({ frameType, format = '', isFinal = false, payload = null }) {
  const payloadBytes = toUint8Array(payload)
  const formatBytes = textEncoder.encode(format)

  if (formatBytes.length > 255) {
    throw new Error('Format is too long for binary frame header')
  }

  const headerLength = 4 + formatBytes.length
  const frame = new Uint8Array(headerLength + payloadBytes.length)
  frame[0] = FRAME_MAGIC
  frame[1] = frameType & 0xff
  frame[2] = isFinal ? FLAG_FINAL : 0
  frame[3] = formatBytes.length
  frame.set(formatBytes, 4)
  frame.set(payloadBytes, headerLength)
  return frame.buffer
}

export function createAudioInputBinaryFrame(format, payload, isLast) {
  return encodeBinaryFrame({
    frameType: FRAME_TYPE_AUDIO_INPUT,
    format,
    isFinal: Boolean(isLast),
    payload
  })
}

export function decodeBinaryFrame(data) {
  const bytes = toUint8Array(data)
  if (bytes.length < 4) {
    throw new Error('Binary frame is too short')
  }
  if (bytes[0] !== FRAME_MAGIC) {
    throw new Error('Invalid binary frame magic')
  }

  const frameType = bytes[1]
  const flags = bytes[2]
  const formatLength = bytes[3]
  const headerLength = 4 + formatLength

  if (bytes.length < headerLength) {
    throw new Error('Invalid binary frame header length')
  }

  const format = textDecoder.decode(bytes.subarray(4, headerLength))
  const payload = bytes.subarray(headerLength).slice().buffer

  return {
    frameType,
    format,
    isFinal: (flags & FLAG_FINAL) !== 0,
    payload
  }
}

export function parseIncomingBinaryMessage(data) {
  const frame = decodeBinaryFrame(data)
  if (frame.frameType === FRAME_TYPE_TTS_OUTPUT) {
    return {
      type: 'tts',
      format: frame.format || 'opus',
      data: frame.payload,
      finished: frame.isFinal,
      binary: true
    }
  }

  return {
    type: 'unknown_binary',
    frameType: frame.frameType,
    format: frame.format,
    data: frame.payload,
    finished: frame.isFinal,
    binary: true
  }
}

export const wsBinaryFrameTypes = {
  audioInput: FRAME_TYPE_AUDIO_INPUT,
  ttsOutput: FRAME_TYPE_TTS_OUTPUT
}
