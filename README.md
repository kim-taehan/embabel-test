# embabel-test

사내망 LLM 게이트웨이(qwen3.5-27b, OpenAI 호환) 위에 [Embabel Agent Framework](https://github.com/embabel/embabel-agent) 를 얹어 만든 **연구 보고서 생성 에이전트**. </br>
Spring WebFlux 함수형 DSL + 코루틴 Flow 로 SSE 스트리밍을 제공하며, Tavily Web Search API 로 실시간 웹 검색 결과에 인용을 그라운딩합니다.

- `POST /api/research/stream` — 단일 토픽 요약 (1 단계 LLM)
- `POST /api/deep-research/stream` — Perplexity Deep Research 클론, 다섹션 보고서 (4 단계 GOAP plan)

> 📘 **Embabel 프레임워크 자체에 대한 심층 문서** → [`docs.embabel.md`](./docs.embabel.md)
> GOAP 플래너 알고리즘 (라이브러리 실제 코드), `@Agent` / `@Action` / `@LlmTool` 코드 레벨 워크스루,
> 다른 프레임워크와의 비교, 토큰 스트리밍 한계까지 다룹니다.

---

## 목차
1. [기술 스택](#1-기술-스택)
2. [전체 아키텍처](#2-전체-아키텍처)
3. [디렉터리 구조](#3-디렉터리-구조)
4. [설정 & 실행](#4-설정--실행)
5. [API 처리 프로세스](#5-api-처리-프로세스)
   - [`POST /api/research/stream`](#51-post-apiresearchstream)
   - [`POST /api/deep-research/stream`](#52-post-apideep-researchstream)
6. [핵심 설계 포인트](#6-핵심-설계-포인트)
7. [SSE 이벤트 카탈로그](#7-sse-이벤트-카탈로그)
8. [테스트 & OpenAPI](#8-테스트--openapi)
9. [더 읽을거리](#9-더-읽을거리)

---

## 1. 기술 스택

| 영역 | 선택 |
|------|------|
| 언어 | Kotlin 2.1.10, Java 21 toolchain |
| 프레임워크 | Spring Boot 3.5.11, Spring WebFlux (reactor-netty) |
| 에이전트 | Embabel Agent 0.3.4 (`embabel-agent-starter`, `embabel-agent-openai-custom-autoconfigure`) |
| LLM | qwen3.5-27b 사내망 게이트웨이 (OpenAI 호환) |
| 웹 검색 | Tavily Search API |
| 비동기/스트림 | kotlinx.coroutines + Reactor `Sinks.Many`, `Flow`, `ServerSentEvent` |
| 문서화 | Spring REST Docs + restdocs-api-spec → springdoc-openapi-ui |
| 테스트 | JUnit5, MockK, WebTestClient, Embabel `FakeOperationContext` |

---

## 2. 전체 아키텍처

**헥사고날 (Ports & Adapters)** — 도메인은 외부 기술에 의존하지 않고, 어댑터가 도메인 포트를 구현합니다.

```
┌─────────────────────── adapter.inbound.web ────────────────────────┐
│                                                                      │
│   ResearchRouter ──► ResearchHandler ──► (ResearchEventDto)         │
│   (coRouter DSL)     (suspend fn)        (SSE payload)              │
│                          │                                           │
└──────────────────────────┼──────────────────────────────────────────┘
                           │ depends on (inbound port)
                           ▼
┌────────────────────── domain.port.inbound ─────────────────────────┐
│   ResearchStreamUseCase   DeepResearchStreamUseCase                 │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ implemented by
                           ▼
┌────────────────────── application.service ─────────────────────────┐
│   ResearchService         DeepResearchService                       │
│   (얇은 위임 레이어 — 트랜잭션/오케스트레이션 자리)                    │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ depends on (outbound port)
                           ▼
┌───────────────────── domain.port.outbound ─────────────────────────┐
│   ResearchAgentPort        DeepResearchAgentPort                    │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ implemented by
                           ▼
┌─────────────────── adapter.outbound.agent ─────────────────────────┐
│   EmbabelResearchAgentAdapter      EmbabelDeepResearchAgentAdapter  │
│        │                                  │                          │
│        └──► AgentInvocation (Embabel) ◄───┘                          │
│                  │                                                    │
│                  ▼                                                    │
│   ┌──────────────────────┬──────────────────────────┐                │
│   │  ResearchPlannerAgent │   DeepResearchAgent       │                │
│   │  (@Agent / @Action)   │   (@Agent / @Action × 4)   │                │
│   └──────────┬────────────┴───────────────┬──────────┘                │
│              │ withToolObject             │                            │
│              ▼                            ▼                            │
│   ResearchTools  ── current_date / tavily_search (@LlmTool)           │
│        │                                                              │
│        └──► Tavily HTTP API (WebClient)                              │
│                                                                       │
│   StreamingAgentEventListener  ── AgenticEventListener                │
│        └──► Sinks.Many<ResearchEvent>                                 │
└──────────────────────────────────────────────────────────────────────┘
                           ▲
                           │ Embabel platform process events
                           │ (ToolCall*, ActionExecution*, ObjectAdded*)
```

**핵심 의존성 흐름**: `web` → `inbound port` → `service` → `outbound port` → `embabel adapter`. 도메인(`domain.model`)은 어떤 외부 라이브러리도 import 하지 않습니다.

---

## 3. 디렉터리 구조

```
src/main/kotlin/com/example/embabeltest/
├─ EmbabelTestApplication.kt
├─ domain/
│  ├─ model/                              # 순수 도메인 (검증 포함)
│  │  ├─ ResearchTopic.kt                 # value class, 1..500
│  │  ├─ ResearchSummary.kt               # 단일 요약 결과
│  │  ├─ DeepResearchReport.kt            # 다섹션 보고서
│  │  ├─ Section.kt                       # 보고서 섹션
│  │  ├─ ResearchPlan.kt                  # 보고서 계획 (max 8 subtopics)
│  │  └─ ResearchEvent.kt                 # sealed (started/tool-*/completed/...)
│  └─ port/
│     ├─ inbound/                         # 핸들러가 소비하는 use case
│     │  ├─ ResearchStreamUseCase.kt
│     │  └─ DeepResearchStreamUseCase.kt
│     └─ outbound/                        # 서비스가 위임하는 어댑터 인터페이스
│        ├─ ResearchAgentPort.kt
│        └─ DeepResearchAgentPort.kt
├─ application/service/
│  ├─ ResearchService.kt                  # ResearchStreamUseCase 구현
│  └─ DeepResearchService.kt              # DeepResearchStreamUseCase 구현
└─ adapter/
   ├─ inbound/web/
   │  ├─ ResearchRouter.kt                # coRouter functional DSL
   │  ├─ ResearchHandler.kt               # suspend handler
   │  └─ dto/                             # 와이어 포맷 (DTO ↔ domain)
   │     ├─ ResearchRequestDto.kt
   │     ├─ ResearchResponseDto.kt
   │     ├─ ResearchEventDto.kt           # SSE payload
   │     └─ DeepResearchReportDto.kt
   └─ outbound/agent/
      ├─ ResearchPlannerAgent.kt          # @Agent: planAngles → summarize
      ├─ DeepResearchAgent.kt             # @Agent: plan → gather → frame → assemble
      ├─ ResearchTools.kt                 # @LlmTool: current_date, tavily_search
      ├─ StreamingAgentEventListener.kt   # AgenticEventListener → Sinks
      ├─ EmbabelResearchAgentAdapter.kt   # ResearchAgentPort 구현
      └─ EmbabelDeepResearchAgentAdapter.kt # + PlanAndSectionListener
```

---

## 4. 설정 & 실행

### 환경 변수 (`.env.properties` 또는 export)

```properties
LLM_API_KEY=sk-davis-27b-xxx
LLM_BASE_URL=https://<internal-gateway>/v1
LLM_MODEL=qwen3.5-27b
TAVILY_API_KEY=tvly-xxx
```

### 주요 application.yml 옵션

| 키 | 기본값 | 의미 |
|----|--------|------|
| `embabel.agent.platform.models.openai.custom.*` | — | 사내 OpenAI 호환 게이트웨이 등록 |
| `embabel.agent.platform.llm-operations.prompts.default-timeout` | `240s` | LLM 호출 타임아웃. **60s 기본값으로는 reasoning + tool loop 가 잘림 → 동일 쿼리 재발사 발생**, 240s 로 상향 |
| `embabel.models.default-llm` | `qwen3.5-27b` | 기본 LLM ID |
| `deep-research.subtopic-count` | `5` | Deep Research 의 섹션 개수 |
| `deep-research.section-word-count` | `200` | 섹션 본문 목표 단어 수 |
| `research.angle-count` | `4` | 단일 요약의 angle 개수 |
| `tavily.api-key` / `tavily.base-url` | `${TAVILY_API_KEY:}` / `https://api.tavily.com` | Tavily 설정 |

### 실행

```bash
./gradlew bootRun
# Swagger UI: http://localhost:8080/swagger-ui.html
# OpenAPI:    http://localhost:8080/openapi3.yaml
```

---

## 5. API 처리 프로세스

두 엔드포인트 모두 **`POST` + `Content-Type: application/json`**, 응답은 **`text/event-stream`** 입니다.
요청 바디: `{"topic": "<문자열, 1..500자>"}`

### 5.1 `POST /api/research/stream`

단일 토픽에 대한 **1-단계 요약** (headline / keyPoints / conclusion).

#### 컴포넌트 시퀀스

```
client ──HTTP──► ResearchHandler.handleStream
                      │
                      │ awaitBody → ResearchRequestDto.toDomain()
                      ▼
                  researchStreamUseCase.stream(ResearchTopic)
                      │ (= ResearchService.stream)
                      ▼
                  researchAgentPort.streamSummarize
                      │ (= EmbabelResearchAgentAdapter)
                      │
                      │   send(Started)
                      │   launch { sink.asFlux().asFlow().collect { send(it) } }
                      │   launch(IO) {
                      │       AgentInvocation
                      │           .builder(agentPlatform)
                      │           .options(ProcessOptions().withListener(StreamingAgentEventListener))
                      │           .build(AgentResearchSummary::class.java)
                      │           .invoke(UserInput(topic))
                      │       send(Completed | Failed)
                      │       sink.tryEmitComplete()
                      │   }
                      ▼
              Embabel GOAP planner
                      │  resolve plan: planAngles → summarize
                      ▼
   ResearchPlannerAgent.planAngles  (LLM, no tools)
                      │  → ResearchOutline { topic, angles[4] }
                      ▼
   ResearchPlannerAgent.summarize   (LLM + tools)
                      │ tool loop:
                      │   ① current_date()              → ToolInvoked / ToolReturned
                      │   ② tavily_search("...")        → ToolInvoked / ToolReturned
                      │   ③ (optional) tavily_search 추가
                      ▼
                  AgentResearchSummary
                      │ adapter.toDomain → ResearchSummary
                      ▼
                  ResearchEvent.Completed(summary)
                      │
                      ▼
                  ResearchEventDto.fromDomain → ServerSentEvent
                      │
client ◄───────── text/event-stream (started, action-*, tool-*, completed)
```

#### 이벤트 흐름

```
event:started               { topic }
event:action-started        { action: "planAngles" }
event:action-completed      { action: "planAngles", status: "SUCCEEDED", durationMs }
event:action-started        { action: "summarize" }
event:tool-invoked          { tool: "current_date", input: "" }
event:tool-returned         { tool: "current_date", durationMs, preview }
event:tool-invoked          { tool: "tavily_search", input: "<query>" }
event:tool-returned         { tool: "tavily_search", durationMs, preview }
event:action-completed      { action: "summarize", status: "SUCCEEDED", durationMs }
event:completed             { summary: { topic, headline, keyPoints[], conclusion } }
```

### 5.2 `POST /api/deep-research/stream`

Perplexity Deep Research 스타일의 **다섹션 보고서**. GOAP planner 가 4 단계 plan 을 만듭니다:

```
planSubtopics → gatherSections → frameReport → assembleReport
```

#### 단계별 설계

| 단계 | 종류 | 입력 | 출력 | 비고 |
|------|------|------|------|------|
| `planSubtopics` | LLM (no tool) | `UserInput` | `AgentResearchPlan` | 토픽을 N(=5) 개 subtopic 으로 분해 |
| `gatherSections` | LLM × N (with tools) | `AgentResearchPlan` | `AgentSectionDrafts` | subtopic 별로 1–2 회 `tavily_search` → 200단어 섹션 작성, 인용 inline |
| `frameReport` | LLM (with tools) | `AgentResearchPlan + AgentSectionDrafts` | `AgentReportFraming` | `current_date()` 1 회 + executiveSummary + conclusion 만 생성 |
| `assembleReport` | **순수 코드** | 셋 다 | `AgentDeepResearchReport` | `@AchievesGoal` — sources dedup 만 수행 |

> 💡 **왜 `frameReport` 와 `assembleReport` 를 분리했나**
> 한 번의 LLM 호출로 5 섹션 + 메타까지 재생성하면 출력 토큰 한도에 걸려 **무한 retry** 가 발생.
> 합성(synthesis)은 LLM, 결합(stitching)은 순수 Kotlin 으로 분리하면 안정성과 비용이 동시에 개선됩니다.

#### 컴포넌트 시퀀스

```
client ──HTTP──► ResearchHandler.handleDeepResearchStream
                     │
                     ▼
              deepResearchStreamUseCase.stream
                     │ (= DeepResearchService.stream)
                     ▼
              deepResearchAgentPort.streamDeepResearch
                     │ (= EmbabelDeepResearchAgentAdapter)
                     │
                     │  send(Started)
                     │  combinedListener =
                     │     [ StreamingAgentEventListener,    # 일반 이벤트
                     │       PlanAndSectionListener ]        # 도메인 이벤트
                     │
                     │  AgentInvocation
                     │     .options(ProcessOptions().withListener(combined))
                     │     .build(AgentDeepResearchReport::class.java)
                     │     .invoke(UserInput(topic))
                     ▼
   ┌──────────────── Embabel GOAP plan ────────────────┐
   │                                                    │
   │ 1) planSubtopics                                   │
   │      LLM → AgentResearchPlan(subtopics × 5)        │
   │      ObjectAddedEvent → PlanFormulated(titles)     │
   │                                                    │
   │ 2) gatherSections                                  │
   │      for each subtopic:                            │
   │          LLM tool loop:                            │
   │             tavily_search × 1–2                    │
   │          → AgentSection(title, body, sources)      │
   │          ObjectAddedEvent → SectionDrafted         │
   │      → AgentSectionDrafts                          │
   │                                                    │
   │ 3) frameReport                                     │
   │      LLM tool loop:                                │
   │          current_date × 1                          │
   │      → AgentReportFraming(execSummary, conclusion) │
   │                                                    │
   │ 4) assembleReport  (no LLM, pure code)             │
   │      → AgentDeepResearchReport                     │
   │         (sources = sections.flatMap{it.sources}    │
   │                            .distinct())            │
   └────────────────────────────────────────────────────┘
                     │
                     │ adapter.toDomain → DeepResearchReport
                     ▼
              ResearchEvent.DeepResearchCompleted(report)
                     ▼
client ◄───── text/event-stream
```

#### 이벤트 흐름

```
event:started               { topic }
event:action-started        { action: "planSubtopics" }
event:action-completed      { action: "planSubtopics", status, durationMs }
event:plan-formulated       { subtopics: [...] }
event:action-started        { action: "gatherSections" }
event:tool-invoked          { tool: "tavily_search", input }    ┐ subtopic 1
event:tool-returned         { tool: "tavily_search", ... }      │
event:section-drafted       { title, sourceCount }              ┘
... (subtopic 2..N 반복)
event:action-completed      { action: "gatherSections", status, durationMs }
event:action-started        { action: "frameReport" }
event:tool-invoked          { tool: "current_date" }
event:tool-returned         { tool: "current_date", ... }
event:action-completed      { action: "frameReport", ... }
event:action-started        { action: "assembleReport" }
event:action-completed      { action: "assembleReport", ... }
event:deep-research-completed
   { report: { topic, executiveSummary,
               sections: [{title, body, sources}],
               sources: [...],   # deduped union
               conclusion } }
```

---

## 6. 핵심 설계 포인트

### (a) Embabel 이벤트 → Coroutine Flow 브릿지

`AgentInvocation` 은 동기 / 블로킹 호출이지만, 에이전트 진행 상황은 `AgenticEventListener` 콜백으로 푸시됩니다.
이를 코루틴 `Flow` 로 노출하기 위해:

```
channelFlow {
    val sink = Sinks.many().multicast().onBackpressureBuffer<ResearchEvent>(256, false)
    val listener = StreamingAgentEventListener(sink)

    send(Started)
    val forwardJob = launch { sink.asFlux().asFlow().collect { send(it) } }
    val agentJob   = launch(Dispatchers.IO) {
        runCatching { AgentInvocation.builder(...).options(withListener(listener)).build(...).invoke(...) }
            .fold(::Completed, { Failed(it.message) })
            .also { send(it); sink.tryEmitComplete() }
    }
    agentJob.join(); forwardJob.cancel()
}
```

이로써 핸들러는 `Flow<ResearchEvent>` 만 알게 되고, Embabel/Reactor 의존이 어댑터 안에 격리됩니다.

### (b) 두 종류의 리스너 합성

Deep Research 는 일반 process event 외에 *"plan 이 만들어졌다", "section 이 추가됐다"* 같은 **도메인 시점**도 알리고 싶습니다.
`ObjectAddedEvent` 로 구체 객체를 가로채어 별도 이벤트를 발행하는 `PlanAndSectionListener` 를 만들고, `MulticastListener` 로 일반 리스너와 합성합니다.

### (c) LLM 타임아웃 / 재시도

- `default-timeout: 60s` (기본) 에서는 reasoning + 다회 tool 호출이 잘리고, retry 시 **동일 쿼리를 다시 발사** → 캐시 207ms 응답 → 무한 루프 패턴 관찰됨.
- `240s` 로 상향 + `gatherSections` 프롬프트에서 "1–2 tavily_search calls (no more)" 로 호출 수 제한.

### (d) Headless validation

도메인 모델의 `init { require(...) }` 가 LLM 출력을 1차 게이트키핑합니다. 빈 keyPoints, 공백 헤드라인 등은 ICE 단계에서 거부됩니다.

---

## 7. SSE 이벤트 카탈로그

`ResearchEvent` (sealed) 와 와이어 포맷 `ResearchEventDto`:

| `event:` | 발행 조건 | payload |
|----------|----------|---------|
| `started` | 어댑터 진입 직후 | `{topic}` |
| `action-started` | `ActionExecutionStartEvent` | `{action}` |
| `action-completed` | `ActionExecutionResultEvent` | `{action, status, durationMs}` |
| `tool-invoked` | `ToolCallRequestEvent` | `{tool, input}` |
| `tool-returned` | `ToolCallResponseEvent` | `{tool, durationMs, preview}` (≤240자 truncate) |
| `plan-formulated` | `ObjectAddedEvent(AgentResearchPlan)` | `{subtopics: string[]}` |
| `section-drafted` | `ObjectAddedEvent(AgentSection)` | `{title, sourceCount}` |
| `completed` | research 흐름 정상 종료 | `{summary: {topic, headline, keyPoints, conclusion}}` |
| `deep-research-completed` | deep-research 흐름 정상 종료 | `{report: {executiveSummary, sections, sources, conclusion}}` |
| `failed` | 에이전트 실행 예외 | `{message}` |

---

## 8. 테스트 & OpenAPI

### 테스트 레이어

| 레이어 | 도구 | 예시 |
|--------|------|------|
| 도메인 검증 | JUnit5 | `ResearchTopicTest`, `DeepResearchReportTest` |
| 에이전트 단위 | Embabel `FakeOperationContext` (LLM stub) | `ResearchPlannerAgentTest`, `DeepResearchAgentTest` |
| Tavily 어댑터 | OkHttp `MockWebServer` | `ResearchToolsTest` |
| 핸들러/라우터 | `WebTestClient` + Spring REST Docs | `ResearchHandlerTest` |

```bash
./gradlew test
```

### OpenAPI 생성

REST Docs 테스트 → `restdocs-api-spec` plugin → `build/api-spec/openapi3.yaml` → `bootRun` / `bootJar` 시 `static/` 으로 복사 → springdoc-ui 가 서빙.

```bash
./gradlew openapi3 copyOpenApiSpec bootRun
# Swagger:  http://localhost:8080/swagger-ui.html
# Spec:     http://localhost:8080/openapi3.yaml
```

---

## 9. 더 읽을거리

- 📘 [`docs.embabel.md`](./docs.embabel.md) — **Embabel 프레임워크 심층 문서**
  - §1: 들어가기 — *왜 JVM* 인가 (TS·Python·JVM 언어 포지셔닝)
  - §2: 아키텍처 개요 — GOAP 분리, 빌딩블록, OODA
  - §3: **런타임 추상화** — Blackboard / OperationContext / AgentProcess / ProcessOptions
  - §4: 어노테이션 동작 모델 — `@Agent` / `@Action` / `@LlmTool`
  - §5: `/api/deep-research/stream` 의 Embabel 코드 워크스루
  - §6: **이벤트 카탈로그** — `AgenticEventListener` 23개 전체 + 클라이언트 라우팅 가이드
  - §7: 오류 처리 / 재계획 / Fallback — 5층 방어선
  - §8: **서브에이전트와 합성** — 개념 + 향후 확장 가이드
  - §9: **테스트와 관측성** — `FakeOperationContext`, OTel·Micrometer 통합 패턴
  - §10: 다른 프레임워크와의 차별점 — LangChain / LangGraph / CrewAI 비교
  - §11: 토큰 단위 스트리밍 한계와 트레이드오프
  - 부록 A: GOAP A\* 플래너 라이브러리 코드 / 부록 B: 향후 확장 패턴
- [Embabel Agent Framework — GitHub](https://github.com/embabel/embabel-agent)
- [Tavily Search API](https://docs.tavily.com/)

---

## 부록: curl 예시

```bash
# 단일 요약 스트리밍
curl -N -X POST http://localhost:8080/api/research/stream \
     -H 'Content-Type: application/json' \
     -d '{"topic":"service mesh"}'

# Deep Research 스트리밍
curl -N -X POST http://localhost:8080/api/deep-research/stream \
     -H 'Content-Type: application/json' \
     -d '{"topic":"최근 프랑스 경제"}'
```
