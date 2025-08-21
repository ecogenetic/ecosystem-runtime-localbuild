package com.ecosystem.plugin.reward;

import com.ecosystem.plugin.business.BusinessLogic;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONObject;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

/**
 * Dynamically loaded class to calculate the reward during algorithm loop.
 * Each iteration of the algorithm loop will call this class to calculate the reward.
 * Access to all parameters is available via the params JSONObject.
 */
public class BusinessLogicReward extends RewardSuper {

    private static final Logger LOGGER = LogManager.getLogger(BusinessLogicReward.class.getName());

    public BusinessLogicReward() {
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
    public static JSONObject reward(JSONObject params) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        double reward = 1.0;
        double learning_reward = 1.0;

        JSONObject rewards = new JSONObject();

        try {
            if (params.isEmpty()) {
                rewards.put("reward", reward);
                rewards.put("learning_reward", learning_reward);
                rewards.put("learning_for_contacts",false);
                rewards.put("learning_for_responses",true);
                params.put("rewards", rewards);
                return params;
            }
        } catch (Exception e) {
            LOGGER.error("BusinessLogicReward:E002: Error checking for empty params: "+e.getMessage());
        }

        try {
            /* Check if the business logic configuration is present*/
            boolean business_Logic_configuration_check = false;
            if (params.has("preloadCorpora")) {
                if (params.getJSONObject("preloadCorpora").has("rewards_business_logic")) {
                    business_Logic_configuration_check = true;
                }
            }

            /* Return the default rewards if the business logic configuration is not present*/
            if (!business_Logic_configuration_check) {
                LOGGER.error("BusinessLogicReward:E002: rewards_business_logic not found in additional corpora. Returning default rewards.");
                rewards.put("reward", reward);
                rewards.put("learning_reward", learning_reward);
                params.put("rewards", rewards);
                return params;
            }

            /* Get the business logic configuration and check if rewards and/or learning reward are configured */
            JSONObject business_logic_configuration = params.getJSONObject("preloadCorpora").getJSONObject("rewards_business_logic");
            boolean rewards_configuration_check = false;
            if (business_logic_configuration.has("reward")) {
                rewards_configuration_check = true;
            }
            boolean learning_reward_configuration_check = false;
            if (business_logic_configuration.has("learning_reward")) {
                learning_reward_configuration_check = true;
            }

            /* If neither rewards nor learning_reward are configured return the default values*/
            if (!rewards_configuration_check && !learning_reward_configuration_check) {
                LOGGER.error("BusinessLogicReward:E003: Neither reward nor learning_reward found in rewards_business_logic. Returning default rewards.");
                rewards.put("reward", reward);
                rewards.put("learning_reward", learning_reward);
                params.put("rewards", rewards);
                return params;
            }

            /* Get the reward value from the business logic function */
            if (rewards_configuration_check) {
                JSONObject rewards_configuration = business_logic_configuration.getJSONObject("reward");
                String function_name = rewards_configuration.getString("function_name");
                String output = rewards_configuration.getString("output");
                params.put("business_logic", function_name);
                params = BusinessLogic.getValues(params);
                reward = params.getJSONObject("business_logic_results").optDouble(output);
            }

            /* Get the learning_reward value from the business logic function */
            if (learning_reward_configuration_check) {
                JSONObject learning_reward_configuration = business_logic_configuration.getJSONObject("learning_reward");
                String function_name = learning_reward_configuration.getString("function_name");
                String output = learning_reward_configuration.getString("output");
                params.put("business_logic", function_name);
                params = BusinessLogic.getValues(params);
                learning_reward = params.getJSONObject("business_logic_results").optDouble(output);
            }
        } catch (Exception e) {
            LOGGER.error("BusinessLogicReward:E001: Error calculating reward using business logic, using default rewards: "+e.getMessage());
        }

        rewards.put("reward", reward);
        rewards.put("learning_reward", learning_reward);
        rewards.put("learning_for_contacts",false);
        rewards.put("learning_for_responses",true);
        params.put("rewards", rewards);
        return params;
    }

}
