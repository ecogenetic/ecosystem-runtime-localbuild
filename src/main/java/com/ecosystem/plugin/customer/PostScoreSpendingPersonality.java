package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.ecosystem.worker.rest.RestAccess.getRestGeneric;

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

			if (hasFeature(featuresObj, "price"))
				finalOffersObject.put("price", getFeature(featuresObj, "price"));
			else
				finalOffersObject.put("price", 1.0);

			if (hasFeature(featuresObj, "cost"))
				finalOffersObject.put("cost", getFeature(featuresObj, "cost"));
			else
				finalOffersObject.put("cost", 1.0);

			if (hasFeature(featuresObj, "Experiential"))
				finalOffersObject.put("experiential", getFeature(featuresObj, "Experiential"));
			if (hasFeature(featuresObj, "Extrovert"))
				finalOffersObject.put("extrovert", getFeature(featuresObj, "Extrovert"));
			if (hasFeature(featuresObj, "Enthusiastic"))
				finalOffersObject.put("enthusiastic", getFeature(featuresObj, "Enthusiastic"));
			if (hasFeature(featuresObj, "Industrious"))
				finalOffersObject.put("industrious", getFeature(featuresObj, "Industrious"));
			if (hasFeature(featuresObj, "Intentional"))
				finalOffersObject.put("intentional", getFeature(featuresObj, "Intentional"));
			if (hasFeature(featuresObj, "Introvert"))
				finalOffersObject.put("introvert", getFeature(featuresObj, "Introvert"));

			if (hasFeature(featuresObj, "personality"))
				finalOffersObject.put("personality", getFeature(featuresObj, "personality"));
			if (hasFeature(featuresObj, "trait"))
				finalOffersObject.put("trait", getFeature(featuresObj, "trait"));
			if (hasFeatureAny(featuresObj, "count", "transaction_count"))
				finalOffersObject.put("transaction_count", getFeatureAny(featuresObj, "count", "transaction_count"));

			String personality = hasFeature(featuresObj, "personality")
					? getFeatureString(featuresObj, "personality")
					: "none";
			String trait = hasFeature(featuresObj, "trait")
					? getFeatureString(featuresObj, "trait")
					: "none";
			finalOffersObject.put("offer", personality);
			finalOffersObject.put("offer_id", personality);
			finalOffersObject.put("offer_name", personality);
			finalOffersObject.put("score",
					resolveFeatureScore(featuresObj, personality, "personality_score"));
			finalOffersObject.put("modified_offer_score",
					resolveFeatureScore(featuresObj, trait, "trait_score"));

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

			JSONObject drift = extractLatestPersonalityDrift(featuresObj, "spend");
			JSONObject dataPayload = new JSONObject().put("featuresObj", featuresObj);
			attachPersonalityDrift(finalOffersObject, dataPayload, drift);

			/** Add other structures to the final result */
			finalOffersObject.put("offer_matrix", new JSONObject());

			/** Prepare offer array before final sorting */
			finalOffers.put(offerIndex, finalOffersObject);
			offerIndex = offerIndex + 1;

			/** Sort final offer list based on score */
			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "score", "double", "d");
			predictModelMojoResult.put("final_result", sortJsonArray);
			/** Anything added to data will be pushed out to the api response */
			predictModelMojoResult.put("data", dataPayload);

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
