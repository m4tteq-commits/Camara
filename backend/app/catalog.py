"""Authored starter recipes; the local AI selects, never invents preparation steps."""
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from math import isclose
import re
import unicodedata
from uuid import NAMESPACE_URL, uuid5
from zoneinfo import ZoneInfo

from .models import ExpiringIngredient, Ingredient, PantryItem, Recipe, RecipeDraft, RecipeRequest
from .recipe_preferences import ANIMAL_FOODS, contains_phrase, ingredient_preferences, matches_ingredient


def normalize(value):
    return " ".join("".join(c for c in unicodedata.normalize("NFD", value.casefold()) if not unicodedata.combining(c)).split())


ALIASES = {"ou": "oua", "oua de gaina": "oua", "rosie": "rosii", "tomate": "rosii", "telemea": "branza", "branza telemea": "branza",
           "fulgi de ovaz": "ovaz", "iaurt natural": "iaurt", "banana": "banane", "ulei de masline": "ulei",
           "ulei de floarea soarelui": "ulei", "cartof": "cartofi", "cepe": "ceapa", "castraveti": "castravete",
           "naut la conserva": "naut fiert", "conserva de ton": "ton la conserva", "sos de rosii": "sos rosii", "passata": "sos rosii"}
ALIASES.update({"ouale": "oua", "morcovi": "morcov", "morcovii": "morcov", "mar": "mere", "mere rosii": "mere",
                "bananele": "banane", "cartofii": "cartofi", "ceapa galbena": "ceapa", "ceapa rosie": "ceapa",
                "lapte uht": "lapte", "iaurt grecesc": "iaurt", "iaurt simplu": "iaurt", "fulgi ovaz": "ovaz",
                "spaghete": "paste", "penne": "paste", "fusilli": "paste", "macaroane": "paste",
                "fasole la conserva": "fasole fiarta", "fasole conservata": "fasole fiarta",
                "naut conservat": "naut fiert", "conserva ton": "ton la conserva", "ton conserva": "ton la conserva",
                "porumb la conserva": "porumb", "castravete verde": "castravete", "dovlecei": "dovlecel",
                "ciuperca": "ciuperci", "ardei gras": "ardei", "ardei rosu": "ardei", "sunca presata": "sunca",
                "tortilla": "lipie", "lipii": "lipie", "couscous": "cuscus", "cous cous": "cuscus",
                "hummus": "humus", "orez deja fiert": "orez fiert", "linte rosie uscata": "linte rosie"})


def canonical(value):
    key = normalize(value)
    return ALIASES.get(key, key)


@dataclass(frozen=True)
class Entry:
    id: str
    title: str
    minutes: int
    extra_minutes: int
    equipment: tuple[str, ...]
    parts: tuple[tuple[str, float, str], ...]
    steps: tuple[str, ...]
    categories: tuple[str, ...] = ("main",)
    unmeasured: tuple[tuple[int, Ingredient], ...] = ()


CATALOG = (
    Entry("omleta", "Omletă cu brânză și roșii", 15, 3, ("plita", "tigaie"),
          (("Ouă", 2, "buc"), ("Brânză", 50, "g"), ("Roșii", 1, "buc"), ("Ulei", 5, "ml")),
          ("Spală roșia și taie-o în bucăți. Fărâmițează brânza și bate ouăle într-un bol.",
           "Încălzește uleiul în tigaie la foc mediu și gătește roșiile 2–3 minute.",
           "Toarnă ouăle și adaugă brânza. Redu focul și gătește până când ouăle sunt complet închegate. Pentru mai multe porții, lucrează în tranșe.")),
    Entry("paste-rosii", "Paste cu sos de roșii", 20, 2, ("plita", "oala"),
          (("Paste", 100, "g"), ("Sos de roșii", 150, "g"), ("Ulei", 5, "ml"), ("Apă", 1000, "ml")),
          ("Fierbe apa într-o oală și adaugă pastele. Respectă timpul indicat pe ambalaj.",
           "Păstrează puțină apă de la paste, apoi scurge restul.",
           "În oala eliberată, încălzește sosul de roșii și uleiul la foc mic, 3–4 minute.",
           "Adaugă pastele și amestecă. Dacă sosul este prea gros, adaugă puțină apă păstrată.")),
    Entry("salata-branza", "Salată de roșii cu brânză", 5, 1, (),
          (("Roșii", 2, "buc"), ("Brânză", 80, "g"), ("Castravete", 1, "buc"), ("Ulei", 5, "ml")),
          ("Spală roșiile și castravetele, apoi taie-le în bucăți.", "Fărâmițează brânza deasupra legumelor.", "Adaugă uleiul și amestecă ușor. Servește imediat.")),
    Entry("sandvis", "Sandviș cu brânză și roșii", 5, 2, (),
          (("Pâine", 2, "buc"), ("Brânză", 60, "g"), ("Roșii", 1, "buc")),
          ("Spală roșia și taie-o felii. Taie sau fărâmițează brânza.", "Așază brânza și roșia între feliile de pâine și servește imediat.")),
    Entry("iaurt-ovaz", "Iaurt cu ovăz și banană", 5, 1, (),
          (("Iaurt", 150, "g"), ("Ovăz", 50, "g"), ("Banane", 1, "buc")),
          ("Amestecă iaurtul și fulgii de ovăz într-un bol.", "Curăță banana, taie-o felii și adaug-o în bol. Servește imediat.")),
    Entry("terci", "Terci de ovăz cu banană", 10, 2, ("plita", "oala"),
          (("Ovăz", 50, "g"), ("Lapte", 200, "ml"), ("Banane", 1, "buc")),
          ("Pune laptele și fulgii de ovăz într-o oală. Încălzește la foc mic, amestecând.",
           "Gătește până când fulgii sunt moi, respectând timpul de pe ambalaj. Adaugă puțină apă dacă se îngroașă prea mult.",
           "Oprește focul și adaugă banana curățată și tăiată felii.")),
    Entry("cartofi-tigaie", "Cartofi cu ceapă la tigaie", 30, 5, ("plita", "tigaie"),
          (("Cartofi", 300, "g"), ("Ceapă", 1, "buc"), ("Ulei", 10, "ml"), ("Apă", 100, "ml")),
          ("Spală și curăță cartofii, apoi taie-i în cuburi mici. Curăță și taie ceapa.",
           "Încălzește uleiul în tigaie și gătește ceapa 2–3 minute la foc mediu.",
           "Adaugă cartofii și apa. Acoperă cu un capac și gătește la foc mediu-mic până când cartofii sunt moi. Amestecă periodic și adaugă apă dacă se usucă înainte de a fi gata.",
           "Îndepărtează capacul și lasă apa rămasă să se evapore, amestecând, apoi rumenește ușor cartofii.")),
    Entry("salata-naut", "Salată de năut fiert", 10, 2, (),
          (("Năut fiert", 200, "g"), ("Roșii", 1, "buc"), ("Castravete", 1, "buc"), ("Ulei", 5, "ml")),
          ("Folosește năut deja fiert, nu boabe crude. Dacă este din conservă, scurge-l și clătește-l.",
           "Spală roșia și castravetele, apoi taie-le în bucăți.", "Amestecă năutul cu legumele și uleiul într-un bol. Servește imediat.")),
    Entry("oua-fierte", "Ouă fierte cu pâine", 15, 1, ("plita", "oala"),
          (("Ouă", 2, "buc"), ("Pâine", 2, "buc"), ("Apă", 500, "ml")),
          ("Așază ouăle într-o oală și acoperă-le cu apă rece. Completează apa dacă este nevoie.",
           "Adu apa la fierbere, apoi redu focul și fierbe ouăle 10–12 minute pentru un gălbenuș închegat.", "Răcește ouăle sub apă rece, curăță-le și servește-le cu pâine.")),
    Entry("paste-ton", "Paste cu ton și roșii", 20, 2, ("plita", "oala"),
          (("Paste", 100, "g"), ("Ton la conservă", 1, "buc"), ("Roșii", 1, "buc"), ("Ulei", 5, "ml"), ("Apă", 1000, "ml")),
          ("Fierbe pastele conform ambalajului, apoi scurge-le și păstrează puțină apă de la fierbere.",
           "Spală roșia și taie-o mărunt. Deschide și scurge conserva de ton.", "În oala eliberată, încălzește uleiul și roșia la foc mic 3–4 minute. Adaugă tonul și încălzește-l.",
           "Amestecă pastele cu sosul. Adaugă puțină apă păstrată dacă este nevoie.")),
    Entry("cartof-microunde", "Cartof la microunde cu iaurt și brânză", 15, 7, ("microunde",),
          (("Cartofi", 1, "buc"), ("Iaurt", 60, "g"), ("Brânză", 30, "g")),
          ("Spală cartoful și înțeapă coaja în mai multe locuri cu o furculiță.",
           "Așază-l într-un vas potrivit pentru microunde, fără metal. Gătește 5 minute, întoarce-l cu grijă și continuă în reprize scurte până când furculița intră ușor în centru. Durata depinde de mărime și puterea aparatului.",
           "Lasă-l 2 minute să se răcorească. Deschide-l cu grijă, deoarece eliberează abur, și adaugă iaurtul și brânza.")),
)

from .catalog_extra import EXTRA_RECIPES
CATALOG += tuple(Entry(*row) for row in EXTRA_RECIPES)
ANIMAL = {"ton la conserva", "sunca", "sardine la conserva", "pui", "carne"}
DAIRY_EGGS = {"oua", "branza", "cascaval", "mozzarella", "iaurt", "lapte", "unt", "pesto"}
BREAKFAST = {"omleta", "iaurt-ovaz", "terci", "oua-fierte", "sandvis"}
BASE_UNITS = {"kg": ("g", 1000), "l": ("ml", 1000)}
CATALOG_INGREDIENTS = {part[0] for entry in CATALOG for part in entry.parts}
MEAT_AND_FISH = ANIMAL_FOODS - {"miere"}
INGREDIENT_AMOUNT = re.compile(r"([0-9]+(?:[.,][0-9]+)?)\s*(buc|kg|g|ml|l)", re.I)


def base_amount(quantity: str) -> tuple[float, str] | None:
    match = INGREDIENT_AMOUNT.fullmatch(quantity)
    if match is None:
        return None
    unit = match[2].lower()
    base, factor = BASE_UNITS.get(unit, (unit, 1))
    return float(match[1].replace(",", ".")) * factor, base


def inventory(pantry):
    stock, names = {}, set()
    for item in pantry:
        unit, multiplier = BASE_UNITS.get(item.unit, (item.unit, 1))
        key = canonical(item.name)
        names.add(key)
        stock[(key, unit)] = stock.get((key, unit), 0) + item.quantity * multiplier
    return stock, names


def prepare(entry: Entry, request: RecipeRequest, pantry: list[PantryItem]) -> RecipeDraft:
    return prepare_with_stock(entry, request, *inventory(pantry))


def prepare_with_stock(entry, request, stock, names):
    ingredients, missing = [], []
    remaining = {}
    for name, amount, unit in entry.parts:
        required = amount * request.servings
        key = canonical(name)
        ingredients.append(Ingredient(name=name, quantity=f"{Decimal(f'{required:g}'):f} {unit}"))
        if key == "apa":  # Potable kitchen water is assumed available and listed explicitly.
            continue
        stock_key = (key, unit)
        available = remaining.get(stock_key, stock.get(stock_key, 0))
        remaining[stock_key] = max(0, available - required)
        if available < required and not isclose(available, required, rel_tol=1e-9):
            shortage = Decimal(f'{required - available:.6g}')
            quantity = f"{shortage:f} {unit}"
            if key in names and stock_key not in stock:
                quantity += " (verifică unitatea din inventar)"
            missing.append(Ingredient(name=name, quantity=quantity))
    for position, item in entry.unmeasured:
        ingredients.insert(position, item)
        if canonical(item.name) != "apa":
            missing.append(item)
    return RecipeDraft(title=entry.title, minutes=entry.minutes + entry.extra_minutes * (request.servings - 1),
                       servings=request.servings, ingredients=ingredients, steps=list(entry.steps), missing_ingredients=missing)


def candidates(request: RecipeRequest, pantry: list[PantryItem], personal_recipes=(), *, require_stock_overlap=True, limit=5) -> list[tuple[Entry, RecipeDraft]]:
    equipment = {normalize(value) for value in request.equipment}
    stock, names = inventory(pantry)
    choices = []
    terms = CATALOG_INGREDIENTS | {part.name for recipe in personal_recipes for part in recipe.ingredients}
    required, excluded, fasting, pantry_only = ingredient_preferences(request, terms, normalize, canonical)
    diet = "vegan" if fasting else request.diet
    personal_entries = []
    for recipe in personal_recipes:
        if diet == 'vegan' and recipe.diet != 'vegan':
            continue
        if diet == 'vegetarian' and recipe.diet not in {'vegetarian', 'vegan'}:
            continue
        parts = []
        unmeasured = []
        for position, part in enumerate(recipe.ingredients):
            measured = base_amount(part.quantity)
            if measured is None:
                unmeasured.append((position, part))
            else:
                amount, unit = measured
                parts.append((part.name, amount / recipe.servings, unit))
        personal_entries.append(Entry(f"personal-{recipe.id}", recipe.title, recipe.minutes, 0,
                                      tuple(normalize(value) for value in recipe.equipment), tuple(parts), tuple(recipe.steps),
                                      (recipe.category,), tuple(unmeasured)))
    for entry in (*personal_entries, *CATALOG):
        if not set(entry.equipment).issubset(equipment) or entry.minutes + entry.extra_minutes * (request.servings - 1) > request.max_minutes:
            continue
        parts = {canonical(part[0]) for part in entry.parts} | {canonical(part.name) for _, part in entry.unmeasured}
        primary = parts - {"apa", "ulei"}
        categories = set(entry.categories) | ({"breakfast"} if entry.id in BREAKFAST else set())
        if request.category != "any" and request.category not in categories:
            continue
        if diet in {"vegetarian", "vegan"} and (primary.intersection(ANIMAL) or any(
                contains_phrase(part, animal) for part in parts for animal in MEAT_AND_FISH)):
            continue
        if diet == "vegan" and (primary.intersection(DAIRY_EGGS) or any(
                matches_ingredient(group, part) for part in parts for group in ("lactate", "oua", "miere"))):
            continue
        if not all(any(matches_ingredient(term, part) for part in parts) for term in required):
            continue
        if any(matches_ingredient(term, part) for term in excluded for part in parts):
            continue
        if require_stock_overlap and not primary.intersection(names):
            continue
        recipe = prepare_with_stock(entry, request, stock, names)
        if pantry_only and recipe.missing_ingredients:
            continue
        choices.append((len(primary.intersection(names)) / max(1, len(primary)), len(recipe.missing_ingredients), entry, recipe))
    choices.sort(key=lambda row: (-row[0], row[1], row[3].minutes))
    return [(row[2], row[3]) for row in (choices if limit is None else choices[:limit])]


def recipe_day() -> date:
    return datetime.now(ZoneInfo("Europe/Bucharest")).date()


def unexpired_stock(pantry: list[PantryItem], today: date) -> list[PantryItem]:
    return [item for item in pantry if item.expires_on is None or item.expires_on >= today]


def expiring_stock(pantry: list[PantryItem], today: date) -> dict[tuple[str, str], date]:
    until = today + timedelta(days=3)
    due = {}
    for item in pantry:
        if item.expires_on is not None and today <= item.expires_on <= until:
            unit = BASE_UNITS.get(item.unit, (item.unit, 1))[0]
            key = canonical(item.name), unit
            due[key] = min(due.get(key, item.expires_on), item.expires_on)
    return due


def expiring_ingredients(draft: RecipeDraft, due: dict[tuple[str, str], date]) -> list[ExpiringIngredient]:
    matched = {}
    for part in draft.ingredients:
        measured = base_amount(part.quantity)
        if measured is None:
            continue
        amount, unit = measured
        key = canonical(part.name), unit
        if key[0] != "apa" and key in due and amount > 0:
            matched.setdefault(key[0], ExpiringIngredient(name=part.name, expires_on=due[key]))
    return sorted(matched.values(), key=lambda item: (item.expires_on, item.name))


def quick_suggestions(request: RecipeRequest, pantry: list[PantryItem], personal_recipes=()) -> list[Recipe]:
    today = recipe_day()
    due = expiring_stock(pantry, today)
    options = candidates(request, unexpired_stock(pantry, today), personal_recipes, limit=None)
    options.sort(key=lambda row: (len(row[1].missing_ingredients), row[1].minutes, row[0].id))
    return [
        Recipe(**draft.model_dump(), id=uuid5(NAMESPACE_URL, f"camara:suggestion:{entry.id}:{request.servings}"),
               model="Catalog Cămara", generated_at=today.isoformat(), generation_ms=0,
               expiring_ingredients=expiring_ingredients(draft, due))
        for entry, draft in options[:5]
    ]
