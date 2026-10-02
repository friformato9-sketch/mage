package org.mage.magezero.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.Counter;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.game.stack.StackObject;
import mage.players.Player;
import org.mage.magezero.GameLogRecorder;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Rebuilds an exact board state on the real XMage engine, replays scripted actions and reports the
 * resulting state, so rulings can be checked against the rules engine instead of an LLM's memory.
 * <p>
 * Scenario JSON (all fields optional unless noted):
 * <pre>
 * {
 *   "active_player": "A",                       // who takes turn 1
 *   "strict": false,                            // true: unanswered choices are errors instead of AI picks
 *   "players": {
 *     "A": {"life": 20,
 *           "battlefield": [{"name": "Goblin Guide", "count": 1, "tapped": false, "counters": {"P1P1": 1}}],
 *           "hand": ["Lightning Bolt"], "graveyard": [], "exile": [], "library": []},
 *     "B": {...}
 *   },
 *   "actions": [                                // executed in order; turn defaults to 1
 *     {"type": "cast", "player": "A", "card": "Lightning Bolt", "targets": ["Ocelot Pride"],
 *      "step": "PRECOMBAT_MAIN", "in_response_to": "Some Spell", "wait": true},
 *     {"type": "activate", "player": "A", "ability": "{T}, Sacrifice", "targets": []},
 *     {"type": "play_land", "player": "A", "card": "Mountain"},
 *     {"type": "attack", "player": "A", "attacker": "Goblin Guide", "defender": "B"},
 *     {"type": "block", "player": "B", "blocker": "Ocelot Pride", "attacker": "Goblin Guide"},
 *     {"type": "choice", "player": "A", "value": "Yes"},   // answers the next choose dialog ("X=3" for X)
 *     {"type": "target", "player": "A", "value": "Ocelot Pride"},
 *     {"type": "mode", "player": "A", "value": "1"},
 *     {"type": "wait", "player": "A", "step": "PRECOMBAT_MAIN"}  // let the stack resolve
 *   ],
 *   "stop_at": {"turn": 1, "step": "END_TURN"}   // default: last action's turn, END_TURN
 * }
 * </pre>
 * Players are "A"/"B" (or "PlayerA"/"PlayerB"). Libraries are padded with Wastes from the filler deck
 * (opening hands are Wastes too, reported as "filler_in_hand"): when the library matters (draws, reveals),
 * list the relevant cards under "library" — they go on top.
 */
public class ScenarioRunner extends CardTestPlayerBase {

    /** card the filler deck is made of; never part of a real scenario, so it is reported separately */
    public static final String FILLER_CARD = "Wastes";

    private final List<String> warnings = new ArrayList<>();

    public ScenarioRunner(String fillerDeckPath) {
        deckNameA = fillerDeckPath;
        deckNameB = fillerDeckPath;
    }

    public JsonObject run(JsonObject spec, Path outDir) throws Exception {
        reset();
        if (bool(spec, "strict", false)) {
            setStrictChooseMode(true);
        }
        if ("B".equals(playerKey(str(spec, "active_player", "A")))) {
            activePlayer = playerB;
        }

        JsonObject players = spec.has("players") ? spec.getAsJsonObject("players") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : players.entrySet()) {
            setupPlayer(player(e.getKey()), e.getValue().getAsJsonObject());
        }

        int lastTurn = 1;
        JsonArray actions = spec.has("actions") ? spec.getAsJsonArray("actions") : new JsonArray();
        for (JsonElement a : actions) {
            lastTurn = Math.max(lastTurn, addAction(a.getAsJsonObject()));
        }

        JsonObject stop = spec.has("stop_at") ? spec.getAsJsonObject("stop_at") : new JsonObject();
        setStopAt(integer(stop, "turn", lastTurn), step(str(stop, "step", "END_TURN")));

        Files.createDirectories(outDir);
        Path logFile = outDir.resolve("log.jsonl");
        Map<String, String> sides = new LinkedHashMap<>();
        sides.put(playerA.getName(), "A");
        sides.put(playerB.getName(), "B");
        GameLogRecorder recorder = new GameLogRecorder(currentGame, logFile, 1, 0, sides);

        JsonArray errors = new JsonArray();
        try {
            execute();
        } catch (AssertionError | Exception ex) {
            // unused commands mean the scripted action was not legal/possible in that situation
            errors.add(String.valueOf(ex.getMessage()));
        }
        String winner = playerA.hasWon() ? playerA.getName() : playerB.hasWon() ? playerB.getName() : null;
        recorder.finish(winner, winner == null ? "stopped" : "game_over");

        JsonObject result = new JsonObject();
        result.addProperty("ok", errors.size() == 0);
        result.add("errors", errors);
        JsonArray warn = new JsonArray();
        warnings.forEach(warn::add);
        result.add("warnings", warn);
        JsonObject stoppedAt = new JsonObject();
        stoppedAt.addProperty("turn", currentGame.getTurnNum());
        PhaseStep step = currentGame.getTurnStepType();
        stoppedAt.addProperty("step", step == null ? null : step.name());
        Player active = currentGame.getPlayer(currentGame.getActivePlayerId());
        stoppedAt.addProperty("active", active == null ? null : active.getName());
        result.add("stopped_at", stoppedAt);
        result.addProperty("winner", winner);
        JsonObject state = new JsonObject();
        state.add(playerA.getName(), playerState(currentGame, playerA));
        state.add(playerB.getName(), playerState(currentGame, playerB));
        result.add("players", state);
        result.add("stack", stackState(currentGame));
        JsonArray log = new JsonArray();
        for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) {
                log.add(JsonParser.parseString(line));
            }
        }
        result.add("log", log);
        return result;
    }

    // ---------------------------------------------------------------- setup

    private void setupPlayer(TestPlayer player, JsonObject p) {
        if (p.has("life")) {
            setLife(player, p.get("life").getAsInt());
        }
        if (p.has("battlefield")) {
            for (JsonElement el : p.getAsJsonArray("battlefield")) {
                JsonObject card = el.isJsonObject() ? el.getAsJsonObject() : named(el.getAsString());
                String name = str(card, "name", null);
                int count = integer(card, "count", 1);
                addCard(Zone.BATTLEFIELD, player, name, count, bool(card, "tapped", false));
                if (card.has("counters")) {
                    for (Map.Entry<String, JsonElement> c : card.getAsJsonObject("counters").entrySet()) {
                        CounterType type = counterType(c.getKey());
                        if (type == null) {
                            warnings.add("unknown counter type: " + c.getKey());
                            continue;
                        }
                        addCounters(1, PhaseStep.UPKEEP, player, name, type, c.getValue().getAsInt() * count);
                    }
                }
            }
        }
        addZone(player, p, "hand", Zone.HAND);
        addZone(player, p, "graveyard", Zone.GRAVEYARD);
        addZone(player, p, "exile", Zone.EXILED);
        addZone(player, p, "library", Zone.LIBRARY);
    }

    private void addZone(TestPlayer player, JsonObject p, String key, Zone zone) {
        if (!p.has(key)) {
            return;
        }
        for (JsonElement el : p.getAsJsonArray(key)) {
            JsonObject card = el.isJsonObject() ? el.getAsJsonObject() : named(el.getAsString());
            addCard(zone, player, str(card, "name", null), integer(card, "count", 1));
        }
    }

    /**
     * @return the turn the action happens on
     */
    private int addAction(JsonObject a) {
        String type = str(a, "type", "");
        TestPlayer player = player(str(a, "player", "A"));
        int turn = integer(a, "turn", 1);
        PhaseStep step = step(str(a, "step", "PRECOMBAT_MAIN"));
        List<String> targets = new ArrayList<>();
        if (a.has("targets")) {
            a.getAsJsonArray("targets").forEach(t -> targets.add(t.getAsString()));
        }
        switch (type) {
            case "cast": {
                String card = str(a, "card", null);
                String respondTo = str(a, "in_response_to", null);
                if (respondTo != null) {
                    castSpell(turn, step, player, card, targets.isEmpty() ? TestPlayer.NO_TARGET : joinTargets(targets), respondTo);
                } else if (targets.size() == 1 && isPlayer(targets.get(0))) {
                    castSpell(turn, step, player, card, player(targets.get(0)));
                } else if (!targets.isEmpty()) {
                    castSpell(turn, step, player, card, joinTargets(targets));
                } else {
                    castSpell(turn, step, player, card);
                }
                if (respondTo == null && bool(a, "wait", true)) {
                    waitStackResolved(turn, step, player);
                }
                break;
            }
            case "activate": {
                String ability = str(a, "ability", null);
                String respondTo = str(a, "in_response_to", null);
                if (respondTo != null) {
                    activateAbility(turn, step, player, ability, targets.isEmpty() ? TestPlayer.NO_TARGET : joinTargets(targets), respondTo);
                } else if (targets.size() == 1 && isPlayer(targets.get(0))) {
                    activateAbility(turn, step, player, ability, player(targets.get(0)));
                } else if (!targets.isEmpty()) {
                    activateAbility(turn, step, player, ability, targets.stream().map(this::targetName).toArray(String[]::new));
                } else {
                    activateAbility(turn, step, player, ability);
                }
                if (respondTo == null && bool(a, "wait", true)) {
                    waitStackResolved(turn, step, player);
                }
                break;
            }
            case "play_land":
                playLand(turn, step, player, str(a, "card", null));
                break;
            case "attack": {
                String defender = str(a, "defender", null);
                if (defender == null) {
                    attack(turn, player, str(a, "attacker", null));
                } else if (isPlayer(defender)) {
                    attack(turn, player, str(a, "attacker", null), player(defender));
                } else {
                    attack(turn, player, str(a, "attacker", null), defender);
                }
                break;
            }
            case "block":
                block(turn, player, str(a, "blocker", null), str(a, "attacker", null));
                break;
            case "choice": {
                JsonElement value = a.get("value");
                if (value.getAsJsonPrimitive().isBoolean()) {
                    setChoice(player, value.getAsBoolean());
                } else {
                    setChoice(player, value.getAsString());
                }
                break;
            }
            case "target": {
                String value = str(a, "value", null);
                if (isPlayer(value)) {
                    addTarget(player, player(value));
                } else {
                    addTarget(player, value);
                }
                break;
            }
            case "mode":
                setModeChoice(player, str(a, "value", null));
                break;
            case "wait":
                waitStackResolved(turn, step, player);
                break;
            default:
                throw new IllegalArgumentException("unknown action type: " + type);
        }
        return turn;
    }

    private String joinTargets(List<String> targets) {
        StringJoiner joiner = new StringJoiner("^");
        targets.forEach(t -> joiner.add(targetName(t)));
        return joiner.toString();
    }

    private String targetName(String target) {
        return isPlayer(target) ? player(target).getName() : target;
    }

    // ---------------------------------------------------------------- state dump

    private static JsonObject playerState(Game game, Player player) {
        JsonObject p = new JsonObject();
        p.addProperty("life", player.getLife());
        p.addProperty("poison", player.getCountersCount(CounterType.POISON));
        p.addProperty("lost", player.hasLost());
        JsonArray battlefield = new JsonArray();
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(player.getId())) {
            JsonObject o = new JsonObject();
            o.addProperty("name", perm.getName());
            o.addProperty("tapped", perm.isTapped());
            o.addProperty("token", perm instanceof PermanentToken);
            if (perm.isCreature(game)) {
                o.addProperty("power", perm.getPower().getValue());
                o.addProperty("toughness", perm.getToughness().getValue());
                o.addProperty("damage", perm.getDamage());
            }
            JsonObject counters = new JsonObject();
            for (Counter c : perm.getCounters(game).values()) {
                counters.addProperty(c.getName(), c.getCount());
            }
            if (counters.size() > 0) {
                o.add("counters", counters);
            }
            if (!perm.getAttachments().isEmpty()) {
                JsonArray attached = new JsonArray();
                for (UUID id : perm.getAttachments()) {
                    Permanent att = game.getPermanent(id);
                    if (att != null) attached.add(att.getName());
                }
                o.add("attachments", attached);
            }
            battlefield.add(o);
        }
        p.add("battlefield", battlefield);
        // the opening hand is drawn from the Wastes filler: report it apart from the scenario's own cards
        List<Card> hand = new ArrayList<>();
        int filler = 0;
        for (Card c : player.getHand().getCards(game)) {
            if (c.getName().equals(FILLER_CARD)) filler++;
            else hand.add(c);
        }
        p.add("hand", names(hand));
        p.addProperty("filler_in_hand", filler);
        p.add("graveyard", names(player.getGraveyard().getCards(game)));
        List<Card> exiled = new ArrayList<>();
        for (Card c : game.getExile().getAllCards(game)) {
            if (c.getOwnerId().equals(player.getId())) exiled.add(c);
        }
        p.add("exile", names(exiled));
        p.addProperty("library_count", player.getLibrary().size());
        return p;
    }

    private static JsonArray stackState(Game game) {
        JsonArray stack = new JsonArray();
        for (StackObject so : game.getStack()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", so.getName());
            Player controller = game.getPlayer(so.getControllerId());
            o.addProperty("controller", controller == null ? null : controller.getName());
            o.addProperty("text", so.getStackAbility() == null ? null : so.getStackAbility().getRule());
            stack.add(o);
        }
        return stack;
    }

    private static JsonArray names(Collection<Card> cards) {
        JsonArray arr = new JsonArray();
        cards.forEach(c -> arr.add(c.getName()));
        return arr;
    }

    // ---------------------------------------------------------------- helpers

    private static String playerKey(String s) {
        if (s == null) return null;
        String k = s.trim().toUpperCase(Locale.ROOT);
        if (k.equals("A") || k.equals("PLAYERA")) return "A";
        if (k.equals("B") || k.equals("PLAYERB")) return "B";
        return null;
    }

    private static boolean isPlayer(String s) {
        return playerKey(s) != null;
    }

    private TestPlayer player(String s) {
        String k = playerKey(s);
        if (k == null) throw new IllegalArgumentException("unknown player: " + s + " (use A or B)");
        return k.equals("A") ? playerA : playerB;
    }

    private static PhaseStep step(String s) {
        return PhaseStep.valueOf(s.trim().toUpperCase(Locale.ROOT));
    }

    private static CounterType counterType(String s) {
        CounterType byName = CounterType.findByName(s);
        if (byName != null) return byName;
        try {
            return CounterType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject named(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        return o;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int integer(JsonObject o, String key, int def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
    }

    public static JsonObject readSpec(Path file) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
