# Embabel (엠베이블)

> Spring Framework 창시자 **Rod Johnson**이 만든 **JVM 기반 AI 에이전트 프레임워크**.
> Kotlin으로 작성되었지만 Java에서도 그대로 사용 가능하며, Spring Boot 위에서 동작합니다.

---

## 목차

1. [핵심 컨셉](#1-핵심-컨셉)
2. [4가지 빌딩 블록 (GOAP)](#2-4가지-빌딩-블록-goap)
3. [실행 모델 (OODA 루프)](#3-실행-모델-ooda-루프)
4. [GOAP 플래너 알고리즘 — Embabel 라이브러리 실제 코드](#4-goap-플래너-알고리즘--embabel-라이브러리-실제-코드)
5. [다른 프레임워크와의 차별점](#5-다른-프레임워크와의-차별점)
6. [`POST /api/deep-research/stream` 코드 레벨 워크스루](#6-post-apideep-researchstream-코드-레벨-워크스루)
7. [오류 처리 / 재계획 / Fallback 메커니즘](#7-오류-처리--재계획--fallback-메커니즘)
8. [추가로 알아두면 좋은 패턴](#8-추가로-알아두면-좋은-패턴)
9. [한계 — 토큰 단위 스트리밍은 왜 어려운가 (트레이드오프)](#9-한계--토큰-단위-스트리밍은-왜-어려운가-트레이드오프)

---

## 1. 핵심 컨셉

가장 차별화된 부분은 **GOAP (Goal-Oriented Action Planning)** 입니다.
원래 게임 AI에서 쓰던 알고리즘으로, **LLM에만 의존하지 않고** 결정론적(deterministic) 플래닝 알고리즘으로 최적의 행동 순서를 찾아냅니다.

> 한 줄 요약: **"플래닝은 LLM이 잘하는 일이 아니다."**

LLM은 *각 단계를 잘 수행*하지만, *어떤 순서로 단계를 밟을지 결정*하는 일은 비결정적이고 비싸며 디버깅이 어렵습니다.
Embabel은 이 두 책임을 분리합니다.

| 책임 | 누가 담당 |
|------|-----------|
| **무엇을 / 어떤 순서로** 할지 결정 | GOAP 플래너 (결정론적, 코드) |
| **각 단계를 어떻게 수행**할지 | LLM (`@Action` 함수 안) |

---

## 2. 4가지 빌딩 블록 (GOAP)

| 요소 | 의미 | 코드에서의 모습 |
|------|------|------------------|
| **Actions** | 에이전트가 수행하는 단계 | `@Action fun planSubtopics(...)` |
| **Goals** | 달성하려는 종착 상태 | `@AchievesGoal` 가 붙은 `@Action` |
| **Conditions** | 액션 실행 전/목표 달성 판단 조건 | 입력 타입 매칭 (e.g. `AgentResearchPlan`이 월드에 존재해야 `gatherSections` 가능) |
| **Domain Model** | Kotlin `data class` / Java `record` 로 만든 타입 | `AgentResearchPlan`, `AgentSection`, ... |

### 왜 도메인 모델이 중요한가
- **타입 안전한 프롬프트** — LLM 출력이 `data class` 로 파싱되며, 컴파일러가 필드 누락을 잡습니다.
- **툴링 가능** — IDE 리팩토링/자동완성이 그대로 동작.
- **Plan은 시스템이 동적으로 생성** — 프로그래머가 직접 작성하지 않습니다. 플래너가 *"`AgentDeepResearchReport`를 만들려면 → `AgentReportFraming`이 필요 → 이건 `AgentSectionDrafts` + `AgentResearchPlan`에서 나옴 → ..."* 식으로 역추론하여 plan을 합성합니다.

---

## 3. 실행 모델 (OODA 루프)

각 액션이 끝날 때마다 **재계획(replan)** 하면서 새로운 정보에 적응합니다.

```
 ┌─────────► Observe ──► Orient ──► Decide ──► Act ──┐
 │     이전 액션 결과    도메인 모델     다음 액션      액션 실행 →
 │     도구 호출 응답    & 컨디션에      재선택        새 결과
 │     외부 상태 변화    비추어 해석     (replan)      (다시 Observe)
 └────────────────────────────────────────────────────┘
```

- **Observe** — 이전 액션의 결과, 툴 호출 응답, 외부 상태 변화를 수집.
- **Orient** — 도메인 모델과 현재 컨디션에 비추어 상황을 해석. (Embabel의 경우 GOAP 플래너가 새 사실을 월드 상태에 반영)
- **Decide** — 다음에 어떤 액션을 실행할지 재계획.
- **Act** — 액션 실행 → 결과가 다시 Observe로.

---

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

### 4.7 본 프로젝트에 대입해보기 — `/api/deep-research/stream` 의 plan은 어떻게 발견되는가?

본 프로젝트의 `DeepResearchAgent`는 4개의 `@Action` 을 선언합니다. Embabel은 이를 다음 GOAP 표현으로 자동 변환합니다 (개념도):

```
Action: planSubtopics
   pre  = { "UserInput" : TRUE }
   post = { "AgentResearchPlan" : TRUE }
   cost = 0.0  value = 0.0

Action: gatherSections
   pre  = { "AgentResearchPlan" : TRUE }
   post = { "AgentSectionDrafts" : TRUE }

Action: frameReport
   pre  = { "AgentResearchPlan" : TRUE, "AgentSectionDrafts" : TRUE }
   post = { "AgentReportFraming" : TRUE }

Action: assembleReport            ← @AchievesGoal
   pre  = { "AgentResearchPlan" : TRUE, "AgentSectionDrafts" : TRUE, "AgentReportFraming" : TRUE }
   post = { "AgentDeepResearchReport" : TRUE }

Goal:   produceDeepResearchReport
   preconditions = { "AgentDeepResearchReport" : TRUE }
```

**A*가 찾아내는 과정 (상태 전이)**:

| 반복 | 현재 상태 (TRUE만 표시)                                 | h | 적용 가능 액션         | 적용 결과 |
|------|--------------------------------------------------------|---|------------------------|-----------|
| 0    | `{UserInput}`                                          | 1 | `planSubtopics`        | 다음 상태로 |
| 1    | `{UserInput, AgentResearchPlan}`                       | 1 | `gatherSections`       | 다음 상태로 |
| 2    | `{... , AgentSectionDrafts}`                           | 1 | `frameReport`          | 다음 상태로 |
| 3    | `{... , AgentReportFraming}`                           | 1 | `assembleReport`       | 다음 상태로 |
| 4    | `{... , AgentDeepResearchReport}` ✓                    | 0 | (goal achieved)        | — |

- `frameReport` 의 preconditions는 2개라 `gatherSections` 보다 먼저 시도되지만(`sortedByDescending {...preconditions.size}`), `AgentSectionDrafts` 가 없으니 `isAchievable` 에서 탈락.
- `gatherSections` 도 `AgentResearchPlan` 이 없으면 못 씀 → `planSubtopics` 가 자연스레 첫 액션으로 결정.
- 코드에 *순서*를 적은 곳은 어디에도 없습니다. **타입 시그니처가 곧 plan** 입니다.

> **확장 시나리오**: 만약 `webSearch(plan)` 을 만들어 `post = "WebResults : TRUE"` 를 선언하고, `gatherSections` 의 precondition에 `"WebResults : TRUE"` 를 추가하면? → 다음 빌드에 자동으로 `planSubtopics → webSearch → gatherSections → frameReport → assembleReport` plan이 합성됩니다. **상태 머신을 다시 짤 필요 없음** = Embabel이 광고하는 "확장성"의 정체.

---

## 5. 다른 프레임워크와의 차별점

### 5.1 한눈에 비교

| 축 | LangChain (Py) | LangGraph (Py) | CrewAI (Py) | OpenAI Assistants | **Embabel (JVM)** |
|----|----------------|----------------|-------------|--------------------|-------------------|
| **언어/런타임** | Python | Python | Python | API-only | **Kotlin / Java** |
| **Plan 생성 방식** | LLM ReAct 루프 | **사람이 그래프 작성** | role/task 선언 | LLM 자체 | **GOAP A\* 플래너** |
| **상태 표현** | `dict` | `TypedDict + Channel` | pydantic 모델 | 메시지 배열 | **도메인 `data class`** |
| **타입 안전** | ❌ 런타임 | ⚠️ TypedDict (선택) | ⚠️ pydantic | ❌ JSON | ✅ **컴파일 타임** |
| **툴 정의** | `@tool` 함수 | 동일 | 동일 | JSON spec | `@LlmTool` 메서드 |
| **재계획 (replan)** | 외부 코드 | 그래프 사이클 수동 | 제한적 | 자동 (블랙박스) | **OODA 자동** |
| **토큰 스트리밍** | 일급 | 일급 | 제한적 | 일급 | ❌ 객체 단위 (§8) |
| **상태 영속화** | 외부 LangSmith 등 | Checkpointer 내장 | (부재) | thread API | (직접) |
| **DI / 트랜잭션** | (수동) | (수동) | (수동) | 해당 없음 | **Spring 일급** |
| **러닝커브** | 낮음 | 중간 | 낮음 | 매우 낮음 | **중간 (GOAP 이해 필요)** |
| **코드베이스 크기** | 100K+ LoC | 중간 | 작음 | (서비스) | 작음 (~5K LoC core) |

> 같은 *Deep Research* 흐름을 4개 도구로 구현했을 때의 차이는 다음 항목별 디테일에서 다룹니다.

---

### 5.2 항목별 디테일

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

### 5.3 정직한 약점 (Embabel 이 *지는* 부분)

객관성을 위해:

| 약점 | 설명 |
|------|------|
| **커뮤니티 크기** | LangChain (수만 stars) 대비 Embabel 은 수백 stars. 예제·튜토리얼·StackOverflow 답변 적음 |
| **LLM 어댑터 폭** | OpenAI / Spring AI 중심. Anthropic native, Cohere, Bedrock 등은 `openai-custom` 게이트웨이 우회 필요 |
| **Python 신기능 지연** | streaming response 변형, vision multi-modal, files API 등이 Python 진영보다 늦게 들어옴 |
| **토큰 단위 스트리밍 약함** | §8 에서 자세히. 챗봇 UX 가 핵심이면 부적합 |
| **Notebook 친화성** | Jupyter / IPython 빠른 실험에는 Python 이 압도적. JVM 의 Kotlin notebook 은 사용자층이 작음 |
| **러닝커브** | GOAP 모델, 어노테이션 기반 plan 합성을 익혀야 함. "그냥 LLM 호출" 하기엔 오버킬 |
| **벤더 의존도** | Embabel Pty Ltd 가 주도. 만약 회사가 사라지면? (Apache 2.0 라이선스라 fork 는 가능) |

---

### 5.4 언제 *다른* 도구를 골라야 하나

- **빠른 프로토타입 / Jupyter 분석**: LangChain. 5분 안에 RAG 파이프라인 띄움.
- **명시적 그래프 워크플로 (사이클·조건분기 많음)**: LangGraph. checkpointer 로 시점 복원 강력.
- **멀티 에이전트 협상·대화**: CrewAI / AutoGen. role 별 페르소나가 협상하는 시나리오.
- **간단한 챗봇 / 토큰 스트리밍 UX**: Claude API / OpenAI Assistants 직접 — 프레임워크 오버헤드 없음.
- **데이터 파이프라인 통합**: Prefect / ZenML / Airflow + LLM 노드.
- **순수 함수 LLM 변환 한 번**: 그냥 `chatClient.prompt(...).call()` 한 줄로 끝.

---

### 5.5 Embabel 이 *진가를 발휘하는* 도메인

- **JVM 엔터프라이즈 시스템 안에서 동작하는 에이전트** — 기존 Spring/JPA/보안 인프라를 그대로 쓰면서 LLM 워크플로를 얹을 때.
- **풍부한 도메인 모델이 이미 있는 코드베이스** — 도메인 객체를 LLM 노드로 *그대로* 활용 가능.
- **결정론·재현성·감사가 중요한 환경** — 금융, 의료, 공공 등 *"같은 입력엔 같은 plan"* 을 증명해야 하는 분야.
- **상태 머신/플로우차트로 표현하기 어려운 다목표 에이전트** — 액션이 많아질수록 그래프 도구는 폭발하지만, GOAP 는 자동 정렬.
- **장기 유지보수가 필요한 시스템** — 타입 안전성·리팩토링 안정성·IDE 지원이 누적 효과.

> **요약**: *"빠르게 LLM 호출 한두 번"* 의 영역은 Python 프레임워크가 더 효율적입니다.
> Embabel 의 가치는 **에이전트가 시스템의 영속적 구성요소**가 될 때 — 액션이 늘어나고, 도메인 검증이 강해지고, 다른 팀의 코드와 합쳐질수록 — 비로소 드러납니다.

---

### 5.6 언어/런타임 포지셔닝 — 왜 JVM, 왜 Python, 왜 TypeScript?

AI 도구들이 *서로 다른 언어를 쓰는 이유* 를 알면 Embabel(JVM) 의 자리가 또렷해집니다.

```
                ┌─────────────────────────── 에이전트가 사는 곳 ───────────────────────────┐
                │                                                                         │
   클라이언트 / CLI / IDE          서버 / 백엔드 / 백오피스 워크플로            데이터 사이언스 / 노트북
   ───────────────────────         ─────────────────────────────────             ─────────────────────
   Claude Code, OpenCode,          Spring Boot, Node, Rails, Django              Jupyter, Colab, ML pipelines
   Cursor, Continue.dev            엔터프라이즈 시스템                            연구·분석
        │                                  │                                            │
        ▼                                  ▼                                            ▼
   TypeScript                        JVM (Embabel)                                   Python
   (혹은 Rust)                       또는 Node                                       (LangChain, LangGraph)
```

#### (a) 왜 Claude Code / OpenCode 같은 *코딩 에이전트*는 TypeScript / Rust 인가

**클라이언트 측 도구이기 때문**입니다. 서버에 배포되는 것이 아니라 *사용자 머신* 에서 실행됩니다:

| 요구사항 | 결과 |
|----------|------|
| **단일 바이너리 / npm 패키지로 배포** | TS는 `npm i -g`, Rust는 정적 binary. JVM은 사용자에게 JDK 설치 강요 → 채택 어려움 |
| **즉각 시작 (cold start <100ms)** | JVM은 JIT warmup으로 시작 1–2초. CLI 도구로는 부적합 |
| **터미널 / IDE 통합 (VS Code 확장, LSP, stdio)** | 노드 생태계가 압도적. Cursor, Continue, Cline 모두 TS |
| **메모리 풋프린트 작게** | TS/Rust는 수십 MB, JVM은 수백 MB |
| **로컬 파일시스템·shell 도구 호출** | 셸 통합은 어느 언어든 가능하지만 노드의 child_process가 가장 단순 |

> 즉 **Claude Code = 사용자 컴퓨터에서 도는 클라이언트** 라서 TS를 골랐습니다.
> *"Embabel을 클라이언트 CLI로 다시 쓰려고 한다"* 는 시도는 잘못된 비교 — 둘은 같은 축이 아님.

#### (b) 왜 LangChain / LangGraph 등 *연구·실험* 도구는 Python 인가

**데이터 사이언스 / 노트북 문화의 관성**:

| 강점 | 이유 |
|------|------|
| **Jupyter 친화** | 셀 단위 실행, 즉시 시각화, 실험 반복 |
| **ML 라이브러리 천국** | torch, numpy, pandas, transformers — 데이터 가공/평가 자연스러움 |
| **빠른 프로토타이핑** | 타입 없음 → 5분 만에 RAG 파이프라인 |
| **연구자 다수가 사용** | 대부분의 LLM 회사 내부 코드도 Python |

**그러나 엔터프라이즈 서버에 그대로 들고가면 부족한 부분**:

| 부족한 점 | 현실의 비용 |
|-----------|--------------|
| **동시성** | GIL 때문에 진짜 병렬 X. async도 협력적이라 CPU-bound 작업이 막힘 |
| **타입 안전** | mypy / pyright 는 *옵션* 이고 런타임에 보장 안 됨. 대규모 팀에서 리팩토링 폭탄 |
| **JIT/AOT 컴파일** | CPython 인터프리터는 JVM 대비 3–10배 느림 (수치 연산 외) |
| **DI / IoC 컨테이너** | Spring 같은 표준 부재. FastAPI Depends, dependency-injector 등 파편화 |
| **트랜잭션 / 리소스 관리** | `@Transactional` 같은 선언적 도구 부재. SQLAlchemy 세션 관리 수동 |
| **장기 운영 도구** | JMX, JFR, 힙 덤프, 스레드 덤프 같은 *프로덕션 진단* 인프라 빈약 |
| **표준화된 보안 프레임워크** | Spring Security 같은 통합 솔루션 부재. OAuth2 마다 라이브러리가 다름 |
| **모니터링/관찰** | OpenTelemetry는 가능하지만 Spring처럼 *기본 탑재* 가 아님. 직접 wiring |
| **메모리 관리** | GC 튜닝 도구가 빈약, 장기 메모리 누수 추적 어려움 |
| **빌드/배포** | venv / poetry / pip 파편화. 단일 deployable artifact 생성 어려움 (Java jar/war 같은 것) |

> Python 에이전트를 **사내 백오피스 워크플로 / 트랜잭션 처리 / 멀티테넌트 SaaS** 에 그대로 올리려는 순간 — 이 모든 비용이 운영팀에게 청구됩니다.

#### (c) 왜 *서버* 에이전트는 JVM 이 적합한가 — 엔터프라이즈 강점 정리

**JVM 의 AI 워크플로 강점은 "AI 자체" 보다는 "AI를 운영 인프라에 끼워 넣는 능력"** 에 있습니다.

| JVM 자산 | AI 에이전트에 어떻게 작동하는가 |
|----------|--------------------------------|
| **Spring Boot 자동 구성** | `@LlmTool` 메서드를 `@Component` 로 그냥 노출. DI / config / lifecycle 무료 |
| **Spring Security** | "이 사용자만 이 도구 호출" — `@PreAuthorize("hasRole('ANALYST')")` 한 줄 |
| **Spring Data + JPA** | 에이전트 결과/감사 로그를 RDB에 영속. 트랜잭션 자동 |
| **Resilience4j / Spring Retry** | LLM 호출 실패 시 exponential backoff. Embabel의 `RetryProperties` 가 이 위에 빌드됨 |
| **Micrometer + Actuator** | `/actuator/metrics`, `/actuator/health` 자동. 토큰 사용량/지연 모니터링 무료 |
| **OpenTelemetry 통합** | 분산 트레이싱 — LLM 호출 → DB → 외부 API 까지 한 trace로 |
| **JMX / JFR / Heap Dump** | 프로덕션 디버깅 일급 도구. 장기간 도는 에이전트의 누수/병목 잡기 쉬움 |
| **Reactive / Coroutines** | Project Reactor / Kotlin coroutines. 수천 동시 SSE 스트림 (본 프로젝트의 사례) |
| **GraalVM native image** | 향후 cold start 1초 미만으로 단축 가능 (아직 Embabel은 JVM 모드만 검증) |
| **단일 deployable jar/war** | 컨테이너 이미지 빌드·배포가 단순. K8s에 그대로 올림 |
| **수십 년 누적된 ops 노하우** | Java 백엔드 운영 인력 풀이 압도적. SRE 채용 / 인수인계 용이 |
| **장기 호환성** | LTS 릴리스 + Spring 의 신중한 deprecation. 5년 후에도 같은 코드 빌드됨 |
| **타입 안전 도메인 모델** | 풍부한 비즈니스 모델 → LLM 흐름의 노드. 리팩토링 시 IDE가 모두 추적 |
| **트랜잭션 + 분산 락** | Embabel 액션 안에서 DB 트랜잭션 / Redis 락 / Kafka 프로듀서 호출 그대로 가능 |

#### (d) 정리표 — 같은 축에서 비교하기

| 도구 카테고리 | 사는 곳 | 대표 언어 | 왜 그 언어인가 |
|---------------|---------|-----------|----------------|
| **코딩 에이전트** (Claude Code, Cursor, Continue, Cline) | 클라이언트(CLI/IDE) | TypeScript / Rust | 단일 바이너리, 즉시 시작, 노드 IDE 생태계 |
| **연구·실험 파이프라인** (LangChain, LangGraph, CrewAI) | 노트북 / 분석 서버 | Python | Jupyter, ML 라이브러리, 빠른 프로토타이핑 |
| **엔터프라이즈 서버 에이전트** (Embabel, Spring AI) | 백엔드 서버 | **Kotlin / Java (JVM)** | DI, 보안, 트랜잭션, 모니터링, 장기 운영 |
| **API 서비스** (OpenAI Assistants, Anthropic) | (관리형 서비스) | (외부) | 직접 구현 안 함 |

> **본 프로젝트의 자리**: 사내망 LLM 게이트웨이 + 기존 Spring 인프라 + Tavily 검색 → **엔터프라이즈 서버 에이전트** 카테고리.
> 이 카테고리에서 *현재 가장 성숙한 옵션이 Embabel + Spring AI* 입니다.

---

## 6. `POST /api/deep-research/stream` 코드 레벨 워크스루

> 라우터·핸들러·서비스 같은 **헥사고날 / 스트리밍 인프라** 는 [README.md §2 전체 아키텍처](./README.md#2-전체-아키텍처) 를 참고하세요.
> 이 섹션은 **Embabel 내부 — `@Agent`, `@Action`, `@LlmTool`, `AgentInvocation`, 리스너** 로만 한정합니다.

요청 한 번에 **LLM 약 7회** 호출 (plan 1 + sections 5 + framing 1) + **Tavily 웹 검색 5–10회** 가 일어납니다.
이 모든 흐름이 **도메인 데이터 클래스의 타입 그래프** 만으로 자동 합성된다는 점이 핵심입니다.

### 6.0 Embabel 진입 후 호출 흐름

```
AgentInvocation.invoke(UserInput("최근 프랑스 경제"))
    │
    ▼
Embabel GOAP Planner   ← 목표 타입(AgentDeepResearchReport)을 보고 plan 합성
    │
    ▼ plan = [planSubtopics, gatherSections, frameReport, assembleReport]
    │
    ▼  (각 액션 실행마다 OODA 루프: replan 가능)
    │
DeepResearchAgent::planSubtopics(UserInput)             ──► AgentResearchPlan
    │                                                              │
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

각 단계마다 Embabel은 5종 콜백 이벤트를 발행:
`ActionExecutionStart/Result`, `ToolCallRequest/Response`, `ObjectAdded`.
이를 두 리스너가 가로채 SSE 이벤트로 변환합니다 (§6.7–6.8).

---

### 6.1 부트스트랩 — 어떻게 자동 등록되나

`@SpringBootApplication` 한 줄만 있으면, Embabel 스타터(`embabel-agent-starter`) 의 auto-configuration 이 다음을 발견·등록합니다:

```kotlin
// EmbabelTestApplication.kt
@SpringBootApplication
class EmbabelTestApplication

fun main(args: Array<String>) { runApplication<EmbabelTestApplication>(*args) }
```

| 클래스패스에서 발견 | 무엇이 일어나는가 |
|----------------------|--------------------|
| `@Agent class DeepResearchAgent` | `AgentPlatform` 의 액션·골 카탈로그에 등록 |
| `@Action fun planSubtopics(...)` | 시그니처 분석 → 자동으로 precondition / effect 합성 |
| `@AchievesGoal @Action fun assembleReport(...)` | 반환 타입 = **goal 타입** 으로 등록 |
| `@LlmTool fun tavily_search(...)` | LLM 호출 시 `withToolObject(...)` 로 노출 가능 |
| `@Component class ResearchTools` | DI 컨테이너에 등록 → 에이전트가 생성자 주입 |

> **결과**: 사용자 코드에서 호출할 진입점은 단 하나, `AgentInvocation.builder(agentPlatform).build(목표타입).invoke(입력)` 입니다.

---

### 6.2 도메인 데이터 클래스 — GOAP 플래너의 어휘(vocabulary)

플래너가 plan을 합성하는 입력은 *함수 시그니처에 등장하는 타입* 뿐입니다.
따라서 **이 데이터 클래스 5개가 곧 plan 의 노드** 입니다.

```kotlin
// adapter/outbound/agent/DeepResearchAgent.kt (상단)
data class AgentResearchSubtopic(
    val title: String,
    val rationale: String,
)

data class AgentResearchPlan(
    val mainTopic: String,
    val subtopics: List<AgentResearchSubtopic>,
)

data class AgentSection(
    val title: String,
    val body: String,
    val sources: List<String>,
)

data class AgentSectionDrafts(
    val sections: List<AgentSection>,
)

data class AgentReportFraming(
    val executiveSummary: String,
    val conclusion: String,
)

data class AgentDeepResearchReport(    // ← @AchievesGoal 액션의 반환 타입 = Goal
    val topic: String,
    val executiveSummary: String,
    val sections: List<AgentSection>,
    val sources: List<String>,
    val conclusion: String,
)
```

> **왜 이렇게 잘게 쪼개나?**
> - `AgentSectionDrafts` 와 `AgentReportFraming` 을 **별개 타입**으로 두면 → `assembleReport` 의 시그니처 `(plan, drafts, framing) → report` 에서 *세 가지가 모두 필요* 함이 타입으로 표현됩니다.
> - 플래너는 *"`AgentReportFraming` 을 만들 줄 아는 액션이 있는가? → `frameReport`. 거기는 `drafts` 가 필요함 → `gatherSections`. ..."* 식으로 **타입을 따라 거꾸로 plan을 합성** 합니다.

---

### 6.3 `@LlmTool` — LLM에게 부여되는 능력 (`ResearchTools.kt` 전체)

```kotlin
// adapter/outbound/agent/ResearchTools.kt
@Component
class ResearchTools(
    @param:Value("\${tavily.api-key:}") private val tavilyApiKey: String,
    @param:Value("\${tavily.base-url:https://api.tavily.com}") private val tavilyBaseUrl: String,
    private val clock: Clock = Clock.systemDefaultZone(),
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
            "Returns a list of up-to-date results, each with a title, url, and an extracted text snippet. " +
            "Call this BEFORE drafting keyPoints whenever the topic mentions temporal terms " +
            "('today', '오늘', 'recent', '최근', 'latest'), proper nouns you are not certain about, " +
            "or any time-sensitive fact. Cite the returned URLs inline in keyPoints.",
    )
    fun tavilySearch(
        @LlmTool.Param(description = "Concise search query in the language most likely to retrieve relevant sources (English usually best).")
        query: String,
        @LlmTool.Param(description = "Maximum number of results to return (1..10).", required = false)
        maxResults: Int = 5,
    ): List<TavilyResult> {
        check(tavilyApiKey.isNotBlank()) { "TAVILY_API_KEY is not configured" }
        val response = webClient.post()
            .uri("$tavilyBaseUrl/search")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(TavilyRequest(
                apiKey = tavilyApiKey,
                query = query,
                maxResults = maxResults.coerceIn(1, 10),
            ))
            .retrieve()
            .bodyToMono(TavilyResponse::class.java)
            .block(REQUEST_TIMEOUT) ?: return emptyList()
        return response.results.map { it.truncated() }    // 결과당 최대 2000자로 truncate
    }

    private fun TavilyResult.truncated(): TavilyResult =
        if (content.length <= MAX_CONTENT_CHARS) this
        else copy(content = content.substring(0, MAX_CONTENT_CHARS))

    companion object {
        const val MAX_CONTENT_CHARS = 2000
        private val REQUEST_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(15)
        // ...
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TavilyResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val score: Double? = null,
)
```

#### `@LlmTool` 디자인 포인트

1. **`description` 이 곧 프롬프트** — Embabel 은 이 문자열을 OpenAI tool-spec 의 `description` 필드로 그대로 넘깁니다. *"언제 이 툴을 호출해야 하는가"* 를 명시하면 LLM 이 더 잘 호출.
2. **`@LlmTool.Param`** — 각 인자에도 description. `required=false` + 기본값으로 옵셔널 인자 표현.
3. **`MAX_CONTENT_CHARS = 2000`** — 한 결과가 너무 길면 LLM 컨텍스트를 잡아먹기에 잘라냅니다. 대신 검색 횟수를 1–2회로 프롬프트에서 제한 (다음 섹션).
4. **WebClient + Reactor `.block(15s)`** — Tavily는 동기 응답이라 안전하게 차단. 실패 시 빈 리스트를 반환해 LLM이 *"검색 결과가 없음"* 으로 인식하고 진행하도록.

---

### 6.4 `@Agent` 본체 — `DeepResearchAgent.kt` (4개 `@Action` 전체)

```kotlin
@Agent(description = "Produce a long-form, source-grounded research report on an arbitrary topic")
class DeepResearchAgent(
    private val researchTools: ResearchTools,
    @param:Value("\${deep-research.subtopic-count:5}") private val subtopicCount: Int,
    @param:Value("\${deep-research.section-word-count:300}") private val sectionWordCount: Int,
) {
    // ... 4 @Action methods (아래 6.4.1 ~ 6.4.4)
}
```

#### 6.4.1 Step 1 — `planSubtopics` (LLM, 툴 없음)

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
| 입력 타입 | `UserInput` (사용자 토픽) |
| 출력 타입 | `AgentResearchPlan` (= GOAP 월드에 추가됨) |
| 툴 | **없음** (`withToolObject` 호출 X) |
| temperature | `0.3` — 어느 정도 다양성 허용 (subtopic이 너무 비슷하면 안 됨) |

> **실제 출력 예시 — topic = "최근 프랑스 경제"**
> ```json
> {
>   "mainTopic": "최근 프랑스 경제",
>   "subtopics": [
>     {"title": "GDP 성장률과 거시 지표",
>      "rationale": "INSEE의 2024-25 분기 GDP 데이터를 통해 경기 회복 정도를 평가"},
>     {"title": "인플레이션과 가계 구매력",
>      "rationale": "에너지 가격 안정 이후 CPI 추이와 실질임금 영향"},
>     {"title": "노동 시장과 실업률",
>      "rationale": "청년 실업률 / 노동개혁 효과 / 최저임금"},
>     {"title": "재정 적자와 EU 규율",
>      "rationale": "GDP 대비 적자 / EDP 절차 가능성 / 신용등급"},
>     {"title": "주요 산업 동향 (에너지·자동차·AI)",
>      "rationale": "원전 회귀, EV 전환, Mistral 등 AI 스타트업 생태계"}
>   ]
> }
> ```
> 이 객체가 만들어지는 순간 `ObjectAddedEvent` → `PlanAndSectionListener` 가 가로채 `plan-formulated` SSE 이벤트 발행.

#### 6.4.2 Step 2 — `gatherSections` (LLM × N + tavily_search)

```kotlin
@Action
fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts {
    val drafts = plan.subtopics.map { subtopic ->
        context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
            .withToolObject(researchTools)               // ★ 이 LLM 호출에서만 툴 가시
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

이 한 액션 안에서 일어나는 일을 시간축으로 펼치면:

```
subtopic[0] = "GDP 성장률과 거시 지표"
    LLM round 1
        → assistant tool_call: tavily_search(query="French GDP growth Q1 2025 INSEE", maxResults=5)
        → tool_result: [{title: "INSEE: France GDP grew 0.1% in Q1...", url: "https://insee.fr/...", content: "..."}, ... ]
    LLM round 2
        → assistant tool_call: tavily_search(query="France 2024 annual GDP final estimate")
        → tool_result: [...]
    LLM round 3
        → assistant final message (structured): {
            "title": "GDP 성장률과 거시 지표",
            "body": "프랑스의 2025년 1분기 GDP는 0.1% 성장하여 [https://insee.fr/...] ...",
            "sources": ["https://insee.fr/...", "https://reuters.com/..."]
          }
    ▼  ObjectAddedEvent(AgentSection) → "section-drafted" SSE

subtopic[1] = "인플레이션과 가계 구매력"
    ... (반복) ...
```

> **`section-drafted` 이벤트 payload**: `{ title: "GDP 성장률과 거시 지표", sourceCount: 3 }`. 본문은 마지막 `deep-research-completed` 까지 SSE에 안 흐릅니다 (8장 토큰 스트리밍 한계 참고).

#### 6.4.3 Step 3 — `frameReport` (LLM + current_date)

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

**컨텍스트 압축 트릭** — 각 섹션 본문 전체가 아니라 **첫 160자 + "…"** 만 LLM에게 노출.
이 한 단계에서 모든 본문을 다시 보낼 경우 ~5,000 토큰 추가 → 비용·지연·재시도 모두 악화. 160자만으로도 LLM이 *"무슨 내용을 다뤘는지"* 파악하기엔 충분.

> **LLM 입력 예시** (섹션 5개 가정)
> ```
> # Section briefs
> - GDP 성장률과 거시 지표: 프랑스의 2025년 1분기 GDP는 0.1% 성장하여 ... [https://...] ...
> - 인플레이션과 가계 구매력: 2024년 11월 CPI는 1.3%로 ... ...
> - 노동 시장과 실업률: 청년 실업률은 17.8%로 ... ...
> - 재정 적자와 EU 규율: GDP 대비 재정 적자는 5.5% ... ...
> - 주요 산업 동향: 자동차 부문은 EV 전환을 가속 ... ...
> ```

#### 6.4.4 Step 4 — `assembleReport` (LLM 없음, 순수 Kotlin)

```kotlin
@AchievesGoal(description = "A multi-section research report has been produced")
@Action
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

- **`@AchievesGoal`** — 이 한 어노테이션이 GOAP 플래너에게 *"이 액션의 반환 타입이 곧 goal"* 임을 알려줍니다.
- **3개 입력 타입 모두 타입 시스템에 의해 강제** — 누가 빠지면 컴파일 에러. 플래너는 셋이 모두 월드에 *없으면* 이 액션을 시도조차 안 합니다.
- **LLM 호출 없음** — 결합·dedup·매핑은 결정론적 작업. LLM에게 시키면 *환각으로 sources 가 사라지거나* 토큰 한도로 잘릴 위험.

> 💡 **분리 이유 재강조**: `frameReport` 와 `assembleReport` 를 한 LLM 호출로 합치면 → 5섹션 + 메타까지 한 번에 만들어야 함 → output token 한도 초과 → 무한 재시도 패턴 관찰됨.
> Embabel 철학: **"LLM이 잘하는 일과 못하는 일을 코드로 분리한다."**

---

### 6.5 LLM 어노테이션 / DSL의 동작 모델

위 4개 `@Action` 에서 공통으로 보이는 패턴 정리:

```kotlin
context.ai()                                           // Embabel이 주입한 OperationContext
    .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))   // 모델 + temperature
    .withToolObject(researchTools)                     // 이 호출에 한해 @LlmTool 노출
    .create<AgentSection>("""...prompt...""")          // 제네릭 타입 = 강제 스키마
```

| 메서드 | 효과 |
|--------|------|
| `withLlm(LlmOptions...)` | 모델 ID, temperature, max-tokens 등. `withAutoLlm()` 은 `embabel.models.default-llm` 을 사용 |
| `withToolObject(obj)` | `obj` 의 `@LlmTool` 메서드 모두를 OpenAI tool-spec 으로 변환해 주입 |
| `withTools("name1", ...)` | 등록된 다른 도구 빈을 이름으로 지정 (본 프로젝트는 안 씀) |
| `create<T>(prompt)` | 1) 프롬프트 송신 2) tool loop 3) 최종 응답 → `T` 로 파싱 4) `init { require }` 검증 5) 실패 시 자동 retry |

> **`create<T>` 의 마지막 단계가 `init` 검증과 결합** 한다는 점이 핵심입니다. `AgentSection` 의 도메인 측 카운터파트 `Section` 이 만약 `require(body.length >= 50)` 같은 조건을 가졌다면, LLM이 짧은 본문을 뱉을 때 자동으로 retry 가 걸립니다.

---

### 6.6 Embabel 진입점 — `AgentInvocation` 호출

`@Agent` / `@Action` 으로 등록된 모든 정보는 Spring 컨테이너 안의 `AgentPlatform` 빈에 모입니다.
실제 실행 트리거:

```kotlin
// 본 프로젝트의 어댑터 안에서 (헥사고날 측 코드는 §README 참조)
val report: AgentDeepResearchReport = AgentInvocation
    .builder(agentPlatform)
    .options(ProcessOptions().withListener(combinedListener))   // 진행 이벤트 구독
    .build(AgentDeepResearchReport::class.java)                 // ← 목표 타입
    .invoke(UserInput("최근 프랑스 경제"))                       // ← 시작 입력
```

이 4 라인이 트리거하는 작업:

1. `AgentPlatform` 의 액션·골 카탈로그 로드
2. **GOAP 플래너 호출** — `AgentDeepResearchReport` 를 만들 수 있는 plan 합성 (§4 참조)
3. plan을 첫 액션부터 순차 실행. 각 액션 후 **replan 가능** (OODA)
4. 각 단계마다 listener에 5종 콜백 push
5. goal 도달 시 결과 객체 반환

**블로킹** API 입니다. WebFlux 환경에서는 `Dispatchers.IO` 코루틴 안에서 호출해야 이벤트 루프를 막지 않음.

---

### 6.7 진행 이벤트 매핑 — `StreamingAgentEventListener.kt` (전체)

Embabel의 5종 콜백을 1:1로 도메인 이벤트로 매핑하는 전체 코드:

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
                status = event.actionStatus.status.name,            // SUCCEEDED / FAILED / ...
                durationMs = event.runningTime.toMillis(),
            )
            is ToolCallRequestEvent -> ResearchEvent.ToolInvoked(
                tool = event.tool,
                input = event.toolInput.takeUnless(String::isBlank).orEmpty(),
            )
            is ToolCallResponseEvent -> ResearchEvent.ToolReturned(
                tool = event.request.tool,
                durationMs = event.runningTime.toMillis(),
                resultPreview = event.resultPreview(),              // ≤240자 truncate
            )
            else -> null
        }
        if (mapped != null) sink.tryEmitNext(mapped)
    }

    private fun ToolCallResponseEvent.resultPreview(): String {
        val raw = runCatching { result.toString() }.getOrDefault("")
        return if (raw.length <= MAX_PREVIEW_CHARS) raw
               else raw.substring(0, MAX_PREVIEW_CHARS) + "..."
    }

    companion object {
        private const val MAX_PREVIEW_CHARS = 240
    }
}
```

**관찰 가능성 디테일**: 툴 응답 본문 전체(수 KB)를 SSE로 흘리면 클라이언트가 마비되므로, **240자로 truncate한 미리보기**만 SSE로 발행합니다. 본문은 LLM 컨텍스트 안에서만 사용됩니다.

---

### 6.8 도메인 마일스톤 — `ObjectAddedEvent` 활용

`ActionExecution*` 만으로는 *"plan 의 어느 단계 결과인지"* 알기 어렵습니다.
대신 **월드 상태에 새 객체가 추가되는 시점** 을 가로채는 방식이 깔끔합니다:

```kotlin
// EmbabelDeepResearchAgentAdapter.kt 안의 inner class
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
                ResearchEvent.SectionDrafted(
                    title = payload.title,
                    sourceCount = payload.sources.size,
                ),
            )
        }
    }
}
```

두 리스너는 한꺼번에 등록됩니다:

```kotlin
private class MulticastListener(
    private val listeners: List<AgenticEventListener>,
) : AgenticEventListener {
    override fun onProcessEvent(event: AgentProcessEvent) {
        listeners.forEach { it.onProcessEvent(event) }
    }
}

// 사용
val combined = MulticastListener(listOf(baseListener, planListener))
ProcessOptions().withListener(combined)
```

**저수준 (process) + 고수준 (도메인) 이벤트가 한 sink로 합류** → 클라이언트는 `event:` 헤더만 보고 분기.

---

### 6.9 실제 SSE 응답 샘플

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
data: {"type":"deep-research-completed","payload":{"report":{
    "topic":"최근 프랑스 경제",
    "executiveSummary":"As of 2026-05-05, France's economy is navigating ...",
    "sections":[
        {"title":"GDP 성장률과 거시 지표","body":"프랑스의 2025년 ... [https://...] ...","sources":["https://...","https://..."]},
        ...
    ],
    "sources":["https://...", "..."],
    "conclusion":"종합하면 프랑스 경제는 ..."
}}}
```

> **읽는 팁**: `event:` 헤더로 클라이언트가 라우팅합니다. 브라우저 EventSource 에서:
> ```js
> const es = new EventSource(...);
> es.addEventListener('section-drafted', e => updateProgress(JSON.parse(e.data)));
> es.addEventListener('deep-research-completed', e => render(JSON.parse(e.data).payload.report));
> ```

---

## 7. 오류 처리 / 재계획 / Fallback 메커니즘

> *"한 액션이 실패하면? 같은 입력으로 재시도? 다른 plan으로 우회? 무한 루프 방어?"* —
> Embabel 은 이 질문들을 **5층 구조** 로 답합니다. 위에서 아래로 내려갈수록 *플랜 자체* 를 바꿉니다.

### 7.1 오류 처리 5층 구조 — 한눈에

| 층 | 메커니즘 | 트리거 | 효과 | 코드 위치 |
|----|----------|--------|------|-----------|
| ① | **Spring Retry (RetryTemplate)** | LLM API 호출 실패 / rate limit | 같은 호출을 exponential backoff 로 재시도 | `embabel-agent-api` `RetryProperties` |
| ② | **Action Retry Policy** | 액션 실행 예외 | 액션 전체를 최대 N회 재시도 (`@Action(actionRetryPolicy = ...)`) | `ActionRetryPolicy` enum |
| ③ | **도메인 검증 → LLM 재호출** | `init { require(...) }` 실패 | LLM 에게 *같은 액션 다시 시도* 요청 | 도메인 `data class` |
| ④ | **GOAP Replan (자동)** | 액션 결과로 월드 상태 변화 | 매 액션 후 plan 재합성 (OODA 의 Decide) | `Planner.bestValuePlanToAnyGoal` |
| ⑤ | **명시적 Replan 요청** | 툴이 `ReplanRequestedException` throw | 현재 plan 즉시 폐기 + 새 plan 합성 | `ReplanRequestedException` / `ReplanningTool` |

각 층은 *독립적으로 동작* 하며, 위층이 못 잡으면 아래층이 받아냅니다.

---

### 7.2 [① + ②] 재시도 — `ActionRetryPolicy`

라이브러리 내부 (소스 인용):

```kotlin
// com/embabel/agent/core/ActionRetryPolicy.kt
/**
 * Retry policy selector for an action.
 * The underlying policy maps to ActionQos with the following default properties:
 *   max-attempts:        5
 *   backoff-millis:      10000      (10s)
 *   backoff-multiplier:  5.0
 *   backoff-maxInterval: 60000      (60s)
 *   idempotent:          false
 */
enum class ActionRetryPolicy {
    /** Fire only once: maps to ActionQos with maxAttempts = 1. */
    FIRE_ONCE,
    /** Default retry policy. */
    DEFAULT,
}
```

내부 구현은 Spring Retry의 `RetryTemplate` + exponential backoff:

```kotlin
// com/embabel/agent/spi/common/RetryProperties.kt
override fun retryTemplate(name: String): RetryTemplate {
    return RetryTemplate.builder()
        .exponentialBackoff(
            Duration.ofMillis(backoffMillis),     // 10s 시작
            backoffMultiplier,                     // ×5
            Duration.ofMillis(backoffMaxInterval), // 최대 60s
        )
        .customPolicy(retryPolicy)                 // SpringAiRetryPolicy(maxAttempts = 5)
        .withListener(object : RetryListener {
            override fun <T, E : Throwable> onError(...) {
                // ToolControlFlowSignal (ReplanRequestedException 등) 은 재시도 X
                if (throwable is ToolControlFlowSignal) throw throwable
                if (isRateLimitError(throwable)) {
                    log.info("LLM RATE LIMITED: Retry {}/{}", retryCount, maxAttempts)
                    return                                // 재시도 진행
                }
                log.info("Operation $name: Retry. count=$retryCount", throwable)
            }
        })
        .build()
}
```

**핵심 디테일**

- **rate limit 인지** — `isRateLimitError(throwable)` 로 429 등을 구분, 별도 로깅하면서 재시도.
- **`ToolControlFlowSignal` 은 재시도 안 함** — `ReplanRequestedException` 같은 *제어 흐름 신호* 는 즉시 위로 던집니다. (재시도 = "같은 결과를 다시 시도", 제어 흐름 = "다른 길로 가자" → 의미가 다름)
- **idempotent=false 기본값** — 부작용 있는 액션도 재시도 가능하게 풀어둠. 결제처럼 진짜 idempotency 필요하면 `false` 로 명시.

### 7.3 본 프로젝트 적용 — `assembleReport` 에 `FIRE_ONCE`

**실제 코드 변경**:

```kotlin
// adapter/outbound/agent/DeepResearchAgent.kt
import com.embabel.agent.core.ActionRetryPolicy   // ← 추가

@AchievesGoal(description = "A multi-section research report has been produced")
@Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)   // ← 추가
fun assembleReport(
    plan: AgentResearchPlan,
    drafts: AgentSectionDrafts,
    framing: AgentReportFraming,
): AgentDeepResearchReport = ...
```

**왜 이게 옳은가**

- `assembleReport` 는 **순수 Kotlin** 입니다 (LLM 호출 없음).
- 이 액션이 실패하면 원인은 **결정론적 코드의 버그** 거나 **상위 객체가 `init` 검증을 통과 못 한 것**.
- 같은 입력으로 5번 재시도해도 결과는 똑같음 → **돈·시간 낭비**.
- 한 번 실패하고 위로 던져 빨리 알게 하는 편이 디버깅에 유리.

> 일반화: **LLM 호출이 없는 결정론적 액션 (assemble / merge / format) 은 모두 `FIRE_ONCE` 가 합리적.**

### 7.4 [③] 도메인 검증이 LLM 재호출을 트리거

```kotlin
// domain/model/DeepResearchReport.kt
data class DeepResearchReport(
    val topic: ResearchTopic,
    val executiveSummary: String,
    val sections: List<Section>,
    ...
) {
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
   │
   ▼
context.ai().create<AgentSection>(...)
   │
   ▼  Jackson 파싱
data class 생성자 호출
   │
   ▼  init { require(...) } 실행
   │
   ├── 통과 → 객체 반환
   └── 실패 (IllegalArgumentException)
         │
         ▼
       Spring Retry RetryTemplate 가 잡음
         │
         ▼
       LLM 다시 호출 (같은 프롬프트, 다른 샘플링)
```

**왜 이게 강력한가**: 검증 로직이 *코드* 에 있으면 LLM 이 비슷한 실수를 반복할 확률이 줄어듭니다. *프롬프트* 안의 *"please return a valid object"* 보다 훨씬 신뢰도 높음.

### 7.5 [④] GOAP Replan — 매 액션 후 자동 재계획

§3 OODA 루프의 핵심. 코드는 `Planner.kt`:

```kotlin
// com/embabel/plan/Planner.kt — 라이브러리 내부
fun plansToGoals(system: PlanningSystem): List<P> {
    val state = worldState()                                         // 현재 월드 상태
    return system.goals
        .mapNotNull { goal -> planToGoal(system.actions, goal) }     // 각 골에 대한 plan
        .sortedByDescending { p -> p.netValue(state = state) }       // net value 내림차순
}

fun bestValuePlanToAnyGoal(system: PlanningSystem): P? =
    plansToGoals(system).firstOrNull()
```

**OODA 의 Decide 단계가 매번 이 함수를 호출** 합니다. 즉 액션이 끝날 때마다:

1. 새 객체가 월드에 추가됨 (e.g., `AgentSectionDrafts`)
2. 그 결과 plan 의 다음 단계가 *다른 액션* 이 더 싸/유리해질 수 있음
3. `bestValuePlanToAnyGoal` 가 다시 호출 → 필요하면 plan 재선택

> **Fallback 자동 동작**: 어떤 액션이 *예외를 던지지 않고* 빈 객체를 반환했다고 해봅시다. 다음 단계 액션의 precondition 이 만족 안 되면 → 플래너가 *다른 경로* 를 찾아갑니다. 코드 한 줄 안 적어도 자동으로 fallback.

### 7.6 [⑤] 명시적 Replan — `ReplanRequestedException`

라이브러리 코드 (소스 그대로 인용):

```kotlin
// com/embabel/agent/core/ReplanRequestedException.kt
/**
 * Exception thrown by a tool to signal that the tool loop should terminate
 * and the agent should replan based on the updated blackboard state.
 *
 * Use cases include:
 *  - Chat routing: A routing tool classifies user intent and requests replan
 *    to switch to the appropriate handler action
 *  - Discovery: A tool discovers that the current approach won't work and
 *    the agent should try a different plan
 *  - State changes: A tool detects significant state changes that require
 *    the agent to reassess its goals
 */
class ReplanRequestedException @JvmOverloads constructor(
    val reason: String,
    val blackboardUpdater: BlackboardUpdater = BlackboardUpdater {},
) : RuntimeException(reason), ToolControlFlowSignal
```

**예시 — 본 프로젝트에 적용한다면**:

```kotlin
// (예시) ResearchTools.kt 에 적용 가능한 패턴
@LlmTool(name = "tavily_search", ...)
fun tavilySearch(query: String, maxResults: Int = 5): List<TavilyResult> {
    if (tavilyApiKey.isBlank()) {
        // 검색이 불가능한 상황 → 다른 plan 으로 우회 요청
        throw ReplanRequestedException(
            reason = "Tavily API key not configured; switching to no-search mode",
            blackboardUpdater = { bb -> bb.addObject(SearchUnavailable) },
        )
    }
    // ... 정상 검색 ...
}

// 그러면 GOAP 플래너는 SearchUnavailable 이 월드에 있을 때
// gatherSectionsOffline 같은 다른 액션을 골라 plan 을 다시 짭니다.
```

### 7.7 `ReplanningTool` 데코레이터 — 항상/조건부 재계획

매번 명시적으로 throw 하기 번거롭다면 데코레이터로 감쌀 수 있습니다 (라이브러리 코드):

```kotlin
// com/embabel/agent/api/tool/ReplanningToolFactory.kt
interface ReplanningToolFactory {

    /** 호출 후 무조건 replan, 결과를 blackboard 에 추가 */
    fun replanAlways(tool: Tool): Tool

    /** 결과 artifact 가 predicate 매칭이면 replan */
    fun <T> replanWhen(tool: Tool, predicate: (t: T) -> Boolean): DelegatingTool

    /** decider 가 ReplanDecision 을 반환하면 replan */
    fun <T> conditionalReplan(
        tool: Tool,
        decider: (t: T, ctx: ReplanContext) -> ReplanDecision?,
    ): DelegatingTool
}
```

**활용 예 (개념 코드)**:

```kotlin
// 검색 결과가 비었을 때 자동 replan
val smartSearch = ReplanningToolFactory.replanWhen<List<TavilyResult>>(rawSearchTool) {
    results -> results.isEmpty()
}
```

→ 결과가 비면 plan 을 바꿔 *기본 지식 기반 작성* 같은 다른 액션으로 전환.

### 7.8 무한 루프 방어 — `excludedActionNames` + `maxIterations`

같은 액션이 계속 실패하며 replan 을 반복하면 무한 루프가 됩니다. 라이브러리는 두 겹 가드:

```kotlin
// com/embabel/plan/Planner.kt
fun bestValuePlanToAnyGoal(
    system: PlanningSystem,
    excludedActionNames: Set<String>,    // 이미 실패한 액션 이름들
): P? {
    if (excludedActionNames.isEmpty()) return bestValuePlanToAnyGoal(system)
    val filteredSystem = object : PlanningSystem {
        override val actions = system.actions.filter { it.name !in excludedActionNames }.toSet()
        // ... 같은 system 의 goals/conditions 유지
    }
    return bestValuePlanToAnyGoal(filteredSystem)
}
```

```kotlin
// com/embabel/plan/goap/astar/AStarGoapPlanner.kt
val maxIterations = 10000   // A* 자체의 안전 가드
```

플래너 차원에선 **excluded 셋** 으로 같은 액션을 빼고 plan 합성, 검색 차원에선 **iteration 상한** 으로 폭주 방지.

### 7.9 본 프로젝트의 다중 방어선 정리

현재 코드가 이미 활용 중인 오류 처리 :

| 층 | 본 프로젝트의 사례 |
|----|---------------------|
| ① Spring Retry | `embabel-agent-starter` 가 자동 wiring (코드 0줄) |
| ② Action Retry | `assembleReport` 에 `FIRE_ONCE` 명시 (위 7.3) — 그 외는 DEFAULT 유지 |
| ③ 도메인 검증 | `ResearchTopic`, `Section`, `DeepResearchReport`, `ResearchPlan` 의 `init { require(...) }` |
| ④ GOAP Replan | 매 액션 후 자동 — 본 프로젝트는 모든 액션이 한 경로에 있어 효과 적음. 액션 추가 시 자동 발휘 |
| ⑤ 명시적 Replan | 현재는 사용 안 함 — 위 7.6 의 `SearchUnavailable` fallback 가 가능한 향후 확장 지점 |

**프롬프트 레벨 가드** (라이브러리 외):

- `embabel.agent.platform.llm-operations.prompts.default-timeout: 240s` — 짧은 타임아웃이 retry 폭주의 원인이었음 (참고 §8.4).
- `gatherSections` 프롬프트의 `"1-2 tavily_search calls (no more)"` — 툴 호출 수 제한.

### 7.10 향후 확장 — Tavily 실패 시 fallback 액션 추가하기

본 프로젝트에 *진짜 fallback plan* 을 넣고 싶다면:

```kotlin
// (확장 제안) DeepResearchAgent.kt
data class SearchUnavailable(val reason: String)

// 옵션 A: tavily_search 가 빈 결과를 반복하면 SearchUnavailable 을 월드에 추가
//          (ReplanningToolFactory.replanWhen 사용)

// 옵션 B: 별도의 가드 액션
@Action
fun checkSearchHealth(context: OperationContext): SearchUnavailable? {
    return runCatching { researchTools.tavilySearch("ping", maxResults = 1) }
        .fold(
            onSuccess = { if (it.isEmpty()) SearchUnavailable("empty results") else null },
            onFailure = { SearchUnavailable(it.message ?: "tavily error") },
        )
}

// 옵션 C: SearchUnavailable 이 월드에 있을 때만 발동하는 대안 gather
@Action
fun gatherSectionsOffline(
    plan: AgentResearchPlan,
    @Suppress("UNUSED_PARAMETER") signal: SearchUnavailable,   // ← precondition 으로 작동
    context: OperationContext,
): AgentSectionDrafts {
    // 검색 없이 LLM 의 기본 지식만으로 섹션 작성 (낮은 신뢰도지만 실패하지 않음)
    val drafts = plan.subtopics.map { subtopic ->
        context.ai().withLlm(...).create<AgentSection>(
            "Draft a section on '${subtopic.title}' using only your prior knowledge. " +
            "Mark unverified claims with [unverified]. No web access available."
        )
    }
    return AgentSectionDrafts(drafts)
}
```

이때 GOAP 플래너는 *월드에 `SearchUnavailable` 이 있는가* 에 따라 자동으로:

```
정상 경로:    planSubtopics → gatherSections     → frameReport → assembleReport
대안 경로:    planSubtopics → gatherSectionsOffline → frameReport → assembleReport
                              ↑
                              precondition: SearchUnavailable 객체 존재
```

**그래프 코드 한 줄 안 고치고**, 액션 메서드 한 개 추가만으로 fallback 이 작동합니다.

---

## 8. 추가로 알아두면 좋은 패턴

### (a) 목표 지향 plan = "타입 그래프 역추론"

```
[Goal]  AgentDeepResearchReport
            ▲
            │ assembleReport(plan, drafts, framing)
            │
   ┌────────┼────────┐
   │        │        │
AgentResearchPlan  AgentSectionDrafts  AgentReportFraming
   ▲             ▲                          ▲
   │ planSubtopics(UserInput)               │ frameReport(plan, drafts)
   │             │ gatherSections(plan)     │
   │             │                          │
   └─ UserInput ─┴──────────────────────────┘
```

플래너는 **목표 타입에서 거꾸로 따라 올라가** 의존 액션을 자동으로 정렬합니다.
새로운 액션을 추가해도 코드 어딘가의 if-else를 고칠 필요가 없습니다 — **타입만 맞추면 plan에 자동으로 끼어듭니다**.

### (b) 툴 노출 범위(scope)

`@LlmTool` 메서드는 **`.withToolObject(researchTools)` 가 붙은 LLM 호출 안에서만** 보입니다.
즉 `planSubtopics` 는 툴이 없어 검색 없이 추론하고, `gatherSections` / `frameReport` 만 검색·날짜 조회를 할 수 있습니다.
**툴 노출 = 권한 부여**로 생각하면 securty / cost 관점에서 이해가 쉽습니다.

### (c) LLM 타임아웃 / 재시도

- `embabel.agent.platform.llm-operations.prompts.default-timeout: 60s` (기본) 에서는 reasoning + 다회 tool 호출이 잘리고, retry 시 **동일 쿼리를 다시 발사** → 캐시 207ms 응답 → 무한 루프 패턴 관찰됨.
- **240s** 로 상향 + 프롬프트에서 `"1–2 tavily_search calls (no more)"` 로 호출 수 제한.

### (d) 도메인 검증이 LLM의 1차 게이트키퍼

```kotlin
// domain/model/DeepResearchReport.kt
init {
    require(executiveSummary.isNotBlank()) { "executiveSummary must not be blank" }
    require(sections.isNotEmpty()) { "report must contain at least one section" }
    require(conclusion.isNotBlank()) { "conclusion must not be blank" }
}
```

- LLM이 빈 필드를 뱉으면 **도메인 생성자에서 즉시 실패** → Embabel이 retry.
- "검증을 한 번 더 LLM에게 부탁"하는 패턴보다 빠르고, 결정론적이고, 무료입니다.
- ResearchPlan은 `MAX_SUBTOPICS = 8` 로 상한을 걸어 폭주를 방지.

### (e) replan 과 무한 루프 방어

GOAP 플래너는 매 액션 종료 후 **replan** 합니다 (§3 OODA). 그런데 같은 액션이 반복적으로 같은 결과를 만들면 무한 루프 위험:

```kotlin
// Planner.kt — 라이브러리 내장
fun bestValuePlanToAnyGoal(
    system: PlanningSystem,
    excludedActionNames: Set<String>,    // 이미 실패/반복한 액션 이름들
): P? { ... }
```

본 프로젝트에선 *프롬프트 레벨* 에서 `"1-2 tavily_search calls (no more)"` 로 막고, 라이브러리 차원에선 A* 의 `maxIterations = 10000` 가드가 받쳐줍니다.

---

## 9. 한계 — 토큰 단위 스트리밍은 왜 어려운가 (트레이드오프)

본 프로젝트의 SSE 이벤트는 **액션/툴 단위(coarse-grained)** 입니다.
Claude Code, ChatGPT 웹UI 처럼 *한 글자씩 타이핑되는 효과* 는 이 구조에서 자연스럽게 나오지 않습니다.

### 9.1 무엇이 스트리밍되고, 무엇이 안 되는가

| 항목 | 본 프로젝트 (Embabel `create<T>()`) | Claude Code 식 토큰 스트리밍 |
|------|--------------------------------------|------------------------------|
| Action 시작/종료 | ✅ `action-started`, `action-completed` | (해당 없음) |
| 툴 호출/응답 | ✅ `tool-invoked`, `tool-returned` | 보통 노출 안 됨 |
| Plan 마일스톤 | ✅ `plan-formulated`, `section-drafted` | (해당 없음) |
| **섹션 본문 토큰** | ❌ **섹션 1개가 통째로 완성된 뒤** `section-drafted` 1번 | ✅ `delta` 이벤트로 한 글자/단어씩 |
| 최종 보고서 | ✅ 한 번에 `deep-research-completed` | ✅ 누적된 토큰의 마지막 |

> **체감**: gatherSections 단계에서 5개 섹션 × 각 ~10–15초 = **약 50–75초 동안 본문이 화면에 안 나옴**.
> 그 사이 사용자가 보는 건 `tool-invoked` / `tool-returned` 이벤트뿐 — *진행 중인 인디케이터*는 되지만, *읽을 거리* 는 안 됩니다.

### 9.2 왜 그런가 — `create<T>()` 의 시그니처가 결정합니다

Embabel의 LLM 호출은 본질적으로 **"객체 단위 동기 호출"** 입니다.

```kotlin
// DeepResearchAgent.kt
context.ai()
    .withLlm(...)
    .withToolObject(researchTools)
    .create<AgentSection>("""...prompt...""")    // ← suspend 아님, blocking & 객체 반환
```

이 한 줄 안에서 일어나는 일:

1. LLM에게 프롬프트 송신 (structured output / JSON mode)
2. LLM이 토큰을 생성 (스트리밍이지만 어댑터가 **모아서** 받음)
3. 중간에 tool call이 나오면 → 툴 실행 → 결과를 다시 LLM에 주입 → LLM 재개
4. **최종 토큰까지 모두 모인 후** JSON 파싱 → `AgentSection` 객체 생성
5. `init { require(...) }` 로 도메인 검증 → 실패 시 retry
6. 검증 통과한 객체를 호출자에게 반환

토큰 스트리밍을 *외부로 흘리려면* 이 6단계 사이의 **2번 시점에 끼어들어야** 하는데, 현재 API는 그 훅을 제공하지 않습니다.
Embabel이 제공하는 콜백은 `AgenticEventListener` 의 5종 이벤트뿐 — `ActionExecutionStart/Result`, `ToolCallRequest/Response`, `ObjectAdded` — 모두 **객체/액션 경계** 에서 발생.

### 9.3 구조적으로 토큰 스트리밍이 어려운 4가지 이유

#### (1) Structured output = "완성된 JSON" 가정

`create<AgentSection>()` 는 LLM에게 *반드시 이 스키마로* 응답하라고 강제합니다.
JSON은 본질적으로 **닫는 괄호가 와야 valid** 한 포맷이라 *부분 파싱* 이 어렵습니다.

```json
{ "title": "GDP 성장률"
   ↑ 여기까지 도착했을 때 "title" 만 추출 가능, 하지만 body는 시작도 안 함
```

스트리밍 JSON 파서(예: Jackson `JsonParser` async, `jq --stream`)로 *끝나지 않은 필드도* 토큰별로 흘릴 순 있지만, **타입 안전한 객체 매핑** 과 양립이 어렵습니다.

#### (2) Tool loop은 비선형 (linear text가 아님)

LLM이 본문을 쓰다가 중간에 `tavily_search()` 를 호출하면:

```
LLM: "프랑스의 2025년 GDP는" → [tool_call: tavily_search("French GDP 2025")]
                                                    │
                                                    ▼
                                         (툴 실행 1.2초)
                                                    │
LLM:                                ◄──────────── tool_result
LLM: "프랑스의 2025년 GDP는 0.7% 성장하여 [https://...]"
```

만약 토큰을 그대로 클라이언트에 흘리면:
- 사용자에게 `"프랑스의 2025년 GDP는"` 까지 표시
- **1.2초 멈춤**
- 갑자기 같은 문장의 다른 버전이 이어짐 (LLM이 자체 수정하기도 함)
- → UX가 일관되지 않음

Claude Code가 매끄럽게 보이는 건 **단일 LLM 한 번 호출** 시나리오라 그렇고, agentic tool loop은 토큰 스트리밍과 잘 어울리지 않습니다.

#### (3) 도메인 검증이 "전체 객체" 단위

```kotlin
// domain/model/DeepResearchReport.kt
init {
    require(executiveSummary.isNotBlank())
    require(sections.isNotEmpty())
    require(conclusion.isNotBlank())
}
```

`require(...)` 는 **모든 필드가 모인 시점에만** 의미가 있습니다.
부분 토큰을 흘려보내고 나서 *나중에* 검증이 실패하면 — 클라이언트는 이미 "잘못된 텍스트" 를 표시한 상태가 됩니다. **롤백 불가**.

이것이 Embabel의 철학과 직결됩니다: *완성된 도메인 객체* 만이 신뢰할 수 있다.

#### (4) Plan 자체가 멀티-단계 (병합 비용)

본 프로젝트는 LLM 7회를 거칩니다 (plan 1 + sections 5 + framing 1).
각 호출의 토큰을 클라이언트에서 **이어붙이려면**:
- 어떤 호출의 토큰인지 (action id, section index) 라벨링
- 호출 간 합성 규칙 (예: section 본문은 누적, plan은 덮어쓰기)
- 재시도/실패 시 rollback delta

가 모두 클라이언트 책임이 됩니다. 결국 *서버에서 organize* 하는 편이 단순합니다.

### 9.4 트레이드오프 정리 — "구조 vs 즉시성"

| 축 | Embabel `create<T>()` (현재) | 토큰 스트리밍 (Claude Code 식) |
|----|------------------------------|--------------------------------|
| 타입 안전성 | ★★★ 컴파일 타임 보장 | ★ 문자열 → 사후 파싱 |
| 도메인 검증 | ★★★ `init` 로 1차 게이트 | ★ 클라이언트가 책임 |
| 부분 실패 처리 | ★★★ retry/replan 가능 | ★ 이미 흘러간 토큰 회수 불가 |
| GOAP plan 합성 | ★★★ 객체 그래프 기반 | ★ 단일 호출 가정 |
| **체감 지연(TTFB-like)** | ★ — *섹션 1개 완성까지 침묵* | ★★★ — 즉시 첫 토큰 |
| **읽기 시작 시점** | ★ 50–75초 후 | ★★★ <1초 |
| 진행 가시성 | ★★ 마일스톤 이벤트 | ★★★ 진행 중 본문 자체 |
| 백프레셔 | 단순 (이벤트가 적음) | 복잡 (수천 delta/sec) |

### 9.5 가능한 워크어라운드 (트레이드오프와 함께)

#### 옵션 A — **현재 구조 유지 + UX 보강** (가장 저비용)

토큰을 못 흘리는 대신 **마일스톤을 더 풍부하게**:

- `tool-invoked` / `tool-returned` 의 `preview` 를 클라이언트가 활용해 *"지금 무엇을 검색 중"* 표시
- `section-drafted` 가 도착하는 순간 섹션을 1개씩 페이드인 (현재 코드가 이미 지원)
- 각 섹션이 끝나면 **그 섹션만이라도** 즉시 본문 표시 — 마지막 `deep-research-completed` 까지 기다리지 않음

**트레이드오프**: 진짜 토큰 스트리밍은 아니지만, 코드 변경 거의 없음.

#### 옵션 B — **섹션 본문에 한해 Spring AI `ChatClient.stream()` 직접 호출**

`gatherSections` 만 Embabel의 `create<T>()` 를 우회하고 **두 단계로 분리**:
1. Spring AI 의 `ChatClient.stream()` 으로 본문 토큰을 직접 받아 SSE로 전달
2. 같은 LLM 응답을 **다시 한 번** structured output으로 파싱하거나, 텍스트→JSON 정규화 단계를 별도로 둠

```kotlin
// 의사 코드
val tokenFlux: Flux<String> = chatClient.prompt(prompt).stream().content()
tokenFlux
    .doOnNext { token -> sink.tryEmitNext(SectionTokenDelta(sectionIdx, token)) }
    .reduce(StringBuilder()) { sb, t -> sb.append(t) }
    .map { parseSection(it.toString()) }
```

**트레이드오프**:
- ✅ 본문은 진짜 토큰 스트리밍
- ❌ Embabel의 tool injection / replan / 도메인 검증의 일부를 잃음
- ❌ 같은 응답을 두 번 흘리면(원본 토큰 + 파싱된 객체) 클라이언트 상태 관리 복잡

#### 옵션 C — **하이브리드** — "구조는 Embabel, 본문 토큰은 별도 채널"

Embabel `@Action` 안에서 Spring AI 의 streaming API를 **로깅 채널** 처럼 부착:

```kotlin
// 개념
class TokenStreamingLlmDecorator(private val sink: Sinks.Many<ResearchEvent>) {
    fun create(prompt: String): AgentSection {
        val accumulated = StringBuilder()
        chatClient.prompt(prompt).stream().content()
            .doOnNext {
                accumulated.append(it)
                sink.tryEmitNext(ResearchEvent.TokenDelta(it))   // 새 이벤트 타입
            }
            .blockLast()
        return objectMapper.readValue(accumulated.toString())     // 사후 파싱
    }
}
```

**트레이드오프**:
- ✅ 토큰을 흘리면서 구조화된 객체도 얻음
- ❌ tool-call 시점에 토큰 흐름이 *깨끗하지 않음* (도구 결과 주입 텍스트가 섞일 수 있음)
- ❌ Embabel이 제공하는 자동 retry / structured-output 강제는 직접 다시 구현 필요

#### 옵션 D — **다른 프레임워크 / 직접 구현**

LangGraph, OpenAI Assistants API, 또는 Claude API + 직접 코드.
- ✅ 토큰 스트리밍 지원이 일급
- ❌ Embabel이 주는 GOAP 플래너, 타입 안전성, 자동 plan 합성을 모두 잃음

### 9.6 실용적인 권고

> **"Deep Research 스타일 보고서" 도메인에서는 토큰 스트리밍의 가치가 생각보다 작습니다.**
> 사용자가 읽기 시작하는 시점은 *섹션이 완성된 후* 가 자연스럽고, 인용 링크가 함께 도착해야 신뢰가 생깁니다.
> 부분 토큰만 보면 *"사실인지 확인 불가능한 문장"* 을 사용자가 먼저 읽게 되는 위험도 있습니다.

따라서 **현 구조 + 옵션 A 의 UX 보강** 이 비용 대비 가장 합리적인 선택입니다.
"Claude Code 같은 타이핑 효과" 가 핵심 요구사항이라면 도메인이 *대화/코드 생성* 쪽이지 *연구 보고서* 가 아닐 가능성이 큽니다.

---

## TL;DR

- Embabel = **GOAP 플래너 + 타입 안전 도메인 모델** 위에 LLM을 얹은 JVM 에이전트 프레임워크.
- `/api/deep-research/stream` = **plan → gather → frame → assemble** 4단계 GOAP plan 을 SSE로 실시간 중계.
- 핵심 트릭: **"합성은 LLM, 결합은 코드"** + **"두 리스너로 저수준/고수준 이벤트 분리"** + **"도메인 검증이 1차 게이트키퍼"**.
- 핵심 한계: **토큰 단위 스트리밍 불가** — `create<T>()` 가 객체 단위 동기 호출이고, structured output / tool loop / 도메인 검증이 모두 *완성된 객체* 를 전제하기 때문. 트레이드오프는 **타입 안전성·검증 가능성** ↔ **즉시성·체감 지연**.
- 오류 처리는 **5층** (§7): Spring Retry → Action Retry Policy → 도메인 검증 → GOAP Replan → 명시적 `ReplanRequestedException`. 본 프로젝트엔 `assembleReport` 에 `actionRetryPolicy = FIRE_ONCE` 적용.
- 언어 포지셔닝: **클라이언트 도구는 TS/Rust** (Claude Code, Cursor — 즉시 시작·단일 바이너리), **연구 노트북은 Python** (Jupyter·ML 라이브러리), **엔터프라이즈 서버 에이전트는 JVM** (Spring Security/Data/Actuator/Resilience4j 통합) — 본 프로젝트는 마지막 카테고리.
