"""Budgeted meal proposals. Prices are user data; unknown prices never count as free."""
import math
import re
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from uuid import UUID, uuid5
from zoneinfo import ZoneInfo

from pydantic import Field, model_validator

from .catalog import base_amount, candidates, canonical
from .models import Model, Name, RecipeRequest, Recipe, MealPlan, ShoppingItem, Mutation, Change


class PlanPrice(Model):
    name: Name
    quantity: float = Field(ge=0.0001, le=100000)
    unit: str = Field(pattern=r"^(buc|g|kg|ml|l)$")
    cents: int = Field(gt=0, le=100_000_000)


class WeekRequest(Model):
    id: UUID
    start: date
    days: int = Field(default=7, ge=1, le=7)
    slots: list[str] = Field(default_factory=lambda: ['dinner'], min_length=1, max_length=3)
    servings: int = Field(default=1, ge=1, le=8)
    budget_cents: int = Field(gt=0, le=100_000_000)
    max_minutes: int = Field(default=30, ge=5, le=180)
    equipment: list[Name] = Field(default_factory=lambda: ['plita', 'tigaie', 'oala'], min_length=1, max_length=8)
    diet: str = Field(default='any', pattern=r'^(any|vegetarian|vegan)$')
    preferences: str = Field(default='', max_length=500)
    use_ai: bool = True
    seed: str = Field(default='', max_length=100)
    prices: list[PlanPrice] = Field(default_factory=list, max_length=100)
    exclude: list[Name] = Field(default_factory=list, max_length=20)

    @model_validator(mode='after')
    def valid_slots(self):
        if len(set(self.slots)) != len(self.slots) or set(self.slots) - {'breakfast', 'lunch', 'dinner'}:
            raise ValueError('Alege mese diferite: mic dejun, prânz sau cină.')
        self.slots = sorted(self.slots, key=['breakfast', 'lunch', 'dinner'].index)
        today = datetime.now(ZoneInfo('Europe/Bucharest')).date()
        if not today <= self.start <= today + timedelta(days=365):
            raise ValueError('Planul trebuie să înceapă între astăzi și anul următor.')
        return self


def base(quantity, unit):
    return (quantity * 1000, 'g' if unit == 'kg' else 'ml') if unit in {'kg', 'l'} else (quantity, unit)


def amount(raw):
    match = re.fullmatch(r'\s*(\d+(?:[.,]\d+)?)\s*(buc|kg|g|ml|l)\s*', raw, re.I)
    if not match:
        raise ValueError('Cantitate neclară în rețetă. Corectează rețeta înainte de planificare.')
    return base(float(match[1].replace(',', '.')), match[2].lower())


def price_book(snapshot, request):
    quotes = {}
    records = sorted([*snapshot.receipts, *snapshot.purchases], key=lambda row: (row.spent_on, str(row.id)))
    for record in records:
        for line in record.lines:
            if not line.include_pantry or not line.line_cents:
                continue
            quantity, unit = base(line.quantity, line.unit)
            if quantity < 0.0001:
                continue
            quotes[(canonical(line.name), unit)] = (quantity, line.line_cents, f'{record.merchant} · {record.spent_on}')
    for price in request.prices:
        quantity, unit = base(price.quantity, price.unit)
        quotes[(canonical(price.name), unit)] = (quantity, price.cents, 'Estimat de tine')
    return quotes


def needs(snapshot, meals):
    remaining, stock = {}, defaultdict(list)
    for item in sorted(snapshot.pantry, key=lambda row: (row.expires_on or date.max, str(row.id))):
        quantity, unit = base(item.quantity, item.unit)
        remaining[item.id] = quantity
        stock[(canonical(item.name), unit)].append(item)
    result = {}
    for meal in sorted((row for row in meals if not row.completed and row.prepared_batch_id is None), key=lambda row: (row.on_date, row.slot, str(row.id))):
        for part in meal.recipe.ingredients:
            name = canonical(part.name)
            if name == 'apa':
                continue
            measured = base_amount(part.quantity)
            if measured is None:
                continue
            quantity, unit = measured
            quantity *= meal.servings / meal.recipe.servings
            for item in stock.get((name, unit), ()):
                if quantity <= 1e-6:
                    break
                if item.expires_on and item.expires_on < meal.on_date:
                    continue
                take = min(quantity, remaining[item.id])
                remaining[item.id] -= take
                quantity -= take
            if quantity > 1e-6:
                key = (name, unit)
                result[key] = (part.name, result.get(key, ('', 0))[1] + quantity)
    return result


def estimate(snapshot, meals, quotes):
    lines = []
    for (key, unit), (name, required) in needs(snapshot, meals).items():
        quote = quotes.get((key, unit))
        package, cents, source = quote or (100 if unit in {'g', 'ml'} else 1, None, 'Preț necunoscut')
        count = max(1, math.ceil(round(required / package, 8)))
        if cents is not None and count * cents > 2_000_000_000:
            raise ValueError('Estimarea este prea mare. Verifică prețul și cantitatea ambalajului introdus.')
        lines.append({'name': name, 'quantity': round(required, 4), 'unit': unit,
                      'buy_quantity': round(count * package, 4), 'package_quantity': package,
                      'package_cents': cents, 'estimated_cents': count * cents if cents is not None else None,
                      'source': source})
    if sum(row['estimated_cents'] or 0 for row in lines) > 2_000_000_000:
        raise ValueError('Estimarea este prea mare. Verifică prețurile și cantitățile ambalajelor.')
    return lines


def options(snapshot, request, slot):
    query = RecipeRequest(max_minutes=request.max_minutes, servings=request.servings, equipment=request.equipment,
                          diet=request.diet, category='breakfast' if slot == 'breakfast' else 'main')
    excluded = {canonical(name) for name in request.exclude}
    return [(entry, draft) for entry, draft in candidates(query, snapshot.pantry, snapshot.personal_recipes, require_stock_overlap=False, limit=None)
            if not entry.unmeasured and not excluded.intersection(canonical(part.name) for part in draft.ingredients)]


def ai_options(snapshot, request):
    # The AI ranks safe candidates; arithmetic and the final budget stay deterministic.
    quotes = price_book(snapshot, request)
    available = options(snapshot, request, request.slots[0])
    rows = []
    for entry, draft in available:
        recipe = Recipe(**draft.model_dump(), id=uuid5(request.id, entry.id), model='Plan automat', generated_at='', generation_ms=0)
        meal = MealPlan(id=uuid5(request.id, 'ai-' + entry.id), on_date=request.start, recipe=recipe, servings=request.servings)
        lines = estimate(snapshot, [meal], quotes)
        known = sum(row['estimated_cents'] or 0 for row in lines)
        unknown = sum(row['estimated_cents'] is None for row in lines)
        rows.append((unknown, known, entry.id, {'id': entry.id, 'title': draft.title, 'minutes': draft.minutes, 'known_cents': known, 'unknown_prices': unknown}))
    rows.sort(key=lambda row: (row[0], row[1]))
    return [row[3] for row in rows[:5]]


def prepared_signature(recipe):
    return recipe.title, frozenset(canonical(part.name) for part in recipe.ingredients), tuple(recipe.steps)


def preview(snapshot, request, seed='', ai_used=False, ai_note='', economical=False):
    quotes = price_book(snapshot, request)
    end = request.start + timedelta(days=request.days - 1)
    fixed = [row for row in snapshot.plans if request.start <= row.on_date <= end]
    meals = list(fixed)
    proposed = []
    counts = Counter(row.recipe.title for row in meals if not row.completed)
    reserved = Counter()
    for meal in snapshot.plans:
        if meal.prepared_batch_id and not meal.completed:
            reserved[str(meal.prepared_batch_id)] += meal.servings
    targets = [(request.start + timedelta(days=day), slot) for day in range(request.days) for slot in request.slots
               if not any(row.on_date == request.start + timedelta(days=day) and row.slot == slot for row in fixed)]
    eligible = {slot: options(snapshot, request, slot) for slot in dict.fromkeys(slot for _, slot in targets)}
    signatures = {slot: {prepared_signature(draft) for _, draft in choices} for slot, choices in eligible.items()}
    batches = [(row, prepared_signature(row.recipe)) for row in snapshot.prepared]
    dated_stock = [(item.expires_on, (canonical(item.name), base(item.quantity, item.unit)[1]))
                   for item in snapshot.pantry if item.expires_on is not None]
    warnings = []
    for day, slot in targets:
        prepared = sorted((row for row, signature in batches if signature in signatures[slot] and row.cooked_on <= day <= row.expires_on and
                           row.remaining - reserved[str(row.id)] >= request.servings), key=lambda row: (row.expires_on, str(row.id)))
        if prepared:
            batch = prepared[0]
            selected = MealPlan(id=uuid5(request.id, f'{day}-{slot}'), on_date=day, slot=slot, recipe=batch.recipe,
                                servings=request.servings, prepared_batch_id=batch.id)
            reserved[str(batch.id)] += request.servings
        else:
            trials = []
            for entry, draft in eligible[slot]:
                recipe = Recipe(**draft.model_dump(), id=uuid5(request.id, f'{day}-{slot}-{entry.id}'), model='Plan automat · catalog verificat',
                                generated_at=datetime.now(timezone.utc).isoformat(), generation_ms=0)
                meal = MealPlan(id=uuid5(request.id, f'{day}-{slot}'), on_date=day, slot=slot, recipe=recipe, servings=request.servings)
                lines = estimate(snapshot, [*meals, meal], quotes)
                known = sum(row['estimated_cents'] or 0 for row in lines)
                unknown = sum(row['estimated_cents'] is None for row in lines)
                parts = {(canonical(name), unit) for name, _, unit in entry.parts if canonical(name) != 'apa'}
                expiry_match = sum(1 for expiry, identity in dated_stock if day <= expiry <= day + timedelta(days=3) and identity in parts)
                score = (known > request.budget_cents, unknown, counts[draft.title], -expiry_match, 0 if entry.id == seed else 1, known, draft.minutes, entry.id)
                if economical:
                    score = (known > request.budget_cents, unknown, known, counts[draft.title], -expiry_match, 0 if entry.id == seed else 1, draft.minutes, entry.id)
                trials.append((score, meal))
            if not trials:
                warnings.append(f'{day} · {slot}: nu există o rețetă pentru timpul și echipamentele alese.')
                continue
            selected = min(trials, key=lambda row: row[0])[1]
        meals.append(selected)
        proposed.append(selected)
        counts[selected.recipe.title] += 1
    lines = estimate(snapshot, meals, quotes)
    unknown = sum(row['estimated_cents'] is None for row in lines)
    known = sum(row['estimated_cents'] or 0 for row in lines)
    if known > request.budget_cents and not economical:
        return preview(snapshot, request, seed, ai_used, ai_note, economical=True)
    manual_quantities = {part.name for meal in meals if not meal.completed and meal.prepared_batch_id is None
                         for part in meal.recipe.ingredients if canonical(part.name) != "apa" and base_amount(part.quantity) is None}
    total = None if unknown or manual_quantities else known
    if manual_quantities:
        warnings.append(f"Totalul include doar cantitățile măsurabile. Verifică separat: {', '.join(sorted(manual_quantities))}.")
    if unknown:
        warnings.append('Completează prețurile necunoscute și recalculează înainte de confirmare.')
    if known > request.budget_cents:
        warnings.append('Propunerea depășește bugetul. Ajustează suma, mesele sau prețurile și recalculează.')
    if ai_note:
        warnings.append(ai_note)
    changes = [Change(kind='plans', id=meal.id, after=meal.model_dump(mode='json')) for meal in proposed]
    for line in lines:
        compatible = []
        for row in snapshot.shopping:
            if row.checked or canonical(row.name) != canonical(line['name']):
                continue
            try:
                quantity, unit = amount(row.quantity)
            except ValueError:
                warnings.append(f'{row.name}: verifică și poziția existentă cu o cantitate neclară.')
                continue
            if unit == line['unit']:
                compatible.append((row, quantity))
        extra = line['buy_quantity'] - sum(quantity for _, quantity in compatible)
        if extra <= 1e-6:
            continue
        previous, quantity = compatible[0] if compatible else (None, 0)
        item = ShoppingItem(id=previous.id if previous else uuid5(request.id, 'shopping-' + canonical(line['name']) + line['unit']),
                            name=line['name'], quantity=f'{quantity + extra:g} {line["unit"]}')
        changes.append(Change(kind='shopping', id=item.id, before=previous.model_dump(mode='json') if previous else None, after=item.model_dump(mode='json')))
    ready = unknown == 0 and not manual_quantities and known <= request.budget_cents and len(proposed) == len(targets) and bool(changes)
    kinds = ['pantry', 'plans', 'shopping', 'prepared', 'products', 'personal_recipes', 'receipts', 'purchases']
    guards = {kind: [row.model_dump(mode='json') for row in getattr(snapshot, kind)] for kind in kinds}
    action = Mutation(label='Plan automat și cumpărături', changes=changes, guards=guards, epoch=snapshot.epoch) if ready else None
    return {'meals': proposed, 'lines': lines, 'total_cents': total, 'known_cents': known, 'budget_cents': request.budget_cents,
            'warnings': list(dict.fromkeys(warnings)), 'ai_used': ai_used, 'seed': seed, 'action': action,
            'existing_meals': len(fixed), 'unfilled': len(targets) - len(proposed)}
