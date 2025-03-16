package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * recommender_smp - Single model for all products with Offermatrix
 * Multiclass classifier trained on offer_name response column, offer matrix need to have all the offers loaded with offer_price.
 */
public class PostScoreRecommenderOffers extends PostScoreSuper {
    private static final Logger LOGGER = LogManager.getLogger(PostScoreRecommenderOffers.class.getName());

    public PostScoreRecommenderOffers() {
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
    public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
        double startTimePost = System.nanoTime();
        try {
            /** Value obtained via API params */
            JSONObject work = params.getJSONObject("in_params");
            double in_balance = 1000.0;
            if (work.has("in_balance"))
                in_balance = DataTypeConversions.getDouble(work, "in_balance");
            else
                LOGGER.info("getPostPredict:I001aa: No in_balance specified, default used. (1000.00)");

            JSONArray sortJsonArray = new JSONArray();
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

            JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");
            try {
                String label = predictModelMojoResult.getJSONArray("label").getString(0).trim();
                JSONArray domains = predictModelMojoResult.getJSONArray("domains");
            } catch (Exception e) {
                LOGGER.error("getPostPredict:E001b:Model could not be loaded, check deployment path: " + e);
            }

            // int resultcount = (int) params.get("resultcount");
            int offerIndex = 0;
            int explore = (int) params.get("explore");

            /** Select top items based on number of offers to present */
            for (int i = 0; i < offerMatrix.length(); i++) {
                JSONObject singleOffer = offerMatrix.getJSONObject(i);
                JSONObject finalOffersObject = new JSONObject();

                double offer_value = 1.0;
                if (singleOffer.has("offer_price"))
                    offer_value = DataTypeConversions.getDouble(singleOffer, "offer_price");
                if (singleOffer.has("price"))
                    offer_value = DataTypeConversions.getDouble(singleOffer, "price");

                double offer_cost = 1.0;
                if (singleOffer.has("offer_cost"))
                    offer_cost = singleOffer.getDouble("offer_cost");
                if (singleOffer.has("cost"))
                    offer_cost = singleOffer.getDouble("cost");

                String offer_id = "";
                if (domainsProbabilityObj.has(singleOffer.getString("offer_id").trim())) {
                    offer_id = singleOffer.getString("offer_id").trim();
                } else if (domainsProbabilityObj.has(singleOffer.getString("offer").trim())) {
                    offer_id = singleOffer.getString("offer").trim();
                } else if (domainsProbabilityObj.has(singleOffer.getString("offer_name").trim())) {
                    offer_id = singleOffer.getString("offer_name").trim();
                } else {
                    LOGGER.error("offerRecommender:E002-1: " + params.get("uuid") + " - Not available (offer_id, offer, offer_name from probabilities): " + singleOffer.getString("offer_name"));
                }

                double p = 0.0;
                if (domainsProbabilityObj.has(offer_id.trim())) {
                    offer_id = singleOffer.getString("offer_id").trim();
                    p = domainsProbabilityObj.getDouble(offer_id);
                } else {
                    LOGGER.error("offerRecommender:E002-1: " + params.get("uuid") + " - Not available: " + singleOffer.getString("offer_name"));
                }

                double modified_offer_score = 1.0;
                modified_offer_score = p * ((double) offer_value - offer_cost);

                finalOffersObject.put("offer", offer_id);
                finalOffersObject.put("offer_name", singleOffer.get("offer_name"));
                finalOffersObject.put("offer_name_desc", singleOffer.get("offer_name") + " - " + i);

                /** process final */
                // double p = domainsProbabilityObj.getDouble(label);
                finalOffersObject.put("score", p);
                finalOffersObject.put("final_score", p);
                finalOffersObject.put("modified_offer_score", modified_offer_score);
                finalOffersObject.put("offer_value", offer_value); // use value from offer matrix
                finalOffersObject.put("price", offer_value);
                finalOffersObject.put("cost", offer_cost);
                finalOffersObject.put("uuid", params.get("uuid"));

                finalOffersObject.put("p", p);
                finalOffersObject.put("explore", explore);

                /** Prepare array before final sort */
                finalOffers.put(offerIndex, finalOffersObject);
                offerIndex = offerIndex + 1;
            }

            sortJsonArray = JSONArraySort.sortArray(finalOffers, "modified_offer_score", "double", "d");
            predictModelMojoResult.put("final_result", sortJsonArray);

            /** Select the correct number of offers */
            predictModelMojoResult = getTopScores(params, predictModelMojoResult);

        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.error(e);
        }

        /** Top scores from final_result */
        predictModelMojoResult = getTopScores(params, predictModelMojoResult);

        double endTimePost = System.nanoTime();
        LOGGER.info("PostScoreRecommenderOffers:I001: time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));

        return predictModelMojoResult;

    }

}
