package com.ecosystem.plugin.reward;

import org.json.JSONObject;

/**
 * Dynamically loaded class to calculate the reward during algorithm loop.
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
        return params;
    }

}
