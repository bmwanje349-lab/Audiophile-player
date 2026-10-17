#!/usr/bin/env python3
"""Audio Doctor — inspect a track, apply only justified repairs, and report before/after."""
from __future__ import annotations
import argparse, json, math, re, shutil, subprocess, sys
from pathlib import Path
from typing import Any
try:
    import numpy as np
except ImportError:
    sys.exit("NumPy is required. Install with: python -m pip install -r requirements.txt")

MAX_DECODE_BYTES = 256 * 1024 * 1024
CHUNK = 1_000_000

class AudioDoctorError(RuntimeError):
    pass

def run(cmd: list[str], *, check: bool = True, timeout: int = 900):
    try:
        result = subprocess.run(cmd, capture_output=True, text=False, timeout=timeout)
    except FileNotFoundError as exc:
        raise AudioDoctorError("Required program not found: " + cmd[0] + ". Install FFmpeg and FFprobe.") from exc
    except subprocess.TimeoutExpired as exc:
        raise AudioDoctorError("Command timed out: " + cmd[0]) from exc
    if check and result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace")[-2500:]
        raise AudioDoctorError("Command failed: " + " ".join(cmd[:5]) + "\n" + detail)
    return result

def executable(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise AudioDoctorError(name + " is required but missing from PATH.")
    return path

def safe_float(value: Any) -> float | None:
    try:
        n = float(value)
        return n if math.isfinite(n) else None
    except (TypeError, ValueError):
        return None

def db(value: float) -> float:
    return 20 * math.log10(max(abs(float(value)), 1e-15))

def ffprobe(path: str | Path) -> dict[str, Any]:
    result = run([executable("ffprobe"), "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)])
    try:
        payload = json.loads(result.stdout.decode("utf-8"))
    except Exception as exc:
        raise AudioDoctorError("FFprobe returned invalid metadata.") from exc
    audio = next((s for s in payload.get("streams", []) if s.get("codec_type") == "audio"), None)
    if not audio:
        raise AudioDoctorError("No audio stream was found.")
    fmt = payload.get("format", {}) or {}
    tags = dict(fmt.get("tags", {}) or {})
    tags.update(audio.get("tags", {}) or {})
    tags = {str(k).lower(): str(v) for k, v in tags.items()}
    return {
        "format_name": fmt.get("format_name"), "duration_seconds": safe_float(fmt.get("duration")) or safe_float(audio.get("duration")),
        "file_size_bytes": safe_float(fmt.get("size")) or Path(path).stat().st_size,
        "bit_rate_bps": safe_float(fmt.get("bit_rate")), "codec": audio.get("codec_name"),
        "sample_rate_hz": int(audio.get("sample_rate") or 0), "channels": int(audio.get("channels") or 0),
        "channel_layout": audio.get("channel_layout"), "bits_per_sample": int(audio.get("bits_per_sample") or audio.get("bits_per_raw_sample") or 0),
        "tags": tags, "cover_art_embedded": any(
            s.get("codec_type") == "video" and int((s.get("disposition") or {}).get("attached_pic", 0)) == 1
            for s in payload.get("streams", [])
        )
    }

def decode_stereo(path: str | Path, info: dict[str, Any]) -> tuple[np.ndarray, int]:
    sr = int(info.get("sample_rate_hz") or 0)
    duration = float(info.get("duration_seconds") or 0)
    if sr < 8000:
        raise AudioDoctorError("Missing or unexpected sample rate.")
    estimated = int(duration * sr * 2 * 4)
    if estimated > MAX_DECODE_BYTES:
        raise AudioDoctorError("Safe memory guard stopped PCM decoding (estimated " + str(round(estimated / 1048576)) + " MiB). Use a shorter track or more memory.")
    result = run([executable("ffmpeg"), "-v", "error", "-nostdin", "-i", str(path), "-map", "0:a:0",
                  "-vn", "-ac", "2", "-ar", str(sr), "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"])
    raw = result.stdout
    if len(raw) < 8 or len(raw) % 8:
        raise AudioDoctorError("FFmpeg could not decode valid stereo PCM.")
    data = np.frombuffer(raw, dtype="<f4").reshape((-1, 2))
    if not np.all(np.isfinite(data)):
        data = np.nan_to_num(data, copy=True, nan=0.0, posinf=1.0, neginf=-1.0)
    return data, sr

def _count_clips(x: np.ndarray, threshold: float = .999) -> int:
    total = 0
    for i in range(0, len(x), CHUNK):
        part = x[i:i+CHUNK]
        total += int(np.count_nonzero((part >= threshold) | (part <= -threshold)))
    return total

def _plateau_pairs(channel: np.ndarray, threshold: float = .985) -> int:
    total = 0
    for i in range(0, len(channel), CHUNK):
        p = channel[i:i+CHUNK]
        if len(p) > 1:
            total += int(np.count_nonzero((np.abs(p[1:]) >= threshold) & (np.abs(p[:-1]) >= threshold) & (np.abs(p[1:] - p[:-1]) < 1e-7)))
    return total

def _silence_edges(x: np.ndarray, sr: int, threshold: float = .001) -> tuple[float, float]:
    first = last = None
    offset = 0
    for i in range(0, len(x), CHUNK):
        p = x[i:i+CHUNK]
        active = np.where(np.any((p > threshold) | (p < -threshold), axis=1))[0]
        if active.size:
            if first is None:
                first = offset + int(active[0])
            last = offset + int(active[-1])
        offset += len(p)
    if first is None or last is None:
        d = len(x) / sr
        return d, d
    return first / sr, max(0.0, (len(x)-1-last) / sr)

def _level_spread(x: np.ndarray, sr: int) -> float | None:
    frame, levels = max(1, sr // 10), []
    for i in range(0, len(x), max(1, sr // 10)):
        p = x[i:i+frame]
        rms = math.sqrt(max(0.0, float(np.sum(p*p, dtype=np.float64)) / max(1, p.size)))
        value = db(rms)
        if value > -70:
            levels.append(value)
    return round(float(np.percentile(levels, 95)-np.percentile(levels, 10)), 2) if len(levels) >= 4 else None

def _tempo(x: np.ndarray, sr: int) -> float | None:
    frame = max(1, sr // 50)
    mono, env = (x[:, 0] + x[:, 1]) * .5, []
    for i in range(0, len(x), frame):
        p = mono[i:i+frame]
        if len(p):
            env.append(math.sqrt(float(np.dot(p, p)) / len(p)))
    if len(env) < 150:
        return None
    novelty = np.maximum(0.0, np.diff(np.asarray(env, dtype=np.float64), prepend=env[0]))
    novelty -= np.mean(novelty)
    if float(np.std(novelty)) < 1e-7:
        return None
    size = 1
    while size < len(novelty)*2:
        size *= 2
    fft = np.fft.rfft(novelty, n=size)
    ac = np.fft.irfft(fft*np.conjugate(fft), n=size)[:len(novelty)]
    fps = sr / frame
    low, high = max(1, int(fps*60/180)), min(len(ac)-1, int(fps*60/60))
    if high <= low:
        return None
    bpm = 60*fps/(low+int(np.argmax(ac[low:high+1])))
    while bpm < 70: bpm *= 2
    while bpm > 175: bpm /= 2
    return round(bpm, 1) if 55 <= bpm <= 200 else None

def _key(x: np.ndarray, sr: int) -> dict[str, Any]:
    names = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]
    major = np.array([6.35,2.23,3.48,2.33,4.38,4.09,2.52,5.19,2.39,3.66,2.29,2.88])
    minor = np.array([6.33,2.68,3.52,5.38,2.60,3.53,2.54,4.75,3.98,2.69,3.34,3.17])
    mono = ((x[:,0]+x[:,1])*.5)[:sr*18]
    size, hop = 8192, 4096
    if len(mono) < size*4:
        return {"key": None, "confidence": "low"}
    window = np.hanning(size)
    freqs = np.fft.rfftfreq(size, 1/sr)
    valid = (freqs >= 55) & (freqs <= 4000)
    midi = np.rint(69+12*np.log2(np.maximum(freqs[valid],1e-12)/440)).astype(int)
    chroma = np.zeros(12)
    for i in range(0, max(1, min(len(mono)-size, sr*18)), hop):
        mag = np.abs(np.fft.rfft(mono[i:i+size]*window))[valid]
        for pc in range(12):
            chroma[pc] += float(np.sum(mag[midi%12 == pc]))
    if np.sum(chroma) <= 1e-9:
        return {"key": None, "confidence": "low"}
    chroma = (chroma-np.mean(chroma))/max(float(np.std(chroma)),1e-12)
    candidates = []
    for root in range(12):
        candidates += [(float(np.corrcoef(chroma,np.roll(major,root))[0,1]),root,"major"),
                       (float(np.corrcoef(chroma,np.roll(minor,root))[0,1]),root,"minor")]
    candidates.sort(reverse=True)
    margin = candidates[0][0]-candidates[1][0]
    return {"key":names[candidates[0][1]]+" "+candidates[0][2],"confidence":"medium" if margin>.06 else "low","score_margin":round(float(margin),3)}

def _spectral_cliff(x: np.ndarray, sr: int) -> dict[str, Any]:
    if sr/2 < 10000 or len(x) < 4096:
        return {"estimated_cutoff_hz":None,"confidence":"low","method":"spectral heuristic"}
    mono = (x[:min(len(x),262144),0]+x[:min(len(x),262144),1])*.5
    size = 1
    while size < len(mono): size *= 2
    size = min(size,262144)
    mag = np.abs(np.fft.rfft(mono[:size]*np.hanning(size)))
    freq = np.fft.rfftfreq(size,1/sr)
    mid = (freq>=2000)&(freq<8000)
    ref = float(np.median(mag[mid])) if np.any(mid) else 0
    if ref<=1e-12:
        return {"estimated_cutoff_hz":None,"confidence":"low","method":"spectral heuristic"}
    levels = 20*np.log10(np.maximum(mag,1e-12)/ref)
    bands = []
    for start in range(7000,int(min(sr/2,20000))-1000,1000):
        mask=(freq>=start)&(freq<start+1000)
        if np.any(mask): bands.append((start,float(np.median(levels[mask]))))
    for i in range(max(0,len(bands)-2)):
        if all(v[1]<-34 for v in bands[i:i+3]):
            return {"estimated_cutoff_hz":bands[i][0],"confidence":"low","method":"spectral heuristic; not proof of source bitrate"}
    return {"estimated_cutoff_hz":None,"confidence":"low","method":"spectral heuristic; no clear cliff detected"}

def analyze_samples(x: np.ndarray, sr: int) -> dict[str, Any]:
    if x.ndim != 2 or x.shape[1] != 2 or len(x)==0:
        raise AudioDoctorError("PCM must contain non-empty stereo float samples.")
    frames=len(x); duration=frames/float(sr)
    peaks=[]; rms=[]; dc=[]
    for ch in range(2):
        col=x[:,ch]
        pk=max(abs(float(np.max(col))),abs(float(np.min(col))))
        peaks.append(round(db(pk),3))
        rms.append(math.sqrt(max(0.0,float(np.dot(col,col))/frames)))
        dc.append(round(float(np.mean(col,dtype=np.float64)),8))
    peak_db=max(peaks)
    rms_db=round(db(math.sqrt((rms[0]**2+rms[1]**2)/2)),3)
    clips=_count_clips(x)
    plateaus=_plateau_pairs(x[:,0])+_plateau_pairs(x[:,1])
    lead,tail=_silence_edges(x,sr)
    stride=max(1,frames//250000)
    left,right=x[::stride,0],x[::stride,1]
    corr=round(float(np.corrcoef(left,right)[0,1]),4) if len(left)>1 and np.std(left)>1e-12 and np.std(right)>1e-12 else None
    mono=(left+right)*.5
    mono_rms=math.sqrt(max(0.0,float(np.dot(mono,mono))/max(1,len(mono))))
    channel_rms=(math.sqrt(max(0.0,float(np.dot(left,left))/max(1,len(left))))+math.sqrt(max(0.0,float(np.dot(right,right))/max(1,len(right))))/2
    return {
        "duration_seconds":round(duration,3),"decoded_sample_rate_hz":sr,"decoded_channels":2,
        "sample_peak_dbfs":peak_db,"channel_peak_dbfs":peaks,"rms_level_dbfs":rms_db,
        "crest_factor_db":round(peak_db-rms_db,2),
        "dc_offset":{"left":dc[0],"right":dc[1],"max_abs":max(abs(dc[0]),abs(dc[1]))},
        "dc_offset_percent_fs":round(max(abs(dc[0]),abs(dc[1]))*100,6),
        "clipped_sample_count":clips,"clipped_sample_ratio":round(clips/max(1,frames*2),9),
        "hard_clip_plateau_pairs":plateaus,
        "leading_silence_seconds":round(lead,3),"trailing_silence_seconds":round(tail,3),
        "short_term_level_spread_db":_level_spread(x,sr),
        "stereo_correlation":corr,
        "mono_sum_level_change_db":round(db(mono_rms)-db(channel_rms),2) if channel_rms>1e-12 else None,
        "tempo_bpm_estimate":_tempo(x,sr),"musical_key_estimate":_key(x,sr),
        "spectral_cliff":_spectral_cliff(x,sr)
    }

def _loudnorm_measure(path: str | Path, target: float=-14.0, tp: float=-1.5) -> dict[str, Any]:
    result=run([executable("ffmpeg"),"-hide_banner","-nostats","-nostdin","-i",str(path),
                "-af","loudnorm=I="+str(target)+":TP="+str(tp)+":LRA=11:print_format=json","-f","null","-"],check=False)
    stderr=result.stderr.decode("utf-8","replace")
    found=re.findall(r'\{\s*"input_i".*?\}',stderr,flags=re.DOTALL)
    empty={"integrated_lufs":None,"true_peak_dbtp":None,"loudness_range_lu":None,"threshold_lufs":None,"target_offset_db":None,"available":False}
    if not found: return empty
    try: raw=json.loads(found[-1])
    except json.JSONDecodeError: return empty
    return {"integrated_lufs":safe_float(raw.get("input_i")),"true_peak_dbtp":safe_float(raw.get("input_tp")),
            "loudness_range_lu":safe_float(raw.get("input_lra")),"threshold_lufs":safe_float(raw.get("input_thresh")),
            "target_offset_db":safe_float(raw.get("target_offset")),"available":result.returncode==0}

def _decode_diagnostic(path: str | Path) -> dict[str, Any]:
    result=run([executable("ffmpeg"),"-v","error","-nostdin","-i",str(path),"-f","null","-"],check=False)
    issues=[line.strip() for line in result.stderr.decode("utf-8","replace").splitlines() if line.strip()]
    return {"decode_error_count_reported":len(issues),"decode_errors":issues[:12],"decode_return_code":result.returncode}

def analyze_audio(path: str | Path) -> dict[str, Any]:
    src=Path(path).expanduser().resolve()
    if not src.is_file(): raise AudioDoctorError("Input file does not exist: "+str(src))
    executable("ffmpeg")
    info=ffprobe(src)
    pcm,sr=decode_stereo(src,info)
    metrics=analyze_samples(pcm,sr)
    loud=_loudnorm_measure(src)
    diagnostic=_decode_diagnostic(src)
    return {"tool":"Audio Doctor Studio","schema_version":1,
            "file":{"name":src.name,"path":str(src),"size_bytes":src.stat().st_size,"format":info.get("format_name"),
                    "codec":info.get("codec"),"sample_rate_hz":info.get("sample_rate_hz"),
                    "channels_in_source":info.get("channels"),"channel_layout":info.get("channel_layout"),
                    "bit_depth":info.get("bits_per_sample") or None,"bit_rate_bps":info.get("bit_rate_bps"),
                    "cover_art_embedded":info.get("cover_art_embedded"),"tags":info.get("tags",{})},
            "metrics":metrics,"loudness":loud,"decode":diagnostic,
            "notes":["BPM, musical key, and spectral-cutoff values are estimates, not authoritative measurements.",
                     "320 kbps MP3 or FLAC export cannot restore detail lost in an earlier lossy encode.",
                     "A clipping detector cannot prove every flagged peak is damaged."]}

def guess_tags(path: str | Path) -> dict[str,str]:
    """Guess artist/title from names such as Artist_-_Title__Official_Music_Video_256k.mp3."""
    name=Path(path).stem
    name=re.sub(r"^[0-9a-f]{8}-","",name,flags=re.I)
    name=re.sub(r"[_ ]?(256k|320k|192k|128k|kbps)$","",name,flags=re.I)
    name=re.sub(r"[_ ]*[\(\[]?(official)?[_ ]*(music[_ ]*)?(video|audio|lyrics?)[\)\]]?$","",name,flags=re.I)
    name=name.replace("_"," ").strip(" -")
    if " - " in name:
        artist,title=name.split(" - ",1)
        return {"artist":artist.strip(),"title":title.strip()}
    return {"title":name}

def _filters_available() -> set[str]:
    result=run([executable("ffmpeg"),"-hide_banner","-filters"],check=False)
    text=result.stdout.decode("utf-8","replace")+result.stderr.decode("utf-8","replace")
    return {f for f in ("adeclip","highpass","loudnorm","atrim","afade") if re.search(r"\b"+f+r"\b",text)}

def _num(v: float) -> str:
    return str(round(float(v),3))

def repair_audio(source: str | Path, output: str | Path | None=None, *, output_format="mp3",
                 target_lufs=-14.0,true_peak_db=-1.5,artist=None,title=None,album=None,cover=None,write_bpm=False):
    src=Path(source).expanduser().resolve()
    if not src.is_file(): raise AudioDoctorError("Input file does not exist: "+str(src))
    if output_format not in ("mp3","flac"): raise AudioDoctorError("Output format must be mp3 or flac.")
    if not -36<=target_lufs<=-5: raise AudioDoctorError("LUFS target must be between -36 and -5.")
    if not -9<=true_peak_db<=0: raise AudioDoctorError("True peak must be between -9 and 0 dBTP.")
    if cover and not Path(cover).is_file(): raise AudioDoctorError("Cover image does not exist: "+str(cover))
    out=src.with_name(src.stem+"_fixed."+output_format) if output is None else Path(output).expanduser().resolve()
    if out.suffix.lower()!="."+output_format: out=out.with_suffix("."+output_format)
    if out==src: raise AudioDoctorError("Output must differ from the source.")
    out.parent.mkdir(parents=True,exist_ok=True)
    before=analyze_audio(src); m=before["metrics"]; loud=before["loudness"]
    filters=[]; fixes=[]; warnings=[]; available=_filters_available()
    if m["hard_clip_plateau_pairs"]>=8 or m["clipped_sample_count"]>=32:
        if "adeclip" in available:
            filters.append("adeclip"); fixes.append("Attempted best-effort clipping reconstruction with FFmpeg adeclip.")
        else: warnings.append("FFmpeg adeclip is unavailable; clipping restoration was skipped.")
    if m["dc_offset"]["max_abs"]>=.0005 and "highpass" in available:
        filters.append("highpass=f=10"); fixes.append("Applied a 10 Hz high-pass to reduce measurable DC/subsonic offset.")
    duration=float(m["duration_seconds"]); lead=float(m["leading_silence_seconds"]); tail=float(m["trailing_silence_seconds"])
    start=max(0,lead-.10) if lead>.65 else 0
    trim_end=max(0,tail-.30) if tail>.65 else 0
    if start>0 or trim_end>0:
        end=max(start+.15,duration-trim_end); effective=max(.15,end-start)
        filters += ["atrim=start="+_num(start)+":end="+_num(end),"asetpts=PTS-STARTPTS","afade=t=in:st=0:d=0.025"]
        if effective>.10: filters.append("afade=t=out:st="+_num(max(0,effective-.08))+":d=0.08")
        fixes.append("Trimmed excessive edge silence, retaining a short lead-in/tail with fades.")
    inp_lufs=loud.get("integrated_lufs"); inp_tp=loud.get("true_peak_dbtp")
    need=(inp_lufs is None or abs(float(inp_lufs)-target_lufs)>1 or
          (inp_tp is not None and float(inp_tp)>true_peak_db+.2) or m["sample_peak_dbfs"]>-.2)
    if need:
        if "loudnorm" not in available: raise AudioDoctorError("This FFmpeg build lacks loudnorm.")
        if loud.get("available") and inp_lufs is not None and inp_tp is not None and loud.get("loudness_range_lu") is not None and loud.get("threshold_lufs") is not None and loud.get("target_offset_db") is not None:
            lf=("loudnorm=I="+_num(target_lufs)+":TP="+_num(true_peak_db)+":LRA=11:measured_I="+_num(inp_lufs)+
                ":measured_TP="+_num(inp_tp)+":measured_LRA="+_num(loud["loudness_range_lu"])+
                ":measured_thresh="+_num(loud["threshold_lufs"])+":offset="+_num(loud["target_offset_db"])+
                ":linear=true:print_format=summary")
        else: lf="loudnorm=I="+_num(target_lufs)+":TP="+_num(true_peak_db)+":LRA=11:linear=false:print_format=summary"
        filters.append(lf); fixes.append("Applied EBU R128 normalization targeting "+_num(target_lufs)+" LUFS and "+_num(true_peak_db)+" dBTP.")
    else: fixes.append("Skipped normalization because loudness and true peak were already near target.")
    guess=guess_tags(src); tags=before["file"].get("tags",{})
    final_title=(title or tags.get("title") or guess.get("title") or src.stem).strip()
    final_artist=(artist or tags.get("artist") or tags.get("album_artist") or guess.get("artist") or "").strip()
    final_album=(album or tags.get("album") or "").strip()
    cmd=[executable("ffmpeg"),"-y","-hide_banner","-nostdin","-i",str(src)]
    if cover: cmd += ["-i",str(Path(cover).resolve())]
    cmd += ["-map","0:a:0","-map_metadata","0"]
    if cover: cmd += ["-map","1:v:0","-disposition:v:0","attached_pic","-c:v","mjpeg" if output_format=="mp3" else "copy"]
    if filters: cmd += ["-af",",".join(filters)]
    cmd += ["-c:a","libmp3lame","-b:a","320k","-id3v2_version","3","-write_id3v1","1"] if output_format=="mp3" else ["-c:a","flac","-compression_level","8"]
    if final_title: cmd += ["-metadata","title="+final_title]
    if final_artist: cmd += ["-metadata","artist="+final_artist]
    if final_album: cmd += ["-metadata","album="+final_album]
    if write_bpm and m.get("tempo_bpm_estimate") is not None: cmd += ["-metadata","BPM="+str(m["tempo_bpm_estimate"])]
    cmd.append(str(out))
    try: run(cmd,timeout=1800)
    except AudioDoctorError:
        if out.exists(): out.unlink()
        raise
    if not out.exists() or out.stat().st_size==0: raise AudioDoctorError("FFmpeg did not create a valid output.")
    after=analyze_audio(out)
    report={"tool":"Audio Doctor Studio","schema_version":1,"input_file":str(src),"output_file":str(out),
            "settings":{"format":output_format,"target_lufs":target_lufs,"true_peak_db":true_peak_db},
            "repairs_attempted":fixes,"warnings":warnings,"before":before,"after":after,
            "limits":["adeclip is best effort and cannot reconstruct the exact original waveform.",
                      "FLAC or 320 kbps MP3 cannot recover information lost in an earlier lossy encode.",
                      "BPM/key/cutoff estimates are heuristic."]}
    out.with_suffix(out.suffix+".report.json").write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding="utf-8")
    return out,report

def main():
    p=argparse.ArgumentParser(description="Inspect an audio file, repair fixable issues, and create a before/after report.")
    p.add_argument("input",help="Input audio file"); p.add_argument("-o","--output",help="Output path")
    p.add_argument("--format",choices=("mp3","flac"),default="mp3"); p.add_argument("--lufs",type=float,default=-14.0)
    p.add_argument("--true-peak",type=float,default=-1.5); p.add_argument("--artist"); p.add_argument("--title"); p.add_argument("--album")
    p.add_argument("--cover"); p.add_argument("--write-bpm",action="store_true")
    p.add_argument("--analyze-only",action="store_true",help="Inspect only; do not write repaired audio")
    p.add_argument("--report",help="Also write JSON report at this path"); args=p.parse_args()
    try:
        if args.analyze_only:
            report=analyze_audio(args.input); rendered=json.dumps(report,indent=2,ensure_ascii=False); print(rendered)
            if args.report: Path(args.report).expanduser().resolve().write_text(rendered+"\n",encoding="utf-8")
            return 0
        output,report=repair_audio(args.input,args.output,output_format=args.format,target_lufs=args.lufs,
            true_peak_db=args.true_peak,artist=args.artist,title=args.title,album=args.album,cover=args.cover,write_bpm=args.write_bpm)
        print("REPAIRED FILE: "+str(output)); print("REPORT: "+str(output.with_suffix(output.suffix+".report.json")))
        print("BEFORE: LUFS="+str(report["before"]["loudness"].get("integrated_lufs"))+" | true peak="+str(report["before"]["loudness"].get("true_peak_dbtp"))+" dBTP")
        print("AFTER:  LUFS="+str(report["after"]["loudness"].get("integrated_lufs"))+" | true peak="+str(report["after"]["loudness"].get("true_peak_dbtp"))+" dBTP")
        if args.report: Path(args.report).expanduser().resolve().write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding="utf-8")
        return 0
    except (AudioDoctorError,OSError,ValueError) as exc:
        print("Audio Doctor error: "+str(exc),file=sys.stderr); return 2

if __name__=="__main__":
    raise SystemExit(main())
