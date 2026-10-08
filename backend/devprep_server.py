from __future__ import annotations

import http.client
import json
import mimetypes
import os
import re
import sqlite3
import sys
import threading
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
SEED_FILE = ROOT / "data" / "seed" / "java-foundations.md"
RESOURCES_FILE = ROOT / "data" / "seed" / "resources.json"
MAX_BODY_BYTES = 1_000_000
MODEL_CACHE_SECONDS = 300
MODEL_CACHE: dict[str, tuple[float, list[str]]] = {}
MODEL_CACHE_LOCK = threading.Lock()


class ManagedConnection(sqlite3.Connection):
    """Close SQLite handles when a request's ``with connect_db()`` scope ends."""

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        try:
            super().__exit__(exc_type, exc_value, traceback)
        finally:
            self.close()


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def now_local() -> datetime:
    return datetime.now().astimezone()


def iso_time(value: datetime) -> str:
    return value.isoformat(timespec="seconds")


def data_directory() -> Path:
    if os.environ.get("DEVPREP_DATA_DIR"):
        path = Path(os.environ["DEVPREP_DATA_DIR"]).expanduser()
    elif os.name == "nt":
        path = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "DevPrep AI"
    else:
        path = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "devprep-ai"
    path.mkdir(parents=True, exist_ok=True)
    return path


def database_path() -> Path:
    override = os.environ.get("DEVPREP_DB_FILE")
    return Path(override).expanduser() if override else data_directory() / "devprep.sqlite3"


def settings_path() -> Path:
    override = os.environ.get("DEVPREP_CONFIG_FILE")
    return Path(override).expanduser() if override else data_directory() / "settings.toml"


def connect_db(path: Path | None = None) -> sqlite3.Connection:
    resolved = path or database_path()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(resolved, timeout=10, factory=ManagedConnection)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA busy_timeout = 5000")
    return connection


def initialize_database(path: Path | None = None) -> None:
    with connect_db(path) as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS knowledge_points (
                id TEXT PRIMARY KEY,
                domain TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                prerequisites_json TEXT NOT NULL DEFAULT '[]',
                next_review_at TEXT,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS materials (
                id TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                title TEXT NOT NULL,
                source_type TEXT NOT NULL,
                imported_at TEXT NOT NULL,
                content TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS material_chunks (
                id TEXT PRIMARY KEY,
                material_id TEXT NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
                chunk_index INTEGER NOT NULL,
                heading TEXT NOT NULL,
                content TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_chunks_material ON material_chunks(material_id, chunk_index);
            CREATE TABLE IF NOT EXISTS questions (
                id TEXT PRIMARY KEY,
                knowledge_point_id TEXT NOT NULL REFERENCES knowledge_points(id),
                question_type TEXT NOT NULL,
                stem TEXT NOT NULL,
                options_json TEXT NOT NULL DEFAULT '[]',
                answer_json TEXT NOT NULL,
                explanation TEXT NOT NULL,
                rubric_json TEXT NOT NULL DEFAULT '[]',
                source_material_id TEXT REFERENCES materials(id),
                status TEXT NOT NULL DEFAULT 'reviewed'
            );
            CREATE INDEX IF NOT EXISTS idx_questions_point ON questions(knowledge_point_id, id);
            CREATE TABLE IF NOT EXISTS attempts (
                id TEXT PRIMARY KEY,
                question_id TEXT NOT NULL REFERENCES questions(id),
                knowledge_point_id TEXT NOT NULL REFERENCES knowledge_points(id),
                answer_json TEXT NOT NULL,
                score REAL NOT NULL,
                is_correct INTEGER NOT NULL,
                rubric_hits_json TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_attempts_created ON attempts(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_attempts_point ON attempts(knowledge_point_id, created_at DESC);
            """
        )
        if connection.execute("SELECT COUNT(*) FROM knowledge_points").fetchone()[0] == 0:
            seed_database(connection)


SEED_TOPICS = [
    ("java-equals", "Java 基础", "equals 与 hashCode", "理解对象相等性与哈希容器的约定。", []),
    ("java-string", "Java 基础", "String 不可变性", "区分字符串不可变性、拼接与可变构建器。", []),
    ("java-overload", "Java 基础", "重载与重写", "分辨编译期重载选择和运行时动态分派。", []),
    ("java-volatile", "并发与 JVM", "volatile 与可见性", "掌握可见性、happens-before 与复合原子性。", []),
    ("java-synchronized", "并发与 JVM", "synchronized", "理解监视器互斥和锁同步关系。", ["java-volatile"]),
    ("java-hashmap", "集合与数据结构", "HashMap 哈希与冲突", "理解桶定位、相等性判断与并发边界。", ["java-equals"]),
    ("java-arraylist", "集合与数据结构", "ArrayList 与 LinkedList", "按访问模式比较数组与链表。", []),
    ("java-jvm-memory", "并发与 JVM", "JVM 运行时数据区", "区分线程私有区与线程共享区。", []),
    ("java-gc", "并发与 JVM", "垃圾回收与可达性", "理解从 GC Roots 出发的可达性分析。", ["java-jvm-memory"]),
    ("tcp-handshake", "计算机网络", "TCP 三次握手", "理解 SYN、SYN-ACK、ACK 与连接状态。", []),
    ("http-https", "计算机网络", "HTTP 与 HTTPS", "区分应用协议与 TLS 提供的传输保护。", ["tcp-handshake"]),
    ("db-index", "数据库", "数据库索引", "理解索引收益、写入代价与多列前缀。", []),
    ("db-acid", "数据库", "事务 ACID", "解释事务属性及隔离级别的作用。", []),
]

SEED_QUESTIONS = [
    {
        "id": "q-equals-01", "point": "java-equals", "type": "single_choice",
        "stem": "两个对象通过 equals 判断相等时，hashCode 必须满足什么约定？",
        "options": ["hashCode 必须相同", "hashCode 必须不同", "两个对象的类型必须完全相同", "JVM 会自动合并这两个对象"],
        "answer": "hashCode 必须相同",
        "explanation": "equals 相等蕴含 hashCode 相同；hashCode 相同并不蕴含 equals 相等。",
        "rubric": [],
    },
    {
        "id": "q-equals-02", "point": "java-equals", "type": "short_answer",
        "stem": "为什么重写 equals 时通常也要重写 hashCode？",
        "options": [], "answer": "相等对象必须有相同哈希值；否则哈希集合可能按哈希桶找不到逻辑上相等的键。",
        "explanation": "HashMap、HashSet 等哈希容器先用哈希值定位，再用相等性比较。",
        "rubric": ["提到 equals 相等时 hashCode 必须相同", "说明哈希集合依赖哈希值定位", "说明违背约定会导致查找或去重异常"],
    },
    {
        "id": "q-string-01", "point": "java-string", "type": "single_choice",
        "stem": "在大量循环拼接字符串时，通常优先考虑哪种方式？",
        "options": ["每次都新建 String", "使用 StringBuilder", "把 String 转成数组后直接改字符", "依赖 String 的对象池"],
        "answer": "使用 StringBuilder",
        "explanation": "String 不可变，循环拼接会产生新的字符串结果；StringBuilder 可在缓冲区中累积内容。",
        "rubric": [],
    },
    {
        "id": "q-string-02", "point": "java-string", "type": "short_answer",
        "stem": "String 不可变带来什么好处？大量拼接时要注意什么？",
        "options": [], "answer": "不可变值可以安全共享，也适合作为哈希键；大量循环拼接可使用 StringBuilder。",
        "explanation": "把对象共享与重复创建新字符串这两个方面分开回答。",
        "rubric": ["提到对象创建后字符序列不能原地修改", "提到可安全共享或作为哈希键", "提到大量循环拼接适合 StringBuilder"],
    },
    {
        "id": "q-overload-01", "point": "java-overload", "type": "single_choice",
        "stem": "Java 重载方法的选择主要依据什么？",
        "options": ["运行时实际对象的类型", "编译期可见的参数类型", "方法体执行耗时", "方法返回值的内容"],
        "answer": "编译期可见的参数类型",
        "explanation": "重载根据方法名及参数列表区分，编译器基于调用点的参数类型选择匹配的方法。",
        "rubric": [],
    },
    {
        "id": "q-volatile-01", "point": "java-volatile", "type": "single_choice",
        "stem": "仅使用 volatile 保护共享变量 count 时，count++ 一定是原子操作吗？",
        "options": ["是，volatile 会锁住变量", "不是，它仍是读、改、写的复合操作", "只有在单核 CPU 上是", "只有使用 long 类型时是"],
        "answer": "不是，它仍是读、改、写的复合操作",
        "explanation": "volatile 保证该变量读写的可见性和相关顺序约束，但不会把复合读改写变成一个原子步骤。",
        "rubric": [],
    },
    {
        "id": "q-volatile-02", "point": "java-volatile", "type": "short_answer",
        "stem": "简述 volatile 能解决的问题，以及它不能解决的问题。",
        "options": [], "answer": "它保证对同一 volatile 变量的可见性并提供相应的 happens-before 关系，但不保证 count++ 这类复合操作的原子性。",
        "explanation": "把可见性/顺序约束与原子性区别开。",
        "rubric": ["说明 volatile 写与后续读取的可见性", "提到 happens-before 或顺序约束", "说明复合读改写仍不原子"],
    },
    {
        "id": "q-sync-01", "point": "java-synchronized", "type": "single_choice",
        "stem": "同一监视器上的 synchronized 解锁与后续加锁之间有什么关系？",
        "options": ["建立 happens-before 关系", "只保证线程优先级", "使两个线程运行在同一 CPU 核", "把所有对象字段都变成 volatile"],
        "answer": "建立 happens-before 关系",
        "explanation": "对同一监视器的解锁 happens-before 后续加锁，synchronized 还提供互斥。",
        "rubric": [],
    },
    {
        "id": "q-hashmap-01", "point": "java-hashmap", "type": "single_choice",
        "stem": "HashMap 遇到哈希冲突时，最终如何区分落入同一桶的不同键？",
        "options": ["只比较对象地址", "使用键的相等性判断", "丢弃后插入的键", "对每个键建立独立线程"],
        "answer": "使用键的相等性判断",
        "explanation": "哈希值用于桶定位；桶内还需要通过键的相等性比较确定具体映射。",
        "rubric": [],
    },
    {
        "id": "q-arraylist-01", "point": "java-arraylist", "type": "single_choice",
        "stem": "ArrayList 按索引读取通常较快，主要因为它基于什么结构？",
        "options": ["红黑树", "可增长数组", "双向链表", "哈希桶"],
        "answer": "可增长数组",
        "explanation": "数组支持按下标定位；中间插入或删除时则需要移动后续元素。",
        "rubric": [],
    },
    {
        "id": "q-jvm-01", "point": "java-jvm-memory", "type": "single_choice",
        "stem": "以下哪一项属于 JVM 的线程私有运行时数据区？",
        "options": ["Java 堆", "方法区", "程序计数器", "所有对象字段"],
        "answer": "程序计数器",
        "explanation": "程序计数器、Java 虚拟机栈和本地方法栈是线程私有；堆与方法区为共享区。",
        "rubric": [],
    },
    {
        "id": "q-gc-01", "point": "java-gc", "type": "single_choice",
        "stem": "可达性分析判断对象存活时，通常从哪里开始遍历引用关系？",
        "options": ["GC Roots", "磁盘临时目录", "所有类名的字典序", "已结束的线程列表"],
        "answer": "GC Roots",
        "explanation": "从 GC Roots 出发遍历可达对象；与局部变量离开源码作用域不是同一概念。",
        "rubric": [],
    },
    {
        "id": "q-tcp-01", "point": "tcp-handshake", "type": "single_choice",
        "stem": "TCP 三次握手中服务端的第二条消息通常是什么？",
        "options": ["FIN", "SYN-ACK", "RST", "仅 ACK"],
        "answer": "SYN-ACK",
        "explanation": "典型流程是客户端 SYN、服务端 SYN-ACK、客户端 ACK。",
        "rubric": [],
    },
    {
        "id": "q-https-01", "point": "http-https", "type": "single_choice",
        "stem": "HTTPS 在 HTTP 通信基础上主要增加了哪一层保护？",
        "options": ["TLS 传输保护", "数据库事务", "应用的角色权限", "请求参数自动校验"],
        "answer": "TLS 传输保护",
        "explanation": "HTTPS 使用 TLS 提供传输机密性、完整性和服务端身份验证；它不替代应用授权。",
        "rubric": [],
    },
    {
        "id": "q-index-01", "point": "db-index", "type": "single_choice",
        "stem": "数据库索引常见的代价是什么？",
        "options": ["占用存储，并增加写入维护成本", "自动保证所有查询都更快", "免除事务隔离", "阻止数据更新"],
        "answer": "占用存储，并增加写入维护成本",
        "explanation": "索引可减少候选数据范围，但需要额外存储，并在写入时维护结构。",
        "rubric": [],
    },
    {
        "id": "q-acid-01", "point": "db-acid", "type": "short_answer",
        "stem": "ACID 四个字母分别代表什么？它们描述的是哪类对象？",
        "options": [], "answer": "原子性、一致性、隔离性、持久性；它们描述数据库事务的性质。",
        "explanation": "逐一写出 Atomicity、Consistency、Isolation、Durability 的含义。",
        "rubric": ["写出原子性", "写出一致性", "写出隔离性", "写出持久性"],
    },
]


def seed_database(connection: sqlite3.Connection) -> None:
    stamp = iso_time(now_local())
    for point_id, domain, title, summary, prerequisites in SEED_TOPICS:
        connection.execute(
            "INSERT INTO knowledge_points(id, domain, title, summary, prerequisites_json, created_at) VALUES(?,?,?,?,?,?)",
            (point_id, domain, title, summary, json.dumps(prerequisites, ensure_ascii=False), stamp),
        )
    content = SEED_FILE.read_text(encoding="utf-8")
    material_id = "material-java-foundations"
    connection.execute(
        "INSERT INTO materials(id, filename, title, source_type, imported_at, content) VALUES(?,?,?,?,?,?)",
        (material_id, SEED_FILE.name, "Java 基础复习资料", "内置资料", stamp, content),
    )
    insert_chunks(connection, material_id, content)
    for question in SEED_QUESTIONS:
        connection.execute(
            """INSERT INTO questions(id, knowledge_point_id, question_type, stem, options_json,
               answer_json, explanation, rubric_json, source_material_id, status)
               VALUES(?,?,?,?,?,?,?,?,?, 'reviewed')""",
            (
                question["id"], question["point"], question["type"], question["stem"],
                json.dumps(question["options"], ensure_ascii=False),
                json.dumps(question["answer"], ensure_ascii=False), question["explanation"],
                json.dumps(question["rubric"], ensure_ascii=False), material_id,
            ),
        )


def insert_chunks(connection: sqlite3.Connection, material_id: str, content: str) -> int:
    parts: list[tuple[str, list[str]]] = []
    heading = "未分类"
    lines: list[str] = []
    fence: tuple[str, int] | None = None
    for line in content.splitlines():
        fence_match = re.match(r"^ {0,3}(`{3,}|~{3,})", line)
        if fence_match:
            marker = fence_match.group(1)
            if fence is None:
                fence = (marker[0], len(marker))
            elif marker[0] == fence[0] and len(marker) >= fence[1]:
                fence = None
            lines.append(line)
            continue
        heading_match = re.match(r"^ {0,3}#{1,6}[ \t]+(.+?)(?:[ \t]+#+)?[ \t]*$", line) if fence is None else None
        if heading_match:
            if any(item.strip() for item in lines):
                parts.append((heading, lines))
            heading = heading_match.group(1).strip()
            lines = []
        else:
            lines.append(line)
    if any(item.strip() for item in lines):
        parts.append((heading, lines))
    for index, (part_heading, part_lines) in enumerate(parts):
        text = "\n".join(part_lines).strip()
        if text:
            connection.execute(
                "INSERT INTO material_chunks(id, material_id, chunk_index, heading, content) VALUES(?,?,?,?,?)",
                (f"{material_id}:{index}", material_id, index, part_heading, text),
            )
    return len(parts)


def dict_row(row: sqlite3.Row | None) -> dict[str, Any] | None:
    return dict(row) if row is not None else None


def dashboard_data(connection: sqlite3.Connection) -> dict[str, Any]:
    current = iso_time(now_local())
    topics = connection.execute(
        """SELECT kp.id, kp.domain, kp.title, kp.summary, kp.prerequisites_json, kp.next_review_at,
                  COUNT(a.id) AS attempts,
                  COALESCE(SUM(a.is_correct),0) AS correct,
                  MAX(a.created_at) AS last_practiced
           FROM knowledge_points kp
           LEFT JOIN attempts a ON a.knowledge_point_id = kp.id
           GROUP BY kp.id"""
    ).fetchall()
    summary: list[dict[str, Any]] = []
    due: list[dict[str, Any]] = []
    for topic in topics:
        attempts = int(topic["attempts"])
        item = {
            "id": topic["id"], "domain": topic["domain"], "title": topic["title"],
            "summary": topic["summary"], "attempts": attempts,
            "correct": int(topic["correct"]),
            "accuracy": round(int(topic["correct"]) / attempts, 2) if attempts else None,
            "last_practiced": topic["last_practiced"], "next_review_at": topic["next_review_at"],
            "prerequisites": json.loads(topic["prerequisites_json"]),
        }
        summary.append(item)
        if topic["next_review_at"] is None or topic["next_review_at"] <= current:
            due.append(item)
    due.sort(key=lambda item: (item["next_review_at"] or "", item["attempts"], item["title"]))
    today_prefix = now_local().date().isoformat()
    studied_today = connection.execute(
        "SELECT COUNT(*) FROM attempts WHERE created_at LIKE ?", (today_prefix + "%",)
    ).fetchone()[0]
    total_attempts = connection.execute("SELECT COUNT(*) FROM attempts").fetchone()[0]
    return {
        "date": today_prefix, "studied_today": int(studied_today),
        "total_attempts": int(total_attempts), "due_count": len(due),
        "topic_count": len(summary), "due_topics": due[:6], "topics": summary,
        "recent_attempts": recent_attempts(connection, 5),
    }


def knowledge_data(connection: sqlite3.Connection) -> list[dict[str, Any]]:
    return dashboard_data(connection)["topics"]


def recent_attempts(connection: sqlite3.Connection, limit: int = 30) -> list[dict[str, Any]]:
    rows = connection.execute(
        """SELECT a.id, a.answer_json, a.score, a.is_correct, a.created_at,
                  q.stem, q.question_type, kp.title AS topic, kp.domain
           FROM attempts a
           JOIN questions q ON q.id = a.question_id
           JOIN knowledge_points kp ON kp.id = a.knowledge_point_id
            ORDER BY a.created_at DESC, a.rowid DESC LIMIT ?""",
        (max(1, min(int(limit), 100)),),
    ).fetchall()
    output = []
    for row in rows:
        answer = json.loads(row["answer_json"])
        output.append({
            "id": row["id"], "answer": answer, "score": row["score"],
            "is_correct": bool(row["is_correct"]), "created_at": row["created_at"],
            "stem": row["stem"], "question_type": row["question_type"],
            "topic": row["topic"], "domain": row["domain"],
        })
    return output


def next_question(connection: sqlite3.Connection, point_id: str | None = None) -> dict[str, Any]:
    params: list[Any] = [iso_time(now_local())]
    condition = ""
    if point_id:
        condition = "WHERE q.knowledge_point_id = ?"
        params.append(point_id)
    row = connection.execute(
        f"""SELECT q.*, kp.title AS topic_title, kp.domain, kp.summary AS topic_summary,
                     CASE
                     WHEN (kp.next_review_at IS NULL AND EXISTS (SELECT 1 FROM attempts a WHERE a.knowledge_point_id=kp.id))
                          OR julianday(kp.next_review_at) <= julianday(?) THEN 0
                     WHEN (SELECT a.is_correct FROM attempts a WHERE a.knowledge_point_id=kp.id
                           ORDER BY a.created_at DESC, a.rowid DESC LIMIT 1)=0 THEN 1
                     WHEN NOT EXISTS (SELECT 1 FROM attempts a WHERE a.knowledge_point_id=kp.id) THEN 2
                     ELSE 3
                   END AS review_priority
            FROM questions q JOIN knowledge_points kp ON kp.id = q.knowledge_point_id
            {condition}
            ORDER BY review_priority,
                     (SELECT COUNT(*) FROM attempts a WHERE a.question_id=q.id), q.id
            LIMIT 1""",
        params,
    ).fetchone()
    if row is None:
        raise ApiError(404, "question_not_found", "这个知识点暂时没有可练题目。")
    return {
        "id": row["id"], "knowledge_point_id": row["knowledge_point_id"],
        "topic_title": row["topic_title"], "domain": row["domain"],
        "topic_summary": row["topic_summary"], "question_type": row["question_type"],
        "stem": row["stem"], "options": json.loads(row["options_json"]),
        "source_type": "审核示例题", "status": row["status"],
        "reason": ["已到复习时间", "上次回答有遗漏，建议再练", "尚未练习这个知识点", "巩固已经练习过的知识点"][row["review_priority"]],
    }


def question_feedback(connection: sqlite3.Connection, question_id: str) -> dict[str, Any]:
    row = connection.execute(
        """SELECT q.*, kp.title AS topic_title FROM questions q
           JOIN knowledge_points kp ON kp.id=q.knowledge_point_id WHERE q.id=?""",
        (question_id,),
    ).fetchone()
    if row is None:
        raise ApiError(404, "question_not_found", "找不到这道题。")
    return {
        "answer": json.loads(row["answer_json"]), "explanation": row["explanation"],
        "rubric": json.loads(row["rubric_json"]), "topic_title": row["topic_title"],
    }


def submit_attempt(connection: sqlite3.Connection, payload: dict[str, Any]) -> dict[str, Any]:
    question_id = payload.get("question_id")
    if not isinstance(question_id, str) or not question_id.strip():
        raise ApiError(400, "invalid_question_id", "请选择要提交的题目。")
    row = connection.execute("SELECT * FROM questions WHERE id=?", (question_id,)).fetchone()
    if row is None:
        raise ApiError(404, "question_not_found", "找不到这道题。")
    expected = json.loads(row["answer_json"])
    rubric = json.loads(row["rubric_json"])
    hits = payload.get("rubric_hits", [])
    submitted = payload.get("answer")
    if not isinstance(submitted, str) or not submitted.strip() or len(submitted) > 8000:
        raise ApiError(400, "invalid_answer", "答案不能为空，且不能超过 8000 个字符。")
    if row["question_type"] == "single_choice":
        if submitted not in json.loads(row["options_json"]):
            raise ApiError(400, "invalid_answer", "请选择题目中的一个选项。")
        correct = submitted == expected
        score = 1.0 if correct else 0.0
        answer_value = submitted
        hits = []
    else:
        if not isinstance(hits, list) or len(hits) != len(rubric) or not all(isinstance(hit, bool) for hit in hits):
            raise ApiError(400, "invalid_rubric", "请按参考要点完成自评。")
        answer_value = submitted.strip()
        score = sum(hits) / len(rubric) if rubric else 0.0
        correct = score >= 0.999
    stamp = now_local()
    created_at = iso_time(stamp)
    connection.execute(
        """INSERT INTO attempts(id, question_id, knowledge_point_id, answer_json, score,
           is_correct, rubric_hits_json, created_at) VALUES(?,?,?,?,?,?,?,?)""",
        (str(uuid.uuid4()), question_id, row["knowledge_point_id"],
         json.dumps(answer_value, ensure_ascii=False), score, int(correct),
         json.dumps(hits), created_at),
    )
    streak = 0
    for prior in connection.execute(
        "SELECT is_correct FROM attempts WHERE knowledge_point_id=? ORDER BY created_at DESC, rowid DESC LIMIT 20",
        (row["knowledge_point_id"],),
    ).fetchall():
        if not prior["is_correct"]:
            break
        streak += 1
    review_hours = 4 if not correct else (24 if streak <= 1 else 72 if streak <= 3 else 168)
    review_at = iso_time(stamp + timedelta(hours=review_hours))
    connection.execute("UPDATE knowledge_points SET next_review_at=? WHERE id=?", (review_at, row["knowledge_point_id"]))
    feedback = question_feedback(connection, question_id)
    return {
        "correct": correct, "score": round(score, 2), "answer": feedback["answer"],
        "explanation": feedback["explanation"], "rubric": feedback["rubric"],
        "review_at": review_at, "topic_title": feedback["topic_title"],
        "streak": streak, "review_hours": review_hours,
    }


def material_list(connection: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = connection.execute(
        """SELECT m.id,m.filename,m.title,m.source_type,m.imported_at,
                  COUNT(c.id) AS chunk_count FROM materials m
           LEFT JOIN material_chunks c ON c.material_id=m.id
           GROUP BY m.id ORDER BY m.imported_at DESC"""
    ).fetchall()
    return [dict(row) for row in rows]


def resource_catalog() -> list[dict[str, str]]:
    """Read the curated public-resource index without copying third-party content."""
    try:
        raw = json.loads(RESOURCES_FILE.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ApiError(500, "resources_unreadable", "公开资源目录暂时无法读取。") from error
    if isinstance(raw, dict) and isinstance(raw.get("resources"), list):
        raw = raw["resources"]
    if not isinstance(raw, list):
        raise ApiError(500, "resources_invalid", "公开资源目录格式无效。")
    resources: list[dict[str, str]] = []
    for item in raw:
        if not isinstance(item, dict):
            raise ApiError(500, "resources_invalid", "公开资源目录包含无效条目。")
        required = ("id", "track", "title", "kind", "description", "url", "license", "source")
        if any(not isinstance(item.get(key), str) or not item[key].strip() for key in required):
            raise ApiError(500, "resources_invalid", "公开资源目录缺少必要字段。")
        parsed_url = urllib.parse.urlsplit(item["url"])
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            raise ApiError(500, "resources_invalid", "公开资源目录包含无效链接。")
        resources.append({key: item[key].strip() for key in required})
    return resources


def import_material(connection: sqlite3.Connection, filename: str, content: str) -> dict[str, Any]:
    if not isinstance(filename, str) or not isinstance(content, str):
        raise ApiError(400, "invalid_material", "文件名和 content 必须是文本。")
    try:
        filename.encode("utf-8")
        content_size = len(content.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise ApiError(400, "invalid_material", "资料包含无效的 Unicode 字符。") from error
    safe_name = Path(filename.replace("\\", "/")).name[:120]
    if Path(safe_name).suffix.lower() not in {".md", ".markdown", ".txt"}:
        raise ApiError(400, "unsupported_file", "目前支持 Markdown 和纯文本资料。")
    if not content.strip():
        raise ApiError(400, "empty_material", "文件内容为空。")
    if content_size > MAX_BODY_BYTES:
        raise ApiError(413, "material_too_large", "单份资料不能超过 1 MB。")
    material_id = f"material-{uuid.uuid4().hex}"
    title = Path(safe_name).stem.replace("_", " ").replace("-", " ")[:120]
    stamp = iso_time(now_local())
    connection.execute(
        "INSERT INTO materials(id,filename,title,source_type,imported_at,content) VALUES(?,?,?,?,?,?)",
        (material_id, safe_name, title, "用户导入", stamp, content),
    )
    count = insert_chunks(connection, material_id, content)
    if count == 0:
        connection.execute("DELETE FROM materials WHERE id=?", (material_id,))
        raise ApiError(400, "empty_material", "资料需要至少包含一段正文。")
    return {"id": material_id, "filename": safe_name, "title": title, "chunk_count": count, "imported_at": stamp}


def material_details(connection: sqlite3.Connection, material_id: str) -> dict[str, Any]:
    material = connection.execute("SELECT * FROM materials WHERE id=?", (material_id,)).fetchone()
    if material is None:
        raise ApiError(404, "material_not_found", "找不到这份资料。")
    chunks = connection.execute(
        "SELECT id,chunk_index,heading,content FROM material_chunks WHERE material_id=? ORDER BY chunk_index",
        (material_id,),
    ).fetchall()
    return {"material": dict(material), "chunks": [dict(chunk) for chunk in chunks]}


def delete_material(connection: sqlite3.Connection, material_id: str) -> dict[str, Any]:
    material = connection.execute("SELECT source_type FROM materials WHERE id=?", (material_id,)).fetchone()
    if material is None:
        raise ApiError(404, "material_not_found", "找不到这份资料。")
    if material["source_type"] != "用户导入":
        raise ApiError(403, "protected_material", "内置资料不能删除。")
    if connection.execute("SELECT 1 FROM questions WHERE source_material_id=? LIMIT 1", (material_id,)).fetchone():
        raise ApiError(409, "material_in_use", "这份资料仍被题目引用，不能删除。")
    connection.execute("DELETE FROM materials WHERE id=?", (material_id,))
    return {"deleted": True, "id": material_id}


def search_materials(connection: sqlite3.Connection, query: str, limit: int = 6) -> list[dict[str, Any]]:
    normalized = query.casefold().strip()
    if not normalized:
        return []
    rows = connection.execute(
        """SELECT c.id,c.heading,c.content,m.id AS material_id,m.filename,m.title,m.source_type
           FROM material_chunks c JOIN materials m ON m.id=c.material_id"""
    ).fetchall()
    latin = set(re.findall(r"[a-z0-9_+#.]{2,}", normalized))
    latin -= {"what", "is", "are", "how", "why", "the", "does", "explain", "of", "to", "and"}
    chinese_blocks = re.findall(r"[\u3400-\u9fff]+", normalized)
    chinese = {block[i:i + 2] for block in chinese_blocks for i in range(max(1, len(block) - 1))}
    chinese -= {"什么", "如何", "为何", "怎样", "怎么", "请问", "介绍", "一下", "解释", "简述"}
    terms = latin | chinese
    if not terms:
        terms = {normalized}
    scored: list[dict[str, Any]] = []
    for row in rows:
        text = f"{row['heading']}\n{row['content']}".casefold()
        matched = [term for term in terms if term in text]
        if not matched:
            continue
        score = sum(min(text.count(term), 3) for term in matched)
        scored.append({
            "id": row["id"], "material_id": row["material_id"], "filename": row["filename"],
            "title": row["title"], "source_type": row["source_type"], "heading": row["heading"],
            "content": row["content"], "score": score,
        })
    scored.sort(key=lambda item: (-item["score"], item["filename"], item["heading"]))
    return scored[: max(1, min(limit, 10))]


def toml_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def validate_base_url(value: Any) -> str:
    if not isinstance(value, str) or len(value) > 500:
        raise ApiError(400, "invalid_config", "Provider 的 base_url 无效。")
    value = value.strip()
    if "\\" in value or any(character.isspace() or ord(character) < 32 or ord(character) == 127 for character in value):
        raise ApiError(400, "invalid_config", "base_url 不能包含空白或控制字符。")
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise ApiError(400, "invalid_config", "Provider 的地址或端口无效。") from error
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username is not None or parsed.password is not None or port == 0:
        raise ApiError(400, "invalid_config", "base_url 需要是有效的 http/https 地址，且不能包含凭据。")
    if parsed.query or parsed.fragment:
        raise ApiError(400, "invalid_config", "请不要把密钥放进 base_url 的 query 或 fragment。")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))


def normalize_config(parsed: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(parsed, dict):
        raise ApiError(400, "invalid_config", "配置必须是 TOML 表。")
    model = parsed.get("model", "")
    selected_provider = parsed.get("model_provider", "")
    if not isinstance(model, str) or len(model) > 160:
        raise ApiError(400, "invalid_config", "model 必须是短文本。")
    if not isinstance(selected_provider, str) or len(selected_provider) > 64:
        raise ApiError(400, "invalid_config", "model_provider 必须是短文本。")
    provider_tables = parsed.get("model_providers", {})
    if not isinstance(provider_tables, dict):
        raise ApiError(400, "invalid_config", "model_providers 必须是 TOML 表。")
    providers: dict[str, dict[str, Any]] = {}
    for provider_id, raw in provider_tables.items():
        if not isinstance(provider_id, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", provider_id) or not isinstance(raw, dict):
            raise ApiError(400, "invalid_config", "Provider 名称或配置无效。")
        name = raw.get("name", provider_id)
        if not isinstance(name, str) or len(name) > 100:
            raise ApiError(400, "invalid_config", "Provider name 必须是短文本。")
        base_url = validate_base_url(raw.get("base_url", ""))
        wire_api = raw.get("wire_api", "responses")
        if wire_api == "chat":
            wire_api = "chat_completions"
        if not isinstance(wire_api, str) or wire_api not in {"responses", "chat_completions"}:
            raise ApiError(400, "invalid_config", "wire_api 支持 responses 或 chat_completions。")
        env_key = raw.get("env_key")
        if "requires_openai_auth" in raw and not isinstance(raw["requires_openai_auth"], bool):
            raise ApiError(400, "invalid_config", "requires_openai_auth 必须是布尔值。")
        if env_key is None and raw.get("requires_openai_auth", False):
            env_key = "OPENAI_API_KEY"
        env_key = "" if env_key is None else env_key
        if not isinstance(env_key, str) or (env_key and not re.fullmatch(r"[A-Z_][A-Z0-9_]{0,63}", env_key)):
            raise ApiError(400, "invalid_config", "env_key 应为环境变量名称，例如 OPENAI_API_KEY。")
        models_path = raw.get("models_path", "/models")
        if not isinstance(models_path, str) or len(models_path) > 500 or not models_path.startswith("/") or models_path.startswith("//") or any(
            character in "?#\\" or character.isspace() or ord(character) < 32 or ord(character) == 127
            for character in urllib.parse.unquote(models_path)
        ) or ".." in urllib.parse.unquote(models_path):
            raise ApiError(400, "invalid_config", "models_path 必须是以 / 开始的站内路径。")
        timeout = raw.get("timeout_seconds", 15)
        if isinstance(timeout, bool) or not isinstance(timeout, int) or not 2 <= timeout <= 120:
            raise ApiError(400, "invalid_config", "timeout_seconds 范围为 2 到 120 秒。")
        providers[provider_id] = {
            "name": name, "base_url": base_url, "wire_api": wire_api,
            "env_key": env_key, "models_path": models_path, "timeout_seconds": timeout,
        }
    if selected_provider and selected_provider not in providers:
        raise ApiError(400, "invalid_config", "选中的 model_provider 不在配置列表中。")
    return {"model": model, "model_provider": selected_provider, "model_providers": providers}


def serialize_config(config: dict[str, Any]) -> str:
    lines = [f"model = {toml_string(config['model'])}", f"model_provider = {toml_string(config['model_provider'])}"]
    for provider_id, provider in config["model_providers"].items():
        lines.extend([
            "", f"[model_providers.{provider_id}]",
            f"name = {toml_string(provider['name'])}",
            f"base_url = {toml_string(provider['base_url'])}",
            f"wire_api = {toml_string(provider['wire_api'])}",
            f"env_key = {toml_string(provider['env_key'])}",
            f"models_path = {toml_string(provider['models_path'])}",
            f"timeout_seconds = {provider['timeout_seconds']}",
        ])
    return "\n".join(lines) + "\n"


def parse_config_text(raw: str) -> dict[str, Any]:
    if not isinstance(raw, str):
        raise ApiError(400, "invalid_config", "toml 必须是文本。")
    try:
        raw_size = len(raw.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise ApiError(400, "invalid_config", "模型配置包含无效的 Unicode 字符。") from error
    if raw_size > 100_000:
        raise ApiError(413, "invalid_config", "模型配置不能超过 100 KB。")
    try:
        parsed = tomllib.loads(raw)
    except tomllib.TOMLDecodeError as error:
        raise ApiError(400, "invalid_toml", f"TOML 格式有误：{error}") from error
    return normalize_config(parsed)


def load_config() -> tuple[dict[str, Any], str]:
    path = settings_path()
    if not path.exists():
        empty = normalize_config({})
        return empty, serialize_config(empty)
    try:
        raw = path.read_text(encoding="utf-8")
        parsed = tomllib.loads(raw)
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as error:
        raise ApiError(500, "config_unreadable", "设置文件无法读取；请在设置中修复 TOML。") from error
    config = normalize_config(parsed)
    return config, serialize_config(config)


def config_public(config: dict[str, Any]) -> dict[str, Any]:
    providers = []
    for provider_id, provider in config["model_providers"].items():
        providers.append({
            "id": provider_id, "name": provider["name"], "base_url": provider["base_url"],
            "wire_api": provider["wire_api"], "env_key": provider["env_key"],
            "models_path": provider["models_path"],
            "timeout_seconds": provider["timeout_seconds"],
            "has_api_key": bool(provider["env_key"] and os.environ.get(provider["env_key"])),
        })
    return {"model": config["model"], "model_provider": config["model_provider"], "providers": providers}


def import_codex_config() -> str:
    codex_home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")).expanduser()
    path = codex_home / "config.toml"
    try:
        original = tomllib.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ApiError(404, "codex_config_not_found", "没有找到本机 Codex config.toml。") from error
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as error:
        raise ApiError(400, "codex_config_invalid", "本机 Codex TOML 配置无法解析。") from error
    safe: dict[str, Any] = {"model": original.get("model", ""), "model_provider": original.get("model_provider", ""), "model_providers": {}}
    provider_tables = original.get("model_providers", {})
    if not isinstance(provider_tables, dict) or not isinstance(safe["model_provider"], str):
        raise ApiError(400, "codex_config_invalid", "本机 Codex Provider 配置无效。")
    if safe["model_provider"] in provider_tables:
        provider_id = safe["model_provider"]
        raw = provider_tables[provider_id]
        if not isinstance(raw, dict):
            raise ApiError(400, "codex_config_invalid", "本机 Codex Provider 配置无效。")
        provider = {
            "name": raw.get("name", provider_id), "base_url": raw.get("base_url", ""),
            "wire_api": raw.get("wire_api", "responses"), "models_path": "/models",
            "timeout_seconds": 15,
        }
        if raw.get("env_key"):
            provider["env_key"] = raw["env_key"]
        elif raw.get("requires_openai_auth", False):
            provider["env_key"] = "OPENAI_API_KEY"
        safe["model_providers"][provider_id] = provider
    config = normalize_config(safe)
    return serialize_config(config)


def endpoint_url(provider: dict[str, Any], path: str) -> str:
    base = urllib.parse.urlsplit(provider["base_url"])
    clean_path = path.lstrip("/")
    joined = (base.path.rstrip("/") + "/" + clean_path).replace("//", "/")
    return urllib.parse.urlunsplit((base.scheme, base.netloc, joined, base.query, ""))


def provider_api_key(provider: dict[str, Any]) -> str:
    key_name = provider.get("env_key", "")
    return os.environ.get(key_name, "") if key_name else ""


class ProviderRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: Any, file: Any, code: int, message: str, headers: Any, new_url: str) -> None:
        raise ApiError(502, "provider_redirect", "模型接口发生重定向，请在设置中填写最终网关地址。")


def provider_urlopen(request: urllib.request.Request, timeout: int) -> Any:
    # Never forward provider credentials to a redirected endpoint.
    return urllib.request.build_opener(ProviderRedirectHandler()).open(request, timeout=timeout)


def fetch_models(provider_id: str, refresh: bool = False) -> list[str]:
    config, _ = load_config()
    provider = config["model_providers"].get(provider_id)
    if provider is None:
        raise ApiError(404, "provider_not_found", "找不到这个模型 Provider。")
    cache_key = provider_id + ":" + provider["base_url"] + ":" + provider["models_path"] + ":" + provider["env_key"]
    with MODEL_CACHE_LOCK:
        cached = MODEL_CACHE.get(cache_key)
        if not refresh and cached and time.monotonic() - cached[0] < MODEL_CACHE_SECONDS:
            return cached[1]
    api_key = provider_api_key(provider)
    if provider["env_key"] and not api_key:
        raise ApiError(400, "missing_api_key", f"请先设置环境变量 {provider['env_key']}。")
    request = urllib.request.Request(endpoint_url(provider, provider["models_path"]), headers={"Accept": "application/json"})
    if api_key:
        request.add_header("Authorization", f"Bearer {api_key}")
    try:
        with provider_urlopen(request, timeout=provider["timeout_seconds"]) as response:
            body = response.read(2_000_001)
            if len(body) > 2_000_000:
                raise ApiError(502, "catalog_too_large", "模型列表响应过大。")
            decoded = json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code in {401, 403}:
            raise ApiError(401, "provider_auth", "网关拒绝了当前凭据，请检查 API Key 和访问权限。") from error
        if error.code == 404:
            raise ApiError(502, "models_endpoint_not_found", "模型列表接口返回 404；请检查 base_url 是否包含正确的 /v1 路径。") from error
        if error.code == 429:
            raise ApiError(429, "provider_rate_limit", "网关暂时限流，请稍后刷新模型列表。") from error
        raise ApiError(502, "provider_unavailable", f"模型列表请求失败（HTTP {error.code}）。") from error
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as error:
        raise ApiError(502, "provider_unavailable", "无法连接模型网关，请检查地址和网络。") from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ApiError(502, "invalid_catalog", "网关返回的模型列表不是有效 JSON。") from error
    values = decoded.get("data", decoded.get("models")) if isinstance(decoded, dict) else decoded
    if not isinstance(values, list):
        raise ApiError(502, "invalid_catalog", "模型列表应为数组或包含 data 数组的 JSON。")
    model_ids = [entry.get("id") if isinstance(entry, dict) else entry for entry in values]
    models = sorted({model.strip() for model in model_ids if isinstance(model, str) and model.strip() and len(model.strip()) <= 160})[:500]
    if not models:
        raise ApiError(502, "empty_catalog", "网关没有返回可用模型；可以在配置里手动填写模型名。")
    with MODEL_CACHE_LOCK:
        MODEL_CACHE[cache_key] = (time.monotonic(), models)
    return models


def read_model_text(payload: dict[str, Any]) -> str:
    config, _ = load_config()
    provider_id = config["model_provider"]
    provider = config["model_providers"].get(provider_id)
    model = payload.get("model") or config["model"]
    question = payload.get("question", "")
    if not isinstance(model, str) or len(model) > 160:
        raise ApiError(400, "invalid_model", "模型名必须是短文本。")
    if not provider or not model.strip():
        raise ApiError(400, "model_not_configured", "先在模型设置中配置 Provider 和模型。")
    if not isinstance(question, str) or not question.strip() or len(question) > 4000:
        raise ApiError(400, "invalid_question", "问题不能为空，且不能超过 4000 个字符。")
    question = question.strip()
    api_key = provider_api_key(provider)
    if provider["env_key"] and not api_key:
        raise ApiError(400, "missing_api_key", f"请先设置环境变量 {provider['env_key']}。")
    passages = payload.get("passages", [])
    if not isinstance(passages, list) or not passages:
        raise ApiError(400, "no_sources", "资料库里没有找到相关内容；请先导入参考资料。")
    passages = [item for item in passages if isinstance(item, dict) and isinstance(item.get("content"), str) and item["content"].strip()][:6]
    if not passages:
        raise ApiError(400, "no_sources", "资料库里没有找到相关内容；请先导入参考资料。")
    source_text = "\n\n".join(
        f"[{index + 1}] {str(item.get('title',''))} / {str(item.get('heading',''))}\n{str(item.get('content',''))[:5000]}"
        for index, item in enumerate(passages)
    )
    instructions = (
        "你是 DevPrep 的计算机学习辅导员。只根据给出的资料回答；资料不足时明确说资料中没有证据。"
        "简洁解释概念、边界和常见误区。用 [1]、[2] 标注支持结论的资料编号，不得虚构来源。"
    )
    if provider["wire_api"] == "responses":
        url = endpoint_url(provider, "/responses")
        request_body: dict[str, Any] = {
            "model": model, "instructions": instructions,
            "input": f"参考资料：\n{source_text}\n\n学习者问题：{question}",
            "store": False,
        }
    else:
        url = endpoint_url(provider, "/chat/completions")
        request_body = {
            "model": model, "stream": False,
            "messages": [
                {"role": "system", "content": instructions},
                {"role": "user", "content": f"参考资料：\n{source_text}\n\n学习者问题：{question}"},
            ],
        }
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(url, data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"), headers=headers, method="POST")
    try:
        with provider_urlopen(request, timeout=provider["timeout_seconds"]) as response:
            body = response.read(2_000_001)
            if len(body) > 2_000_000:
                raise ApiError(502, "response_too_large", "模型回复超过允许大小。")
            result = json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code in {401, 403}:
            raise ApiError(401, "provider_auth", "模型网关拒绝了当前凭据。") from error
        if error.code == 429:
            raise ApiError(429, "provider_rate_limit", "模型网关暂时限流，请稍后再试。") from error
        raise ApiError(502, "provider_unavailable", f"模型生成失败（HTTP {error.code}）。") from error
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as error:
        raise ApiError(502, "provider_unavailable", "无法连接模型网关，请检查地址和网络。") from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ApiError(502, "invalid_response", "模型网关返回的内容无法解析。") from error
    if not isinstance(result, dict):
        raise ApiError(502, "invalid_response", "模型网关返回的 JSON 结构无效。")
    if result.get("error") or result.get("status") in ("failed", "cancelled"):
        raise ApiError(502, "provider_generation_failed", "模型生成未成功，请检查模型名或稍后重试。")
    if result.get("status") == "incomplete":
        raise ApiError(502, "incomplete_response", "模型回复未完成，请重试或更换模型。")
    if provider["wire_api"] == "responses":
        text = result.get("output_text")
        if not text:
            text_parts = []
            outputs = result.get("output", [])
            if not isinstance(outputs, list):
                raise ApiError(502, "invalid_response", "模型输出应为数组。")
            for output in outputs:
                content = output.get("content", []) if isinstance(output, dict) else []
                if not isinstance(content, list):
                    raise ApiError(502, "invalid_response", "模型文本结构无效。")
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "refusal":
                        raise ApiError(422, "provider_refusal", "模型拒绝生成该回答，请调整问题。")
                    if isinstance(item, dict) and item.get("type") in {"output_text", "text"}:
                        if not isinstance(item.get("text"), str):
                            raise ApiError(502, "invalid_response", "模型文本结构无效。")
                        text_parts.append(item["text"])
            text = "\n".join(text_parts)
    else:
        choices = result.get("choices", [])
        if not isinstance(choices, list) or (choices and not isinstance(choices[0], dict)):
            raise ApiError(502, "invalid_response", "模型 choices 结构无效。")
        choice = choices[0] if choices else {}
        if choice.get("finish_reason") == "length":
            raise ApiError(502, "incomplete_response", "模型回复被长度限制截断，请重试或更换模型。")
        message = choice.get("message", {})
        if not isinstance(message, dict):
            raise ApiError(502, "invalid_response", "模型 message 结构无效。")
        if message.get("refusal"):
            raise ApiError(422, "provider_refusal", "模型拒绝生成该回答，请调整问题。")
        text = message.get("content", "")
        if isinstance(text, list):
            text_parts = [item.get("text", "") for item in text if isinstance(item, dict)]
            if not all(isinstance(item, str) for item in text_parts):
                raise ApiError(502, "invalid_response", "模型文本结构无效。")
            text = "\n".join(text_parts)
    if not isinstance(text, str) or not text.strip():
        raise ApiError(502, "empty_response", "模型没有返回可显示的文本。")
    try:
        text.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ApiError(502, "invalid_response", "模型回复包含无效的 Unicode 字符。") from error
    return text.strip()


def answer_query(connection: sqlite3.Connection, payload: dict[str, Any]) -> dict[str, Any]:
    query = payload.get("question", "")
    if not isinstance(query, str) or not query.strip() or len(query) > 4000:
        raise ApiError(400, "invalid_question", "问题不能为空，且不能超过 4000 个字符。")
    query = query.strip()
    passages = search_materials(connection, query)
    if not passages:
        return {
            "answer": "资料库中没有找到足够相关的内容。请导入课程笔记或限定问题范围后再试。",
            "citations": [], "model_used": None, "grounded": False,
        }
    try:
        answer = read_model_text({"question": query, "passages": passages})
        model_used = load_config()[0]["model"]
        citation_numbers = [int(number) for number in re.findall(r"\[(\d{1,3})\]", answer)]
        grounded = bool(citation_numbers) and all(1 <= number <= len(passages) for number in citation_numbers)
    except ApiError as error:
        if error.code != "model_not_configured":
            raise
        answer = "当前模型尚未配置。先阅读下方命中的原文资料；配置模型后可以生成带来源编号的解释。"
        model_used = None
        grounded = False
    citations = [
        {"id": item["id"], "material_id": item["material_id"], "title": item["title"],
         "heading": item["heading"], "source_type": item["source_type"],
         "excerpt": item["content"][:900], "score": item["score"]}
        for item in passages
    ]
    return {"answer": answer, "citations": citations, "model_used": model_used, "grounded": grounded}


class DevPrepHandler(BaseHTTPRequestHandler):
    server_version = "DevPrepLocal/0.1"

    def log_message(self, format: str, *args: Any) -> None:
        # Do not log request bodies, query values, provider URLs, or authorization headers.
        sys.stderr.write(f"{self.log_date_time_string()} {self.command} {self.path.split('?')[0]}\n")

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(encoded)

    def check_local_origin(self) -> None:
        try:
            host = urllib.parse.urlsplit("http://" + self.headers.get("Host", "")).hostname
        except ValueError as error:
            raise ApiError(403, "invalid_host", "请求主机无效。") from error
        if host not in {"127.0.0.1", "localhost", "::1"}:
            raise ApiError(403, "invalid_host", "本地复习服务只接受本机主机名。")
        origin = self.headers.get("Origin")
        if not origin:
            return
        try:
            parsed_origin = urllib.parse.urlsplit(origin)
            hostname = parsed_origin.hostname
        except ValueError as error:
            raise ApiError(403, "invalid_origin", "请求来源无效。") from error
        if parsed_origin.scheme not in {"http", "https"} or hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ApiError(403, "invalid_origin", "本地复习服务只接受本机浏览器请求。")

    def read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ApiError(400, "invalid_body_size", "Content-Length 必须是数字。") from error
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ApiError(413, "invalid_body_size", "请求内容为空或超过 1 MB。")
        try:
            decoded = json.loads(self.rfile.read(length).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise ApiError(400, "invalid_json", "请求内容不是有效 JSON。") from error
        if not isinstance(decoded, dict):
            raise ApiError(400, "invalid_json", "请求内容必须是 JSON 对象。")
        try:
            json.dumps(decoded, ensure_ascii=False).encode("utf-8")
        except UnicodeEncodeError as error:
            raise ApiError(400, "invalid_json", "请求包含无效的 Unicode 字符。") from error
        return decoded

    def do_GET(self) -> None:
        try:
            self.check_local_origin()
            parsed = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(parsed.query)
            path = parsed.path
            if path == "/api/health":
                return self.send_json(200, {"ok": True, "version": "0.1.0"})
            if path == "/api/dashboard":
                with connect_db() as connection:
                    return self.send_json(200, dashboard_data(connection))
            if path == "/api/knowledge":
                with connect_db() as connection:
                    return self.send_json(200, {"topics": knowledge_data(connection)})
            if path == "/api/practice/next":
                point_id = query.get("knowledge_point_id", [None])[0]
                with connect_db() as connection:
                    return self.send_json(200, next_question(connection, point_id))
            if path == "/api/practice/reference":
                question_id = query.get("question_id", [""])[0]
                with connect_db() as connection:
                    return self.send_json(200, question_feedback(connection, question_id))
            if path == "/api/materials":
                with connect_db() as connection:
                    return self.send_json(200, {"materials": material_list(connection)})
            if path == "/api/resources":
                return self.send_json(200, {"resources": resource_catalog()})
            if path.startswith("/api/materials/"):
                material_id = urllib.parse.unquote(path.removeprefix("/api/materials/"))
                with connect_db() as connection:
                    return self.send_json(200, material_details(connection, material_id))
            if path == "/api/history":
                try:
                    limit = int(query.get("limit", [30])[0])
                except ValueError as error:
                    raise ApiError(400, "invalid_limit", "limit 必须是数字。") from error
                with connect_db() as connection:
                    return self.send_json(200, {"attempts": recent_attempts(connection, limit)})
            if path == "/api/settings":
                config, raw = load_config()
                return self.send_json(200, {"toml": raw, **config_public(config)})
            if path == "/api/models":
                provider_id = query.get("provider", [""])[0]
                refresh = query.get("refresh", ["0"])[0] == "1"
                return self.send_json(200, {"provider": provider_id, "models": fetch_models(provider_id, refresh)})
            return self.serve_static(path)
        except ApiError as error:
            self.send_json(error.status, {"error": {"code": error.code, "message": error.message}})
        except (sqlite3.Error, OSError) as error:
            print(f"request failed: {type(error).__name__}", file=sys.stderr)
            self.send_json(500, {"error": {"code": "internal_error", "message": "本地数据暂时不可用。"}})

    def do_POST(self) -> None:
        try:
            self.check_local_origin()
            path = urllib.parse.urlsplit(self.path).path
            payload = self.read_json()
            if path == "/api/attempts":
                with connect_db() as connection:
                    feedback = submit_attempt(connection, payload)
                return self.send_json(201, feedback)
            if path == "/api/materials/import":
                filename = payload.get("filename", "study.md")
                content = payload.get("content")
                if not isinstance(content, str):
                    raise ApiError(400, "invalid_material", "content 必须是文本。")
                with connect_db() as connection:
                    material = import_material(connection, filename, content)
                return self.send_json(201, {"material": material})
            if path == "/api/qa":
                with connect_db() as connection:
                    return self.send_json(200, answer_query(connection, payload))
            if path == "/api/settings":
                raw = payload.get("toml")
                if not isinstance(raw, str):
                    raise ApiError(400, "invalid_config", "toml 必须是文本。")
                config = parse_config_text(raw)
                normalized = serialize_config(config)
                target = settings_path()
                target.parent.mkdir(parents=True, exist_ok=True)
                temp_path = target.with_name(target.name + "." + uuid.uuid4().hex + ".tmp")
                temp_path.write_text(normalized, encoding="utf-8")
                os.replace(temp_path, target)
                with MODEL_CACHE_LOCK:
                    MODEL_CACHE.clear()
                return self.send_json(200, {"toml": normalized, **config_public(config)})
            if path == "/api/settings/import-codex":
                return self.send_json(200, {"toml": import_codex_config()})
            return self.send_json(404, {"error": {"code": "not_found", "message": "接口不存在。"}})
        except ApiError as error:
            self.send_json(error.status, {"error": {"code": error.code, "message": error.message}})
        except (sqlite3.Error, OSError) as error:
            print(f"request failed: {type(error).__name__}", file=sys.stderr)
            self.send_json(500, {"error": {"code": "internal_error", "message": "本地数据暂时不可用。"}})

    def do_DELETE(self) -> None:
        try:
            self.check_local_origin()
            path = urllib.parse.urlsplit(self.path).path
            if path.startswith("/api/materials/"):
                material_id = urllib.parse.unquote(path.removeprefix("/api/materials/"))
                with connect_db() as connection:
                    result = delete_material(connection, material_id)
                return self.send_json(200, result)
            self.send_json(404, {"error": {"code": "not_found", "message": "接口不存在。"}})
        except ApiError as error:
            self.send_json(error.status, {"error": {"code": error.code, "message": error.message}})
        except (sqlite3.Error, OSError) as error:
            print(f"request failed: {type(error).__name__}", file=sys.stderr)
            self.send_json(500, {"error": {"code": "internal_error", "message": "本地数据暂时不可用。"}})

    def serve_static(self, request_path: str) -> None:
        dist = ROOT / "dist"
        if not dist.exists():
            return self.send_json(404, {"error": {"code": "frontend_not_built", "message": "先运行 npm run build，或用 npm run dev 启动开发界面。"}})
        decoded_path = urllib.parse.unquote(request_path).lstrip("/")
        requested = (dist / decoded_path).resolve() if decoded_path else dist / "index.html"
        try:
            requested.relative_to(dist.resolve())
        except ValueError:
            return self.send_json(403, {"error": {"code": "invalid_path", "message": "路径无效。"}})
        if not requested.is_file():
            requested = dist / "index.html"
        if not requested.is_file():
            return self.send_json(404, {"error": {"code": "not_found", "message": "页面不存在。"}})
        body = requested.read_bytes()
        self.send_response(200)
        content_type = mimetypes.guess_type(requested.name)[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type in {"application/javascript", "application/json"}:
            content_type += "; charset=utf-8"
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store" if requested.name == "index.html" else "public, max-age=31536000, immutable")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_json(405, {"error": {"code": "method_not_allowed", "message": "不支持该请求方式。"}})


def serve(host: str | None = None, port: int | None = None) -> None:
    initialize_database()
    bind_host = host or os.environ.get("DEVPREP_HOST", "127.0.0.1")
    bind_port = port or int(os.environ.get("DEVPREP_PORT", "8765"))
    server = ThreadingHTTPServer((bind_host, bind_port), DevPrepHandler)
    server.daemon_threads = True
    print(f"DevPrep AI ready at http://{bind_host}:{bind_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
