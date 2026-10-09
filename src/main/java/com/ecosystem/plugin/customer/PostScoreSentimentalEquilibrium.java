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
 * Post-score for <em>live</em> dynamic Sentimental Equilibrium ranking.
 *
 * <p>Reads {@code arm_reward} from the dynamic-engagement options store after
 * {@code SentimentalEquilibrium.score()} via {@code RollingBehavior}
 * ({@code approach=behaviorAlgos}, {@code sub_approach=sentimentalEquilibrium}).
 *
 * <p><b>Do not use</b> for Workbench Sentimental Equilibrium Process / batch job
 * scores on the feature store — that static path is
 * {@link PostScoreSentimentalEquilibriumPreScored}.
 *
 * Follows the same pattern as PostScoreDynamicInteractionParameterised.
 */
public class PostScoreSentimentalEquilibrium extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreSentimentalEquilibrium.class.getName());

	public PostScoreSentimentalEquilibrium() {
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
			JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");

			JSONObject offerMatrixWithKey = new JSONObject();
			boolean om = false;
			if (params.has("offerMatrixWithKey")) {
				offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");
				om = true;
			}

			JSONObject work = params.getJSONObject("in_params");

			JSONArray options = getOptions(params);
			JSONObject optionParams = getOptionsParams(params);

			JSONObject contextual_variables = optionParams.getJSONObject("contextual_variables");

			if (!work.has("contextual_variable_one")) {
				if (featuresObj.has(contextual_variables.getString("contextual_variable_one_name")))
					work.put("contextual_variable_one", featuresObj.get(contextual_variables.getString("contextual_variable_one_name")));
				else
					work.put("contextual_variable_one", "");
			}
			if (!work.has("contextual_variable_two")) {
				if (featuresObj.has(contextual_variables.getString("contextual_variable_two_name")))
					work.put("contextual_variable_two", featuresObj.get(contextual_variables.getString("contextual_variable_two_name")));
				else
					work.put("contextual_variable_two", "");
			}

			String contextual_variable_one = String.valueOf(work.get("contextual_variable_one"));
			String contextual_variable_two = String.valueOf(work.get("contextual_variable_two"));

			JSONArray finalOffers = new JSONArray();
			int offerIndex = 0;

			for (int j = 0; j < options.length(); j++) {
				JSONObject option = options.getJSONObject(j);

				String offer = option.getString("optionKey");

				if (om && !offerMatrixWithKey.has(offer)) {
					continue;
				}

				String cvOneOption = option.has("contextual_variable_one") ? String.valueOf(option.get("contextual_variable_one")) : "";
				String cvTwoOption = option.has("contextual_variable_two") ? String.valueOf(option.get("contextual_variable_two")) : "";

				if (!cvOneOption.equals(contextual_variable_one) || !cvTwoOption.equals(contextual_variable_two))
					continue;

				double arm_reward = 0.001;
				if (option.has("arm_reward")) {
					arm_reward = (double) DataTypeConversions.getDoubleFromIntLong(option.get("arm_reward"));
				}
				double alpha = option.has("alpha") ? (double) DataTypeConversions.getDoubleFromIntLong(option.get("alpha")) : arm_reward;
				double beta = option.has("beta") ? (double) DataTypeConversions.getDoubleFromIntLong(option.get("beta")) : 1.0 - arm_reward;

				if (arm_reward != arm_reward) arm_reward = 0.0;
				if (alpha != alpha) alpha = 0.0;
				if (beta != beta) beta = 0.0;

				double offer_value = 1.0;
				double offer_cost = 1.0;
				double modified_offer_score = arm_reward;

				if (om && offerMatrixWithKey.has(offer)) {
					JSONObject singleOffer = offerMatrixWithKey.getJSONObject(offer);

					if (singleOffer.has("offer_price"))
						offer_value = DataTypeConversions.getDouble(singleOffer, "offer_price");
					if (singleOffer.has("price"))
						offer_value = DataTypeConversions.getDouble(singleOffer, "price");

					if (singleOffer.has("offer_cost"))
						offer_cost = singleOffer.getDouble("offer_cost");
					if (singleOffer.has("cost"))
						offer_cost = singleOffer.getDouble("cost");

					modified_offer_score = arm_reward * ((double) offer_value - offer_cost);
				}

				JSONObject finalOffersObject = new JSONObject();
				finalOffersObject.put("offer", offer);
				finalOffersObject.put("offer_name", option.has("option") ? option.getString("option") : offer);
				finalOffersObject.put("score", arm_reward);
				finalOffersObject.put("arm_reward", arm_reward);
				finalOffersObject.put("modified_offer_score", modified_offer_score);
				finalOffersObject.put("alpha", alpha);
				finalOffersObject.put("beta", beta);
				finalOffersObject.put("price", offer_value);
				finalOffersObject.put("cost", offer_cost);
				finalOffersObject.put("offer_value", offer_value);
				finalOffersObject.put("contextual_variable_one", contextual_variable_one);
				finalOffersObject.put("contextual_variable_two", contextual_variable_two);

				if (params.has("uuid"))
					finalOffersObject.put("uuid", params.get("uuid"));

				if (option.has("propensity"))
					finalOffersObject.put("propensity", option.get("propensity"));
				if (option.has("weighting"))
					finalOffersObject.put("weighting", option.get("weighting"));

				finalOffers.put(offerIndex, finalOffersObject);
				offerIndex++;
			}

			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "arm_reward", "double", "d");
			predictModelMojoResult.put("final_result", sortJsonArray);

		} catch (Exception e) {
			LOGGER.error("PostScoreSentimentalEquilibrium:getPostPredict:E001: " + e.getMessage());
			e.printStackTrace();
		}

		predictModelMojoResult = getTopScores(params, predictModelMojoResult);

		double endTimePost = System.nanoTime();
		LOGGER.info("PostScoreSentimentalEquilibrium:I001: execution time in ms: ".concat(String.valueOf((endTimePost - startTimePost) / 1000000)));
		return predictModelMojoResult;
	}
}
