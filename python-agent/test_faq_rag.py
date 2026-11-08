"""FAQ regression tests: offline by default; --live opts into real model loading."""

import importlib.util
import json
import sys
import tempfile
import threading
import time
import types
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


class FakeCollection:
    def __init__(self, metadata=None):
        self.rows = {}
        self.metadata = dict(metadata or {})
        self.upserts = 0
        self.last_query = None

    def count(self):
        return len(self.rows)

    def upsert(self, ids, documents, metadatas):
        if not ids:
            raise ValueError("Chroma requires nonempty ids")
        self.upserts += 1
        time.sleep(0.01)
        self.rows.update(zip(ids, metadatas))

    def modify(self, metadata):
        # 与真实 Chroma CollectionCommon._validate_modify_request 一致：
        # 即便值不变，也不能在 modify 时再次提交距离函数。
        if "hnsw:space" in metadata:
            raise ValueError("Changing the distance function is not supported")
        self.metadata = metadata

    def query(self, **kwargs):
        self.last_query = kwargs
        rows = list(self.rows.values())[:kwargs["n_results"]]
        return {"metadatas": [rows], "distances": [[0.1] * len(rows)]}


class FaqRagTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / "faq.json"
        self.write_faqs([self.faq(1)])
        config = types.SimpleNamespace(
            FAQ_DATA_PATH=str(self.source), RAG_CHROMA_PATH="chroma_data",
            RAG_MODEL_CACHE_DIR="", EMBED_MODEL="offline-test", RAG_TOP_K=3,
        )
        spec = importlib.util.spec_from_file_location("faq_rag_offline", HERE / "faq_rag.py")
        self.rag = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"config": config}):
            spec.loader.exec_module(self.rag)
        self.collections = {}
        self.clients = []
        self.models = []

        def get_collection(**kwargs):
            return self.collections.setdefault(kwargs["name"], FakeCollection(kwargs["metadata"]))

        def make_client(path):
            self.clients.append(path)
            time.sleep(0.02)
            return types.SimpleNamespace(get_or_create_collection=get_collection)

        def make_embedding(**kwargs):
            self.models.append(kwargs)
            return object()

        chromadb = types.ModuleType("chromadb")
        chromadb.PersistentClient = make_client
        embeddings = types.ModuleType("chromadb.utils.embedding_functions")
        embeddings.SentenceTransformerEmbeddingFunction = make_embedding
        modules = patch.dict(sys.modules, {
            "chromadb": chromadb, "chromadb.utils.embedding_functions": embeddings,
        })
        modules.start()
        self.addCleanup(modules.stop)

    @staticmethod
    def faq(item_id, answer="请以支付渠道结果为准"):
        return {"id": item_id, "question": "退款多久到账", "answer": answer, "category": "退款"}

    def write_faqs(self, faqs):
        self.source.write_text(json.dumps(faqs, ensure_ascii=False), encoding="utf-8")

    def test_reviewed_source_loads_without_assuming_thirty_rows(self):
        self.rag.c.FAQ_DATA_PATH = "faq_grounded.json"
        faqs = self.rag._load_faqs()
        self.assertTrue(faqs)
        self.assertEqual(len({str(f["id"]) for f in faqs}), len(faqs))
        self.assertTrue(all(f["question"] and f["answer"] for f in faqs))

    def test_changed_source_uses_fresh_index_and_preserves_previous_collection(self):
        self.write_faqs([self.faq(1), self.faq(2)])
        self.rag.build_index()
        previous = self.rag._collection
        self.write_faqs([self.faq(1, "新答案")])
        hits = self.rag.search("退款")
        self.assertEqual(hits[0]["answer"], "新答案")
        self.assertIsNot(self.rag._collection, previous)
        self.assertEqual(previous.count(), 2)
        self.assertEqual(self.rag._collection.count(), 1)

    def test_unchanged_source_does_not_repeat_embeddings(self):
        self.rag.build_index()
        self.rag.build_index()
        self.rag.search("退款")
        self.assertEqual(self.rag._collection.upserts, 1)

    def test_model_change_uses_an_independent_collection(self):
        self.rag.build_index()
        previous = self.rag._collection
        self.rag.c.EMBED_MODEL = "different-offline-model"
        self.rag.search("退款")
        self.assertIsNot(self.rag._collection, previous)

    def test_empty_source_returns_no_results_without_empty_upsert(self):
        self.rag.build_index()
        self.write_faqs([])
        self.assertEqual(self.rag.search("退款"), [])

    def test_duplicate_ids_are_rejected_before_embedding(self):
        self.write_faqs([self.faq(1), self.faq("1")])
        with self.assertRaises(ValueError):
            self.rag.build_index()
        self.assertEqual(self.models, [])

    def test_invalid_source_does_not_fall_back_to_old_answers(self):
        self.rag.build_index()
        self.source.write_text("not json", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.rag.search("退款")

    def test_blank_query_does_not_load_model(self):
        self.assertEqual(self.rag.search("  "), [])
        self.assertEqual(self.models, [])

    def test_top_k_is_limited_to_index_size(self):
        self.rag.search("退款", top_k=100)
        self.assertEqual(self.rag._collection.last_query["n_results"], 1)

    def test_invalid_top_k_is_rejected_before_model_loading(self):
        for top_k in (0, -1, True, 1.5):
            with self.subTest(top_k=top_k), self.assertRaises(ValueError):
                self.rag.search("退款", top_k=top_k)
        self.assertEqual(self.models, [])

    def test_concurrent_cold_searches_initialize_and_build_once(self):
        barrier = threading.Barrier(8)

        def search(_):
            barrier.wait(timeout=5)
            return self.rag.search("退款")

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(search, range(8)))
        self.assertTrue(all(result for result in results))
        self.assertEqual(len(self.clients), 1)
        self.assertEqual(len(self.models), 1)
        self.assertEqual(self.rag._collection.upserts, 1)

    def test_failed_build_is_retried(self):
        collection = self.rag._get_collection()
        with patch.object(collection, "upsert", side_effect=RuntimeError("offline failure")):
            with self.assertRaises(RuntimeError):
                self.rag.search("退款")
        self.assertEqual(self.rag.search("退款")[0]["answer"], "请以支付渠道结果为准")


def live_smoke():
    """Opt-in only: may download bge-m3 and persist a real vector index."""
    sys.path.insert(0, str(HERE))
    import faq_rag

    faq_rag.build_index()
    for question, topic in [
        ("退款多久能到账", "退款"), ("配送费怎么算", "配送"),
        ("起送价是多少", "起送"), ("优惠券怎么用", "优惠券"),
        ("订单状态都是啥意思", "订单"),
    ]:
        hits = faq_rag.search(question, top_k=3)
        assert hits and topic in hits[0]["question"] + hits[0]["answer"], question
        print(f"{question}: {hits[0]['question']} ({hits[0]['score']:.4f})")


if __name__ == "__main__":
    if "--live" in sys.argv:
        live_smoke()
    else:
        unittest.main()
