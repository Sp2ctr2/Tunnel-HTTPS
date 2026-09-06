"""Release selection and static generation contract tests."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import build
from test_site import static_checks


class BuildTests(unittest.TestCase):
    def release(self, **changes):
        value = {'name': 'Example beta', 'tag_name': 'v0.9-test', 'draft': False,
                 'published_at': '2026-09-07T00:00:00Z', 'prerelease': True,
                 'html_url': build.REPO + '/releases/tag/v0.9-test',
                 'assets': [{'name': 'app.apk', 'state': 'uploaded'}]}
        value.update(changes)
        return value

    def choose(self, values):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'releases.json'
            path.write_text(json.dumps(values))
            return build.select_release(path)

    def test_no_release_input(self):
        self.assertIsNone(build.select_release(None))

    def test_empty_release_list(self):
        self.assertIsNone(self.choose([]))

    def test_draft_is_never_a_public_download(self):
        self.assertIsNone(self.choose([self.release(draft=True)]))

    def test_unpublished_is_not_a_release(self):
        self.assertIsNone(self.choose([self.release(published_at=None)]))

    def test_wrong_origin_rejected(self):
        self.assertIsNone(self.choose([self.release(html_url='https://example.com/releases/tag/v1')]))

    def test_uploaded_apk_required(self):
        self.assertIsNone(self.choose([self.release(assets=[{'name':'app.apk','state':'new'}])]))
        self.assertIsNone(self.choose([self.release(assets=[{'name':'README.txt','state':'uploaded'}])]))

    def test_published_beta_allowed_without_being_called_stable(self):
        chosen = self.choose([self.release()])
        self.assertTrue(chosen['prerelease'])
        self.assertEqual(chosen['url'], self.release()['html_url'])

    def test_skip_draft_then_select_published(self):
        chosen = self.choose([self.release(draft=True),self.release(name='Published')])
        self.assertEqual(chosen['name'], 'Published')

    def test_escapes_release_label(self):
        old = build.LANG
        build.LANG = 'en'
        try:
            page = build.guide({'name':'<img src=x onerror=alert(1)>','url':build.REPO+'/releases/tag/v1'})
            self.assertIn('&lt;img', page)
            self.assertNotIn('<img src=x', page)
        finally:
            build.LANG = old

    def test_localized_links_and_generated_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'site'
            with contextlib.redirect_stdout(io.StringIO()):
                build.build(out)
            self.assertEqual(static_checks(out)['html_pages'], 9)
            self.assertIn('<html lang="ko">', (out/'ko/guide/index.html').read_text())
            self.assertIn('href="../../guide/"', (out/'ko/guide/index.html').read_text())
            self.assertNotIn('/releases/latest', (out/'guide/index.html').read_text())
            self.assertFalse(json.loads((out/'site-manifest.json').read_text())['published_apk_selected'])

    def test_build_is_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            with contextlib.redirect_stdout(io.StringIO()):
                build.build(out)
                before = {str(p.relative_to(out)):p.read_bytes() for p in out.rglob('*') if p.is_file()}
                build.build(out)
            after = {str(p.relative_to(out)):p.read_bytes() for p in out.rglob('*') if p.is_file()}
            self.assertEqual(before,after)

if __name__ == '__main__':
    unittest.main()
