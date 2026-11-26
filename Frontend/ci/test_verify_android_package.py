import struct
import unittest
from verify_android_package import elf_load_alignment

def elf64(alignment):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 112, alignment)
    return bytes(data)

class ElfAlignmentTests(unittest.TestCase):
    def test_16kb_load_alignment(self):
        self.assertEqual(elf_load_alignment(elf64(16384)), [16384])

    def test_4kb_is_distinguishable(self):
        self.assertEqual(elf_load_alignment(elf64(4096)), [4096])

    def test_rejects_non_elf(self):
        with self.assertRaises(ValueError):
            elf_load_alignment(b"not an ELF")

    def test_rejects_missing_load_segments(self):
        data = bytearray(elf64(16384))
        struct.pack_into("<I", data, 64, 0)
        with self.assertRaises(ValueError):
            elf_load_alignment(bytes(data))

if __name__ == "__main__":
    unittest.main()
