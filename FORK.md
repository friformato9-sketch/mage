# MageZero engine fork — Devourer of Truth

This XMage fork backs the [Devourer of Truth](https://github.com/friformato9-sketch/mtg-judge-devourer-of-truth)
Telegram MTG judge bot. It starts from [WillWroble/mage](https://github.com/WillWroble/mage), the engine of
[MageZero](https://github.com/WillWroble/MageZero) (v0.2.0), and adds full-game simulations, rules
scenarios for engine-checked rulings, and stronger minimax play. The Python side (training pipeline,
configs, decks) is [friformato9-sketch/MageZero](https://github.com/friformato9-sketch/MageZero), see its
`FORK.md`.

Work branch: `feature/telegram-sim`. Remotes: `origin` = this fork, `upstream` = WillWroble/mage,
`xmage` = magefree/mage (for new cards).

## What the fork adds

| Area | Where | What |
| --- | --- | --- |
| Simulate mode | `Mage.MageZero`: `Config`, `MageZeroMain`, `ParallelDataGenerator` | `mode: simulate` plays games without writing training data and writes `summary.json` (wins, turns, end reason) |
| Deck validation | `DeckCheck`, `MageZeroMain` | With `format: modern` both decks are checked first; `validation.json` lists unknown cards and format errors, exit code 2 when invalid |
| Game log | `GameLogRecorder` | `logging.game_log_dir` → `game_N.jsonl`, one event per line, flushed as the game runs (schema below) |
| Rules scenarios | `Mage.MageZero.Scenario` (`ScenarioRunner`, `ScenarioMain`), `release/mz-scenario.*` | Rebuild an exact board, replay scripted actions on the real engine, dump the resulting state (`result.json`) |
| Advised minimax player | `ComputerPlayerAdvised`, `Config` (`advisor.url`); hooks in `ComputerPlayer6` (`rootCandidates`), `ComputerPlayer7` (`chooseRootChild`), `RootCandidate` | Key decisions are sent to an LLM advisor service, which approves the pick or returns evaluation weights for a new search |
| Position evaluation | `Mage.Player.AI`: `GameStateEvaluator2` | Cards in hand are worth 250 (lands 5), positions are scored after this turn's cleanup, and weights can be set per player |
| Launchers | `Mage.MageZero/release/mz-xmage.*` | `MZ_HEAP` caps the JVM heap (default 24g) |
| New cards | merge of magefree/mage 1.4.61 | `Mage.Player.AI.MA` → `MAD` rename, `activatorId` removal, soulbond `getPairedMOR()` in `StateEncoder` |

## Build and deploy

Requirements: JDK 21 (Temurin) and Maven 3.9.

**Build in a folder your IDE does not have open.** The VS Code Java extension compiles opened Maven projects
into the same `target/classes` and can wipe them halfway through a Maven build, producing jars with missing
classes (e.g. `ClassNotFoundException: mage.cards.a.ArclightPhoenix`). Use a worktree, and always build clean
after a merge (incremental builds keep stale classes):

```bash
git worktree add --detach ../mage-build feature/telegram-sim   # once; later: git -C ../mage-build checkout --detach <commit>
cd ../mage-build
mvn clean install -DskipTests -T 1C -pl Mage.MageZero,Mage.Tests,Mage.MageZero.Scenario -am
```

Deploy into an XMage distribution folder (MageZero's `xmage/`, or the bot's own copy set by
`MAGEZERO_XMAGE_DIR`): unzip `lib/` from `Mage.MageZero/target/mage-magezero.zip`, copy
`Mage.MageZero/release/mz-*.bat|.sh`, `Mage.MageZero.Scenario/target/mage-magezero-scenario.jar` and
`Mage.MageZero.Scenario/target/scenario-lib/*.jar` into `lib/`, then delete `db/` so the card database is
rebuilt with the new cards (first run takes about 30 s). Windows locks the jars of a running engine, which
is why the bot uses its own copy while MageZero trains.

## Simulations

A MageZero game config with `mode: simulate`, `format: modern` and `logging.game_log_dir` plays the games
and logs them. Each player block may add `advisor: {url: http://127.0.0.1:8765/advise}` (minimax players
only); set `gameplay.mulligans_enabled: false` (default true) to skip mulligans. Example: `configs/simulate-modern.yml` in the MageZero repo;
the bot writes its own config per game (`simulation.py`).

### Game log events (`game_N.jsonl`)

Every event except `game_start`/`game_end` carries `turn` and `active` (the active player's name).
Cards are `{name, set, number, token, types}` so consumers can fetch the exact printing.

| Event | Fields |
| --- | --- |
| `game_start` | `game`, `seed`, `players` (player name → deck name) |
| `turn_start` | `life` (player → life) |
| `log` | `step`, `text` (the engine's log line, HTML removed), `cards` referenced by the line |
| `life` | `player`, `from`, `to` |
| `state` | `step`, `players` (name → `life`, `poison`, `library_count`, `hand`, `graveyard`, `exile`, `battlefield` with `tapped`, `attacking`, `blocking`, `power`, `toughness`, `damage`, `summoning_sick`, `counters`, `attached_to`), `stack` top first (`label`, `kind` spell/ability, `controller`, `text`, `targets`; `name`/`set`/`number` = the source card). Written after a log line, only when something changed |
| `turn_end` | `life`, `battlefield` (name → permanents), `zones` (name → `hand`, `library`, `graveyard`, `poison`, `hand_cards`) |
| `game_end` | `winner`, `result` (win/draw), `reason`, `turns`, `life` |

## Rules scenarios

```bash
xmage/mz-scenario.bat <scenario.json> <out-dir>     # → <out-dir>/result.json
```

The JSON format is documented at the top of `ScenarioRunner.java`. In short: `players.A/B` with `life`,
`battlefield` (name, count, tapped, counters), `hand`, `graveyard`, `exile`, `library` (on top of a
Wastes filler); `actions` (`cast`, `activate`, `play_land`, `attack`, `block`, `choice`, `target`, `mode`,
`wait`) executed in order, each inheriting the previous action's turn and step unless it sets its own; and
an optional `stop_at`. Examples: `Mage.MageZero.Scenario/examples/` (first strike block, Giant Growth in
response, Bolt without red mana, Tarmogoyf vs Bolt). The bot turns every rules question into such a
scenario (`engine.py`).

## Advised minimax player

`ComputerPlayerAdvised` is a minimax player (`ComputerPlayer7`) that consults an HTTP advisor on key
decisions: its own main phase, any response to the stack, and combat declarations, when there are at least
two real alternatives (land plays and land abilities alone don't count). Simulation copies are plain
`ComputerPlayer7`s, so the search never calls the advisor.

- **Request** (POST JSON): `game_id`, `player`, `turn`, `step`, `active_player`, `stack_top_first`,
  `decks` (`mine`, `opponent`: distinct card names), `me` / `opponent` (life, hand, battlefield, graveyard,
  library count; the opponent gets `likely_in_hand` with hypergeometric odds from their unseen cards), and up
  to 4 `candidates` (`index`, `play`, `engine_score`, `after` = predicted position).
- **Response**: `approve`; when false, `preferred` and `weights` (`my_life`, `opponent_life`, `board`,
  `cards_in_hand`, clamped to 0.25–3.0). The engine searches again with those weights and plays its new best
  line, so the advisor can never force an illegal or unsearched move. Errors or timeouts (connect 5 s,
  read 5 min) keep the engine's pick.
- The bot's service is `advisor.py` (Claude or local qwen, game plans per Modern archetype).

## Position evaluation

`GameStateEvaluator2` changes, each switchable by a system property for A/B benchmarks (`mz bench` in the
MageZero repo):

| Property | Default | Meaning |
| --- | --- | --- |
| `magezero.eval.handCardScore` | 250 | Value of a nonland card in hand (XMage's original: 5) |
| `magezero.eval.handLandScore` | 5 | Value of a land in hand: a land only matters once played, and at 250 the AI kept tapped or painful lands in hand |
| `magezero.eval.afterCleanup` | true | Score the position after this turn's cleanup, so "until end of turn" boosts don't count as lasting value |

`setWeights` / `clearWeights` scale each component of the score per player; the advised player uses them.

## Updating with new XMage cards

New cards exist only once XMage implements them in Java, so updating means merging upstream XMage:

```bash
git remote add xmage https://github.com/magefree/mage.git   # once
git fetch xmage master
git checkout -b merge/xmage-<version> feature/telegram-sim
git merge xmage/master
```

Then build clean in the worktree, deploy, delete `db/`, run the scenario examples and a simulated game,
and fast-forward `feature/telegram-sim`.
