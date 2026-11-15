import re
from functools import lru_cache


class PreferenceConfirmation(ValueError):
    """The ingredient clauses must be expressed as explicit filters."""


FOOD_GROUPS = {
    "soia": {"soia", "tofu", "tempeh", "edamame", "miso"},
    "ciuperci": {"ciuperci", "ciuperca", "champignon", "pleurotus", "hribi", "shiitake", "galbiori"},
    "lapte": {"lapte"},
    "lactate": {"lapte", "branza", "cascaval", "mozzarella", "parmezan", "iaurt", "unt", "smantana", "kefir",
                "urda", "telemea", "feta", "ricotta"},
    "oua": {"ou", "oua", "ouale", "maioneza"},
    "nuci": {"nuci", "nuca"},
    "arahide": {"arahide", "alune de pamant"},
}
ANIMAL_FOODS = {"carne", "pui", "vita", "porc", "curcan", "sunca", "bacon", "salam", "peste", "ton",
                "sardine", "somon", "macrou", "cod", "hering", "creveti", "fructe de mare", "miere"}

MARKER = re.compile(r"\b(?P<exclude>fara|nu vreau|nu pot manca|nu mananc|exclude|evita)\b(?:\s+|$)"
                    r"|\b(?:neaparat|obligatoriu|trebuie sa fie)\s+cu\s+")
FASTING = re.compile(r"\b(?:tin post|de post|postesc)\b")
AMBIGUOUS = re.compile(r"\b(?:alerg\w*|intoleran\w*|nu suport|evit\w*|scoti|elimin\w*|nu pune|neaparat|obligatoriu)\b")
END = re.compile(r"[.!?;\n]|,?\s+(?:dar\b|prefer\b|daca\b|si vreau\b|si sa\b|si in\b|ideal\b)")
SPLIT = re.compile(r"\s*(?:,|\bsi\b)\s*")
CONFIRM_FILTERS = "Confirmă ingredientele în «Filtre și echipamente», apoi reformulează cerința din «Ce ți-ar plăcea?»."


def contains_phrase(text: str, phrase: str) -> bool:
    return phrase_pattern(phrase).search(text) is not None


@lru_cache(maxsize=1024)
def phrase_pattern(phrase):
    return re.compile(rf"(?<!\w){re.escape(phrase)}(?!\w)")


def matches_ingredient(term: str, part: str) -> bool:
    return any(contains_phrase(part, name) for name in FOOD_GROUPS.get(term, {term}))


def ingredient_preferences(request, names, normalize, canonical):
    required = {canonical(name) for name in request.required_ingredients}
    excluded = {canonical(name) for name in request.excluded_ingredients}
    text = "\n".join(normalize(line) for line in request.preferences.splitlines())
    if re.search(r"\b(?:nu vreau|nu trebuie)\s+fara cumparaturi\b", text):
        raise PreferenceConfirmation(CONFIRM_FILTERS)
    pantry_only = request.pantry_only or bool(re.search(r"\bfara cumparaturi\b", text))
    text = re.sub(r"\bfara cumparaturi\b", " ", text)
    if AMBIGUOUS.search(MARKER.sub(" ", text)) or any(contains_phrase(text, phrase) for phrase in ("de obicei fara", "as prefera fara", "daca se poate fara")):
        raise PreferenceConfirmation(CONFIRM_FILTERS)

    known = {canonical(name) for name in names} | set(FOOD_GROUPS)
    known.update(ANIMAL_FOODS)
    known.update({"linte", "naut", "fasole", "ulei", "sare", "usturoi", "piper", "mazare", "spanac"})
    markers = list(MARKER.finditer(text))
    for index, marker in enumerate(markers):
        limit = markers[index + 1].start() if index + 1 < len(markers) else len(text)
        clause = END.split(text[marker.end():limit], maxsplit=1)[0].strip(" ,")
        clause = re.sub(r"\s+si$", "", clause)
        terms = {canonical(part) for part in SPLIT.split(clause)}
        if not terms <= known:
            raise PreferenceConfirmation(CONFIRM_FILTERS)
        (excluded if marker["exclude"] else required).update(terms)

    fasting = bool(FASTING.search(text))
    if fasting and re.search(r"\b(?:nu tin post|nu postesc|nu vreau de post|nu trebuie sa fie de post)\b", text):
        raise PreferenceConfirmation(CONFIRM_FILTERS)
    if required.intersection(excluded) or any(matches_ingredient(term, other) for term in excluded for other in required):
        raise PreferenceConfirmation("Același ingredient apare la «Obligatorii» și «De evitat». Verifică filtrele.")
    return required, excluded, fasting, pantry_only
