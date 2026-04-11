# GrapeRank V3 — Neo4j Stored Procedures

## What this is

This package implements the V3 GrapeRank algorithm as a Neo4j stored procedure
plugin. Once the JAR is deployed to your Neo4j instance, every follow / mute /
report event can trigger an incremental score recomputation **inside the
database**, with zero network overhead and JVM-speed math.

Scores are persisted as relationships in the graph itself:

```
(:GrapeRankObserver)-[:GRAPERANK_SCORE {value: 0.255}]->(:NostrUser)
```

## Building the JAR

```bash
./gradlew shadowJar
```

This produces:

```
build/libs/graperank-1.0-SNAPSHOT-neo4j-plugin.jar
```

The shadow plugin bundles the Kotlin stdlib but **excludes** all Neo4j
libraries (they're provided by your Neo4j instance at runtime).

> **Important:** match the `neo4jVersion` in `build.gradle.kts` to your
> production Neo4j version. The current default is `5.26.0`. If you're running
> Neo4j 5.19, change it — the procedure API is not guaranteed to be
> binary-compatible across minor versions.

## Deploying to Neo4j

1. Copy the JAR to your Neo4j plugins directory:

   ```bash
   cp build/libs/graperank-*-neo4j-plugin.jar /var/lib/neo4j/plugins/
   ```

2. Add to `neo4j.conf` (if not already allowing unsigned procedures):

   ```
   dbms.security.procedures.unrestricted=graperank.*
   ```

3. Restart Neo4j.

4. Verify:

   ```cypher
   CALL dbms.procedures() YIELD name
   WHERE name STARTS WITH 'graperank'
   RETURN name
   ```

   You should see `graperank.v3.onFollow`, `graperank.v3.registerObserver`, etc.

## Registering observers

Before scores are computed for anyone, you must register which users are
**observers** — i.e. the users whose point-of-view you care about. This is a
one-time operation per user:

```cypher
CALL graperank.v3.registerObserver('pubkey_hex')
```

This walks the observer's entire reachable trust graph and creates all initial
`GRAPERANK_SCORE` relationships. It can take a few seconds for well-connected
users; after that, incremental updates are near-instant.

## Integrating with `process_strfry_event.py`

The goal is **minimal changes** to the existing Python code. Each existing
function already runs a Cypher query that mutates the graph. You just append
procedure calls to those same queries.

### Kind 1984 — Reports (simplest)

Reports only add edges, never remove them. You can call `onReport` directly
inside the existing Cypher using a subquery:

**Before:**

```python
cypher = """
MERGE (pub:NostrUser {pubkey: $publisher})
WITH pub, $reported_pubkeys AS rps
UNWIND rps AS rp
    MERGE (reported:NostrUser {pubkey: rp})
    MERGE (pub)-[:REPORTS]->(reported)
"""
```

**After:**

```python
cypher = """
MERGE (pub:NostrUser {pubkey: $publisher})
WITH pub, $reported_pubkeys AS rps
UNWIND rps AS rp
    MERGE (reported:NostrUser {pubkey: rp})
    MERGE (pub)-[:REPORTS]->(reported)
    WITH pub, reported
    CALL graperank.v3.onReport(pub.pubkey, reported.pubkey)
    YIELD observer, target, score
RETURN count(*) AS updated
"""
```

This works because reports are additive — every `MERGE` either matches an
existing edge or creates a new one, and `onReport` is idempotent (if the score
didn't change, it's a no-op internally).

### Kind 3 — Follows (needs diff)

Follow lists are **replacements**: the event contains the full current list,
and the Cypher deletes edges that are no longer present. We can't just call
`onFollow` for every pubkey in the list because most already exist — and we
also need `onUnfollow` for the removed ones.

The inline-Cypher approach doesn't work cleanly here because you need the diff
**before** the DELETE runs but the procedure calls **after** the edges exist /
are gone. So: two small queries in the same transaction.

**Before:**

```python
async def process_event_kind_3(session, event):
    publisher = event["pubkey"]
    followed_pubkeys = [tag[1] for tag in event.get("tags", []) if tag[0] == "p"]

    if not followed_pubkeys:
        cypher = """
        MATCH (pub:NostrUser {pubkey: $publisher})-[r:FOLLOWS]->()
        DELETE r
        """
        await session.run(cypher, publisher=publisher)
        return

    cypher = """
    MERGE (pub:NostrUser {pubkey: $publisher})
    WITH pub, $followed_pubkeys AS fps
    UNWIND fps AS fp
        MERGE (f:NostrUser {pubkey: fp})
        MERGE (pub)-[:FOLLOWS]->(f)
    WITH pub, fps
    OPTIONAL MATCH (pub)-[r:FOLLOWS]->(oldF)
    WHERE NOT oldF.pubkey IN fps
    DELETE r"""

    await session.run(cypher, publisher=publisher, followed_pubkeys=followed_pubkeys)
```

**After:**

```python
async def process_event_kind_3(session, event):
    publisher = event["pubkey"]
    followed_pubkeys = [tag[1] for tag in event.get("tags", []) if tag[0] == "p"]

    if not followed_pubkeys:
        # All follows removed — capture who was followed, delete, then score
        cypher = """
        MATCH (pub:NostrUser {pubkey: $publisher})-[r:FOLLOWS]->(old)
        WITH pub, collect(old.pubkey) AS removed
        MATCH (pub)-[r:FOLLOWS]->()
        DELETE r
        RETURN removed
        """
        result = await session.run(cypher, publisher=publisher)
        record = await result.single()
        if record and record["removed"]:
            for rp in record["removed"]:
                await session.run(
                    "CALL graperank.v3.onUnfollow($src, $tgt)",
                    src=publisher, tgt=rp
                )
        return

    # --- Step 1: diff + mutate in one query ---
    cypher = """
    MERGE (pub:NostrUser {pubkey: $publisher})

    // Snapshot old follows before mutation
    WITH pub
    OPTIONAL MATCH (pub)-[:FOLLOWS]->(old)
    WITH pub, collect(old.pubkey) AS oldFollows

    // Merge new follows
    WITH pub, oldFollows, $followed_pubkeys AS fps
    UNWIND fps AS fp
        MERGE (f:NostrUser {pubkey: fp})
        MERGE (pub)-[:FOLLOWS]->(f)

    // Delete removed follows
    WITH pub, oldFollows, $followed_pubkeys AS fps
    OPTIONAL MATCH (pub)-[r:FOLLOWS]->(gone)
    WHERE NOT gone.pubkey IN fps
    DELETE r

    // Return the diff
    WITH pub, oldFollows, $followed_pubkeys AS fps
    RETURN [fp IN fps WHERE NOT fp IN oldFollows] AS added,
           [fp IN oldFollows WHERE NOT fp IN fps] AS removed
    """
    result = await session.run(cypher, publisher=publisher, followed_pubkeys=followed_pubkeys)
    record = await result.single()

    # --- Step 2: score only the changes ---
    for pubkey in (record["added"] or []):
        await session.run(
            "CALL graperank.v3.onFollow($src, $tgt)",
            src=publisher, tgt=pubkey
        )
    for pubkey in (record["removed"] or []):
        await session.run(
            "CALL graperank.v3.onUnfollow($src, $tgt)",
            src=publisher, tgt=pubkey
        )
```

**Why two steps?** `onFollow` must run **after** the FOLLOWS edge exists in
the graph (it reads incoming edges of the target to compute the score).
`onUnfollow` must run **after** the edge is deleted (so the recomputation
excludes it). The diff query handles both mutations and returns what changed;
the procedure calls then score only the delta. For a typical follow-list event
with 500 follows where 2 changed, this means 2 procedure calls — not 500.

### Kind 10000 — Mutes (same pattern as follows)

Mute lists are also full replacements, so the same diff pattern applies:

**After:**

```python
async def process_event_kind_10000(session, event):
    publisher = event["pubkey"]
    muted_pubkeys = [tag[1] for tag in event.get("tags", []) if tag[0] == "p"]

    if not muted_pubkeys:
        cypher = """
        MATCH (pub:NostrUser {pubkey: $publisher})-[r:MUTES]->(old)
        WITH pub, collect(old.pubkey) AS removed
        MATCH (pub)-[r:MUTES]->()
        DELETE r
        RETURN removed
        """
        result = await session.run(cypher, publisher=publisher)
        record = await result.single()
        if record and record["removed"]:
            for rp in record["removed"]:
                await session.run(
                    "CALL graperank.v3.onUnmute($src, $tgt)",
                    src=publisher, tgt=rp
                )
        return

    cypher = """
    MERGE (pub:NostrUser {pubkey: $publisher})

    WITH pub
    OPTIONAL MATCH (pub)-[:MUTES]->(old)
    WITH pub, collect(old.pubkey) AS oldMutes

    WITH pub, oldMutes, $muted_pubkeys AS fps
    UNWIND fps AS fp
        MERGE (f:NostrUser {pubkey: fp})
        MERGE (pub)-[:MUTES]->(f)

    WITH pub, oldMutes, $muted_pubkeys AS fps
    OPTIONAL MATCH (pub)-[r:MUTES]->(gone)
    WHERE NOT gone.pubkey IN fps
    DELETE r

    WITH pub, oldMutes, $muted_pubkeys AS fps
    RETURN [fp IN fps WHERE NOT fp IN oldMutes] AS added,
           [fp IN oldMutes WHERE NOT fp IN fps] AS removed
    """
    result = await session.run(cypher, publisher=publisher, muted_pubkeys=muted_pubkeys)
    record = await result.single()

    for pubkey in (record["added"] or []):
        await session.run(
            "CALL graperank.v3.onMute($src, $tgt)",
            src=publisher, tgt=pubkey
        )
    for pubkey in (record["removed"] or []):
        await session.run(
            "CALL graperank.v3.onUnmute($src, $tgt)",
            src=publisher, tgt=pubkey
        )
```

## Why it works this way

### Why stored procedures instead of Cypher?

The V3 algorithm is an **iterative BFS with convergence** — it walks outgoing
edges from the changed node, recomputing scores until nothing moves by more
than 0.0001. Cypher has no loop construct, so you can't express "keep walking
until stable." A stored procedure runs inside Neo4j's JVM with direct access
to the graph store, giving us both the iteration and zero serialisation
overhead.

### Why call procedures separately instead of inline?

For **reports** (additive only), inline works perfectly — `CALL ... YIELD`
inside the `UNWIND`.

For **follows and mutes** (full list replacement), inline doesn't work because:

1. `onFollow(src, tgt)` needs the FOLLOWS edge to **already exist** when it
   runs (it reads the target's incoming edges to compute the weighted score).
2. `onUnfollow(src, tgt)` needs the FOLLOWS edge to **already be deleted**
   (so the recomputation excludes it).
3. The existing Cypher query creates AND deletes in one pass, so there's no
   clean point where "new edges exist but old edges are still present."

Splitting into diff-then-score gives the procedures the correct graph state.

### Why the observer-affected check matters

When `alice follows bob`, only observers who already have a score for `alice`
are affected (because they're the only ones who can "see" her in their trust
network). An observer with no path to `alice` gets zero contribution from her
follow of `bob`. The `findAffectedObservers` check skips them entirely, turning
an O(observers) operation into O(affected_observers) — typically 1-5 instead of
hundreds.

### Score storage model

```
(:GrapeRankObserver:NostrUser)-[:GRAPERANK_SCORE {value: Double}]->(:NostrUser)
```

- The `:GrapeRankObserver` label marks registered observers (fast filter)
- Each score is one relationship; querying all scores for an observer is a
  single relationship traversal
- Scores below 1e-10 are deleted rather than stored (keeps the graph clean)

## Available procedures

| Procedure | Mode | Description |
|---|---|---|
| `graperank.v3.registerObserver(pubkey)` | WRITE | One-time setup; computes full trust graph for this observer |
| `graperank.v3.onFollow(source, target)` | WRITE | Call **after** a FOLLOWS edge is created |
| `graperank.v3.onUnfollow(source, target)` | WRITE | Call **after** a FOLLOWS edge is deleted |
| `graperank.v3.onMute(source, target)` | WRITE | Call **after** a MUTES edge is created |
| `graperank.v3.onUnmute(source, target)` | WRITE | Call **after** a MUTES edge is deleted |
| `graperank.v3.onReport(source, target)` | WRITE | Call **after** a REPORTS edge is created |
| `graperank.v3.getScores(observer)` | READ | Returns all `{observer, target, score}` rows |

All WRITE procedures return `Stream<{observer, target, score}>` — the list of
scores that actually changed. You can `YIELD` these columns or just consume
them.
