"""Keep the English-first public entry points and their explicit Chinese choice."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PublicLanguageTests(unittest.TestCase):
    def test_readmes_are_english_by_default_and_chinese_is_an_explicit_link(self):
        for name in ('README.md', 'docs/i18n/README.en.md'):
            text = (ROOT / name).read_text()
            self.assertIn('[简体中文]', text)
            visible = re.sub(r'\]\([^)]*\)', ']', text).replace('简体中文', '')
            self.assertIsNone(re.search(r'[\u4e00-\u9fff]', visible), name)
            self.assertIn('name="appearance"', text)
            self.assertIn('GPL-3.0', text)
        self.assertTrue((ROOT / 'docs/i18n/README.zh-CN.md').is_file())

    def test_document_landings_have_no_automatically_expanded_chinese(self):
        for name in ('docs/README.md', 'docs/aerove/README.md', 'docs/aerove-lite/README.md',
                     'docs/shared/README.md', 'docs/development/README.md',
                     'docs/research/README.md', 'docs/archive/README.md', 'docs/aerove/network-defaults.md'):
            text = (ROOT / name).read_text()
            self.assertIn('<details>\n<summary>简体中文', text)
            self.assertNotRegex(text, r'<details\s+open')
            visible = text.split('<details>', 1)[0]
            visible = re.sub(r'\]\([^)]*\)', ']', visible).replace('简体中文', '')
            self.assertIsNone(re.search(r'[\u4e00-\u9fff]', visible), name)

    def test_language_coverage_is_explicit_and_historical_evidence_remains(self):
        text = (ROOT / 'docs/development/LANGUAGE_COVERAGE.md').read_text()
        self.assertIn('not completion of all documentation', text)
        self.assertIn('docs/shared/UI_CONTRACT.md', text)
        self.assertIn('docs/development/upstreams.md', text)
        network = (ROOT / 'docs/aerove/network-defaults.md').read_text()
        for value in ('223.5.5.5', '223.6.6.6', '8.8.8.8', '1.1.1.1',
                      '6847cd225d70bc9eb6fdd9311d50ff53bc91981c', 'allowBypass=false'):
            self.assertIn(value, network)
        self.assertIn('not a claim that every value below is the current build default', network)


if __name__ == '__main__':
    unittest.main()
