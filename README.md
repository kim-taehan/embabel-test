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

- 📘 [`docs.embabel.md`](./docs.embabel.md) — **Embabel 프레임워크 심층 문서** (비개발자도 읽을 수 있는 톤)
  - §1: 들어가기 — *왜 JVM* 인가 (TS·Python·JVM 언어 포지셔닝)
  - §2: `/api/deep-research` 4단계 파이프라인 + **`StreamingAgentEventListener` 가 SSE 이벤트를 어떻게 만드나**
  - §3: Embabel 아키텍처 — **5가지 빌딩 블록** (Actions / Goals / Conditions / Domain Model / **Tools `@LlmTool`**) + OODA 실행 모델
  - §4: GOAP 플래너 알고리즘 — Embabel 라이브러리 A\* 실제 코드 (`Plan.kt`, `ConditionWorldState.kt`, `AStarGoapPlanner.kt`, 두 단계 최적화)
  - §5: AI 가 실수하면 어떻게 되나 — **5겹 안전망** (액션 재시도 → 도메인 검증 → GOAP replan → 명시적 replan → 무한루프 방어)
  - §6: **실증** — Brexit 보고서 한 번 돌려보기 (이벤트 시퀀스 + §1~§5 증명 매핑 + 최종 보고서 전체)
  - §7: 다른 도구와 비교 — LangChain / LangGraph / CrewAI / OpenAI Assistants vs Embabel + **토큰 스트리밍 한계 (트레이드오프)**
  - §8: **개선 방향** — 속도(병렬화) / 신뢰성(Tavily fallback, disconnect) / 관측성(OTel·Micrometer) / 기능 확장(추가 Goal) + 우선순위 추천
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

---

## 부록: 실제 응답 예시 — Brexit 보고서

> **입력**: `{"topic":"브렉시트 이후에 영국에 경제상황을 정리해줘"}`
> **소요 시간**: 약 25초 (검색 11회 + LLM 호출 6회)
> **결과**: 5개 섹션 · 19개 출처 · 한국어/영어 자료 혼용

### 실시간 스트리밍 — SSE 이벤트 시퀀스 (요약)

```
event: started                  { topic: "브렉시트 이후에 영국에 경제상황을 정리해줘" }
event: tool-invoked × 11        { tool: "tavily_search", input: "<쿼리>" }
event: tool-returned × 11       { durationMs: 1083~1790, preview: "..." }
event: tool-invoked             { tool: "current_date" }
event: tool-returned            { durationMs: 1, preview: "Success(2026-05-06)" }
event: deep-research-completed  { report: { ... } }
```

→ 약 25초 동안 22개의 SSE 이벤트가 실시간으로 도착. 마지막에 5섹션 보고서가 한꺼번에 완성.

### 최종 보고서

> # 브렉시트 이후 영국 경제 상황 보고서
>
> **작성 시점**: 2026-05-06
> **분석 대상**: 무역 · 노동 · 환율 · 투자 · 재정 정책
>
> ---
>
> ## 요약 (Executive Summary)
>
> 본 보고서는 브렉시트 이후 영국 경제의 주요 변화를 무역, 노동, 환율, 투자 및 재정 정책 측면에서 종합적으로 분석합니다. **2026-05-06 기준**, 영국은 EU와의 비관세 장벽과 노동력 부족으로 인한 구조적 어려움을 겪고 있으며, 이는 경제 성장에 지속적인 영향을 미치고 있습니다. 이러한 맥락에서 정부의 재정 정책과 산업 재편 전략이 향후 경제 회복의 핵심 요소로 부각되고 있습니다.
>
> ---
>
> ## 1. 무역 장벽과 상품 교역량 변화
>
> 브렉시트 이후 영국과 EU 간 상품 교역은 비관세 장벽(NTB)의 급증으로 구조적 변화를 겪었습니다. 2021년 1월 TCA 발효 직후 영국 대 EU 상품 수출은 약 **40% 급감** 했으나 부분 회복되었습니다. 그러나 2017년 대비 2024년까지 대 EU 수출은 **23% 감소** 한 반면 수입은 5% 감소에 그쳐 무역 불균형이 심화되었습니다. 비관세 장벽 증가는 장기적으로 영국 기업 투자와 1인당 산출량을 각각 **2.5%·3% 낮추고** 생산성을 1.2% 감소시킬 것으로 추정됩니다.
>
> *출처*: [LSE](https://economic-policy.org/76th-economic-policy-panel/brexit-trade/) · [Global Angle](https://global-angle.com/brexit-impact-on-exports-in-uk/) · [Productivity Institute](https://www.productivity.ac.uk/wp-content/uploads/2025/08/WP057-Brexit-and-Non-Tariff-Barriers-August-2025.pdf) · [영국 의회 도서관](https://commonslibrary.parliament.uk/research-briefings/cbp-7851/)
>
> ---
>
> ## 2. 노동력 부족과 인력 이동 제한
>
> 2021년 도입된 새 이민 시스템은 EU 출신 노동자의 유입을 급감시켰고, 비EU 출신 노동자 증가로 일부 상쇄되었으나 저숙련 직종 인력 부족이 심화되었습니다. 특히 **건강·사회복지, 숙박·음식, 운송** 산업에서 인력 공급이 수요를 못 따라가는 상황이 발생. 스탠포드 연구에 따르면 브렉시트로 **2025년까지 고용 3~4% 감소, 생산성 3~4% 하락** 예상.
>
> *출처*: [CER](https://www.cer.eu/insights/impact-brexit-immigration-uk) · [IZA](https://docs.iza.org/dp15883.pdf) · [Stanford SIEPR](https://siepr.stanford.edu/publications/working-paper/economic-impact-brexit)
>
> ---
>
> ## 3. 파운드 환율 변동성과 물가 상승
>
> 브렉시트 투표 직후 2016년 5월~2017년 3월 파운드 실질 유효 환율 **11% 하락**. 수입 물가 상승 → 2022년 10월 소비자 물가 상승률 **11.1% 도달**. 2025년 영국 기업의 헤지 비율이 **2024년 45% → 53%** 로 상향되는 등 위험 관리 비용 증가.
>
> *출처*: [NIESR](https://niesr.ac.uk/blog/uk-trade-and-exchange-rate) · [KDI](https://eiec.kdi.re.kr/material/pageoneView.do?idx=1635) · [Reuters](https://www.reuters.com/world/uk/many-uk-firms-say-volatile-pound-triggered-losses-2025-need-hedge-grows-2025-12-11/)
>
> ---
>
> ## 4. 외국 직접 투자(FDI) 감소와 산업 재편
>
> 2024년 영국 내 FDI 유입액은 전년 대비 **279억 파운드 감소** 하여 134억 파운드. 브렉시트 투표 이후 FDI 유입은 약 **16~20% 감소** 추정. 산업별로는 금융·서비스업 → **기술·R&D 분야로 재편 가속**. EY 2024 보고서에 따르면 영국은 유럽 FDI 프로젝트 수 2위 유지, 본사·R&D 분야 점유율은 각각 34%·19%.
>
> *출처*: [ONS](https://www.ons.gov.uk/economy/nationalaccounts/balanceofpayments/bulletins/foreigndirectinvestmentinvolvingukcompanies/2024) · [UKTPO](https://www.uktpo.org/briefing-papers/not-backing-britain-fdi-inflows-since-the-brexit-referendum/) · [EY](https://www.ey.com/en_uk/newsroom/2025/05/uk-fdi-projects-second-in-europe)
>
> ---
>
> ## 5. 재정 정책과 경제 성장률 전망
>
> 영국 재무부 2026년 봄 전망: 인플레이션 하락 + **차입금 6년 만의 최저 수준** 으로 G7 평균 하회. 그러나 OBR 분석에 따르면 브렉시트 이후 무역 관계가 **장기 생산성을 EU 잔류 시보다 4% 낮춤**. 2026년 성장률 전망: NIESR **+1.4%**, OECD **+0.7%**. 1인당 GDP는 브렉시트가 없었을 경우보다 **6~8% 낮은 수준** 으로 추정.
>
> *출처*: [HM Treasury](https://www.gov.uk/government/news/spring-forecast-2026-the-right-economic-plan-for-britain) · [OBR](https://obr.uk/forecasts-in-depth/the-economy-forecast/brexit-analysis/) · [NIESR](https://niesr.ac.uk/reports/economic-outlook-winter-2026)
>
> ---
>
> ## 결론
>
> 브렉시트 이후 영국 경제는 **무역 장벽, 노동력 부족, 환율 변동성, FDI 감소** 등 구조적 도전에 직면해 성장 잠재력이 제한되었습니다. 정부의 재정 정책이 이를 완화하기 위해 노력하고 있으나, 근본적인 경제 구조 변화는 단기 해결이 어려우며 **장기 회복을 위해서는 포괄적인 개혁이 필요** 합니다.
>
> ---
>
> ### 핵심 수치 요약
>
> | 지표 | 변화 |
> |------|------|
> | 대 EU 수출 (2017→2024) | ▼ 23% |
> | 1인당 GDP (vs EU 잔류 가정) | ▼ 6~8% |
> | 비관세 장벽으로 인한 생산성 | ▼ 1.2% |
> | FDI 유입 (2024) | ▼ 279억 파운드 |
> | 파운드 실질 유효 환율 (2016~17) | ▼ 11% |
> | 2026 성장률 전망 (NIESR / OECD) | +1.4% / +0.7% |

### 주목할 점

- **LLM이 검색 언어를 자율 선택** — 환율·재정 섹션에서 영어 검색 후 *"한국 자료가 더 풍부할 수 있겠다"* 판단해 한국어 추가 검색 → KDI, a-ha 등 한국어 출처가 인용됨
- **모든 주장에 인라인 출처** — `gatherSections` 프롬프트의 *"Cite each claim inline as [URL]"* 규칙이 5개 섹션 모두에 일관되게 적용
- **시점 박기** — `current_date()` 도구가 `frameReport` 단계에서 1회 호출되어 executive summary 에 *"2026-05-06 기준"* 자동 삽입
