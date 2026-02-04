import { OpusDecoder } from 'opus-decoder'

/**
 * Opus 音频流播放器
 * 接收裸 Opus 帧数据，解码后通过 Web Audio API 播放
 */
export class OpusStreamPlayer {
  constructor() {
    this.audioContext = null
    this.decoder = null
    this.isInitialized = false
    this.isPlaying = false
    
    // 音频缓冲队列
    this.audioQueue = []
    this.currentSource = null
    this.nextStartTime = 0
    
    // Opus 参数（与后端一致）
    this.sampleRate = 24000
    this.channels = 1
  }

  /**
   * 初始化播放器
   */
  async init() {
    if (this.isInitialized) return

    // 创建 AudioContext
    this.audioContext = new (window.AudioContext || window.webkitAudioContext)({
      sampleRate: this.sampleRate
    })

    // 初始化 Opus 解码器
    this.decoder = new OpusDecoder({
      sampleRate: this.sampleRate,
      channels: this.channels
    })
    await this.decoder.ready

    this.isInitialized = true
    console.log('OpusStreamPlayer initialized')
  }

  /**
   * 确保 AudioContext 处于运行状态
   */
  async resume() {
    if (this.audioContext && this.audioContext.state === 'suspended') {
      await this.audioContext.resume()
    }
  }

  /**
   * 解析裸 Opus 数据（带2字节长度头的多帧格式）
   * @param {Uint8Array} data - 裸 Opus 数据
   * @returns {Uint8Array[]} - Opus 帧数组
   */
  parseOpusFrames(data) {
    const frames = []
    let offset = 0

    while (offset + 2 <= data.length) {
      // 读取帧长度（2字节小端）
      const frameLength = data[offset] | (data[offset + 1] << 8)
      offset += 2

      if (offset + frameLength > data.length) {
        console.warn('Incomplete Opus frame data')
        break
      }

      // 提取帧数据
      const frameData = data.slice(offset, offset + frameLength)
      frames.push(frameData)
      offset += frameLength
    }

    return frames
  }

  /**
   * 接收并播放 Opus 数据
   * @param {ArrayBuffer} opusData - Base64 解码后的 Opus 数据
   */
  async feed(opusData) {
    if (!this.isInitialized) {
      await this.init()
    }
    await this.resume()

    const data = new Uint8Array(opusData)
    const frames = this.parseOpusFrames(data)

    if (frames.length === 0) return

    // 解码所有帧
    const pcmChunks = []
    for (const frame of frames) {
      try {
        const decoded = this.decoder.decodeFrame(frame)
        if (decoded && decoded.channelData && decoded.channelData[0]) {
          pcmChunks.push(decoded.channelData[0])
        }
      } catch (e) {
        console.error('Opus decode error:', e)
      }
    }

    if (pcmChunks.length === 0) return

    // 合并 PCM 数据
    const totalLength = pcmChunks.reduce((sum, chunk) => sum + chunk.length, 0)
    const mergedPcm = new Float32Array(totalLength)
    let writeOffset = 0
    for (const chunk of pcmChunks) {
      mergedPcm.set(chunk, writeOffset)
      writeOffset += chunk.length
    }

    // 创建 AudioBuffer
    const audioBuffer = this.audioContext.createBuffer(
      this.channels,
      mergedPcm.length,
      this.sampleRate
    )
    audioBuffer.getChannelData(0).set(mergedPcm)

    // 调度播放
    this.scheduleBuffer(audioBuffer)
  }

  /**
   * 调度音频缓冲区播放（无缝衔接）
   */
  scheduleBuffer(audioBuffer) {
    const source = this.audioContext.createBufferSource()
    source.buffer = audioBuffer
    source.connect(this.audioContext.destination)

    // 计算开始时间，确保无缝衔接
    const currentTime = this.audioContext.currentTime
    const startTime = Math.max(currentTime, this.nextStartTime)
    
    source.start(startTime)
    this.nextStartTime = startTime + audioBuffer.duration

    this.isPlaying = true
    source.onended = () => {
      // 检查是否还有更多音频在播放
      if (this.audioContext.currentTime >= this.nextStartTime - 0.01) {
        this.isPlaying = false
      }
    }
  }

  /**
   * 停止播放并清空队列
   */
  stop() {
    if (this.currentSource) {
      try {
        this.currentSource.stop()
      } catch (e) {
        // 忽略已停止的错误
      }
      this.currentSource = null
    }
    this.audioQueue = []
    this.nextStartTime = 0
    this.isPlaying = false
  }

  /**
   * 重置播放器状态（用于新对话）
   */
  reset() {
    this.stop()
    this.nextStartTime = 0
  }

  /**
   * 销毁播放器
   */
  async destroy() {
    this.stop()
    
    if (this.decoder) {
      this.decoder.free()
      this.decoder = null
    }
    
    if (this.audioContext) {
      await this.audioContext.close()
      this.audioContext = null
    }
    
    this.isInitialized = false
  }
}

// 单例
let playerInstance = null

export function getOpusPlayer() {
  if (!playerInstance) {
    playerInstance = new OpusStreamPlayer()
  }
  return playerInstance
}
