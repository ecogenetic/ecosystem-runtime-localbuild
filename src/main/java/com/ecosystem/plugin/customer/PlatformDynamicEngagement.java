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

/**
 * ECOSYSTEM.AI INTERNAL PLATFORM SCORING
 * Use this class to score with dynamic sampling configurations. This class is configured to work with no model.
 * 20 Apr 2022 - Updated
 */
public class PlatformDynamicEngagement {
	private static final Logger LOGGER = LogManager.getLogger(PlatformDynamicEngagement.class.getName());

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
				if (option.has("contextual_variable_one"))
					contextual_variable_one_Option = String.valueOf(option.get("contextual_variable_one"));
				String contextual_variable_two_Option = "";
				if (option.has("contextual_variable_two"))
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
						params = getExplore(params, randomisation.getDouble("epsilon"), "explore");
						explore = params.getInt("explore");
						p = option.getDouble("weighting");
						if (explore == 0)
							arm_reward = MathRandomizer.getRandomIntBetweenRange(0, 1);
						else
							arm_reward = 1.0;
					} else {
						/** REMEMBER THAT THIS IS HERE BECAUSE OF BATCH PROCESS, OTHERWISE IT REQUIRES THE TOTAL COUNTS */
						/* Phase 2: sampling - calculate the arms and rank them */
						params.put("explore", 1); // force explore to zero and use Thompson Sampling only!!
						explore = 1; // set as explore as the dynamic responder is exploration based...
						p = DataTypeConversions.getDouble(option, "arm_reward");
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

			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "score", "double", "d");
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

	/**
	 * When epsilon greedy is used
	 * @param params
	 * @param epsilonIn
	 * @param name
	 * @return
	 */
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
		result.put("contextual_variable_one", work.get("contextual_variable_one"));
		result.put("contextual_variable_two", work.get("contextual_variable_two"));

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

		return predictResult;
	}


}
