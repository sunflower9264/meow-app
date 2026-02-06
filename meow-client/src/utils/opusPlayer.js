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

        this.currentSource = null
        this.activeSources = new Set()
        this.nextStartTime = 0

        this.sampleRate = 24000
        this.channels = 1

        this.pendingOpusBytes = new Uint8Array(0)
        this.feedChain = Promise.resolve()
        this.streamVersion = 0
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

        await this.createDecoder()

        this.isInitialized = true
        console.log('OpusStreamPlayer initialized')
    }

    async createDecoder() {
        const decoder = new OpusDecoder({
            sampleRate: this.sampleRate,
            channels: this.channels
        })
        await decoder.ready
        this.decoder = decoder
    }

    async recreateDecoder() {
        if (this.decoder) {
            this.decoder.free()
        }
        await this.createDecoder()
    }

    /**
     * 确保 AudioContext 处于运行状态
     */
    async resume() {
        if (this.audioContext && this.audioContext.state === 'suspended') {
            await this.audioContext.resume()
        }
    }

    concatBytes(first, second) {
        if (first.length === 0) return second
        if (second.length === 0) return first

        const merged = new Uint8Array(first.length + second.length)
        merged.set(first, 0)
        merged.set(second, first.length)
        return merged
    }

    /**
     * 解析裸 Opus 数据（带2字节长度头的多帧格式），并保留尾部不完整数据
     * @param {Uint8Array} data - 裸 Opus 数据
     * @returns {{ frames: Uint8Array[], remaining: Uint8Array }}
     */
    parseOpusFrames(data) {
        const frames = []
        let offset = 0

        while (offset + 2 <= data.length) {
            // 读取帧长度（2字节小端）
            const frameLength = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
            offset += 2

            if (frameLength <= 0) {
                console.error('Invalid Opus frame length:', frameLength)
                continue
            }

            if (offset + frameLength > data.length) {
                // 保留不完整数据，等待下一批拼接
                offset -= 2
                break
            }

            // 提取帧数据
            const frameData = data.slice(offset, offset + frameLength)
            frames.push(frameData)
            offset += frameLength
        }

        return {
            frames,
            remaining: data.slice(offset)
        }
    }

    /**
     * 接收并播放 Opus 数据
     * @param {ArrayBuffer} opusData - Base64 解码后的 Opus 数据
     * @param {boolean} isLastFrame - 当前句子的最后一帧
     */
    feed(opusData, isLastFrame = false) {
        const requestVersion = this.streamVersion

        this.feedChain = this.feedChain
            .then(() => this.processFeed(opusData, isLastFrame, requestVersion))
            .catch((e) => {
                console.error('Opus feed error:', e)
            })

        return this.feedChain
    }

    async processFeed(opusData, isLastFrame, requestVersion) {
        if (!this.isInitialized) {
            await this.init()
        }
        await this.resume()

        if (requestVersion !== this.streamVersion) {
            return
        }

        const incoming = opusData ? new Uint8Array(opusData) : new Uint8Array(0)
        const mergedData = this.concatBytes(this.pendingOpusBytes, incoming)
        const { frames, remaining } = this.parseOpusFrames(mergedData)
        this.pendingOpusBytes = remaining

        if (frames.length > 0) {
            try {
                const decoded = this.decoder.decodeFrames(frames)

                if (decoded?.errors?.length) {
                    console.error('Opus decode errors:', decoded.errors)
                }

                const pcm = decoded?.channelData?.[0]
                const decodedSampleRate = decoded?.sampleRate || this.sampleRate

                if (pcm && pcm.length > 0 && requestVersion === this.streamVersion) {
                    const audioBuffer = this.audioContext.createBuffer(
                        this.channels,
                        pcm.length,
                        decodedSampleRate
                    )
                    audioBuffer.getChannelData(0).set(pcm)
                    this.scheduleBuffer(audioBuffer)
                }
            } catch (e) {
                console.error('Opus decode error:', e)
            }
        }

        if (isLastFrame) {
            if (this.pendingOpusBytes.length > 0) {
                console.warn('Dropping trailing incomplete Opus bytes:', this.pendingOpusBytes.length)
                this.pendingOpusBytes = new Uint8Array(0)
            }

            await this.recreateDecoder()
        }
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

        this.activeSources.add(source)
        this.currentSource = source
        source.onended = () => {
            this.activeSources.delete(source)
            if (this.currentSource === source) this.currentSource = null
        }
    }

    /**
     * 停止播放并清空队列
     */
    stop() {
        this.streamVersion += 1

        for (const source of this.activeSources) {
            try {
                source.stop()
            } catch (e) {
                // 忽略已停止的错误
            }
        }

        this.activeSources.clear()
        this.currentSource = null
        this.nextStartTime = 0
        this.pendingOpusBytes = new Uint8Array(0)
    }

    /**
     * 重置播放器状态（用于新对话）
     */
    reset() {
        this.stop()
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
