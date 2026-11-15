import json
from uuid import UUID

import httpx
from fastapi.testclient import TestClient

from app.main import create_app
from app.recipe_pdf import page_text
from test_api import HEADERS, KEY


def test_page_range_preserves_page_boundaries_and_reads_scans(monkeypatch, tmp_path):
    path = tmp_path / "book.pdf"
    calls = []

    def command(args, timeout, error):
        calls.append(args)
        return {"pdfinfo": "Pages: 7\n", "pdftotext": "\fSupă de morcovi\f", "pdftoppm": "",
                "tesseract": "Orez cu legume\n"}[args[0]]

    monkeypatch.setattr("app.recipe_pdf.pdf_command", command)
    assert page_text(path, 3, 4) == "Pagina PDF 3\nOrez cu legume\n\nPagina PDF 4\nSupă de morcovi"
    assert calls[1][1:5] == ["-f", "3", "-l", "4"]
    assert calls[2][1:5] == ["-f", "3", "-l", "3"]
    assert calls[3][0] == "tesseract"


def test_pdf_preview_validates_input_and_only_sends_page_text(tmp_path, monkeypatch):
    for prefix in ("GROQ", "OPENROUTER"):
        monkeypatch.setenv(f"{prefix}_MODEL", "test-model")
        monkeypatch.setenv(f"{prefix}_API_KEY", "test-key")
    binary = b"%PDF-test-fixture"
    extracted = "Pagina PDF 3\nOmletă\nIngrediente: 2 ouă\nPași: bate ouăle."

    def extract(path, first, last):
        assert path.read_bytes() == binary and (first, last) == (3, 4)
        return extracted

    monkeypatch.setattr("app.main.page_text", extract)
    calls = []

    def respond(request):
        calls.append(request.url.host)
        payload = json.loads(request.content)
        assert json.loads(payload["messages"][1]["content"]) == {"text": extracted}
        if len(calls) == 1:
            return httpx.Response(429)
        if len(calls) == 3:
            return httpx.Response(400, json={"error": {"code": "json_validate_failed", "failed_generation": "Private input"}})
        if len(calls) == 4:
            return httpx.Response(200, json={"choices": [{"finish_reason": "length"}]})
        draft = {"title": "Omletă", "minutes": 12, "minutes_estimated": True, "servings": None, "notes": [],
                 "ingredients": [{"name": "Ouă", "amount": "2", "unit": "buc", "original": "2 ouă", "quantity_text": ""},
                                 {"name": "Piper", "amount": "", "unit": "buc", "original": "Piper", "quantity_text": ""}],
                 "steps": ["Bate ouăle."]}
        result = {"recipes": [draft, {**draft, "title": "Fragment", "steps": []}], "more_recipes": False}
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]})

    app = create_app(tmp_path / "pdf.db", KEY, object())
    with TestClient(app, headers=HEADERS) as api:
        remote = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        try:
            for provider in app.state.cloud_ai.values():
                provider.client = remote
            url = "/v1/personal-recipes/pdf-preview"
            headers = {"Content-Type": "application/pdf"}
            assert api.post(url, content=binary, headers=headers, params={"first": 1, "last": 5}).status_code == 422
            params = {"first": 3, "last": 4}
            assert api.post(url, content=binary, params=params).status_code == 415
            assert api.post(url, content=b"not pdf", headers=headers, params=params).status_code == 422
            assert api.post(url, content=binary, headers={**headers, "Authorization": ""}, params=params).status_code == 401
            preview = api.post(url, content=binary, headers=headers, params=params).raise_for_status().json()
            assert [recipe["title"] for recipe in preview["recipes"]] == ["Omletă"]
            assert preview["recipes"][0]["minutes_estimated"] is True
            assert preview["recipes"][0]["ingredients"][1]["quantity_text"] == ""
            assert api.get("/v1/snapshot").json()["personal_recipes"] == []
            failed = api.post(url, content=binary, headers=headers, params=params)
            assert failed.status_code == 502
            assert all(label in failed.json()["detail"] for label in ("GroqCloud", "OpenRouter"))
            assert "Private input" not in failed.text
            assert calls == ["api.groq.com", "openrouter.ai", "api.groq.com", "openrouter.ai"]
        finally:
            api.portal.call(remote.aclose)


def test_upload_reuses_extracted_pages_and_cleans_expired_file(tmp_path, monkeypatch):
    binary = b"%PDF-cached-fixture"
    inspections = []
    reads = []
    prompts = []

    def inspect(path):
        assert path.read_bytes() == binary
        inspections.append(path)
        return 5

    def extract(path, first, last):
        assert path.read_bytes() == binary
        reads.append((first, last))
        for page in range(first, last + 1):
            yield page, f"Recipe {page}"

    async def drafts(_, text):
        prompts.append(text)
        return {"recipes": [], "more_recipes": False}

    monkeypatch.setattr("app.main.pdf_pages", inspect)
    monkeypatch.setattr("app.recipe_pdf.read_pages", extract)
    monkeypatch.setattr("app.main.drafts_from_pdf", drafts)
    app = create_app(tmp_path / "uploads.db", KEY, object())
    with TestClient(app, headers=HEADERS) as api:
        base = "/v1/personal-recipes/pdf-uploads"
        headers = {"Content-Type": "application/pdf"}
        failed = api.post(base, content=b"bad", headers=headers)
        assert failed.status_code == 422
        assert list(app.state.pdf_dir.iterdir()) == []
        upload = api.post(base, content=binary, headers=headers)
        assert upload.status_code == 201 and upload.json()["pages"] == 5
        book = upload.json()["id"]
        route = f"{base}/{book}/preview"
        assert api.post(route, params={"first": 1, "last": 2}, headers={"Authorization": ""}).status_code == 401
        assert api.post(route, params={"first": 5, "last": 6}).status_code == 422
        for first, last in [(1, 2), (2, 4), (1, 4)]:
            api.post(route, params={"first": first, "last": last}).raise_for_status()
        assert reads == [(1, 2), (3, 4)] and len(inspections) == 1
        assert [text.count("Pagina PDF") for text in prompts] == [2, 3, 4]
        app.state.pdf_sessions.documents[UUID(book)].expires = 0
        expired = api.post(route, params={"first": 1, "last": 2})
        assert expired.status_code == 410 and not inspections[0].exists()
        assert api.get("/v1/snapshot").json()["personal_recipes"] == []
