"""限制进程内会话与检查点容量；通过公开 saver 接口保留当前状态。"""
from collections import OrderedDict, defaultdict
from contextlib import contextmanager
from dataclasses import dataclass
from threading import Lock
import time

from langgraph.checkpoint.memory import InMemorySaver


class MemoryCapacityError(RuntimeError):
    """所有会话均在执行，暂时没有可释放的容量。"""


@dataclass
class _Session:
    touched: float
    active: int = 0


class ManagedMemorySaver(InMemorySaver):
    def __init__(self, *, max_sessions: int, max_turns: int, ttl_seconds: float,
                 clock=time.monotonic):
        super().__init__()
        if max_sessions < 1 or max_turns < 1 or ttl_seconds <= 0:
            raise ValueError("memory limits must be positive")
        self.max_sessions = max_sessions
        self.max_turns = max_turns
        self.ttl_seconds = ttl_seconds
        self._clock = clock
        self._sessions = OrderedDict()
        self._guard = Lock()

    def _remove(self, thread_id):
        super().delete_thread(thread_id)
        self._sessions.pop(thread_id, None)

    @contextmanager
    def session(self, thread_id: str):
        """仅在会话锁内使用；过期和容量淘汰都跳过正在执行的会话。"""
        with self._guard:
            now = self._clock()
            for key, entry in list(self._sessions.items()):
                if not entry.active and now - entry.touched >= self.ttl_seconds:
                    self._remove(key)
            if thread_id not in self._sessions:
                if len(self._sessions) >= self.max_sessions:
                    candidate = next((key for key, value in self._sessions.items()
                                      if not value.active), None)
                    if candidate is None:
                        raise MemoryCapacityError("all in-memory sessions are active")
                    self._remove(candidate)
                self._sessions[thread_id] = _Session(now)
            entry = self._sessions[thread_id]
            entry.active += 1
            self._sessions.move_to_end(thread_id)
        try:
            yield
        finally:
            with self._guard:
                entry.active -= 1
                entry.touched = self._clock()
                self._sessions.move_to_end(thread_id)

    def compact(self, thread_id: str):
        """在 invoke 结束且仍持有会话锁时，只保存最新检查点与最近完整用户轮。

        保存失败节点的 pending_writes，压缩不会执行或重放工具。
        不支持历史回放、检查点分叉或嵌套子图；本项目只使用单层客服图。
        """
        config = {"configurable": {"thread_id": thread_id, "checkpoint_ns": ""}}
        saved = self.get_tuple(config)
        if saved is None:
            return
        checkpoint = saved.checkpoint.copy()
        values = dict(checkpoint["channel_values"])
        messages = values.get("messages", [])
        starts = [index for index, message in enumerate(messages) if message.type == "human"]
        if len(starts) > self.max_turns:
            values["messages"] = messages[starts[-self.max_turns]:]
        checkpoint["channel_values"] = values
        pending = defaultdict(list)
        for task_id, channel, value in saved.pending_writes or []:
            pending[task_id].append((channel, value))
        # 最新检查点已包含所需各 channel 的反序列化值，全部重新保存，
        # 同时释放旧检查点、旧 writes 和旧 blob 版本，不保留已删除的父链。
        self.delete_thread(thread_id)
        written = self.put(config, checkpoint, saved.metadata, checkpoint["channel_versions"])
        for task_id, writes in pending.items():
            self.put_writes(written, writes, task_id)
