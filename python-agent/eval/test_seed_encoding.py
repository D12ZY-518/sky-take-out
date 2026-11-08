"""演示种子必须在首条写入前声明 UTF-8 会话编码。"""

import unittest
from pathlib import Path


class SeedEncodingTest(unittest.TestCase):
    def test_utf8_declaration_precedes_inserts(self):
        seed = Path(__file__).resolve().parents[2] / "docker" / "seed" / "02-sample-data.sql"
        content = seed.read_text(encoding="utf-8")
        self.assertLess(content.index("SET NAMES utf8mb4;"), content.index("INSERT IGNORE"))
        self.assertIn("宫保鸡丁", content)


if __name__ == "__main__":
    unittest.main()
