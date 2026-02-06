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

        // WebSocket 按消息边界交付，服务端每条 tts 消息就是完整帧包（可包含1-N个长度头帧）
        this.packetQueue = []
        this.isQueueProcessing = false
        this.maxFramesPerDecode = 6

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

    async resetDecoder() {
        if (!this.decoder) return

        try {
            await this.decoder.reset()
        } catch (e) {
            console.warn('Opus decoder reset failed, recreating decoder:', e)
            await this.recreateDecoder()
        }
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
     * 解析单条 WebSocket 消息中的 Opus 帧包（[2字节长度头][帧数据]...）
     * @param {Uint8Array} packetData - 单条消息的二进制数据
     * @returns {Uint8Array[]}
     */
    parsePacketFrames(packetData) {
        const frames = []
        let offset = 0

        while (offset + 2 <= packetData.length) {
            // 读取帧长度（2字节小端）
            const frameLength = (packetData[offset] & 0xFF) | ((packetData[offset + 1] & 0xFF) << 8)
            offset += 2

            if (frameLength <= 0) {
                console.error('Invalid Opus frame length:', frameLength)
                break
            }

            if (offset + frameLength > packetData.length) {
                console.error(
                    'Incomplete Opus frame in packet:',
                    frameLength,
                    'available:',
                    packetData.length - offset
                )
                break
            }

            // 提取帧数据
            const frameData = packetData.slice(offset, offset + frameLength)
            frames.push(frameData)
            offset += frameLength
        }

        if (offset !== packetData.length) {
            console.warn('Dropping unexpected trailing Opus bytes:', packetData.length - offset)
        }

        return frames
    }

    startQueueProcessor(requestVersion) {
        if (this.isQueueProcessing) return

        this.isQueueProcessing = true
        this.processPacketQueue(requestVersion)
            .catch((e) => {
                console.error('Opus queue processing error:', e)
            })
            .finally(() => {
                this.isQueueProcessing = false
                if (this.packetQueue.length > 0 && requestVersion === this.streamVersion) {
                    this.startQueueProcessor(requestVersion)
                }
            })
    }

    async processPacketQueue(requestVersion) {
        while (requestVersion === this.streamVersion && this.packetQueue.length > 0) {
            const decodeFrames = []
            let shouldResetDecoder = false

            while (this.packetQueue.length > 0 && decodeFrames.length < this.maxFramesPerDecode) {
                const packet = this.packetQueue.shift()
                if (packet.frames.length > 0) {
                    decodeFrames.push(...packet.frames)
                }

                if (packet.isLastFrame) {
                    shouldResetDecoder = true
                    break
                }
            }

            if (decodeFrames.length > 0) {
                this.decodeAndSchedule(decodeFrames, requestVersion)
            }

            if (shouldResetDecoder && requestVersion === this.streamVersion) {
                await this.resetDecoder()
            }

            await Promise.resolve()
        }
    }

    decodeAndSchedule(frames, requestVersion) {
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
        const frames = this.parsePacketFrames(incoming)

        // 即使当前包无音频，也保留段结束标记，用于 reset decoder
        if (frames.length > 0 || isLastFrame) {
            this.packetQueue.push({ frames, isLastFrame })
            this.startQueueProcessor(requestVersion)
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
        this.packetQueue = []
        this.feedChain = Promise.resolve()
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
