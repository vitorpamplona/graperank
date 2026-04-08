# GrapeRank

This is a simple implementation of the GrapeRank algorithm in Kotlin.

GrapeRank is a **subjective web-of-trust ranking algorithm**. Given a social graph where users follow, mute, or report each other, GrapeRank computes a **trust score** for every user **from the perspective of a specific observer**. Scores are not global rankings; they are personal. Two observers with different social neighborhoods will compute different scores for the same user.

---

## Table of Contents

1. [Core Concepts](#core-concepts)
2. [The Score Formula](#the-score-formula)
3. [Version 1 - Stateless Iterative](#version-1--stateless-iterative-v1iterative)
4. [Version 2 - Stateful Full Recomputation](#version-2--stateful-full-recomputation-v2stateful)
5. [Version 3 - Incremental Forward Propagation](#version-3--incremental-forward-propagation-v3incremental)
6. [Comparison Table](#comparison-table)
7. [Signal Decay Illustration](#signal-decay-over-hops)

---

## Core Concepts

### The Social Graph

Users form a directed graph. An edge from A to B can be:

| Relationship | Rating | Confidence (direct) | Confidence (indirect) | Meaning                     |
|:-------------|:------:|:--------------------:|:---------------------:|:----------------------------|
| **Follow**   |  1.0   |        0.08          |         0.04          | Endorsement / trust         |
| **Mute**     |  0.0   |        0.40          |         0.40          | Neutral filter / ignore     |
| **Report**   | -0.1   |        0.40          |         0.40          | Distrust / negative signal  |

- **Rating** measures the *quality* of the relationship (positive = trust, zero = neutral, negative = distrust).
- **Confidence** measures how much weight this edge carries. Direct endorsements from the observer itself carry higher confidence (0.08) than transitive endorsements from other users (0.04). Mutes and reports always carry high confidence (0.40) because negative signals are treated as strong.

### Observer-Centric Scoring

The observer always has score **1.0** (full self-trust). Every other user's score is computed by aggregating incoming edges, weighted by the source's own score from the observer's perspective. This means trust flows outward from the observer and decays with every hop.

```
                    Observer (score = 1.0)
                        |
                      follows
                        |
                        v
                    User A (score ~0.054)
                        |
                      follows
                        |
                        v
                    User B (score ~0.0015)
                        |
                      follows
                        |
                        v
                    User C (score ~0.00004)
                        |
                      follows
                        |
                        v
                    User D (score ~0.0) -- below threshold, effectively zero
```

---

## The Score Formula

For a target user `T` seen from observer `O`:

```
For each incoming edge e into T:
    source_score = O.scores[e.source]          // how much O trusts the source
    weight_e     = e.confidence(O) * source_score
    rating_e     = weight_e * e.rating()

sumOfWeights     = SUM(weight_e)       for all edges
sumOfWeightRating = SUM(rating_e)      for all edges

                                        sumOfWeightRating
score(T) = max( weightToConfidence(W) * ─────────────────, 0 )
                                           sumOfWeights

where W = sumOfWeights
```

### The `weightToConfidence` Function

This converts accumulated weight into a confidence value between 0 and 1:

```
weightToConfidence(w, rigor = 0.5) = 1 - e^(-w * -ln(rigor))
                                   = 1 - e^(-w * ln(2))
                                   = 1 - 2^(-w)
```

This is a **saturating exponential curve**. As total weight grows, confidence approaches 1.0 but never reaches it. With the default rigor of 0.5, this simplifies to `1 - 2^(-w)`.

```
  confidence
  1.0 |                          ___________________
      |                    ____/
      |                ___/
  0.5 |           ____/.............................. <- rigor threshold (w=1)
      |       ___/
      |    __/
      |  _/
  0.0 |_/____________________________________________ weight
      0         1         2         3         4
```

The **rigor** parameter controls how quickly confidence saturates. Higher rigor (closer to 1) means you need more evidence (weight) to reach the same confidence level. At rigor=0.5, a weight of 1.0 gives confidence of 0.5.

### Why `max(..., 0)`?

If a user is mostly reported (negative ratings), `sumOfWeightRating / sumOfWeights` becomes negative. The `max(..., 0)` floor ensures scores never go below zero -- a user can be untrusted but not "negatively trusted."

---

## Version 1 -- Stateless Iterative (`v1Iterative`)

> **File:** `v1Iterative/GrapeRank.kt` (68 lines)

### Design Philosophy

The simplest possible implementation. A pure function that takes the graph and an observer, returns a map of scores. No state is retained between calls.

### How It Works

```
┌────────────────────────────────────────────────────────────────┐
│  grapeRank(users, observer) -> Map<User, Double>               │
│                                                                │
│  1. Initialize: scores = { observer -> 1.0 }                  │
│                                                                │
│  2. REPEAT:                                                    │
│     ┌────────────────────────────────────────────────────────┐ │
│     │  changed = false                                       │ │
│     │  FOR EACH user in users (skip observer):               │ │
│     │      newScore = computeScore(user, scores)             │ │
│     │      IF |newScore - oldScore| > 0.0001:                │ │
│     │          changed = true                                │ │
│     │      scores[user] = newScore                           │ │
│     └────────────────────────────────────────────────────────┘ │
│     UNTIL changed == false                                     │
│                                                                │
│  3. Return scores                                              │
└────────────────────────────────────────────────────────────────┘
```

### Algorithm Classification

- **Pattern:** Iterative relaxation (similar to Bellman-Ford or PageRank power iteration)
- **Convergence:** The algorithm halts when no score changes by more than `0.0001` in a full pass over all users.
- **Time per call:** `O(R * (V + E))` where `R` = number of rounds to converge, `V` = users, `E` = edges.
- **Space:** `O(V)` for the scores map.

### Walkthrough

```
Round 0 (initialization):
    scores = { Observer: 1.0 }

Round 1:
    Evaluate Alice:  Observer --follows--> Alice
        weight = 0.08 * 1.0 = 0.08
        score  = weightToConfidence(0.08) * (0.08 * 1.0) / 0.08
               = 0.054 * 1.0 = 0.054
        scores = { Observer: 1.0, Alice: 0.054 }

    Evaluate Bob:    Alice --follows--> Bob
        weight = 0.04 * 0.054 = 0.00216
        score  = weightToConfidence(0.00216) * 1.0 = 0.0015
        scores = { Observer: 1.0, Alice: 0.054, Bob: 0.0015 }

Round 2:
    Re-evaluate Alice: same inputs -> same score (no change)
    Re-evaluate Bob:   same inputs -> same score (no change)
    -> converged, stop
```

### Strengths and Weaknesses

| Strengths | Weaknesses |
|:----------|:-----------|
| Easy to understand and verify | Must recompute everything from scratch each call |
| Pure function, no side effects | No way to do incremental updates |
| Minimal code (68 lines) | Not suitable for dynamic graphs |

---

## Version 2 -- Stateful Full Recomputation (`v2Stateful`)

> **File:** `v2Stateful/StatefulGrapeRank.kt` (117 lines)

### Design Philosophy

Introduces **persistent state** so scores survive between graph mutations. Each observer's scores are stored inside the `User` object. When the graph changes (a new follow/mute/report), all observers' scores are recomputed. The core iteration loop is identical to V1 -- the key difference is *where* scores live and *when* recomputation is triggered.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Graph                                                      │
│  ├── users: [User, User, User, ...]                         │
│  └── observers: [User, User, ...]                           │
│                                                             │
│  User                                                       │
│  ├── incomingEdges: [Follow(src=A), Report(src=B), ...]     │
│  └── scores: { Observer1 -> 0.5, Observer2 -> 0.3, ... }   │
│              ↑                                              │
│              Scores are stored per-observer inside User     │
└─────────────────────────────────────────────────────────────┘
```

### How It Works

```
  User A follows User B
       │
       v
  B.incomingEdges.add(Follow(A))
       │
       v
  graph.computeScoresFrom(A)
       │
       v
  ┌──────────────────────────────┐
  │  FOR EACH observer:          │
  │    updateGrapevine(          │
  │      all users, observer     │
  │    )                         │
  │                              │
  │  updateGrapevine is the same │
  │  iterative relaxation loop   │
  │  as V1, storing results in   │
  │  observer.scores[user]       │
  └──────────────────────────────┘
```

### Key Difference from V1

The `computeScoresFrom` method is called **automatically** whenever an edge is added. It loops over **all** registered observers and runs the full iterative relaxation for each one:

```kotlin
fun computeScoresFrom(user: User) {
    observers.forEach { observer ->
        updateGrapevine(users, observer)  // full recomputation
    }
}
```

This uses Kotlin **context receivers** (`context(graph: Graph)`) so that calling `A follows B` automatically triggers the recomputation in the enclosing graph.

### Algorithm Classification

- **Pattern:** Reactive full recomputation (event-driven trigger, same iterative core)
- **Trigger:** Any edge mutation triggers a full pass over all users for all observers.
- **Time per update:** `O(|observers| * R * (V + E))`
- **Space:** `O(V * |observers|)` for stored scores.

### Strengths and Weaknesses

| Strengths | Weaknesses |
|:----------|:-----------|
| Scores are always up-to-date after any mutation | Every edge addition triggers full recomputation |
| Object-oriented, natural API (`A follows B`) | Wasted work: one new edge recomputes ALL users |
| Supports multiple observers concurrently | Time cost scales with number of observers |

---

## Version 3 -- Incremental Forward Propagation (`v3Incremental`)

> **File:** `v3Incremental/IncrementalGrapeRank.kt` (162 lines)

### Design Philosophy

The key insight: when a single edge changes, you don't need to recompute the entire graph. You only need to update the **affected subgraph** -- the nodes reachable downstream from the change. V3 achieves this through **forward propagation** using a breadth-first traversal of outgoing edges.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  User                                                       │
│  ├── inEdges:  [Follow(src=A), Mute(src=B), ...]           │
│  ├── outEdges: {C, D, E}    <── NEW: bidirectional index   │
│  └── scores:   { Observer1 -> 0.5, ... }                   │
└─────────────────────────────────────────────────────────────┘

       inEdges (who trusts me)        outEdges (who I vouch for)
             ┌───┐                          ┌───┐
        A ──>│   │                          │   │──> C
        B ──>│ T │                          │ T │──> D
        C ──>│   │                          │   │──> E
             └───┘                          └───┘
```

The addition of `outEdges` is what enables forward propagation. When T's score changes, we know exactly which nodes (C, D, E) might be affected, because T is an input to their score calculation.

### How It Works -- The Propagation

```
  User A follows User B
       │
       ├── A.outEdges.add(B)           ← index the reverse direction
       ├── B.inEdges.add(Follow(A))
       │
       v
  graph.computeScoresFrom(B)          ← start at the changed node
       │
       v
  ┌──────────────────────────────────────────────────────────────┐
  │  FOR EACH observer:                                          │
  │    updateScores(target=B, observer)                          │
  │         │                                                    │
  │         v                                                    │
  │    WHILE observer.newScore(B) changed:                       │
  │         processOutEdges(B, observer)                         │
  │              │                                               │
  │              v                                               │
  │         BFS over outgoing edges:                             │
  │         ┌──────────────────────────────────────────────────┐ │
  │         │  queue = B.outEdges     (nodes to recompute)     │ │
  │         │  WHILE queue is not empty:                       │ │
  │         │      next = queue.removeFirst()                  │ │
  │         │      IF observer.newScore(next) changed:         │ │
  │         │          queue.addAll(next.outEdges)             │ │
  │         │                ↑                                 │ │
  │         │                Only expands if score changed!    │ │
  │         └──────────────────────────────────────────────────┘ │
  └──────────────────────────────────────────────────────────────┘
```

### The BFS Propagation Visualized

Imagine this graph, where Observer follows A, A follows B, and B follows C:

```
  Step 1: Observer follows A (new edge)
  ════════════════════════════════════════════

         Observer ──follow──> A ──follow──> B ──follow──> C

  Start at A (the target of the new edge).

  Step 2: Recompute A's score
  ════════════════════════════════════════════

         Observer ──follow──> [A] ──follow──> B ──follow──> C
                               ↑
                          score changed!
                          (was 0, now 0.054)

  A's score changed, so add A.outEdges = {B} to the queue.

  Step 3: Process queue -> recompute B
  ════════════════════════════════════════════

         Observer ──follow──> A ──follow──> [B] ──follow──> C
                                             ↑
                                        score changed!
                                        (was 0, now 0.0015)

  B's score changed, so add B.outEdges = {C} to the queue.

  Step 4: Process queue -> recompute C
  ════════════════════════════════════════════

         Observer ──follow──> A ──follow──> B ──follow──> [C]
                                                           ↑
                                                      score changed!
                                                      (was 0, now 0.00004)

  C's score changed, but C.outEdges = {} (empty). Queue is empty. Done!
```

### Handling Cycles

The use of a `mutableSetOf<User>` as the queue is critical for cycle handling. Sets automatically deduplicate, so if propagation reaches a node that's already in the queue, it won't be added again. And the convergence check (`|new - old| > 0.0001`) ensures that cycles eventually stop propagating when scores stabilize.

```
         ┌──────────────────────┐
         │                      │
         v                      │
         A ──follow──> B ──follow──> C
                                │
                           follow back to A

  Round 1: Recompute A -> changed -> add B
            Recompute B -> changed -> add C
            Recompute C -> changed -> add A (cycle!)
  Round 2: Recompute A -> |delta| < 0.0001 -> NOT changed -> stop
```

### Algorithm Classification

- **Pattern:** Event-driven incremental BFS propagation
- **Trigger:** Edge addition triggers update starting at the affected node only.
- **Best case:** `O(|affected subgraph|)` -- only nodes whose scores actually change are visited.
- **Worst case:** `O(R * (V + E))` same as V1/V2 (if entire graph is affected), but typically much better.
- **Space:** `O(V * |observers|) + O(E)` extra for outgoing edges index.

### Strengths and Weaknesses

| Strengths | Weaknesses |
|:----------|:-----------|
| Only recomputes affected nodes | Higher memory: stores both in and out edges |
| BFS avoids stack overflow (vs. recursive DFS) | More complex implementation (162 lines vs 68) |
| Ideal for dynamic graphs with frequent updates | Set-based queue has overhead for small graphs |
| Propagation stops early when scores converge | |

---

## Comparison Table

```
                    V1 Iterative     V2 Stateful       V3 Incremental
                ─────────────    ─────────────────   ──────────────────
  State            None            Per-observer       Per-observer
                                    scores             scores + out-edges

  Trigger       Manual call       Auto on edge add   Auto on edge add

  Recompute      Entire graph     Entire graph       Affected subgraph
  Scope           (all users)      (all users)        only (BFS)

  Edge Index     Incoming only    Incoming only      Incoming + Outgoing

  Update Cost    O(R*(V+E))       O(|obs|*R*(V+E))   O(|obs|*|affected|)
  per call

  Space          O(V)             O(V*|obs|)         O(V*|obs| + E)

  API Style      Pure function    OOP + context      OOP + context
                                   receivers          receivers

  Best For       One-shot         Multi-observer,    Large dynamic graphs,
                  analysis         small graphs       frequent updates

  Lines of       68               117                162
  Code
```

---

## Signal Decay Over Hops

One of the most important behaviors of GrapeRank is how trust decays over distance. The test data from `LinearGraph` shows this clearly:

```
  Observer ──> +1hop ──> +2hops ──> +3hops ──> +4hops

  Score:  1.0    0.054     0.0015    0.00004    ~0.0
          │       │          │          │          │
          │       │          │          │          └─ Below convergence
          │       │          │          └──────────── 37x decay
          │       │          └─────────────────────── 36x decay
          │       └────────────────────────────────── 18.5x decay
          └────────────────────────────────────────── Observer (anchor)
```

This aggressive decay is by design. After ~4 hops, the signal effectively vanishes. This means GrapeRank naturally limits the influence of distant, unknown users while giving meaningful weight to your local trust neighborhood -- exactly the right behavior for a web-of-trust system.

```
  Trust
  1.0 |*
      |
      |
      |
  0.05|  *
      |
      |    *
  0.0 |________*________*___________________________
       0   1   2   3   4   5   6   7  (hops)
```

---

## Summary

The three versions represent a natural evolution of the same core algorithm:

1. **V1 Iterative** is a **textbook implementation** -- a pure function, easy to reason about, perfect for understanding the math.
2. **V2 Stateful** adds **statefulness and reactivity** -- scores are stored and automatically recomputed on mutations, but the inner loop is still brute-force over the entire graph.
3. **V3 Incremental** adds **surgical precision** -- by indexing outgoing edges and using BFS propagation, it only touches nodes whose scores actually need to change, making it dramatically more efficient for large, dynamic social graphs.

All three produce **identical results** for the same inputs (verified by the test suite), but they trade off simplicity for performance as the graph grows.

# MIT License

<pre>
Copyright (c) 2023 Vitor Pamplona

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
</pre>
