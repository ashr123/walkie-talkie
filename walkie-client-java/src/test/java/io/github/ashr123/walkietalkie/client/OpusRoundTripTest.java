package io.github.ashr123.walkietalkie.client;

import io.github.jaredmdobson.concentus.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the Concentus Opus pipeline used by the desktop client: a 20 ms / 48 kHz frame encodes to
/// a compact packet and decodes back to a full frame of audio. This guards the codec configuration and
/// the exact encode/decode call shapes the client relies on.
class OpusRoundTripTest {

	private static final int SAMPLE_RATE = 48_000;
	private static final int FRAME_SAMPLES = 960; // 20 ms

	@Test
	void encodesAndDecodesA20msFrame() throws OpusException {
		OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
		encoder.setBitrate(64_000);
		encoder.setComplexity(10);
		encoder.setUseInbandFEC(true);
		encoder.setPacketLossPercent(10);
		encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
		OpusDecoder decoder = new OpusDecoder(SAMPLE_RATE, 1);

		short[] pcm = new short[FRAME_SAMPLES];
		for (int i = 0; i < FRAME_SAMPLES; i++) {
			pcm[i] = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE) * 12_000.0);
		}

		byte[] packet = new byte[4000];
		int packetLength = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.length);
		// A 20 ms 64 kbps frame is ~160 bytes — vastly smaller than the 1920-byte raw PCM frame.
		assertTrue(packetLength > 0 && packetLength < 1000,
				"expected a compact Opus packet, got " + packetLength + " bytes");

		short[] decoded = new short[FRAME_SAMPLES];
		int decodedSamples = decoder.decode(packet, 0, packetLength, decoded, 0, FRAME_SAMPLES, false);
		assertEquals(FRAME_SAMPLES, decodedSamples, "decoder should reconstruct a full 20 ms frame");

		double energy = 0.0;
		for (short sample : decoded) {
			energy += (double) sample * sample;
		}
		assertTrue(energy > 0.0, "decoded audio should not be silence");
	}

	@Test
	void encodesAndDecodesAStereoFrame() throws OpusException {
		int channels = 2;
		OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, channels, OpusApplication.OPUS_APPLICATION_AUDIO);
		encoder.setBitrate(128_000);
		encoder.setUseInbandFEC(true);
		encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
		OpusDecoder decoder = new OpusDecoder(SAMPLE_RATE, channels);

		// Interleaved L/R: a 440 Hz tone on the left, 660 Hz on the right.
		short[] pcm = new short[FRAME_SAMPLES * channels];
		for (int i = 0; i < FRAME_SAMPLES; i++) {
			pcm[i * 2] = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE) * 12_000.0);
			pcm[i * 2 + 1] = (short) (Math.sin(2.0 * Math.PI * 660.0 * i / SAMPLE_RATE) * 12_000.0);
		}

		byte[] packet = new byte[4000];
		// frame size is samples *per channel*; the input holds FRAME_SAMPLES * channels interleaved.
		int packetLength = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.length);
		assertTrue(packetLength > 0 && packetLength < 1500,
				"expected a compact stereo Opus packet, got " + packetLength + " bytes");

		short[] decoded = new short[FRAME_SAMPLES * channels];
		int perChannel = decoder.decode(packet, 0, packetLength, decoded, 0, FRAME_SAMPLES, false);
		assertEquals(FRAME_SAMPLES, perChannel, "decoder should reconstruct a full 20 ms frame per channel");

		double left = 0.0;
		double right = 0.0;
		for (int i = 0; i < FRAME_SAMPLES; i++) {
			left += (double) decoded[i * 2] * decoded[i * 2];
			right += (double) decoded[i * 2 + 1] * decoded[i * 2 + 1];
		}
		assertTrue(left > 0.0 && right > 0.0, "both channels should contain audio");
	}
}
