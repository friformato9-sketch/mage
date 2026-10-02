package org.mage.magezero;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.abilities.Ability;
import mage.abilities.PlayLandAbility;
import mage.abilities.common.PassAbility;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.RootCandidate;
import mage.player.ai.SimulationNode2;
import mage.player.ai.score.GameStateEvaluator2;
import mage.players.Player;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Minimax player that runs its key decisions past an advisor (an LLM behind a local HTTP service,
 * see the bot's advisor.py) before playing them.
 * <p>
 * Key decision = its own main phase, a response to anything on the stack, or a combat declaration,
 * with at least two real alternatives (not only land plays or passing). The advisor gets the state, the engine's top candidates with their scores and
 * predicted outcome, and the opponent's likely hand from their unseen cards. It approves the engine's
 * pick, or rejects it with evaluation weights (my life, opponent life, board, cards in hand): the
 * search then runs again with those weights and the engine plays its new best line. The advisor never
 * picks a move itself, so it cannot make an illegal or unsearched play.
 * <p>
 * Simulation copies of this player are plain ComputerPlayer7s, so searches never call the advisor.
 */
public class ComputerPlayerAdvised extends ComputerPlayer8 {

    private static final Logger logger = Logger.getLogger(ComputerPlayerAdvised.class);
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_CANDIDATES = 4;
    private static final int MAX_LIKELY_CARDS = 10;
    private static final double MIN_WEIGHT = 0.25, MAX_WEIGHT = 3.0;

    private final String advisorUrl;
    private final transient OkHttpClient http;

    public ComputerPlayerAdvised(String name, RangeOfInfluence range, int skill, String advisorUrl) {
        super(name, range, skill);
        this.advisorUrl = advisorUrl;
        // a local LLM can take a while; a failed call just keeps the engine's pick
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(6, TimeUnit.MINUTES)
                .build();
    }

    @Override
    protected SimulationNode2 chooseRootChild(Game game, SimulationNode2 best) {
        if (!isKeyDecision(game)) {
            return best;
        }
        List<SimulationNode2> candidates = candidates(best);
        if (candidates.size() < 2 || candidates.stream().allMatch(ComputerPlayerAdvised::isRoutine)) {
            return best;
        }
        JsonObject advice = ask(request(game, candidates));
        if (advice == null) {
            return best;
        }
        String backend = str(advice, "backend", "advisor");
        String reason = shorten(str(advice, "reason", ""), 140);
        String bestPlay = describe(best);
        if (!advice.has("approve") || advice.get("approve").getAsBoolean() || !advice.has("weights")) {
            inform(game, getName() + " consults " + backend + ": keeps " + bestPlay + (reason.isEmpty() ? "" : " — " + reason));
            return best;
        }
        JsonObject w = advice.getAsJsonObject("weights");
        GameStateEvaluator2.setWeights(playerId, new GameStateEvaluator2.Weights(
                weight(w, "my_life"), weight(w, "opponent_life"), weight(w, "board"), weight(w, "cards_in_hand")));
        try {
            Game sim = createSimulation(game);
            SimulationNode2.resetCount();
            root = new SimulationNode2(null, sim, maxDepth, playerId);
            addActionsTimed();
            SimulationNode2 pick = root != null && root.getChildren() != null && !root.getChildren().isEmpty()
                    ? root.getChildren().get(0) : best;
            inform(game, getName() + " consults " + backend + ": rejects " + bestPlay
                    + (reason.isEmpty() ? "" : " — " + reason) + " → plays " + describe(pick));
            return pick;
        } finally {
            GameStateEvaluator2.clearWeights(playerId);
        }
    }

    /**
     * Own main phase with an empty stack, any response to something on the stack (e.g. Consign to
     * Memory on an opponent's cast trigger), and combat declarations in either turn (e.g. giving
     * Psychic Frog flying to block). The minimax only acts in main phases and combat declarations.
     */
    private boolean isKeyDecision(Game game) {
        PhaseStep step = game.getTurnStepType();
        boolean mainPhase = step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN;
        boolean combat = step == PhaseStep.DECLARE_ATTACKERS || step == PhaseStep.DECLARE_BLOCKERS;
        return !game.getStack().isEmpty()
                || combat
                || (mainPhase && game.isActivePlayer(playerId));
    }

    /**
     * The engine's pick first, then the other root actions by score, one per distinct play.
     */
    private List<SimulationNode2> candidates(SimulationNode2 best) {
        List<SimulationNode2> out = new ArrayList<>();
        out.add(best);
        Set<String> seen = new HashSet<>();
        seen.add(describe(best));
        List<RootCandidate> others = new ArrayList<>(rootCandidates);
        others.sort(Comparator.comparingInt((RootCandidate c) -> c.score).reversed());
        for (RootCandidate c : others) {
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
            c.node.setScore(c.score);
            if (c.node != best && seen.add(describe(c.node))) {
                out.add(c.node);
            }
        }
        return out;
    }

    /**
     * Passing, playing a land or activating a land's ability (e.g. cracking a fetchland): not worth a
     * consult on their own. A decision is consulted only if some candidate casts or activates something else.
     */
    private static boolean isRoutine(SimulationNode2 node) {
        Game g = node.getGame();
        return node.getAbilities().stream().allMatch(a -> {
            if (a instanceof PlayLandAbility || a instanceof PassAbility) {
                return true;
            }
            Permanent source = g.getPermanent(a.getSourceId());
            return source != null && source.isLand(g);
        });
    }

    // ---------------------------------------------------------------- request

    private JsonObject request(Game game, List<SimulationNode2> candidates) {
        Player me = game.getPlayer(playerId);
        Player opponent = game.getPlayer(game.getOpponents(playerId, false).stream().findFirst().orElse(null));
        JsonObject r = new JsonObject();
        r.addProperty("game_id", game.getId().toString());
        r.addProperty("player", getName());
        r.addProperty("turn", game.getTurnNum());
        r.addProperty("step", String.valueOf(game.getTurnStepType()));
        Player active = game.getPlayer(game.getActivePlayerId());
        r.addProperty("active_player", active == null ? null : active.getName());
        JsonArray stack = new JsonArray();
        for (StackObject so : game.getStack()) {
            Player controller = game.getPlayer(so.getControllerId());
            stack.add((controller == null ? "?" : controller.getName()) + ": " + so.getName()
                    + (so.getStackAbility() == null ? "" : " — " + shorten(so.getStackAbility().getRule(), 160)));
        }
        r.add("stack_top_first", stack);
        JsonObject decks = new JsonObject();
        decks.add("mine", deckNames(me, game));
        decks.add("opponent", deckNames(opponent, game));
        r.add("decks", decks);
        r.add("me", playerState(me, game, true));
        JsonObject opp = playerState(opponent, game, false);
        opp.add("likely_in_hand", likelyHand(opponent, game));
        r.add("opponent", opp);
        JsonArray list = new JsonArray();
        for (int i = 0; i < candidates.size(); i++) {
            SimulationNode2 node = candidates.get(i);
            JsonObject c = new JsonObject();
            c.addProperty("index", i + 1);
            c.addProperty("play", describe(node));
            c.addProperty("engine_score", node.getScore());
            c.addProperty("after", predicted(node, me.getId(), opponent.getId()));
            list.add(c);
        }
        r.add("candidates", list);
        return r;
    }

    private static JsonArray deckNames(Player player, Game game) {
        Set<String> names = new TreeSet<>();
        player.getLibrary().getCards(game).forEach(c -> names.add(c.getName()));
        player.getHand().getCards(game).forEach(c -> names.add(c.getName()));
        player.getGraveyard().getCards(game).forEach(c -> names.add(c.getName()));
        game.getExile().getAllCards(game).stream().filter(c -> c.isOwnedBy(player.getId())).forEach(c -> names.add(c.getName()));
        game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isOwnedBy(player.getId()) && !p.isCopy())
                .forEach(p -> names.add(p.getName()));
        JsonArray out = new JsonArray();
        names.forEach(out::add);
        return out;
    }

    private static JsonObject playerState(Player player, Game game, boolean showHand) {
        JsonObject s = new JsonObject();
        s.addProperty("name", player.getName());
        s.addProperty("life", player.getLife());
        s.addProperty("hand_count", player.getHand().size());
        if (showHand) {
            s.addProperty("hand", player.getHand().getCards(game).stream().map(Card::getName).collect(Collectors.joining(", ")));
        }
        s.addProperty("battlefield", game.getBattlefield().getAllActivePermanents(player.getId()).stream()
                .map(p -> permanent(p, game)).collect(Collectors.joining(", ")));
        s.addProperty("graveyard", player.getGraveyard().getCards(game).stream().map(Card::getName).collect(Collectors.joining(", ")));
        s.addProperty("library_count", player.getLibrary().size());
        return s;
    }

    private static String permanent(Permanent p, Game game) {
        StringBuilder sb = new StringBuilder(p.getName());
        if (p.isCreature(game)) {
            sb.append(' ').append(p.getPower().getValue()).append('/').append(p.getToughness().getValue());
            if (p.hasSummoningSickness()) {
                sb.append(" summoning-sick");
            }
        }
        if (p.isTapped()) {
            sb.append(" tapped");
        }
        return sb.toString();
    }

    /**
     * What a player could hold, from their unseen cards (library + hand) as a human opponent sees it:
     * P(at least one copy in a hand of h cards) = 1 - C(U-k, h) / C(U, h).
     */
    private static JsonArray likelyHand(Player player, Game game) {
        Map<String, Integer> unseen = new HashMap<>();
        player.getLibrary().getCards(game).forEach(c -> unseen.merge(c.getName(), 1, Integer::sum));
        player.getHand().getCards(game).forEach(c -> unseen.merge(c.getName(), 1, Integer::sum));
        int pool = unseen.values().stream().mapToInt(Integer::intValue).sum();
        int hand = player.getHand().size();
        List<Map.Entry<String, Double>> probs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : unseen.entrySet()) {
            double none = 1.0;
            for (int i = 0; i < hand; i++) {
                none *= Math.max(0, pool - e.getValue() - i) / (double) Math.max(1, pool - i);
            }
            probs.add(new AbstractMap.SimpleEntry<>(e.getKey(), 1 - none));
        }
        probs.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Double> e : probs.subList(0, Math.min(MAX_LIKELY_CARDS, probs.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("card", e.getKey());
            o.addProperty("probability", Math.round(e.getValue() * 100) / 100.0);
            out.add(o);
        }
        return out;
    }

    private String describe(SimulationNode2 node) {
        Game g = node.getGame();
        String play = node.getAbilities().stream()
                .map(a -> getAbilityAndSourceInfo(g, a, true))
                .collect(Collectors.joining(", then "));
        return play.isEmpty() ? "pass" : play;
    }

    /**
     * The simulated game right after the candidate: life totals and boards.
     */
    private static String predicted(SimulationNode2 node, UUID me, UUID opponent) {
        Game g = node.getGame();
        Player p = g.getPlayer(me), o = g.getPlayer(opponent);
        if (p == null || o == null) {
            return "";
        }
        String myExile = g.getExile().getAllCards(g).stream().filter(c -> c.isOwnedBy(me))
                .map(Card::getName).collect(Collectors.joining(", "));
        String stack = g.getStack().stream().map(StackObject::getName).collect(Collectors.joining(", "));
        // graveyard and exile matter for graveyard decks: e.g. exiling Atraxa to pay a cost ruins Goryo's Vengeance
        return "life " + p.getLife() + " vs " + o.getLife()
                + "; my board: " + g.getBattlefield().getAllActivePermanents(me).stream().map(x -> permanent(x, g)).collect(Collectors.joining(", "))
                + "; opponent board: " + g.getBattlefield().getAllActivePermanents(opponent).stream().map(x -> permanent(x, g)).collect(Collectors.joining(", "))
                + "; my hand " + p.getHand().size() + " cards"
                + "; my graveyard: " + p.getGraveyard().getCards(g).stream().map(Card::getName).collect(Collectors.joining(", "))
                + (myExile.isEmpty() ? "" : "; my exile: " + myExile)
                + (stack.isEmpty() ? "" : "; stack: " + stack);
    }

    // ---------------------------------------------------------------- transport

    private JsonObject ask(JsonObject request) {
        Request req = new Request.Builder().url(advisorUrl)
                .post(RequestBody.create(GSON.toJson(request), JSON)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                logger.warn("advisor answered HTTP " + resp.code());
                return null;
            }
            return JsonParser.parseString(resp.body().string()).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("advisor unreachable, keeping the engine's pick: " + e.getMessage());
            return null;
        }
    }

    private static void inform(Game game, String message) {
        if (!game.isSimulation()) {
            game.informPlayers(message);
        }
    }

    private static double weight(JsonObject w, String key) {
        double v = w.has(key) && !w.get(key).isJsonNull() ? w.get(key).getAsDouble() : 1.0;
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, v));
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static String shorten(String s, int max) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }
}
