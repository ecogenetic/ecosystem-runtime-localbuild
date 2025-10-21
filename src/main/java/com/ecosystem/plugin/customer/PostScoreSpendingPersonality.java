package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * This is the post-score class to obtain spending personality.
 */
public class PostScoreSpendingPersonality extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreSpendingPersonality.class.getName());

	public PostScoreSpendingPersonality() {
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
			JSONObject domainsProbabilityObj = new JSONObject();
			if (predictModelMojoResult.has("domainsProbabilityObj"))
				domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");

			if (params.has("preloadCorpora")) {
				if (params.getJSONObject("preloadCorpora").has("network")) {
					JSONObject a = params.getJSONObject("preloadCorpora");
					JSONObject preloadCorpora = a.getJSONObject("network");
				}
			}

			JSONArray finalOffers = new JSONArray();

			/** This loop can be used to add number of offers/options to return result */
			JSONObject finalOffersObject = new JSONObject();
			int offerIndex = 0;

			/** Model type based approaches */
			/*
			String type = "";
			if (predictModelMojoResult.get("type").getClass().getName().toLowerCase().contains("array")) {
				type = predictModelMojoResult
						.getJSONArray("type")
						.get(0)
						.toString().toLowerCase().trim();
			} else {
				type = ((String) predictModelMojoResult.get("type")).toLowerCase().trim();
			}
			*/

			if (featuresObj.has("price"))
				finalOffersObject.put("price", featuresObj.get("price"));
			else
				finalOffersObject.put("price", 1.0);

			if (featuresObj.has("cost"))
				finalOffersObject.put("cost", featuresObj.get("cost"));
			else
				finalOffersObject.put("cost", 1.0);

			if (featuresObj.has("Experiential"))
				finalOffersObject.put("experiential", featuresObj.get("Experiential"));
			if (featuresObj.has("Extrovert"))
				finalOffersObject.put("extrovert", featuresObj.get("Extrovert"));
			if (featuresObj.has("Enthusiastic"))
				finalOffersObject.put("enthusiastic", featuresObj.get("Enthusiastic"));
			if (featuresObj.has("Industrious"))
				finalOffersObject.put("industrious", featuresObj.get("Industrious"));
			if (featuresObj.has("Intentional"))
				finalOffersObject.put("intentional", featuresObj.get("Intentional"));
			if (featuresObj.has("Introvert"))
				finalOffersObject.put("introvert", featuresObj.get("Introvert"));

			if (featuresObj.has("personality"))
				finalOffersObject.put("personality", featuresObj.get("personality"));
			if (featuresObj.has("trait"))
				finalOffersObject.put("trait", featuresObj.get("trait"));
			if (featuresObj.has("count"))
				finalOffersObject.put("transaction_count", featuresObj.get("count"));

			finalOffersObject.put("offer", featuresObj.getString("personality"));
			finalOffersObject.put("offer_id", featuresObj.getString("personality"));
			finalOffersObject.put("offer_name", featuresObj.getString("personality"));
			finalOffersObject.put("score", featuresObj.get(featuresObj.getString("personality")));
			finalOffersObject.put("modified_offer_score", featuresObj.get(featuresObj.getString("trait")));

			finalOffersObject.put("offer_details", domainsProbabilityObj);

			/** Additional details */
			/**
			// call generative api here, could also use prompt library...
			String prompt = "As an analyst you need to explain these scores: " + featuresObj.getString("personality") + featuresObj.get(featuresObj.getString("personality"));
			String chat_server = "http://ecosystem-server:3001/api";
			String result = String.valueOf(getRestGeneric("POST:" + chat_server + "/intent :PARAM: <PROMPT>" + prompt + "</PROMPT>"));
			finalOffersObject.put("additional_details", result);
			 **/

			/** Default value, could be replaced by offer matrix or feature store */
			double offer_value = 1.0;
			finalOffersObject.put("offer_value", offer_value);
			finalOffersObject.put("uuid", params.get("uuid"));

			/** Add other structures to the final result */
			finalOffersObject.put("offer_matrix", new JSONObject());

			/** Prepare offer array before final sorting */
			finalOffers.put(offerIndex, finalOffersObject);
			offerIndex = offerIndex + 1;

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

}
