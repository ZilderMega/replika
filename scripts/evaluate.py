"""Evaluate exported transcripts against a separate reference. No audio inference here."""
import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


def words(text):
    return re.findall(r"[^\W_]+", text.casefold(), flags=re.UNICODE)


def edit_distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, expected in enumerate(reference, 1):
        current = [i]
        for j, actual in enumerate(hypothesis, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (expected != actual)))
        previous = current
    return previous[-1]


def transcript(document):
    if "transcript" not in document and "raw" in document:
        document = document["raw"]
    rows = document["transcript"]
    for row in rows:
        if not isinstance(row["speaker_id"], str) or not row["speaker_id"] or not isinstance(row["text"], str):
            raise ValueError("Invalid speaker_id or text")
        a, b = row["start_ms"], row["end_ms"]
        if type(a) is not int or type(b) is not int or a < 0 or b < a:
            raise ValueError("Timestamps must be non-negative integer milliseconds in order")
    return sorted(rows, key=lambda row: row["start_ms"])


def optimal_mapping(weights, actual_ids, expected_ids):
    size = max(len(actual_ids), len(expected_ids))
    if size > 12:
        raise ValueError("This exact assignment evaluator supports up to 12 speakers")
    states = {0: (0, ())}
    for row in range(size):
        next_states = {}
        for mask, (score, chosen) in states.items():
            for column in range(size):
                if mask & (1 << column):
                    continue
                value = weights.get((actual_ids[row], expected_ids[column]), 0) if row < len(actual_ids) and column < len(expected_ids) else 0
                new_mask = mask | (1 << column)
                candidate = (score + value, chosen + (column,))
                if new_mask not in next_states or candidate[0] > next_states[new_mask][0]:
                    next_states[new_mask] = candidate
        states = next_states
    chosen = states[(1 << size) - 1][1] if size else ()
    return {speaker: expected_ids[chosen[i]] if chosen[i] < len(expected_ids) else None for i, speaker in enumerate(actual_ids)}


def diarization(reference, hypothesis, collar_ms=0, ignore_overlap=False):
    events = defaultdict(list)
    collars = []
    for kind, rows in ((0, reference), (1, hypothesis)):
        for row in rows:
            events[row["start_ms"]].append((kind, row["speaker_id"], 1))
            events[row["end_ms"]].append((kind, row["speaker_id"], -1))
            if kind == 0 and collar_ms:
                collars.extend((max(0, t - collar_ms), t + collar_ms) for t in (row["start_ms"], row["end_ms"]))
    for a, b in collars:
        events[a]; events[b]
    boundaries = sorted(events)
    active = [Counter(), Counter()]
    frames = []
    weights = defaultdict(int)
    for i, start in enumerate(boundaries[:-1]):
        for kind, speaker, delta in events[start]:
            active[kind][speaker] += delta
        end = boundaries[i + 1]
        midpoint = (start + end) / 2
        expected = {speaker for speaker, count in active[0].items() if count > 0}
        actual = {speaker for speaker, count in active[1].items() if count > 0}
        if any(a <= midpoint < b for a, b in collars) or (ignore_overlap and len(expected) > 1):
            continue
        length = end - start
        frames.append((expected, actual, length))
        for a in actual:
            for e in expected:
                weights[a, e] += length
    # Abstention is not an anonymous identity that may earn credit through optimal relabeling.
    mapping = optimal_mapping(weights, sorted({row["speaker_id"] for row in hypothesis if row["speaker_id"] != "speaker_unknown"}),
                              sorted({row["speaker_id"] for row in reference}))
    missed = false = confusion = total = 0
    for expected, actual, length in frames:
        matched = len(expected & {mapping.get(speaker) for speaker in actual})
        missed += max(0, len(expected) - len(actual)) * length
        false += max(0, len(actual) - len(expected)) * length
        confusion += (min(len(expected), len(actual)) - matched) * length
        total += len(expected) * length
    return {"der": (missed + false + confusion) / total if total else None,
            "missed_speaker_ms": missed, "false_speaker_ms": false, "confused_speaker_ms": confusion,
            "reference_speaker_ms": total, "speaker_mapping": mapping, "collar_ms": collar_ms,
            "ignore_overlap": ignore_overlap}


def evaluate(reference, hypothesis, collar_ms=0, ignore_overlap=False):
    expected = [word for row in reference for word in words(row["text"])]
    actual = [word for row in hypothesis for word in words(row["text"])]
    distance = edit_distance(expected, actual)
    result = {"wer": distance / len(expected) if expected else None, "word_errors": distance,
              "reference_words": len(expected), "hypothesis_words": len(actual)}
    result.update(diarization(reference, hypothesis, collar_ms, ignore_overlap))
    # Conditional boundary error is reported with match coverage, not presented as full alignment accuracy.
    used = set()
    boundary_errors = []
    for row in reference:
        candidates = [(abs(row["start_ms"] - candidate["start_ms"]) + abs(row["end_ms"] - candidate["end_ms"]), i, candidate)
                      for i, candidate in enumerate(hypothesis) if i not in used and words(candidate["text"]) == words(row["text"])
                      and result["speaker_mapping"].get(candidate["speaker_id"]) == row["speaker_id"]]
        if candidates:
            error, index, _ = min(candidates)
            used.add(index); boundary_errors.append(error / 2)
    result.update(boundary_mae_ms=sum(boundary_errors) / len(boundary_errors) if boundary_errors else None,
                  boundary_matched_utterances=len(boundary_errors), reference_utterances=len(reference))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--hypothesis", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--collar-ms", type=int, default=0)
    parser.add_argument("--ignore-overlap", action="store_true")
    parser.add_argument("--duration-ms", type=int)
    parser.add_argument("--elapsed-ms", type=int)
    args = parser.parse_args()
    reference = json.loads(args.reference.read_text(encoding="utf-8-sig"))
    hypothesis = json.loads(args.hypothesis.read_text(encoding="utf-8-sig"))
    result = evaluate(transcript(reference), transcript(hypothesis), args.collar_ms, args.ignore_overlap)
    duration = args.duration_ms if args.duration_ms is not None else hypothesis.get("duration_ms")
    elapsed = args.elapsed_ms if args.elapsed_ms is not None else hypothesis.get("elapsed_ms")
    result.update(audio_ms=duration, elapsed_ms=elapsed, rtf=elapsed / duration if duration and elapsed is not None else None,
                  reference=str(args.reference), hypothesis=str(args.hypothesis),
                  normalization="Unicode letters/digits, casefold, punctuation ignored; no translation or transliteration")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
