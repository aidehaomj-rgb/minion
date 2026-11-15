import ast
import copy
import json
import sys
import unittest
from pathlib import Path
from replay_runtime_jobs import compare, replay, load


class ReplayRuntimeJobsTest(unittest.TestCase):
    def test_existing_metrics_unknown_is_not_a_score(self):
        r = replay({'taskId': 'real-format', 'status': 'completed',
                    'sessionInputTokens': 999, 'sessionOutputTokens': 777})
        self.assertTrue(r['quality']['businessAccuracy'].startswith('unknown'))
        self.assertTrue(r['quality']['falseCompletion'].startswith('unknown'))
        self.assertEqual('unknown_missing_or_invalid', r['metrics']['taskInputTokens'])
        self.assertEqual('unknown_no_event_trace', r['trace']['status'])

    def test_trace_replays_latest_requirements_repeats_stops(self):
        events = [dict(eventId='r1', kind='requirement', revision=1),
                  dict(eventId='s1', kind='execution_started', executionKey='sql-hash', instanceId='i1', revision=1),
                  dict(eventId='r2', kind='requirement', revision=2),
                  dict(eventId='s2', kind='execution_started', executionKey='sql-hash', instanceId='i2', revision=1),
                  dict(eventId='stop', kind='stop')]
        r = replay({'events': events + [events[1]]})
        self.assertEqual(2, r['trace']['latestRequirementRevision'])
        self.assertEqual(1, r['trace']['repeatedExecutions'])
        self.assertEqual(1, r['trace']['staleRevisionExecutions'])
        self.assertEqual(1, r['trace']['stopsWithPendingExecutions'])
        self.assertEqual(1, r['trace']['duplicateEventsIgnored'])
        # A pending background process is not proof of a premature stop or leak.
        self.assertEqual('unknown_not_labelled', r['quality']['prematureStop'])
        self.assertEqual('unknown_not_labelled', r['quality']['crossProjectLeak'])

    def test_real_format_fixtures_pair_and_compare(self):
        base = Path(__file__).parent / 'runtime-replay-fixtures'
        result = compare(load([base / 'before.json']), load([base / 'after.json']))
        self.assertEqual(2, result['paired'])
        delta = result['pairs'][0]['deltaAfterMinusBefore']
        self.assertEqual(-40, delta['taskInputTokens'])
        self.assertEqual(-500, delta['activeMillis'])
        self.assertEqual(1, result['quality']['prematureStop']['before']['unknown'])
        self.assertEqual(1.0, result['quality']['prematureStop']['before']['trueRate'])
        self.assertEqual(0.0, result['quality']['prematureStop']['after']['trueRate'])

    def test_duplicate_reports_and_conflicting_snapshots(self):
        a = {'taskId': 'x', 'revision': 2}
        r = compare([a, a], [a])
        self.assertEqual(1, r['duplicateReportsIgnored']['before'])
        with self.assertRaises(ValueError):
            compare([a, dict(a, status='completed')], [a])
        self.assertEqual(0, compare([a], [dict(a, revision=3)])['paired'])

    def test_malformed_reviews_or_scope_cannot_score(self):
        a = {'taskId': 'x', 'taskInputTokens': 10,
             'manualBusinessReview': {'accepted': True, 'expectedItems': 1,
                                      'omittedItems': 2, 'duplicateItems': 0, 'incorrectItems': 0,
                                      'latestInstructionFollowed': True}}
        r = compare([a], [a])
        self.assertEqual('unknown_not_labelled', r['pairs'][0]['before']['quality']['accepted'])
        self.assertTrue(r['pairs'][0]['deltaAfterMinusBefore']['taskInputTokens'].startswith('unknown'))
        for bad in (True, -1, float('nan'), float('inf')):
            self.assertEqual('unknown_missing_or_invalid', replay({'activeMillis': bad})['metrics']['activeMillis'])

    def test_conflicting_event_ids_rejected(self):
        with self.assertRaises(ValueError):
            replay({'events': [{'eventId': 'x', 'kind': 'stop'}, {'eventId': 'x', 'kind': 'other'}]})

    def test_python37_syntax(self):
        for name in ('replay_runtime_jobs.py', 'test_replay_runtime_jobs.py'):
            source = (Path(__file__).parent / name).read_text(encoding='utf-8-sig')
            if sys.version_info >= (3, 8):
                ast.parse(source, feature_version=(3, 7))
            else:
                ast.parse(source)


if __name__ == '__main__':
    unittest.main()
