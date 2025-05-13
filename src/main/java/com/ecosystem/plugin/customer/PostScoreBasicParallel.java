package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * This the ecosystem/Ai generic post-score template.
 * Customer plugin for specialized logic to be added to the runtime engine.
 * This class is loaded through the plugin loader system.
 */
public class PostScoreBasicParallel extends PostScoreSuper {
    private static final Logger LOGGER = LogManager.getLogger(PostScoreBasic.class.getName());

    public PostScoreBasicParallel() {
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
    public JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
        double startTimePost = System.nanoTime();
        try {
            /* Setup JSON objects for specific prediction case */
            JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
            JSONObject domainsProbabilityObj = new JSONObject();
            if (predictModelMojoResult.has("domainsProbabilityObj"))
                domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");

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

            if (params.has("preloadCorpora")) {
                if (params.getJSONObject("preloadCorpora").has("network")) {
                    JSONObject a = params.getJSONObject("preloadCorpora");
                    JSONObject preloadCorpora = a.getJSONObject("network");
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
                boolean explainability = false;
                // LOGGER.info("predictModelMojoResult: " + predictModelMojoResult.toString());
                if (predictModelMojoResult.get("type").getClass().getName().toLowerCase().contains("array")) {
                    type = predictModelMojoResult
                            .getJSONArray("type")
                            .get(0)
                            .toString().toLowerCase().trim();
                    if (predictModelMojoResult.has("shapley_contributions"))
                        explainability = true;
                } else {
                    type = ((String) predictModelMojoResult.get("type")).toLowerCase().trim();
                }

                /** Offer name, defaults to type (replace with offer matrix etc) */
                if (featuresObj.has("offer_name_final"))
                    finalOffersObject.put("offer_name", featuresObj.get("offer_name_final"));
                else
                    finalOffersObject.put("offer_name", type);

                if (featuresObj.has("offer"))
                    finalOffersObject.put("offer", featuresObj.get("offer"));
                else
                    finalOffersObject.put("offer", type);

                if (featuresObj.has("offer_id"))
                    finalOffersObject.put("offer", featuresObj.get("offer_id"));
                else
                    finalOffersObject.put("offer_id", type);

                if (featuresObj.has("price"))
                    finalOffersObject.put("price", featuresObj.get("price"));
                else
                    finalOffersObject.put("price", 1.0);

                if (featuresObj.has("cost"))
                    finalOffersObject.put("cost", featuresObj.get("cost"));
                else
                    finalOffersObject.put("cost", 1.0);

                /** Score based on model type */
                if (type.contains("clustering")) {
                    finalOffersObject.put("cluster", predictModelMojoResult.getJSONArray("cluster").get(0));
                    finalOffersObject.put("score", DataTypeConversions.getDouble(domainsProbabilityObj, "score"));
                    finalOffersObject.put("modified_offer_score", DataTypeConversions.getDouble(domainsProbabilityObj, "score"));
                } else if (type.contains("anomalydetection")) {
                    double[] score = (double[]) domainsProbabilityObj.get("score");
                    finalOffersObject.put("score", score[0]);
                    finalOffersObject.put("modified_offer_score", score[0]);
                } else if (type.contains("regression")) {
                    Object score = predictModelMojoResult.getJSONArray("value").get(0);
                    finalOffersObject.put("score", score);
                    finalOffersObject.put("modified_offer_score", score);
                } else if (type.contains("multinomial")) {
                    Object probability = predictModelMojoResult.getJSONArray("probability").get(0);
                    Object label = null;
                    try {
                        label = predictModelMojoResult.getJSONArray("label").get(0);
                    } catch (Exception e) {
                        LOGGER.error("PostScoreBasic:getPostPredict:E001: Error relates to scoring your model. The model wasn't loaded or is not accessible.");
                        e.printStackTrace();
                    }
                    Object response = predictModelMojoResult.getJSONArray("response").get(0);
                    finalOffersObject.put("score", probability);
                    finalOffersObject.put("modified_offer_score", probability);
                    finalOffersObject.put("offer", label);
                    finalOffersObject.put("offer_name", response);
                } else if (type.contains("coxph")) {
                    Object score = predictModelMojoResult.getJSONArray("value").get(0);
                    finalOffersObject.put("score", score);
                    finalOffersObject.put("modified_offer_score", score);
                } else if (type.contains("wordembedding")) {
                    float[] score = (float[]) predictModelMojoResult.getJSONArray("_text_word2vec").get(0);
                    finalOffersObject.put("score", Double.valueOf(String.valueOf(score[0])));
                    finalOffersObject.put("embedding", score);
                    finalOffersObject.put("modified_offer_score", 0.0);
                } else if (type.contains("deeplearning")) {
                    /** From TensorFlow or PyTorch */
                    Object score = domainsProbabilityObj.getDouble("1");
                    finalOffersObject.put("score", score);
                    finalOffersObject.put("modified_offer_score", score);
                    Object response = predictModelMojoResult.getJSONArray("response").get(0);
                    finalOffersObject.put("offer_name", response);
                } else if (type.contains("empty score")) {
                    /** This is typically used for data lookup only, obtain values from feature store! */
                    if (featuresObj.has("offer_name"))
                        finalOffersObject.put("offer_name", featuresObj.get("offer_name"));

                    if (featuresObj.has("offer"))
                        finalOffersObject.put("offer", featuresObj.get("offer"));

                    if (featuresObj.has("score"))
                        finalOffersObject.put("score", Double.valueOf(String.valueOf(featuresObj.get("score"))));
                    else
                        finalOffersObject.put("score", 1.0);

                    if (featuresObj.has("modified_offer_score"))
                        finalOffersObject.put("modified_offer_score", Double.valueOf(String.valueOf(featuresObj.get("modified_offer_score"))));
                    else
                        finalOffersObject.put("modified_offer_score", 1.0);

                    if (featuresObj.has("cost"))
                        finalOffersObject.put("cost", Double.valueOf(String.valueOf(featuresObj.get("cost"))));
                    else
                        finalOffersObject.put("cost", 0.0);

                } else {
                    finalOffersObject.put("score", 1.0);
                    finalOffersObject.put("modified_offer_score", 1.0);
                }

                finalOffersObject.put("offer_details", domainsProbabilityObj);
                if (explainability) {
                    finalOffersObject.put("shapley_contributions", predictModelMojoResult.get("shapley_contributions"));
                    finalOffersObject.put("shapley_contributions_names", predictModelMojoResult.get("shapley_contributions_names"));
                }

                /** Default value, could be replaced by offer matrix or feature store */
                double offer_value = 1.0;
                finalOffersObject.put("offer_value", offer_value);
                finalOffersObject.put("uuid", params.get("uuid"));

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

        /** Call parallel deployment if specified in in_params or corpora */
        /**
            in_params example:
         {
             "parallel_url":"",
             "parallel_campaign":"",
         }
         */
        callParallelDeployment(params);

        double endTimePost = System.nanoTime();
        LOGGER.info("getPostPredict:I001: execution time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));
        return predictModelMojoResult;
    }

}
