"""Offline regression for screenshot navigation, focus and four-screen review sheets."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image, ImageDraw
import capture_store_screenshots as capture


class CaptureWorkflowTest(unittest.TestCase):
    def test_four_screens_in_both_layout_directions(self):
        for locale in ('en-US', 'ar-SA'):
            with self.subTest(locale=locale), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                apk = root/'capture.apk'
                apk.touch()
                state = {'row': 0, 'about': False}
                shots = []

                def key(code):
                    if code == 19: state['row'] = max(0, state['row']-1)
                    if code == 20: state['row'] = min(2, state['row']+1)

                def nodes():
                    return ([{'text': '© 2026 Buzin Solutions LTDA'}] if state['about'] else
                            [{'text': 'About BuzinCast', 'bounds': '[500,700][900,800]'}])

                def shot(path, region):
                    expected = {'01-conectar.png': 0, '04-ferramentas.png': 1, '02-configuracoes.png': 2}
                    if path.name in expected:
                        self.assertEqual(expected[path.name], state['row'])
                        self.assertFalse(state['about'])
                    else:
                        self.assertEqual('03-sobre.png', path.name)
                        self.assertTrue(state['about'])
                    shots.append(path.stem)

                with patch.object(capture, 'OUTPUT', root), patch.object(capture, 'RTL', False), \
                     patch.object(capture, 'TOUCHED_PACKAGES', set()), patch.object(capture, 'adb', return_value=b'Success'), \
                     patch.object(capture, 'key', side_effect=key), patch.object(capture, 'nodes', side_effect=nodes), \
                     patch.object(capture, 'tap', side_effect=lambda *args: state.update(about=True)), \
                     patch.object(capture, 'settled_shot', side_effect=shot), patch.object(capture.time, 'sleep'):
                    capture.capture('normal', 'com.buzincast.tv', [locale], apk)
                self.assertEqual(list(capture.SCREENSHOTS), shots)

    def test_tools_requires_its_own_focus_outline_ltr_and_rtl(self):
        for rtl in (False, True):
            with self.subTest(rtl=rtl), patch.object(capture, 'RTL', rtl):
                image = Image.new('RGB', (1920, 1080), '#081019')
                draw = ImageDraw.Draw(image)
                draw.rectangle(capture.screen_region((110,327,399,334)), fill='#7cf4cd')
                self.assertFalse(capture.focus_visible(image, '04-ferramentas.png'))
                draw.rectangle(capture.screen_region((110,199,399,206)), fill='#7cf4cd')
                self.assertTrue(capture.focus_visible(image, '04-ferramentas.png'))

    def test_generates_all_four_review_sheets(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            dest = root/'normal'/'en-US'
            dest.mkdir(parents=True)
            files = []
            for name in capture.SCREENSHOTS:
                path = dest/(name+'.png')
                Image.new('RGB', (32,18), '#7cf4cd').save(path)
                files.append(path)
            with patch.object(capture, 'OUTPUT', root), patch.object(capture, 'CAPTURED', files):
                capture.contact_sheets()
            self.assertEqual({f'review-{name}.jpg' for name in capture.SCREENSHOTS},
                             {p.name for p in root.glob('review-*.jpg')})


if __name__ == '__main__': unittest.main()
