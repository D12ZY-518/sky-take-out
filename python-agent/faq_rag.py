"""FAQ 向量检索：按题库内容和嵌入模型隔离持久化索引，首次使用时加载。"""

import hashlib
import json
import math
import os.path
import threading
from typing import Optional

import config as c

# RLock 让预热线程与首批请求共享一次初始化；失败时不发布半初始化状态。
_state_lock = threading.RLock()
_client: Optional[object] = None
_client_path = None
_collection: Optional[object] = None
_collection_key = None
_embedding: Optional[object] = None
_embedding_key = None


def _absolute_path(path):
    """相对路径始终以本模块目录为根，不依赖启动工作目录。"""
    return os.path.abspath(os.path.join(os.path.dirname(__file__), path))


def _load_faqs() -> list:
    """读取并校验题库；无效题库直接报错，避免继续提供旧的规则承诺。"""
    with open(_absolute_path(c.FAQ_DATA_PATH), "r", encoding="utf-8") as source:
        rows = json.load(source)
    if not isinstance(rows, list):
        raise ValueError("FAQ 数据必须是数组")
    faqs, ids = [], set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("FAQ 条目必须是对象")
        item_id = row.get("id")
        if isinstance(item_id, bool) or not isinstance(item_id, (str, int)):
            raise ValueError("FAQ id 必须是字符串或整数")
        item_id = str(item_id).strip()
        if not item_id or item_id in ids:
            raise ValueError("FAQ id 不能为空或重复")
        ids.add(item_id)
        if any(not isinstance(row.get(key), str) or not row[key].strip()
               for key in ("question", "answer")):
            raise ValueError("FAQ question 和 answer 必须是非空字符串")
        category = row.get("category", "")
        if not isinstance(category, str):
            raise ValueError("FAQ category 必须是字符串")
        faqs.append({
            "id": item_id, "question": row["question"].strip(),
            "answer": row["answer"].strip(), "category": category,
        })
    return faqs


def _fingerprint(faqs):
    payload = {"schema": 1, "model": c.EMBED_MODEL, "faqs": faqs}
    return hashlib.sha256(json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")).hexdigest()


def _get_collection(faqs=None):
    """懒加载；新题库/模型使用新 collection，保留磁盘中的旧索引。"""
    global _client, _client_path, _collection, _collection_key, _embedding, _embedding_key
    with _state_lock:
        if faqs is None:
            faqs = _load_faqs()
        path = _absolute_path(c.RAG_CHROMA_PATH)
        cache = _absolute_path(c.RAG_MODEL_CACHE_DIR) if c.RAG_MODEL_CACHE_DIR else ""
        digest = _fingerprint(faqs)
        key = (path, cache, digest)
        if _collection is not None and _collection_key == key:
            return _collection

        import chromadb
        from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction

        if _client is None or _client_path != path:
            client = chromadb.PersistentClient(path=path)
        else:
            client = _client
        embedding_key = (c.EMBED_MODEL, cache)
        if _embedding is None or _embedding_key != embedding_key:
            kwargs = {"cache_folder": cache} if cache else {}
            # 该构造器会加载模型，缓存未命中时可能下载；导入本模块本身不会触发。
            embedding = SentenceTransformerEmbeddingFunction(model_name=c.EMBED_MODEL, **kwargs)
        else:
            embedding = _embedding
        collection = client.get_or_create_collection(
            name=f"faq_grounded_v3_{digest[:24]}",
            embedding_function=embedding,
            metadata={"hnsw:space": "cosine"},
        )
        _client, _client_path = client, path
        _embedding, _embedding_key = embedding, embedding_key
        _collection, _collection_key = collection, key
        return collection


def _ensure_index(faqs):
    """调用方持有 _state_lock；完成标记只在全部写入成功后保存。"""
    coll = _get_collection(faqs)
    digest = _fingerprint(faqs)
    # Chroma 禁止 modify 再次传入 hnsw:space，即使仍然是 cosine。
    # 距离函数已在创建 collection 时固化，只更新业务完成标记。
    metadata = {key: value for key, value in (coll.metadata or {}).items()
                if key != "hnsw:space"}
    if metadata.get("faq_digest") != digest or coll.count() != len(faqs):
        coll.modify(metadata={**metadata, "faq_digest": ""})
        coll.upsert(
            ids=[row["id"] for row in faqs],
            documents=[f"{row['question']} {row['answer']}" for row in faqs],
            metadatas=[{key: row[key] for key in ("question", "answer", "category")}
                       for row in faqs],
        )
        coll.modify(metadata={**metadata, "faq_digest": digest})
    return coll


def build_index():
    """题库未变化时复用已有索引，避免每次启动都重新计算向量。"""
    with _state_lock:
        faqs = _load_faqs()
        if faqs:
            _ensure_index(faqs)


def search(query: str, top_k: Optional[int] = None) -> list:
    """返回 {question, answer, score}；余弦相似度为 1 - distance，可为负数。"""
    if not isinstance(query, str):
        raise ValueError("FAQ query 必须是字符串")
    if not query.strip():
        return []
    if top_k is None:
        top_k = c.RAG_TOP_K
    if isinstance(top_k, bool) or not isinstance(top_k, int) or top_k <= 0:
        raise ValueError("FAQ top_k 必须是正整数")
    with _state_lock:
        faqs = _load_faqs()
        if not faqs:
            return []
        coll = _ensure_index(faqs)
        count = len(faqs)

    # 已完成的 collection 内容不再原地更新，不同请求可并行执行检索。
    res = coll.query(
        query_texts=[query.strip()], n_results=min(top_k, count),
        include=["metadatas", "distances"],
    )
    metadata_groups = res.get("metadatas") or [[]]
    distance_groups = res.get("distances") or [[]]
    hits = []
    for row, distance in zip(metadata_groups[0] or [], distance_groups[0] or []):
        if not isinstance(row, dict) or not all(
            isinstance(row.get(key), str) and row[key].strip() for key in ("question", "answer")
        ):
            continue
        if isinstance(distance, bool) or not isinstance(distance, (int, float)):
            continue
        if not math.isfinite(distance):
            continue
        hits.append({"question": row["question"], "answer": row["answer"],
                     "score": float(1.0 - distance)})
    return hits


def warm_up():
    """后台预热失败不阻断服务；工具层捕获检索错误并明确告知规则无法核实。"""
    try:
        build_index()
        print("[faq_rag] warm_up 完成：FAQ 索引已就绪", flush=True)
    except Exception as error:
        # 只打印异常类型，避免第三方错误消息包含本地路径或访问令牌。
        print(f"[faq_rag] warm_up 失败（{type(error).__name__}），FAQ 将在后续调用时重试", flush=True)
