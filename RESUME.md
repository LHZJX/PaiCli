# PaiCLI 简历描述整理

> 项目：PaiCLI —— 基于大模型的 Java AI Agent CLI
> 建议时间：2026 年 6 月 – 2026 年 8 月（两期迭代）

---

## 技术栈

Java 17 · Maven · GLM 大模型 API（Function Calling）· OkHttp · Jackson · SLF4J · JUnit 5

---

## 版本一：中文完整版（推荐，放"项目经历"栏）

**PaiCLI —— 基于大模型的 Java AI Agent 命令行工具**（附 GitHub 链接）

- 独立开发一款类 Claude Code 的 AI Agent CLI，支持自然语言指令驱动完成文件读写、Shell 命令执行、工程脚手架创建等任务。
- **ReAct Agent 循环**：实现"思考 → 行动 → 观察"的推理执行闭环，基于 LLM Function Calling 协议封装工具调用（tool_calls 解析与结果回传），内置最大迭代次数限制与 Token 用量统计。
- **工具注册中心**：基于函数式接口（`ToolExecutor`）设计可插拔的工具体系，支持参数 JSON Schema 自动生成，内置 read_file / write_file / list_dir / execute_command / create_project 五类工具，新增工具仅需注册一段 lambda。
- **Plan-and-Execute 架构**：第二期引入 LLM 规划器（Planner），将复杂任务拆解为带依赖关系的子任务 DAG；实现 DFS 拓扑排序确定执行顺序并检测循环依赖，支持按依赖状态串行执行与进度追踪。
- **失败自动重规划**：任务执行失败时携带失败原因与已完成任务上下文重新生成执行计划（replan），提升复杂任务完成的鲁棒性。
- **模式自适应**：通过启发式规则（动作关键词计数 + 输入长度）自动判断简单/复杂任务，CLI 支持 `/plan`、`mode`、`clear` 等命令在 ReAct 与 Plan-and-Execute 间动态切换。
- 编写 JUnit 5 单元测试覆盖命令解析与执行计划（拓扑排序、依赖校验）核心逻辑。

---

## 版本二：中文精简版（简历空间紧张时，3-4 条）

**PaiCLI —— 基于大模型的 Java AI Agent CLI**（Java 17 · Maven · GLM API · OkHttp · Jackson）

- 独立开发类 Claude Code 的 Java Agent 命令行工具，实现基于 LLM Function Calling 的 **ReAct 推理执行循环**。
- 设计可插拔工具注册中心（函数式接口 + JSON Schema 参数定义），内置文件操作、Shell 执行、项目脚手架等五类工具。
- 二期重构为 **Plan-and-Execute + DAG**：LLM 规划器拆解复杂任务 → DFS 拓扑排序定执行顺序 → 失败自动重规划，支撑多步依赖任务的自动执行。

---

## 版本三：英文完整版（投外企 / 大厂）

**PaiCLI — A Java AI Agent CLI powered by LLMs** (Java 17 · Maven · GLM API · OkHttp · Jackson · JUnit 5)

- Built a Claude-Code-like AI agent CLI in pure Java that executes natural-language tasks (file I/O, shell commands, project scaffolding).
- Implemented a **ReAct agent loop** on top of the LLM Function Calling protocol, handling tool-call parsing, result feedback, iteration limits and token usage tracking.
- Designed a **pluggable tool registry** using functional interfaces with auto-generated JSON Schemas; shipped 5 built-in tools (read/write file, list dir, run command, scaffold project).
- Evolved to **Plan-and-Execute with a task DAG**: an LLM planner decomposes complex goals into dependency-ordered subtasks, DFS topological sorting determines execution order and detects cycles, and a **replan mechanism** regenerates the plan on failure for robustness.
- Added heuristic mode selection and `/plan` CLI commands to switch between ReAct and plan-driven execution; covered the core logic with JUnit 5 unit tests.

---

## 版本四：英文精简版

**PaiCLI — A Java AI Agent CLI powered by LLMs** (Java 17 · Maven · GLM API · OkHttp · Jackson)

- Built a Claude-Code-like AI agent CLI in Java with a **ReAct loop** built on the LLM Function Calling protocol.
- Designed a pluggable tool registry (functional interface + JSON Schema params) with 5 built-in tools for file ops, shell commands and project scaffolding.
- Evolved to **Plan-and-Execute with a task DAG** — LLM-based decomposition, DFS topological sort for execution order, and automatic replanning on task failure.

---

## 写作要点（务必注意）

1. **用"动词 + 做了什么 + 解决了什么"结构**，避免写成"使用了 XX、调用了 XX"的流水账。
2. **突出架构演进**（ReAct → Plan-and-Execute）：这是项目区别于"调一下 API"demo 的最大亮点，面试官最爱追问演进动机。
3. **不要编造数字**：代码中没有性能对比、用户量等指标，没有就宁可不写量化数据；若想补"成功率提升 xx%"需先做真实对比实验。
4. 若目标岗位偏 **Java 工程化**，多谈工具注册中心的可插拔设计与拓扑排序实现；偏 **AI 应用**，多谈 ReAct 循环与 Function Calling 协议封装。

---

## 面试追问自测清单

- [ ] 为什么用 DFS 拓扑排序？循环依赖如何处理？（见 `ExecutionPlan.computeExecutionOrder`）
- [ ] replan 触发的条件是什么？为什么进度 <50% 才重规划？（见 `PlanExecuteAgent.executePlan`）
- [ ] `shouldPlan` 的启发式规则是怎么定的？有哪些关键词？
- [ ] ReAct 和 Plan-and-Execute 各自的适用场景与优缺点？
- [ ] Function Calling 的消息往返格式（assistant 携带 tool_calls → tool 回传结果）是怎么实现的？
- [ ] 新增一个工具需要改动哪些地方？扩展点在哪？
