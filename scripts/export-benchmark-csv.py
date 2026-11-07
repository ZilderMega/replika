"""Export only measured values. Unknown quality metrics stay empty, never zero."""
import csv
import json
from pathlib import Path

directory = Path("artifacts/test-results")
threads = json.loads((directory / "threads.json").read_text(encoding="utf-8-sig"))
pipeline = json.loads((directory / "full-speed.json").read_text(encoding="utf-8-sig"))
rows = []
for run in threads["runs"]:
    rows.append(dict(kind="ASR median of 3 warm runs", version="0.6.0", device=threads["device"],
                     provider=run["requested_provider"], threads=run["threads"], audio_ms=run["audio_ms"],
                     elapsed_ms=run["asr_ms"], rtf=run["asr_rtf"], same_words=run["same_words"],
                     notes="Inference only; loading excluded; requested NNAPI is not proof of NPU"))
for index, run in enumerate(pipeline["runs"], 1):
    rows.append(dict(kind=f"Full pipeline run {index}", version="0.6.0", device=pipeline["device"], provider="cpu", threads=4,
                     audio_ms=run["duration_ms"], elapsed_ms=run["elapsed_ms"], rtf=run["rtf"],
                     notes="Already imported audio; startup model preparation excluded; stage model loading included"))
fields = ["kind", "version", "device", "provider", "threads", "audio_ms", "elapsed_ms", "rtf", "same_words", "wer", "der", "notes"]
with (directory / "results.csv").open("w", newline="", encoding="utf-8") as output:
    writer = csv.DictWriter(output, fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows)
print(f"Exported {len(rows)} measured rows")
