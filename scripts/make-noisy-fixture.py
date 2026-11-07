"""Deterministic noisy copy of the public Russian smoke fixture, never a user recording."""
import array
import math
import random
import wave
from pathlib import Path

source = Path(".models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs/0.wav")
with wave.open(str(source), "rb") as reader:
    assert reader.getnchannels() == 1 and reader.getsampwidth() == 2 and reader.getframerate() == 16000
    params = reader.getparams()
    samples = array.array("h", reader.readframes(reader.getnframes()))
rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
noise_rms = rms / math.sqrt(10)  # 10 dB target global SNR before clipping
generator = random.Random(20261107)
noisy = array.array("h", (max(-32768, min(32767, round(sample + generator.gauss(0, noise_rms)))) for sample in samples))
with wave.open(".models/noisy-test.wav", "wb") as writer:
    writer.setparams(params)
    writer.writeframes(noisy.tobytes())
print("Generated noisy-test.wav: deterministic Gaussian noise, nominal global SNR 10 dB")
