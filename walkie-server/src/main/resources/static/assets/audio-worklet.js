// AudioWorklet processors for the WebSocket-relay transport (48 kHz mono).
//
// CaptureProcessor batches the live microphone signal into fixed 20 ms frames and emits them as
// little-endian 16-bit PCM. The main thread then either Opus-encodes them (WebCodecs) or sends them
// as raw PCM (fallback). PlaybackProcessor receives already-decoded Float32 frames and streams them
// to the speakers.

const FRAME_SAMPLES = 960; // 20 ms @ 48 kHz mono

class CaptureProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this._buffer = new Float32Array(FRAME_SAMPLES);
        this._offset = 0;
    }

    process(inputs) {
        const input = inputs[0];
        if (!input || input.length === 0 || !input[0]) {
            return true;
        }
        const channel = input[0];
        for (let i = 0; i < channel.length; i++) {
            this._buffer[this._offset++] = channel[i];
            if (this._offset === FRAME_SAMPLES) {
                const pcm = new ArrayBuffer(FRAME_SAMPLES * 2);
                const view = new DataView(pcm);
                for (let s = 0; s < FRAME_SAMPLES; s++) {
                    const clamped = Math.max(-1, Math.min(1, this._buffer[s]));
                    view.setInt16(s * 2, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
                }
                this.port.postMessage(pcm, [pcm]);
                this._offset = 0;
            }
        }
        return true;
    }
}

class PlaybackProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this._queue = [];
        this._current = null;
        this._pos = 0;
        this.port.onmessage = (event) => {
            // event.data is an ArrayBuffer of Float32 PCM samples (already decoded).
            const frame = new Float32Array(event.data);
            // Bound the buffer so a fast sender can't grow playback latency without limit.
            if (this._queue.length < 50) {
                this._queue.push(frame);
            }
        };
    }

    process(_inputs, outputs) {
        const channel = outputs[0][0];
        for (let i = 0; i < channel.length; i++) {
            if (!this._current || this._pos >= this._current.length) {
                this._current = this._queue.shift() || null;
                this._pos = 0;
            }
            channel[i] = this._current ? this._current[this._pos++] : 0;
        }
        return true;
    }
}

registerProcessor('capture-processor', CaptureProcessor);
registerProcessor('playback-processor', PlaybackProcessor);
