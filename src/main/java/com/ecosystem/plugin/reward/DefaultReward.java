package com.ecosystem.plugin.reward;

import org.json.JSONObject;

/**
 * Dynamically loaded class to calculate the reward during algorithm loop.
 * Each iteration of the algorithm loop will call this class to calculate the reward.
 * Access to all parameters is available via the params JSONObject.
 */
public class DefaultReward extends RewardSuper {

    public DefaultReward () {
        // Constructor
    }

    public static void reward() {
        // Dynamic loader
    }

    /**
     * This method is called to calculate the reward.
     * @param params Runtime json object with all execution path variables.
     * @return Updated params
     */
    public static JSONObject reward(JSONObject params) {

        double reward = 1.0;
        double learning_reward = 1.0;

        JSONObject rewards = params.optJSONObject("rewards");
        if (rewards == null)
            rewards = new JSONObject();

        rewards.put("reward", reward);
        rewards.put("learning_reward", learning_reward);
        params.put("rewards", rewards);
        return params;
    }

}
