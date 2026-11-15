from datetime import date
from typing import Annotated, Literal
from uuid import UUID
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator

Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


class PantryInput(Model):
    name: Name
    quantity: float = Field(gt=0, le=100000)
    unit: Literal["buc", "g", "kg", "ml", "l"] = "buc"
    location: Literal["frigider", "congelator", "camara"] = "frigider"
    expires_on: date | None = None


class PantryItem(PantryInput):
    id: UUID


class StockMinimum(Model):
    id: UUID
    name: Name
    quantity: float = Field(ge=0.0001, le=100000)
    unit: Literal["buc", "g", "kg", "ml", "l"]


class ShoppingInput(Model):
    name: Name
    quantity: str = Field(default="", max_length=80)
    checked: bool = False


class ShoppingItem(ShoppingInput):
    id: UUID


class RecipeRequest(Model):
    max_minutes: int = Field(default=30, ge=5, le=180)
    servings: int = Field(default=1, ge=1, le=8)
    equipment: list[Name] = Field(default_factory=lambda: ["plita", "tigaie"], min_length=1, max_length=8)
    preferences: str = Field(default="", max_length=500)
    pantry_only: bool = False
    diet: Literal["any", "vegetarian", "vegan"] = "any"
    category: Literal["any", "breakfast", "main", "snack"] = "any"
    required_ingredients: list[Name] = Field(default_factory=list, max_length=8)
    excluded_ingredients: list[Name] = Field(default_factory=list, max_length=8)


class Ingredient(Model):
    name: Name
    quantity: Name


class RecipeDraft(Model):
    title: Name
    minutes: int = Field(ge=1, le=180)
    servings: int = Field(ge=1, le=8)
    ingredients: list[Ingredient] = Field(min_length=1, max_length=20)
    steps: list[Text] = Field(min_length=1, max_length=12)
    missing_ingredients: list[Ingredient] = Field(default_factory=list, max_length=20)

    @field_validator("steps")
    @classmethod
    def distinct_steps(cls, values):
        normalized = [" ".join(v.casefold().split()) for v in values]
        if len(normalized) != len(set(normalized)):
            raise ValueError("Pașii identici repetați nu constituie o rețetă validă.")
        return values


class ExpiringIngredient(Model):
    name: Name
    expires_on: date


class Recipe(RecipeDraft):
    id: UUID
    model: str
    generated_at: str
    generation_ms: int
    selection_cached: bool = False
    request_key: str = ""
    expiring_ingredients: list[ExpiringIngredient] = Field(default_factory=list, max_length=20, exclude_if=lambda items: not items)


class UseSoonIdea(Model):
    recipe: Recipe
    foods: list[Name]


class PersonalRecipe(Model):
    id: UUID
    title: Name
    minutes: int = Field(ge=1, le=180)
    servings: int = Field(ge=1, le=8)
    ingredients: list[Ingredient] = Field(min_length=1, max_length=20)
    steps: list[Text] = Field(min_length=1, max_length=12)
    equipment: list[Name] = Field(default_factory=list, max_length=8)
    diet: Literal["any", "vegetarian", "vegan"] = "any"
    category: Literal["breakfast", "main", "snack"] = "main"
    updated_at: str
    minutes_estimated: bool = Field(default=False, exclude_if=lambda estimated: not estimated)

    @field_validator("steps")
    @classmethod
    def unique_steps(cls, value):
        if len({item.casefold() for item in value}) != len(value):
            raise ValueError("Pașii trebuie să fie diferiți.")
        return value

    @field_validator("ingredients")
    @classmethod
    def valid_parts(cls, value):
        from .catalog import INGREDIENT_AMOUNT, canonical
        if len({canonical(item.name) for item in value}) != len(value):
            raise ValueError("Unește ingredientele cu același nume.")
        for item in value:
            match = INGREDIENT_AMOUNT.fullmatch(item.quantity)
            if match and not 0 < float(match[1].replace(',', '.')) <= 100000:
                raise ValueError("Cantitățile trebuie să fie pozitive, în buc/g/kg/ml/l.")
        return value


class Product(Model):
    id: UUID
    name: Name
    barcode: str = Field(default="", pattern=r"^(?:\d{8}|\d{12,14})?$")
    package_quantity: float = Field(gt=0, le=100000)
    unit: Literal["buc", "g", "kg", "ml", "l"] = "buc"
    location: Literal["frigider", "congelator", "camara"] = "frigider"
    receipt_names: list[Name] = Field(default_factory=list, max_length=20)


class ReceiptLine(Model):
    name: Name
    quantity: float = Field(default=1, gt=0, le=100000)
    unit: Literal["buc", "g", "kg", "ml", "l"] = "buc"
    location: Literal["frigider", "congelator", "camara"] = "frigider"
    line_cents: int | None = Field(default=None, ge=0, le=100_000_000)
    include_pantry: bool = True
    warnings: list[str] = Field(default_factory=list)


class ReceiptPreview(Model):
    image_sha256: str
    merchant: str
    spent_on: date | None = None
    total_cents: int | None = None
    lines: list[ReceiptLine] = Field(default_factory=list)
    raw_text: str
    warnings: list[str] = Field(default_factory=list)


class ReceiptRecord(Model):
    id: UUID
    image_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    merchant: Name
    spent_on: date
    total_cents: int = Field(gt=0, le=100_000_000)
    expense_id: UUID
    imported_at: str
    lines: list[ReceiptLine] = Field(default_factory=list, max_length=80)


class BulkTextRequest(Model):
    text: str = Field(min_length=1, max_length=3000)


class BulkPreviewRow(Model):
    item: PantryInput
    warnings: list[str] = Field(default_factory=list)


class BulkPreview(Model):
    rows: list[BulkPreviewRow]


class PantryBatch(Model):
    items: list[PantryItem] = Field(min_length=1, max_length=40)

    @field_validator("items")
    @classmethod
    def unique_ids(cls, values):
        if len({item.id for item in values}) != len(values):
            raise ValueError("Identificatorii alimentelor trebuie să fie unici.")
        return values


class BatchResult(Model):
    count: int
    replayed: bool = False


class RecipeOption(Model):
    id: str
    title: str
    missing_count: int


class DeviceOptions(Model):
    fingerprint: str
    prompt: str
    options: list[RecipeOption]
    allow_none: bool = False


class DeviceChoice(Model):
    request: RecipeRequest
    fingerprint: str = Field(min_length=64, max_length=64)
    recipe_id: Name
    id: UUID
    generation_ms: int = Field(ge=0, le=240000)
    selection_cached: bool = False


class DeviceModelInfo(Model):
    available: bool
    name: str
    bytes: int
    sha256: str
    path: str


class MealPlan(Model):
    id: UUID
    on_date: date
    slot: Literal["breakfast", "lunch", "dinner"] = "dinner"
    recipe: Recipe
    servings: int = Field(ge=1, le=8)
    completed: bool = False
    prepared_batch_id: UUID | None = None


class PreparedBatch(Model):
    id: UUID
    recipe: Recipe
    cooked_on: date
    expires_on: date
    location: Literal["frigider", "congelator"] = "frigider"
    total: int = Field(ge=1, le=8)
    remaining: int = Field(ge=0, le=8)

    @model_validator(mode="after")
    def valid_batch(self):
        if self.remaining > self.total or self.expires_on < self.cooked_on:
            raise ValueError("Verifică porțiile și termenul introdus pentru mâncarea gătită.")
        return self


class PurchaseLine(ReceiptLine):
    shopping_id: UUID | None = None
    pantry_id: UUID | None = None
    expires_on: date | None = None
    line_cents: int = Field(gt=0, le=100_000_000)


class Purchase(Model):
    id: UUID
    merchant: Name
    spent_on: date
    total_cents: int = Field(gt=0, le=100_000_000)
    expense_id: UUID
    lines: list[PurchaseLine] = Field(min_length=1, max_length=60)

    @model_validator(mode="after")
    def total_matches(self):
        if sum(row.line_cents for row in self.lines) != self.total_cents:
            raise ValueError("Totalul trebuie să corespundă produselor confirmate.")
        return self


class Expense(Model):
    id: UUID
    label: Name
    amount_cents: int = Field(gt=0, le=100_000_000)
    spent_on: date
    category: Name = "Alimente"


class Budget(Model):
    id: UUID
    month: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")
    limit_cents: int = Field(ge=0, le=100_000_000)


class StockUse(Model):
    item: PantryItem
    quantity: float = Field(gt=0, le=100000)


class FoodEvent(Model):
    id: UUID
    kind: Literal["cooked", "used", "wasted", "prepared", "served", "portion_wasted"]
    title: Name
    occurred_on: date
    allocations: list[StockUse] = Field(default_factory=list, max_length=40)
    value_cents: int | None = Field(default=None, ge=0, le=100_000_000)
    reason: str = Field(default="", max_length=200)
    undone: bool = False
    plan_id: UUID | None = None
    batch_id: UUID | None = None
    portions: int | None = Field(default=None, ge=1, le=8)

    @model_validator(mode="after")
    def valid_consumption(self):
        if self.kind in {"cooked", "used", "wasted"} and not self.allocations:
            raise ValueError("Consum fără cantități.")
        if self.kind == "used" and (len(self.allocations) != 1 or self.plan_id is not None or self.batch_id is not None or self.portions is not None):
            raise ValueError("Consumul rapid se înregistrează pentru un singur aliment.")
        if self.kind in {"prepared", "served", "portion_wasted"} and (self.batch_id is None or self.portions is None):
            raise ValueError("Alege lotul și porțiile.")
        if self.kind in {"served", "portion_wasted"} and self.allocations:
            raise ValueError("Ingredientele sunt deja scăzute la preparare.")
        return self


RecordKind = Literal["pantry", "shopping", "plans", "expenses", "events", "budgets", "products", "personal_recipes", "receipts", "prepared", "purchases", "stock_minimums"]


class Change(Model):
    kind: RecordKind
    id: UUID
    before: dict[str, Any] | None = None
    after: dict[str, Any] | None = None


class Mutation(Model):
    label: Name
    changes: list[Change] = Field(min_length=1, max_length=200)
    guards: dict[RecordKind, list[dict[str, Any]]] = Field(default_factory=dict)
    epoch: str = ""


class MutationResult(Model):
    replayed: bool = False


class Snapshot(Model):
    pantry: list[PantryItem]
    shopping: list[ShoppingItem]
    recipes: list[Recipe]
    plans: list[MealPlan] = Field(default_factory=list)
    expenses: list[Expense] = Field(default_factory=list)
    events: list[FoodEvent] = Field(default_factory=list)
    budgets: list[Budget] = Field(default_factory=list)
    aliases: dict[str, str] = Field(default_factory=dict)
    products: list[Product] = Field(default_factory=list)
    personal_recipes: list[PersonalRecipe] = Field(default_factory=list)
    receipts: list[ReceiptRecord] = Field(default_factory=list)
    prepared: list[PreparedBatch] = Field(default_factory=list)
    purchases: list[Purchase] = Field(default_factory=list)
    stock_minimums: list[StockMinimum] = Field(default_factory=list)
    stock_tools: bool = True
    epoch: str = ""
    restored_at: str | None = None


class AiStatus(Model):
    available: bool
    model: str
    detail: str
