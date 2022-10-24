package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.plugin.lib.ScoreAsyncItems;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.MathRandomizer;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import com.ecosystem.worker.h2o.ModelPredictWorkerH2O;
import hex.genmodel.easy.EasyPredictModelWrapper;
import hex.genmodel.easy.RowData;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * recommender_smp - Multiple models for per product with Offermatrix
 * Binomial model per product, all loaded into memory, scoring per offerMatrix line item.
 * 24 February 2022
 */
public class PostScoreRecommenderMulti {

    private static final Logger LOGGER = LogManager.getLogger(PostScoreRecommenderMulti.class.getName());

    ModelPredictWorkerH2O modelPredictWorkerH2O;
    ScoreAsyncItems scoreAsyncItems;

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

    public PostScoreRecommenderMulti() {
        modelPredictWorkerH2O = new ModelPredictWorkerH2O();
        scoreAsyncItems = new ScoreAsyncItems(modelPredictWorkerH2O);
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
     * @return JSONObject result to further post-scoring logic
     */
    public JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {

        double startTimePost = System.nanoTime();

        /** Value obtained via API params */
        JSONObject work = params.getJSONObject("in_params");
        double in_balance = 100.0;
        if (work.has("in_balance"))
            in_balance = DataTypeConversions.getDouble(work, "in_balance");
        else
            LOGGER.info("getPostPredict:I001aa: No in_balance specified, default used. (1000.00)");

        JSONArray finalOffers = new JSONArray();

        /* Setup JSON objects for specific prediction case */
        JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
        if (predictModelMojoResult.has("ErrorMessage")) {
            LOGGER.error("getPostPredict:E001a:" + predictModelMojoResult.get("ErrorMessage"));
            return null;
        }

        JSONArray offerMatrix = new JSONArray();
        if (params.has("offerMatrix"))
            offerMatrix = params.getJSONArray("offerMatrix");

        // JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");
        // String label = predictModelMojoResult.getJSONArray("label").getString(0);
        // JSONArray domains = predictModelMojoResult.getJSONArray("domains");

        int resultcount = (int) params.get("resultcount");
        int offerIndex = 0;

        /** Async processing scoring across all models loaded per offer */
        JSONObject domainsProbabilityObj = new JSONObject();
        if (predictModelMojoResult.has("domainsProbabilityObj"))
            domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");

        JSONObject resultScore = new JSONObject();
        try {
            double startTimePost1 = System.nanoTime();

            RowData row = modelPredictWorkerH2O.toRowData((JSONObject) predictModelMojoResult.get("features"));
            resultScore = scoreAsyncItems.allOfAsyncScoring(offerMatrix, params, models, row, domainsProbabilityObj);

            double endTimePost1 = System.nanoTime();
            LOGGER.info("scoreAsyncItems.allOfAsyncScoring:I0001a: Async process time in ms: ".concat( String.valueOf((double) ((endTimePost1 - startTimePost1) / 1000000)) ));
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        /** All items are excluded that are not active and no scores */
        offerMatrix = resultScore.getJSONArray("newOfferMatrix");

        /** Select top items based on number of offers to present */
        for (int i = 0; i < offerMatrix.length(); i++) {
            JSONObject singleOffer = offerMatrix.getJSONObject(i);
            String offer_id = String.valueOf(singleOffer.get("offer_id"));

            LOGGER.debug("singleOffer:D001-1: " + singleOffer.toString());
            LOGGER.debug("singleOffer:offer_id:D001-2: " + offer_id);

            double offer_price = 1.0;
            /** Offer matrix needs item "price" for aggregator to work! */
            if (!singleOffer.has("price"))
                if(singleOffer.has("offer_price"))
                    singleOffer.put("price", singleOffer.get("offer_price"));

            if (singleOffer.has("price"))
                offer_price = DataTypeConversions.getDouble(singleOffer, "price");
            else
                LOGGER.error("PostScoreRecommenderMultiSafaricom:E0011: price not in offerMatrix, value set to 1");

            int explore = (int) params.get("explore");
            JSONObject finalOffersObject = new JSONObject();

            offer_id = DataTypeConversions.getString(singleOffer.getString("offer_id"));

            /*******************************************************************************/

            double p = resultScore.getDouble(offer_id);

            /*******************************************************************************/

            /** Multi-model needs to store the model for logging - DO NOT REMOVE THIS!*/
            finalOffersObject.put("model_name", offer_id + ".zip");
            finalOffersObject.put("model_index", resultScore.get(offer_id + "_model_index"));

            finalOffersObject.put("offer", singleOffer.get("offer_id"));
            finalOffersObject.put("offer_name", singleOffer.get("offer_name"));
            // finalOffersObject.put("offer_name_desc", offer_name + " - " + i);

            /** process final */
            // double p = domainsProbabilityObj.getDouble(label);
            finalOffersObject.put("score", p);
            finalOffersObject.put("final_score", p);
            finalOffersObject.put("modified_offer_score", p);
            finalOffersObject.put("offer_value", offer_price); // use value from offer matrix
            // finalOffersObject.put("offer_profit_probability", offer_profit * p);

            finalOffersObject.put("p", p);
            finalOffersObject.put("explore", explore);

            /** Prepare array before final sort */
            finalOffers.put(offerIndex, finalOffersObject);
            offerIndex = offerIndex + 1;
        }

        JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "score", "double", "d");
        predictModelMojoResult.put("final_result", sortJsonArray);

        predictModelMojoResult = getTopScores(params, predictModelMojoResult);

        /** Multi-model needs to store the model for logging! - DO NOT REMOVE THIS! */
        if (sortJsonArray.length() > 0) {
            if (sortJsonArray.getJSONObject(0).has("model_index")) {
                String model_name = (String) sortJsonArray.getJSONObject(0).get("model_name");
                params.put("model_selected", model_name);
            }
        } else {
            LOGGER.error("PostScoreRecommenderMulti:E999: No result ");
        }

        double endTimePost = System.nanoTime();
        LOGGER.info("PostScoreRecommenderMulti:I001: time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));

        return predictModelMojoResult;

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
