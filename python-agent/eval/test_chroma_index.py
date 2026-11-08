"""可选的真实 Chroma 契约回归：临时索引、固定向量，无模型下载。"""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


@unittest.skipUnless(importlib.util.find_spec("chromadb"), "optional chromadb is not installed")
class ChromaIndexTest(unittest.TestCase):
    def test_build_and_source_change_with_real_chroma(self):
        import chromadb
        from chromadb.api.types import EmbeddingFunction
        from chromadb.config import Settings

        class LocalEmbedding(EmbeddingFunction):
            def __init__(self):
                pass

            def __call__(self, input):
                return [[1.0, 0.0, 0.0] for _ in input]

            @staticmethod
            def name():
                return "offline_faq_test"

            def get_config(self):
                return {}

            @staticmethod
            def build_from_config(config):
                return LocalEmbedding()

        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
            source = Path(folder) / "faq.json"
            source.write_text(json.dumps([{"id": 1, "question": "退款规则", "answer": "旧答案"}]),
                              encoding="utf-8")
            config = SimpleNamespace(
                FAQ_DATA_PATH=str(source), RAG_CHROMA_PATH=str(Path(folder) / "index"),
                RAG_MODEL_CACHE_DIR="", EMBED_MODEL="offline_faq_test", RAG_TOP_K=3)
            spec = importlib.util.spec_from_file_location(
                "faq_chroma_contract", Path(__file__).resolve().parents[1] / "faq_rag.py")
            rag = importlib.util.module_from_spec(spec)
            with patch.dict("sys.modules", {"config": config}):
                spec.loader.exec_module(rag)

            client = chromadb.PersistentClient(path=config.RAG_CHROMA_PATH,
                                               settings=Settings(anonymized_telemetry=False))
            try:
                with (patch.object(chromadb, "PersistentClient", return_value=client),
                      patch("chromadb.utils.embedding_functions.SentenceTransformerEmbeddingFunction",
                            return_value=LocalEmbedding())):
                    rag.build_index()
                    self.assertEqual(rag.search("退款")[0]["answer"], "旧答案")
                    original = rag._collection
                    source.write_text(json.dumps([
                        {"id": 1, "question": "退款规则", "answer": "新答案"}]), encoding="utf-8")
                    self.assertEqual(rag.search("退款")[0]["answer"], "新答案")
                    self.assertNotEqual(original.name, rag._collection.name)
                    self.assertEqual(original.count(), 1)
            finally:
                client._system.stop()


if __name__ == "__main__":
    unittest.main()
