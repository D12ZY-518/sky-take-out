# syntax=docker/dockerfile:1.7
#
# Dockerfile —— Python Agent（FastAPI + LangChain Agent + bge-m3 FAQ 向量检索）
# 基础镜像选 3.12-slim 而非本机的 3.14：torch / sentence-transformers / chromadb
# 对 3.12 的预编译 wheel 最齐全，slim 变体不带编译工具链、体积小。
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Shanghai \
    # pip 走阿里云源（业务依赖与 RAG 依赖）
    PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
    # HuggingFace 国内镜像：模型若缺失时从 hf-mirror 下载，不连 huggingface.co
    HF_ENDPOINT=https://hf-mirror.com \
    # 模型缓存目录（与 volume 挂载点一致；config.py 还会把它写进 HF_HOME）
    HF_HOME=/app/models

WORKDIR /app

# 1) torch 必须用 CPU 专用索引：PyPI 默认 wheel 捆绑 CUDA 运行时（GB 级），
#    download.pytorch.org/whl/cpu 才是纯 CPU 版；版本钉死与本机已验证环境一致
RUN pip install --no-cache-dir "torch==2.14.0+cpu" \
    --index-url https://download.pytorch.org/whl/cpu

# 2) RAG 三件套（体积大头：chromadb 带 onnxruntime，整体 1GB+，固有成本）
RUN pip install --no-cache-dir \
    "sentence-transformers==6.0.1" \
    "transformers==5.17.0" \
    "chromadb==1.5.9"

# 3) 业务直依赖（先拷 requirements 单独装一层，改代码不会重装依赖）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 4) 业务代码（models/ chroma_data/ .env 已在 .dockerignore 排除：
#    大模型权重与向量库运行时走 volume 挂载，密钥走 compose 环境变量注入）
COPY . .

EXPOSE 8000
# --host 0.0.0.0 不能漏：默认 127.0.0.1 只在容器内可达，宿主机/Java 容器都连不进来
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
