"""Regression cases for the escaped-backtick publication incident."""
import unittest
from check_rendering import check_markdown, check_native


class RenderingTests(unittest.TestCase):
    def test_normal_inline_code(self):
        self.assertFalse(check_markdown('Use `VpnService` and `adb install -r demo.apk`.')[0])

    def test_escaped_inline_code(self):
        self.assertTrue(check_markdown(r'Use \`VpnService\`.')[0])

    def test_shell_fence(self):
        self.assertFalse(check_markdown('```sh\nadb install -r demo.apk\n```\n')[0])

    def test_escaped_fence(self):
        self.assertTrue(check_markdown('\\`\\`\\`sh\nadb install -r demo.apk\n\\`\\`\\`')[0])

    def test_unclosed_fence(self):
        self.assertTrue(check_markdown('```sh\nadb install -r demo.apk\n')[0])

    def test_bare_command(self):
        self.assertTrue(check_markdown('./gradlew :app:assembleDebug\n')[0])

    def test_bare_mermaid(self):
        self.assertTrue(check_markdown('flowchart LR\n A --> B\n')[0])

    def test_normal_mermaid(self):
        self.assertFalse(check_markdown('```mermaid\nflowchart LR\n A --> B\n```\n')[0])

    def test_nested_fence_example(self):
        self.assertFalse(check_markdown('````markdown\n```sh\nadb shell\n```\n````\n')[0])

    def test_quoted_fence(self):
        self.assertFalse(check_markdown('> ```sh\n> adb shell\n> ```\n')[0])

    def test_valid_details(self):
        self.assertFalse(check_markdown('<details>\n<summary>Build</summary>\n\n```sh\n./gradlew :app:assembleDebug\n```\n\n</details>\n')[0])

    def test_html_swallowed_markdown(self):
        self.assertTrue(check_markdown('<div>\n```sh\nadb shell\n```\n</div>\n')[0])

    def test_literal_delimiters_in_code_example(self):
        self.assertFalse(check_markdown('```text\n\\`escaped example\\`\n```\n')[0])

    def test_native_html(self):
        content = '<pre lang="mermaid">flowchart LR</pre><details><summary>Commands</summary><pre>adb install -r demo.apk\n./gradlew :app:assembleDebug\n./gradlew :app:testDebugUnitTest :app:lintDebug</pre></details>'
        self.assertEqual(check_native(content)['status'], 'PASS')
        self.assertEqual(check_native(content.replace('<details>', '<div>').replace('</details>', '</div>'))['status'], 'FAIL')


if __name__ == '__main__':
    unittest.main()
