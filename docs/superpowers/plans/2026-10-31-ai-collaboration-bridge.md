# AI Collaboration Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local multi-agent collaboration system enabling OpenCode and Claude Code to work together through a web UI with shared memory, real-time communication, and security controls.

**Architecture:** Python async backend (aiohttp + aiosqlite) with WebSocket communication. Agents invoked via CLI subprocesses with JSON I/O. Single-page vanilla HTML/CSS/JS frontend served by backend. SQLite for persistence.

**Tech Stack:** Python 3.11, aiohttp, aiosqlite, asyncio-subprocess; Vanilla HTML/CSS/JS frontend; OpenCode CLI, Claude Code CLI

**Spec:** C:\Users\Fernando\Desktop\NanatsuCore\AI_COLLABORATION_BRIDGE_DESIGN.md

## Global Constraints

- Python 3.11+, Node.js 26+, OpenCode v2.2.5+, Claude Code v2.1.314+
- Windows-compatible paths and subprocess handling
- No external API dependencies - uses existing CLI authentication
- Path confinement: agents restricted to project working directory
- Git-backed changes with diff recording
- Approval required for destructive operations
- Configurable limits: max rounds (default 10), agent timeout (default 5min), task timeout (default 30min), max messages (default 50)
- WebSocket on port 8765 (configurable)

## Review Focus

1. **Agent CLI failures** - subprocess timeouts, non-zero exits, malformed JSON → graceful error capture, retry logic, user notification
2. **Path traversal attempts** - agents requesting files outside project dir → validation rejects, logs attempt, continues safely
3. **WebSocket disconnect/reconnect** - client drops during active task → message queue persists, state recovers on reconnect
4. **Concurrent task conflicts** - multiple tasks on same project → SQLite transactions, file locking, sequential execution per project
5. **Large output handling** - agent returns massive JSON → streaming parse, size limits, truncation with warning

---

### Task 1: Project Structure & Database Schema

**Files:**
- Create: `src/db/schema.sql`
- Create: `src/db/database.py`
- Create: `src/models/__init__.py`
- Create: `src/models/project.py`
- Create: `src/models/conversation.py`
- Create: `src/models/message.py`
- Create: `src/models/task.py`
- Create: `src/models/evidence.py`
- Create: `src/models/decision.py`
- Create: `src/models/modification.py`
- Create: `src/models/error.py`
- Create: `src/models/test_run.py`
- Create: `src/models/agent_session.py`

**Interfaces:**
- Produces: `Database` class with `initialize()`, `get_connection()`; model classes with CRUD methods

- [ ] **Step 1: Write failing test for database initialization**

```python
# tests/test_database.py
import pytest
import aiosqlite
from src.db.database import Database

@pytest.mark.asyncio
async def test_database_initializes_all_tables(tmp_path):
    db_path = tmp_path / "test.db"
    db = Database(str(db_path))
    await db.initialize()
    
    async with aiosqlite.connect(str(db_path)) as conn:
        cursor = await conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = {row[0] for row in await cursor.fetchall()}
    
    expected = {'projects', 'conversations', 'messages', 'tasks', 'evidence', 
                'decisions', 'modifications', 'errors', 'test_runs', 'agent_sessions'}
    assert expected.issubset(tables)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_database.py::test_database_initializes_all_tables -v`
Expected: FAIL - module not found

- [ ] **Step 3: Implement database schema and Database class**

`src/db/schema.sql` - CREATE TABLE statements for all 10 tables with proper indexes
`src/db/database.py` - Database class with initialize(), connection management

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_database.py::test_database_initializes_all_tables -v`
Expected: PASS

- [ ] **Step 5: Implement model classes with basic CRUD**

Each model in `src/models/` with create, get, update, delete, list methods using parameterized queries

- [ ] **Step 6: Test model CRUD operations**

Run: `pytest tests/test_models.py -v`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/db/ src/models/ tests/test_database.py tests/test_models.py
git commit -m "feat: database schema and models"
```

---

### Task 2: Agent Wrapper Base Classes

**Files:**
- Create: `src/agents/base.py`
- Create: `src/agents/opencode_agent.py`
- Create: `src/agents/claude_agent.py`
- Create: `src/agents/__init__.py`

**Interfaces:**
- Consumes: `Database`, model classes
- Produces: `Agent` abstract base class; `OpenCodeAgent`, `ClaudeAgent` implementations with `execute(prompt: str, session_id: str, working_dir: str) -> AgentResult`

- [ ] **Step 1: Write failing test for agent execution**

```python
# tests/test_agents.py
import pytest
from src.agents.opencode_agent import OpenCodeAgent
from src.agents.claude_agent import ClaudeAgent

@pytest.mark.asyncio
async def test_opencode_agent_executes_simple_prompt(tmp_path):
    agent = OpenCodeAgent()
    result = await agent.execute(
        prompt="Say 'hello world'",
        session_id="test-session",
        working_dir=str(tmp_path)
    )
    assert result.success is True
    assert "hello world" in result.output.lower()

@pytest.mark.asyncio
async def test_claude_agent_executes_simple_prompt(tmp_path):
    agent = ClaudeAgent()
    result = await agent.execute(
        prompt="Say 'hello world'",
        session_id="test-session-uuid",
        working_dir=str(tmp_path)
    )
    assert result.success is True
    assert "hello world" in result.output.lower()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_agents.py -v`
Expected: FAIL - modules not found

- [ ] **Step 3: Implement Agent base class and result dataclass**

`src/agents/base.py` - Abstract `Agent` class with `execute()`, `validate_working_dir()`, timeout handling; `AgentResult` dataclass (success, output, error, metadata)

- [ ] **Step 4: Implement OpenCodeAgent**

`src/agents/opencode_agent.py` - Uses `opencode run --format json --session {id} --prompt "{prompt}"` with subprocess, parses JSON output, handles session continuity

- [ ] **Step 5: Implement ClaudeAgent**

`src/agents/claude_agent.py` - Uses `claude -p --output-format json --session-id {uuid} --add-dir {dir} --allowed-tools "Read,Write,Edit,Bash,Glob,Grep,Task" "{prompt}"` with subprocess, parses JSON output

- [ ] **Step 6: Run tests to verify agents work**

Run: `pytest tests/test_agents.py -v`
Expected: PASS (requires OpenCode/Claude CLI available)

- [ ] **Step 7: Commit**

```bash
git add src/agents/ tests/test_agents.py
git commit -m "feat: agent wrapper classes for OpenCode and Claude Code"
```

---

### Task 3: Coordinator Core Logic

**Files:**
- Create: `src/coordinator/coordinator.py`
- Create: `src/coordinator/task_manager.py`
- Create: `src/coordinator/round_controller.py`
- Create: `src/coordinator/context_manager.py`
- Create: `src/coordinator/__init__.py`

**Interfaces:**
- Consumes: `Database`, model classes, `Agent` implementations
- Produces: `Coordinator` class with `start_task()`, `process_round()`, `handle_agent_response()`, `request_user_approval()`, `cancel_task()`

- [ ] **Step 1: Write failing test for task creation and distribution**

```python
# tests/test_coordinator.py
import pytest
from src.coordinator.coordinator import Coordinator
from src.models.task import Task, TaskStatus

@pytest.mark.asyncio
async def test_coordinator_creates_and_distributes_task(db):
    coord = Coordinator(db)
    task = await coord.create_task(
        project_id=1,
        title="Test task",
        description="Test description",
        assigned_agents=["opencode", "claude"],
        max_rounds=3
    )
    assert task.id is not None
    assert task.status == TaskStatus.PENDING
    
    # Verify task distributed to agents
    messages = await coord.get_task_messages(task.id)
    assert len(messages) == 2  # one per agent
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_coordinator.py::test_coordinator_creates_and_distributes_task -v`
Expected: FAIL

- [ ] **Step 3: Implement TaskManager**

`src/coordinator/task_manager.py` - Task lifecycle: create, assign, update_status, get_next_action, enforce_limits

- [ ] **Step 4: Implement RoundController**

`src/coordinator/round_controller.py` - Round tracking, max_rounds enforcement, round completion detection, consensus checking

- [ ] **Step 5: Implement ContextManager**

`src/coordinator/context_manager.py` - Build agent prompts with conversation history, shared evidence, project context, file references

- [ ] **Step 6: Implement Coordinator main class**

`src/coordinator/coordinator.py` - Orchestrates task flow: distribute → collect responses → detect divergence → request clarification → route to implementer → request review → run tests → present result

- [ ] **Step 7: Run tests**

Run: `pytest tests/test_coordinator.py -v`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/coordinator/ tests/test_coordinator.py
git commit -m "feat: coordinator core with task management, rounds, context"
```

---

### Task 4: WebSocket Server & Message Routing

**Files:**
- Create: `src/server/websocket_server.py`
- Create: `src/server/message_router.py`
- Create: `src/server/api_routes.py`
- Create: `src/server/__init__.py`

**Interfaces:**
- Consumes: `Coordinator`, `Database`, model classes
- Produces: `WebSocketServer` with `start()`, `broadcast()`, `handle_client()`; REST endpoints for initial load

- [ ] **Step 1: Write failing test for WebSocket connection**

```python
# tests/test_websocket.py
import pytest
import asyncio
from aiohttp import ClientSession, WSMsgType
from src.server.websocket_server import WebSocketServer

@pytest.mark.asyncio
async def test_websocket_connect_and_receive(db):
    server = WebSocketServer(coordinator, host="127.0.0.1", port=0)
    await server.start()
    port = server.port
    
    async with ClientSession() as session:
        async with session.ws_connect(f"http://127.0.0.1:{port}/ws") as ws:
            await ws.send_json({"type": "ping"})
            msg = await ws.receive()
            assert msg.type == WSMsgType.TEXT
            data = msg.json()
            assert data["type"] == "pong"
    
    await server.stop()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_websocket.py::test_websocket_connect_and_receive -v`
Expected: FAIL

- [ ] **Step 3: Implement MessageRouter**

`src/server/message_router.py` - Route incoming WS messages to coordinator methods; handle: create_task, send_message, approve_modification, cancel_task, get_history, config_update

- [ ] **Step 4: Implement WebSocketServer**

`src/server/websocket_server.py` - aiohttp WebSocket handler, connection registry, broadcast to all/specific clients, heartbeat, reconnection handling

- [ ] **Step 5: Implement REST API routes**

`src/server/api_routes.py` - GET /api/projects, GET /api/projects/{id}/history, GET /api/tasks/{id}, POST /api/projects

- [ ] **Step 6: Run tests**

Run: `pytest tests/test_websocket.py tests/test_api.py -v`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/server/ tests/test_websocket.py tests/test_api.py
git commit -m "feat: WebSocket server and REST API"
```

---

### Task 5: Security Layer - Path Validation & Git Integration

**Files:**
- Create: `src/security/path_validator.py`
- Create: `src/security/git_manager.py`
- Create: `src/security/backup_manager.py`
- Create: `src/security/approval_workflow.py`
- Create: `src/security/__init__.py`

**Interfaces:**
- Consumes: `Database`, project working directory
- Produces: `PathValidator.validate(path)`, `GitManager.commit_before_change()`, `BackupManager.create_backup()`, `ApprovalWorkflow.request_approval()`

- [ ] **Step 1: Write failing test for path validation**

```python
# tests/test_security.py
import pytest
from src.security.path_validator import PathValidator

def test_path_validator_allows_within_project(tmp_path):
    project_dir = tmp_path / "project"
    project_dir.mkdir()
    validator = PathValidator(str(project_dir))
    
    allowed = project_dir / "src" / "main.py"
    allowed.parent.mkdir(parents=True)
    assert validator.validate(str(allowed)) is True

def test_path_validator_rejects_outside_project(tmp_path):
    project_dir = tmp_path / "project"
    project_dir.mkdir()
    outside = tmp_path / "outside.txt"
    validator = PathValidator(str(project_dir))
    
    assert validator.validate(str(outside)) is False

def test_path_validator_rejects_traversal(tmp_path):
    project_dir = tmp_path / "project"
    project_dir.mkdir()
    validator = PathValidator(str(project_dir))
    
    assert validator.validate(str(project_dir / ".." / "etc" / "passwd")) is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_security.py::test_path_validator_allows_within_project -v`
Expected: FAIL

- [ ] **Step 3: Implement PathValidator**

`src/security/path_validator.py` - Resolves paths, ensures within project root, handles symlinks, Windows path normalization

- [ ] **Step 4: Implement GitManager**

`src/security/git_manager.py` - `is_git_repo()`, `commit_all(message)`, `get_diff()`, `create_branch()`, `checkout()`; uses subprocess git commands

- [ ] **Step 5: Implement BackupManager**

`src/security/backup_manager.py` - Timestamped backups to `.bridge/backups/`, retention policy, restore capability

- [ ] **Step 6: Implement ApprovalWorkflow**

`src/security/approval_workflow.py` - Persist approval requests to DB, WebSocket notification to UI, wait for user decision with timeout

- [ ] **Step 7: Run tests**

Run: `pytest tests/test_security.py -v`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/security/ tests/test_security.py
git commit -m "feat: security layer with path validation, git, backups, approvals"
```

---

### Task 6: Frontend - Single Page Application

**Files:**
- Create: `src/frontend/index.html`
- Create: `src/frontend/styles.css`
- Create: `src/frontend/app.js`
- Create: `src/frontend/components/dashboard.js`
- Create: `src/frontend/components/chat.js`
- Create: `src/frontend/components/task_manager.js`
- Create: `src/frontend/components/controls.js`

**Interfaces:**
- Consumes: WebSocket API, REST API
- Produces: Complete UI served at `/` with real-time updates

- [ ] **Step 1: Create index.html with structure**

Semantic HTML: header (project selector, agent status), main (tabs: dashboard, chat, tasks, controls), WebSocket connection logic

- [ ] **Step 2: Create styles.css**

Modern responsive design: CSS Grid/Flexbox, dark/light theme, agent status indicators, message threading, code blocks with syntax highlighting

- [ ] **Step 3: Create app.js core**

WebSocket connection with reconnection, message routing to components, state management, API helpers

- [ ] **Step 4: Create dashboard component**

Agent status cards (OpenCode, Claude), current task, round counter, usage estimates, error display

- [ ] **Step 5: Create chat component**

Real-time message list with sender identification, threading (reply-to), file/evidence display, search/filter, auto-scroll

- [ ] **Step 6: Create task manager component**

Task creation form, task list with status, progress tracking, pause/cancel buttons, review request handling

- [ ] **Step 7: Create controls component**

Modification approval dialog (diff view), round limit config, permission toggles, project selector, emergency stop

- [ ] **Step 8: Integrate and test manually**

Start server, open browser, verify all components connect and update in real-time

- [ ] **Step 9: Commit**

```bash
git add src/frontend/
git commit -m "feat: complete frontend SPA with dashboard, chat, tasks, controls"
```

---

### Task 7: Main Application Entry Point & Configuration

**Files:**
- Create: `src/main.py`
- Create: `src/config.py`
- Create: `requirements.txt`
- Create: `README.md`
- Create: `.env.example`

**Interfaces:**
- Consumes: All previous modules
- Produces: Runnable application with `python -m src.main`

- [ ] **Step 1: Create configuration system**

`src/config.py` - Pydantic Settings with env file support: database_path, host, port, default_project_dir, agent_timeouts, round_limits, model_preferences

- [ ] **Step 2: Create main.py**

`src/main.py` - Initialize DB, Coordinator, WebSocketServer, serve frontend static files, graceful shutdown handling

- [ ] **Step 3: Create requirements.txt**

```
aiohttp>=3.9.0
aiosqlite>=0.19.0
pydantic>=2.5.0
pydantic-settings>=2.1.0
python-dotenv>=1.0.0
```

- [ ] **Step 4: Create README.md**

Installation, configuration, running, architecture overview, API documentation

- [ ] **Step 5: Create .env.example**

Template with all configurable options documented

- [ ] **Step 6: Test full application startup**

Run: `python -m src.main` → verify server starts, frontend loads, WebSocket connects

- [ ] **Step 7: Commit**

```bash
git add src/main.py src/config.py requirements.txt README.md .env.example
git commit -m "feat: application entry point and configuration"
```

---

### Task 8: Integration Testing & End-to-End Verification

**Files:**
- Create: `tests/test_integration.py`
- Create: `tests/test_e2e_collaboration.py`

**Interfaces:**
- Consumes: Full application stack
- Produces: Verified working collaboration flow

- [ ] **Step 1: Write integration test for full collaboration cycle**

```python
# tests/test_integration.py
import pytest
from src.main import create_app

@pytest.mark.asyncio
async def test_full_collaboration_cycle(tmp_path, db):
    # 1. Create project
    # 2. Create task "write hello world function"
    # 3. Coordinator distributes to both agents
    # 4. Agents respond
    # 5. Coordinator detects consensus
    # 6. Implementation agent writes code
    # 7. Review agent reviews
    # 8. Tests run
    # 9. Result presented
    pass
```

- [ ] **Step 2: Run integration test**

Run: `pytest tests/test_integration.py -v`
Expected: PASS (may take 30-60 seconds)

- [ ] **Step 3: Manual E2E verification**

Start server, create project, create task, observe full cycle in browser

- [ ] **Step 4: Commit**

```bash
git add tests/test_integration.py tests/test_e2e_collaboration.py
git commit -m "test: integration and E2E tests for full collaboration cycle"
```

---

### Task 9: Polish, Error Handling & Documentation

**Files:**
- Modify: Various - add error handling, logging, validation
- Create: `docs/architecture.md`
- Create: `docs/api.md`
- Create: `docs/user-guide.md`

**Interfaces:**
- Consumes: Complete codebase
- Produces: Production-ready system with docs

- [ ] **Step 1: Add structured logging throughout**

Replace prints with `logging` module, correlation IDs for request tracing

- [ ] **Step 2: Add comprehensive error handling**

Try/except in all async paths, user-friendly error messages via WebSocket, error persistence to DB

- [ ] **Step 3: Add input validation**

Pydantic models for all API/WS inputs, sanitization

- [ ] **Step 4: Write documentation**

Architecture diagram (mermaid), API reference, user guide with screenshots

- [ ] **Step 5: Final manual testing**

Fresh clone, install deps, run, create project, run collaboration task

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: polish, error handling, logging, documentation"
```

---

## Execution Order Summary

1. **Database & Models** (foundation)
2. **Agent Wrappers** (external integration)
3. **Coordinator** (core logic)
4. **WebSocket Server** (communication)
5. **Security Layer** (safety)
6. **Frontend** (UI)
7. **Main Entry & Config** (runnable app)
8. **Integration Tests** (verification)
9. **Polish & Docs** (production ready)

Each task produces independently testable deliverables. Tasks 1-3 can be developed in parallel after Task 1. Tasks 4-5 depend on 2-3. Task 6 depends on 4. Task 7 depends on 1-6. Tasks 8-9 depend on 7.