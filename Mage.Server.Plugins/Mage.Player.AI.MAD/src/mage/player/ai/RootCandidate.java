package mage.player.ai;

import mage.abilities.Ability;

/**
 * MageZero: one action the minimax considered at the root of its search, with the node it led to
 * (the simulated game after the action) and the score the search gave it.
 */
public class RootCandidate {

    public final Ability action;
    public final SimulationNode2 node;
    public final int score;

    public RootCandidate(Ability action, SimulationNode2 node, int score) {
        this.action = action;
        this.node = node;
        this.score = score;
    }
}
