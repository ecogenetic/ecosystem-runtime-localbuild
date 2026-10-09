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
 * Static post-score for Workbench Sentimental Equilibrium <em>algorithm job</em> output.
 *
 * <p><b>Not</b> the live dynamic {@code behaviorAlgos}/{@code sentimentalEquilibrium} path.
 * That ranking uses {@link PostScoreSentimentalEquilibrium} and the options store
 * ({@code arm_reward}). This class never reads options or arm rewards.
 *
 * <p>Same approach as {@link PostScoreSpendingPersonality}: the feature store (or lookup)
 * already holds one row per customer from Process
 * ({@code sentimental_equilibrium_wb}, {@code se_<predictor>}, …). This plugin copies
 * those JSON keys into {@code final_result}:
 * <ul>
 *   <li>{@code engagementScore} → {@code score}</li>
 *   <li>{@code sentimental_equilibrium} → {@code offer} / {@code offer_name}</li>
 *   <li>{@code requiredEffort} → {@code modified_offer_score}</li>
 *   <li>also: {@code customer}, {@code effort}, {@code responses}, {@code equilibriumViable}</li>
 *   <li>optional field-map: {@code recommended_engagement_rate}, {@code rate_adjustment}, {@code status}</li>
 * </ul>
 *
 * Configure with
 * {@code plugin.postscore=com.ecosystem.plugin.customer.PostScoreSentimentalEquilibriumPreScored}.
 */
public class PostScoreSentimentalEquilibriumPreScored extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreSentimentalEquilibriumPreScored.class.getName());

	public PostScoreSentimentalEquilibriumPreScored() {
	}

	/**
	 * Pre-post predict logic
	 */
	public void getPostPredict() {
	}

	/**
	 * getPostPredict
	 *
	 * @param predictModelMojoResult Result from scoring
	 * @param params                 Params carried from input
	 * @param session                Session variable for Cassandra
	 * @param models                 Preloaded H2O Models
	 * @return JSONObject result to further post-scoring logic
	 */
	public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
		double startTimePost = System.nanoTime();
		try {
			/* Feature store / lookup already joined Workbench SE job fields into featuresObj */
			JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
			JSONObject domainsProbabilityObj = new JSONObject();
			if (predictModelMojoResult.has("domainsProbabilityObj"))
				domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");

			JSONArray finalOffers = new JSONArray();
			JSONObject finalOffersObject = new JSONObject();
			int offerIndex = 0;

			if (hasFeature(featuresObj, "price"))
				finalOffersObject.put("price", getFeature(featuresObj, "price"));
			else
				finalOffersObject.put("price", 1.0);

			if (hasFeature(featuresObj, "cost"))
				finalOffersObject.put("cost", getFeature(featuresObj, "cost"));
			else
				finalOffersObject.put("cost", 1.0);

			/* Workbench Process keys (sentimental_equilibrium_wb / se_*) */
			if (hasFeature(featuresObj, "customer"))
				finalOffersObject.put("customer", getFeature(featuresObj, "customer"));
			if (hasFeature(featuresObj, "effort"))
				finalOffersObject.put("effort", getFeature(featuresObj, "effort"));
			if (hasFeature(featuresObj, "responses"))
				finalOffersObject.put("responses", getFeature(featuresObj, "responses"));
			if (hasFeature(featuresObj, "engagementScore"))
				finalOffersObject.put("engagementScore", getFeature(featuresObj, "engagementScore"));
			if (hasFeature(featuresObj, "sentimental_equilibrium"))
				finalOffersObject.put("sentimental_equilibrium", getFeature(featuresObj, "sentimental_equilibrium"));
			if (hasFeature(featuresObj, "equilibriumViable"))
				finalOffersObject.put("equilibriumViable", getFeature(featuresObj, "equilibriumViable"));
			if (hasFeature(featuresObj, "requiredEffort"))
				finalOffersObject.put("requiredEffort", getFeature(featuresObj, "requiredEffort"));
			if (hasFeature(featuresObj, "recommended_engagement_rate"))
				finalOffersObject.put("recommended_engagement_rate", getFeature(featuresObj, "recommended_engagement_rate"));
			if (hasFeature(featuresObj, "rate_adjustment"))
				finalOffersObject.put("rate_adjustment", getFeature(featuresObj, "rate_adjustment"));
			if (hasFeature(featuresObj, "status"))
				finalOffersObject.put("status", getFeature(featuresObj, "status"));

			String equilibrium = hasFeature(featuresObj, "sentimental_equilibrium")
					? getFeatureString(featuresObj, "sentimental_equilibrium")
					: "none";

			/* Always assign numeric score (runtime /response and sort require "score"). */
			double score = firstNumericFeature(featuresObj, "engagementScore", "recommended_engagement_rate");
			double modifiedScore = firstNumericFeature(featuresObj, "requiredEffort", "rate_adjustment");
			if (modifiedScore == 0.0 && !hasFeatureAny(featuresObj, "requiredEffort", "rate_adjustment"))
				modifiedScore = score;

			finalOffersObject.put("offer", equilibrium);
			finalOffersObject.put("offer_id", equilibrium);
			finalOffersObject.put("offer_name", equilibrium);
			finalOffersObject.put("score", score);
			finalOffersObject.put("modified_offer_score", modifiedScore);

			finalOffersObject.put("offer_details", domainsProbabilityObj);

			double offer_value = 1.0;
			finalOffersObject.put("offer_value", offer_value);
			finalOffersObject.put("uuid", params.get("uuid"));

			JSONObject dataPayload = new JSONObject().put("featuresObj", featuresObj);
			finalOffersObject.put("offer_matrix", new JSONObject());

			finalOffers.put(offerIndex, finalOffersObject);
			offerIndex = offerIndex + 1;

			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "score", "double", "d");
			predictModelMojoResult.put("final_result", sortJsonArray);
			predictModelMojoResult.put("data", dataPayload);

		} catch (Exception e) {
			LOGGER.error(e);
			/* Guarantee final_result[+].score even when feature extraction fails */
			if (!predictModelMojoResult.has("final_result")
					|| predictModelMojoResult.getJSONArray("final_result").length() == 0) {
				JSONObject fallback = new JSONObject();
				fallback.put("offer", "none");
				fallback.put("offer_id", "none");
				fallback.put("offer_name", "none");
				fallback.put("score", 0.0);
				fallback.put("modified_offer_score", 0.0);
				if (params.has("uuid"))
					fallback.put("uuid", params.get("uuid"));
				predictModelMojoResult.put("final_result", new JSONArray().put(fallback));
			}
		}

		predictModelMojoResult = getTopScores(params, predictModelMojoResult);

		double endTimePost = System.nanoTime();
		LOGGER.info("getPostPredict:I001: execution time in ms: ".concat(String.valueOf((endTimePost - startTimePost) / 1000000)));
		return predictModelMojoResult;
	}

	/** First present feature coerced to double; otherwise 0.0. */
	private static double firstNumericFeature(JSONObject featuresObj, String... keys) {
		for (String key : keys) {
			if (!hasFeature(featuresObj, key))
				continue;
			try {
				Object raw = getFeature(featuresObj, key);
				Object coerced = DataTypeConversions.getDoubleFromIntLong(raw);
				if (coerced instanceof Number)
					return ((Number) coerced).doubleValue();
				if (coerced != null)
					return Double.parseDouble(String.valueOf(coerced));
			} catch (Exception ignored) {
				/* try next key */
			}
		}
		return 0.0;
	}

}
