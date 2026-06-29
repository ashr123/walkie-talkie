// AudioWorklet processors for the WebSocket-relay transport (48 kHz; mono or negotiated stereo).
//
// CaptureProcessor batches the live microphone signal into fixed 20 ms frames and emits them as
// little-endian 16-bit PCM. The main thread then either Opus-encodes them (WebCodecs) or sends them
// as raw PCM (fallback). PlaybackProcessor receives already-decoded Float32 frames and streams them
// to the speakers.
//
// This file runs in the AudioWorkletGlobalScope, so `AudioWorkletProcessor` and `registerProcessor` are
// runtime-provided globals and each `process()` is invoked by the audio rendering thread (never called
// from this file). The directives below tell the IDE that, so it doesn't flag them as unresolved/unused.
/* global AudioWorkletProcessor, registerProcessor */

const FRAME_SAMPLES = 960; // 20 ms @ 48 kHz mono

class CaptureProcessor extends AudioWorkletProcessor {
	constructor(options) {
		super();
		this._channels = options && options.processorOptions && options.processorOptions.channels || 1;
		this._pcm = new ArrayBuffer(FRAME_SAMPLES * this._channels * 2);
		this._view = new DataView(this._pcm);
		this._offset = 0; // per-channel sample index into the current frame
	}

	// noinspection JSUnusedGlobalSymbols
	process(inputs) {
		const input = inputs[0];
		if (!input || input.length === 0 || !input[0]) {
			return true;
		}
		const blockSize = input[0].length;
		for (let i = 0; i < blockSize; i++) {
			for (let c = 0; c < this._channels; c++) {
				const ch = input[c] || input[0]; // duplicate if the source lacks this channel
				const clamped = Math.max(-1, Math.min(1, ch[i]));
				this._view.setInt16((this._offset * this._channels + c) * 2,
					clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
			}
			this._offset++;
			if (this._offset === FRAME_SAMPLES) {
				this.port.postMessage(this._pcm, [this._pcm]);
				this._pcm = new ArrayBuffer(FRAME_SAMPLES * this._channels * 2);
				this._view = new DataView(this._pcm);
				this._offset = 0;
			}
		}
		return true;
	}
}

class PlaybackProcessor extends AudioWorkletProcessor {
	constructor(options) {
		super();
		this._channels = options && options.processorOptions && options.processorOptions.channels || 1;
		this._queue = [];
		this._current = null;
		this._pos = 0; // per-channel sample index into the current (interleaved) frame
		this.port.onmessage = event => {
			// event.data is interleaved Float32 already matched to the output channel count.
			const frame = new Float32Array(event.data);
			// Bound the buffer so a fast sender can't grow playback latency without limit.
			if (this._queue.length < 50) {
				this._queue.push(frame);
			}
		};
	}

	// noinspection JSUnusedGlobalSymbols
	process(_inputs, outputs) {
		const out = outputs[0];
		if (!out || !out[0]) {
			return true; // no output channel this quantum; stay alive without crashing
		}
		const channels = this._channels;
		const blockSize = out[0].length;
		for (let i = 0; i < blockSize; i++) {
			if (!this._current || this._pos * channels >= this._current.length) {
				this._current = this._queue.shift() || null;
				this._pos = 0;
			}
			for (let c = 0; c < channels; c++) {
				if (out[c]) {
					out[c][i] = this._current ? this._current[this._pos * channels + c] : 0;
				}
			}
			this._pos++;
		}
		return true;
	}
}

registerProcessor('capture-processor', CaptureProcessor);
registerProcessor('playback-processor', PlaybackProcessor);
