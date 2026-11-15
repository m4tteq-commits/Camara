import json
from pathlib import Path
from shutil import which

import pytest
from PIL import Image, ImageDraw, ImageFont

from app.models import MealPlan, Mutation
from app.recipe_pdf import page_text


def test_plan_exchange_fixture_is_valid_for_android_and_server():
    fixture = Path(__file__).resolve().parents[2] / "android/app/src/test/resources/week-contract.json"
    body = json.loads(fixture.read_text(encoding="utf-8"))
    plans = [MealPlan.model_validate(value) for value in body["meals"]]
    action = Mutation.model_validate(body["action"])
    assert {str(plan.id) for plan in plans} == {str(change.id) for change in action.changes}
    assert all(MealPlan.model_validate(change.after) in plans for change in action.changes)


@pytest.mark.skipif(any(which(name) is None for name in ("pdfinfo", "pdftotext", "pdftoppm", "tesseract")),
                    reason="OCR tools are installed in the container and CI")
def test_scanned_pdf_uses_real_poppler_and_tesseract(tmp_path):
    path = tmp_path / "scanned.pdf"
    with Image.new("RGB", (1240, 1754), "white") as image:
        ImageDraw.Draw(image).multiline_text((100, 130), "OMLETA\nINGREDIENTE: 2 OUA\nPASI: BATE OUALE.",
                                            fill="black", font=ImageFont.load_default(size=58), spacing=30)
        image.save(path, "PDF", resolution=150)
    result = page_text(path, 1, 1).upper()
    assert "OMLETA" in result and "INGREDIENTE" in result
