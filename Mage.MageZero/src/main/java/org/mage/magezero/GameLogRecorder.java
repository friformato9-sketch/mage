package org.mage.magezero;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.events.Listener;
import mage.game.events.TableEvent;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records the human-readable game log of a single (non-simulated) game as JSON Lines, one event per line,
 * flushed immediately so an external consumer (e.g. the Telegram bot) can stream the game turn by turn.
 * <p>
 * Events: game_start, turn_start, log, life, turn_end, game_end. Every card referenced by a log line is
 * resolved to its printing (set code + collector number) so consumers can fetch the exact card image.
 * <p>
 * Hooks into {@link Game#addTableEventListener}, which only fires for the real game: AI simulation copies
 * get a fresh, listener-less TableEventSource, so search noise never reaches the log.
 */
public class GameLogRecorder implements Listener<TableEvent> {

    private static final Logger logger = Logger.getLogger(GameLogRecorder.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Pattern OBJECT_ID = Pattern.compile("object_id='([0-9a-fA-F-]{36})'");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SHORT_ID = Pattern.compile("\\s*\\[[0-9a-f]{3}]");

    private final transient Game game;
    private final transient BufferedWriter out;
    private final Map<String, String> deckNames;
    private final Map<UUID, Integer> lastLife = new HashMap<>();
    private int currentTurn = -1;
    private UUID currentActive = null;
    private boolean finished = false;

    /**
     * @param deckNames player name -> deck name, reported in game_start
     */
    public GameLogRecorder(Game game, Path file, int gameIndex, long seed, Map<String, String> deckNames) throws IOException {
        this.game = game;
        this.deckNames = deckNames;
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        JsonObject start = new JsonObject();
        start.addProperty("type", "game_start");
        start.addProperty("game", gameIndex);
        start.addProperty("seed", seed);
        JsonObject players = new JsonObject();
        deckNames.forEach(players::addProperty);
        start.add("players", players);
        write(start);
        game.addTableEventListener(this);
    }

    @Override
    public void event(TableEvent event) {
        if (finished || event.getEventType() != TableEvent.EventType.INFO || event.getMessage() == null) {
            return;
        }
        try {
            onMessage(event.getMessage());
        } catch (Exception e) {
            // never let logging break the game
            logger.warn("game log recorder failed on message: " + event.getMessage(), e);
        }
    }

    private void onMessage(String html) {
        checkTurnBoundary();
        String text = cleanText(html);
        if (text.isEmpty()) {
            return;
        }
        JsonObject log = turnEvent("log");
        PhaseStep step = game.getTurnStepType();
        log.addProperty("step", step == null ? null : step.name());
        log.addProperty("text", text);
        log.add("cards", referencedCards(html));
        write(log);
        if (currentTurn >= 0) {
            writeLifeChanges();
        }
    }

    /**
     * Turns are detected lazily from game state: the engine announces every new turn with a log line
     * (Turn.logStartOfTurn), which is the first message we see after the turn number changes.
     */
    private void checkTurnBoundary() {
        int turn = game.getTurnNum();
        UUID active = game.getActivePlayerId();
        if (active == null) {
            // pregame (shuffles, opening hands, mulligans): logged as turn 0, no turn boundaries yet
            return;
        }
        if (turn == currentTurn && Objects.equals(active, currentActive)) {
            return;
        }
        if (currentTurn >= 0) {
            write(turnEnd());
        }
        currentTurn = turn;
        currentActive = active;
        JsonObject start = turnEvent("turn_start");
        start.add("life", lifeTotals());
        write(start);
        rememberLife();
    }

    /**
     * Closes the log with the final board and the outcome. Safe to call once; later calls are ignored.
     */
    public synchronized void finish(String winner, String reason) {
        if (finished) {
            return;
        }
        if (currentTurn >= 0) {
            writeLifeChanges();
            write(turnEnd());
        }
        JsonObject end = new JsonObject();
        end.addProperty("type", "game_end");
        end.addProperty("winner", winner);
        end.addProperty("result", winner == null ? "draw" : "win");
        end.addProperty("reason", reason);
        end.addProperty("turns", game.getTurnNum());
        end.add("life", lifeTotals());
        write(end);
        finished = true;
        try {
            out.close();
        } catch (IOException e) {
            logger.warn("failed to close game log", e);
        }
    }

    // ---------------------------------------------------------------- snapshots

    private JsonObject turnEvent(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("turn", Math.max(currentTurn, 0));
        Player active = currentActive == null ? null : game.getPlayer(currentActive);
        o.addProperty("active", active == null ? null : active.getName());
        return o;
    }

    private JsonObject turnEnd() {
        JsonObject end = turnEvent("turn_end");
        end.add("life", lifeTotals());
        JsonObject battlefield = new JsonObject();
        JsonObject zones = new JsonObject();
        for (Player player : game.getPlayers().values()) {
            JsonArray permanents = new JsonArray();
            for (Permanent p : game.getBattlefield().getAllActivePermanents(player.getId())) {
                JsonObject card = cardRef(p);
                card.addProperty("tapped", p.isTapped());
                if (p.isCreature(game)) {
                    card.addProperty("power", p.getPower().getValue());
                    card.addProperty("toughness", p.getToughness().getValue());
                }
                permanents.add(card);
            }
            battlefield.add(player.getName(), permanents);
            JsonObject counts = new JsonObject();
            counts.addProperty("hand", player.getHand().size());
            counts.addProperty("library", player.getLibrary().size());
            counts.addProperty("graveyard", player.getGraveyard().size());
            counts.addProperty("poison", player.getCountersCount(CounterType.POISON));
            zones.add(player.getName(), counts);
        }
        end.add("battlefield", battlefield);
        end.add("zones", zones);
        return end;
    }

    private JsonObject lifeTotals() {
        JsonObject life = new JsonObject();
        for (Player player : game.getPlayers().values()) {
            life.addProperty(player.getName(), player.getLife());
        }
        return life;
    }

    private void rememberLife() {
        for (Player player : game.getPlayers().values()) {
            lastLife.put(player.getId(), player.getLife());
        }
    }

    private void writeLifeChanges() {
        for (Player player : game.getPlayers().values()) {
            Integer before = lastLife.get(player.getId());
            int now = player.getLife();
            if (before != null && before != now) {
                JsonObject life = turnEvent("life");
                life.addProperty("player", player.getName());
                life.addProperty("from", before);
                life.addProperty("to", now);
                write(life);
            }
            lastLife.put(player.getId(), now);
        }
    }

    // ---------------------------------------------------------------- card references

    private JsonArray referencedCards(String html) {
        JsonArray cards = new JsonArray();
        Set<UUID> seen = new HashSet<>();
        Matcher m = OBJECT_ID.matcher(html);
        while (m.find()) {
            UUID id;
            try {
                id = UUID.fromString(m.group(1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            MageObject object = resolve(id);
            if (object != null) {
                cards.add(cardRef(object));
            }
        }
        return cards;
    }

    private MageObject resolve(UUID id) {
        Permanent permanent = game.getPermanentOrLKIBattlefield(id);
        if (permanent != null) {
            return permanent;
        }
        Card card = game.getCard(id);
        if (card != null) {
            return card;
        }
        return game.getObject(id);
    }

    private static JsonObject cardRef(MageObject object) {
        JsonObject ref = new JsonObject();
        ref.addProperty("name", object.getName());
        ref.addProperty("set", object.getExpansionSetCode());
        ref.addProperty("number", object.getCardNumber());
        ref.addProperty("token", object instanceof PermanentToken);
        return ref;
    }

    static String cleanText(String html) {
        String text = HTML_TAG.matcher(html.replace("<br>", " ")).replaceAll("");
        text = SHORT_ID.matcher(text).replaceAll("");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        return text.replaceAll("\\s+", " ").trim();
    }

    private void write(JsonObject event) {
        try {
            out.write(GSON.toJson(event));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            logger.warn("failed to write game log event", e);
        }
    }
}
