import math, shutil, sys, tempfile, unittest, wave
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from audio_doctor import analyze_audio, analyze_samples, guess_tags

class AudioDoctorTests(unittest.TestCase):
    def test_filename_tags(self):
        tags=guess_tags("Fik_Fameica_-_Sikiwa_Official_Music_Video_256k.mp3")
        self.assertEqual(tags["artist"],"Fik Fameica")
        self.assertIn("Sikiwa",tags["title"])
        self.assertNotIn("256k",tags["title"])
    def test_stereo_sine_metrics(self):
        sr=16000; t=np.arange(sr*3,dtype=np.float32)/sr; tone=.5*np.sin(2*np.pi*440*t)
        result=analyze_samples(np.column_stack((tone,tone)).astype(np.float32),sr)
        self.assertLess(result["sample_peak_dbfs"],0); self.assertGreater(result["sample_peak_dbfs"],-7)
        self.assertAlmostEqual(result["stereo_correlation"],1.0,places=3)
        self.assertEqual(result["clipped_sample_count"],0)
    def test_ffprobe_analysis(self):
        if not shutil.which("ffmpeg") or not shutil.which("ffprobe"): self.skipTest("FFmpeg unavailable")
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"test_tone.wav"; sr=16000; t=np.arange(sr*2,dtype=np.float32)/sr
            left=.35*np.sin(2*np.pi*440*t); right=.35*np.sin(2*np.pi*440*t+.05)
            pcm=np.clip(np.column_stack((left,right))*32767,-32768,32767).astype("<i2")
            with wave.open(str(path),"wb") as w:
                w.setnchannels(2); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())
            report=analyze_audio(path)
            self.assertEqual(report["file"]["sample_rate_hz"],sr)
            self.assertEqual(report["file"]["channels_in_source"],2)
            self.assertGreater(report["metrics"]["duration_seconds"],1.9)
            self.assertIn("integrated_lufs",report["loudness"])
            self.assertIn("decode_error_count_reported",report["decode"])
if __name__=="__main__": unittest.main()
