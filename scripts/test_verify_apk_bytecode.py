#!/usr/bin/env python3
import unittest
import struct
import zipfile
import io
import os
import sys

# Import from verify_apk_bytecode
sys.path.insert(0, os.path.dirname(__file__))
from verify_apk_bytecode import parse_axml, parse_uleb128, is_class_defined_in_dex

class TestVerifyApkBytecodeAdversarial(unittest.TestCase):

    def test_parse_uleb128_valid(self):
        # 128 in LEB128 is 0x80, 0x01
        data = bytes([0x80, 0x01])
        val, count = parse_uleb128(data, 0)
        self.assertEqual(val, 128)
        self.assertEqual(count, 2)

    def test_parse_axml_truncated(self):
        data = b"\x03\x00\x08\x00" # Too short
        with self.assertRaises(ValueError):
            parse_axml(data)

    def test_parse_axml_invalid_magic(self):
        data = struct.pack("<II", 0x12345678, 100) + b"\x00" * 92
        with self.assertRaises(ValueError):
            parse_axml(data)

    def test_is_class_defined_in_dex_short(self):
        dex_bytes = b"dex\n035\x00"
        self.assertFalse(is_class_defined_in_dex(dex_bytes, "Lcom/example/App;"))

    def test_is_class_defined_in_dex_missing_class(self):
        # Build minimal DEX header
        # 112 bytes header
        # magic: "dex\n035\0"
        header = bytearray(112)
        header[0:8] = b"dex\n035\x00"
        self.assertFalse(is_class_defined_in_dex(bytes(header), "Lcom/example/App;"))

if __name__ == "__main__":
    unittest.main()
