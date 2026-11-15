import re
import subprocess
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from itertools import groupby
from pathlib import Path
from uuid import UUID, uuid4

from pydantic import Field

from .ai import AiError
from .models import Model
from .personal_recipe_ai import IMPORT_RULES, ImportedRecipe, schema_for

PDF_MAX_BYTES = 20 * 1024 * 1024
PDF_MAX_PAGES = 4
PDF_MAX_TEXT = 7000
PDF_SESSION_TTL = 30 * 60
PDF_SESSION_LIMIT = 6
PDF_PAGE_CACHE_LIMIT = 64
INVALID_PDF = "Fișierul PDF nu poate fi citit. Verifică dacă se deschide fără parolă."


class PdfUpload(Model):
    id: UUID
    pages: int


@dataclass
class PdfDocument:
    path: Path
    pages: int
    expires: float
    cached: OrderedDict[int, str] = field(default_factory=OrderedDict)


class PdfSessions:
    def __init__(self):
        self.documents: OrderedDict[UUID, PdfDocument] = OrderedDict()

    def expire(self):
        now = time.monotonic()
        for book_id, document in list(self.documents.items()):
            if document.expires <= now:
                self.remove(book_id)

    def remove(self, book_id):
        self.documents[book_id].path.unlink(missing_ok=True)
        del self.documents[book_id]

    def add(self, path: Path, pages: int) -> PdfUpload:
        self.expire()
        if len(self.documents) >= PDF_SESSION_LIMIT:
            self.remove(next(iter(self.documents)))
        book_id = uuid4()
        self.documents[book_id] = PdfDocument(path, pages, time.monotonic() + PDF_SESSION_TTL)
        return PdfUpload(id=book_id, pages=pages)

    def get(self, book_id: UUID) -> PdfDocument:
        self.expire()
        document = self.documents.get(book_id)
        if document is None:
            raise AiError("Sesiunea PDF a expirat. Încarcă din nou fișierul.", 410)
        self.documents.move_to_end(book_id)
        document.expires = time.monotonic() + PDF_SESSION_TTL
        return document


class PdfRecipes(Model):
    recipes: list[ImportedRecipe] = Field(max_length=4)
    more_recipes: bool


def pdf_command(args: list[str], timeout: int, error: str) -> str:
    try:
        result = subprocess.run(args, capture_output=True, timeout=timeout, check=True)
    except FileNotFoundError as exc:
        raise AiError("Citirea PDF sau OCR nu este disponibilă pe ZimaOS.") from exc
    except subprocess.TimeoutExpired as exc:
        raise AiError("Citirea PDF a durat prea mult. Încearcă un interval mai mic.", 504) from exc
    except subprocess.CalledProcessError as exc:
        raise AiError(error, 422) from exc
    return result.stdout.decode("utf-8", errors="replace")


def pdf_pages(path: Path) -> int:
    info = pdf_command(["pdfinfo", str(path)], 8, INVALID_PDF)
    pages = re.search(r"^Pages:\s+(\d+)\s*$", info, re.MULTILINE)
    if pages is None or int(pages[1]) < 1:
        raise AiError(INVALID_PDF, 422)
    return int(pages[1])


def read_pages(path: Path, first: int, last: int):
    # Poppler separates physical pages with a form feed unless asked otherwise.
    text = pdf_command(["pdftotext", "-f", str(first), "-l", str(last), "-layout", "-enc", "UTF-8", str(path), "-"], 12, INVALID_PDF)
    extracted = text.split("\f")
    for offset, page in enumerate(range(first, last + 1)):
        content = extracted[offset].strip() if offset < len(extracted) else ""
        if not any(char.isalpha() for char in content):
            prefix = path.with_suffix(".ocr")
            image = Path(f"{prefix}.png")
            try:
                pdf_command(["pdftoppm", "-f", str(page), "-l", str(page), "-singlefile", "-scale-to", "2100",
                             "-gray", "-png", str(path), str(prefix)], 10, INVALID_PDF)
                content = pdf_command(["tesseract", str(image), "stdout", "-l", "ron+eng", "--psm", "3"],
                                      13, "Pagina scanată nu a putut fi citită. Încearcă o copie mai clară.").strip()
            finally:
                image.unlink(missing_ok=True)
        yield page, content[:PDF_MAX_TEXT + 1]


def cached_page_text(document: PdfDocument, first: int, last: int) -> str:
    if last > document.pages:
        raise AiError(f"Intervalul depășește numărul de pagini din PDF ({document.pages}).", 422)
    selected = []
    length = 0
    # Group adjacent cache misses so a single Poppler call reads each missing run.
    for cached, group in groupby(range(first, last + 1), key=document.cached.__contains__):
        pages = list(group)
        entries = ((page, document.cached[page]) for page in pages) if cached else read_pages(document.path, pages[0], pages[-1])
        for page, content in entries:
            document.cached[page] = content
            document.cached.move_to_end(page)
            if not content:
                continue
            block = f"Pagina PDF {page}\n{content}"
            length += len(block) + (2 if selected else 0)
            if length > PDF_MAX_TEXT:
                raise AiError("Paginile conțin prea mult text pentru o singură extragere. Alege un interval mai mic.", 422)
            selected.append(block)
    if not selected:
        raise AiError("Nu am găsit text lizibil în paginile alese.", 422)
    return "\n\n".join(selected)


def session_page_text(document: PdfDocument, first: int, last: int) -> str:
    try:
        return cached_page_text(document, first, last)
    finally:
        while len(document.cached) > PDF_PAGE_CACHE_LIMIT:
            document.cached.popitem(last=False)


def page_text(path: Path, first: int, last: int) -> str:
    return cached_page_text(PdfDocument(path, pdf_pages(path), 0), first, last)


async def drafts_from_pdf(provider, text: str) -> PdfRecipes:
    rules = (
        "Extrage separat până la patru rețete complete, în ordinea apariției. Include doar rețetele pentru care "
        "paginile conțin atât ingrediente, cât și instrucțiuni. Ignoră cuprinsul, reclama și fragmentele incomplete. "
        "Setează more_recipes dacă mai sunt rețete complete în text. Returnează o listă goală dacă nu găsești niciuna. "
        "Redă pașii concis. " + IMPORT_RULES
    )
    result = await provider.structured("pdf_recipe_import", rules, {"text": text}, schema_for(PdfRecipes))
    batch = PdfRecipes.model_validate(result)
    return batch.model_copy(update={"recipes": [recipe for recipe in batch.recipes if recipe.ingredients and recipe.steps]})
