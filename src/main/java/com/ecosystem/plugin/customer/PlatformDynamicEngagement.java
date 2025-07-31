package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * ECOSYSTEM.AI INTERNAL PLATFORM SCORING
 * Use this class to score with dynamic sampling configurations. This class is configured to work with no model.
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

//			params.put("business_logic", "api");
//			params.put("business_logic_params", "dowork");
//			params.put("business_logic_action", "http://localhost:8091/business");
//			params = BusinessLogic.getValues(params);

			/** Setup JSON objects for specific prediction case */
			JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
			//JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");

			JSONObject offerMatrixWithKey = new JSONObject();
			boolean om = false;
			if (params.has("offerMatrixWithKey")) {
				offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");
				om = true;
			} else {
				LOGGER.info("No Offer Matrix with key configured, using generated defaults.");
			}

			JSONObject work = params.getJSONObject("in_params");

			/***************************************************************************************************/
			/** Standardized approach to access dynamic datasets in plugin.
			 * The options array is the data set/feature_store that's keeping track of the dynamic changes.
			 * The optionParams is the parameter set that will influence the real-time behavior through param changes.
			 */
			/***************************************************************************************************/
			JSONArray options = getOptions(params);
			JSONObject optionParams = getOptionsParams(params);
			JSONObject locations = getLocations(params);

			JSONObject contextual_variables = optionParams.getJSONObject("contextual_variables");
			JSONObject randomisation = optionParams.getJSONObject("randomisation");

			/***************************************************************************************************/
			/** Test if contextual variable is coming via api or feature store: API takes preference... */
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

			/***************************************************************************************************/
			/** Test if offer eligibility is specified in in_params */
			boolean check_eligibility_list = false;
			JSONObject eligibility_list = new JSONObject();
			if (work.has("eligible_offers")) {
				LOGGER.info("PlatformDynamicEngagement:I002: eligible_offers item found in params, constructing list of eligible offers.");
				try {
					eligibility_list = work.getJSONObject("eligible_offers");
					if (!eligibility_list.isEmpty()) {
						check_eligibility_list = true;
						LOGGER.info("PlatformDynamicEngagement:I002: applying eligibility list: "+eligibility_list.toString());
					} else {
						LOGGER.warn("PlatformDynamicEngagement:W001: eligibility list is empty, skipping eligible_offers check");
					}
				} catch (Exception e) {
					LOGGER.warn("PlatformDynamicEngagement:I002: eligible_offers found but error occurred but error extracting eligibility list. Skipping eligible_offers check.");
				}
			}
			/***************************************************************************************************/

			JSONArray finalOffers = new JSONArray();
			int offerIndex = 0;
			int explore = 0;
			explore = params.getInt("explore");
			int[] optionsSequence = generateOptionsSequence(options.length(), options.length());
			String contextual_variable_one = String.valueOf(work.get("contextual_variable_one"));
			String contextual_variable_two = String.valueOf(work.get("contextual_variable_two"));

			for(int j : optionsSequence) {
				if (j > params.getInt("resultcount")) break;

				JSONObject option = options.getJSONObject(j);

				/** Skip the item if offer matrix does not contain option */
				/*
				if (!offerMatrixWithKey.has(option.getString("optionKey")))
					continue;
				 */
				/** GENERATE DEFAULT IF OPTION IS NOT IN OFFER MATRIX! */
				String offer = option.getString("optionKey");
				if (!offerMatrixWithKey.has(option.getString("optionKey"))) {
					JSONObject singleOffer = defaultOffer(offer);
					offerMatrixWithKey.put(option.getString("optionKey"), singleOffer);
					LOGGER.warn("BEWARE, DEFAULT OFFER GENERATED. IN OPTIONS STORE AND NOT OFFER MATRIX: " + option.getString("optionKey"));
				}

				/** Test eligibility TODO: CREATE A SEPARATE SUPERCLASS WITH THIS IN IT! */
				if (locations != null) {
					try {
						if (locations.getJSONObject(offer).has("open_times")) {
							String day = params.getJSONObject("in_params").getString("day");
							String time = params.getJSONObject("in_params").getString("time");

							if (locations.getJSONObject(offer).getJSONObject("open_times").has(day)) {
								if (locations.getJSONObject(offer).getJSONObject("open_times").getJSONObject(day).has("opening1") &&
										locations.getJSONObject(offer).getJSONObject("open_times").getJSONObject(day).has("closing1")) {

									LOGGER.info("It's Open!");
									if (!locations.getJSONObject(offer).getJSONObject("open_times").getString("operatingStatus").equals("operating"))
										continue;

									SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a");

									Date opening = sdf.parse(locations.getJSONObject(offer).getJSONObject("open_times").getJSONObject(day).getString("opening1"));
									Date closing = sdf.parse(locations.getJSONObject(offer).getJSONObject("open_times").getJSONObject(day).getString("closing1"));
									if (closing.before(opening)) {
										Calendar cal = Calendar.getInstance();
										cal.setTime(closing);
										cal.add(Calendar.DATE, 1);
										closing = cal.getTime();
									}
									Date time_now = sdf.parse(time);
									if (time_now.after(opening) && time_now.before(closing)) {
										LOGGER.info("It's Open!");
									} else {
										continue;
									}

								}
							}
						}
					} catch (Exception e) {
						LOGGER.info("\n\n" + offer + " -> Oh no, there's something wrong with the time range check, and will be ignored! use api params: {day:'monday', 'time': '11.00 AM'} " + e.getMessage() + "\n\n");
					}
				}


//				String contextual_variable_one_Option = "";
//				if (option.has("contextual_variable_one") && !contextual_variable_one.equals(""))
//					contextual_variable_one_Option = String.valueOf(option.get("contextual_variable_one"));
//				String contextual_variable_two_Option = "";
//				if (option.has("contextual_variable_two") && !contextual_variable_two.equals(""))
//					contextual_variable_two_Option = String.valueOf(option.get("contextual_variable_two"));

				/* If eligibility list has been extracted from in_params, check that offer is eligible*/
				boolean eligibility_from_params = true;
				if (check_eligibility_list) {
					if (!eligibility_list.has(offer)) {
						eligibility_from_params = false;
					}
				}

				if (compareContextualVariableValues(option, work) & eligibility_from_params) {

					double alpha = (double) DataTypeConversions.getDoubleFromIntLong(option.get("alpha"));
					double beta = (double) DataTypeConversions.getDoubleFromIntLong(option.get("beta"));
					double accuracy = 0.001;
					if (option.has("accuracy"))
						accuracy = (double) DataTypeConversions.getDoubleFromIntLong(option.get("accuracy"));

					/***************************************************************************************************/
					/* r IS THE RANDOMIZED SCORE VALUE */
					double p = 0.0;
					double arm_reward = 0.001;
					double learning_reward = 1.0;

					if (option.has("arm_reward")) {
						p = (double) option.get("arm_reward");
					} else {
						p = arm_reward;
					}
					arm_reward = p;

					if (option.has("learning_reward")) {
						learning_reward = (double) option.get("learning_reward");
					}

					/** Check if values are correct */
					if (p != p) p = 0.0;
					if (alpha != alpha) alpha = 0.0;
					if (beta != beta) beta = 0.0;
					if (arm_reward != arm_reward) arm_reward = 0.0;
					/***************************************************************************************************/

					JSONObject singleOffer = new JSONObject();
					double offer_value = 1.0;
					double offer_cost = 1.0;
					double modified_offer_score = p;
					if (om) {
						if (offerMatrixWithKey.has(offer)) {

							singleOffer = offerMatrixWithKey.getJSONObject(offer);

							if (singleOffer.has("offer_price"))
								offer_value = DataTypeConversions.getDouble(singleOffer, "offer_price");
							if (singleOffer.has("price"))
								offer_value = DataTypeConversions.getDouble(singleOffer, "price");

							if (singleOffer.has("offer_cost"))
								offer_cost = singleOffer.getDouble("offer_cost");
							if (singleOffer.has("cost"))
								offer_cost = singleOffer.getDouble("cost");

							modified_offer_score = p * ((double) offer_value - offer_cost);
						}
					}

					JSONObject finalOffersObject = new JSONObject();

					finalOffersObject.put("offer", offer);
					finalOffersObject.put("offer_name", offer);
					if (!option.has("option"))
						finalOffersObject.put("offer_name_desc", offer);
					else
						finalOffersObject.put("offer_name_desc", option.getString("option"));
					/* process final */
					finalOffersObject.put("score", p);
					finalOffersObject.put("final_score", p);
					finalOffersObject.put("modified_offer_score", modified_offer_score);
					finalOffersObject.put("offer_value", offer_value);
					finalOffersObject.put("price", offer_value);
					finalOffersObject.put("cost", offer_cost);

					finalOffersObject.put("p", p);
					if (option.has("contextual_variable_one"))
						finalOffersObject.put("contextual_variable_one", contextual_variable_one);
					else
						finalOffersObject.put("contextual_variable_one", "");

					if (option.has("contextual_variable_two"))
						finalOffersObject.put("contextual_variable_two", contextual_variable_two);
					else
						finalOffersObject.put("contextual_variable_two", "");

					finalOffersObject.put("alpha", alpha);
					finalOffersObject.put("beta", beta);
					if (!option.has("weighting"))
						finalOffersObject.put("weighting", -1.0);
					else
						finalOffersObject.put("weighting", (double) DataTypeConversions.getDoubleFromIntLong(option.get("weighting")));
					finalOffersObject.put("explore", explore);
					finalOffersObject.put("uuid", params.get("uuid"));
					finalOffersObject.put("arm_reward", arm_reward);
					finalOffersObject.put("learning_reward", learning_reward);

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

			predictModelMojoResult = getTopScores(params, predictModelMojoResult);

			double endTimePost = System.nanoTime();
			LOGGER.info("PlatformDynamicEngagement:I001: time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));

		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(e);
		}

		return predictModelMojoResult;

	}

}
