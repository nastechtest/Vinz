#!/usr/bin/env python3
import hashlib, importlib.util, io, tarfile, tempfile, unittest, zipfile
from pathlib import Path

MODULE = Path(__file__).parents[1] / "models" / "package_sing.py"
spec = importlib.util.spec_from_file_location("package_sing", MODULE)
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)

class SingPackageTests(unittest.TestCase):
    def test_deterministic_archive_and_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d) / "a.zip", Path(d) / "b.zip"
            entries = {"z.txt": b"z", "a.txt": b"a"}
            p.make_zip(a, entries); p.make_zip(b, entries)
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertEqual(p.details(a, entries)["file_list"], ["a.txt", "z.txt"])
            with zipfile.ZipFile(a) as z:
                for i in z.infolist():
                    self.assertEqual(i.date_time, p.STAMP)
                    self.assertEqual(i.external_attr >> 16, 0o100644)
    def test_unsafe_output_names_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            for bad in ("../evil", "/evil", "a/b", "a\\b"):
                with self.assertRaises(ValueError): p.make_zip(Path(d) / "x.zip", {bad: b"x"})
    def test_missing_runtime_entry_rejected(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as z: z.writestr("jni/arm64-v8a/libonnxruntime.so", b"x")
        with self.assertRaisesRegex(ValueError, "missing"):
            p.zip_entries(raw.getvalue(), {"jni/arm64-v8a/libonnxruntime.so", "jni/x86_64/libonnxruntime.so"})
    def test_unsafe_spleeter_link_rejected(self):
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode="w:bz2") as t:
            for n in ("sherpa-onnx-spleeter-2stems-fp16/vocals.fp16.onnx", "sherpa-onnx-spleeter-2stems-fp16/accompaniment.fp16.onnx"):
                i = tarfile.TarInfo(n); i.type = tarfile.SYMTYPE; i.linkname = "../../bad"; t.addfile(i)
        with self.assertRaisesRegex(ValueError, "unsafe"):
            p.tar_entries(raw.getvalue())
    def test_corrupt_source_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); (root / "x").write_bytes(b"wrong")
            with self.assertRaisesRegex(ValueError, "checksum"):
                p.source_bytes(root, {"filename":"x", "bytes":5, "sha256":"0" * 64})
    def test_modelstore_enum_consistency(self):
        package = {"filename":"x.zip", "sha256":"a" * 64, "download_bytes":12, "unpacked_bytes":34}
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "ModelStore.kt"; f.write_text('"x.zip", "' + 'a'*64 + '", 1_2, tr(R.string.test), 3_4,')
            p.check_source(f, {"packages":[package]})
            f.write_text('"x.zip" ' + 'a'*64 + ' 12')
            with self.assertRaisesRegex(ValueError, "differs"): p.check_source(f, {"packages":[package]})
if __name__ == "__main__": unittest.main()
