package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.MathRandomizer;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * This the ecosystem/Ai generic post-score template.
 * Customer plugin for specialized logic to be added to the runtime engine.
 * This class is loaded through the plugin loader system.
 */
public class PostScoreBasic {
    private static final Logger LOGGER = LogManager.getLogger(PostScoreBasic.class.getName());

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

    public PostScoreBasic() {
    }

    /**
     * Pre-post predict logic
     */
    public void getPostPredict () {
    }

    /**
     * getPostPredict
     *
     * @param predictModelMojoResult Result from scoring
     * @param params                 Params carried from input
     * @param session                Session variable for Cassandra
     * @param models 				 Preloaded H2O Models
     * @return JSONObject result to further post-scoring logic
     */
    public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
        double startTimePost = System.nanoTime();
        try {
            /* Setup JSON objects for specific prediction case */
            JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
            JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");

            /* If whitelist settings then only allow offers on list */
            boolean whitelist = false;
            ArrayList<String> offerWhiteList = new ArrayList<>();
            if (params.has("whitelist")) {
                if (!params.getJSONObject("whitelist").isEmpty()) {
                    offerWhiteList = (ArrayList<String>) params.getJSONObject("whitelist").get("whitelist");
                    params.put("resultcount", offerWhiteList.size());
                    whitelist = DataTypeConversions.getBooleanFromString(params.getJSONObject("whitelist").get("logicin"));
                }
            }

            JSONArray finalOffers = new JSONArray();
            int resultcount = (int) params.get("resultcount");
            /* For each offer in offer matrix determine eligibility */
            /* get selector field from properties: predictor.selector.setup */
            // String s = new JSONObject(settings.getSelectorSetup()).getJSONObject("lookup").getString("fields");

            /** This loop can be used to add number of offers/options to return result */
            JSONObject finalOffersObject = new JSONObject();
            int offerIndex = 0;
            for (int i = 0; i < resultcount; i++) {

                /** Model type based approaches */
                String type = "";
                type = predictModelMojoResult.getJSONArray("type").getString(0);

                /** Offer name, defaults to type (replace with offer matrix etc) */
                if (featuresObj.has("offer_name_final"))
                    finalOffersObject.put("offer_name", featuresObj.getString("offer_name_final"));
                else
                    finalOffersObject.put("offer_name", type);

                if (featuresObj.has("offer"))
                    finalOffersObject.put("offer", featuresObj.getString("offer"));
                else
                    finalOffersObject.put("offer", type);

                /** Score based on model type */
                if (type.equals("Clustering")) {
                    finalOffersObject.put("cluster", predictModelMojoResult.getJSONArray("cluster").get(0));
                    finalOffersObject.put("score", DataTypeConversions.getDouble(domainsProbabilityObj, "score"));
                    finalOffersObject.put("modified_offer_score", DataTypeConversions.getDouble(domainsProbabilityObj, "score"));
                } else if (type.equals("AnomalyDetection")) {
                    double[] score = (double[]) domainsProbabilityObj.get("score");
                    finalOffersObject.put("score", score[0]);
                    finalOffersObject.put("modified_offer_score", score[0]);
                } else if (type.equals("regression")) {
                    Object score = predictModelMojoResult.getJSONArray("value").get(0);
                    finalOffersObject.put("score", score);
                    finalOffersObject.put("modified_offer_score", score);
                } else if (type.equals("multinomial")) {
                    Object probability = predictModelMojoResult.getJSONArray("probability").get(0);
                    Object label = predictModelMojoResult.getJSONArray("label").get(0);
                    Object response = predictModelMojoResult.getJSONArray("response").get(0);
                    finalOffersObject.put("score", probability);
                    finalOffersObject.put("modified_offer_score", probability);
                    finalOffersObject.put("offer", label);
                    finalOffersObject.put("offer_name", response);
                } else {
                    finalOffersObject.put("score", 1.0);
                    finalOffersObject.put("modified_offer_score", 1.0);
                }

                finalOffersObject.put("offer_details", domainsProbabilityObj);

                /** Default value, could be replaced by offer matrix or feature store */
                double offer_value = 1.0;
                finalOffersObject.put("offer_value", offer_value);

                /** Add other structures to the final result */
                finalOffersObject.put("offer_matrix", featuresObj);

                /** Budget processing option, if it's set in the properties */
                if (settings.getPredictorOfferBudget() != null) {
                    JSONObject budgetItem = obtainBudget(featuresObj, params.getJSONObject("featuresObj"), offer_value);
                    double budgetSpendLimit = budgetItem.getDouble("spend_limit");
                    finalOffersObject.put("spend_limit", budgetSpendLimit);
                }

                /** Prepare offer array before final sorting */
                finalOffers.put(offerIndex, finalOffersObject);
                offerIndex = offerIndex + 1;
            }

            /** Sort final offer list based on score */
            JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "score", "double", "d");
            predictModelMojoResult.put("final_result", sortJsonArray);

        } catch (Exception e) {
            LOGGER.error(e);
        }

        /** Get top scores and test for explore/exploit randomization */
        predictModelMojoResult = getTopScores(params, predictModelMojoResult);

        double endTimePost = System.nanoTime();
        LOGGER.info("getPostPredict:I001: execution time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));

        return predictModelMojoResult;
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

    private static JSONObject setValues(JSONObject work) {
        JSONObject result = new JSONObject();
        result.put("score", work.get("score"));
        result.put("final_score", work.get("score"));
        result.put("offer", work.get("offer"));
        result.put("offer_name", work.get("offer_name"));
        result.put("offer_details", work.get("offer_details"));
        result.put("modified_offer_score", work.get("modified_offer_score"));
        result.put("offer_value", work.get("offer_value"));

        return result;
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
     * @param params
     * @param predictResult
     * @return
     */
    private static JSONObject getTopScores(JSONObject params, JSONObject predictResult) {
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

}
