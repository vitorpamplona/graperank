# GrapeRank

This is a simple implementation of the GrapeRank algorithm in Kotlin.

GrapeRank is a **subjective web-of-trust ranking algorithm**. Given a social graph where users follow, mute, or report each other, GrapeRank computes a **trust score** for every user **from the perspective of a specific observer**. Scores are not global rankings; they are personal. Two observers with different social neighborhoods will compute different scores for the same user.

---

## Table of Contents

1. [Core Concepts](#core-concepts)
2. [The Score Formula](#the-score-formula)
3. [Version 1 - Full Graph Sweep](#version-1--full-graph-sweep-v1fullsweep)
4. [Version 2 - Reactive Full Sweep](#version-2--reactive-full-sweep-v2reactivesweep)
5. [Version 3 - Targeted BFS Propagation](#version-3--targeted-bfs-propagation-v3targetedbfs)
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

## Version 1 -- Full Graph Sweep (`v1FullSweep`)

> **File:** `v1FullSweep/GrapeRank.kt` (68 lines)

### The Walk

V1 walks the graph the simplest way possible: a **blind linear scan** over every user in the graph, repeated until nothing changes. It doesn't know or care about graph topology -- it just sweeps the entire user list, re-evaluating each node on every pass.

```
  Graph with 50,000 users, Observer only follows Alice:

  Observer ──follow──> Alice          Bob  Carol  David  ...  User₅₀₀₀₀
                                       ↑    ↑      ↑              ↑
                                       │    │      │              │
  Round 1:  ─────────────── visits ALL 50,000 users ──────────────
  Round 2:  ─────────────── visits ALL 50,000 users again ────────
            (even though only Alice's score changed in round 1)
```

Each round visits every node and recomputes its score by scanning its incoming edges. If any score changed, the entire sweep runs again. This is **iterative relaxation**, the same pattern as PageRank's power iteration or Bellman-Ford's edge relaxation.

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

### Why It's Wasteful

The sweep is **topology-blind**. In a graph with 50,000 users where the observer only connects to a handful, V1 still visits all 50,000 on every round -- including the vast majority whose scores will remain zero. It has no way to skip irrelevant nodes.

### Complexity

- **Time per call:** `O(rounds * (users + edges))` -- each round scans all users and their edges.
- **Space:** `O(users)` for the scores map.
- Rounds are typically small (2-4) because trust decays fast, but the `users` factor hurts on large graphs with sparse connectivity.

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

---

## Version 2 -- Reactive Full Sweep (`v2ReactiveSweep`)

> **File:** `v2ReactiveSweep/ReactiveSweepGrapeRank.kt` (117 lines)

### The Walk

V2 uses the **exact same full-sweep walk** as V1 -- iterating over every user in the graph each round until convergence. The traversal logic is identical. What changes is *when* it runs: every time an edge is added (`A follows B`, `A reports B`, etc.), the full sweep is triggered automatically for all observers.

```
  Adding a single edge in a 50,000-user graph:

  A follows B
       │
       v
  ┌──────────────────────────────────────────────────────┐
  │  FOR EACH observer:                                  │
  │    Sweep ALL 50,000 users (round 1)                  │
  │    Sweep ALL 50,000 users (round 2)                  │
  │    ...until convergence                              │
  └──────────────────────────────────────────────────────┘

  One new edge → entire graph re-walked for every observer.
```

### Why It's Still Wasteful

The core problem is the same as V1: the walk doesn't know *which* part of the graph was affected by the new edge. Adding a single follow between two users at the edge of the graph still triggers a complete re-scan of all 50,000 nodes. The sweep is structurally incapable of skipping unaffected regions.

```kotlin
fun computeScoresFrom(user: User) {
    observers.forEach { observer ->
        sweepAllNodes(users, observer)  // full sweep of ALL users
    }
}
```

### Complexity

- **Time per edge addition:** `O(observers * rounds * (users + edges))` -- same full sweep as V1, but multiplied by observer count and triggered on every mutation.
- **Space:** `O(users * observers)` for stored scores.

On a graph with 50,000 users and 3 observers, adding one edge walks `3 * rounds * 50,000` nodes. This is the key bottleneck that V3 solves.

---

## Version 3 -- Targeted BFS Propagation (`v3TargetedBFS`)

> **File:** `v3TargetedBFS/TargetedBFSGrapeRank.kt` (162 lines)

### The Walk

V3 fundamentally changes the traversal strategy. Instead of blindly sweeping the entire graph, it starts at the **changed node** and walks **only forward** through outgoing edges, using breadth-first search. It stops expanding a path the moment a node's score doesn't change -- meaning the rest of that branch is unaffected and doesn't need visiting.

```
  Same 50,000-user graph, adding one edge:

  A follows B
       │
       v
  Start at B, recompute B's score.
  B's score changed → follow B's outEdges → {C, D}
  Recompute C → changed → follow C's outEdges → {E}
  Recompute D → NOT changed → STOP this path
  Recompute E → NOT changed → STOP
  Done. Visited 4 nodes instead of 50,000.
```

This requires each user to maintain an **outgoing edge index** (`outEdges`), which V1 and V2 don't have. This index answers the question: "if my score changes, whose scores might be affected?" -- enabling the targeted walk.

```
       inEdges (who trusts me)        outEdges (who I vouch for)
             ┌───┐                          ┌───┐
        A ──>│   │                          │   │──> C
        B ──>│ T │                          │ T │──> D
        C ──>│   │                          │   │──> E
             └───┘                          └───┘
```

### The BFS Walk Step by Step

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
  │         propagateForward(B, observer)                         │
  │              │                                               │
  │              v                                               │
  │         BFS over outgoing edges:                             │
  │         ┌──────────────────────────────────────────────────┐ │
  │         │  queue = B.outEdges     (nodes to recompute)     │ │
  │         │  WHILE queue is not empty:                       │ │
  │         │      next = queue.removeFirst()                  │ │
  │         │      IF observer.newScore(next) changed:         │ │
  │         │          queue.addAll(next.outEdges)             │ │
  │         │      ELSE:                                       │ │
  │         │          (stop expanding this path)              │ │
  │         └──────────────────────────────────────────────────┘ │
  └──────────────────────────────────────────────────────────────┘
```

### Visualized: V2 vs V3 on the Same Graph

```
  Graph:  Observer ──> A ──> B ──> C        D  E  F  ...  Z
                                             (disconnected users)

  V2 walk (full sweep):
  ┌─────────────────────────────────────────────────────────────┐
  │  Visit: Observer, A, B, C, D, E, F, G, H, ... Z            │
  │         ════════════════════════════════════════             │
  │         All nodes visited. Most are wasted work.            │
  └─────────────────────────────────────────────────────────────┘

  V3 walk (targeted BFS from change point):
  ┌─────────────────────────────────────────────────────────────┐
  │  Visit: A → B → C → (C has no outEdges, stop)              │
  │         ═════════                                           │
  │         3 nodes visited. D through Z never touched.         │
  └─────────────────────────────────────────────────────────────┘
```

### Handling Cycles

The BFS queue is a `mutableSetOf<User>` which naturally **deduplicates** -- if propagation loops back to a node already in the queue, it won't be added twice. The convergence threshold (`|delta| < 0.0001`) ensures cycles terminate when scores stabilize:

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

### Complexity

- **Best case:** `O(affected nodes)` -- only nodes whose scores actually change are visited. In a sparse graph where a new edge affects 5 nodes out of 50,000, only those 5 are walked.
- **Worst case:** `O(rounds * (users + edges))` -- same as V1/V2 if the entire graph is reachable and affected (e.g., a densely connected cluster). But this is rare.
- **Space:** `O(users * observers + edges)` -- extra memory for the outgoing edge index.

### The Tradeoff: Memory for Speed

V3 stores **both** incoming and outgoing edges per user, using more memory than V1/V2. This is a classic space-time tradeoff: the outgoing index is what makes the targeted walk possible, avoiding the blind full sweep.

---

## Comparison Table

```
                  V1 FullSweep       V2 ReactiveSweep    V3 TargetedBFS
                ────────────────   ────────────────────  ──────────────────
  Walk           Full sweep of      Full sweep of        Targeted BFS from
  Strategy       all users,         all users,           change point,
                 repeated until     repeated until       expanding only
                 convergence        convergence          nodes that changed

  Nodes          ALL nodes,         ALL nodes,           Only affected
  Visited        every round        every round          downstream nodes

  When It        Manual call        Auto on each         Auto on each
  Runs           (one-shot)         edge mutation         edge mutation

  Edge Index     Incoming only      Incoming only        Incoming + Outgoing

  Cost per       O(rounds *         O(observers *        O(observers *
  Update           (users+edges))     rounds *             affected nodes)
                                       (users+edges))

  Wasted Work    High: visits       Higher: same         Minimal: only
                 unaffected         full sweep but       visits nodes
                 nodes              per observer         whose scores
                                    per mutation         actually change

  Space          O(users)           O(users *            O(users *
                                      observers)           observers
                                                           + edges)

  Lines of       68                 117                  162
  Code
```

### Complexity at a Glance

Consider a graph with **50,000 users**, **3 observers**, and a single new follow edge that affects **5 downstream nodes**:

```
  V1:  rounds * 50,000 nodes scanned        (manual call, one observer)
  V2:  3 * rounds * 50,000 = ~300,000 nodes (triggered per observer)
  V3:  3 * 5 = 15 nodes                     (only the affected subgraph)
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

The three versions compute identical results but walk the graph very differently:

1. **V1 FullSweep** does a **blind full sweep** -- it scans every node in the graph on every round, regardless of which nodes are actually reachable or affected. Simple and correct, but `O(users)` work even when only a handful of nodes matter.
2. **V2 ReactiveSweep** uses the **same full sweep**, but triggers it reactively on every edge mutation for every observer. The walk itself is unchanged; the cost multiplies by observer count and mutation frequency.
3. **V3 TargetedBFS** replaces the full sweep with a **targeted BFS walk** from the change point outward. By maintaining an outgoing edge index, it follows only the paths where scores actually propagate, skipping the rest of the graph entirely. This trades extra memory for dramatically fewer nodes visited.

All three produce **identical results** for the same inputs (verified by the test suite). The progression from V1 to V3 is a textbook evolution: from brute-force scanning to topology-aware traversal.

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
