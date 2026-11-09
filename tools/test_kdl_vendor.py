"""The vendored readers are byte for byte what vendored.json records. Run: python3 -m unittest discover -s tools"""
import hashlib
import json
import os
import unittest

VENDOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")


class VendoredTest(unittest.TestCase):
    def test_hashes_match_the_record(self):
        with open(os.path.join(VENDOR, "vendored.json"), encoding="utf-8") as f:
            items = json.load(f)["vendored"]
        self.assertTrue(items)
        for item in items:
            with open(os.path.join(VENDOR, item["path"]), "rb") as f:
                got = hashlib.sha256(f.read()).hexdigest()
            self.assertEqual(got, item["sha256"], item["path"])
            self.assertTrue(os.path.isfile(os.path.join(VENDOR, item["licence_file"])), item["licence_file"])

    def test_every_python_file_is_recorded(self):
        with open(os.path.join(VENDOR, "vendored.json"), encoding="utf-8") as f:
            recorded = {i["path"] for i in json.load(f)["vendored"]}
        on_disk = set()
        for d, dirs, files in os.walk(VENDOR):
            dirs[:] = [x for x in dirs if x != "__pycache__"]
            for name in files:
                rel = os.path.relpath(os.path.join(d, name), VENDOR).replace(os.sep, "/")
                if name.endswith(".py") and rel != "__init__.py":
                    on_disk.add(rel)
        self.assertEqual(on_disk, recorded)


if __name__ == "__main__":
    unittest.main()
