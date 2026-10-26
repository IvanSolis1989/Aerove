import hashlib
from http.server import ThreadingHTTPServer
import io
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

from prepare_smart_config_meow_ci_fixture import FixedRedirect, acquire_geo
from fixture_server import Handler


class Response(io.BytesIO):
    status = 200
    headers = {}


class SmartCiFixtureTest(unittest.TestCase):
    def spec(self, data):
        return dict(file="Country.mmdb", bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                    gitBlobSha1=hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest(),
                    url="https://raw.githubusercontent.com/Loyalsoldier/geoip/fixed/Country.mmdb")

    def test_only_verified_download_is_published_and_then_reused(self):
        data = b"unit-only-data"
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            with patch("prepare_smart_config_meow_ci_fixture._open_fixed", return_value=Response(data)) as opened:
                path, receipt = acquire_geo(self.spec(data), Path(temporary), download=True)
                self.assertEqual(path.read_bytes(), data)
                self.assertEqual(receipt["acquisition"], "fixed-commit-ci-fetch")
                self.assertEqual(opened.call_count, 1)
            with patch("prepare_smart_config_meow_ci_fixture._open_fixed") as opened:
                _, receipt = acquire_geo(self.spec(data), Path(temporary), download=False)
                self.assertEqual(receipt["acquisition"], "verified-cache")
                opened.assert_not_called()

    def test_over_limit_or_wrong_hash_never_publishes_cache(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            root = Path(temporary)
            for body in [b"abc-extra", b"bad"]:
                with patch("prepare_smart_config_meow_ci_fixture._open_fixed", return_value=Response(body)):
                    with self.assertRaises(ValueError):
                        acquire_geo(self.spec(b"abc"), root, download=True)
                self.assertFalse((root / "Country.mmdb").exists())
                self.assertFalse(list(root.glob("*.part")))

    def test_local_network_fetch_is_forbidden(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            with patch("prepare_smart_config_meow_ci_fixture._open_fixed") as opened:
                with self.assertRaisesRegex(ValueError, "CI"):
                    acquire_geo(self.spec(b"data"), Path(temporary), download=True)
                opened.assert_not_called()

    def test_redirect_cannot_leave_https_fixed_host(self):
        handler = FixedRedirect()
        for destination in ["http://raw.githubusercontent.com/file", "https://unrelated.example/file"]:
            with self.assertRaisesRegex(ValueError, "redirect"):
                handler.redirect_request(urllib.request.Request("https://raw.githubusercontent.com/fixed"),
                                         None, 302, "redirect", {}, destination)

    def test_current_and_legacy_fixture_owners_keep_distinct_fixed_identities(self):
        import prepare_smart_config_meow_ci_fixture as current
        import prepare_smart_config_meow_legacy_ci_fixture as legacy
        self.assertEqual(current.COMMIT, "23a2c93b6cba799b83f51e56f95b7f378b425503")
        self.assertEqual(current.SCRIPT_SHA, "2acc2840a9c1d896672c9933904a4ccf63a61beb8881543b9138c0606e53c88f")
        self.assertEqual(current.MANIFEST_SHA, "e956168cfab6da7befaf2aa4d442b18a1dd8f7165398b5a9b613caacdd7bc2f4")
        self.assertEqual(legacy.COMMIT, "89abb221a157acc98d6b52c85583e6b5a5b2742b")
        self.assertEqual(legacy.SCRIPT_SHA, "217a4d7ecfb56d99cbb79148159ee636b882af5520c4ad7b6c6c870405f938cb")
        self.assertEqual(legacy.MANIFEST_SHA, "cdf3e8c05f59555b2ef8f711aafa212d963160779e9a1eb1592fd038269fc70b")
        self.assertEqual(current.PINNED_GEO, legacy.PINNED_GEO)
        self.assertEqual(current.ASN, legacy.ASN)

    def test_fixture_server_owner_is_explicit_and_never_inferred_from_input(self):
        from fixture_server import load_smart_fixture
        with patch("fixture_server.load_verified_fixture", return_value={"current": b"new"}) as current, \
                patch("prepare_smart_config_meow_legacy_ci_fixture.load_verified_fixture", return_value={"legacy": b"old"}) as legacy:
            self.assertEqual(load_smart_fixture("fixed-directory"), {"current": b"new"})
            self.assertEqual(load_smart_fixture("fixed-directory", "legacy"), {"legacy": b"old"})
            with self.assertRaisesRegex(ValueError, "owner"): load_smart_fixture("fixed-directory", "guess")
            current.assert_called_once_with("fixed-directory")
            legacy.assert_called_once_with("fixed-directory")

    def test_server_keeps_uuid_protocol_and_serves_only_whitelisted_smart_paths(self):
        class FixtureHandler(Handler):
            smart_files = {"manifest.json": b"unit-manifest"}
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        try:
            with urllib.request.urlopen(base + "/00000000-0000-4000-8000-000000000000") as result:
                self.assertEqual(result.read(), b"AEROVE_LITE_FIXTURE:00000000-0000-4000-8000-000000000000")
            with urllib.request.urlopen(base + "/smart-config-meow/manifest.json") as result:
                self.assertEqual(result.read(), b"unit-manifest")
            for path in ["../secret", "%2e%2e/secret", "manifest.json?extra=1", "providers/999.bin"]:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(base + "/smart-config-meow/" + path)
                self.assertEqual(error.exception.code, 404)
                error.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
