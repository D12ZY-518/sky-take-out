# config.py —— 配置中心（Java 对照：application.yml + @Value 注入的那套东西）
#
# 【它在架构里的位置】
#   main.py / agent.py / tools.py 都要 import config，然后写 config.XXX 取值。
#   全项目只有这一个文件碰"配置读取"，别的文件不直接 getenv —— 配置单一入口，
#   以后加配置项只改这里（类比 Java 里把 @Value 收拢到一个 Properties 类）。
#
# 【.env 是什么】
#   和代码同目录的一个纯文本文件，格式"键=值"，专门放密钥这类不能进 git 的东西。
#   .env.example 是它的模板（无真实密钥，进 git）；.env 本体在 .gitignore 里。
#   Java 世界等价物：启动参数 -Dxxx=yyy 或环境变量占位符 ${DEEPSEEK_API_KEY}。

# from X import Y：从 dotenv 包导入 load_dotenv 函数（等价 Java 的 import）。
# 注意区别：import 是"拿别人写好的"，def 是"自己定义新的"——新手最易混。
from dotenv import load_dotenv
from os import getenv   # 只导入函数本身，所以下面直接写 getenv(...)；若写 import os 则要 os.getenv(...)

# 1. 加载 .env：把文件里的键值对读进环境变量。
#    ⚠ 必须放在任何 getenv 之前 —— 它只对"之后"的读取生效；
#    放在末尾的话，前面读到的全是默认值，而且【不报错】，属于最阴险的静默 bug。
load_dotenv()

# 2. 读取配置：getenv("键名", "默认值")。
#    取不到就用默认值、不抛异常（好设计：生产环境靠真实环境变量注入，不该强依赖 .env 文件）；
#    代价是缺配置不会当场炸，会拖到真正用时才 401 —— 所以重要配置的验证要放到启动或首次调用处。
DEEPSEEK_API_KEY = getenv("DEEPSEEK_API_KEY", "")
# DeepSeek 官方兼容 OpenAI 的接口协议，所以能直接用 langchain-openai 的 ChatOpenAI 接入，
# 只需把 base_url 指到 DeepSeek。若日后换模型厂商，大概率只改这个 URL 和模型名。
DEEPSEEK_BASE_URL = getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_MODEL = getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")

# 每次模型 HTTP 请求的等待/重试预算，以及一轮图执行的最大步数。
# 图步数包含模型和工具节点；这不是整轮请求的总耗时上限。
import math


def _positive_float(name: str, default: str) -> float:
    value = float(getenv(name, default))
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be a finite positive number")
    return value


def _bounded_int(name: str, default: str, minimum: int) -> int:
    value = int(getenv(name, default))
    if value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    return value


MODEL_TIMEOUT_SECONDS = _positive_float("MODEL_TIMEOUT_SECONDS", "30")
MODEL_MAX_RETRIES = _bounded_int("MODEL_MAX_RETRIES", "0", 0)
AGENT_RECURSION_LIMIT = _bounded_int("AGENT_RECURSION_LIMIT", "12", 2)
AGENT_MEMORY_TTL_SECONDS = _positive_float("AGENT_MEMORY_TTL_SECONDS", "1800")
AGENT_MEMORY_MAX_SESSIONS = _bounded_int("AGENT_MEMORY_MAX_SESSIONS", "1000", 1)
AGENT_MEMORY_MAX_TURNS = _bounded_int("AGENT_MEMORY_MAX_TURNS", "10", 1)
REQUEST_CACHE_TTL_SECONDS = _positive_float("REQUEST_CACHE_TTL_SECONDS", "600")
REQUEST_CACHE_MAX_ENTRIES = _bounded_int("REQUEST_CACHE_MAX_ENTRIES", "1000", 1)

# Java 业务服务地址：Python 工具回调 /internal/** 时拼在 URL 前面
JAVA_BASE_URL = getenv("JAVA_BASE_URL", "http://localhost:8080")
# 内部接口共享令牌：必须与 Java application.yml 的 sky.ai.internal-token 完全一致，
# 否则 InternalTokenInterceptor 会回 401（这就是"服务间授信"，与用户登录态 JWT 是两套独立鉴权）
INTERNAL_TOKEN = getenv("INTERNAL_TOKEN", "dev-internal-token-123")

# 3. 类型转换陷阱：环境变量读出来永远是【字符串】"1"，不是布尔 true。
#    这里手动转：strip() 去空格 → lower() 转小写 → in 判断是否属于这几个写法。
#    从内到外读这串链式调用，最终得到 True/False。
#    Java 对照：Boolean.parseBoolean 只认 "true"(忽略大小写)，这里额外放行 "1"/"yes" 更宽松。
MOCK_JAVA = getenv("MOCK_JAVA", "1").strip().lower() in ("1", "true", "yes")

# ==================== RAG 配置（FAQ 向量检索，阶段 8 新增） ====================
# 【为什么要在 config 里管 RAG】同一原则：所有可调参数集中在此，外部只读 config.XXX。
#   FAQ 向量检索涉及"模型选型 / 数据路径 / 召回条数 / 缓存目录 / 镜像源"五项参数，
#   统一收口到这里，调优时只改一处（类比 Java 把 RAG 配置写进 application.yml 而非散在代码里）。
import os                            # 模块级导入幂等，多次 import 不重复加载；这里单独 import 是为了下面 setdefault
FAQ_DATA_PATH = getenv("FAQ_DATA_PATH", "faq_grounded.json")       # 已对照后端能力的保守题库；旧 30 条留作审计材料
EMBED_MODEL   = getenv("EMBED_MODEL", "BAAI/bge-m3")               # 嵌入模型：BAAI bge-m3，中英多语 + 长文本 + 1024 维
# 选型理由：开源、能本地跑、国产模型中文够强；不联网调 OpenAI text-embedding-3，省 token 也避免数据外泄
RAG_TOP_K     = _bounded_int("RAG_TOP_K", "3", 1)                 # 召回 top-k：3 是经验起点，至少召回 1 条
RAG_MODEL_CACHE_DIR = getenv("RAG_MODEL_CACHE_DIR", "models")        # 模型缓存目录，默认项目下 models/（已 .gitignore），避免下载到 C 盘
# Chroma 向量库持久化目录：进程重启不丢索引；相对路径以 config.py 同目录为根（已 .gitignore）
RAG_CHROMA_PATH   = getenv("RAG_CHROMA_PATH", "chroma_data")
# ⚠ HF_ENDPOINT 必须在【import sentence_transformers 之前】生效，否则它连的是 huggingface.co，
#    国内大概率超时/403。这里在 config 模块顶部直接写进 os.environ，faq_rag.py 后 import 时就能读到。
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")      # setdefault：只在用户环境未设时兜底，不覆盖
# 把 RAG_MODEL_CACHE_DIR 解析成绝对路径：HF_HOME 必须是绝对路径，否则 cwd 不同找不到缓存。
# 同时让 SentenceTransformerEmbeddingFunction 的 cache_folder 也是绝对路径，行为可预测。
if RAG_MODEL_CACHE_DIR:
    if not os.path.isabs(RAG_MODEL_CACHE_DIR):
        RAG_MODEL_CACHE_DIR = os.path.join(os.path.dirname(__file__), RAG_MODEL_CACHE_DIR)
    os.environ.setdefault("HF_HOME", RAG_MODEL_CACHE_DIR)            # HF_HOME 是 huggingface_hub 真正读的键
