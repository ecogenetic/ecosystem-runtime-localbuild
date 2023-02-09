package com.ecosystem.plugin.customer;

import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.MathRandomizer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

public class PostScoreSuper {

    static GlobalSettings settings;
    static {
        try {
            settings = new GlobalSettings();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @param params
     * @param predictResult
     * @return
     */
    public static JSONObject getTopScores(JSONObject params, JSONObject predictResult) {

        int resultCount = 1;
        if (params.has("resultcount")) resultCount = params.getInt("resultcount");

        if (predictResult.getJSONArray("final_result").length() <= resultCount)
            resultCount = predictResult.getJSONArray("final_result").length();

        /* depending on epsilon and mab settings */
        if (params.getInt("explore") == 0) {
            predictResult.put("final_result", getSelectedPredictResult(predictResult, resultCount));
            predictResult.put("explore", 0);
        } else {
            predictResult.put("final_result", getSelectedPredictResultRandom(predictResult, resultCount));
            predictResult.put("explore", 1);
        }

        return predictResult;

    }

    /**
     * Get random results for MAB
     * @param predictResult
     * @param numberOffers
     * @return
     */
    public static JSONArray getSelectedPredictResultRandom(JSONObject predictResult, int numberOffers) {
        return getSelectedPredictResultExploreExploit(predictResult, numberOffers, 1);
    }

    /**
     * Get result based on score
     * @param predictResult
     * @param numberOffers
     * @return
     */
    public static JSONArray getSelectedPredictResult(JSONObject predictResult, int numberOffers) {
        return getSelectedPredictResultExploreExploit(predictResult, numberOffers, 0);
    }

    /**
     * Review this: Master version in EcosystemMaster class. {offer_treatment_code: {$regex:"_A"}}
     *
     * @param predictResult
     * @param numberOffers
     * @return
     */
    public static JSONArray getSelectedPredictResultExploreExploit(JSONObject predictResult, int numberOffers, int explore) {
        JSONArray offers = new JSONArray();
        int resultLength = predictResult.getJSONArray("final_result").length();

        for (int j = 0, k = 0; j < resultLength; j++) {
            JSONObject work = new JSONObject();
            if (explore == 1) {
                int rand = MathRandomizer.getRandomIntBetweenRange(0, resultLength - 1);
                work = predictResult.getJSONArray("final_result").getJSONObject(rand);
            } else {
                work = predictResult.getJSONArray("final_result").getJSONObject(j);
            }

            /* test if budget is enabled && spend_limit is greater than 0, if budget is disabled, then this will be 1.0 */
            if (settings.getPredictorOfferBudget() != null) {
                /* if budget setting and there is budget to spend */
                if (work.has("spend_limit")) {
                    if ((work.getDouble("spend_limit") > 0.0) | work.getDouble("spend_limit") == -1) {
                        offers.put(k, setValuesFinal(work, k + 1));
                        if ((k + 1) == numberOffers) break;
                        k = k + 1;
                    }
                } else {
                    break;
                }
            } else {
                /* no budget setting present */
                offers.put(k, setValuesFinal(work, k + 1));
                if ((k + 1) == numberOffers) break;
                k = k + 1;
            }
        }

        return offers;
    }

    /**
     * Set values JSONObject that will be used in final
     * @param work
     * @param rank
     * @return
     */
    private static JSONObject setValuesFinal(JSONObject work, int rank) {
        JSONObject offer = new JSONObject();

        offer.put("rank", rank);
        offer.put("result", setValues(work));
        offer.put("result_full", work);

        return offer;
    }

    private static JSONObject setValues(JSONObject work) {
        JSONObject result = new JSONObject();
        result.put("score", work.get("score"));
        result.put("arm_reward", work.get("arm_reward"));
        result.put("final_score", work.get("score"));
        result.put("offer", work.get("offer"));
        result.put("offer_name", work.get("offer_name"));
        result.put("modified_offer_score", work.get("modified_offer_score"));
        result.put("offer_value", work.get("offer_value"));
        result.put("uuid", work.get("uuid"));

        /** Important to use for logging and history lookup in Dynamic Configurations */
        if (work.has("contextual_variable_one"))
            result.put("contextual_variable_one", work.get("contextual_variable_one"));
        if (work.has("contextual_variable_two"))
            result.put("contextual_variable_two", work.get("contextual_variable_two"));

        return result;
    }


    private static JSONObject getExplore(JSONObject params, double epsilonIn, String name) {
        double rand = MathRandomizer.getRandomDoubleBetweenRange(0, 1);
        double epsilon = epsilonIn;
        params.put(name + "_epsilon", epsilon);
        if (rand <= epsilon) {
            params.put(name, 1);
        } else {
            params.put(name, 0);
        }
        return params;
    }

}
