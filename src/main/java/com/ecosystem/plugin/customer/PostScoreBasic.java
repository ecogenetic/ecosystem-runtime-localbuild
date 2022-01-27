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
 * This the ecosystem/Ai personality series.
 * Customer plugin for specialized logic to be added to the runtime engine. This class is loaded through the plugin
 * loader system.
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

            /* For each offer in offer matrix determine eligibility */
            int offerIndex = 0;
            /* get selector field from properties: predictor.selector.setup */
            String s = new JSONObject(settings.getSelectorSetup()).getJSONObject("lookup").getString("fields");

            JSONObject finalOffersObject = new JSONObject();
            finalOffersObject.put("offer_name", featuresObj.getString("offer_name_final"));
            finalOffersObject.put("score", domainsProbabilityObj);
            finalOffersObject.put("offer_value", 0.0);

            finalOffersObject.put("offer_matrix", featuresObj);

            // TODO OBTAIN BUDGET PRIORITY AND ASSIGN
            if (settings.getPredictorOfferBudget() != null) {
                JSONObject budgetItem = obtainBudget(featuresObj, params.getJSONObject("featuresObj"), 0.0);
                double budgetSpendLimit = budgetItem.getDouble("spend_limit");
                finalOffersObject.put("spend_limit", budgetSpendLimit);
            }

            finalOffers.put(offerIndex, finalOffersObject);
            JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "modified_offer_score", "double", "d");
            predictModelMojoResult.put("final_result", sortJsonArray);

        } catch (Exception e) {
            LOGGER.error(e);
        }

        predictModelMojoResult = getTopScores(params, predictModelMojoResult);
        return predictModelMojoResult;

    }

    public void offerRecommender() {
    }

    /**
     * Review this: Master version in EcosystemMaster class.
     *
     * @param predictResult
     * @param numberOffers
     * @return
     */
    public static JSONArray getSelectedPredictResult(JSONObject predictResult, int numberOffers) {
        JSONArray offers = new JSONArray();

        for (int j = 0; j < numberOffers; j++) {
            JSONObject work = predictResult.getJSONArray("final_result").getJSONObject(j);
            JSONObject offer = new JSONObject();
            offer.put("rank", j + 1);
            offer.put("result", setValues(work));
            offer.put("offer_matrix", work.getJSONObject("offer_matrix"));
            offers.put(j, offer);
        }
        return offers;
    }

    /**
     * Review this: Master version in EcosystemMaster class.
     * Get random results for MAB
     *
     * @param predictResult
     * @param numberOffers
     * @return
     */
    public static JSONArray getSelectedPredictResultRandom(JSONObject predictResult, int numberOffers) {
        JSONArray offers = new JSONArray();
        /* If there's no 'final' return an empty array */
        if (predictResult.has("final_result")) {
            int resultLength = predictResult.getJSONArray("final_result").length() - 1;
            for (int j = 0; j < numberOffers; j++) {
                int rand = MathRandomizer.getRandomIntBetweenRange(0, resultLength);
                JSONObject work = predictResult.getJSONArray("final_result").getJSONObject(rand);
                JSONObject offer = new JSONObject();
                offer.put("rank", j+1);
                offer.put("result", setValues(work));
                offer.put("offer_matrix", work.getJSONObject("offer_matrix"));
                offers.put(j, offer);
            }
        }
        return offers;
    }

    private static JSONObject setValues(JSONObject work) {
        JSONObject result = new JSONObject();
        result.put("score", work.get("score"));
        result.put("offer_name", work.get("offer_name"));
        result.put("modified_offer_score", work.get("modified_offer_score"));
        result.put("offer_value", work.get("offer_value"));
        return result;
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
            predictResult.put("final_result", PostScoreBasic.getSelectedPredictResult(predictResult, resultCount));
            predictResult.put("explore", 0);
        } else {
            predictResult.put("final_result", PostScoreBasic.getSelectedPredictResultRandom(predictResult, resultCount));
            predictResult.put("explore", 1);
        }
        return predictResult;
    }

}
