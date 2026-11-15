"""Fast, deterministic Romanian list parsing. Ambiguities stay visible for confirmation."""
from datetime import date, datetime
import re
from fractions import Fraction

from .catalog import canonical
from .models import BulkPreview, BulkPreviewRow, PantryInput

NUMBERS = {"un": 1, "o": 1, "unu": 1, "una": 1, "doi": 2, "doua": 2, "trei": 3, "patru": 4,
           "cinci": 5, "sase": 6, "sapte": 7, "opt": 8, "noua": 9, "zece": 10, "jumatate": 0.5}
UNITS = {"g": "g", "gr": "g", "grame": "g", "gram": "g", "kg": "kg", "kilogram": "kg", "kilograme": "kg",
         "ml": "ml", "mililitri": "ml", "mililitru": "ml", "l": "l", "litru": "l", "litri": "l",
         "buc": "buc", "bucata": "buc", "bucati": "buc", "bucăți": "buc", "bucată": "buc"}
SHELF = {"paste", "orez", "ovaz", "paine", "ulei", "cartofi", "ceapa", "usturoi", "ton la conserva", "naut fiert", "fasole fiarta", "linte fiarta", "cuscus", "malai"}


def preview(text: str) -> BulkPreview:
    text = re.sub(r"(?<=\d),(?=\d)", ".", text)
    parts = [s.strip(" .") for s in re.split(r"[,;\n]+|\s+(?:și|si|plus)\s+", text, flags=re.I) if s.strip(" .")]
    if not parts or len(parts) > 40:
        raise ValueError("Introdu între 1 și 40 de alimente, separate prin virgulă sau rând nou.")
    rows = []
    for part in parts:
        warnings = []
        expiry = None
        suffix = re.search(r"\s+(?:expiră|expira|până la|pana la)\s+(.+)$", part, re.I)
        if suffix:
            raw_date = suffix.group(1).strip()
            try:
                expiry = date.fromisoformat(raw_date) if "-" in raw_date else datetime.strptime(raw_date, "%d.%m.%Y").date()
            except ValueError:
                warnings.append("Termen nerecunoscut; completează-l manual după import.")
            part = part[:suffix.start()]
        suffix_amount = re.match(r"^(.+?)\s+(\d+(?:\.\d+)?(?:/\d+)?)\s*(kg|g|gr|grame|ml|l|litri|litru|buc|bucăți|bucati)?$", part, re.I)
        if suffix_amount:
            name_first, amount_last, unit_last = suffix_amount.groups()
            part = f"{amount_last} {unit_last or ''} {name_first}"
        match = re.match(r"^(?:(\d+(?:\.\d+)?(?:/\d+)?)\s*|(un|o|unu|una|doi|două|doua|trei|patru|cinci|șase|sase|șapte|sapte|opt|nouă|noua|zece|jumătate|jumatate)\s+)(.+)$", part, re.I)
        quantity, unit, name = 1.0, "buc", part
        if match:
            digits, word, name = match.groups()
            raw_amount = digits or word
            try:
                quantity = float(Fraction(raw_amount)) if digits else float(NUMBERS[canonical(word)])
            except (ValueError, ZeroDivisionError, OverflowError) as exc:
                raise ValueError(f"Cantitate invalidă: {raw_amount[:40]}") from exc
            name = re.sub(r"^de\s+", "", name.strip(), flags=re.I)
            unit_match = re.match(r"^([a-zăâîșț]+)\.?\s+(.+)$", name, re.I)
            if unit_match and canonical(unit_match.group(1)) in UNITS:
                unit, name = UNITS[canonical(unit_match.group(1))], unit_match.group(2)
            elif re.match(r"^(?:conservă|conserve|conserva)\s+(?:de\s+)?", name, re.I):
                name = re.sub(r"^(?:conservă|conserve|conserva)\s+(?:de\s+)?", "", name, flags=re.I) + " la conservă"
            elif re.match(r"^(?:pachet|pachete|cutie|cutii|sticlă|sticle|sticla|borcan|borcane)\s+", name, re.I):
                warnings.append("Ambalaj fără gramaj: păstrat ca bucăți; verifică denumirea și cantitatea.")
        else:
            warnings.append("Cantitate neprecizată: am propus 1 buc; verifică înainte de salvare.")
        name = re.sub(r"^de\s+", "", name.strip(), flags=re.I).strip()
        if not name or not any(c.isalpha() for c in name) or name.startswith("-") or len(name) > 100 or not 0 < quantity <= 100000:
            raise ValueError(f"Verifică alimentul: {part[:100]}")
        rows.append(BulkPreviewRow(item=PantryInput(name=name[0].upper() + name[1:], quantity=quantity, unit=unit,
                    location="camara" if canonical(name) in SHELF else "frigider", expires_on=expiry), warnings=warnings))
    return BulkPreview(rows=rows)
