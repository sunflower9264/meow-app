import { OpusDecoder } from 'opus-decoder'

/**
 * Opus 音频流播放器
 * 接收 Ogg Opus 格式数据，解码后通过 Web Audio API 播放
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

    // 累积的 Ogg 数据缓冲区（用于处理分片传输）
    this.oggBuffer = null
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

    // 初始化 Ogg Opus 解码器（会自动从 Ogg 头部读取参数）
    this.decoder = new OpusDecoder({
      onDecode: this.onDecodedFrame.bind(this),
      onDecodeAll: this.onAllDecoded.bind(this)
    })
    await this.decoder.ready

    this.isInitialized = true
    console.log('OpusStreamPlayer initialized (Ogg Opus mode)')
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
   * 解码帧回调（每解码一帧触发）
   * @param {Object} decoded - 解码结果 {channelData, samplesDecoded, sampleRate}
   */
  onDecodedFrame(decoded) {
    if (!decoded || !decoded.channelData || !decoded.channelData[0]) {
      return
    }

    const pcmData = decoded.channelData[0] // Float32Array
    this.scheduleAudio(pcmData)
  }

  /**
   * 全部解码完成回调
   * @param {Object} decoded - 解码结果 {channelData, samplesDecoded, sampleRate}
   */
  onAllDecoded(decoded) {
    if (!decoded || !decoded.channelData || !decoded.channelData[0]) {
      return
    }

    const pcmData = decoded.channelData[0] // Float32Array
    this.scheduleAudio(pcmData)
  }

  /**
   * 调度音频播放
   * @param {Float32Array} pcmData - PCM 数据
   */
  scheduleAudio(pcmData) {
    if (pcmData.length === 0) return

    // 创建 AudioBuffer
    const audioBuffer = this.audioContext.createBuffer(
      this.channels,
      pcmData.length,
      this.sampleRate
    )
    audioBuffer.getChannelData(0).set(pcmData)

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
   * 接收并播放 Ogg Opus 数据
   * 注意：每次 feed 都应该是完整的 Ogg Opus 文件（包含头部）
   *
   * @param {ArrayBuffer} opusData - Base64 解码后的 Ogg Opus 数据
   */
  async feed(opusData) {
    if (!this.isInitialized) {
      await this.init()
    }
    await this.resume()

    const data = new Uint8Array(opusData)

    // 如果解码器已经使用过，需要重新创建
    // 因为 opus-decoder 的 free() 后需要重新初始化
    if (this.decoder) {
      try {
        this.decoder.free()
      } catch (e) {
        // 忽略错误
      }
    }

    // 创建新的解码器实例
    const pcmChunks = []

    const tempDecoder = new OpusDecoder({
      onDecode: (decoded) => {
        if (decoded && decoded.channelData && decoded.channelData[0]) {
          pcmChunks.push(decoded.channelData[0])
        }
      },
      onDecodeAll: (decoded) => {
        if (decoded && decoded.channelData && decoded.channelData[0]) {
          pcmChunks.push(decoded.channelData[0])
        }
      }
    })

    await tempDecoder.ready

    // 解码数据
    try {
      tempDecoder.decode(data)
    } catch (e) {
      console.error('Ogg Opus decode error:', e)
      tempDecoder.free()
      return
    }

    // 合并所有 PCM 块并播放
    if (pcmChunks.length > 0) {
      const totalLength = pcmChunks.reduce((sum, chunk) => sum + chunk.length, 0)
      const mergedPcm = new Float32Array(totalLength)
      let offset = 0
      for (const chunk of pcmChunks) {
        mergedPcm.set(chunk, offset)
        offset += chunk.length
      }
      this.scheduleAudio(mergedPcm)
    }

    // 释放解码器
    tempDecoder.free()
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
    this.oggBuffer = null
  }

  /**
   * 重置播放器状态（用于新对话）
   */
  reset() {
    this.stop()
    this.nextStartTime = 0
    this.oggBuffer = null
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
