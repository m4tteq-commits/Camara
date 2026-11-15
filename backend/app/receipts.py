"""Local OCR plus conservative Romanian receipt parsing. Preview never writes records."""
import csv
import hashlib
import io
import re
import subprocess
import tempfile
from collections import defaultdict
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

from PIL import Image, ImageOps
from .catalog import normalize
from .models import Product, ReceiptLine, ReceiptPreview

MONEY = re.compile(r"(?<!\d)(\d{1,7}[.,]\d{2})(?:\s*[A-F])?\s*$", re.I)
SKIP = re.compile(r"^(?:sub\s*total|tva|total tva|card|cash|numerar|rest|cif|cui|bon fiscal|nr|data|ora|baza|casier|casa|pos)\b")


def cents(value):
    try:
        number = Decimal(value.replace(',', '.')) * 100
        return int(number) if number == number.to_integral_value() and 0 <= number <= 100_000_000 else None
    except InvalidOperation:
        return None


def parse_receipt(text: str, image_hash: str, products: list[Product] = (), confidence=100) -> ReceiptPreview:
    text = text[:20000]
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    merchant = next((line[:100] for line in lines[:6] if not re.search(r"\d{3,}", line) and len(line) > 2), "Magazin")
    spent_on, total = None, None
    for candidate in re.findall(r"\b(?:\d{4}-\d{2}-\d{2}|\d{2}[./]\d{2}[./]\d{2,4})\b", text):
        try:
            if '-' in candidate:
                spent_on = date.fromisoformat(candidate)
            else:
                d, m, y = re.split(r"[./]", candidate)
                spent_on = date(int(y) + (2000 if len(y) == 2 else 0), int(m), int(d))
            break
        except ValueError:
            pass
    names, aliases = defaultdict(dict), defaultdict(dict)
    for product in products:
        names[normalize(product.name)][product.id] = product
        for alias in product.receipt_names:
            aliases[normalize(alias)][product.id] = product
    parsed = []
    pending_name = None
    for line in lines:
        lower = normalize(line)
        match = MONEY.search(line)
        if re.match(r"^total(?:\s|:|$)|^de plata\b", lower) and 'tva' not in lower:
            pending_name = None
            if match:
                total = cents(match.group(1))
            continue
        if SKIP.match(lower):
            pending_name = None
            continue
        if not match:
            if len(line) > 2 and any(c.isalpha() for c in line) and not re.search(r"\d{2}[:./-]\d{2}", line):
                pending_name = line[:100]
            continue
        if match.start() > 0 and line[match.start() - 1] in '-−':
            pending_name = None
            continue
        name = line[:match.start()].strip(' .:-')
        packs, weighted_unit = 1.0, None
        qty_line = re.match(r"^([0-9]+(?:[.,][0-9]+)?)\s*(kg|g|l|ml|buc)?\s*[xX*]\s*", name, re.I)
        if qty_line:
            packs = float(qty_line.group(1).replace(',', '.'))
            weighted_unit = qty_line.group(2)
            rest = name[qty_line.end():].strip()
            name = pending_name if pending_name and (not rest or re.match(r"^[\d.,\s]+$", rest)) else rest
        if not name or not any(c.isalpha() for c in name) or len(name) > 100 or not 0 < packs <= 100000:
            continue
        pending_name = None
        key = normalize(name)
        matches = names.get(key) or aliases.get(key, {})
        product = next(iter(matches.values())) if len(matches) == 1 else None
        warnings = []
        quantity, unit, location = packs, (weighted_unit or 'buc').lower(), 'frigider'
        if product:
            name = product.name
            if not weighted_unit or weighted_unit.lower() == 'buc':
                quantity, unit = packs * product.package_quantity, product.unit
            location = product.location
        elif not weighted_unit:
            warnings.append("Ambalaj necunoscut: verifică greutatea/volumul și numărul de bucăți.")
        if quantity > 100000:
            quantity, unit = packs, 'buc'
            warnings.append("Cantitatea ambalajelor trebuie verificată manual.")
        fee = any(word in normalize(name) for word in ('garantie', 'sgr', 'discount', 'reducere', 'punga', 'sacosa'))
        parsed.append(ReceiptLine(name=name, quantity=quantity, unit=unit, location=location,
                                  line_cents=cents(match.group(1)), include_pantry=not fee, warnings=warnings))
        if len(parsed) >= 80:
            break
    warnings = ["Verifică produsele, cantitățile și totalul înainte de confirmare."]
    if confidence < 75:
        warnings.append("Fotografia conține text recunoscut cu încredere redusă. Corectează câmpurile sau refă fotografia.")
    if total is None:
        warnings.append("Totalul nu a fost identificat. Introdu totalul bonului manual.")
    if not spent_on:
        warnings.append("Data bonului trebuie completată sau verificată.")
    if total is not None and parsed and sum(row.line_cents or 0 for row in parsed) != total:
        warnings.append("Suma pozițiilor diferă de total: pot exista reduceri, garanții sau poziții nerecunoscute.")
    return ReceiptPreview(image_sha256=image_hash, merchant=merchant, spent_on=spent_on, total_cents=total,
                          lines=parsed, raw_text=text, warnings=warnings)


def recognize_image_text(data: bytes, *, page_layout="6") -> tuple[str, float]:
    if not data or len(data) > 12_000_000:
        raise ValueError("Fotografia trebuie să fie JPEG/PNG și să aibă cel mult 12 MB.")
    try:
        with Image.open(io.BytesIO(data), formats=['JPEG', 'PNG']) as source:
            if source.width * source.height > 20_000_000 or max(source.size) > 10000:
                raise ValueError("Fotografia este prea mare. Alege o imagine de rezoluție mai mică.")
            image = ImageOps.exif_transpose(source).convert('L')
            image.thumbnail((3200, 6000))
            image = ImageOps.autocontrast(image)
    except (OSError, Image.DecompressionBombError) as exc:
        raise ValueError("Imagine JPEG/PNG nevalidă.") from exc
    with tempfile.TemporaryDirectory(prefix='camara-ocr-') as temporary:
        path = Path(temporary) / 'receipt.png'
        image.save(path)
        try:
            result = subprocess.run(['tesseract', str(path), 'stdout', '-l', 'ron+eng', '--psm', page_layout, 'tsv'],
                                    capture_output=True, timeout=45, check=True)
        except FileNotFoundError as exc:
            raise RuntimeError("OCR-ul local nu este instalat pe server.") from exc
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as exc:
            raise RuntimeError("Textul nu a putut fi citit. Încearcă o fotografie mai clară.") from exc
    groups, scores = {}, []
    for row in csv.DictReader(io.StringIO(result.stdout.decode('utf-8')), delimiter='\t'):
        if row.get('level') != '5' or not row.get('text', '').strip():
            continue
        key = tuple(row.get(k) for k in ('page_num', 'block_num', 'par_num', 'line_num'))
        groups.setdefault(key, []).append(row['text'])
        scores.append(float(row['conf']))
    text = '\n'.join(' '.join(words) for words in groups.values())
    if not text.strip():
        raise ValueError("Nu am găsit text lizibil în fotografie.")
    return text, sum(scores) / max(1, len(scores))


def recognize_receipt(data: bytes, products=()):
    text, confidence = recognize_image_text(data)
    return parse_receipt(text, hashlib.sha256(data).hexdigest(), products, confidence)
