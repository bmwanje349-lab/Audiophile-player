#!/usr/bin/env python3
"""Local-only web/API bridge. It binds to loopback; never expose this server to the internet."""
from __future__ import annotations
import io, json, mimetypes, os, shutil, tempfile, zipfile
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse
from audio_doctor import AudioDoctorError, analyze_audio, repair_audio

ROOT=Path(__file__).resolve().parent; WEB=ROOT/"web"; MAX_UPLOAD=240*1024*1024

class Handler(SimpleHTTPRequestHandler):
    protocol_version="HTTP/1.1"
    def __init__(self,*args,**kwargs): super().__init__(*args,directory=str(ROOT),**kwargs)
    def _json(self,status,payload):
        data=json.dumps(payload,ensure_ascii=False).encode()
        self.send_response(status); self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Content-Length",str(len(data))); self.send_header("Cache-Control","no-store"); self.end_headers(); self.wfile.write(data)
    def _send(self,status,body,kind,filename=None):
        self.send_response(status); self.send_header("Content-Type",kind); self.send_header("Content-Length",str(len(body)))
        self.send_header("Cache-Control","no-store")
        if filename: self.send_header("Content-Disposition",'attachment; filename="'+filename.replace('"',"")+'"')
        self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        path=urlparse(self.path).path
        if path=="/api/status":
            return self._json(200,{"name":"Audio Doctor Studio local engine","python_engine":True,"ffmpeg":bool(shutil.which("ffmpeg")),
                "ffprobe":bool(shutil.which("ffprobe")),"numpy":True,"max_upload_bytes":MAX_UPLOAD,
                "privacy":"Uploaded temp files are processed locally and deleted after the request."})
        if path in ("/","/index.html"):
            return self._send(200,(WEB/"index.html").read_bytes(),"text/html; charset=utf-8")
        if path.startswith("/web/"):
            target=(WEB/Path(unquote(path[len("/web/"):]))).resolve()
            if target!=WEB.resolve() and WEB.resolve() not in target.parents: return self._json(403,{"error":"Path not allowed."})
            if not target.is_file(): return self._json(404,{"error":"File not found."})
            kind=mimetypes.guess_type(str(target))[0] or "application/octet-stream"
            if target.suffix==".webmanifest": kind="application/manifest+json"
            return self._send(200,target.read_bytes(),kind)
        return self._json(404,{"error":"Not found."})
    def do_POST(self):
        path=urlparse(self.path)
        if path.path not in ("/api/analyze","/api/repair"): return self._json(404,{"error":"Unknown endpoint."})
        try: length=int(self.headers.get("Content-Length","0"))
        except ValueError: return self._json(400,{"error":"Invalid content length."})
        if length<1 or length>MAX_UPLOAD: return self._json(413,{"error":"Empty file or larger than the 240 MiB local limit."})
        body=self.rfile.read(length)
        if len(body)!=length: return self._json(400,{"error":"Upload ended unexpectedly."})
        params=parse_qs(path.query); filename=Path(unquote(params.get("filename",["track"])[0])).name
        suffix=Path(filename).suffix.lower()
        if suffix not in (".mp3",".wav",".flac",".m4a",".aac",".ogg",".opus",".aiff",".aif",".wma",".mp4"): suffix=".audio"
        try:
            with tempfile.TemporaryDirectory(prefix="audio-doctor-") as folder:
                source=Path(folder)/("source"+suffix); source.write_bytes(body)
                if path.path=="/api/analyze": return self._json(200,analyze_audio(source))
                fmt=params.get("format",["mp3"])[0].lower()
                out,report=repair_audio(source,Path(folder)/("AudioDoctor_fixed."+fmt),output_format=fmt,
                    target_lufs=float(params.get("lufs",["-14"])[0]),true_peak_db=float(params.get("true_peak",["-1.5"])[0]),
                    artist=params.get("artist",[None])[0],title=params.get("title",[None])[0],
                    write_bpm=params.get("write_bpm",["false"])[0]=="true")
                bundle=io.BytesIO()
                with zipfile.ZipFile(bundle,"w",zipfile.ZIP_DEFLATED) as z:
                    z.write(out,out.name); z.writestr("AudioDoctor_before_after_report.json",json.dumps(report,indent=2,ensure_ascii=False))
                return self._send(200,bundle.getvalue(),"application/zip","AudioDoctor_Repair.zip")
        except (AudioDoctorError,OSError,ValueError) as exc: return self._json(400,{"error":str(exc)})
    def log_message(self,fmt,*args): super().log_message(fmt,*args)

def main():
    host=os.environ.get("AUDIO_DOCTOR_HOST","127.0.0.1"); port=int(os.environ.get("AUDIO_DOCTOR_PORT","8765"))
    if host not in ("127.0.0.1","localhost","::1"): raise SystemExit("For safety, bind only to loopback; do not expose this server publicly.")
    server=ThreadingHTTPServer((host,port),Handler)
    print("Audio Doctor Studio: http://"+host+":"+str(port)); print("Local-only server. Stop with Ctrl+C.")
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
if __name__=="__main__": main()
