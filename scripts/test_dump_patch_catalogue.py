import unittest
import json
from dump_patch_catalogue import compatibility, dump, RULES


class CatalogueTests(unittest.TestCase):
    def test_both_reference_formats_and_invalid_reference(self):
        shared = [{'app': ['1']}, [], [{'packageName': None}]]
        self.assertEqual(compatibility({'compatiblePackagesKey': 0}, shared), shared[0])
        self.assertEqual(compatibility({'compatibilityKey': 1}, shared), [])
        with self.assertRaises(ValueError): compatibility({'compatibilityKey': 8}, shared)

    def test_universal_rows_preserve_source_and_declared_category(self):
        data = dict(bundles=[dict(source='github', repo='owner/repo', patches=[
            dict(name='Disable Sentry', category='Telemetry', compatibilityKey=0),
            dict(name='App specific', compatibilityKey=1), dict(name='Bad', compatibilityKey=8)])],
            compatibilities=[[], {'app': ['1']}])
        result = dump(data, json.loads(RULES.read_text(encoding='utf-8')))
        self.assertEqual(result['summary']['universal'], 1)
        self.assertEqual(result['rows'][0]['declaredCategory'], 'Telemetry')
        self.assertEqual(result['rows'][0]['category'], 'Privacy & telemetry')
        self.assertFalse(result['rows'][1]['universal'])
        self.assertEqual(len(result['errors']), 1)


if __name__ == '__main__': unittest.main()
