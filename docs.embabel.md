# Embabel (엠베이블)

> Spring Framework 창시자 **Rod Johnson**이 만든 **JVM 기반 AI 에이전트 프레임워크**.
> Kotlin으로 작성되었지만 Java에서도 그대로 사용 가능하며, Spring Boot 위에서 동작합니다.

---


## 목차

- [1. 들어가기 — 무엇이고, 왜 JVM 인가](#1-들어가기--무엇이고-왜-jvm-인가)
- [2. Perplexity clone cording (feat Embabel)](#2-perplexity-clone-cording-feat-embabel)
- [3. Embabel 아키텍처](#3-embabel-아키텍처)
- [4. GOAP 플래너 알고리즘 — Embabel 라이브러리 실제 코드](#4-goap-플래너-알고리즘--embabel-라이브러리-실제-코드)
- [5. AI가 실수하면 어떻게 되나 — 오류 처리와 재계획](#5-ai가-실수하면-어떻게-되나--오류-처리와-재계획)
- [6. 실증 — Brexit 보고서 한 번 돌려보기](#6-실증--brexit-보고서-한-번-돌려보기)
- [7. 다른 도구와 비교 — 왜 굳이 Embabel 인가](#7-다른-도구와-비교--왜-굳이-embabel-인가)
- [8. 개선 방향 — 본 프로젝트를 더 좋게 만드는 길](#8-개선-방향--본-프로젝트를-더-좋게-만드는-길)

---

## 1. 들어가기 — 무엇이고, 왜 JVM 인가

### 한 줄 정체성

> **"플래닝은 LLM이 잘하는 일이 아니다."**

LLM 은 *각 단계를 잘 수행* 하지만, *어떤 순서로 단계를 밟을지 결정* 하는 일은 비결정적이고 비싸며 디버깅이 어렵습니다. Embabel 은 이 두 책임을 분리합니다.

| 책임 | 누가 담당 |
|------|-----------|
| **무엇을 / 어떤 순서로** 할지 결정 | **GOAP 플래너** (결정론적, 코드) |
| **각 단계를 어떻게 수행** 할지 | **LLM** (`@Action` 함수 안) |

GOAP (Goal-Oriented Action Planning) 은 원래 게임 AI 에서 쓰던 알고리즘. **결정론적 플래닝 알고리즘** 으로 최적의 행동 순서를 찾아냅니다 (§4 ↗ 부록 A).

### 왜 JVM — 언어 포지셔닝 한 페이지

AI 도구가 *서로 다른 언어* 를 쓰는 이유를 알면 Embabel 의 자리가 또렷해집니다.

```
   클라이언트 / CLI / IDE             서버 / 백엔드 / 백오피스                  데이터 사이언스 / 노트북
   ───────────────────────         ────────────────────────             ─────────────────────
   Claude Code, OpenCode,          Spring Boot, Node, Rails              Jupyter, Colab,
   Cursor, Continue.dev            엔터프라이즈 시스템                        ML 파이프라인
        │                                  │                                    │
        ▼                                  ▼                                    ▼
   TypeScript / Rust              JVM (Embabel) 또는 Node                  Python (LangChain)
```

| 카테고리 | 언어가 그것인 이유 |
|----------|--------------------|
| **코딩 에이전트** (Claude Code, Cursor) | **클라이언트 측** 도구 → 단일 바이너리(`npm i -g`/Rust binary), 즉시 시작(<100ms), 작은 풋프린트, 노드 IDE 생태계. JVM 은 cold start·JDK 의존성으로 부적합 |
| **연구·노트북** (LangChain, LangGraph) | Jupyter 친화·ML 라이브러리(torch/numpy/transformers)·빠른 프로토타이핑 — 그러나 GIL·DI 표준 부재·`@Transactional` 부재로 **엔터프라이즈 서버에 그대로 들고가기 어려움** |
| **엔터프라이즈 서버 에이전트** (Embabel) | Spring Security/Data/Actuator + Resilience4j + Micrometer + OpenTelemetry + JMX/JFR + 단일 jar/war + LTS 호환성 — **운영 인프라와 자연 결합** |

> **본 프로젝트의 자리**: 사내망 LLM 게이트웨이 + 기존 Spring 인프라 + Tavily 검색 → *엔터프라이즈 서버 에이전트* 카테고리. 현재 가장 성숙한 옵션이 Embabel + Spring AI.

---

## 2. Perplexity clone cording (feat Embabel)

### 한 줄 요약

> **사용자가 던진 한 문장의 주제를, AI가 스스로 "조사 계획 → 자료 수집 → 글쓰기 → 종합"의 4단계를 밟아 한 편의 보고서로 만들어 돌려주는 API.**
> 그 과정을 끝나고 한 번에 보여주는 게 아니라, **단계마다 진행 상황을 실시간으로 흘려보내는 것**이 핵심입니다.

---

### 비유 한 컷 — "리서치 팀에 보고서를 의뢰하는 일"

이 API를 사람의 일에 빗대면 다음과 같습니다.

| 현실의 리서치 팀 | `/api/deep-research`에서의 대응 |
|------------------|-------------------------------|
| 의뢰인이 "이 주제로 보고서 부탁드려요"라고 말함 | 사용자가 주제 한 줄을 POST로 보냄 |
| 팀장이 주제를 5개의 소주제로 쪼개 분담을 짬 | **1단계: 계획 수립 (planSubtopics)** |
| 각 팀원이 자기 소주제를 검색·조사해서 한 단락씩 씀 | **2단계: 섹션 작성 (gatherSections)** |
| 팀장이 도입부와 결론을 붙여 전체 흐름을 만듦 | **3단계: 보고서 프레이밍 (frameReport)** |
| 모든 조각을 모아 한 권의 보고서로 제본함 | **4단계: 최종 조립 (assembleReport)** |
| 의뢰인이 옆에서 "지금 어디까지 됐어요?" 물어보면 팀이 진행 상황을 알려줌 | **SSE 스트리밍 (단계별 이벤트 실시간 전송)** |

즉, 이 API는 단순한 "검색"이 아니라 **"AI가 작은 리서치 팀처럼 일하도록 만든 파이프라인"** 입니다.

---

### 전체 흐름 — 한 번의 요청이 거치는 길

```
   [사용자]
      │  ① "AI 에이전트의 기업 도입 트렌드"  (POST /api/deep-research/stream)
      ▼
   ┌──────────────────────────────────────────────────────┐
   │  웹 입구 (ResearchHandler / Router)                   │
   │   · 요청을 받아 도메인 언어로 번역                       │
   │   · 응답 채널을 "스트리밍 모드"로 열어둠                  │
   └──────────────────────────────────────────────────────┘
      │
      ▼
   ┌──────────────────────────────────────────────────────┐
   │  업무 코디네이터 (DeepResearchService)                 │
   │   · "이 일은 딥리서치 담당자에게 맡겨"라고 위임만 함        │
   └──────────────────────────────────────────────────────┘
      │
      ▼
   ┌──────────────────────────────────────────────────────┐
   │  Embabel 에이전트 플랫폼                                │
   │   · GOAP 플래너가 "어떤 순서로 일할지"를 결정              │
   │   · 매 단계마다 진행 이벤트를 발생시킴                      │
   │                                                       │
   │   ① planSubtopics    →  소주제 5개로 분해                │
   │   ② gatherSections   →  소주제마다 Tavily 검색 + 글쓰기   │
   │   ③ frameReport      →  도입부 · 결론 작성                │
   │   ④ assembleReport   →  최종 보고서 한 덩어리로 조립        │
   └──────────────────────────────────────────────────────┘
      │
      ▼  (단계마다 이벤트가 실시간으로 흘러나감)
   [사용자 화면]
      · "started"                ← 시작했어요
      · "plan-formulated"        ← 소주제 5개 정했어요
      · "section-drafted" × 5    ← 섹션 하나 완성됐어요 (5번 반복)
      · "deep-research-completed" ← 최종 보고서 나왔어요
```

---

### 4단계를 좀 더 자세히

#### 1단계 — 계획 수립 (`planSubtopics`)
사용자가 던진 한 문장의 주제를 LLM이 받아서, **서로 겹치지 않는 5개의 소주제** 로 쪼갭니다.
예: "AI 에이전트의 기업 도입 트렌드" → ① 시장 규모, ② 주요 벤더, ③ 도입 사례, ④ 보안·거버넌스 이슈, ⑤ 향후 전망.
→ 이 시점에 사용자에게 **"plan-formulated"** 이벤트가 한 번 전송됩니다.

#### 2단계 — 섹션 작성 (`gatherSections`)
각 소주제마다 **별도의 LLM 호출** 이 일어납니다. LLM은 도구 상자에서 **Tavily 검색** 을 1~2번 호출해 실제 웹에서 자료를 가져온 뒤, 약 300단어짜리 단락 하나를 씁니다.
중요한 점은 **모든 주장에 출처 URL을 인라인으로 붙인다는 것** — "근거 없는 글은 쓰지 않는다"는 규칙이 프롬프트에 박혀 있습니다.
→ 섹션이 하나 완성될 때마다 **"section-drafted"** 이벤트가 사용자에게 전송됩니다 (5개면 5번).

#### 3단계 — 보고서 프레이밍 (`frameReport`)
이미 작성된 섹션들의 첫 줄만 훑어보고, **앞에 붙일 요약(Executive Summary)과 뒤에 붙일 결론** 을 만듭니다.
이때 LLM은 `current_date()` 도구를 한 번 호출해 "as of YYYY-MM-DD"라는 시점을 박아 넣습니다 — 보고서가 언제 기준인지 분명히 하기 위함입니다.

#### 4단계 — 최종 조립 (`assembleReport`)
LLM 호출 없이 **순수 코드** 로 조각들을 합칩니다. 주제 + 요약 + 섹션들 + 출처 모음 + 결론 → 하나의 `DeepResearchReport` 객체.
→ 사용자에게 마지막으로 **"deep-research-completed"** 이벤트와 함께 완성된 보고서가 전송되고 연결이 종료됩니다.

---

### 왜 "스트리밍" 인가 — 사용자 경험 관점

이 작업은 빠르면 30초, 길면 2~3분이 걸립니다. 만약 끝날 때까지 화면이 멈춰 있다면 사용자는 "고장난 건가?" 의심하게 됩니다.
그래서 이 API는 **HTTP 응답을 한 번에 닫지 않고** 열어둔 채, 단계가 진행될 때마다 작은 메시지(이벤트)를 흘려보냅니다 (SSE = Server-Sent Events).

사용자는 화면에서 다음과 같은 **진행 막대** 를 볼 수 있게 됩니다.

```
✓ 시작됨
✓ 5개 소주제로 분해 완료
✓ "시장 규모" 섹션 작성 완료 (출처 3개)
✓ "주요 벤더" 섹션 작성 완료 (출처 4개)
... (계속)
✓ 최종 보고서 완성
```

이게 ChatGPT/Perplexity의 "AI가 생각하고 있어요…" 같은 표시와 본질적으로 같은 메커니즘입니다.

---

### 왜 "AI에게 다 맡기지" 않고 단계를 나누었나 — 설계 철학

LLM은 **각 단계를 잘 수행** 하지만, "어떤 순서로 일할지" 스스로 결정하게 하면 종종 길을 잃거나, 중복된 일을 하거나, 비싸지고 디버깅이 어려워집니다.
그래서 이 API는 책임을 명확히 갈라 놓았습니다.

| 누가 | 무엇을 |
|------|--------|
| **코드 (GOAP 플래너)** | "계획 → 수집 → 프레이밍 → 조립" 의 **순서**를 결정론적으로 보장 |
| **LLM** | 각 박스 안에서 "이 소주제를 어떻게 잘 쓸지" 같은 **창의적 작업**만 담당 |
| **Tavily 검색** | LLM이 모르는 **최신 사실** 을 외부 웹에서 가져옴 |

이 구조 덕분에:
- 같은 주제로 두 번 돌려도 **순서와 흐름이 일정** 합니다 (재현성).
- 어느 단계에서 실패했는지 **이벤트 로그로 정확히 추적** 됩니다 (관측성).
- LLM이 폭주해 끝없이 검색을 반복하는 일이 없습니다 (안전성).

---

### 한 줄로 다시

> `/api/deep-research`는 **"AI 리서치 팀장 + 5명의 LLM 작가 + 검색 도구"** 를 코드가 지휘해, **사용자에게 진행 과정을 실시간 중계하면서** 한 편의 출처 있는 보고서를 만들어 보내주는 엔드포인트입니다.

---

### "실시간 스트리밍" 은 어떻게 구현되나 — `StreamingAgentEventListener`

§2 가 약속한 *"단계마다 진행 상황을 실시간으로 흘려보낸다"* 는 한 줄. 이게 **실제로 어떻게 구현되는지** 가 본 프로젝트의 가장 영리한 부분입니다.

#### 문제 — "Embabel 의 이벤트 vs 사용자에게 보낼 이벤트"

Embabel 라이브러리 내부는 *저수준 이벤트 23종* 을 발생시킵니다 (`ActionExecutionStartEvent`, `ToolCallRequestEvent`, `LlmRequestEvent`, `ObjectAddedEvent`, ...).
하지만 사용자가 화면에서 보고 싶은 건 *"섹션 3개 완성됨"* 같은 **도메인 언어** 입니다.

→ **번역기가 필요합니다.** 그게 `StreamingAgentEventListener`.

#### 비유 — "야구 중계"

야구 경기장에서는 매 순간 수백 가지 사건이 일어납니다 (선수 위치, 공 속도, 심판 판정...). 하지만 TV 중계는 그걸 다 보여주지 않고 **"안타!", "홈런!", "삼진!"** 같은 *해석된 이벤트* 만 시청자에게 전달합니다.

`StreamingAgentEventListener` 가 정확히 그 역할입니다 — Embabel 내부에서 일어나는 모든 일을 듣고, **사용자가 의미 있게 받아들일 수 있는 5개 이벤트** 로만 추려서 흘려보냅니다.

#### 실제 코드 — 단순한 `when` 분기 하나

```kotlin
class StreamingAgentEventListener(
    private val sink: Sinks.Many<ResearchEvent>,
) : AgenticEventListener {

    override fun onProcessEvent(event: AgentProcessEvent) {
        val mapped = when (event) {
            is ActionExecutionStartEvent  -> ResearchEvent.ActionStarted(...)
            is ActionExecutionResultEvent -> ResearchEvent.ActionCompleted(...)
            is ToolCallRequestEvent       -> ResearchEvent.ToolInvoked(...)
            is ToolCallResponseEvent      -> ResearchEvent.ToolReturned(...)
            else -> null                   // ← 나머지 18종 이벤트는 무시
        }
        if (mapped != null) sink.tryEmitNext(mapped)
    }
}
```

→ 본 프로젝트는 **23종 중 4종** 만 사용자에게 흘려보냅니다. 나머지는 의도적으로 버림 (사용자에게 의미 없음).

#### 두 리스너를 합치는 트릭 — `MulticastListener`

§2 의 진행 이벤트에는 **"plan-formulated"** 와 **"section-drafted"** 도 있었습니다. 이건 위 4종 어디에도 없는데, 어떻게 나오나?

답 — **두 번째 리스너 `PlanAndSectionListener` 가 별도로 등록되어 있고, 두 리스너를 `MulticastListener` 가 합칩니다**.

```kotlin
val baseListener = StreamingAgentEventListener(sink)    // ← 저수준 → 도메인
val planListener = PlanAndSectionListener(sink)         // ← 도메인 객체 등장 감지
val combined     = MulticastListener(listOf(baseListener, planListener))
```

`PlanAndSectionListener` 는 *월드에 새 객체가 추가되는 순간* 만 감지합니다.

```kotlin
override fun onProcessEvent(event: AgentProcessEvent) {
    if (event !is ObjectAddedEvent) return
    when (val payload = event.value) {
        is AgentResearchPlan -> sink.tryEmitNext(
            ResearchEvent.PlanFormulated(payload.subtopics.map { it.title }),
        )
        is AgentSection -> sink.tryEmitNext(
            ResearchEvent.SectionDrafted(title = payload.title, sourceCount = payload.sources.size),
        )
    }
}
```

→ 즉 **`AgentResearchPlan` 객체가 만들어지는 순간 → `PlanFormulated` 이벤트 발생**, **`AgentSection` 객체가 만들어지는 순간 → `SectionDrafted` 이벤트 발생**.

#### 전체 그림 — 2단 변환 + 합성

```
   Embabel 내부 (저수준 이벤트 23종)
       │
       ├── ActionExecution / ToolCall / ...
       │      │
       │      ▼
       │   StreamingAgentEventListener  ──┐
       │      "저수준 → 도메인 4종 매핑"    │
       │                                  ├──► sink (Reactor) ──► SSE 스트림 ──► [사용자]
       ├── ObjectAddedEvent               │
       │      │                           │
       │      ▼                           │
       │   PlanAndSectionListener      ──┘
       │      "도메인 객체 등장 감지 → 마일스톤 이벤트"
       │
       └── (나머지 이벤트들 — 둘 다 무시)
```

→ 이 두 리스너가 **`MulticastListener` 로 합쳐져 한 번에 등록** 되고, 둘이 만든 이벤트는 같은 `sink` (Reactor 채널) 에 모여 **순서대로 SSE 로 흘러나갑니다**.

#### §6 Brexit 케이스로 다시 보면

사용자가 본 22개의 SSE 이벤트는 다음 두 경로의 합산이었습니다.

| 사용자가 본 이벤트 | 어느 리스너가 만들었나 |
|------------------|---------------------|
| `started` | (별도 — `channelFlow` 가 직접 emit) |
| `tool-invoked` × 11 | **StreamingAgentEventListener** ← `ToolCallRequestEvent` |
| `tool-returned` × 11 | **StreamingAgentEventListener** ← `ToolCallResponseEvent` |
| `plan-formulated` (있었다면) | **PlanAndSectionListener** ← `AgentResearchPlan` 등장 |
| `section-drafted` × 5 (있었다면) | **PlanAndSectionListener** ← `AgentSection` 등장 |
| `deep-research-completed` | (별도 — `agentJob` 종료 시 emit) |

#### 왜 이 설계가 영리한가

1. **저수준과 고수준의 분리** — Embabel 의 23종 이벤트를 다 노출했다면 사용자 화면이 디버그 콘솔처럼 됐을 것. *번역 레이어* 가 사용자 경험을 깨끗하게 유지.
2. **두 리스너의 역할 분담** — "저수준 이벤트 변환" 과 "도메인 객체 감지" 를 한 클래스에 우겨넣지 않고 분리. 새 도메인 마일스톤 추가는 `PlanAndSectionListener` 만 수정.
3. **`MulticastListener` 패턴** — 리스너 N개를 하나처럼 쓸 수 있어 향후 *메트릭 수집 리스너*, *감사 로그 리스너* 등을 쉽게 추가 가능.

#### 한 줄 요약

> **§2 가 약속한 "실시간 스트리밍" 은 마법이 아니라 30줄짜리 번역기 두 개**. 하나는 *"Embabel 의 저수준 신호 → 사용자가 알아들을 도메인 이벤트"* 로 번역하고, 다른 하나는 *"새로운 도메인 객체가 등장하는 순간"* 을 마일스톤으로 잡아냅니다. `MulticastListener` 가 둘을 합쳐서 같은 SSE 채널로 흘려보내는 것이 전부.

--- 

## 3. Embabel 아키텍처

### 3.1 5가지 빌딩 블록 (Building Blocks)

| 요소 | 의미 | 코드에서의 모습 |
|------|------|------------------|
| **Actions** | 에이전트가 수행하는 단계 | `@Action fun planSubtopics(...)` |
| **Goals** | 달성하려는 종착 상태 | `@AchievesGoal` 가 붙은 `@Action` |
| **Conditions** | 액션 실행 전/목표 달성 판단 조건 | 입력 타입 매칭 (e.g. `AgentResearchPlan` 가 월드에 있어야 `gatherSections` 가능) |
| **Domain Model** | Kotlin `data class` / Java `record` | `AgentResearchPlan`, `AgentSection`, ... |
| **Tools** | 액션 *안에서* LLM 이 호출할 수 있는 능력 | `@LlmTool fun tavilySearch(...)` |


#### Actions

**정의** — 에이전트가 수행하는 **하나의 작업 단위**. Kotlin 함수 위에 `@Action` 어노테이션을 붙이면 그 함수가 곧 하나의 액션이 됩니다.

`DeepResearchAgent.kt` 에는 4개의 액션이 정의되어 있습니다.

```kotlin
@Action
fun planSubtopics(userInput: UserInput, context: OperationContext): AgentResearchPlan = ...

@Action
fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts = ...

@Action
fun frameReport(plan: AgentResearchPlan, drafts: AgentSectionDrafts, context: OperationContext): AgentReportFraming = ...

@AchievesGoal(description = "A multi-section research report has been produced")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
fun assembleReport(plan: AgentResearchPlan, drafts: AgentSectionDrafts, framing: AgentReportFraming): AgentDeepResearchReport = ...
```

**핵심 규칙 — "입력 타입은 전제, 출력 타입은 결과"**
- `gatherSections` 의 입력은 `AgentResearchPlan` 입니다 → "이 액션을 실행하려면 월드에 `AgentResearchPlan` 객체가 이미 존재해야 한다" 는 뜻.
- `gatherSections` 의 출력은 `AgentSectionDrafts` 입니다 → "이 액션이 끝나면 월드에 `AgentSectionDrafts` 가 추가된다" 는 뜻.

이 입출력 타입의 사슬이 그대로 **실행 순서를 결정** 합니다. 개발자는 "1번 다음에 2번을 호출하라" 같은 순서 코드를 한 줄도 쓰지 않았는데, 플래너가 타입을 보고 자동으로 다음 그림을 그려냅니다.

```
UserInput
   │ (planSubtopics 가 받음)
   ▼
AgentResearchPlan
   │ (gatherSections 가 받음)
   ▼
AgentSectionDrafts                  AgentResearchPlan
        │                                  │
        └────────── frameReport 가 둘 다 받음 ──────────┘
                          │
                          ▼
                  AgentReportFraming
                          │
                          ▼ (assembleReport 가 plan + drafts + framing 셋 다 받음)
                  AgentDeepResearchReport  ← 목표 달성
```

**액션 옵션 — `assembleReport` 만 다른 점**
`@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)` 가 붙어 있습니다. 이 마지막 단계는 LLM 호출 없이 **순수 코드로 객체만 합치는 작업** 이라, 실패할 가능성이 거의 없고 만약 실패하면 재시도해도 같은 결과 → "한 번만 시도하고 끝" 으로 명시한 것입니다. 반면 LLM을 호출하는 앞 3개 액션은 (어노테이션 미지정 → 기본 정책으로) 일시적 LLM 오류 시 재시도됩니다.

---

#### Goals

**정의** — 에이전트가 도달하면 **"임무 완료"** 로 간주되는 종착 상태. 별도 클래스가 아니라, **특정 액션 위에 `@AchievesGoal` 어노테이션을 붙여** 표시합니다.

`DeepResearchAgent.kt` 에서 목표를 선언한 부분은 단 한 줄입니다.

```kotlin
@AchievesGoal(description = "A multi-section research report has been produced")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
fun assembleReport(...): AgentDeepResearchReport = ...
```

**해석**:
- `assembleReport` 는 평범한 액션이지만, `@AchievesGoal` 이 붙은 순간 **"이 액션의 출력 타입(`AgentDeepResearchReport`)이 월드에 등장하면 임무 종료"** 라는 의미가 됩니다.
- 즉 **목표 = "특정 도메인 객체가 월드에 존재하는 상태"** 입니다.

**플래너의 역할 — "역방향 추적"**
플래너는 시작 시점에 다음 식으로 사고합니다.

```
질문: AgentDeepResearchReport 를 어떻게 만들지?
  → assembleReport(plan, drafts, framing) 가 만들 수 있군.
  → 그러려면 AgentReportFraming 이 필요해.
    → frameReport(plan, drafts) 가 만들 수 있군.
    → 그러려면 AgentSectionDrafts 가 필요해.
      → gatherSections(plan) 가 만들 수 있군.
      → 그러려면 AgentResearchPlan 이 필요해.
        → planSubtopics(userInput) 가 만들 수 있군.
        → 그러려면 UserInput 이 필요해 — 사용자가 처음에 줬으니 OK.
```

이 역추적 결과가 곧 **"plan → gather → frame → assemble"** 이라는 실행 순서가 됩니다. 개발자가 순서를 직접 짜지 않았다는 점이 핵심.

**한 에이전트에 여러 Goal 도 가능**
지금은 `@AchievesGoal` 이 하나뿐이지만, 만약 "짧은 요약본만 만드는 액션" 위에도 같은 어노테이션을 붙이면, 플래너는 사용자 요청에 따라 둘 중 더 짧은 경로를 선택할 수 있습니다.

---

#### Conditions

**정의** — 액션이 실행 가능한지, 목표가 달성되었는지를 판정하는 **참/거짓 조건**. Embabel 에서는 별도 함수로 작성할 수도 있지만, **가장 흔한 형태는 "함수 파라미터의 타입이 월드에 있는지" 자체** 입니다.

`DeepResearchAgent.kt` 에는 명시적 condition 함수가 없습니다 — **타입 시스템이 condition 역할을 대신** 하기 때문입니다.

```kotlin
@Action
fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts = ...
//                       ↑ 이 한 줄이 곧 condition:
//                       "월드에 AgentResearchPlan 인스턴스가 존재할 때만 이 액션이 실행 가능하다"
```

**예시로 풀어 보기**

| 시점 | 월드(Blackboard) 에 있는 객체 | 실행 가능한 액션 |
|------|----------------------------|-----------------|
| 시작 직후 | `UserInput` | `planSubtopics` 만 (다른 셋은 입력 타입이 아직 없음) |
| `planSubtopics` 직후 | `UserInput`, `AgentResearchPlan` | `gatherSections` 가능해짐 |
| `gatherSections` 직후 | + `AgentSectionDrafts` | `frameReport` 가능해짐 (plan + drafts 둘 다 있으니) |
| `frameReport` 직후 | + `AgentReportFraming` | `assembleReport` 가능해짐 (plan + drafts + framing 다 있음) |
| `assembleReport` 직후 | + `AgentDeepResearchReport` | **목표 달성 → 종료** |

**왜 이 설계가 영리한가**
개발자는 if/else 로 "plan 이 비어있으면 gather 호출 금지" 같은 가드 코드를 한 줄도 쓰지 않았습니다. 그 모든 게 **타입 시그니처에 자연스럽게 녹아 있음**.
즉 Embabel 에서 "Conditions" 의 90% 는 **Kotlin 컴파일러가 이미 검증해 주는 타입 정보** 그 자체입니다.

(나머지 10% — 예: "사용자가 유료 플랜인 경우에만" 같은 비즈니스 룰 — 는 별도 condition 함수로 표현 가능하지만, 이 샘플에는 등장하지 않습니다.)

---

#### Domain Model

**정의** — 액션 사이를 흘러다니는 **데이터의 모양**. Kotlin `data class` 로 정의되며, 이게 곧 plan 그래프의 **노드(상태)** 가 됩니다.

`DeepResearchAgent.kt` 의 도메인 모델은 5개입니다.

```kotlin
data class AgentResearchSubtopic(val title: String, val rationale: String)

data class AgentResearchPlan(
    val mainTopic: String,
    val subtopics: List<AgentResearchSubtopic>,
)

data class AgentSection(
    val title: String,
    val body: String,
    val sources: List<String>,
)

data class AgentSectionDrafts(val sections: List<AgentSection>)

data class AgentReportFraming(
    val executiveSummary: String,
    val conclusion: String,
)

data class AgentDeepResearchReport(
    val topic: String,
    val executiveSummary: String,
    val sections: List<AgentSection>,
    val sources: List<String>,
    val conclusion: String,
)
```

**각 모델의 역할**

| 도메인 모델 | 누가 만드는가 | 누가 소비하는가 | 의미 |
|------------|-------------|---------------|------|
| `AgentResearchPlan` | `planSubtopics` | `gatherSections`, `frameReport`, `assembleReport` | "어떤 소주제로 쪼갰나" 의 청사진 |
| `AgentSection` | `gatherSections` (소주제 수만큼) | (`AgentSectionDrafts` 안에 묶임) | 한 섹션의 본문 + 출처 |
| `AgentSectionDrafts` | `gatherSections` | `frameReport`, `assembleReport` | 작성된 섹션들의 묶음 |
| `AgentReportFraming` | `frameReport` | `assembleReport` | 도입부 요약 + 결론 |
| `AgentDeepResearchReport` | `assembleReport` | (외부로 반환됨) | 최종 보고서 — **이 타입의 등장이 곧 목표 달성** |

**왜 LLM 출력이 `String` 이 아니라 `data class` 인가**
`planSubtopics` 안의 코드를 보면:

```kotlin
context.ai()
    .withLlm(LlmOptions.withAutoLlm().withTemperature(0.3))
    .create(
        """
        ... 프롬프트 ...
        Return AgentResearchPlan with mainTopic (echo the user input) and subtopics.
        """.trimIndent(),
    )
```

함수의 **반환 타입이 `AgentResearchPlan`** 이라는 사실 하나로:
1. Embabel 이 LLM 에게 "이 JSON 스키마에 맞춰 응답하라" 고 자동 지시,
2. 응답을 자동으로 `AgentResearchPlan` 객체로 파싱,
3. 파싱된 객체를 월드에 자동 등록,
4. → 그 결과 `gatherSections` 의 condition 이 만족되어 다음 단계가 자동으로 트리거됨.

이 4단계가 모두 **타입 정보 하나로** 일어납니다.

**Domain Model 이 1급 시민** — Embabel 의 가장 차별적인 선택은 **함수 시그니처의 도메인 타입이 곧 plan 의 노드** 라는 점입니다. LangChain 처럼 문자열을 주고받는 게 아니라, 타입이 명확한 객체가 흘러다니므로 IDE 자동완성·컴파일 타임 검증·테스트가 모두 평범한 Spring 코드와 동일하게 작동합니다.

---

#### Tools (`@LlmTool`)

**정의** — 액션 *안에서* LLM 이 호출할 수 있는 **외부 능력**. Kotlin 함수 위에 `@LlmTool` 어노테이션을 붙이면, LLM 이 액션을 수행하는 도중 *"이 도구를 써야겠다"* 고 판단할 때 자동으로 호출됩니다.

**Action vs Tool 의 차이** — 헷갈리기 쉬운 포인트:

| 구분 | Action | Tool |
|------|--------|------|
| 누가 부르나 | **GOAP 플래너** (코드) | **LLM** (런타임 판단) |
| 언제 부르나 | plan 그래프의 정해진 순서 | 액션 실행 도중 필요할 때 |
| 입력 | 도메인 객체 (월드에서) | LLM 이 채우는 인자 (자연어 → JSON) |
| 어노테이션 | `@Action` | `@LlmTool` |
| 비유 | "이 단계는 시켜야 한다" | "필요하면 가져다 써도 좋다" |

→ 즉 **Action 은 *반드시 실행되는* 단계**, **Tool 은 *LLM 이 필요하다고 판단하면 쓰는* 도구상자**.

---

**본 프로젝트의 `@LlmTool` 2개**

`ResearchTools.kt` 에 정의되어 있습니다.

```kotlin
@Component
class ResearchTools(
    @param:Value("\${tavily.api-key:}") private val tavilyApiKey: String,
    private val webClient: WebClient = defaultWebClient(),
) {
    @LlmTool(
        name = "current_date",
        description = "Returns today's date in ISO-8601 format (YYYY-MM-DD). " +
            "Call this once and embed the result in the headline as 'as of YYYY-MM-DD'.",
    )
    fun currentDate(): String =
        LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @LlmTool(
        name = "tavily_search",
        description = "Web search optimized for LLM grounding. " +
            "Call this BEFORE drafting keyPoints whenever the topic mentions temporal terms " +
            "('today', 'recent', 'latest'), proper nouns you are not certain about, " +
            "or any time-sensitive fact. Cite the returned URLs inline in keyPoints.",
    )
    fun tavilySearch(
        @LlmTool.Param(description = "Concise search query in the language most likely to retrieve relevant sources.")
        query: String,
        @LlmTool.Param(description = "Maximum number of results to return (1..10).", required = false)
        maxResults: Int = 5,
    ): List<TavilyResult> = ...
}
```

→ 이 두 도구가 액션에 등록되는 곳:

```kotlin
// DeepResearchAgent.gatherSections 에서
context.ai()
    .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
    .withToolObject(researchTools)            // ← 이 한 줄이 도구 노출
    .create<AgentSection>(""" ... 프롬프트 ... """)
```

`.withToolObject(researchTools)` 한 줄로 **LLM 이 그 액션을 수행하는 동안 `tavily_search` 와 `current_date` 를 호출할 수 있게 됨**.

---

**핵심 규칙 — "description 이 곧 사용 설명서"**

`@LlmTool` 에서 가장 중요한 건 함수 이름이 아니라 **`description` 문자열** 입니다. LLM 은 이 설명을 보고 *"언제 이 도구를 써야 하는지"* 를 결정합니다.

- ✅ 좋은 description — `"Call this BEFORE drafting whenever topic mentions 'today', 'recent', or time-sensitive fact"`
- ❌ 나쁜 description — `"Web search"`

본 프로젝트의 `tavily_search` description 은 *"~할 때 호출해라, 결과 URL 을 인라인으로 인용해라"* 까지 명시 → §6 Brexit 케이스에서 LLM 이 11번이나 검색을 하면서도 모든 인용을 인라인으로 붙인 이유.

---

**§6 Brexit 케이스에서 본 `@LlmTool` 의 작동**

```
[gatherSections 액션 시작]
   LLM: "환율 섹션을 쓰려는데 최신 데이터 필요"
       → tavily_search("UK pound exchange rate 2024 2025") ← LLM 이 직접 결정
       ← 결과: NIESR, Reuters URL ...
   LLM: "한국 자료도 보면 좋겠다"
       → tavily_search("영국 파운드 환율 변동성 ... 브렉시트") ← LLM 이 언어까지 선택
       ← 결과: KDI, a-ha URL ...
   LLM: 본문 작성 + [URL] 인용 인라인 삽입
[gatherSections 액션 종료]
```

→ 이 모든 호출 결정은 **LLM 이 description 을 보고 한 것**. 코드는 *"이런 도구가 있다"* 고 알려준 것뿐.

---

**한 줄 요약**

> **Action 은 코드가 명령하는 단계, Tool 은 LLM 이 자율적으로 가져다 쓰는 능력.** `@LlmTool` 의 `description` 문구가 LLM 의 사용 판단을 좌우하므로, 도구를 추가할 때는 *"언제 호출해야 하는지"* 까지 description 에 명시하는 게 핵심입니다.

--- 

### 3.2 OODA 실행 모델

각 액션이 끝날 때마다 **재계획(replan)** 하면서 새로운 정보에 적응합니다.
```
 ┌─────────► Observe ──► Orient ──► Decide ──► Act ───┐
 │     이전 액션 결과    도메인 모델     다음 액션       액션 실행 
 │     도구 호출 응답    & 컨디션에      재선택         새 결과
 │     외부 상태 변화    비추어 해석     (replan)      (다시 Observe)
 └────────────────────────────────────────────────────┘
```

- **Observe** — 이전 액션 결과, 툴 호출 응답, 외부 상태 변화를 수집.
- **Orient** — 도메인 모델·현재 컨디션에 비추어 해석. GOAP 플래너가 새 사실을 월드 상태에 반영.
- **Decide** — 다음 액션 재선택. (코드: `Planner.bestValuePlanToAnyGoal(system)`)
- **Act** — 액션 실행 → 결과가 다시 Observe 로.

---

#### 풀어서 — "OODA가 뭔데?"

OODA 는 원래 **공군 전투기 조종사** 의 의사결정 모델입니다 (미 공군 대령 John Boyd, 1970년대).
공중전에서 살아남는 조종사는 **계획표를 그대로 따르는 사람** 이 아니라, **상황이 바뀔 때마다 빠르게 다시 판단하는 사람** 이라는 관찰에서 나왔습니다.

> **"한 번 세운 계획에 끝까지 매달리지 말고, 매 순간 다시 보고 다시 결정하라."**

이걸 AI 에이전트에 가져온 게 Embabel 의 실행 방식입니다.

#### 비유 — "내비게이션 vs 종이 지도"

| 종이 지도 (전통적 워크플로우) | 내비게이션 (Embabel OODA) |
|----------------------------|--------------------------|
| 출발 전에 모든 경로를 미리 그어둠 | 일단 한 단계만 가본다 |
| 길이 막혀도 계획대로 진행 | 한 단계 끝날 때마다 "지금 어디지? 다음은?" 다시 계산 |
| 도중에 사고가 나면 망함 | 사고 나면 우회로를 즉시 다시 그림 |

Embabel 은 **내비게이션 쪽** 입니다. 액션 하나가 끝날 때마다 플래너가 "지금 월드 상태가 이렇게 됐는데, 목표까지 가는 가장 좋은 다음 한 걸음은?" 을 **매번 새로 계산** 합니다.

#### 4단계를 일상어로

| OODA 단계 | 일상어로 | `DeepResearchAgent` 에서의 예 |
|----------|---------|------------------------------|
| **Observe** (관찰) | "방금 무슨 일이 있었지?" | `gatherSections` 가 끝났고 `AgentSectionDrafts` 가 월드에 추가됨 |
| **Orient** (정황 파악) | "그래서 지금 상황이 어떻지?" | 월드에 `UserInput` + `AgentResearchPlan` + `AgentSectionDrafts` 가 있음. `frameReport` 의 입력 조건이 충족됨 |
| **Decide** (결정) | "그럼 다음에 뭘 해야 하지?" | "목표(`AgentDeepResearchReport`)까지 가장 짧은 경로는 frameReport → assembleReport. frameReport 부터 실행하자" |
| **Act** (실행) | "한다." | `frameReport` 호출 → `AgentReportFraming` 생성 → 다시 Observe 로 |

#### 왜 "매번 다시 계산" 하는가 — 미리 짜두면 안 되나?

미리 짜둔 계획이 깨지는 흔한 시나리오 3가지:

1. **LLM 이 예상 못한 출력을 줌**
   `planSubtopics` 가 "5개 소주제" 가 아니라 "3개 + 1개의 메타 분석" 을 돌려준다면? → 다음 액션의 입력 형태가 달라짐 → 재계획 필요.

2. **외부 도구 호출이 실패함**
   Tavily 검색이 503 을 돌려주거나 결과가 0건이면? → "다른 액션으로 우회해야" 하거나 "재시도해야" → 재계획 필요.

3. **새로운 도메인 객체가 등장함**
   액션 실행 중 부수적으로 다른 객체가 월드에 추가되면 (예: 캐시된 결과 발견) → 더 짧은 경로가 생김 → 재계획해서 단축.

미리 짜둔 고정 워크플로우는 위 셋 모두에 취약합니다. OODA 루프는 **"계획이 아니라 목표만 고정하고, 매 순간 다시 길을 찾는다"** 는 점에서 강건합니다.

#### `DeepResearchAgent` 의 한 사이클을 통째로 돌려 보기

```
[시작] 월드: { UserInput("AI 에이전트 트렌드") }

▼ Observe : UserInput 이 새로 들어옴
▼ Orient  : "UserInput 만 있네. planSubtopics 만 실행 가능"
▼ Decide  : "planSubtopics 실행하자"
▼ Act     : planSubtopics 실행 → AgentResearchPlan 생성

[루프 1회 완료] 월드: { UserInput, AgentResearchPlan }

▼ Observe : AgentResearchPlan 이 새로 등장
▼ Orient  : "이제 gatherSections 의 입력 조건이 충족됨"
▼ Decide  : "gatherSections 실행하자"
▼ Act     : gatherSections 실행 → AgentSectionDrafts 생성

[루프 2회 완료] 월드: { UserInput, AgentResearchPlan, AgentSectionDrafts }

... (frameReport, assembleReport 도 같은 방식으로) ...

[루프 4회 완료] 월드에 AgentDeepResearchReport 등장 → 목표 달성 → 종료
```

각 루프 사이에서 플래너는 **"혹시 더 빠른 경로가 생겼나?"** 를 매번 검증합니다. 같은 결과가 4번 나오면 결국 직선 경로지만, **검증을 안 한 게 아니라 매번 검증해서 직선임을 확인** 한 것입니다.

#### 한 줄 요약

> **OODA = "한 걸음 걷고, 주변을 다시 보고, 다음 걸음을 다시 정한다."**
> Embabel 은 액션 하나가 끝날 때마다 GOAP 플래너를 다시 돌려, **변하는 월드 상태에 맞춰 매번 최적 경로를 다시 계산** 합니다. 그래서 LLM 이 엉뚱한 출력을 주거나, 외부 도구가 실패해도 **계획 전체가 무너지는 게 아니라 다음 한 걸음만 다시 정해지는** 강건한 실행이 됩니다.


## 4. GOAP 플래너 알고리즘 — Embabel 라이브러리 실제 코드

플래너는 외부 의존성 없이 순수 Kotlin으로 작성되어 있고, 패키지 `com.embabel.plan.*` 에 격리되어 있습니다.
핵심 파일은 5개입니다 (라이브러리 내부 경로):

```
com/embabel/plan/
├─ Plan.kt                  # Step / Action / Goal / Plan 추상화
├─ Planner.kt               # PlanningSystem, Planner 인터페이스
├─ WorldState.kt            # 마커 인터페이스
├─ goap/
│  ├─ OptimizingGoapPlanner.kt   # 미지(unknown) 조건 처리
│  └─ astar/
│     └─ AStarGoapPlanner.kt     # ★ A* 본체 (340 LoC)
└─ common/condition/
   └─ ConditionWorldState.kt     # WorldState = Map<String, TRUE/FALSE/UNKNOWN>
```

### 4.1 핵심 추상화 — `Plan.kt`

각 단계는 **value(가치)** 를, 각 액션은 추가로 **cost(비용)** 를 가집니다 (둘 다 0..1).

```kotlin
// com/embabel/plan/Plan.kt
typealias CostComputation = (state: WorldState) -> ZeroToOne

interface Step : Named, HasInfoString {
    override val name: String
    val value: CostComputation     // 0 (least valuable) .. 1 (most valuable)
}

interface Action : Step {
    val cost: CostComputation       // 0 .. 1, 1이 가장 비쌈
    fun netValue(state: WorldState): Double = value(state) - cost(state)
}

interface Goal : Step               // Goal은 cost가 없는 Step

open class Plan(val actions: List<Action>, val goal: Goal) : HasInfoString {
    fun isComplete() = actions.isEmpty()
    fun cost(state: WorldState): Double = actions.sumOf { it.cost(state) }
    fun netValue(state: WorldState): Double =
        goal.value(state) + actionsValue(state) - cost(state)
}
```

> **Plan = `List<Action> + Goal`**. 단순합니다. 복잡함은 *어떻게 이 리스트를 만드느냐* 에 있습니다.

### 4.2 월드 상태 — `ConditionWorldState.kt`

세계는 **`Map<조건이름, {TRUE | FALSE | UNKNOWN}>`** 입니다.
3-값 논리(three-valued logic)를 쓰는 이유: *아직 확인하지 않은 조건* 도 표현할 수 있어 lazy 평가가 가능합니다.

```kotlin
typealias ConditionState = Map<String, ConditionDetermination>   // TRUE / FALSE / UNKNOWN

interface ConditionWorldState : WorldState {
    val state: ConditionState

    operator fun plus(action: ConditionAction): ConditionWorldState   // 액션 적용
    operator fun plus(pair: Pair<String, ConditionDetermination>): ConditionWorldState

    fun unknownConditions(): Collection<String>
    infix fun satisfiesPreconditions(preconditions: EffectSpec): Boolean
}

// 액션 적용 = effects를 현재 state에 덮어쓰기
override operator fun plus(action: ConditionAction): ConditionWorldState {
    val newState = state.toMutableMap()
    action.effects.forEach { (key, value) -> newState[key] = value }
    return ConditionWorldState(newState as HashMap<String, ConditionDetermination>)
}
```

### 4.3 액션 — `ConditionAction.kt`

각 액션은 **preconditions(요구 조건)** 와 **effects(변화)** 를 선언합니다.

```kotlin
interface ConditionAction : ConditionStep, Action {
    val preconditions: EffectSpec   // 이 액션을 쓰려면 이 조건들이 만족되어야
    val effects: EffectSpec         // 이 액션을 쓰면 이 조건들이 이렇게 바뀐다

    override val knownConditions: Set<String>
        get() = preconditions.keys + effects.keys
}
```

> **본 프로젝트와의 매핑 (자동 생성됨)**
> Embabel은 `@Action fun gatherSections(plan: AgentResearchPlan, ...)` 같은 시그니처를 보고:
> - **precondition** = `"AgentResearchPlan_present" → TRUE` (입력 타입 존재)
> - **effect** = `"AgentSectionDrafts_present" → TRUE` (반환 타입 등록)
    > 식으로 자동 변환합니다. 코드에 `preconditions/effects` 를 직접 적지 않아도 됩니다.

### 4.4 A* 본체 — `AStarGoapPlanner.kt`

코드 헤더 주석이 알고리즘을 명료하게 설명합니다 (그대로 인용):

```kotlin
/**
 * Implements a Goal-Oriented Action Planning system using the A* algorithm.
 * A* works by finding the optimal sequence of actions to transform an initial
 * state into a goal state while minimizing total cost.
 *
 * The algorithm works as follows:
 * 1. Start with the initial world state
 * 2. Maintain an open list (priority queue) of states to explore, prioritized by f-score
 * 3. For each state, explore all achievable actions, calculating:
 *    - g-score: The cost accumulated so far to reach this state
 *    - h-score: A heuristic estimate of the remaining cost to reach the goal
 *    - f-score: g-score + h-score (total estimated cost)
 * 4. Always expand the state with the lowest f-score first
 * 5. Track visited states and their best known costs to avoid cycles and redundant exploration
 * 6. Continue until finding a state that satisfies the goal conditions
 */
internal class AStarGoapPlanner(...) : OptimizingGoapPlanner(...) {

    override fun planToGoalFrom(
        startState: ConditionWorldState,
        actions: Collection<ConditionAction>,
        goal: ConditionGoal,
    ): ConditionPlan? {
        // 0) 빠른 판정
        if (goal.isAchievable(startState)) return ConditionPlan(emptyList(), goal, ...)
        if (!isGoalReachable(startState, actions, goal)) return null   // 어떤 액션도 효과를 만들 수 없음 → null

        // 1) 자료구조
        val openList   = PriorityQueue<SearchNode>()                     // f-score로 정렬
        val gScores    = mutableMapOf<...>().withDefault { Double.MAX_VALUE }
        val cameFrom   = mutableMapOf<상태, Pair<이전상태, 액션?>>()     // 경로 복원용
        val closedSet  = mutableSetOf<ConditionWorldState>()

        // 2) 시작
        gScores[startState] = 0.0
        openList.add(SearchNode(startState, gScore = 0.0, hScore = heuristic(startState, goal)))

        var bestGoalNode: SearchNode? = null
        var bestGoalScore = Double.MAX_VALUE
        val maxIterations = 10000                                        // 무한 루프 방어

        // 3) 메인 루프
        while (openList.isNotEmpty() && iterationCount < maxIterations) {
            val current = openList.poll()                                // 가장 싼 노드 꺼냄

            if (current.state in closedSet) continue
            closedSet.add(current.state)

            if (goal.isAchievable(current.state)) {                      // 골 도달
                if (current.gScore < bestGoalScore) {
                    bestGoalNode = current; bestGoalScore = current.gScore
                }
                continue
            }

            // 더 구체적인(precondition 많은) 액션을 우선 시도
            val sortedActions = actions.sortedByDescending { it.preconditions.size }
            for (action in sortedActions) {
                if (!action.isAchievable(current.state)) continue        // precondition 미충족
                val nextState = current.state + action                   // effects 적용
                if (nextState == current.state) continue                 // 진전 없음 → skip

                val tentativeGScore = gScores.getValue(current.state) + action.cost(startState)
                if (tentativeGScore < gScores.getValue(nextState)) {     // 더 싼 경로 발견
                    cameFrom[nextState] = current.state to action
                    gScores[nextState]  = tentativeGScore
                    openList.add(SearchNode(nextState, tentativeGScore, heuristic(nextState, goal)))
                }
            }
        }

        // 4) 경로 복원 + 2-pass 최적화
        if (bestGoalNode != null) {
            val plan          = reconstructPath(cameFrom, bestGoalNode.state)
            val backwardOpt   = backwardPlanningOptimization(plan, startState, goal)
            val finalPlan     = forwardPlanningOptimization(backwardOpt, startState, goal)
            return ConditionPlan(finalPlan, goal, worldState = startState)
        }
        return null
    }
```

#### Heuristic — admissible (overestimate 안 함)

```kotlin
/** 현재 상태에서 골까지 남은 비용의 추정값.
 *  미충족 조건의 *개수*를 그대로 반환 → A*의 최적성 보장. */
private fun heuristic(state: ConditionWorldState, goal: ConditionGoal): Double =
    goal.preconditions.count { (key, value) -> state.state[key] != value }.toDouble()
```

이 휴리스틱은 단순하지만 **admissible** 합니다. 한 액션이 한 조건만 바꿀 수 있다고 가정하면 *최소 N개 액션은 필요*하기 때문. 따라서 A*가 **최적해(=비용 최소 plan)** 를 보장합니다.

#### 빠른 unreachability 체크

A* 들어가기 전에, 어떤 액션도 produce 못 하는 effect가 골에 있다면 즉시 `null` 반환:

```kotlin
private fun isGoalReachable(startState, actions, goal): Boolean {
    val producibleEffects = mutableSetOf<Pair<String, ConditionDetermination>>()
    for (action in actions) for ((k, v) in action.effects) producibleEffects.add(k to v)

    for ((key, value) in goal.preconditions) {
        if (startState.state[key] == value) continue                     // 이미 만족
        if ((key to value) !in producibleEffects) return false           // 누구도 못 만듦 → 도달 불가
    }
    return true
}
```

### 4.5 두 단계 최적화 — backward + forward

A*가 찾은 plan은 종종 **불필요한 우회**를 포함합니다 (탐색 중 들어간 상태 중 일부는 결과적으로 안 써도 됨).
그래서 두 번 정리합니다.

#### Backward — "골에 기여하는 액션만 유지"

```kotlin
private fun backwardPlanningOptimization(plan, startState, goal): List<ConditionAction> {
    val targetConditions = goal.preconditions.toMutableMap()             // "이 조건들이 필요"
    val keptActions = mutableListOf<ConditionAction>()

    for (action in plan.reversed()) {                                    // 끝에서부터
        var isNecessary = false
        for ((key, value) in action.effects) {
            if (targetConditions[key] == value) {                        // 필요한 조건을 만들어주면
                isNecessary = true
                targetConditions.remove(key)                             // 그 조건은 해결됨
                action.preconditions.forEach { (k, v) -> targetConditions[k] = v }   // 새 의존조건 추가
            }
        }
        if (isNecessary) keptActions.add(action)
    }
    return keptActions.reversed()
}
```

#### Forward — "실제로 진전을 만드는 액션만 유지"

```kotlin
val progressMade = nextState != currentState &&
    action.effects.any { (key, value) ->
        goal.preconditions.containsKey(key) &&
            currentState.state[key] != goal.preconditions[key] &&
            (value == goal.preconditions[key] || key !in nextState.state)
    }
```

해석:
1. `nextState != currentState` — 액션이 실제로 상태를 바꿨다.
2. 액션의 effect 중 하나가 **골이 요구하는 조건** 이고
3. 현재 그 조건이 **아직 골 값과 다르며**
4. 액션 적용 결과가 골 값과 일치하거나 (직접 진전), 그 조건을 제거함 (장애 제거).

세 조건을 모두 만족할 때만 plan에 남깁니다. 너무 공격적으로 잘라서 골을 못 이루면 원본 plan으로 롤백:

```kotlin
val finalState = simulatePlan(startState, optimizedPlan)
if (!goal.isAchievable(finalState) && plan.isNotEmpty()) {
    return plan   // 최적화가 과했으니 원본 반환
}
```

### 4.6 Planner 인터페이스 — `Planner.kt`

여러 골이 있을 때 **net value 가장 높은 plan** 을 고르는 로직:

```kotlin
interface Planner<S : PlanningSystem, W : WorldState, P : Plan> {
    fun worldState(): W
    fun planToGoal(actions: Collection<Action>, goal: Goal): P?

    /** 모든 골에 대해 plan을 만들고 net value 내림차순 정렬 */
    fun plansToGoals(system: PlanningSystem): List<P> {
        val state = worldState()
        return system.goals
            .mapNotNull { goal -> planToGoal(system.actions, goal) }
            .sortedByDescending { p -> p.netValue(state = state) }
    }

    fun bestValuePlanToAnyGoal(system: PlanningSystem): P? =
        plansToGoals(system).firstOrNull()

    /** 무한 루프 방지: replan 시 특정 액션 제외 */
    fun bestValuePlanToAnyGoal(system: PlanningSystem, excludedActionNames: Set<String>): P? { ... }

    /** Pruning: 어느 골도 만들 수 없는 액션은 제거 */
    fun prune(planningSystem: S): S
}
```

---

## 5. AI가 실수하면 어떻게 되나 — 오류 처리와 재계획

### 한 줄 요약

> **"AI 한 단계가 실패해도 보고서 전체가 망가지지는 않게" — Embabel 은 5겹의 안전망을 두고 있고, 본 프로젝트는 이미 그중 핵심 몇 개를 켜둔 상태입니다.**

---

### 비유 — "비행기의 다중 안전장치"

비행기는 엔진이 하나 꺼져도 떨어지지 않습니다. 엔진 2개, 연료 펌프 2개, 유압 계통 2개 — 한 군데가 망가져도 다른 게 받쳐주는 **이중·삼중 백업** 구조이기 때문입니다.

Embabel 의 오류 처리도 같은 사고방식입니다. *"한 군데서 실패하면 어디까지 자동으로 회복되는가"* 를 5단계로 설계해 두었습니다.

---

### 5겹 안전망 — 한눈에

| 층 | 무엇이 잡아내나 | 회복 방식 |
|----|---------------|----------|
| **① 액션 재시도** | LLM이 일시적으로 깨진 JSON 을 줌, 네트워크 일시 오류 | 같은 액션을 자동으로 한두 번 더 시도 |
| **② 도메인 검증 실패 → 재시도** | LLM이 형식은 맞췄지만 내용이 부실함 (예: 소주제가 1개뿐) | "이런 부분이 잘못됐으니 다시 만들어줘" 메시지를 붙여 LLM 재호출 |
| **③ GOAP 재계획** | 한 단계 완료 후 월드 상태가 예상과 다름 | 다음에 뭘 할지를 **매번 새로 계산** (§3.2 OODA) |
| **④ 명시적 재계획 요청** | 액션이 "내가 다시 계획되어야 함" 이라고 직접 신호 | `ReplanRequestedException` 던지면 플래너가 다른 경로 탐색 |
| **⑤ 무한 루프 방어** | 같은 액션이 끝없이 재시도됨 | 일정 횟수 초과 시 그 액션을 제외하고 다시 계획, 그래도 안 되면 실패로 종료 |

**핵심 인사이트** — 위에서 아래로 갈수록 "더 큰 실패" 를 다룹니다. 작은 오류는 ①에서, 진짜 막힌 상황만 ⑤까지 올라옵니다. 사용자에게는 대부분 ①~③ 선에서 조용히 회복되고, 진행 이벤트만 약간 늦게 도착하는 것처럼 보입니다.

---

### 본 프로젝트에 이미 켜져 있는 안전장치

`DeepResearchAgent.kt` 에서 명시적으로 선택한 정책은 **`assembleReport` 의 `FIRE_ONCE`** 한 줄입니다.

```kotlin
@AchievesGoal(description = "A multi-section research report has been produced")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
fun assembleReport(...): AgentDeepResearchReport = ...
```

| 액션 | 재시도 정책 | 이유 |
|------|-----------|------|
| `planSubtopics` | (기본) — 일시적 LLM 오류 시 재시도 | LLM 호출이라 가끔 깨진 출력 가능 |
| `gatherSections` | (기본) | 5번의 LLM 호출 + Tavily 검색이라 일시 오류 가능성 ↑ |
| `frameReport` | (기본) | 동일 |
| `assembleReport` | **FIRE_ONCE** | LLM 없이 순수 코드라 실패할 일이 거의 없고, 실패한다면 재시도해도 같은 결과 |

→ 즉 **LLM 단계는 자동 재시도, 코드 단계는 한 번만** 이라는 합리적 정책이 코드 한 줄에 표현되어 있습니다.

---

### 한 줄로 다시

> **계획 한 단계가 실패해도 → 자동 재시도 → 그래도 안 되면 다른 경로로 재계획 → 그래도 안 되면 그 액션 제외하고 다시 시도.** 사용자는 이 모든 게 일어난 줄도 모른 채 약간 늦은 보고서를 받게 됩니다.

---

## 6. 실증 — Brexit 보고서 한 번 돌려보기

이제까지 설명한 §2~§5 의 모든 주장을 **단 한 번의 실제 호출** 로 증명할 수 있습니다.
입력: **"브렉시트 이후에 영국에 경제상황을 정리해줘"** 한 문장.

---

### 6.1 일어난 일 — 이벤트 시퀀스 요약

```
event: started               ← "브렉시트 이후 영국 경제 상황" 접수
event: tool-invoked          ← tavily_search "Brexit impact on UK trade ... 2024 2025"
event: tool-returned         ← 1.79초 후 LSE PDF 등 결과
event: tool-invoked          ← tavily_search "UK non-tariff barriers post-Brexit"
event: tool-returned         ← 1.51초
event: tool-invoked          ← tavily_search "Brexit UK labor shortage 2024 2025"
event: tool-returned         ← 1.08초
                             ... (총 11회의 tavily_search) ...
event: tool-invoked          ← tavily_search "영국 재정 정책 경제성장률 전망 2026 브렉시트"
event: tool-returned         ← 1.22초
event: tool-invoked          ← current_date()
event: tool-returned         ← 1ms, "2026-05-06"
event: deep-research-completed ← 5개 섹션 + 19개 출처 + 결론 포함된 보고서
```

**숫자로 보는 한 번의 실행**:
- 검색 호출: **11회** (5개 섹션 × 1~3회)
- 평균 검색 응답 시간: **약 1.2초**
- `current_date()` 호출: **1회** (frameReport 단계에서)
- 최종 출처 수: **19개 (중복 제거)**
- 사용 언어: **영어 9회 + 한국어 2회** — LLM 이 주제별로 더 좋은 검색 결과를 얻을 언어를 스스로 선택

---

### 6.2 §1~§5 의 어느 부분이 증명되었나

| 4.md 섹션 | 이번 실행이 증명한 것 |
|-----------|---------------------|
| **§2 4단계 파이프라인** | plan → gather → frame → assemble 전부 한 번의 호출에 자연스럽게 일어남 |
| **§2 SSE 스트리밍** | `started` → 11×(`tool-invoked`/`tool-returned`) → `deep-research-completed` 가 실시간으로 흘러나옴 |
| **§2 "출처 있는 보고서"** | 본문 모든 주장에 `[https://...]` 인라인 인용 ([LSE], [ONS], [Reuters], [한국 KDI] 등 19개) |
| **§3.1 Domain Model 1급 시민** | 최종 응답이 평범한 JSON 객체 — `topic`, `executiveSummary`, `sections[]`, `sources[]`, `conclusion` 5개 필드 정확히 채워짐 |
| **§3.2 OODA 루프** | 검색 11번이 일제히 같은 패턴이 아니라, 섹션별로 *다른 쿼리·다른 횟수·다른 언어* — LLM 이 매번 직전 상황을 보고 다음 검색을 결정한 증거 |
| **§3 frameReport 의 시점 박기** | `current_date()` 가 정확히 한 번 호출되고, executive summary 에 **"2026-05-06 기준"** 이라는 문구가 박혀 들어감 |
| **§5 안전망** | 11회 검색 중 0회 실패 — 다행히 안전망이 발동할 일은 없었지만, 만약 503 이 났다면 자동 재시도가 들어갔을 자리 |

---

### 6.3 흥미로운 관찰

**(a) LLM 이 검색 언어를 직접 선택했다**
"환율" 섹션은 영어로 한 번 검색한 뒤 *"한국 자료가 더 풍부할 수 있겠다"* 판단해 한국어로 추가 검색. 결과적으로 KDI, a-ha 등 한국어 출처가 인용됨. 이건 코드에 시킨 게 아니라 **LLM 이 ResearchTools 의 도구 설명을 보고 스스로 결정** 한 행동.

**(b) 섹션마다 검색 횟수가 다르다**
- 무역 장벽: 2회
- 노동력: 2회
- 환율: 2회 (영어 1 + 한국어 1)
- FDI: 2회
- 재정 정책: 2회 (영어 1 + 한국어 1) + `current_date` 1회

프롬프트는 "1~2회" 라고만 시켰을 뿐, 정확한 횟수는 LLM 이 주제 복잡도에 따라 결정.

**(c) 한 번의 호출 = 약 25~30초**
검색 11회 합계 약 14초 + LLM 호출 5~6회 + 조립. 이 시간 동안 사용자가 빈 화면을 보는 게 아니라 **약 22개의 SSE 이벤트** 가 실시간으로 도착. §2 의 "스트리밍이 왜 필요한가" 가 그대로 증명됨.

---

### 6.4 최종 보고서 — 전체

---

> # 브렉시트 이후 영국 경제 상황 보고서
>
> **주제**: 브렉시트 이후에 영국에 경제상황을 정리해줘
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
> 브렉시트 이후 영국과 EU 간 상품 교역은 비관세 장벽(NTB)의 급증으로 인해 구조적 변화를 겪었습니다. 2021년 1월 무역 및 협력 협정(TCA) 발효 직후 영국 대 EU 상품 수출은 약 **40% 급감** 했으나, 이후 몇 달 만에 부분적으로 회복되었습니다. 그러나 장기적으로 2017년 대비 2024년까지 대 EU 수출은 **23% 감소** 한 반면, 수입은 5% 감소에 그쳐 무역 불균형이 심화되었습니다. 이는 영국 기업들이 EU 시장 진출을 포기하는 비용이 EU 기업들이 영국 시장을 포기하는 비용보다 훨씬 높기 때문입니다.
>
> 비관세 장벽의 증가는 장기적으로 영국의 기업 투자와 1인당 산출량을 각각 **2.5% 및 3% 낮추고**, 생산성을 1.2% 감소시키는 것으로 추정됩니다. 2024년 기준 EU는 여전히 영국 상품 수출의 48%, 수입의 54%를 차지하지만, 브렉시트 이전의 단일 시장 접근성에 비해 교역 비용이 크게 증가한 상태입니다.
>
> *출처*: [LSE 경제정책패널](https://economic-policy.org/76th-economic-policy-panel/brexit-trade/) · [Global Angle](https://global-angle.com/brexit-impact-on-exports-in-uk/) · [Productivity Institute (2025)](https://www.productivity.ac.uk/wp-content/uploads/2025/08/WP057-Brexit-and-Non-Tariff-Barriers-August-2025.pdf) · [영국 의회 도서관](https://commonslibrary.parliament.uk/research-briefings/cbp-7851/)
>
> ---
>
> ## 2. 노동력 부족과 인력 이동 제한의 경제적 파장
>
> 브렉시트 이후 영국은 자유 이동의 종료와 새로운 이민 체계 도입으로 인해 노동력 구조에 중대한 변화를 겪고 있으며, 이는 경제 전반에 파장을 미치고 있습니다. 2021년 도입된 새로운 이민 시스템은 **EU 출신 노동자의 유입을 급감** 시켰으며, 비EU 출신 노동자의 증가로 일부 상쇄되었으나 전체적으로 저숙련 직종에서의 인력 부족이 심화되었습니다.
>
> 특히 **건강 및 사회복지, 숙박 및 음식 서비스, 운송** 등 저숙련 노동이 집중된 산업에서 비영국 출신 근로자 수의 감소와 비자 발급 부족으로 인해 인력 공급이 수요를 따라가지 못하는 상황이 발생했습니다. 이러한 노동력 부족은 기업들의 인건비 상승을 유발하여 물가 상승 압력으로 이어졌으며, 이는 영국의 생산성 저하와 투자 감소로 연결되었습니다.
>
> 스탠포드 연구에 따르면 브렉시트로 인해 **2025년까지 고용이 3~4% 감소** 하고 생산성이 3~4% 하락할 것으로 추정되며, 이는 GDP 성장에 직접적인 타격을 입히고 있습니다. 결과적으로 인력 이동 제한은 영국의 노동 시장 유연성을 저해하고 경제 성장 잠재력을 제한하는 주요 요인으로 작용하고 있습니다.
>
> *출처*: [CER — 이민 영향 분석](https://www.cer.eu/insights/impact-brexit-immigration-uk) · [IZA Discussion Paper](https://docs.iza.org/dp15883.pdf) · [Premier Science](https://premierscience.com/pjec-24-477/) · [Stanford SIEPR](https://siepr.stanford.edu/publications/working-paper/economic-impact-brexit)
>
> ---
>
> ## 3. 영국 파운드 환율 변동성과 물가 상승 요인
>
> 브렉시트 이후 영국 파운드화는 극심한 환율 변동성을 겪었으며, 이는 물가 상승의 주요 동인이 되었습니다. 브렉시트 투표 직후인 2016년 5월부터 2017년 3월까지 파운드의 실질 유효 환율은 **11% 하락** 했습니다.
>
> 파운드화 가치의 급락은 수입 물가를 직접적으로 상승시켜 소비자 물가에 압력을 가했으며, 이는 **2022년 10월 소비자 물가 상승률이 11.1%** 에 달하는 등 고물가 현상을 초래했습니다.
>
> 이러한 환율 불확실성은 기업들의 경영에도 타격을 주었으며, 2025년 많은 영국 기업들이 파운드의 변동성으로 인해 손실을 보고 헤지 비율을 **2024년의 45%에서 53%로 상향** 하는 등 위험 관리에 더 많은 자원을 할당하고 있습니다. 브렉시트 이후의 불확실성 증가와 투자 감소는 파운드화 가치 하락을 지속시키는 구조적 요인으로 작용하여, 수입 의존도가 높은 영국 경제의 물가 안정을 어렵게 만들고 있습니다.
>
> *출처*: [NIESR — 무역과 환율](https://niesr.ac.uk/blog/uk-trade-and-exchange-rate) · [KDI 경제정보센터](https://eiec.kdi.re.kr/material/pageoneView.do?idx=1635) · [Reuters (2025-12-11)](https://www.reuters.com/world/uk/many-uk-firms-say-volatile-pound-triggered-losses-2025-need-hedge-grows-2025-12-11/) · [a-ha 질문답변](https://www.a-ha.io/questions/429d297308627ec6ba18fa13bbb95388)
>
> ---
>
> ## 4. 외국 직접 투자(FDI) 유입 감소와 산업 재편
>
> 브렉시트 이후 영국의 외국인 직접 투자(FDI)는 구조적인 감소세와 산업별 재편을 동시에 겪고 있습니다. 영국 국가통계청(ONS)에 따르면 2024년 영국 내 FDI 유입액은 전년 대비 **279억 파운드 감소** 하여 134억 파운드에 그쳤으며, 이는 브렉시트 이후 지속된 투자 위축의 연장선으로 해석됩니다. 연구에 따르면 브렉시트 투표 이후 영국의 FDI 유입은 약 **16~20% 감소** 한 것으로 추정되며, 이는 불확실성으로 인한 투자 심리 위축을 반영합니다.
>
> 산업별로는 전통적인 강세였던 금융 및 서비스업 중심에서 **기술 및 R&D 분야로의 재편이 가속화** 되고 있습니다. EY의 2024년 보고서에 따르면 영국은 유럽 내 FDI 프로젝트 수에서 2위를 유지했으나, 기술 분야 프로젝트 점유율은 2023년 27%에서 20%로 하락했습니다. 반면 본사 및 R&D 관련 프로젝트에서는 여전히 유럽 내 34%와 19%의 높은 점유율을 기록하며, 고부가가치 산업으로의 집중 추세가 뚜렷합니다.
>
> 이러한 변화는 브렉시트 이후 영국이 단일 시장 접근성 상실로 인한 대중적 투자 감소와 동시에, 특정 고기술 분야에서의 경쟁력 유지를 위한 산업 구조 조정을 단행하고 있음을 시사합니다.
>
> *출처*: [ONS — FDI 통계](https://www.ons.gov.uk/economy/nationalaccounts/balanceofpayments/bulletins/foreigndirectinvestmentinvolvingukcompanies/2024) · [UK Trade Policy Observatory](https://www.uktpo.org/briefing-papers/not-backing-britain-fdi-inflows-since-the-brexit-referendum/) · [EY — 유럽 FDI 보고서](https://www.ey.com/en_uk/newsroom/2025/05/uk-fdi-projects-second-in-europe)
>
> ---
>
> ## 5. 영국 정부의 재정 정책과 경제 성장률 전망
>
> 2026년 영국 정부는 공공재정 안정화와 경제 성장을 동시에 추구하는 재정 정책을 펼치고 있습니다. 영국 재무부의 2026년 봄 전망에 따르면, 정부의 경제 계획은 인플레이션 하락과 차입금 감소를 이끌었으며, **올해 차입금은 6년 만에 최저 수준** 으로 감소하여 G7 평균을 하회할 것으로 예상됩니다.
>
> 그러나 브렉시트의 구조적 영향은 여전히 경제 성장에 제약을 미치고 있습니다. 예산 책임청(OBR)은 브렉시트 이후의 무역 관계가 **장기적인 생산성을 EU 잔류 시보다 4% 낮추는 것** 으로 분석했습니다.
>
> 이에 따라 2026년 경제 성장률 전망은 기관마다 상이한데, NIESR는 **1.4% 성장** 을 예상하는 반면, OECD는 **0.7% 로 하향 조정** 했습니다. 브렉시트 10년이 지났음에도 1인당 GDP는 브렉시트가 없었을 경우보다 **6~8% 낮은 수준** 으로 추정되며, 신규 자유무역협정(FTA)의 이득은 EU 단일시장 이탈로 인한 손실을 상쇄하지 못하고 있습니다.
>
> *출처*: [HM Treasury — Spring Forecast 2026](https://www.gov.uk/government/news/spring-forecast-2026-the-right-economic-plan-for-britain) · [OBR — 브렉시트 분석](https://obr.uk/forecasts-in-depth/the-economy-forecast/brexit-analysis/) · [NIESR Economic Outlook](https://niesr.ac.uk/reports/economic-outlook-winter-2026) · [영국 의회 도서관](https://commonslibrary.parliament.uk/research-briefings/sn02784/) · [Brunch — 브렉시트 10년 회고](https://brunch.co.kr/@08b0349ec2fc42a/52)
>
> ---
>
> ## 결론
>
> 브렉시트 이후 영국 경제는 **무역 장벽, 노동력 부족, 환율 변동성, FDI 감소** 등 구조적 도전에 직면하여 성장 잠재력이 제한되었습니다. 이러한 요인들은 상호 연계되어 물가 상승과 산업 재편을 가속화했으며, 정부의 재정 정책이 이를 완화하기 위해 노력하고 있습니다. 그러나 근본적인 경제 구조의 변화는 단기적으로 해결하기 어려운 과제로 남아 있으며, **장기적인 회복을 위해서는 포괄적인 개혁이 필요** 합니다.
>
> ---
>
> ### 핵심 수치 요약
>
> | 지표 | 변화 |
> |------|------|
> | 대 EU 수출 (2017→2024) | **▼ 23%** |
> | 1인당 GDP (vs EU 잔류 가정) | **▼ 6~8%** |
> | 비관세 장벽으로 인한 생산성 | **▼ 1.2%** |
> | FDI 유입 (2024) | **▼ 279억 파운드** |
> | 파운드 실질 유효 환율 (2016~17) | **▼ 11%** |
> | 2026 성장률 전망 (NIESR / OECD) | **+1.4% / +0.7%** |
>
> ---
>
> *전체 출처 19건 · 한국어·영어 자료 혼용 · LLM 자체 출처 검증 완료*

---

### 한 줄로 다시

> **한 문장의 입력 → 약 25초 후 5섹션·19출처짜리 보고서**. 그 사이 사용자는 22개의 진행 이벤트를 실시간으로 보았고, 코드는 어떤 순서·어떤 언어·어떤 검색을 할지 한 줄도 지시하지 않았습니다. **§1~§5 가 설명한 모든 메커니즘이 한 번의 호출에서 동시에 작동한 결과** 입니다.

---

## 7. 다른 도구와 비교 — 왜 굳이 Embabel 인가

### 한 줄 요약

> **AI 에이전트 프레임워크는 여러 개가 있고, 각자 잘하는 영역이 다릅니다.** Embabel 은 **"엔터프라이즈 서버 안에서 안정적으로 도는 백엔드 에이전트"** 자리에 강합니다. 다만 정직하게 못하는 것도 있습니다 (§7.4).

---

### 비유 — "공장 조립 라인 vs 공방 작업장"

도구들의 사고방식 차이를 한 비유로 잡으면:

| 도구 | 비유 | 의미 |
|------|------|------|
| **LangChain (Python)** | **공방 작업장** | 자유롭게 도구를 들고 시도, 빠른 시제품. 운영보다 *실험* 에 강함 |
| **LangGraph (Python)** | **회로도 그리기** | 노드와 화살표를 사람이 직접 그림. 명시적이지만 그래프가 커지면 유지보수 ↑ |
| **CrewAI (Python)** | **역할극 팀** | "기획자", "리서처" 같은 역할을 부여하고 협업시킴. 멀티에이전트 협상에 강함 |
| **Claude API / OpenAI 직접** | **단일 작업자** | 한 번에 한 명의 LLM 과 자유 대화. 가장 단순 |
| **Embabel (JVM)** | **공장 조립 라인** | 단계가 정해져 있고 *타입* 으로 검증되며 매번 같은 품질. 운영용 |

**핵심 — Embabel 은 "실험" 보다 "운영" 쪽** 입니다. Brexit 보고서를 한 번 만드는 것보다, **하루에 1만 건의 보고서를 안정적으로 생성** 하는 시나리오에서 진가가 나옵니다.

---

### 한눈에 비교 — 6가지 축

| 축 | LangChain | LangGraph | CrewAI | OpenAI Assistants | **Embabel** |
|----|-----------|-----------|--------|-------------------|-------------|
| **언어** | Python | Python | Python | API only | Kotlin/Java |
| **계획 짜기** | LLM 즉흥 | 사람이 그래프 작성 | 역할/태스크 선언 | LLM 자체 | **GOAP 자동** |
| **타입 안전성** | ❌ 런타임 오류 | ⚠️ 부분적 | ⚠️ 부분적 | ❌ JSON | ✅ **컴파일 타임** |
| **재계획** | 직접 코드 | 그래프 사이클 수동 | 제한적 | 자동 (블랙박스) | **자동 (OODA)** |
| **Spring/엔터프라이즈** | ⚠️ FastAPI 등 직접 | ⚠️ | ⚠️ | — | ✅ **일급** |
| **토큰 단위 스트리밍** | ✅ | ✅ | 부분적 | ✅ | ❌ **객체 단위 (§7.4)** |

---

### 그래서 어떨 때 무엇을 골라야 하나 — 의사결정 가이드

| 시나리오 | 추천 도구 | 이유 |
|---------|----------|------|
| Jupyter 에서 빠르게 시제품 | **LangChain** | Python ML 생태계, 노트북 친화 |
| 그래프가 복잡하고 사이클 많음 | **LangGraph** | 그래프를 명시적으로 설계 |
| 에이전트끼리 대화·협상 시키기 | **CrewAI / AutoGen** | 멀티에이전트 협업이 본업 |
| 챗봇·코드 자동완성처럼 *한 글자씩 타이핑* | **Claude API / OpenAI 직접** | 토큰 스트리밍 최적 |
| **JVM/Spring 기반 백엔드에 에이전트 박기** | **Embabel** ⭐ | 본 프로젝트 — Spring Security, @Transactional, Resilience4j 와 자연 결합 |
| 데이터 파이프라인에 LLM 노드 끼워넣기 | Airflow/Prefect + LLM | 스케줄러가 본업 |

> **본 프로젝트의 자리** — 사내망 LLM 게이트웨이 + 기존 Spring 인프라 + Tavily 검색이라는 조건에서, **Embabel + Spring AI** 가 현실적으로 가장 성숙한 옵션입니다.

---

### Python (LangChain, LangGraph), JVM (Embabel) 비교 — 4가지 핵심 차이점

#### (a) 타입 안전성 — "런타임 폭발 vs 컴파일러가 잡아줌"

**LangChain (Python, dict 기반)**
```python
state = {"plan": research_plan, "drafts": []}
# ... 어딘가에서
state["draft"].append(section)            # 's' 빠진 키 오타 → 런타임 KeyError
section.sourcs                             # 'e' 빠진 속성 → AttributeError
```

**Embabel (Kotlin)**
```kotlin
val drafts = AgentSectionDrafts(sections = listOf(...))
section.sourcs                             // ← 컴파일 에러 즉시
```

도메인 모델의 `init { require(...) }` 까지 결합하면 LLM 의 잘못된 출력이 **객체 생성 시점에 거부** 됩니다 (§7 (d)). LangChain 에서는 같은 검증을 직접 작성하고, 실패 시 retry 로직도 직접 구현해야 합니다.

#### (b) 결정론적 플래닝 — "그래프를 그리는가, 시스템이 합성하는가"

**LangGraph** — 사람이 그래프를 직접 그립니다:
```python
graph = StateGraph(State)
graph.add_node("plan", plan_subtopics)
graph.add_node("gather", gather_sections)
graph.add_node("frame", frame_report)
graph.add_node("assemble", assemble_report)
graph.add_edge("plan", "gather")
graph.add_edge("gather", "frame")
graph.add_edge("frame", "assemble")
graph.add_conditional_edges("gather", lambda s: "frame" if s["enough"] else "gather")
# 새 노드 추가하면 모든 edge 재작성
```

**Embabel** — 시그니처만 적으면 plan 합성:
```kotlin
@Action fun planSubtopics(input: UserInput): AgentResearchPlan = ...
@Action fun gatherSections(plan: AgentResearchPlan): AgentSectionDrafts = ...
@Action fun frameReport(plan: AgentResearchPlan, drafts: AgentSectionDrafts): AgentReportFraming = ...
@AchievesGoal @Action fun assembleReport(...): AgentDeepResearchReport = ...
// 그래프 코드 0줄. A*가 타입 그래프에서 plan 합성 (§4).
```

**같은 입력 → 같은 plan** 이 보장되므로 디버깅이 쉽고, 새 액션 추가가 그래프 재작성을 요구하지 않습니다.

> **CrewAI** 와의 비교: CrewAI 는 *agent 들이 자연어로 협상* 하는 모델이라 더 비결정적입니다. 같은 task 라도 매번 다른 경로를 타고, 토큰 비용이 예측 불가능. Embabel 은 정반대 — 협상 대신 **타입 매칭** 으로 합의합니다.

#### (c) Spring 통합 — JVM 엔터프라이즈와의 자연스러운 결합

**다른 프레임워크에서 직접 해야 하는 일들** 이 Embabel 에서는 거의 무료:

| 필요한 것 | LangChain/LangGraph | Embabel |
|-----------|----------------------|---------|
| 사용자 인증 | FastAPI dep 직접 | `@PreAuthorize` |
| Tracing/Metrics | OpenTelemetry 수동 wiring | Spring Boot Actuator + Micrometer 자동 |
| DB 트랜잭션 | SQLAlchemy 직접 | `@Transactional` |
| 툴 의존성 주입 | 글로벌 변수/factory | `@Component` + 생성자 주입 |
| Rate limiter / Circuit breaker | 직접 | Resilience4j 어노테이션 |
| Config externalization | env / pydantic-settings | `application.yml` + `@ConfigurationProperties` |

본 프로젝트의 `ResearchTools` 가 좋은 예시:

```kotlin
@Component
class ResearchTools(
    @param:Value("\${tavily.api-key:}") private val tavilyApiKey: String,
    private val webClient: WebClient = defaultWebClient(),
) {
    @LlmTool(name = "tavily_search", ...)
    fun tavilySearch(query: String, maxResults: Int = 5): List<TavilyResult> { ... }
}
```

- API 키는 Spring `@Value` 로 외부 설정에서 주입
- WebClient (Reactor 기반) 가 connection pool / timeout / TLS 를 관리
- 만약 캐시 추가하고 싶다면? `@Cacheable("tavily")` 한 줄

#### (d) 확장성 — "새 기능 추가 = `@Action` 하나 추가"

본 프로젝트에 *PDF 인용 추출* 기능을 더한다고 해봅시다.

**LangGraph** 의 경우:
1. `extract_pdf_citations` 노드 추가
2. `gather_sections` 와 `frame_report` 사이에 새 edge 그리기
3. State 의 `TypedDict` 에 `citations` 필드 추가
4. 모든 conditional edge 재검토

**Embabel**:
```kotlin
@Action
fun extractPdfCitations(drafts: AgentSectionDrafts): AgentCitationIndex {
    // sources 의 PDF URL 들에서 본문 인용 추출
}

// frameReport 시그니처에 AgentCitationIndex 추가만 하면
@Action
fun frameReport(
    plan: AgentResearchPlan,
    drafts: AgentSectionDrafts,
    citations: AgentCitationIndex,        // ← 추가
    context: OperationContext,
): AgentReportFraming = ...
```

→ 다음 빌드에 자동으로 plan 이 `... → gatherSections → extractPdfCitations → frameReport → ...` 로 재합성됩니다. **edge 재작성 X, conditional 재검토 X**. (§4.7 의 확장 시나리오와 동일한 메커니즘)

#### (e) 도메인 객체 = 1급 시민

LangChain 의 메시지/체인 모델은 본질적으로 **문자열 + 체인** 입니다. 도메인 모델은 LLM과 *직교적* 으로 존재합니다.

Embabel 은 반대로 **도메인 모델이 LLM 흐름의 노드 그 자체** 입니다:

```
       사용자 도메인           ↔             LLM 흐름
   AgentResearchPlan          ←  =  →    "plan-formulated" 이벤트
   AgentSection               ←  =  →    "section-drafted" 이벤트
   AgentDeepResearchReport    ←  =  →    Goal
```

- IDE 의 *Find Usages* 가 *"이 데이터 클래스를 만드는 LLM 액션"* 과 *"소비하는 액션"* 을 모두 찾아줍니다.
- 도메인 모델 리팩토링이 곧 LLM 흐름 리팩토링.

---


### 정직한 한계 — 토큰 단위 스트리밍이 안 된다

§6 의 Brexit 사례에서 사용자가 본 진행 이벤트는 다음과 같았습니다.

```
event: started
event: tool-invoked / tool-returned  × 11번
event: deep-research-completed       ← 여기서 한꺼번에 5섹션 도착
```

**중요한 사실** — 보고서 본문은 **약 25초간 단 한 글자도 화면에 안 나오다가**, 마지막에 5섹션이 한꺼번에 도착했습니다. ChatGPT/Claude 처럼 *글자가 한 자씩 타이핑되는 효과* 는 자연스럽게 나오지 않습니다.

#### 왜 안 되는가 — 비유로

Embabel 은 LLM 에게 "JSON 형식의 객체로 응답하라" 고 요구합니다 (§3.1 Domain Model). JSON 은 **닫는 괄호 `}` 가 와야 비로소 유효한 형식** 이므로, 중간에 한 글자씩 흘려보내면 파싱·검증·다음 단계 트리거가 모두 깨집니다.

비유하자면:
- **토큰 스트리밍** = 편지를 쓰는 즉시 한 글자씩 보냄. 빠르지만 끝까지 안 받으면 의미 불완전.
- **Embabel 방식** = 편지를 다 쓰고 봉투에 봉인한 뒤 통째로 보냄. 늦지만 *완성품* 이 도착함.

#### 트레이드오프 — 무엇을 얻고 무엇을 잃나

| 항목 | Embabel (객체 단위) | 토큰 스트리밍 |
|------|-------------------|--------------|
| **타입 안전성** | ★★★ — 깨진 응답은 객체 생성 자체가 거부됨 | ★ — 런타임에 발견 |
| **출처 검증** | ★★★ — 인용이 *완성된 후* 도착 | ★ — 본문 먼저 도착, 출처는 늦거나 없음 |
| **재시도/재계획** | ★★★ — 깨지면 자동 복구 | ★ — 직접 처리 필요 |
| **첫 글자까지 시간** | ★ — 25초 후 | ★★★ — 1초 이내 |
| **"AI가 일하고 있다" 느낌** | ★★ — 진행 이벤트로 보완 | ★★★ — 본문이 직접 흘러나옴 |

#### Brexit 사례로 다시 생각해 보면

연구 보고서 도메인에서는 **출처 없는 한 문장이 먼저 도착하는 게 오히려 위험** 합니다.
> "브렉시트로 인해 GDP 가 6~8% 감소했습니다" *(출처: ?)*

위처럼 출처가 늦게 도착하면 사용자가 *검증 안 된 문장* 을 먼저 읽고 신뢰해 버릴 위험이 있습니다. **"섹션 통째로 + 출처 인라인 + 한꺼번에"** 방식이 실제로는 더 안전한 UX 일 수 있다는 뜻.

#### 그래서 실용적인 결론

| 도메인 | 토큰 스트리밍이 필수? | 추천 |
|--------|--------------------|------|
| 챗봇 / 대화형 / 코드 자동완성 | ✅ 필수 | Claude API, OpenAI 직접 |
| 연구 보고서 / 분석 / 문서 생성 | ❌ 큰 영향 없음 | **Embabel** ⭐ + 마일스톤 이벤트로 UX 보강 |
| 실시간 의사결정 도우미 | ⚠️ 도움 됨 | 하이브리드 |

본 프로젝트는 *연구 보고서* 도메인이므로, 토큰 스트리밍의 부재는 **결정적 약점이 아닌 합리적 트레이드오프** 입니다.

---

### 한 줄로 다시

> **Embabel = "실험용 도구가 아니라 운영용 도구"**. 빠른 시제품엔 LangChain 이 낫고, 한 글자씩 타이핑되는 챗봇엔 Claude API 가 낫습니다. 하지만 **Spring 기반 백엔드 안에서 안정적으로 도는 구조화된 보고서 생성** 이 목적이라면, *타입 안전성 + 자동 재계획 + 5겹 안전망* 의 조합이 다른 어떤 도구보다 강합니다.

---

## 8. 개선 방향 — 본 프로젝트를 더 좋게 만드는 길

### 한 줄 요약

> **현재도 잘 돌아갑니다.** Brexit 보고서가 25초 만에 출처 19개와 함께 나왔으니까요(§6).
> 다만 *"더 빠르게 / 더 안 죽게 / 더 잘 보이게"* 만들 여지가 있고, 다행히 Embabel 라이브러리 안에 그 도구들이 이미 다 있습니다.

---

### 비유 — "MVP 차량 → 양산형 차량"

지금 상태는 *"바퀴 4개가 잘 굴러가는 시제품 차량"* 입니다.
양산형 차량으로 가려면 같은 골격에 **에어백, 계기판, 정비 알림** 같은 걸 추가해야 합니다.

| 단계 | 본 프로젝트의 위치 | 다음에 추가할 것 |
|------|-----------------|---------------|
| 굴러는 가는가 | ✅ Brexit 보고서 생성 성공 | — |
| 더 빨리 가는가 | ⚠️ 5섹션을 한 줄로 처리 (직렬) | **병렬화** |
| 한 부품이 망가져도 가는가 | ⚠️ 부분 안전망만 적용 | **Tavily fallback, disconnect 처리** |
| 어디가 고장났는지 보이는가 | ⚠️ 진행 이벤트는 있으나 메트릭 없음 | **OpenTelemetry, Micrometer** |
| 새 기능 끼우기 쉬운가 | ✅ 액션 추가만으로 확장 가능 | **Subagent 분리, 추가 Goal** |

---

### 4가지 개선 카테고리

#### (a) 속도 — "5섹션을 동시에 작성"

**현재** — `gatherSections` 안에서 5개 섹션을 **하나씩 순차** 로 작성합니다 (Brexit 케이스에서 약 15~18초 소요).
**개선** — 각 섹션을 **별도 Subagent** 로 분리해 *최대 3개 동시 실행* 하면 같은 작업을 약 6~8초에 끝낼 수 있습니다 (전체 25초 → 약 13초).

| 측면 | 현재 (직렬) | 개선 (병렬) |
|------|-----------|-----------|
| 5섹션 작성 시간 | 약 15~18초 | 약 6~8초 |
| 섹션 1개 실패 시 | 다른 섹션도 영향 가능 | 격리됨 (다른 섹션 무관) |
| 코드 변경 분량 | — | `SectionResearchAgent` 클래스 1개 + `gatherSections` 5줄 교체 |
| 추가 비용 | — | 자식 프로세스 오버헤드 수십 ms |

> **언제 안 해도 되나** — 응답 시간이 30초 안에 들어오면 사용자 체감 차이가 크지 않습니다. 트래픽이 낮은 사내 도구라면 우선순위 ↓.

---

#### (b) 신뢰성 — "한 부품이 망가져도 멈추지 않게"

**현재의 빈 자리 2개**:

1. **Tavily 검색이 죽으면 어떻게 되나?**
   현재는 그 섹션의 LLM 호출이 실패 → 액션 재시도 → 그래도 안 되면 전체 보고서 실패.
   → **개선** — "Tavily 가 비어있으면 *오프라인 모드* 액션으로 우회" 하는 fallback 액션을 하나 더 등록. GOAP 플래너가 자동으로 대안 경로를 찾아냅니다 (§3.2 OODA 가 있어서 가능). 코드 변경은 액션 메서드 1~2개 추가.

2. **사용자가 도중에 브라우저를 닫으면?**
   현재는 백엔드가 25초 동안 계속 일을 끝까지 합니다 → **LLM 토큰 낭비**.
   → **개선** — `ProcessControl` 을 써서 클라이언트 disconnect 감지 시 진행 중인 작업을 중단. 비용 절감.

| 개선 | 노력 | 효과 |
|------|------|------|
| Tavily fallback 액션 | ★★ | 외부 의존성 장애 시 부분이라도 응답 |
| Disconnect 처리 | ★ | 트래픽 늘어날수록 비용 절감 효과 ↑ |

---

#### (c) 관측성 — "어디가 느린지 보이게"

**현재** — `started`, `tool-invoked`, `tool-returned`, `deep-research-completed` 정도만 사용. 미사용 이벤트가 많습니다.

| 미사용 이벤트 | 활용 시 얻는 것 |
|------------|---------------|
| `LlmRequestEvent` / `LlmResponseEvent` | LLM 토큰 사용량·지연 시간 추적 → **비용 모니터링** |
| `ProgressUpdateEvent` | "5섹션 중 3개 완성" 같은 명시적 진행률 → **UX 개선** |
| `ProcessKilledEvent` | 클라이언트 종료 후 확실한 정리 |
| `ToolLoopStart/Completed` | tool 호출 *그룹* 단위 추적 → **검색 패턴 분석** |

**관측 인프라 추가** — Spring 기반이라 거의 자동입니다.
- **Micrometer Timer** — 각 액션·툴 호출의 지연 시간을 자동 수집 → Grafana 등으로 시각화
- **OpenTelemetry span tree** — 한 번의 deep-research 호출을 트리 구조로 분해 (planSubtopics → 5×gatherSections → frameReport → assembleReport)

| 개선 | 노력 | 효과 |
|------|------|------|
| Micrometer Timer 사이드카 | ★ | 어느 단계가 느린지 즉시 보임 |
| OpenTelemetry 통합 | ★★ | 분산 추적 + 호출 트리 시각화 |
| `LlmRequestEvent` 토큰 카운팅 | ★ | LLM 비용 일별/월별 집계 |

---

#### (d) 기능 확장 — "보고서 종류 늘리기"

**현재** — 골(Goal)이 *"긴 보고서"* 하나뿐.
**개선** — 같은 `DeepResearchAgent` 에 *"짧은 요약본"* 같은 추가 Goal 을 붙이면, 사용자 요청에 따라 GOAP 플래너가 더 짧은 경로를 자동 선택.

```kotlin
// 의사 코드 — 추가 한 줄로 새 골 등록
@AchievesGoal(description = "A short executive briefing has been produced")
@Action
fun assembleBriefing(plan: AgentResearchPlan, framing: AgentReportFraming): ShortBriefing = ...
```

→ 이 한 액션이 등록되는 순간, 플래너는 사용자 입력에 따라 *"긴 보고서"* 와 *"짧은 브리핑"* 중 더 적합한 경로를 골라줍니다. **개발자가 if/else 분기를 한 줄도 안 쓰는 게 핵심**.

---

### 우선순위 추천 — 노력 vs 효과

| 우선순위 | 개선 | 노력 | 효과 | 권장 시점 |
|---------|------|------|------|---------|
| **1** | Micrometer + 토큰 카운팅 | ★ | ★★★ | **지금 바로** — 운영 시 어차피 필요 |
| **2** | Tavily fallback | ★★ | ★★★ | 외부 노출 전에 |
| **3** | 5섹션 병렬화 | ★★★ | ★★ | 응답 시간 SLA 가 생기면 |
| **4** | Disconnect 처리 | ★ | ★★ | 트래픽이 늘면 |
| **5** | 추가 Goal (짧은 브리핑) | ★★ | ★★ | 새 사용자 요구가 나오면 |
| **6** | OpenTelemetry 풀 통합 | ★★★ | ★★ | 마이크로서비스 환경이면 |

---

### 한 줄로 다시

> **현재 코드는 Brexit 보고서를 잘 만들어 냅니다. 다음 단계는 "잘 만들어내는 것"에 더해 "느린 곳을 보이게, 외부 장애에 안 죽게, 새 기능을 쉽게 추가하게" 만드는 것** — 이미 라이브러리 안에 도구가 다 있어서, 코드 한두 클래스 추가 수준의 작업이 대부분입니다.

--- 

