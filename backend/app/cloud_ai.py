import asyncio
import json
import os
import time
from datetime import datetime, timezone
from typing import Literal
from uuid import uuid4

import httpx

from .ai import AiError, RECIPE_SELECTION_RULES, personal_recipe_signals, recipe_option, selection_context, prompt_key
from .request_limits import secret_setting
from .catalog import expiring_ingredients, expiring_stock, recipe_day
from .database import Database
from .models import Recipe, RecipeRequest, Snapshot

CloudProviderId = Literal["openrouter", "groq"]
PROVIDERS = {
    "openrouter": ("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "OPENROUTER"),
    "groq": ("GroqCloud", "https://api.groq.com/openai/v1/chat/completions", "GROQ"),
}


class CloudRecipeProvider:
    def __init__(self, provider: CloudProviderId, client: httpx.AsyncClient, db: Database):
        self.id = provider
        self.label, self.url, prefix = PROVIDERS[provider]
        self.key = secret_setting(f"{prefix}_API_KEY")
        self.model = os.environ.get(f"{prefix}_MODEL", "").strip()
        self.client = client
        self.db = db
        self.lock = asyncio.Lock()
        self.selection_lock = asyncio.Lock()

    @property
    def configured(self) -> bool:
        return bool(self.key and self.model)

    async def generate(self, request: RecipeRequest, snapshot: Snapshot) -> Recipe:
        if not self.configured:
            raise AiError(f"{self.label} trebuie configurat pe ZimaOS.")
        start = time.perf_counter()
        context, choices = await asyncio.to_thread(selection_context, request, snapshot.pantry, snapshot.personal_recipes)
        today = recipe_day()
        due = expiring_stock(snapshot.pantry, today)
        identifiers = {str(number): recipe_id for number, recipe_id in enumerate(choices, 1)}
        personal = {f"personal-{item.id}": personal_recipe_signals(item) for item in snapshot.personal_recipes}
        options = [recipe_option(number, choices[recipe_id], due, today) | personal.get(recipe_id, {})
                   for number, recipe_id in identifiers.items()]
        if context.allow_none:
            identifiers["none"] = "none"
        key = prompt_key(f"{self.id}|{self.model}", RECIPE_SELECTION_RULES,
                         {"options": options, "preferences": request.preferences, "allowed_ids": list(identifiers)})
        selected = await asyncio.to_thread(self.db.cached_choice, key)
        cached = selected in identifiers
        if cached:
            choice = identifiers[selected]
            return self.selected_recipe(choice, choices, due, key, start, cached=True)
        try:
            await asyncio.wait_for(self.selection_lock.acquire(), timeout=0.1)
        except TimeoutError as exc:
            raise AiError(f"{self.label} pregătește deja o recomandare.", 429, retryable=False) from exc
        try:
            selected = await asyncio.to_thread(self.db.cached_choice, key)
            cached = selected in identifiers
            if not cached:
                selected = await self.select(options, request.preferences, list(identifiers))
                await asyncio.to_thread(self.db.cache_choice, key, selected)
            choice = identifiers[selected]
        finally:
            self.selection_lock.release()
        return self.selected_recipe(choice, choices, due, key, start, cached)

    def selected_recipe(self, choice, choices, due, key, start, cached):
        if choice == "none":
            raise AiError("Nicio rețetă nu se potrivește preferinței. Încearcă alte opțiuni.", 422)
        draft = choices[choice]
        return Recipe(**draft.model_dump(), id=uuid4(), model=f"{self.label} · {self.model}",
                      expiring_ingredients=expiring_ingredients(draft, due),
                      generated_at=datetime.now(timezone.utc).isoformat(), selection_cached=cached,
                      generation_ms=int((time.perf_counter() - start) * 1000), request_key=key)

    async def select(self, options, preferences, allowed):
        result = await self.structured("recipe_choice", RECIPE_SELECTION_RULES,
                                       {"options": options, "preferences": preferences, "allowed_ids": allowed},
                                       {"type": "object", "properties": {"recipe_id": {"type": "string", "enum": allowed}},
                                        "required": ["recipe_id"], "additionalProperties": False})
        if not isinstance(result, dict) or set(result) != {"recipe_id"} or result["recipe_id"] not in allowed:
            raise AiError(f"{self.label} a returnat o alegere invalidă.", 502, retryable=False)
        return result["recipe_id"]

    async def structured(self, name, instructions, input_data, schema):
        if not self.configured:
            raise AiError(f"{self.label} trebuie configurat pe ZimaOS.")
        try:
            await asyncio.wait_for(self.lock.acquire(), timeout=0.1)
        except TimeoutError as exc:
            raise AiError(f"{self.label} este ocupat momentan.", 429, retryable=False) from exc
        try:
            return await self._structured(name, instructions, input_data, schema)
        finally:
            self.lock.release()

    async def _structured(self, name, instructions, input_data, schema):
        payload = {
            "model": self.model,
            "stream": False,
            "messages": [
                {"role": "system", "content": instructions},
                {"role": "user", "content": json.dumps(input_data, ensure_ascii=False)},
            ],
        }
        budget = {"recipe_choice": 768, "personal_recipe_advice": 1536,
                  "personal_recipe_import": 3072, "pdf_recipe_import": 4096, "recipe_conversation": 4096}.get(name, 3072)
        if self.id == "groq":
            payload["max_completion_tokens"] = budget
            if self.model in {"openai/gpt-oss-20b", "openai/gpt-oss-120b"}:
                payload.update(reasoning_effort="low" if name == "recipe_choice" else "medium", temperature=0)
        else:
            # OpenRouter reasoning tokens share the completion budget.
            payload["max_tokens"] = budget
        payload["response_format"] = {"type": "json_schema", "json_schema": {
            "name": name, "strict": True, "schema": schema,
        }}
        if self.id == "openrouter":
            payload["provider"] = {"require_parameters": True}
        try:
            async with asyncio.timeout(28):
                response = await self.client.post(
                    self.url, headers={"Authorization": f"Bearer {self.key}"}, json=payload,
                    timeout=httpx.Timeout(23, connect=4), follow_redirects=False,
                )
            response.raise_for_status()
        except (httpx.TimeoutException, TimeoutError) as exc:
            raise AiError(f"{self.label} nu a răspuns la timp.", 504) from exc
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            if status in (401, 403):
                raise AiError(f"{self.label} a respins cheia API.") from exc
            if status == 402:
                raise AiError(f"Verifică soldul sau limita de consum la {self.label}.") from exc
            if status == 429:
                raise AiError(f"{self.label} a atins limita de cereri.", 429) from exc
            if status == 400:
                try:
                    failed_validation = exc.response.json()["error"]["code"] == "json_validate_failed"
                except (KeyError, TypeError, ValueError):
                    failed_validation = False
                if failed_validation:
                    raise AiError(f"{self.label} a returnat un rezultat incomplet sau invalid.", 502, retryable=False) from exc
            if status in (400, 404, 422):
                raise AiError(f"Verifică modelul configurat pentru {self.label}.") from exc
            raise AiError(f"{self.label} nu este disponibil momentan.") from exc
        except httpx.RequestError as exc:
            raise AiError(f"ZimaOS nu poate contacta {self.label}.") from exc
        try:
            reply = response.json()["choices"][0]
            if reply["finish_reason"] != "stop":
                raise ValueError("Incomplete selection")
            return json.loads(reply["message"]["content"])
        except (IndexError, KeyError, TypeError, ValueError) as exc:
            raise AiError(f"{self.label} a returnat o alegere invalidă." if name == "recipe_choice"
                          else f"{self.label} a returnat un rezultat incomplet sau invalid.", 502, retryable=False) from exc
