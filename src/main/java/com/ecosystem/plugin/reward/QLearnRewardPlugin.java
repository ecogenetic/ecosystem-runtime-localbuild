package com.ecosystem.plugin.reward;

import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QLearnRewardPlugin extends RewardSuper {

    private static final Logger LOGGER = LogManager.getLogger(QLearnRewardPlugin.class.getName());

    public QLearnRewardPlugin () {
        // Constructor
    }

    public static void reward() {
        // Dynamic loader
    }

    /**
     *
     * @return
     */
    public static JSONObject reward(
            JSONObject params
    ) {

        List<Object> actions = params.getJSONArray("actions").toList();
        String action = String.valueOf(params.get("action"));
        int state = Integer.valueOf(String.valueOf(params.get("state")));
        List<Object> offers = params.getJSONArray("offers").toList();
        int category = Integer.valueOf(String.valueOf(params.get("category")));
        JSONObject log_action_state = params.getJSONObject("log_action_state");
        String training_data_source = String.valueOf(params.get("training_data_source"));

        double reward = 0.0;
        String copcar_prefix = "cc";
        double  copcar_median = 0;
        List<Double> cc_list = new ArrayList<>();

        if (!training_data_source.equals("logging")) {
            try {
                // category = Integer.valueOf(String.valueOf(params.getJSONObject("featuresObj").get("copcar")));
                if (params.has("preloadCorpora")) {
                    if (params.getJSONObject("preloadCorpora").has("cclookup")) {
                        JSONObject cc_lookup = params.getJSONObject("preloadCorpora").getJSONObject("cclookup");
                        JSONObject cc_fields = cc_lookup.getJSONObject("copcar");
                        String cc_fields_string = String.valueOf(cc_fields.get("value"));
                        ArrayList<String> ccList = new ArrayList<>(Arrays.asList(cc_fields_string.split(",")));
                        for (String cc_key : ccList) {
                            double cc_value = Double.valueOf(String.valueOf(params.getJSONObject("featuresObj").get(cc_key)));
                            cc_list.add(cc_value);
                        }

                    } else {
                        for (int i = 0; i < params.getJSONObject("featuresObj").names().length(); i++) {
                            String key = params.getJSONObject("featuresObj").names().getString(i);
                            int key_index = key.indexOf(copcar_prefix);
                            if (key_index == 0) {
                                double cc_value = Double.valueOf(String.valueOf(params.getJSONObject("featuresObj").get(key)));
                                cc_list.add(cc_value);
                            }

                        }
                    }
                }

                int list_size = cc_list.size();
                if (list_size % 2 == 1) {
                    copcar_median = cc_list.get(((list_size + 1) / 2) - 1);
                } else {
                    if (list_size > 0)
                        copcar_median = cc_list.get((list_size / 2) - 1) + cc_list.get(list_size / 2);
                    else
                        copcar_median = 0;
                }

                double previous_day_mbs = 0;
                if (params.getJSONObject("featuresObj").has("previous_day_mbs"))
                    previous_day_mbs = Double.valueOf(String.valueOf(params.getJSONObject("featuresObj").get("previous_day_mbs")));
                else
                    LOGGER.info("previous_day_mbs not found in featuresObj");

                if (previous_day_mbs > 0) {
                    state = 1;
                } else {
                    state = 0;
                }

                double cost = Double.parseDouble((String) offers.get(actions.indexOf(action)));

                if (cost > copcar_median && state == 1) {
                    reward = 10;
                } else if (cost == copcar_median && state == 1) {
                    reward = 8;
                } else if (cost < copcar_median && state == 1) {
                    reward = 0;
                } else if (cost > copcar_median && state == 0) {
                    reward = -10;
                } else if (cost == copcar_median && state == 0) {
                    reward = -10;
                } else if (cost < copcar_median && state == 0) {
                    reward = -10;
                } else {
                    reward = 0;
                }

            } catch (Exception e) {
                e.printStackTrace();
                LOGGER.error(e.getMessage());
            }

            return params.put("reward", reward);
        } else {
            try {
                String log_state = log_action_state.getString("state");
                int log_response = log_action_state.getInt("response");
                String log_action = log_action_state.getString("offer");
                double cost = Double.parseDouble((String) offers.get(actions.indexOf(log_action)));

                if (log_response > 0) {
                    reward = cost;
                } else {
                    reward = -1;
                }
            } catch (Exception e) {
                e.printStackTrace();
                LOGGER.error(e.getMessage());
            }

            JSONObject rewards = params.optJSONObject("rewards");
            if (rewards == null)
                rewards = new JSONObject();
            rewards.put("reward", reward);
            params.put("rewards", rewards);
            return params;
        }

    }

}

