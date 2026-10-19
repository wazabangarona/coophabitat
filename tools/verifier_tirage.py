#!/usr/bin/env python3
"""Vérification indépendante d'un tirage au sort CoopHabitat (algorithme CH-TIRAGE-v1).

N'utilise que la bibliothèque standard de Python : tout membre ou auditeur peut recalculer le tirage
sans faire confiance au serveur. Les valeurs à fournir sont publiées dans l'espace membre
(« Lots et attributions ») une fois les résultats publiés.

Exemple :
  python3 tools/verifier_tirage.py --campagne ATT-2026-000001 --graine <hex> --entropie "12 34 56" \\
      --participants "ADH-0001;ADH-0002;ADH-0003" --lots "LOT-A1;LOT-A2" \\
      --engagement <hex> --empreinte-participants <hex> --empreinte-lots <hex>
"""
import argparse
import hashlib
import hmac
import struct
import sys

VERSION = "CH-TIRAGE-v1"
LONG_MAX = (1 << 63) - 1


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def participants_digest(campaign: str, members: list[str]) -> str:
    return sha256_hex(f"{VERSION}|participants|{campaign}|" + ";".join(members))


def lots_digest(campaign: str, lots: list[str]) -> str:
    return sha256_hex(f"{VERSION}|lots|{campaign}|" + ";".join(lots))


def commitment(seed_hex: str) -> str:
    return sha256_hex(f"{VERSION}|engagement|{seed_hex}")


def final_seed(seed_hex: str, p_digest: str, l_digest: str, entropy: str) -> bytes:
    return bytes.fromhex(sha256_hex("|".join([VERSION, seed_hex, p_digest, l_digest, (entropy or "").strip()])))


class Stream:
    """Flux déterministe : HMAC-SHA256(graine finale, compteur sur 8 octets gros-boutiste)."""

    def __init__(self, seed: bytes):
        self.seed, self.counter, self.block = seed, 0, b""

    def next_long63(self) -> int:
        if len(self.block) < 8:
            self.block = hmac.new(self.seed, struct.pack(">q", self.counter), hashlib.sha256).digest()
            self.counter += 1
        value, self.block = struct.unpack(">q", self.block[:8])[0], self.block[8:]
        return value & LONG_MAX

    def next_int(self, bound: int) -> int:
        limit = LONG_MAX - (LONG_MAX % bound)  # rejet : pas de biais de modulo
        while True:
            v = self.next_long63()
            if v < limit:
                return v % bound


def shuffle(sorted_members: list[str], seed: bytes) -> list[str]:
    order, stream = list(sorted_members), Stream(seed)
    for i in range(len(order) - 1, 0, -1):
        j = stream.next_int(i + 1)
        order[i], order[j] = order[j], order[i]
    return order


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--campagne", required=True, help="référence de la campagne, ex. ATT-2026-000001")
    ap.add_argument("--graine", required=True, help="graine révélée (hexadécimal)")
    ap.add_argument("--entropie", default="", help="entropie publique saisie en séance")
    ap.add_argument("--participants", required=True, help="numéros d'adhérent séparés par « ; » (ordre indifférent)")
    ap.add_argument("--lots", required=True, help="références des lots séparées par « ; » (ordre indifférent)")
    ap.add_argument("--engagement", help="engagement publié avant le tirage")
    ap.add_argument("--empreinte-participants", help="empreinte publiée de la liste des participants")
    ap.add_argument("--empreinte-lots", help="empreinte publiée de la liste des lots")
    a = ap.parse_args()

    members = sorted(x.strip() for x in a.participants.split(";") if x.strip())
    lots = sorted(x.strip() for x in a.lots.split(";") if x.strip())
    p_dig, l_dig = participants_digest(a.campagne, members), lots_digest(a.campagne, lots)
    ok = True
    for label, expected, actual in [("Engagement sur la graine", a.engagement, commitment(a.graine)),
                                    ("Empreinte des participants", a.empreinte_participants, p_dig),
                                    ("Empreinte des lots", a.empreinte_lots, l_dig)]:
        if expected:
            match = expected.lower() == actual
            ok &= match
            print(f"{label:28}: {'CONFORME' if match else 'NON CONFORME'}")
    order = shuffle(members, final_seed(a.graine, p_dig, l_dig, a.entropie))
    print("\nOrdre recalculé :")
    for rank, number in enumerate(order, start=1):
        lot = lots[rank - 1] if rank <= len(lots) else "liste d'attente"
        print(f"  {rank:>3}. {number:<16} → {lot}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
