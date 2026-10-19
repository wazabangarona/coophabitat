"""Vecteur de référence partagé avec DrawEngineTest (Java). Lancer : python3 -m unittest discover tools"""
import unittest

import verifier_tirage as v

SEED = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
PARTS = [f"ADH-000{i}" for i in range(1, 8)]
LOTS = ["LOT-A1", "LOT-A2", "LOT-B1"]


class GoldenVector(unittest.TestCase):
    def test_same_result_as_the_server(self):
        pd = v.participants_digest("ATT-TEST-000001", PARTS)
        ld = v.lots_digest("ATT-TEST-000001", LOTS)
        self.assertEqual(v.commitment(SEED), "3df7dc6fac795849e3484fc8d9995fe158bf1aa1b11a9fa6b676d11328e51b54")
        self.assertEqual(pd, "4841d3f7af88bfb7f336b7fef7c88218fd0b1f6f0d07ec78bb690e3b20f2700f")
        self.assertEqual(ld, "11bf5ad6e9ac5ee3a3a9d3fe69c564eb9232dd1c7c7fc931abc1be2e1d62c94c")
        self.assertEqual(v.shuffle(PARTS, v.final_seed(SEED, pd, ld, "17 42 08")),
                         ["ADH-0005", "ADH-0007", "ADH-0001", "ADH-0002", "ADH-0003", "ADH-0006", "ADH-0004"])


if __name__ == "__main__":
    unittest.main()
