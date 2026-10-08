from __future__ import annotations

import http.client
import io
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

from backend import devprep_server as server


FIXED_TIME = datetime(2026, 10, 8, 12, 0, tzinfo=timezone(timedelta(hours=8)))


def example_config(wire_api: str = "responses", env_key: str = "") -> dict:
    return server.normalize_config({
        "model": "test-model",
        "model_provider": "example",
        "model_providers": {
            "example": {
                "name": "Example provider",
                "base_url": "https://gateway.example/v1",
                "wire_api": wire_api,
                "env_key": env_key,
            },
        },
    })


class BackendTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="devprep-tests-")
        self.test_root = Path(self.directory.name)
        self.environment = mock.patch.dict(os.environ, {
            "DEVPREP_DATA_DIR": str(self.test_root),
            "DEVPREP_DB_FILE": str(self.test_root / "test.sqlite3"),
            "DEVPREP_CONFIG_FILE": str(self.test_root / "settings.toml"),
        }, clear=True)
        self.environment.start()
        with server.MODEL_CACHE_LOCK:
            server.MODEL_CACHE.clear()
        server.initialize_database()
        self.connection = server.connect_db()
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(self.environment.stop)
        self.addCleanup(self.connection.close)

    def assert_api_error(self, code: str, operation, *args, **kwargs) -> server.ApiError:
        with self.assertRaises(server.ApiError) as raised:
            operation(*args, **kwargs)
        self.assertEqual(raised.exception.code, code)
        return raised.exception


class PracticeTests(BackendTestCase):
    def submit_choice(self, correct: bool = True) -> dict:
        answer = "hashCode \u5fc5\u987b\u76f8\u540c" if correct else "hashCode \u5fc5\u987b\u4e0d\u540c"
        return server.submit_attempt(self.connection, {"question_id": "q-equals-01", "answer": answer})

    def test_first_correct_answer_has_one_day_review(self) -> None:
        with mock.patch.object(server, "now_local", return_value=FIXED_TIME):
            feedback = self.submit_choice()
        self.assertEqual(feedback["streak"], 1)
        self.assertEqual(feedback["review_hours"], 24)
        self.assertEqual(feedback["review_at"], server.iso_time(FIXED_TIME + timedelta(hours=24)))
        self.assertEqual(feedback["score"], 1.0)

    def test_same_second_answers_order_and_streak_reset(self) -> None:
        with mock.patch.object(server, "now_local", return_value=FIXED_TIME):
            results = [self.submit_choice(value) for value in (True, True, True, True, False, True)]
        self.assertEqual([result["streak"] for result in results], [1, 2, 3, 4, 0, 1])
        self.assertEqual([result["review_hours"] for result in results], [24, 72, 72, 168, 4, 24])
        self.assertEqual([attempt["is_correct"] for attempt in server.recent_attempts(self.connection, 2)], [True, False])

    def test_streak_is_scoped_to_knowledge_point(self) -> None:
        self.submit_choice()
        question = server.next_question(self.connection, "java-string")
        expected = server.question_feedback(self.connection, question["id"])["answer"]
        feedback = server.submit_attempt(self.connection, {"question_id": question["id"], "answer": expected})
        self.assertEqual(feedback["streak"], 1)

    def test_partial_short_answer_records_rubric_score(self) -> None:
        feedback = server.submit_attempt(self.connection, {
            "question_id": "q-equals-02", "answer": "An answer to self-assess", "rubric_hits": [True, False, True],
        })
        self.assertEqual(feedback["score"], 0.67)
        self.assertFalse(feedback["correct"])
        self.assertEqual(feedback["review_hours"], 4)
        attempt = self.connection.execute("SELECT * FROM attempts").fetchone()
        self.assertAlmostEqual(attempt["score"], 2 / 3)
        self.assertEqual(json.loads(attempt["rubric_hits_json"]), [True, False, True])

    def test_full_short_answer_is_correct(self) -> None:
        feedback = server.submit_attempt(self.connection, {
            "question_id": "q-equals-02", "answer": "Answer", "rubric_hits": [True, True, True],
        })
        self.assertTrue(feedback["correct"])
        self.assertEqual(feedback["review_hours"], 24)

    def test_invalid_answer_does_not_update_history(self) -> None:
        for answer in (None, "", "  ", [], "not an option", "a" * 8001):
            with self.subTest(answer_type=type(answer).__name__):
                self.assert_api_error("invalid_answer", server.submit_attempt, self.connection, {
                    "question_id": "q-equals-01", "answer": answer,
                })
        self.assertEqual(self.connection.execute("SELECT COUNT(*) FROM attempts").fetchone()[0], 0)

    def test_invalid_self_assessment_is_rejected(self) -> None:
        for hits in (None, [], [True], [1, 1, 1], "yes"):
            with self.subTest(hits=hits):
                self.assert_api_error("invalid_rubric", server.submit_attempt, self.connection, {
                    "question_id": "q-equals-02", "answer": "Answer", "rubric_hits": hits,
                })

    def test_unknown_question_is_rejected(self) -> None:
        self.assert_api_error("question_not_found", server.submit_attempt, self.connection, {
            "question_id": "missing", "answer": "Answer",
        })

    def test_next_question_hides_reference(self) -> None:
        question = server.next_question(self.connection, "java-equals")
        self.assertNotIn("answer", question)
        self.assertNotIn("rubric", question)
        reference = server.question_feedback(self.connection, question["id"])
        self.assertIn("answer", reference)

    def test_global_next_question_prioritizes_due_topic(self) -> None:
        self.connection.execute("UPDATE knowledge_points SET next_review_at=? WHERE id='java-equals'", (server.iso_time(FIXED_TIME - timedelta(hours=1)),))
        with mock.patch.object(server, "now_local", return_value=FIXED_TIME):
            question = server.next_question(self.connection)
        self.assertEqual(question["knowledge_point_id"], "java-equals")
        self.assertTrue(question["reason"])

    def test_global_next_question_prioritizes_last_wrong_answer(self) -> None:
        with mock.patch.object(server, "now_local", return_value=FIXED_TIME):
            self.submit_choice(False)
            question = server.next_question(self.connection)
        self.assertEqual(question["knowledge_point_id"], "java-equals")

    def test_practiced_topic_with_missing_schedule_is_due(self) -> None:
        self.submit_choice()
        self.connection.execute("UPDATE knowledge_points SET next_review_at=NULL WHERE id='java-equals'")
        self.assertEqual(server.next_question(self.connection)["knowledge_point_id"], "java-equals")

    def test_dashboard_includes_prerequisites_and_history(self) -> None:
        self.submit_choice()
        dashboard = server.dashboard_data(self.connection)
        topic = next(item for item in dashboard["topics"] if item["id"] == "java-hashmap")
        self.assertEqual(topic["prerequisites"], ["java-equals"])
        self.assertEqual(dashboard["total_attempts"], 1)


class MaterialTests(BackendTestCase):
    def test_import_view_search_delete_round_trip(self) -> None:
        material = server.import_material(self.connection, "../../notes.md", "# Cache\nNovelCacheMarker stores data.\n## Eviction\nLeast recently used entries expire.")
        self.assertEqual(material["filename"], "notes.md")
        self.assertEqual(material["chunk_count"], 2)
        details = server.material_details(self.connection, material["id"])
        self.assertEqual(len(details["chunks"]), 2)
        matches = server.search_materials(self.connection, "NovelCacheMarker")
        self.assertEqual(matches[0]["material_id"], material["id"])
        server.delete_material(self.connection, material["id"])
        self.assertEqual(server.search_materials(self.connection, "NovelCacheMarker"), [])
        self.assertEqual(self.connection.execute("SELECT COUNT(*) FROM material_chunks WHERE material_id=?", (material["id"],)).fetchone()[0], 0)

    def test_fenced_code_with_hash_is_not_a_heading(self) -> None:
        material = server.import_material(self.connection, "code.md", "# Example\n```python\n# comment\nprint('ok')\n```\nAfter code.")
        chunks = server.material_details(self.connection, material["id"])["chunks"]
        self.assertEqual(len(chunks), 1)
        self.assertIn("# comment", chunks[0]["content"])

    def test_heading_only_material_is_rejected_without_saved_row(self) -> None:
        self.assert_api_error("empty_material", server.import_material, self.connection, "empty.md", "# Heading\n\n## Another heading")
        self.assertEqual(len(server.material_list(self.connection)), 1)

    def test_unsupported_empty_and_large_material_are_rejected(self) -> None:
        self.assert_api_error("unsupported_file", server.import_material, self.connection, "document.pdf", "Content")
        self.assert_api_error("empty_material", server.import_material, self.connection, "notes.txt", "  ")
        self.assert_api_error("material_too_large", server.import_material, self.connection, "notes.txt", "x" * (server.MAX_BODY_BYTES + 1))

    def test_builtin_material_cannot_be_deleted(self) -> None:
        self.assert_api_error("protected_material", server.delete_material, self.connection, "material-java-foundations")

    def test_missing_material_has_clear_error(self) -> None:
        self.assert_api_error("material_not_found", server.material_details, self.connection, "missing")
        self.assert_api_error("material_not_found", server.delete_material, self.connection, "missing")

    def test_surrogate_material_text_has_clear_error(self) -> None:
        self.assert_api_error("invalid_material", server.import_material, self.connection, "notes.txt", "invalid\ud800")

    def test_question_words_do_not_create_unrelated_sources(self) -> None:
        self.assertEqual(server.search_materials(self.connection, "What is QuasarUniqueMarker?"), [])

    def test_no_sources_refuses_without_model_request(self) -> None:
        with mock.patch.object(server, "read_model_text") as model:
            response = server.answer_query(self.connection, {"question": "QuasarUniqueMarker"})
        model.assert_not_called()
        self.assertEqual(response["citations"], [])
        self.assertFalse(response["grounded"])

    def test_unconfigured_model_still_exposes_source_passages(self) -> None:
        response = server.answer_query(self.connection, {"question": "hashCode"})
        self.assertIsNone(response["model_used"])
        self.assertFalse(response["grounded"])
        self.assertTrue(response["citations"])

    def test_invalid_citation_number_is_not_marked_grounded(self) -> None:
        with mock.patch.object(server, "read_model_text", return_value="Unsupported [99]"), mock.patch.object(server, "load_config", return_value=(example_config(), "")):
            response = server.answer_query(self.connection, {"question": "hashCode"})
        self.assertFalse(response["grounded"])


class ConfigurationTests(BackendTestCase):
    def test_serialize_round_trip_with_toml_escape_characters(self) -> None:
        config = example_config()
        config["model_providers"]["example"]["name"] = 'Quoted "name"\twith\rcontrol\ncharacters\\path'
        self.assertEqual(server.parse_config_text(server.serialize_config(config)), config)

    def test_unknown_fields_are_removed_from_public_toml(self) -> None:
        parsed = {
            **example_config(), "api_key": "synthetic-secret-do-not-export", "projects": {"private": "unused"},
        }
        parsed["model_providers"]["example"]["api_key"] = "synthetic-secret-do-not-export"
        config = server.normalize_config(parsed)
        serialized = server.serialize_config(config)
        self.assertNotIn("synthetic-secret", serialized)
        self.assertNotIn("projects", serialized)

    def test_url_rejects_credentials_query_bad_port_and_control_characters(self) -> None:
        urls = ["ftp://gateway.example/v1", "https://user:pass@gateway.example/v1", "https://gateway.example/v1?token=x", "https://gateway.example/v1#token", "https://gateway.example:invalid/v1", "https://gateway.example:70000/v1", "https://gateway.example:0/v1", "http://[invalid/v1", "https://gate way.example/v1", "https://gateway.example/\nv1", "https://gateway.example\\other"]
        for url in urls:
            with self.subTest(url=url):
                self.assert_api_error("invalid_config", server.validate_base_url, url)

    def test_public_https_and_local_http_addresses_are_allowed(self) -> None:
        self.assertEqual(server.validate_base_url("https://gateway.example/v1/"), "https://gateway.example/v1")
        self.assertEqual(server.validate_base_url("http://127.0.0.1:3000/v1"), "http://127.0.0.1:3000/v1")

    def test_invalid_provider_shapes_are_user_errors(self) -> None:
        for field, value in (("wire_api", []), ("env_key", []), ("requires_openai_auth", "false"), ("timeout_seconds", True), ("models_path", "/%2e%2e/models"), ("models_path", "//outside.example/models"), ("models_path", "/models\n")):
            config = example_config()
            config["model_providers"]["example"][field] = value
            with self.subTest(field=field, value=value):
                self.assert_api_error("invalid_config", server.normalize_config, config)

    def test_invalid_toml_has_structured_error(self) -> None:
        self.assert_api_error("invalid_toml", server.parse_config_text, 'model = "unterminated')

    def test_surrogate_config_text_has_clear_error(self) -> None:
        self.assert_api_error("invalid_config", server.parse_config_text, "model = '\ud800'")

    def test_api_key_only_uses_explicit_environment_name(self) -> None:
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "synthetic-openai", "DEVPREP_API_KEY": "synthetic-global", "EXAMPLE_TOKEN": "synthetic-selected"}):
            self.assertEqual(server.provider_api_key({"env_key": ""}), "")
            self.assertEqual(server.provider_api_key({"env_key": "MISSING_TOKEN"}), "")
            self.assertEqual(server.provider_api_key({"env_key": "EXAMPLE_TOKEN"}), "synthetic-selected")

    def test_public_config_never_contains_key_value(self) -> None:
        config = example_config(env_key="EXAMPLE_TOKEN")
        with mock.patch.dict(os.environ, {"EXAMPLE_TOKEN": "synthetic-private-token"}):
            public = server.config_public(config)
        self.assertTrue(public["providers"][0]["has_api_key"])
        self.assertEqual(public["providers"][0]["timeout_seconds"], 15)
        self.assertNotIn("synthetic-private-token", json.dumps(public))

    def test_codex_import_uses_only_safe_selected_fields(self) -> None:
        original = {
            **example_config(), "api_key": "synthetic-private-token", "projects": {"workspace": {"trust_level": "trusted"}},
        }
        original["model_providers"]["example"]["api_key"] = "synthetic-private-token"
        with mock.patch.object(Path, "home", return_value=self.test_root), mock.patch.object(Path, "read_text", return_value="fixture"), mock.patch.object(server.tomllib, "loads", return_value=original):
            imported = server.import_codex_config()
        self.assertNotIn("synthetic-private-token", imported)
        self.assertNotIn("projects", imported)
        self.assertEqual(server.parse_config_text(imported)["model_provider"], "example")


class ProviderTests(BackendTestCase):
    def payload(self) -> dict:
        return {"question": "Explain a concept", "passages": [{"title": "Notes", "heading": "Concept", "content": "A concept has a precise definition."}]}

    def generation(self, result, wire_api: str = "responses") -> str:
        data = result if isinstance(result, bytes) else json.dumps(result).encode("utf-8")
        with mock.patch.object(server, "load_config", return_value=(example_config(wire_api), "")), mock.patch.object(server, "provider_urlopen", return_value=io.BytesIO(data)):
            return server.read_model_text(self.payload())

    def test_catalog_data_array_and_direct_string_array(self) -> None:
        for result in ({"data": [{"id": "z-model"}, {"id": "a-model"}, {"id": "a-model"}]}, ["z-model", "a-model", "a-model"], [{"id": "z-model"}, {"id": "a-model"}], {"models": ["z-model", "a-model"]}):
            with self.subTest(result=result), mock.patch.object(server, "load_config", return_value=(example_config(), "")), mock.patch.object(server, "provider_urlopen", return_value=io.BytesIO(json.dumps(result).encode("utf-8"))):
                self.assertEqual(server.fetch_models("example", refresh=True), ["a-model", "z-model"])

    def test_catalog_empty_invalid_and_too_large(self) -> None:
        for result, code in ((b"[]", "empty_catalog"), (b'{"data":null}', "invalid_catalog"), (b"not-json", "invalid_catalog"), (b"x" * 2_000_001, "catalog_too_large")):
            with self.subTest(code=code), mock.patch.object(server, "load_config", return_value=(example_config(), "")), mock.patch.object(server, "provider_urlopen", return_value=io.BytesIO(result)):
                self.assert_api_error(code, server.fetch_models, "example", True)

    def test_catalog_missing_key_prevents_network_request(self) -> None:
        with mock.patch.object(server, "load_config", return_value=(example_config(env_key="MISSING_TOKEN"), "")), mock.patch.object(server, "provider_urlopen") as opener:
            self.assert_api_error("missing_api_key", server.fetch_models, "example")
        opener.assert_not_called()

    def test_catalog_cache_and_refresh(self) -> None:
        with mock.patch.object(server, "load_config", return_value=(example_config(), "")), mock.patch.object(server, "provider_urlopen", side_effect=[io.BytesIO(b'{"data":[{"id":"first"}]}'), io.BytesIO(b'{"data":[{"id":"second"}]}')]) as opener:
            self.assertEqual(server.fetch_models("example"), ["first"])
            self.assertEqual(server.fetch_models("example"), ["first"])
            self.assertEqual(server.fetch_models("example", True), ["second"])
        self.assertEqual(opener.call_count, 2)

    def test_responses_flat_text_and_nested_output(self) -> None:
        self.assertEqual(self.generation({"output_text": " Answer [1] "}), "Answer [1]")
        self.assertEqual(self.generation({"output": [{"type": "message", "content": [{"type": "output_text", "text": "Nested [1]"}]}]}), "Nested [1]")

    def test_chat_text_and_content_parts(self) -> None:
        self.assertEqual(self.generation({"choices": [{"message": {"content": "Chat [1]"}}]}, "chat_completions"), "Chat [1]")
        self.assertEqual(self.generation({"choices": [{"message": {"content": [{"type": "text", "text": "Part [1]"}]}}]}, "chat_completions"), "Part [1]")

    def test_generation_http_and_connection_errors_are_structured(self) -> None:
        cases = [(urllib.error.HTTPError("https://gateway.example/v1/responses", code, "error", {}, None), expected) for code, expected in ((401, "provider_auth"), (403, "provider_auth"), (429, "provider_rate_limit"), (500, "provider_unavailable"))]
        cases.extend([(TimeoutError(), "provider_unavailable"), (urllib.error.URLError("offline"), "provider_unavailable"), (http.client.IncompleteRead(b"partial"), "provider_unavailable")])
        for failure, code in cases:
            with self.subTest(code=code, failure_type=type(failure).__name__), mock.patch.object(server, "load_config", return_value=(example_config(), "")), mock.patch.object(server, "provider_urlopen", side_effect=failure):
                self.assert_api_error(code, server.read_model_text, self.payload())

    def test_malformed_response_shapes_do_not_crash(self) -> None:
        responses = [(None, "responses"), ([], "responses"), ({"output": None}, "responses"), ({"output": [{"content": None}]}, "responses"), ({"output": [{"content": [{"type": "output_text", "text": {}}]}]}, "responses"), ({"choices": None}, "chat_completions"), ({"choices": [None]}, "chat_completions"), ({"choices": [{"message": None}]}, "chat_completions"), ({"choices": [{"message": {"content": [{"text": {}}]}}]}, "chat_completions")]
        for result, wire_api in responses:
            with self.subTest(result=result, wire_api=wire_api):
                self.assert_api_error("invalid_response", self.generation, result, wire_api)

    def test_failed_incomplete_refused_empty_and_oversize_responses(self) -> None:
        cases = [({"status": "failed", "error": {"message": "private diagnostic"}}, "responses", "provider_generation_failed"), ({"status": "incomplete", "output_text": "Partial"}, "responses", "incomplete_response"), ({"output": [{"content": [{"type": "refusal"}]}]}, "responses", "provider_refusal"), ({"choices": [{"finish_reason": "length", "message": {"content": "Partial"}}]}, "chat_completions", "incomplete_response"), ({"choices": [{"message": {"refusal": "refused"}}]}, "chat_completions", "provider_refusal"), ({}, "responses", "empty_response"), (b"not-json", "responses", "invalid_response"), (b"x" * 2_000_001, "responses", "response_too_large")]
        for result, wire_api, code in cases:
            with self.subTest(code=code, wire_api=wire_api):
                self.assert_api_error(code, self.generation, result, wire_api)

    def test_provider_redirect_is_rejected_before_forwarding_request(self) -> None:
        request = urllib.request.Request("https://gateway.example/v1/models", headers={"Authorization": "Bearer synthetic-test-token"})
        self.assert_api_error("provider_redirect", server.ProviderRedirectHandler().redirect_request, request, None, 302, "Found", {}, "https://other.example/models")

    def test_generation_without_sources_does_not_call_provider(self) -> None:
        with mock.patch.object(server, "load_config", return_value=(example_config(), "")), mock.patch.object(server, "provider_urlopen") as opener:
            self.assert_api_error("no_sources", server.read_model_text, {"question": "Explain", "passages": [None]})
        opener.assert_not_called()

    def test_generation_surrogate_text_has_clear_error(self) -> None:
        self.assert_api_error("invalid_response", self.generation, {"output_text": "Invalid\ud800"})

    def test_generation_uses_expected_wire_endpoint_and_request_shape(self) -> None:
        for wire_api, suffix in (("responses", "/responses"), ("chat_completions", "/chat/completions")):
            result = b'{"output_text":"Answer [1]"}' if wire_api == "responses" else b'{"choices":[{"message":{"content":"Answer [1]"}}]}'
            with self.subTest(wire_api=wire_api), mock.patch.object(server, "load_config", return_value=(example_config(wire_api), "")), mock.patch.object(server, "provider_urlopen", return_value=io.BytesIO(result)) as opener:
                server.read_model_text(self.payload())
            request = opener.call_args.args[0]
            self.assertEqual(request.full_url, "https://gateway.example/v1" + suffix)
            self.assertIsNone(request.get_header("Authorization"))
            body = json.loads(request.data)
            self.assertEqual(body["model"], "test-model")
            self.assertIn("input" if wire_api == "responses" else "messages", body)


class HttpApiTests(BackendTestCase):
    def setUp(self) -> None:
        super().setUp()
        handler = type("QuietHandler", (server.DevPrepHandler,), {"log_message": lambda *args: None})
        self.http_server = server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.http_server.daemon_threads = True
        self.server_thread = threading.Thread(target=self.http_server.serve_forever, daemon=True)
        self.server_thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self) -> None:
        self.http_server.shutdown()
        self.http_server.server_close()
        self.server_thread.join(timeout=2)

    def request(self, method: str, path: str, payload=None, headers=None):
        connection = http.client.HTTPConnection(*self.http_server.server_address, timeout=3)
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_practice_reference_and_topic_fields(self) -> None:
        status, reference = self.request("GET", "/api/practice/reference?question_id=q-equals-02")
        self.assertEqual(status, 200)
        self.assertEqual(len(reference["rubric"]), 3)
        status, knowledge = self.request("GET", "/api/knowledge")
        self.assertEqual(status, 200)
        self.assertIn("prerequisites", knowledge["topics"][0])

    def test_material_import_detail_and_delete_endpoints(self) -> None:
        status, created = self.request("POST", "/api/materials/import", {"filename": "notes.md", "content": "# API notes\nTest material."})
        self.assertEqual(status, 201)
        material_id = created["material"]["id"]
        status, details = self.request("GET", "/api/materials/" + material_id)
        self.assertEqual(status, 200)
        self.assertEqual(len(details["chunks"]), 1)
        self.assertIn("content", details["material"])
        status, deleted = self.request("DELETE", "/api/materials/" + material_id)
        self.assertEqual(status, 200)
        self.assertTrue(deleted["deleted"])
        self.assertEqual(self.request("GET", "/api/materials/" + material_id)[0], 404)
        self.assertEqual(self.request("DELETE", "/api/materials/material-java-foundations")[0], 403)

    def test_public_resource_catalog_endpoint(self) -> None:
        status, payload = self.request("GET", "/api/resources")
        self.assertEqual(status, 200)
        self.assertGreaterEqual(len(payload["resources"]), 9)
        self.assertIn("前端", {item["track"] for item in payload["resources"]})
        required = {"id", "track", "title", "kind", "description", "url", "license", "source"}
        for resource in payload["resources"]:
            self.assertTrue(required.issubset(resource))
            self.assertIn(resource["url"].split(":", 1)[0], {"http", "https"})

    def test_settings_normalized_round_trip(self) -> None:
        status, saved = self.request("POST", "/api/settings", {"toml": server.serialize_config(example_config())})
        self.assertEqual(status, 200)
        self.assertEqual(saved["providers"][0]["timeout_seconds"], 15)
        status, loaded = self.request("GET", "/api/settings")
        self.assertEqual(status, 200)
        self.assertEqual(loaded["toml"], saved["toml"])

    def test_non_local_origin_or_host_is_rejected(self) -> None:
        self.assertEqual(self.request("GET", "/api/settings", headers={"Origin": "https://other.example"})[0], 403)
        self.assertEqual(self.request("GET", "/api/settings", headers={"Host": "other.example"})[0], 403)

    def test_invalid_content_length_returns_json_error(self) -> None:
        status, error = self.request("POST", "/api/attempts", {}, {"Content-Length": "not-a-number"})
        self.assertEqual(status, 400)
        self.assertEqual(error["error"]["code"], "invalid_body_size")

    def test_surrogate_json_returns_error_without_losing_connection(self) -> None:
        status, error = self.request("POST", "/api/materials/import", {"filename": "notes.txt", "content": "invalid\ud800"})
        self.assertEqual(status, 400)
        self.assertEqual(error["error"]["code"], "invalid_json")


if __name__ == "__main__":
    unittest.main()
