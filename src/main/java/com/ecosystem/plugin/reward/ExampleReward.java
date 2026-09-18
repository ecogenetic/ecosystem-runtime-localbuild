package com.ecosystem.plugin.reward;

import org.json.JSONObject;

/**
 * Template reward plugin. Copy this class, replace the placeholder cost / category / state
 * lookups, and set {@code plugin.reward} to the new class name.
 * Must put a {@code rewards} object on {@code params} with {@code reward} (score-time
 * multiplier) and {@code learning_reward} (learning weight). See README "Reward Plugins".
 */
public class ExampleReward extends RewardSuper {

    public ExampleReward () {
        // Constructor
    }

    public static void reward() {
        // dynamic loader
    }

    /**
     *
     * @return params
     */
    public static JSONObject reward(JSONObject params) {

        double reward = 0.0;

        /** Obtain these values from offer matrix, feature store or corpora */
        double cost = 0;
        double category = 0;
        int state = 0;

        if (cost > category && state == 1) {
            reward = 10;
        } else if (cost == category && state == 1) {
            reward = 8;
        } else if (cost < category && state == 1) {
            reward = 0;
        } else if (cost > category && state == 0) {
            reward = -10;
        } else if (cost == category && state == 0) {
            reward = -10;
        } else if (cost < category && state == 0) {
            reward = -10;
        } else {
            reward = 0;
        }

        JSONObject rewards = params.optJSONObject("rewards");
        if (rewards == null)
            rewards = new JSONObject();
        rewards.put("reward", reward);
        rewards.put("learning_reward", 1.0);
        params.put("rewards", rewards);
        return params;
    }

}
