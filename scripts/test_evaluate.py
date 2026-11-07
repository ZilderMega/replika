import unittest
from evaluate import evaluate, transcript


def row(speaker, start, end, text):
    return dict(speaker_id=speaker, start_ms=start, end_ms=end, text=text)


class EvaluationTests(unittest.TestCase):
    def test_unknown_speaker_never_earns_identity_credit(self):
        result = evaluate([row("one", 0, 1000, "да")], [row("speaker_unknown", 0, 1000, "да")])
        self.assertEqual(0, result['wer'])
        self.assertEqual(1, result['der'])
        self.assertNotIn('speaker_unknown', result['speaker_mapping'])
    def test_speaker_ids_are_permutation_invariant(self):
        reference = [row("one", 0, 1000, "Привет, мир!"), row("two", 1000, 2000, "Да")]
        hypothesis = [row("b", 0, 1000, "привет мир"), row("a", 1000, 2000, "да")]
        result = evaluate(reference, hypothesis)
        self.assertEqual(0, result["wer"])
        self.assertEqual(0, result["der"])
        self.assertEqual(0, result["boundary_mae_ms"])

    def test_speaker_confusion_and_word_errors(self):
        result = evaluate([row("one", 0, 1000, "один"), row("two", 1000, 2000, "два")],
                          [row("a", 0, 2000, "один три")])
        self.assertEqual(.5, result["wer"])
        self.assertEqual(.5, result["der"])

    def test_false_alarm_and_missed_overlap(self):
        result = evaluate([row("one", 0, 1000, "один"), row("two", 0, 1000, "два")],
                          [row("a", 0, 2000, "один")])
        self.assertEqual(1, result["der"])
        self.assertEqual(1000, result["missed_speaker_ms"])
        self.assertEqual(1000, result["false_speaker_ms"])

    def test_silence_is_not_perfect_accuracy(self):
        self.assertIsNone(evaluate([], [row("a", 0, 1000, "галлюцинация")])["wer"])
        self.assertIsNone(evaluate([], [row("a", 0, 1000, "галлюцинация")])["der"])

    def test_bad_timestamps_are_rejected(self):
        with self.assertRaises(ValueError):
            transcript({"transcript": [row("a", 2, 1, "ошибка")]})


if __name__ == "__main__":
    unittest.main()
