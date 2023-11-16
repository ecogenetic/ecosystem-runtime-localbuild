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
 * This is the post-score class to define spam or ham messages.
 */
public class PostScoreSpam extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreSpam.class.getName());

	public PostScoreSpam() {
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
			String type = "";
			if (predictModelMojoResult.get("type").getClass().getName().toLowerCase().contains("array")) {
				type = predictModelMojoResult
						.getJSONArray("type")
						.get(0)
						.toString().toLowerCase().trim();
			} else {
				type = ((String) predictModelMojoResult.get("type")).toLowerCase().trim();
			}


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

			if (type.contains("deeplearning")) {
				/** From TensorFlow or PyTorch */
				Object score = domainsProbabilityObj.getDouble("1");
				finalOffersObject.put("score", score);
				finalOffersObject.put("modified_offer_score", score);
				Object response = predictModelMojoResult.getJSONArray("response").get(0);

				double score_final = DataTypeConversions.getDouble(finalOffersObject, "score");

				if (score_final >= 0.8) {
					finalOffersObject.put("offer", "true");
					finalOffersObject.put("offer_name", "true");
					finalOffersObject.put("spam", "true");
				} else {
					finalOffersObject.put("offer", "false");
					finalOffersObject.put("offer_name", "false");
					finalOffersObject.put("spam", "false");
				}
				finalOffersObject.put("spam_confidence", score_final);
				finalOffersObject.put("ham_confidence", 1.0 - score_final);

			}

			finalOffersObject.put("offer_details", domainsProbabilityObj);

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
