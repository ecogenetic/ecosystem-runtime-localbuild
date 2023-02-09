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
 * ECOSYSTEM.AI INTERNAL PLATFORM SCORING
 * Use this class to score with dynamic sampling configurations. This class is configured to work with no model.
 * 1 Nov 2022 - Updated
 */
public class PlatformDynamicEngagement extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PlatformDynamicEngagement.class.getName());

	public PlatformDynamicEngagement() {
	}

	/**
	 * Pre-post predict logic
	 */
	public void getPostPredict () {
	}

	/**
	 * getPostPredict
	 * Example params:
	 *    {"contextual_variable_one":"Easy Income Gold|Thin|Senior", "contextual_variable_two":"", "batch": true}
	 *
	 * @param predictModelMojoResult Result from scoring
	 * @param params                 Params carried from input
	 * @param session                Session variable for Cassandra
	 * @return JSONObject result to further post-scoring logic
	 */
	public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
		double startTimePost = System.nanoTime();
		try {
			/* Setup JSON objects for specific prediction case */
			JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
			//JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");
			//JSONArray offerMatrix = params.getJSONArray("offerMatrix");
			JSONObject work = params.getJSONObject("in_params");

			/* Personality and base spend score */
			// double probability = (double) domainsProbabilityObj.get("1");
			//double probability = 1.0;

			/***************************************************************************************************/
			/** Standardized approach to access dynamic datasets in plugin.
			 * The options array is the data set/feature_store that's keeping track of the dynamic changes.
			 * The optionParams is the parameter set that will influence the real-time behavior through param changes.
			 */
			/***************************************************************************************************/
			JSONArray options = (JSONArray) ((
					(JSONObject) params.getJSONObject("dynamicCorpora")
							.get("dynamic_engagement_options")).get("data"));
			JSONObject optionParams = (JSONObject) ((
					(JSONObject) params.getJSONObject("dynamicCorpora")
							.get("dynamic_engagement")).get("data"));

			JSONObject contextual_variables = optionParams.getJSONObject("contextual_variables");
			JSONObject randomisation = optionParams.getJSONObject("randomisation");

			/***************************************************************************************************/
			/* Test if contextual variable is coming via api or feature store: API takes preference... */
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
			/***************************************************************************************************/

			JSONArray finalOffers = new JSONArray();
			int offerIndex = 0;
			int explore;
			String contextual_variable_one = String.valueOf(work.get("contextual_variable_one"));
			String contextual_variable_two = String.valueOf(work.get("contextual_variable_two"));
			for (int j = 0; j < options.length(); j++) {
				JSONObject option = options.getJSONObject(j);
				String contextual_variable_one_Option = "";
				if (option.has("contextual_variable_one") && !contextual_variable_one.equals(""))
					contextual_variable_one_Option = String.valueOf(option.get("contextual_variable_one"));
				String contextual_variable_two_Option = "";
				if (option.has("contextual_variable_two") && !contextual_variable_two.equals(""))
					contextual_variable_two_Option = String.valueOf(option.get("contextual_variable_two"));

				if (contextual_variable_one_Option.equals(contextual_variable_one) && contextual_variable_two_Option.equals(contextual_variable_two)) {

					double alpha = (double) DataTypeConversions.getDoubleFromIntLong(option.get("alpha"));
					double beta = (double) DataTypeConversions.getDoubleFromIntLong(option.get("beta"));
					double accuracy = 0.001;
					if (option.has("accuracy")) accuracy = (double) DataTypeConversions.getDoubleFromIntLong(option.get("accuracy"));

					/***************************************************************************************************/
					/* r IS THE RANDOMIZED SCORE VALUE */
					double p = 0.0;
					double arm_reward = 0.001;
					if (randomisation.getString("approach").equals("epsilonGreedy")) {
					//						params = getExplore(params, randomisation.getDouble("epsilon"), "explore");
					//						explore = params.getInt("explore");
					//						p = option.getDouble("weighting");
					//						if (explore == 1)
					//							arm_reward = MathRandomizer.getRandomIntBetweenRange(0, 1);
					//						else
					//							arm_reward = 1.0;

						params.put("explore", 0);
						explore = 0;
						p = DataTypeConversions.getDouble(option, "arm_reward");
						arm_reward = p;

					} else {

						/** REMEMBER THAT THIS IS HERE BECAUSE OF BATCH PROCESS, OTHERWISE IT REQUIRES THE TOTAL COUNTS */
						/* Phase 2: sampling - calculate the arms and rank them */
						params.put("explore", 0); // force explore to zero and use Thompson Sampling only!!
						explore = 0; // set as explore as the dynamic responder is exploration based...
						p = DataTypeConversions.getDouble(option, "arm_reward");
						arm_reward = p;

					}
					/***************************************************************************************************/

					JSONObject finalOffersObject = new JSONObject();

					finalOffersObject.put("offer", option.getString("optionKey"));
					finalOffersObject.put("offer_name", option.getString("optionKey"));
					finalOffersObject.put("offer_name_desc", option.getString("option"));

					/* process final */
					finalOffersObject.put("score", p);
					finalOffersObject.put("final_score", p);
					finalOffersObject.put("modified_offer_score", p);
					finalOffersObject.put("offer_value", 1.0);

					finalOffersObject.put("p", p);
					finalOffersObject.put("contextual_variable_one", contextual_variable_one_Option);
					finalOffersObject.put("contextual_variable_two", contextual_variable_two_Option);
					finalOffersObject.put("alpha", alpha);
					finalOffersObject.put("beta", beta);
					finalOffersObject.put("weighting", (double) DataTypeConversions.getDoubleFromIntLong(option.get("weighting")));
					finalOffersObject.put("explore", explore);
					finalOffersObject.put("uuid", params.get("uuid"));

					finalOffersObject.put("arm_reward", arm_reward);

					/* Debugging variables */
					if (!option.has("expected_takeup"))
						finalOffersObject.put("expected_takeup", -1.0);
					else
						finalOffersObject.put("expected_takeup", (double) DataTypeConversions.getDoubleFromIntLong(option.get("expected_takeup")));

					if (!option.has("propensity"))
						finalOffersObject.put("propensity", -1.0);
					else
						finalOffersObject.put("propensity", (double) DataTypeConversions.getDoubleFromIntLong(option.get("propensity")));

					if (!option.has("epsilon_nominated"))
						finalOffersObject.put("epsilon_nominated", -1.0);
					else
						finalOffersObject.put("epsilon_nominated", (double) DataTypeConversions.getDoubleFromIntLong(option.get("epsilon_nominated")));

					finalOffers.put(offerIndex, finalOffersObject);
					offerIndex = offerIndex + 1;
				}
			}

			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "arm_reward", "double", "d");
			predictModelMojoResult.put("final_result", sortJsonArray);

		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(e);
		}

		predictModelMojoResult = getTopScores(params, predictModelMojoResult);

		double endTimePost = System.nanoTime();
		LOGGER.info("PlatformDynamicEngagement:I001: time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));

		return predictModelMojoResult;

	}

}
