# Embabel (엠베이블)

> Spring Framework 창시자 **Rod Johnson** 이 만든 **JVM 기반 AI 에이전트 프레임워크**.
> Kotlin 으로 작성, Java 에서도 그대로 사용 가능, Spring Boot 위에서 동작.
>
> 본 문서는 **JVM 백엔드 개발자** 를 위한 심층 기술 가이드입니다. 프로젝트의 전반 아키텍처(헥사고날, 디렉터리, 실행 등)는 [README.md](./README.md) 를 먼저 참고하세요.

---

## 목차

1. [들어가기 — 무엇이고, 왜 JVM 인가](#1-들어가기--무엇이고-왜-jvm-인가)
2. [아키텍처 개요 — GOAP 분리·빌딩블록·OODA](#2-아키텍처-개요--goap-분리빌딩블록ooda)
3. [런타임 추상화 — Blackboard / OperationContext / AgentProcess / ProcessOptions](#3-런타임-추상화--blackboard--operationcontext--agentprocess--processoptions)
4. [어노테이션 동작 모델 — `@Agent` / `@Action` / `@LlmTool`](#4-어노테이션-동작-모델--agent--action--llmtool)
5. [`POST /api/deep-research/stream` 코드 워크스루](#5-post-apideep-researchstream-코드-워크스루)
6. [이벤트 카탈로그 — `AgenticEventListener` 이벤트](#6-이벤트-카탈로그--agenticeventlistener-이벤트)
7. [오류 처리 / 재계획 / Fallback](#7-오류-처리--재계획--fallback)
8. [서브에이전트와 합성 — 개념 + 향후 확장 가이드](#8-서브에이전트와-합성--개념--향후-확장-가이드)
9. [테스트와 관측성](#9-테스트와-관측성)
10. [다른 프레임워크와의 차별점](#10-다른-프레임워크와의-차별점)
11. [한계 — 토큰 단위 스트리밍 (트레이드오프)](#11-한계--토큰-단위-스트리밍-트레이드오프)
12. [TL;DR](#12-tldr)
- [부록 A — GOAP A\* 플래너 라이브러리 내부](#부록-a--goap-a-플래너-라이브러리-내부)
- [부록 B — 향후 확장 패턴 (Tavily fallback, Subagent 분리)](#부록-b--향후-확장-패턴-tavily-fallback-subagent-분리)

> 🔮 **표시 약속** — 본문에 `🔮 개념 설명 / 향후 확장` 마커가 달린 영역은 **본 프로젝트에 구현되지 않은** 라이브러리 사용법을 *참고용* 으로 보여줍니다. 의사 코드이며 즉시 컴파일·실행되지 않을 수 있습니다.

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
   클라이언트 / CLI / IDE          서버 / 백엔드 / 백오피스             데이터 사이언스 / 노트북
   ───────────────────────         ────────────────────────             ─────────────────────
   Claude Code, OpenCode,          Spring Boot, Node, Rails              Jupyter, Colab,
   Cursor, Continue.dev            엔터프라이즈 시스템                    ML 파이프라인
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

전체 비교 매트릭스는 §10 에서.

### 이 문서의 사용법

- §2–§4 — 정신모델·런타임 어휘·어노테이션. **순서대로** 읽어야 §5 가 빠르게 들어옵니다.
- §5 — 본 프로젝트 코드 워크스루. 여기까지가 *"쓰기 시작"* 단계.
- §6–§9 — 이벤트, 오류 처리, 합성, 운영. *"안정 배포"* 단계.
- §10–§11 — 의사결정 보강·정직한 한계.
- 부록 A 는 *Embabel 라이브러리 자체* 가 궁금할 때만.

---

## 2. 아키텍처 개요 — GOAP 분리·빌딩블록·OODA

### 2.1 4가지 빌딩 블록

| 요소 | 의미 | 코드에서의 모습 |
|------|------|------------------|
| **Actions** | 에이전트가 수행하는 단계 | `@Action fun planSubtopics(...)` |
| **Goals** | 달성하려는 종착 상태 | `@AchievesGoal` 가 붙은 `@Action` |
| **Conditions** | 액션 실행 전/목표 달성 판단 조건 | 입력 타입 매칭 (e.g. `AgentResearchPlan` 가 월드에 있어야 `gatherSections` 가능) |
| **Domain Model** | Kotlin `data class` / Java `record` | `AgentResearchPlan`, `AgentSection`, ... |

**Domain Model 이 1급 시민** — Embabel 의 가장 차별적인 선택은 **함수 시그니처의 도메인 타입이 곧 plan 의 노드** 라는 점입니다.

```
       사용자 도메인           ↔             LLM 흐름
   AgentResearchPlan          ←  =  →    "plan-formulated" 이벤트
   AgentSection               ←  =  →    "section-drafted" 이벤트
   AgentDeepResearchReport    ←  =  →    Goal
```

따라서:
- IDE *Find Usages* 가 *"이 데이터 클래스를 만드는 LLM 액션"* 과 *"소비하는 액션"* 을 모두 찾아줍니다.
- 도메인 모델 리팩토링이 곧 LLM 흐름 리팩토링.
- **타입만 맞추면 plan 에 자동으로 끼어듭니다** = "확장성"의 정체.

### 2.2 OODA 실행 모델

각 액션이 끝날 때마다 **재계획(replan)** 하면서 새로운 정보에 적응합니다.

```
 ┌─────────► Observe ──► Orient ──► Decide ──► Act ──┐
 │     이전 액션 결과    도메인 모델     다음 액션      액션 실행 →
 │     도구 호출 응답    & 컨디션에      재선택        새 결과
 │     외부 상태 변화    비추어 해석     (replan)      (다시 Observe)
 └────────────────────────────────────────────────────┘
```

- **Observe** — 이전 액션 결과, 툴 호출 응답, 외부 상태 변화를 수집.
- **Orient** — 도메인 모델·현재 컨디션에 비추어 해석. GOAP 플래너가 새 사실을 월드 상태에 반영.
- **Decide** — 다음 액션 재선택. (코드: `Planner.bestValuePlanToAnyGoal(system)`)
- **Act** — 액션 실행 → 결과가 다시 Observe 로.

### 2.3 본 프로젝트 plan 발견 방식 (직관)

`DeepResearchAgent` 는 4개 `@Action` 만 선언합니다. Embabel 은 자동으로 다음 GOAP 표현을 생성:

```
planSubtopics:    pre={UserInput}                                      post={AgentResearchPlan}
gatherSections:   pre={AgentResearchPlan}                              post={AgentSectionDrafts}
frameReport:      pre={AgentResearchPlan, AgentSectionDrafts}          post={AgentReportFraming}
assembleReport:   pre={AgentResearchPlan, AgentSectionDrafts,          post={AgentDeepResearchReport}  ← @AchievesGoal
                       AgentReportFraming}

Goal: produceDeepResearchReport      preconditions={AgentDeepResearchReport}
```

플래너는 **목표 타입에서 거꾸로 따라 올라가** 의존 액션을 자동 정렬:

```
[Goal]  AgentDeepResearchReport
            ▲ assembleReport(plan, drafts, framing)
   ┌────────┼────────┐
AgentResearchPlan  AgentSectionDrafts  AgentReportFraming
   ▲ planSubtopics    ▲ gatherSections(plan)    ▲ frameReport(plan, drafts)
```

**상태 머신·그래프 코드 0줄.** 새 액션을 추가해도 if-else 를 고칠 필요가 없습니다. 알고리즘 자체는 §4 ↗ 부록 A.

---

## 3. 런타임 추상화 — Blackboard / OperationContext / AgentProcess / ProcessOptions

> 이 섹션은 **사용자가 `@Action` 안에서 실제로 만지는 객체** 를 정리합니다. §5 코드 워크스루에서 `context.ai().create<T>(...)` 가 *마법처럼* 보이지 않게 하기 위한 어휘 정렬입니다.

### 3.1 Blackboard — 공유 상태 컨테이너

`Blackboard` 는 **AgentProcess 가 컨텍스트를 유지하는 방법** 입니다. 모든 `@Action` 의 입력·출력이 여기에 쌓입니다.

```kotlin
// com/embabel/agent/core/Blackboard.kt — 라이브러리 인용
interface Blackboard : Bindable, MayHaveLastResult, HasInfoString {
    val blackboardId: String
    val objects: List<Any>                                          // 추가된 순서대로 보존

    operator fun get(name: String): Any?                            // 키로 조회
    fun <T> last(clazz: Class<T>): T?                               // 타입의 마지막 인스턴스
    fun <T> objectsOfType(clazz: Class<T>): List<T>                 // 타입의 모든 인스턴스
    fun <V : Any> getOrPut(name: String, creator: () -> V): V

    fun spawn(): Blackboard                                          // 자식 블랙보드 (서브에이전트용)
    fun setCondition(key: String, value: Boolean): Blackboard       // GOAP 조건 강제 설정
    fun getCondition(key: String): Boolean?
    fun hide(what: Any)                                              // 검색에서만 숨김 (제거 X)
}

interface Bindable {
    fun bind(key: String, value: Any): Bindable                     // 키-값 바인딩
    fun bindProtected(key: String, value: Any): Bindable            // 상태 전이 시에도 살아남음
    fun addObject(value: Any): Bindable                              // 키 없이 추가 (마지막 = "it")
}
```

**핵심 디테일**

- **불변 (append-only)** — 객체는 *추가만* 가능. 제거는 불가, 대신 `hide()` 로 검색에서만 숨김.
- **타입 기반 조회** — `objectsOfType<AgentSection>()` 로 *지금까지 만들어진 모든 섹션* 을 가져올 수 있음.
- **`bind` vs `addObject`** — 키 명시 vs 익명. 후자는 `"it"` 라는 기본 키로 마지막 항목.
- **`bindProtected`** — 상태 전이(state transition) 때 `clear` 가 일어나도 살아남는 바인딩. 대화 히스토리·사용자 ID 같이 *대화 내내 유지* 해야 하는 데이터에 사용.
- **`spawn()`** — 부모 컨텍스트를 *복사한 자식 블랙보드*. 서브에이전트가 부모를 오염시키지 않으면서 독립 작업할 때 (§8).
- **`setCondition`** — *직접* 조건을 TRUE/FALSE 로 강제. `ReplanRequestedException` 의 `BlackboardUpdater` 가 이 API 를 활용 (§7).

> 본 프로젝트는 직접 Blackboard API 를 호출하지 않습니다. `@Action` 의 입력 매개변수와 반환 값이 자동으로 Blackboard 에 매핑됩니다. **명시적 조작이 필요한 시점** = 같은 타입의 객체가 여러 개일 때, 또는 서브에이전트 결과를 부모로 끌어올릴 때.

### 3.2 OperationContext — `@Action` 에 주입되는 객체

```kotlin
// com/embabel/agent/api/common/OperationContext.kt — 라이브러리 인용
interface OperationContext : Blackboard, ToolGroupConsumer {
    val processContext: ProcessContext
    val agentProcess: AgentProcess
    val operation: Operation                                         // 실행 중인 액션 자신

    fun user(): User?                                                // ProcessOptions.identities.forUser
    fun ai(): Ai = OperationContextAi(this)                          // LLM 진입점
    fun promptRunner(llm: LlmOptions = LlmOptions(), ...): PromptRunner

    fun <T : Any> fireAgent(obj: Any, resultType: Class<T>): CompletableFuture<T>?
    //   ^ 다른 등록된 agent 를 비동기 호출

    fun <T, R> parallelMap(
        items: Collection<T>,
        maxConcurrency: Int,
        transform: (t: T) -> R,
    ): List<R>
}
```

**`OperationContext` 는 Blackboard 를 상속** — 즉 액션 안에서 `context.objectsOfType<AgentSection>()` 같이 직접 블랙보드 조회 가능.

**노출되는 능력**

| 메서드 | 의미 | 본 프로젝트 사용 |
|--------|------|------------------|
| `ai()` / `promptRunner()` | LLM 호출 (구조화 출력 + 툴 루프) | ✅ 모든 `@Action` |
| `parallelMap(items, maxConcurrency, transform)` | 컬렉션 병렬 처리 | ❌ — `gatherSections` 는 직렬 `map` 사용. 병렬화 가능 (3.4 참고) |
| `fireAgent(obj, resultType)` | 같은 platform 의 다른 `@Agent` 호출 | ❌ — 서브에이전트 미사용 |
| `user()` | 현재 사용자 (Identities) | ❌ |
| `agentProcess` / `processContext` | 진행 중 프로세스 핸들 | ❌ (이벤트 리스너만 사용) |

### 3.3 AgentProcess — 실행 상태

```kotlin
// com/embabel/agent/core/AgentProcess.kt — 라이브러리 인용
interface AgentProcess : Blackboard, Timestamped, Timed,
                         OperationStatus<AgentProcessStatusCode>,
                         LlmInvocationHistory {
    val id: String
    val blackboard: Blackboard
    val parentId: String?                                            // 자식 프로세스의 부모 추적
    val processOptions: ProcessOptions
    val planner: Planner<*, *, *>
    val history: List<ActionInvocation>                              // 실행된 액션 + 소요 시간
    val goal: com.embabel.plan.Goal?
    // ... tick(), run(), kill() 등
}
```

- **thread-local 접근** — `AgentProcess.get()` 으로 *현재 스레드에서 실행 중인 프로세스* 핸들을 가져올 수 있음. `ConditionalReplanningTool` 같은 라이브러리 내부 코드가 이 패턴을 씁니다.
- **history** — 어떤 액션이 언제 얼마나 걸렸는지 시계열. 디버깅·관측성에 유용 (§9).
- **parentId** — 서브에이전트 호출 시 자식 프로세스가 부모 ID 를 보존 → 분산 트레이싱 자연스러움.

### 3.4 ProcessOptions — 실행 설정

`AgentInvocation.options(...)` 에 넘기는 객체. *"이번 한 번의 실행을 어떻게 운영할지"* 를 제어합니다.

```kotlin
// com/embabel/agent/core/ProcessOptions.kt — 라이브러리 인용
data class ProcessOptions @JvmOverloads constructor(
    val contextId: ContextId? = null,                                // 외부 자원 / 영속 키
    val identities: Identities = Identities(),                       // forUser, runAs
    val blackboard: Blackboard? = null,                              // 기존 블랙보드 재사용
    val verbosity: Verbosity = Verbosity(),                          // 로그 상세도
    val budget: Budget = Budget(),                                   // 비용·액션·토큰 상한
    val processControl: ProcessControl = ProcessControl(...),        // 지연·종료 정책
    val prune: Boolean = false,                                      // 무관 액션 제거
    val listeners: List<AgenticEventListener> = emptyList(),         // 이벤트 구독자
    val outputChannel: OutputChannel = DevNullOutputChannel,
    val plannerType: PlannerType = PlannerType.GOAP,
)

data class Budget @JvmOverloads constructor(
    val cost: Double = 2.0,                                          // USD
    val actions: Int = 50,
    val tokens: Int = 1_000_000,
)

data class Verbosity @JvmOverloads constructor(
    val showPrompts: Boolean = false,
    val showLlmResponses: Boolean = false,
    val debug: Boolean = false,
    val showPlanning: Boolean = false,
)

data class ProcessControl @JvmOverloads constructor(
    val toolDelay: Delay = Delay.NONE,
    val operationDelay: Delay = Delay.NONE,
    val earlyTerminationPolicy: EarlyTerminationPolicy =
        EarlyTerminationPolicy.maxActions(100),
)
```

**본 프로젝트 사용**:

```kotlin
// adapter/outbound/agent/EmbabelDeepResearchAgentAdapter.kt
.options(ProcessOptions().withListener(combined))
```

**활용 가능한 옵션 (현재 미사용)**

> 🔮 **개념 설명 / 향후 확장**

```kotlin
// 디버깅 — 프롬프트와 LLM 응답 모두 로그
.options(
    ProcessOptions()
        .withListener(combined)
        .withVerbosity(Verbosity().showPrompts().showLlmResponses().showPlanning())
)

// 비용 상한 — 사용자별 호출당 $0.50 / 토큰 50K 제한
.options(
    ProcessOptions()
        .withListener(combined)
        .withBudget(Budget(cost = 0.50, actions = 30, tokens = 50_000))
)

// 클라이언트 disconnect 시 즉시 종료 — earlyTerminationPolicy
.options(
    ProcessOptions()
        .withListener(combined)
        .withProcessControl(
            ProcessControl().withEarlyTerminationPolicy(
                EarlyTerminationPolicy.firstOf(
                    EarlyTerminationPolicy.maxActions(20),
                    EarlyTerminationPolicy.hardBudgetLimit(0.30),
                )
            )
        )
)

// 멀티테넌트 — 사용자 ID 를 process 에 부착 → @PreAuthorize / 감사 로그
.options(
    ProcessOptions()
        .withIdentities(Identities(forUser = currentUser))
)
```

### 3.5 블랙보드 vs 함수 인자 — 언제 무엇을 쓰나

Embabel 에서 데이터는 두 경로로 흐릅니다:

| 경로 | 어떻게 | 언제 |
|------|--------|------|
| **함수 인자** | `@Action fun gatherSections(plan: AgentResearchPlan, ...)` 에서 자동 주입 | **기본** — 90% 의 경우. 타입 안전·IDE 지원. |
| **명시적 Blackboard** | `context.addObject(...)`, `context.objectsOfType<T>()` | (1) *같은 타입* 의 객체가 여러 개일 때 (2) 액션 *외부* (예: 툴) 에서 부모 컨텍스트에 데이터 주입 (3) 서브에이전트 결과 끌어올림 |

> **본 프로젝트는 거의 함수 인자만 사용** — `gatherSections` 가 N개 `AgentSection` 을 만들지만, 모두 `AgentSectionDrafts` 라는 *컨테이너 타입* 한 개로 묶어 반환합니다. 만약 `AgentSection` 을 *개별 객체* 로 블랙보드에 쌓고 싶다면 `context.addObject(it)` 패턴이 필요.

---

## 4. 어노테이션 동작 모델 — `@Agent` / `@Action` / `@LlmTool`

§5 코드 워크스루로 들어가기 전에 어노테이션 메커닉을 정리합니다.

### 4.1 `@Agent` — 에이전트 클래스

```kotlin
// com/embabel/agent/api/annotation/annotations.kt — 라이브러리 인용 (요약)
@Component
annotation class Agent(
    val name: String = "",
    val description: String,                                         // 필수
    val version: String = DEFAULT_VERSION,
    val planner: PlannerType = PlannerType.GOAP,
    val opaque: Boolean = false,                                     // 액션·조건을 외부에 숨김
    val actionRetryPolicy: ActionRetryPolicy = ActionRetryPolicy.DEFAULT,
)
```

- **Spring stereotype** — `@Component` 를 메타-포함하므로 classpath scan 으로 자동 등록.
- **`description` 필수** — 다중 에이전트 환경에서 *어느 에이전트를 쓸지* LLM 이 고를 때 참조.
- **`opaque = true`** — 이 에이전트가 *블랙박스* 로 보이게 함. 서브에이전트로 노출 시 내부 액션을 부모 plan 에 합치지 않음.

본 프로젝트:
```kotlin
@Agent(description = "Produce a long-form, source-grounded research report on an arbitrary topic")
class DeepResearchAgent(...) { ... }
```

### 4.2 `@Action` — 단계

```kotlin
@Target(AnnotationTarget.FUNCTION)
annotation class Action(
    val description: String = "",
    val pre: Array<String> = [],                                     // 추가 precondition
    val post: Array<String> = [],                                    // 추가 effect
    val canRerun: Boolean = false,                                   // 같은 프로세스에서 재실행 가능?
    val readOnly: Boolean = false,                                   // 부작용 없음 (관찰용)
    val clearBlackboard: Boolean = false,                            // 실행 후 블랙보드 초기화
    val cost: ZeroToOne = 0.0,                                       // 정적 비용
    val value: ZeroToOne = 0.0,                                      // 정적 가치
    val costMethod: String = "",                                     // 동적 비용 메서드 이름
    val valueMethod: String = "",
    val trigger: KClass<*> = Unit::class,                            // 반응형: 이 타입이 마지막 결과여야 발동
    val actionRetryPolicy: ActionRetryPolicy = ActionRetryPolicy.DEFAULT,
)
```

- **`pre` / `post`** — 함수 시그니처가 자동 생성하는 조건 외에 **명시적** 으로 더할 수 있음. 비-타입 조건(예: `"user_authenticated"`).
- **`canRerun = false` (기본)** — 같은 프로세스에서 한 번만. `true` 면 GOAP 가 같은 액션을 plan 에 두 번 넣을 수 있음.
- **`readOnly`** — 외부 부작용 없음을 선언. *학습 / catchup* 모드에서 검증/감사용.
- **`trigger`** — *반응형* 동작. 지정된 타입이 *방금 추가된* 객체일 때만 액션 발동. 여러 입력이 다 모여 있어도 trigger 가 *방금 새로* 들어왔어야 함.
- **`actionRetryPolicy`** — `DEFAULT` (5회 exp backoff) 또는 `FIRE_ONCE` (단발). 본 프로젝트는 `assembleReport` 에 `FIRE_ONCE` (LLM 없는 결정론적 액션이라 재시도 무의미).

### 4.3 `@AchievesGoal` — 골 마커

```kotlin
@AchievesGoal(description = "A multi-section research report has been produced")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
fun assembleReport(...): AgentDeepResearchReport
```

이 액션의 반환 타입이 **goal 타입** 임을 선언. 플래너는 이 타입의 객체가 월드에 등장하면 plan 종결.

### 4.4 `@LlmTool` — LLM 에게 부여되는 능력

```kotlin
@LlmTool(
    name = "tavily_search",
    description = "Web search optimized for LLM grounding. ..." +
        "Call this BEFORE drafting keyPoints whenever the topic mentions ...",
)
fun tavilySearch(
    @LlmTool.Param(description = "Concise search query in the language most likely to retrieve relevant sources (English usually best).")
    query: String,
    @LlmTool.Param(description = "Maximum number of results to return (1..10).", required = false)
    maxResults: Int = 5,
): List<TavilyResult> = ...
```

- **`description` 이 곧 프롬프트** — Embabel 이 OpenAI tool-spec 의 `description` 필드로 그대로 송신. *"언제 호출해야 하는가"* 까지 적으면 호출 정확도 ↑.
- **`@LlmTool.Param`** — 각 인자에도 description. `required = false` + 기본값으로 옵셔널.
- **노출 범위** — `.withToolObject(researchTools)` 가 붙은 LLM 호출 *안에서만* 보입니다. 다른 액션에선 안 보임.

### 4.5 빌더 DSL — `context.ai().withLlm().withToolObject().create<T>()`

모든 `@Action` 에서 공통으로 보이는 패턴:

```kotlin
context.ai()                                                          // OperationContextAi
    .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))           // 모델·temperature
    .withToolObject(researchTools)                                    // 이 호출에만 @LlmTool 노출
    .create<AgentSection>("""...prompt...""")                         // 제네릭 = 강제 스키마
```

| 메서드 | 효과 |
|--------|------|
| `withLlm(LlmOptions...)` | 모델 ID, temperature, max-tokens. `withAutoLlm()` 은 `embabel.models.default-llm` 사용 |
| `withToolObject(obj)` | `obj` 의 `@LlmTool` 메서드 모두를 OpenAI tool-spec 으로 변환·주입 |
| `withTools("name1", ...)` | 등록된 다른 도구 빈을 이름으로 지정 |
| `create<T>(prompt)` | (1) 프롬프트 송신 → (2) tool loop → (3) `T` 로 파싱 → (4) `init { require }` 검증 → (5) 실패 시 자동 retry |

> **`create<T>` 의 마지막 단계가 도메인 검증과 결합** — `Section(body = "...")` 의 `init { require(body.length >= 50) }` 같은 조건이 LLM 출력에 자동으로 적용됩니다. 짧은 본문을 뱉으면 retry.

### 4.6 툴 노출 = 권한 부여

| `@Action` | `withToolObject(researchTools)` | 보이는 툴 |
|-----------|----------------------------------|------------|
| `planSubtopics` | ❌ | (없음) |
| `gatherSections` | ✅ | `tavily_search`, `current_date` |
| `frameReport` | ✅ | `tavily_search`, `current_date` |
| `assembleReport` | ❌ (LLM 미사용) | (없음) |

**툴 노출 = 권한 부여** 관점으로 생각하면 보안·비용 양 측면이 명료해집니다. 새 툴 추가 시 *어느 액션에서 보이는가* 를 한 자리에서 결정.

---

## 5. `POST /api/deep-research/stream` 코드 워크스루

> 라우터·핸들러·서비스 등 헥사고날 인프라는 [README.md §2 전체 아키텍처](./README.md#2-전체-아키텍처) 를 참고. 이 섹션은 **Embabel 진입 후** 만 다룹니다.

요청 한 번에 **LLM 약 7회** (plan 1 + sections 5 + framing 1) + **Tavily 검색 5–10회** + 마지막 결합 1회 (LLM 없음).

### 5.1 한눈에

```
AgentInvocation.invoke(UserInput("최근 프랑스 경제"))
    │
    ▼
Embabel GOAP Planner   ← 목표 타입(AgentDeepResearchReport)을 보고 plan 합성
    │
    ▼ plan = [planSubtopics, gatherSections, frameReport, assembleReport]
    │
DeepResearchAgent::planSubtopics(UserInput)             ──► AgentResearchPlan
    │                                                              ▼  ObjectAddedEvent → "plan-formulated" SSE
    │
DeepResearchAgent::gatherSections(plan)                 ──► AgentSectionDrafts
    │   ├ subtopic 1: LLM + tavily_search ×1~2  ──► AgentSection #1   ─► "section-drafted" SSE
    │   ├ subtopic 2: 동일                       ──► AgentSection #2  ─► ...
    │   └ ... (subtopicCount = 5)
    │
DeepResearchAgent::frameReport(plan, drafts)            ──► AgentReportFraming
    │   └ LLM + current_date ×1
    │
DeepResearchAgent::assembleReport(plan, drafts, framing) ──► AgentDeepResearchReport  (★ Goal 도달, LLM 없음)
    │
    ▼  채널 sink → Flow<ResearchEvent> → ServerSentEvent
client (SSE)
```

### 5.2 부트스트랩

```kotlin
@SpringBootApplication
class EmbabelTestApplication
fun main(args: Array<String>) { runApplication<EmbabelTestApplication>(*args) }
```

`embabel-agent-starter` 의 auto-configuration 이 다음을 발견·등록:

| 클래스패스에서 발견 | 결과 |
|----------------------|------|
| `@Agent class DeepResearchAgent` | `AgentPlatform` 카탈로그에 등록 |
| `@Action fun planSubtopics(...)` | 시그니처 분석 → 자동 precondition / effect 합성 |
| `@AchievesGoal @Action fun assembleReport(...)` | 반환 타입 = goal 타입 |
| `@LlmTool fun tavily_search(...)` | LLM 호출 시 노출 가능 |
| `@Component class ResearchTools` | DI 컨테이너 등록 → 에이전트가 생성자 주입 |

### 5.3 도메인 데이터 클래스 — plan 의 어휘

```kotlin
// adapter/outbound/agent/DeepResearchAgent.kt
data class AgentResearchSubtopic(val title: String, val rationale: String)

data class AgentResearchPlan(val mainTopic: String, val subtopics: List<AgentResearchSubtopic>)

data class AgentSection(val title: String, val body: String, val sources: List<String>)

data class AgentSectionDrafts(val sections: List<AgentSection>)

data class AgentReportFraming(val executiveSummary: String, val conclusion: String)

data class AgentDeepResearchReport(                                  // ← @AchievesGoal 의 반환 타입 = Goal
    val topic: String,
    val executiveSummary: String,
    val sections: List<AgentSection>,
    val sources: List<String>,
    val conclusion: String,
)
```

### 5.4 4개 `@Action` (전체 코드)

#### Step 1 — `planSubtopics` (LLM, 툴 없음)

```kotlin
@Action
fun planSubtopics(userInput: UserInput, context: OperationContext): AgentResearchPlan =
    context.ai()
        .withLlm(LlmOptions.withAutoLlm().withTemperature(0.3))
        .create(
            """
            Decompose the user's topic into $subtopicCount distinct subtopics suitable for
            a thorough research report. Each subtopic must be:
            - substantive enough to fill a 200-400 word section,
            - non-overlapping with the others,
            - investigatable through public web sources.

            Return AgentResearchPlan with mainTopic (echo the user input) and subtopics.

            # User input
            ${userInput.content}
            """.trimIndent(),
        )
```

| 항목 | 값 |
|------|-----|
| 입력 / 출력 | `UserInput` → `AgentResearchPlan` |
| 툴 | 없음 |
| temperature | `0.3` |

> **출력 예시 (topic = "최근 프랑스 경제")**
> ```json
> {
>   "mainTopic": "최근 프랑스 경제",
>   "subtopics": [
>     {"title": "GDP 성장률과 거시 지표", "rationale": "INSEE의 2024-25 분기 GDP 데이터..."},
>     {"title": "인플레이션과 가계 구매력", "rationale": "에너지 가격 안정 이후 CPI 추이..."},
>     {"title": "노동 시장과 실업률", "rationale": "청년 실업률 / 노동개혁 효과..."},
>     {"title": "재정 적자와 EU 규율", "rationale": "GDP 대비 적자 / EDP 절차..."},
>     {"title": "주요 산업 동향 (에너지·자동차·AI)", "rationale": "원전 회귀, EV 전환..."}
>   ]
> }
> ```
> `ObjectAddedEvent` → `PlanAndSectionListener` 가 가로채 `plan-formulated` SSE 발행 (§6).

#### Step 2 — `gatherSections` (LLM × N + tavily_search)

```kotlin
@Action
fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts {
    val drafts = plan.subtopics.map { subtopic ->
        context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
            .withToolObject(researchTools)                          // ★ 툴 노출
            .create<AgentSection>(
                """
                Draft ONE section of a report on '${plan.mainTopic}'. Subtopic: '${subtopic.title}'.

                Steps:
                1. Issue 1-2 tavily_search calls (no more) with focused queries.
                2. Write a $sectionWordCount-word section grounded on the results.
                3. Cite each claim inline as [https://url].

                Return AgentSection { title, body (with inline [URL] citations), sources (deduped URL list) }.
                Keep prose tight. No headings. No filler.
                """.trimIndent(),
            )
    }
    return AgentSectionDrafts(drafts)
}
```

**시간축으로 펼치면 (subtopic 1개당)**

```
LLM round 1
   → assistant tool_call: tavily_search(query="French GDP growth Q1 2025 INSEE")
   ← tool_result: [{title: "INSEE: France GDP grew 0.1% in Q1...", url, content}, ...]
LLM round 2
   → assistant tool_call: tavily_search(query="France 2024 annual GDP final estimate")
   ← tool_result: [...]
LLM round 3
   → assistant final: AgentSection { title, body (with [https://...] citations), sources }
   ▼  ObjectAddedEvent(AgentSection) → "section-drafted" SSE
```

#### Step 3 — `frameReport` (LLM + current_date)

```kotlin
@Action
fun frameReport(
    plan: AgentResearchPlan,
    drafts: AgentSectionDrafts,
    context: OperationContext,
): AgentReportFraming {
    val titlesBlock = drafts.sections.joinToString("\n") { section ->
        "- ${section.title}: ${section.body.take(160).replace('\n', ' ')}..."
    }
    return context.ai()
        .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
        .withToolObject(researchTools)
        .create(
            """
            You are framing a research report on '${plan.mainTopic}'.
            The report already has ${drafts.sections.size} drafted sections (do NOT rewrite them).

            # Section briefs
            $titlesBlock

            Tasks:
            1. Call current_date() once.
            2. Write a 2-3 sentence executiveSummary that frames the topic and includes 'as of YYYY-MM-DD'.
            3. Write a 3-4 sentence conclusion that synthesizes the main findings across the sections.

            Return AgentReportFraming with executiveSummary and conclusion. Keep both concise.
            """.trimIndent(),
        )
}
```

**컨텍스트 압축 트릭** — 각 섹션 본문 전체가 아니라 **첫 160자 + "…"** 만 노출. 모든 본문 재송신 시 ~5,000 토큰 추가 → 비용·지연·재시도 악화. 160자만으로도 *"무슨 내용을 다뤘는지"* 파악엔 충분.

#### Step 4 — `assembleReport` (LLM 없음, 순수 Kotlin)

```kotlin
@AchievesGoal(description = "A multi-section research report has been produced")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)              // ← 결정론적 → 재시도 무의미
fun assembleReport(
    plan: AgentResearchPlan,
    drafts: AgentSectionDrafts,
    framing: AgentReportFraming,
): AgentDeepResearchReport = AgentDeepResearchReport(
    topic = plan.mainTopic,
    executiveSummary = framing.executiveSummary,
    sections = drafts.sections,
    sources = drafts.sections.flatMap { it.sources }.distinct(),
    conclusion = framing.conclusion,
)
```

> 💡 **왜 `frameReport` 와 `assembleReport` 를 분리했나** — 한 LLM 호출로 5섹션 + 메타까지 재생성하면 출력 토큰 한도 초과 → 무한 retry. **합성(synthesis)은 LLM, 결합(stitching)은 순수 Kotlin** 으로 분리하면 안정성·비용·지연 모두 개선. 환각으로 sources 가 사라질 위험도 제거.

### 5.5 Embabel 진입점 — `AgentInvocation`

`@Agent` / `@Action` 으로 등록된 모든 정보는 Spring 컨테이너 안의 `AgentPlatform` 빈에 모입니다. 실행 트리거:

```kotlin
val report: AgentDeepResearchReport = AgentInvocation
    .builder(agentPlatform)
    .options(ProcessOptions().withListener(combinedListener))
    .build(AgentDeepResearchReport::class.java)                       // ← 목표 타입
    .invoke(UserInput("최근 프랑스 경제"))
```

이 4 라인이 트리거하는 작업:

1. `AgentPlatform` 의 액션·골 카탈로그 로드
2. **GOAP 플래너 호출** — `AgentDeepResearchReport` 를 만들 수 있는 plan 합성
3. plan 의 첫 액션부터 순차 실행. 각 액션 후 **replan 가능** (OODA)
4. 각 단계마다 listener 에 5종 콜백 push (§6)
5. goal 도달 시 결과 객체 반환

**블로킹 API** — WebFlux 환경에선 `Dispatchers.IO` 코루틴 안에서. 본 프로젝트의 어댑터(`EmbabelDeepResearchAgentAdapter.streamDeepResearch`) 가 이 패턴.

### 5.6 실제 SSE 응답 샘플

```bash
curl -N -X POST http://localhost:8080/api/deep-research/stream \
     -H 'Content-Type: application/json' \
     -d '{"topic":"최근 프랑스 경제"}'
```

```
event: started
data: {"type":"started","payload":{"topic":"최근 프랑스 경제"}}

event: action-started
data: {"type":"action-started","payload":{"action":"planSubtopics"}}

event: action-completed
data: {"type":"action-completed","payload":{"action":"planSubtopics","status":"SUCCEEDED","durationMs":3812}}

event: plan-formulated
data: {"type":"plan-formulated","payload":{"subtopics":["GDP 성장률과 거시 지표","인플레이션 / 가계 구매력","노동 시장과 실업률","재정 적자와 EU 규율","주요 산업 동향"]}}

event: action-started
data: {"type":"action-started","payload":{"action":"gatherSections"}}

event: tool-invoked
data: {"type":"tool-invoked","payload":{"tool":"tavily_search","input":"French GDP growth 2025 INSEE"}}

event: tool-returned
data: {"type":"tool-returned","payload":{"tool":"tavily_search","durationMs":1247,"preview":"[{title=France GDP growth slows in Q3..., url=https://..."}}

event: section-drafted
data: {"type":"section-drafted","payload":{"title":"GDP 성장률과 거시 지표","sourceCount":3}}

... (subtopic 2..5 반복)

event: action-completed
data: {"type":"action-completed","payload":{"action":"gatherSections","status":"SUCCEEDED","durationMs":48201}}

event: action-started
data: {"type":"action-started","payload":{"action":"frameReport"}}

event: tool-invoked
data: {"type":"tool-invoked","payload":{"tool":"current_date","input":""}}

event: tool-returned
data: {"type":"tool-returned","payload":{"tool":"current_date","durationMs":2,"preview":"2026-05-05"}}

event: action-completed
data: {"type":"action-completed","payload":{"action":"frameReport","status":"SUCCEEDED","durationMs":4109}}

event: action-started
data: {"type":"action-started","payload":{"action":"assembleReport"}}

event: action-completed
data: {"type":"action-completed","payload":{"action":"assembleReport","status":"SUCCEEDED","durationMs":1}}

event: deep-research-completed
data: {"type":"deep-research-completed","payload":{"report":{"topic":"최근 프랑스 경제","executiveSummary":"As of 2026-05-05, ...","sections":[...],"sources":[...],"conclusion":"..."}}}
```

> **클라이언트 라우팅** — `event:` 헤더로 EventSource 가 분기.
> ```js
> const es = new EventSource(...);
> es.addEventListener('section-drafted', e => updateProgress(JSON.parse(e.data)));
> es.addEventListener('deep-research-completed', e => render(JSON.parse(e.data).payload.report));
> ```

---

## 6. 이벤트 카탈로그 — `AgenticEventListener` 이벤트

§5 에서 본 5개 외에도 라이브러리는 풍부한 이벤트를 발행합니다. 모두 `com.embabel.agent.api.event.AgentProcessEvent` 의 하위 타입.

### 6.1 이벤트 분류표 (총 23종)

| # | 이벤트 | 카테고리 | 발생 시점 | 주요 payload | 본 프로젝트 활용 |
|---|--------|----------|-----------|---------------|------------------|
| 1 | `AgentProcessCreationEvent` | 생명주기 | invoke 직후 | `agentProcess` | ❌ |
| 2 | `AgentProcessReadyToPlanEvent` | 플래닝 | 매 plan 시도 직전 | `worldState` | ❌ |
| 3 | `AgentProcessPlanFormulatedEvent` | 플래닝 | plan 합성 직후 | `worldState`, `plan` | ❌ |
| 4 | `ReplanRequestedEvent` | 플래닝 | replan 요청 시 | `reason` | ❌ |
| 5 | `StateTransitionEvent` | 상태 | 상태 머신 전이 | `newState`, `previousState` | ❌ |
| 6 | `ActionExecutionStartEvent` | 액션 | 액션 시작 | `action` | ✅ → `action-started` |
| 7 | `ActionExecutionResultEvent` | 액션 | 액션 종료 | `action`, `actionStatus`, `runningTime` | ✅ → `action-completed` |
| 8 | `ToolLoopStartEvent` | LLM | 툴 루프 시작 | `toolNames`, `maxIterations`, `outputClass` | ❌ |
| 9 | `ToolLoopCompletedEvent` | LLM | 툴 루프 종료 | `totalIterations`, `replanRequested`, `runningTime` | ❌ |
| 10 | `ToolCallRequestEvent` | 툴 | LLM 이 툴 호출 | `tool`, `toolInput`, `correlationId` | ✅ → `tool-invoked` |
| 11 | `ToolCallResponseEvent` | 툴 | 툴 응답 도착 | `result`, `runningTime` | ✅ → `tool-returned` |
| 12 | `LlmRequestEvent` | LLM | LLM 호출 직전 | `outputClass`, `messages`, `llmMetadata` | ❌ |
| 13 | `LlmResponseEvent` | LLM | LLM 응답 도착 | `response`, `runningTime` | ❌ |
| 14 | `ObjectAddedEvent` | 도메인 | 익명 객체 추가 | `value` | ✅ → `plan-formulated`, `section-drafted` |
| 15 | `ObjectBoundEvent` | 도메인 | 키 바인딩 | `name`, `value` | ❌ |
| 16 | `ProgressUpdateEvent` | 진척 | 액션이 명시적 진행률 발행 | `name`, `current`, `total` | ❌ |
| 17 | `GoalAchievedEvent` | 종료 | goal 도달 | `goal`, `worldState` | ❌ |
| 18 | `AgentProcessCompletedEvent` | 종료 | 정상 종료 | `result` | (어댑터에서 별도 처리) |
| 19 | `AgentProcessFailedEvent` | 종료 | 실패 종료 | — | (어댑터에서 별도 처리) |
| 20 | `AgentProcessWaitingEvent` | 종료 | 입력 대기 | — | ❌ |
| 21 | `AgentProcessPausedEvent` | 종료 | 일시 중단 | — | ❌ |
| 22 | `AgentProcessStuckEvent` | 종료 | plan 합성 불가 | — | ❌ |
| 23 | `ProcessKilledEvent` | 종료 | `kill()` 호출됨 | — | ❌ |

> 표가 23행이지만 *현실적으로 사용자가 신경 쓸* 카테고리는 *생명주기 / 액션 / LLM·툴 / 도메인 / 종료* 의 5개. 본 프로젝트는 *액션·툴·도메인·종료* 만 사용 중.

### 6.2 본 프로젝트 매핑 (`StreamingAgentEventListener.kt` 전체)

```kotlin
// adapter/outbound/agent/StreamingAgentEventListener.kt
class StreamingAgentEventListener(
    private val sink: Sinks.Many<ResearchEvent>,
) : AgenticEventListener {

    override fun onProcessEvent(event: AgentProcessEvent) {
        val mapped = when (event) {
            is ActionExecutionStartEvent -> ResearchEvent.ActionStarted(
                action = event.action.shortName(),
            )
            is ActionExecutionResultEvent -> ResearchEvent.ActionCompleted(
                action = event.action.shortName(),
                status = event.actionStatus.status.name,
                durationMs = event.runningTime.toMillis(),
            )
            is ToolCallRequestEvent -> ResearchEvent.ToolInvoked(
                tool = event.tool,
                input = event.toolInput.takeUnless(String::isBlank).orEmpty(),
            )
            is ToolCallResponseEvent -> ResearchEvent.ToolReturned(
                tool = event.request.tool,
                durationMs = event.runningTime.toMillis(),
                resultPreview = event.resultPreview(),                // ≤240자 truncate
            )
            else -> null
        }
        if (mapped != null) sink.tryEmitNext(mapped)
    }

    private fun ToolCallResponseEvent.resultPreview(): String {
        val raw = runCatching { result.toString() }.getOrDefault("")
        return if (raw.length <= 240) raw else raw.substring(0, 240) + "..."
    }
}
```

도메인 시점은 별도 리스너:

```kotlin
// EmbabelDeepResearchAgentAdapter.kt 의 inner class
private class PlanAndSectionListener(
    private val sink: Sinks.Many<ResearchEvent>,
) : AgenticEventListener {
    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is ObjectAddedEvent) return
        when (val payload = event.value) {
            is AgentResearchPlan -> sink.tryEmitNext(
                ResearchEvent.PlanFormulated(payload.subtopics.map { it.title }),
            )
            is AgentSection -> sink.tryEmitNext(
                ResearchEvent.SectionDrafted(payload.title, payload.sources.size),
            )
        }
    }
}
```

두 리스너를 한꺼번에 등록 — `MulticastListener` 합성:

```kotlin
private class MulticastListener(private val listeners: List<AgenticEventListener>) : AgenticEventListener {
    override fun onProcessEvent(event: AgentProcessEvent) {
        listeners.forEach { it.onProcessEvent(event) }
    }
}

val combined = MulticastListener(listOf(baseListener, planListener))
ProcessOptions().withListener(combined)
```

> **저수준(프로세스) + 고수준(도메인) 이 한 sink 로 합류** → 클라이언트는 `event:` 헤더만 보고 분기. 책임 분리는 *서버 안에서* 끝남.

### 6.3 미사용 이벤트의 활용 시나리오

> 🔮 **개념 설명 / 향후 확장**

#### `LlmRequestEvent` / `LlmResponseEvent` — LLM 토큰·지연 추적

```kotlin
class LlmMetricsListener(private val meterRegistry: MeterRegistry) : AgenticEventListener {
    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is LlmResponseEvent<*> -> {
                meterRegistry.timer("llm.invocation",
                    "model", event.request.llmMetadata.modelName,
                    "action", event.request.action?.name ?: "unknown",
                ).record(event.runningTime)
                // event.request.messages 길이 → input tokens 추정
                // event.response 직렬화 길이 → output tokens 추정
            }
            else -> {}
        }
    }
}
```

#### `ToolLoopStart/Completed` — 진행 중 tool loop UX

```kotlin
// "지금 검색 중..." 스피너를 ToolLoopStart 에서 켜고, Completed 에서 끔
// totalIterations 와 replanRequested 로 "회복 시도" 표시 가능
```

#### `GoalAchievedEvent` — `AgentProcessCompletedEvent` 와의 차이

`GoalAchievedEvent` 는 *plan 의 종착 액션이 끝난 시점*, `AgentProcessCompletedEvent` 는 *프로세스 자체 종료 시점*. 보통 둘이 거의 동시지만, post-processing 단계가 있으면 갈릴 수 있음.

#### `ProgressUpdateEvent` — 명시적 진행률

```kotlin
@Action
fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts {
    plan.subtopics.forEachIndexed { idx, subtopic ->
        // 명시적 진행률 push (라이브러리는 자동으로 ProgressUpdateEvent 발행)
        context.processContext.platformServices.eventListener
            .onProcessEvent(ProgressUpdateEvent(
                agentProcess = context.agentProcess,
                name = "drafting sections",
                current = idx + 1,
                total = plan.subtopics.size,
            ))
        // ... 섹션 작성 ...
    }
}
```

#### `ProcessKilledEvent` — 클라이언트 disconnect 시

WebFlux 클라이언트가 SSE 연결을 끊으면 `channelFlow` 가 cancel 됨. 이때 `agentJob.cancel()` 만으론 *이미 시작된 LLM 호출* 이 끝까지 돌아갑니다. `processControl.kill()` 을 호출하면 `ProcessKilledEvent` 가 발행되고 budget 검사에서 즉시 종료.

### 6.4 클라이언트 SSE 라우팅 가이드

| 이벤트 | UI 위치 |
|--------|---------|
| `started` | "준비 중..." 토스트 |
| `plan-formulated` | sidebar 에 subtopic 리스트 표시 (회색) |
| `action-started: gatherSections` | 진행률 바 시작 |
| `tool-invoked: tavily_search` | "🔍 {input} 검색 중" 마이크로 알림 |
| `tool-returned` | preview (240자) 를 hover tooltip 으로 |
| `section-drafted` | sidebar 의 해당 subtopic 을 ✓ 표시 + 본문 영역 fade-in 준비 |
| `action-completed: gatherSections` | 진행률 바 100% |
| `deep-research-completed` | 최종 본문 렌더링 |
| `failed` | 토스트 + 재시도 버튼 |

---

## 7. 오류 처리 / 재계획 / Fallback

> *"한 액션이 실패하면? 같은 입력으로 재시도? 다른 plan 으로 우회? 무한 루프 방어?"* — **5층 구조** 로 답합니다.

### 7.1 5층 방어선

| 층 | 메커니즘 | 트리거 | 효과 | 코드 위치 |
|----|----------|--------|------|-----------|
| ① | **Spring Retry (RetryTemplate)** | LLM API 실패 / rate limit | 같은 호출 exp backoff 재시도 | `RetryProperties.kt` |
| ② | **Action Retry Policy** | 액션 실행 예외 | 액션 전체를 최대 N회 재시도 | `ActionRetryPolicy` enum |
| ③ | **도메인 검증 → LLM 재호출** | `init { require(...) }` 실패 | LLM 같은 액션 다시 시도 | 도메인 `data class` |
| ④ | **GOAP Replan (자동)** | 액션 결과로 월드 변화 | 매 액션 후 plan 재합성 | `Planner.bestValuePlanToAnyGoal` |
| ⑤ | **명시적 Replan** | 툴이 `ReplanRequestedException` throw | plan 즉시 폐기 + 새 plan 합성 | `ReplanRequestedException` |

### 7.2 [① + ②] `ActionRetryPolicy`

```kotlin
// com/embabel/agent/core/ActionRetryPolicy.kt — 라이브러리 인용
enum class ActionRetryPolicy {
    /** Fire only once: maps to ActionQos with maxAttempts = 1. */
    FIRE_ONCE,
    /** max-attempts: 5 / backoff-millis: 10000 / multiplier: 5.0 / max-interval: 60000 / idempotent: false */
    DEFAULT,
}
```

내부는 Spring Retry `RetryTemplate` + exponential backoff:

```kotlin
// com/embabel/agent/spi/common/RetryProperties.kt — 라이브러리 인용 (요약)
override fun retryTemplate(name: String): RetryTemplate {
    return RetryTemplate.builder()
        .exponentialBackoff(Duration.ofMillis(10_000), 5.0, Duration.ofMillis(60_000))
        .customPolicy(SpringAiRetryPolicy(maxAttempts = 5))
        .withListener(object : RetryListener {
            override fun <T, E : Throwable> onError(...) {
                // ToolControlFlowSignal (ReplanRequestedException) 은 재시도 X
                if (throwable is ToolControlFlowSignal) throw throwable
                if (isRateLimitError(throwable)) {
                    log.info("LLM RATE LIMITED: Retry {}/{}", retryCount, maxAttempts)
                }
            }
        })
        .build()
}
```

**핵심 디테일**

- **rate limit 인지** — 429 등을 별도 로깅하면서 재시도.
- **`ToolControlFlowSignal` 은 재시도 안 함** — `ReplanRequestedException` 같은 *제어 흐름 신호* 는 즉시 위로. 재시도 = "같은 결과", 제어 흐름 = "다른 길로" → 의미가 다름.
- **`idempotent = false` 기본값** — 부작용 있는 액션도 재시도 가능. 결제 등 진짜 idempotency 필요시 `true`.

### 7.3 본 프로젝트 적용 — `assembleReport: FIRE_ONCE`

```kotlin
@AchievesGoal(description = "...")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
fun assembleReport(plan, drafts, framing): AgentDeepResearchReport = ...
```

**왜 옳은가** — `assembleReport` 는 순수 Kotlin (LLM 없음). 실패 원인은 *결정론적 코드 버그* 또는 *상위 객체의 init 검증 실패*. 같은 입력으로 5번 재시도해도 결과 동일 → 시간·돈 낭비. 한 번 실패하고 위로 던져 빨리 알게 하는 편이 디버깅 유리.

> **일반화**: LLM 호출 없는 결정론적 액션 (assemble / merge / format) 은 모두 `FIRE_ONCE` 가 합리적.

### 7.4 [③] 도메인 검증이 LLM 재호출 트리거

```kotlin
// domain/model/DeepResearchReport.kt
data class DeepResearchReport(...) {
    init {
        require(executiveSummary.isNotBlank()) { "executiveSummary must not be blank" }
        require(sections.isNotEmpty())          { "report must contain at least one section" }
        require(conclusion.isNotBlank())        { "conclusion must not be blank" }
    }
}
```

흐름:

```
LLM 응답 (JSON)
   │  Jackson 파싱
   ▼  data class 생성자 호출
   ▼  init { require(...) } 실행
   │
   ├── 통과 → 객체 반환
   └── 실패 (IllegalArgumentException)
         ▼  Spring Retry RetryTemplate 가 잡음
         ▼  LLM 다시 호출 (같은 프롬프트, 다른 샘플링)
```

**왜 강력한가**: 검증 로직이 *코드* 에 있으면 LLM 이 비슷한 실수를 반복할 확률이 줄어듭니다. *프롬프트* 안의 *"please return a valid object"* 보다 훨씬 신뢰도 높음.

### 7.5 [④] GOAP Replan — 매 액션 후 자동

§2.2 OODA 루프의 핵심. 코드:

```kotlin
// com/embabel/plan/Planner.kt — 라이브러리 인용
fun bestValuePlanToAnyGoal(system: PlanningSystem): P? =
    plansToGoals(system).firstOrNull()                                // net value 내림차순 첫 번째
```

매 액션 종료 후 호출 → *다음에 어느 액션을 실행할지* 다시 결정. 어떤 액션이 빈 객체를 반환했다면 다음 단계 액션의 precondition 이 만족 안 되니 **플래너가 다른 경로를 자동 탐색**. 코드 0줄로 fallback.

### 7.6 [⑤] 명시적 Replan — `ReplanRequestedException`

```kotlin
// com/embabel/agent/core/ReplanRequestedException.kt — 라이브러리 인용
class ReplanRequestedException @JvmOverloads constructor(
    val reason: String,
    val blackboardUpdater: BlackboardUpdater = BlackboardUpdater {},
) : RuntimeException(reason), ToolControlFlowSignal
```

> 🔮 **개념 — 본 프로젝트 미적용**
>
> ```kotlin
> @LlmTool(name = "tavily_search", ...)
> fun tavilySearch(query: String, maxResults: Int = 5): List<TavilyResult> {
>     if (tavilyApiKey.isBlank()) {
>         throw ReplanRequestedException(
>             reason = "Tavily API key not configured; switching to no-search mode",
>             blackboardUpdater = { bb -> bb.addObject(SearchUnavailable) },
>         )
>     }
>     // ... 정상 검색 ...
> }
> ```
>
> 이때 GOAP 플래너는 `SearchUnavailable` 이 월드에 있으면 `gatherSectionsOffline` 같은 다른 액션을 골라 plan 재합성 (부록 B 의 시나리오).

### 7.7 `ReplanningTool` 데코레이터

매번 throw 하기 번거롭다면 데코레이터로:

```kotlin
// com/embabel/agent/api/tool/ReplanningToolFactory.kt — 라이브러리 인용
interface ReplanningToolFactory {
    fun replanAlways(tool: Tool): Tool                                // 항상 replan
    fun <T> replanWhen(tool: Tool, predicate: (t: T) -> Boolean): DelegatingTool
    fun <T> conditionalReplan(tool: Tool, decider: (T, ReplanContext) -> ReplanDecision?): DelegatingTool
}
```

> 🔮 **활용 예 (개념)** — 검색 결과가 비면 자동 replan
> ```kotlin
> val smartSearch = ReplanningToolFactory.replanWhen<List<TavilyResult>>(rawSearchTool) { results ->
>     results.isEmpty()
> }
> ```

### 7.8 무한 루프 방어

```kotlin
// com/embabel/plan/Planner.kt — 라이브러리 인용
fun bestValuePlanToAnyGoal(system: PlanningSystem, excludedActionNames: Set<String>): P? {
    val filteredSystem = ... // 이미 실패한 액션을 제외한 PlanningSystem
    return bestValuePlanToAnyGoal(filteredSystem)
}
```

```kotlin
// com/embabel/plan/goap/astar/AStarGoapPlanner.kt — 라이브러리 인용
val maxIterations = 10000   // A* 의 안전 가드
```

플래너 차원: **excluded 셋** 으로 같은 액션 제외. 검색 차원: **iteration 상한**.

`ProcessOptions.budget.actions` (기본 50) 도 추가 가드 — 액션 수가 50을 넘으면 `EarlyTerminationPolicy.maxActions` 가 작동.

### 7.9 본 프로젝트의 다중 방어선

| 층 | 본 프로젝트 사례 |
|----|------------------|
| ① Spring Retry | `embabel-agent-starter` 자동 wiring (코드 0줄) |
| ② Action Retry | `assembleReport` 만 `FIRE_ONCE`, 나머지 `DEFAULT` |
| ③ 도메인 검증 | `ResearchTopic`, `Section`, `DeepResearchReport`, `ResearchPlan` 의 `init { require(...) }` |
| ④ GOAP Replan | 매 액션 후 자동. 본 프로젝트는 모든 액션이 한 경로에 있어 효과 적음. 액션 추가 시 자동 발휘 |
| ⑤ 명시적 Replan | 미사용 — 부록 B 의 `SearchUnavailable` 패턴이 향후 확장 지점 |

**프롬프트 레벨 가드**:
- `embabel.agent.platform.llm-operations.prompts.default-timeout: 240s` — 짧은 타임아웃이 retry 폭주 원인이었음.
- `gatherSections` 프롬프트의 `"1-2 tavily_search calls (no more)"` — 툴 호출 수 제한.

---

## 8. 서브에이전트와 합성 — 개념 + 향후 확장 가이드

> 🔮 **이 섹션 전체는 본 프로젝트 미구현** — 단일 `@Agent` 만 사용. 라이브러리에 어떤 합성 기능이 있고 *언제 분리하는 게 좋은지* 의 가이드입니다.

### 8.1 Subagent 정의

```kotlin
// com/embabel/agent/api/tool/Subagent.kt — 라이브러리 인용 (요약)
class Subagent {
    companion object {
        fun ofClass(clazz: Class<*>): Subagent                        // @Agent 어노테이션 클래스
        inline fun <reified T> ofClass(): Subagent                     // Kotlin reified
        fun byName(name: String): Subagent                             // 런타임 이름 해석
        fun ofInstance(agent: Agent): Subagent                         // 직접 인스턴스
        fun ofAnnotatedInstance(bean: Any): Subagent                   // 어노테이션 빈
    }
}
```

서브에이전트는 *부모의 블랙보드 컨텍스트를 공유* 하는 자식 에이전트로 동작. 입력 타입은 *자식의 첫 액션 시그니처* 로부터 자동 추론.

### 8.2 `RunSubagent` — 액션 안에서 호출

```kotlin
// 패턴: PromptRunner 의 withTool 로 등록
context.ai()
    .withTool(Subagent.ofClass<MyAgent>())
    .creating(Result::class.java)
    .fromPrompt("...")
```

또는 직접 `RunSubagent.instance(...)` / `RunSubagent.fromAnnotatedInstance(...)` 호출.

### 8.3 핸드오프 패턴 — 부모가 결과를 받는 경로

| 경로 | 시나리오 |
|------|----------|
| **반환값** | 자식의 goal 객체를 부모 액션의 *반환 타입* 으로 그대로 받음 |
| **부모 블랙보드 `addObject`** | 자식이 *여러* 객체를 만들고 부모의 후속 액션이 각각을 입력으로 받아야 할 때 |

자식 프로세스는 `parentId` 를 보존 → 분산 트레이싱 자연스러움 (§9.4).

### 8.4 본 프로젝트가 확장한다면

> 🔮 **가설**: 현재 `gatherSections` 는 한 액션이 N 개 subtopic 을 직렬 처리. 만약 *각 subtopic 을 독립적인 에이전트로* 만들면:
>
> ```kotlin
> @Agent(description = "Research a single subtopic with web grounding")
> class SectionResearchAgent(private val tools: ResearchTools) {
>     @AchievesGoal(description = "A single section drafted")
>     @Action
>     fun draftSection(input: SectionRequest, context: OperationContext): AgentSection =
>         context.ai().withLlm(...).withToolObject(tools).create<AgentSection>(...)
> }
>
> // DeepResearchAgent 의 gatherSections 를 다음으로 교체
> @Action
> fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts {
>     val drafts = context.parallelMap(plan.subtopics, maxConcurrency = 3) { subtopic ->
>         context.fireAgent(SectionRequest(plan.mainTopic, subtopic), AgentSection::class.java)
>             ?.get() ?: error("section research failed")
>     }
>     return AgentSectionDrafts(drafts)
> }
> ```
>
> **얻는 것**:
> - 각 subtopic 이 독립 budget·재시도. 한 섹션이 실패해도 다른 섹션 영향 X.
> - `parallelMap(maxConcurrency = 3)` 로 병렬화 → 5 섹션 직렬 75초 → 병렬 ~25초.
> - 트레이싱 시 자식 프로세스가 별도 span tree 로 분리 → 어느 subtopic 이 느린지 즉시 가시.
> - `SectionResearchAgent` 를 다른 컨텍스트 (단일 섹션 endpoint, 외부 호출) 에서 재사용 가능.
>
> **잃는 것**:
> - 추가 클래스·등록 비용. 작은 흐름에는 과한 추상화.
> - 자식 프로세스 생성 오버헤드 (수십 ms).

### 8.5 단일 `@Agent` vs 분리 — 결정 기준

| 신호 | 분리 권장 |
|------|-----------|
| 한 단계가 *여러 인스턴스를 동시에* 처리 | ✅ (병렬화 + 격리) |
| 한 단계가 다른 LLM 모델·budget 가 필요 | ✅ |
| 한 단계가 *재사용 가능한 능력* (다른 endpoint 에서도 호출) | ✅ |
| 한 단계의 부분 실패가 전체를 죽이지 않아야 함 | ✅ |
| 한 단계의 history 가 부모 history 를 오염시키면 안 됨 (privacy/audit) | ✅ |
| 단순 직렬 흐름·단일 책임 | ❌ 단일 `@Agent` 유지 |

> **본 프로젝트 현재**: 단일 `@Agent` 가 합리적. 만약 사용자 사용량이 늘고 *섹션별 latency 분포* 가 중요해지면 §8.4 패턴 검토.

---

## 9. 테스트와 관측성

### 9.1 `FakeOperationContext` — 본 프로젝트가 이미 쓰는 패턴

Embabel `embabel-agent-test` 모듈은 LLM 호출을 *큐* 로 스텁할 수 있는 테스트 더블을 제공합니다. 본 프로젝트의 `DeepResearchAgentTest` 는 이를 활용:

```kotlin
// src/test/kotlin/.../DeepResearchAgentTest.kt
class DeepResearchAgentTest {
    private val tools = ResearchTools(
        tavilyApiKey = "tvly-test",
        tavilyBaseUrl = "https://api.tavily.test",
        webClient = WebClient.builder().build(),
    )
    private val agent = DeepResearchAgent(researchTools = tools, subtopicCount = 5, sectionWordCount = 300)

    @Test
    fun `planSubtopics asks for the configured number of subtopics`() {
        val context = fakeContextReturning(
            AgentResearchPlan(
                mainTopic = "CQRS",
                subtopics = listOf(AgentResearchSubtopic("Definition", "core")),
            ),
        )

        agent.planSubtopics(UserInput("CQRS"), context)

        val prompt = (context.promptRunner() as FakePromptRunner)
            .llmInvocations.first().messages.single().content
        assertTrue(prompt.contains("CQRS"))
        assertTrue(prompt.contains("5"))
    }

    @Test
    fun `gatherSections drafts one section per subtopic`() {
        val plan = AgentResearchPlan(mainTopic = "CQRS", subtopics = listOf(
            AgentResearchSubtopic("Definition", "core"),
            AgentResearchSubtopic("Eventual consistency", "tradeoff"),
        ))
        val context = FakeOperationContext.create()
        plan.subtopics.forEach { sub ->
            context.expectResponse(
                AgentSection(
                    title = sub.title,
                    body = "body for ${sub.title}",
                    sources = listOf("https://example.com/${sub.title.lowercase()}"),
                ),
            )
        }

        val drafts = agent.gatherSections(plan, context)

        assertEquals(2, drafts.sections.size)
    }

    private fun fakeContextReturning(response: Any): OperationContext {
        val context = FakeOperationContext.create()
        context.expectResponse(response)
        return context
    }
}
```

**메커닉**

- **`FakeOperationContext.create()`** — 실제 Embabel 런타임 없이 `OperationContext` 인터페이스를 구현한 fake.
- **`expectResponse(obj)`** — *다음 LLM 호출이 이 객체를 반환할 것* 을 큐에 등록. `gatherSections` 처럼 N 회 호출하는 액션은 N 번 큐잉.
- **`FakePromptRunner.llmInvocations`** — 실행 후 *어떤 프롬프트가* 송신됐는지 검증. `messages.single().content` 로 프롬프트 텍스트 접근.

### 9.2 테스트 매트릭스

| 레이어 | 도구 | 본 프로젝트 예시 | 검증 대상 |
|--------|------|-------------------|-----------|
| **도메인 검증** | JUnit5 | `ResearchTopicTest`, `DeepResearchReportTest` | `init { require(...) }` 동작 |
| **에이전트 단위** | `FakeOperationContext` | `ResearchPlannerAgentTest`, `DeepResearchAgentTest` | 프롬프트 내용·반환 객체 매핑 |
| **툴 어댑터** | OkHttp `MockWebServer` | `ResearchToolsTest` | Tavily 요청·응답 직렬화 |
| **핸들러/라우터** | `WebTestClient` + Spring REST Docs | `ResearchHandlerTest` | SSE 페이로드·HTTP 계약 |

> **`FakeOperationContext` 가 LLM 을 부르지 않으므로** 단위 테스트는 빠르고 결정적. CI 에서 매번 돌릴 수 있음. 통합 테스트 (실제 LLM 호출) 는 별도 `@Tag("integration")` 으로 격리.

### 9.3 관측성 — `AgenticEventListener` 트레이싱

> 🔮 **개념 — 본 프로젝트 미구현**

#### Micrometer Timer 사이드카

```kotlin
@Component
class ActionMetricsListener(
    private val meterRegistry: MeterRegistry,
) : AgenticEventListener {
    private val activeTimers = ConcurrentHashMap<Pair<String, String>, Timer.Sample>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is ActionExecutionStartEvent -> {
                val key = event.processId to event.action.name
                activeTimers[key] = Timer.start(meterRegistry)
            }
            is ActionExecutionResultEvent -> {
                val key = event.processId to event.action.name
                activeTimers.remove(key)?.stop(
                    meterRegistry.timer("embabel.action.duration",
                        "action", event.action.name,
                        "status", event.actionStatus.status.name,
                    )
                )
            }
            is ToolCallResponseEvent -> {
                meterRegistry.timer("embabel.tool.duration", "tool", event.request.tool)
                    .record(event.runningTime)
            }
            is ReplanRequestedEvent -> {
                meterRegistry.counter("embabel.replan", "reason", event.reason).increment()
            }
            else -> {}
        }
    }
}
```

`ProcessOptions.withListener(actionMetricsListener)` 로 부착 → `/actuator/metrics/embabel.action.duration` 에서 액션별 latency·실패율 가시.

### 9.4 OpenTelemetry — replan 사이클을 span tree 로

> 🔮 **개념 — 본 프로젝트 미구현**

각 액션·툴·LLM 호출을 OTel span 으로 매핑하면 분산 트레이싱이 자연스럽습니다:

```kotlin
class OtelTracingListener(private val tracer: Tracer) : AgenticEventListener {
    private val spans = ConcurrentHashMap<String, Span>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is ActionExecutionStartEvent -> {
                val span = tracer.spanBuilder("action.${event.action.name}")
                    .setAttribute("agent.process.id", event.processId)
                    .startSpan()
                spans["${event.processId}-${event.action.name}"] = span
            }
            is ActionExecutionResultEvent -> {
                spans.remove("${event.processId}-${event.action.name}")?.let {
                    it.setAttribute("status", event.actionStatus.status.name)
                    it.end()
                }
            }
            is LlmRequestEvent<*> -> {
                tracer.spanBuilder("llm.${event.request.llmMetadata.modelName}")
                    .setAttribute("output.class", event.outputClass.simpleName)
                    .startSpan()
                    .also { /* ... */ }
            }
            is ReplanRequestedEvent -> {
                Span.current().addEvent("replan", Attributes.of(stringKey("reason"), event.reason))
            }
            else -> {}
        }
    }
}
```

`AgentProcess.parentId` 를 활용하면 서브에이전트 호출도 *부모 span* 의 자식으로 자연스럽게 이어집니다.

### 9.5 `ProcessControl` 로 클라이언트 disconnect 처리

> 🔮 **개념 — 본 프로젝트 미구현**

WebFlux 클라이언트가 SSE 연결을 끊으면 `channelFlow` 의 `awaitClose` 가 트리거됩니다. 이때 `agentJob.cancel()` 만으론 *이미 시작된 LLM 호출* 이 끝까지 돌고 비용이 발생. 해결:

```kotlin
override fun streamDeepResearch(topic: ResearchTopic): Flow<ResearchEvent> = channelFlow {
    // ... 기존 sink, listener 설정 ...

    val agentJob = launch(Dispatchers.IO) {
        val invocation = AgentInvocation.builder(agentPlatform)
            .options(ProcessOptions().withListener(combined))
            .build(AgentDeepResearchReport::class.java)
        invocation.invoke(UserInput(topic.value))
        // ...
    }

    awaitClose {
        // 클라이언트 disconnect 시 process 도 즉시 종료
        AgentProcess.get()?.kill()                                    // ProcessKilledEvent 발행
        agentJob.cancel()
    }
}
```

`processControl.earlyTerminationPolicy` 에 *클라이언트 연결 상태* 를 합치면 budget 검사 시점마다 종료 결정.

---

## 10. 다른 프레임워크와의 차별점

### 10.1 한눈에 비교

| 축 | LangChain (Py) | LangGraph (Py) | CrewAI (Py) | OpenAI Assistants | **Embabel (JVM)** |
|----|----------------|----------------|-------------|--------------------|-------------------|
| **언어/런타임** | Python | Python | Python | API-only | **Kotlin / Java** |
| **Plan 생성** | LLM ReAct | **사람이 그래프 작성** | role/task 선언 | LLM 자체 | **GOAP A\*** |
| **상태 표현** | `dict` | `TypedDict + Channel` | pydantic | 메시지 배열 | **도메인 `data class`** |
| **타입 안전** | ❌ 런타임 | ⚠️ TypedDict | ⚠️ pydantic | ❌ JSON | ✅ **컴파일** |
| **재계획** | 외부 코드 | 그래프 사이클 수동 | 제한적 | 자동 (블랙박스) | **OODA 자동** |
| **토큰 스트리밍** | 일급 | 일급 | 제한적 | 일급 | ❌ 객체 단위 (§11) |
| **상태 영속화** | 외부 LangSmith | Checkpointer 내장 | (부재) | thread API | (직접) |
| **DI / 트랜잭션** | (수동) | (수동) | (수동) | — | **Spring 일급** |
| **러닝커브** | 낮음 | 중간 | 낮음 | 매우 낮음 | **중간** |

### 10.2 항목별 — 핵심 3가지

#### (a) 타입 안전성

**LangChain (Python)**
```python
state["draft"].append(section)            # 's' 빠진 키 오타 → 런타임 KeyError
section.sourcs                             # 'e' 빠진 → AttributeError
```

**Embabel (Kotlin)**
```kotlin
section.sourcs                             // ← 컴파일 에러 즉시
```

도메인의 `init { require(...) }` 까지 결합하면 LLM 잘못된 출력이 **객체 생성 시점에 거부** 됩니다. LangChain 에선 같은 검증·retry 로직을 직접 구현해야 함.

#### (b) Spring 통합 — JVM 엔터프라이즈

| 필요한 것 | LangChain/LangGraph | Embabel |
|-----------|----------------------|---------|
| 인증 | FastAPI dep 직접 | `@PreAuthorize` |
| Tracing/Metrics | OTel 수동 wiring | Spring Boot Actuator + Micrometer 자동 |
| DB 트랜잭션 | SQLAlchemy 직접 | `@Transactional` |
| 툴 의존성 주입 | 글로벌 변수/factory | `@Component` + 생성자 주입 |
| Rate limiter / Circuit breaker | 직접 | Resilience4j 어노테이션 |

본 프로젝트의 `ResearchTools` 가 좋은 예 — `@Component`, `@Value` 로 외부 설정 주입, `WebClient` 가 connection pool / timeout 관리, 캐시 추가는 `@Cacheable` 한 줄.

#### (c) 도메인 객체 = 1급 시민

LangChain 의 메시지/체인 모델은 본질적으로 **문자열 + 체인**. 도메인 모델은 LLM 과 *직교적* 으로 존재.

Embabel 은 **도메인 모델이 LLM 흐름의 노드 그 자체** (§2.1).
- IDE *Find Usages* 가 *"이 데이터 클래스를 만드는 LLM 액션"* 을 찾아줌
- 도메인 리팩토링 = LLM 흐름 리팩토링

### 10.3 정직한 약점

| 약점 | 설명 |
|------|------|
| **커뮤니티 크기** | LangChain (수만 stars) 대비 Embabel 은 수백 stars. 예제·튜토리얼 적음 |
| **LLM 어댑터 폭** | OpenAI / Spring AI 중심. Anthropic native, Bedrock 등은 우회 필요 |
| **Python 신기능 지연** | streaming response 변형, vision, files API 등 늦게 들어옴 |
| **토큰 단위 스트리밍** | §11 — 챗봇 UX 가 핵심이면 부적합 |
| **Notebook 친화성** | Jupyter 빠른 실험은 Python 압도 |
| **러닝커브** | GOAP·어노테이션 plan 합성을 익혀야 함 |
| **벤더 의존도** | Embabel Pty Ltd 주도. Apache 2.0 fork 가능하지만 |

### 10.4 도구 선택 가이드

- **빠른 프로토타입 / Jupyter**: LangChain
- **명시적 그래프 (사이클·조건 많음)**: LangGraph
- **멀티 에이전트 협상**: CrewAI / AutoGen
- **간단한 챗봇 / 토큰 스트리밍 UX**: Claude API / OpenAI Assistants 직접
- **JVM 엔터프라이즈 서버 에이전트**: **Embabel + Spring AI** — 본 프로젝트
- **데이터 파이프라인**: Prefect / ZenML / Airflow + LLM 노드

---

## 11. 한계 — 토큰 단위 스트리밍 (트레이드오프)

본 프로젝트의 SSE 이벤트는 **액션/툴 단위(coarse-grained)** 입니다. Claude Code, ChatGPT 웹UI 처럼 *한 글자씩 타이핑되는 효과* 는 자연스럽게 나오지 않습니다.

### 11.1 무엇이 스트리밍되고, 무엇이 안 되는가

| 항목 | 본 프로젝트 (Embabel `create<T>()`) | Claude Code 식 토큰 스트리밍 |
|------|--------------------------------------|------------------------------|
| Action 시작/종료 | ✅ `action-started`, `action-completed` | (해당 없음) |
| 툴 호출/응답 | ✅ `tool-invoked`, `tool-returned` | 보통 노출 안 됨 |
| Plan 마일스톤 | ✅ `plan-formulated`, `section-drafted` | (해당 없음) |
| **섹션 본문 토큰** | ❌ **섹션 1개 통째로 완성된 뒤** `section-drafted` 1번 | ✅ `delta` 이벤트로 한 글자씩 |
| 최종 보고서 | ✅ 한 번에 `deep-research-completed` | ✅ 누적 토큰의 마지막 |

> **체감**: gatherSections 5개 섹션 × ~10–15초 = **약 50–75초 동안 본문 화면에 안 나옴**. 그 사이 사용자가 보는 건 `tool-invoked` / `tool-returned` 뿐.

### 11.2 왜 그런가 — `create<T>()` 시그니처

`create<T>()` 는 **객체 단위 동기 호출**. 내부에서 일어나는 일:

1. LLM 에 프롬프트 송신 (structured output / JSON mode)
2. LLM 토큰 생성 (스트리밍이지만 어댑터가 모아서 받음)
3. 중간 tool call → 툴 실행 → 결과 주입 → LLM 재개
4. **최종 토큰까지 모인 후** JSON 파싱 → `T` 객체 생성
5. `init { require(...) }` 검증 → 실패 시 retry
6. 검증 통과 객체 반환

토큰을 외부로 흘리려면 **2번 시점의 훅** 이 필요한데, `AgenticEventListener` 의 콜백은 *객체/액션 경계* 에서만 발생.

### 11.3 두 가지 핵심 이유

#### (1) Structured output = 닫힌 JSON 가정

JSON 은 본질적으로 **닫는 괄호가 와야 valid** 라 *부분 파싱* 이 어렵습니다. 스트리밍 JSON 파서로 *끝나지 않은 필드도* 토큰별로 흘릴 순 있지만 **타입 안전한 객체 매핑** 과 양립이 어렵습니다.

#### (2) Tool loop 은 비선형

```
LLM: "프랑스의 2025년 GDP는" → [tool_call: tavily_search]
                                   ▼ (툴 실행 1.2초)
LLM:                          ◄── tool_result
LLM: "프랑스의 2025년 GDP는 0.7% 성장하여 [https://...]"
```

토큰을 그대로 흘리면: `"프랑스의 2025년 GDP는"` 표시 → **1.2초 멈춤** → 같은 문장의 다른 버전 이어짐 (LLM 자체 수정도 발생) → UX 비일관.

(추가 이유 2가지 — *도메인 검증이 전체 객체 단위*, *plan 자체가 멀티-단계* — 는 위 둘의 따름정리.)

### 11.4 트레이드오프 — "구조 vs 즉시성"

| 축 | Embabel `create<T>()` | 토큰 스트리밍 |
|----|----------------------|----------------|
| 타입 안전성 | ★★★ | ★ |
| 도메인 검증 | ★★★ | ★ |
| 부분 실패 처리 | ★★★ | ★ |
| **체감 지연** | ★ — 섹션 1개 완성까지 침묵 | ★★★ — 즉시 첫 토큰 |
| **읽기 시작 시점** | ★ 50–75초 후 | ★★★ <1초 |
| 진행 가시성 | ★★ 마일스톤 이벤트 | ★★★ 진행 중 본문 |

### 11.5 워크어라운드 (요약)

- **A. 현재 구조 유지 + UX 보강** (권장) — 마일스톤 풍부하게, 섹션이 도착하면 1개씩 페이드인. 코드 변경 거의 없음.
- B. 섹션 본문에 한해 Spring AI `ChatClient.stream()` 직접 호출 — 본문은 진짜 토큰. Embabel tool injection / 도메인 검증 일부 잃음.
- C. 하이브리드 — 토큰 흘리면서 사후 파싱. tool-call 시점 흐름 깨끗하지 않음.
- D. 다른 프레임워크 — GOAP·타입 안전성 모두 잃음.

### 11.6 권고

> **"Deep Research 스타일 보고서" 도메인에서는 토큰 스트리밍의 가치가 생각보다 작습니다.** 사용자가 읽기 시작하는 시점은 *섹션이 완성된 후* 가 자연스럽고, 인용 링크가 함께 도착해야 신뢰가 생깁니다. 부분 토큰만 보면 *"검증 불가능한 문장"* 을 사용자가 먼저 읽는 위험.

따라서 **현 구조 + 옵션 A** 가 비용 대비 가장 합리적. "Claude Code 같은 타이핑" 이 핵심이라면 도메인이 *대화/코드 생성* 쪽이지 *연구 보고서* 가 아닐 가능성.

---

## 12. TL;DR

- Embabel = **GOAP 플래너 + 타입 안전 도메인 모델** 위에 LLM 을 얹은 JVM 에이전트 프레임워크. 클라이언트 도구(Claude Code, Cursor — TS) / 연구 노트북(LangChain — Python) 과는 *다른 카테고리* — **엔터프라이즈 서버 에이전트** 가 자리.
- **런타임 어휘**: `Blackboard` (공유 상태) → `OperationContext` (`@Action` 에 주입, `ai().create<T>()`) → `AgentProcess` (실행 인스턴스) → `ProcessOptions` (실행 설정: budget, listeners, control).
- **`/api/deep-research/stream`** = `planSubtopics → gatherSections → frameReport → assembleReport` 의 4단계 GOAP plan 을 SSE 로 실시간 중계.
- **핵심 트릭**: *"합성은 LLM, 결합은 코드"* + *"두 리스너로 저수준/고수준 이벤트 분리"* + *"도메인 검증이 1차 게이트키퍼"* + *"`assembleReport` 는 `FIRE_ONCE`"*.
- **이벤트 23종**, 본 프로젝트는 5종만 사용. 미사용 이벤트(`LlmRequest/Response`, `ToolLoopStart/Completed`, `ProgressUpdate`, `ProcessKilled`) 는 관측성·UX·취소 처리에 활용 가능.
- **오류 처리는 5층**: Spring Retry → Action Retry → 도메인 검증 → GOAP Replan → 명시적 `ReplanRequestedException`. 본 프로젝트는 `assembleReport` 에 `actionRetryPolicy = FIRE_ONCE` 적용.
- **테스트는 `FakeOperationContext`** (이미 사용 중). 관측성·서브에이전트·OTel 통합은 라이브러리에 있지만 본 프로젝트 미적용 — §8/§9 의 가이드 참고.
- **핵심 한계**: 토큰 단위 스트리밍 불가 — `create<T>()` 가 객체 단위 동기 호출이고, structured output / tool loop / 도메인 검증이 모두 *완성된 객체* 를 전제하기 때문.

---

## 부록 A — GOAP A\* 플래너 라이브러리 내부

> Embabel *을 쓰는* 사람에겐 필수 아닙니다. *디버그·포크·확장* 시 참고.

플래너는 외부 의존성 없이 순수 Kotlin, 패키지 `com.embabel.plan.*` 에 격리:

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

### A.1 핵심 추상화 — `Plan.kt`

```kotlin
typealias CostComputation = (state: WorldState) -> ZeroToOne

interface Step : Named, HasInfoString {
    override val name: String
    val value: CostComputation     // 0..1
}

interface Action : Step {
    val cost: CostComputation       // 0..1, 1이 가장 비쌈
    fun netValue(state: WorldState): Double = value(state) - cost(state)
}

interface Goal : Step

open class Plan(val actions: List<Action>, val goal: Goal) : HasInfoString {
    fun isComplete() = actions.isEmpty()
    fun cost(state: WorldState): Double = actions.sumOf { it.cost(state) }
    fun netValue(state: WorldState): Double =
        goal.value(state) + actionsValue(state) - cost(state)
}
```

### A.2 월드 상태 — 3-값 논리

```kotlin
typealias ConditionState = Map<String, ConditionDetermination>   // TRUE / FALSE / UNKNOWN

interface ConditionWorldState : WorldState {
    val state: ConditionState
    operator fun plus(action: ConditionAction): ConditionWorldState   // effects 적용
    fun unknownConditions(): Collection<String>
}
```

### A.3 액션 — preconditions + effects

```kotlin
interface ConditionAction : ConditionStep, Action {
    val preconditions: EffectSpec
    val effects: EffectSpec
}
```

> Embabel 이 `@Action fun gatherSections(plan: AgentResearchPlan, ...)` 시그니처를 보고:
> - precondition = `"AgentResearchPlan_present" → TRUE`
> - effect = `"AgentSectionDrafts_present" → TRUE`
> 식으로 자동 변환.

### A.4 A\* 본체

```kotlin
internal class AStarGoapPlanner(...) : OptimizingGoapPlanner(...) {

    override fun planToGoalFrom(
        startState: ConditionWorldState,
        actions: Collection<ConditionAction>,
        goal: ConditionGoal,
    ): ConditionPlan? {
        // 0) 빠른 판정
        if (goal.isAchievable(startState)) return ConditionPlan(emptyList(), goal, ...)
        if (!isGoalReachable(startState, actions, goal)) return null

        // 1) 자료구조
        val openList   = PriorityQueue<SearchNode>()
        val gScores    = mutableMapOf<...>().withDefault { Double.MAX_VALUE }
        val cameFrom   = mutableMapOf<상태, Pair<이전상태, 액션?>>()
        val closedSet  = mutableSetOf<ConditionWorldState>()

        // 2) 시작
        gScores[startState] = 0.0
        openList.add(SearchNode(startState, gScore = 0.0, hScore = heuristic(startState, goal)))

        var bestGoalNode: SearchNode? = null
        var bestGoalScore = Double.MAX_VALUE
        val maxIterations = 10000

        // 3) 메인 루프
        while (openList.isNotEmpty() && iterationCount < maxIterations) {
            val current = openList.poll()
            if (current.state in closedSet) continue
            closedSet.add(current.state)

            if (goal.isAchievable(current.state)) {
                if (current.gScore < bestGoalScore) {
                    bestGoalNode = current; bestGoalScore = current.gScore
                }
                continue
            }

            val sortedActions = actions.sortedByDescending { it.preconditions.size }
            for (action in sortedActions) {
                if (!action.isAchievable(current.state)) continue
                val nextState = current.state + action
                if (nextState == current.state) continue

                val tentativeGScore = gScores.getValue(current.state) + action.cost(startState)
                if (tentativeGScore < gScores.getValue(nextState)) {
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

#### Heuristic — admissible

```kotlin
private fun heuristic(state: ConditionWorldState, goal: ConditionGoal): Double =
    goal.preconditions.count { (key, value) -> state.state[key] != value }.toDouble()
```

미충족 조건 *개수* 를 그대로 반환 → A\* 의 최적성 보장 (한 액션이 한 조건만 바꾼다는 가정 하에 *최소 N개 액션 필요*).

#### 빠른 unreachability 체크

```kotlin
private fun isGoalReachable(startState, actions, goal): Boolean {
    val producibleEffects = mutableSetOf<Pair<String, ConditionDetermination>>()
    for (action in actions) for ((k, v) in action.effects) producibleEffects.add(k to v)

    for ((key, value) in goal.preconditions) {
        if (startState.state[key] == value) continue
        if ((key to value) !in producibleEffects) return false           // 누구도 못 만듦
    }
    return true
}
```

### A.5 두 단계 최적화

#### Backward — "골에 기여하는 액션만"

```kotlin
private fun backwardPlanningOptimization(plan, startState, goal): List<ConditionAction> {
    val targetConditions = goal.preconditions.toMutableMap()
    val keptActions = mutableListOf<ConditionAction>()

    for (action in plan.reversed()) {
        var isNecessary = false
        for ((key, value) in action.effects) {
            if (targetConditions[key] == value) {
                isNecessary = true
                targetConditions.remove(key)
                action.preconditions.forEach { (k, v) -> targetConditions[k] = v }
            }
        }
        if (isNecessary) keptActions.add(action)
    }
    return keptActions.reversed()
}
```

#### Forward — "실제로 진전을 만드는 액션만"

```kotlin
val progressMade = nextState != currentState &&
    action.effects.any { (key, value) ->
        goal.preconditions.containsKey(key) &&
            currentState.state[key] != goal.preconditions[key] &&
            (value == goal.preconditions[key] || key !in nextState.state)
    }
```

너무 공격적으로 잘라서 골을 못 이루면 원본으로 롤백:

```kotlin
val finalState = simulatePlan(startState, optimizedPlan)
if (!goal.isAchievable(finalState) && plan.isNotEmpty()) return plan
```

### A.6 Planner 인터페이스

```kotlin
interface Planner<S : PlanningSystem, W : WorldState, P : Plan> {
    fun worldState(): W
    fun planToGoal(actions: Collection<Action>, goal: Goal): P?

    fun plansToGoals(system: PlanningSystem): List<P> {
        val state = worldState()
        return system.goals
            .mapNotNull { goal -> planToGoal(system.actions, goal) }
            .sortedByDescending { p -> p.netValue(state = state) }
    }

    fun bestValuePlanToAnyGoal(system: PlanningSystem): P? =
        plansToGoals(system).firstOrNull()

    fun bestValuePlanToAnyGoal(system: PlanningSystem, excludedActionNames: Set<String>): P?

    fun prune(planningSystem: S): S
}
```

---

## 부록 B — 향후 확장 패턴 (Tavily fallback, Subagent 분리)

> 🔮 **모두 본 프로젝트 미구현** — 라이브러리 사용법 시연용 의사 코드. 적용 시 빌드·테스트 검증 필요.

### B.1 Tavily 실패 시 GOAP fallback

목표: Tavily API 가 *없거나 빈 결과* 일 때 자동으로 *오프라인 모드* 액션으로 우회.

```kotlin
// 1) 시그널 타입 — GOAP precondition 으로 작동
data class SearchUnavailable(val reason: String)

// 2) tavily_search 가 빈 결과를 반복하면 SearchUnavailable 시그널
//    (ReplanningToolFactory.replanWhen 패턴, §7.7)
val smartSearch = ReplanningToolFactory.replanWhen<List<TavilyResult>>(rawSearch) {
    results -> results.isEmpty()
}

// 또는 가드 액션
@Action
fun checkSearchHealth(context: OperationContext): SearchUnavailable? {
    return runCatching { researchTools.tavilySearch("ping", maxResults = 1) }
        .fold(
            onSuccess = { if (it.isEmpty()) SearchUnavailable("empty results") else null },
            onFailure = { SearchUnavailable(it.message ?: "tavily error") },
        )
}

// 3) SearchUnavailable 이 월드에 있을 때만 발동하는 대안 gather
@Action
fun gatherSectionsOffline(
    plan: AgentResearchPlan,
    @Suppress("UNUSED_PARAMETER") signal: SearchUnavailable,            // ← precondition
    context: OperationContext,
): AgentSectionDrafts {
    val drafts = plan.subtopics.map { subtopic ->
        context.ai().withLlm(...).create<AgentSection>(
            "Draft a section on '${subtopic.title}' using only your prior knowledge. " +
            "Mark unverified claims with [unverified]. No web access available."
        )
    }
    return AgentSectionDrafts(drafts)
}
```

GOAP 플래너의 자동 합성 결과:

```
정상 경로: planSubtopics → gatherSections      → frameReport → assembleReport
대안 경로: planSubtopics → gatherSectionsOffline → frameReport → assembleReport
                          ↑ precondition: SearchUnavailable 객체 존재
```

**그래프 코드 한 줄 안 고치고**, 액션 메서드 한 개 추가만으로 fallback 작동.

### B.2 섹션을 Subagent 로 분리 + 병렬화

§8.4 의 가설을 코드 형태로:

```kotlin
data class SectionRequest(val mainTopic: String, val subtopic: AgentResearchSubtopic)

@Agent(description = "Research a single subtopic with web grounding")
class SectionResearchAgent(private val tools: ResearchTools) {

    @AchievesGoal(description = "A single section drafted")
    @Action
    fun draftSection(input: SectionRequest, context: OperationContext): AgentSection =
        context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
            .withToolObject(tools)
            .create<AgentSection>("""
                Draft ONE section of a report on '${input.mainTopic}'.
                Subtopic: '${input.subtopic.title}'.
                ... (gatherSections 의 동일 프롬프트)
            """.trimIndent())
}

// DeepResearchAgent.gatherSections 를 다음으로 교체
@Action
fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts {
    val drafts = context.parallelMap(plan.subtopics, maxConcurrency = 3) { subtopic ->
        context.fireAgent(
            SectionRequest(plan.mainTopic, subtopic),
            AgentSection::class.java,
        )?.get() ?: error("section research failed for ${subtopic.title}")
    }
    return AgentSectionDrafts(drafts)
}
```

**얻는 것** (§8.4 재인용):
- 각 subtopic 독립 budget·재시도 격리
- 5 섹션 직렬 75초 → 병렬 ~25초 (`maxConcurrency = 3`)
- 분산 트레이싱이 자식 프로세스를 별도 span tree 로 분리
- `SectionResearchAgent` 를 단일 섹션 endpoint 등 다른 컨텍스트에서 재사용

**잃는 것**: 추가 클래스 비용, 자식 프로세스 생성 오버헤드 (수십 ms).
