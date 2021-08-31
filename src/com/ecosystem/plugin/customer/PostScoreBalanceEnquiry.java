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
import java.util.ArrayList;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * Customer plugin for specialized logic to be added to the runtime engine. This class is loaded through the plugin
 * loader system.
 */
public class PostScoreBalanceEnquiry {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreBalanceEnquiry.class.getName());

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

	public PostScoreBalanceEnquiry() {
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
		try {
			/* Setup JSON objects for specific prediction case */
			JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
			JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");
			JSONArray offerMatrix = params.getJSONArray("offerMatrix");
			JSONObject work = params.getJSONObject("in_params");

			// TODO VALIDATE ALL FIENDS USED FROM OFFER_MATRIX AND FEATURESTORE EG PRICE

			/* Configure rules for balance enquiry case */
			double voice_balance = DataTypeConversions.getDouble(work, "voice_balance");
			double data_balance = DataTypeConversions.getDouble(work, "data_balance");
			double in_balance = DataTypeConversions.getDouble(work, "in_balance");

			/* Return input result if key feautes are missing */
			if (!(featuresObj.has("daily_voice_usage_avg")) | !(featuresObj.has("daily_data_usage_avg"))) {
				LOGGER.error("PostScoreBalanceEnquiry:offerRecommender:E001-1: " + params.get("uuid") + " - Feature Store does not contain key fields. Check that customer and key features are available. (daily_voice_usage_avg)");
				return predictModelMojoResult;
			}

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

			double daily_voice_usage_avg = featuresObj.getDouble("daily_voice_usage_avg");
			double daily_data_usage_avg = featuresObj.getDouble("daily_data_usage_avg");

			String preferred = null;
			if ((voice_balance >= daily_voice_usage_avg) & (data_balance < daily_data_usage_avg)) preferred = "Data";
			if ((voice_balance < daily_voice_usage_avg) & (data_balance >= daily_data_usage_avg)) preferred = "Voice";
			if ((voice_balance < daily_voice_usage_avg) & (data_balance < daily_data_usage_avg)) preferred = "IntegratedBundle";
			if ((voice_balance >= daily_voice_usage_avg) & (data_balance >= daily_data_usage_avg)) preferred = "Any";

			JSONArray finalOffers = new JSONArray();

			/* For each offer in offer matrix determine eligibility */
			int offerIndex = 0;
			for (int i = 0; i < offerMatrix.length(); i++) {
				JSONObject singleOffer = offerMatrix.getJSONObject(i);

				/* If whitelist settings then only allow offers on list */
				if (offerWhiteList.size() > 0) {
					boolean skip = true;
					for (int w = 0; w < offerWhiteList.size(); w++) {
						if ((singleOffer.getString("offer_name_final").equalsIgnoreCase(offerWhiteList.get(w)))) {
							//System.out.println("Whitelist Item: " + offerWhiteList.get(w) + " -> to offer -> " + singleOffer.getString("offer_name"));
							skip = false;
							w = offerWhiteList.size() + 1;
						}
					}
					if (skip) continue;
				}

				/* get selector field from properties: predictor.selector.setup */
				//String s = new JSONObject(settings.getSelectorSetup()).getJSONObject("lookup").getString("fields");
                String s = new String("payment_method_code");

				if (!offerMatrix.getJSONObject(i).has(s)) { LOGGER.error("Not in offerMatrix: ".concat(s)); break; }
				if (!featuresObj.has(s)) { LOGGER.error("Not in featuresObj: " + s); break; }

                String payment_method_code = (String) featuresObj.get("payment_method_code");
                if (payment_method_code.equalsIgnoreCase("h")){
                    payment_method_code = "p";
                }

				if (offerMatrix.getJSONObject(i).getString(s).equalsIgnoreCase(payment_method_code)) {
					String cop_car = singleOffer.getString("cop_car").toLowerCase();
					double cop_car_value;
					if (featuresObj.has(cop_car)) {
						/* if cop_car has a null or NaN it should be set to 0.0 */
						try {
							cop_car_value = featuresObj.getDouble(cop_car);
						} catch (Exception e) {
							cop_car_value = 0.0;
						}
					} else {
						cop_car_value = 0.0;
					}

					double offer_value = singleOffer.getDouble("price") - cop_car_value * singleOffer.getDouble("alpha");
					if (offer_value >= 0 | whitelist) {
						double offer_score = 0.0;
						/* test if offer name is in domains */
						if (domainsProbabilityObj.has(singleOffer.getString("offer_name_final"))) {
							offer_score = domainsProbabilityObj.getDouble(singleOffer.getString("offer_name_final"));
						} else {
							LOGGER.error("PostScoreBalanceEnquiry:offerRecommender:E002-1: " + params.get("uuid") + " - Not available: " + singleOffer.getString("offer_name_final"));
						}

                        if (offerMatrix.getJSONObject(i).getString("offer_type").equals("Voice"))
							offer_score = 0.5;

						double modified_offer_score = 1.0;
                        boolean offerTypeRule = (preferred.equalsIgnoreCase("Any") | preferred.equalsIgnoreCase(singleOffer.getString("offer_type")));

						if ((preferred.equals("Any") | (preferred.equals(singleOffer.getString("offer_type"))))) {
							modified_offer_score = offer_score * (double) singleOffer.optInt("offer_weight");
						} else {
							modified_offer_score = offer_score * (double) singleOffer.optInt("offer_weight") * 0.0000000000000000000000000000000001;
						}

						if (offerMatrix.getJSONObject(i).getString("payment_method_code").equalsIgnoreCase("C"))
							in_balance = 0.0;

						JSONObject finalOffersObject = new JSONObject();
						finalOffersObject.put("offer_name", singleOffer.getString("offer_name_final"));
						finalOffersObject.put("score", offer_score);
						finalOffersObject.put("modified_offer_score", modified_offer_score);
						finalOffersObject.put("offer_value", offer_value);

						finalOffersObject.put("voice_balance", voice_balance);
						finalOffersObject.put("data_balance", data_balance);

						finalOffersObject.put("daily_voice_usage_avg", daily_voice_usage_avg);
						finalOffersObject.put("daily_data_usage_avg", daily_data_usage_avg);

						finalOffersObject.put("preferred", preferred);
						finalOffersObject.put("offer_matrix", singleOffer);

						// TODO OBTAIN BUDGET PRIORITY AND ASSIGN
						if (settings.getPredictorOfferBudget() != null) {
							JSONObject budgetItem = obtainBudget(singleOffer, params.getJSONObject("featuresObj"), offer_value);
							double budgetSpendLimit = budgetItem.getDouble("spend_limit");
							finalOffersObject.put("spend_limit", budgetSpendLimit);
						}

						/* set selection rules */
						boolean ruleSelect = false;
						boolean rule1 = (in_balance > 0.0 & singleOffer.getDouble("price") < in_balance);
						boolean rule2 = (in_balance <= 0);

                        String whitelist_only = "y";
				        if (singleOffer.has("whitelist_only_yn")) whitelist_only = singleOffer.getString("whitelist_only_yn");
                        boolean rule3 = (whitelist_only.equalsIgnoreCase("n") | whitelist_only.equalsIgnoreCase("y") & whitelist);

						if(rule3){
                            if (rule1 | rule2) ruleSelect = true;
                            if (whitelist) ruleSelect = true;
                        }

						/* eligible for offer */
						if (ruleSelect) {
							finalOffers.put(offerIndex, finalOffersObject);
							offerIndex = offerIndex + 1;
						}

					}
				}
			}

			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "modified_offer_score", "double", "d");
			predictModelMojoResult.put("final_result", sortJsonArray);

		} catch (Exception e) {
			LOGGER.error(e);
		}

		predictModelMojoResult = getTopScores(params, predictModelMojoResult);
		return predictModelMojoResult;

	}

	public void offerRecommender() {
	}

	/**
	 * Review this: Master version in EcosystemMaster class.
	 *
	 * @param predictResult
	 * @param numberOffers
	 * @return
	 */
	public static JSONArray getSelectedPredictResult(JSONObject predictResult, int numberOffers) {
		JSONArray offers = new JSONArray();

		for (int j = 0; j < numberOffers; j++) {
			JSONObject work = predictResult.getJSONArray("final_result").getJSONObject(j);
			JSONObject offer = new JSONObject();
			offer.put("rank", j + 1);
			offer.put("result", setValues(work));
			offer.put("offer_matrix", work.getJSONObject("offer_matrix"));
			offers.put(j, offer);
		}
		return offers;
	}

	/**
	 * Review this: Master version in EcosystemMaster class.
	 * Get random results for MAB
	 *
	 * @param predictResult
	 * @param numberOffers
	 * @return
	 */
	public static JSONArray getSelectedPredictResultRandom(JSONObject predictResult, int numberOffers) {
		JSONArray offers = new JSONArray();
		/* If there's no 'final' return an empty array */
		if (predictResult.has("final_result")) {
			int resultLength = predictResult.getJSONArray("final_result").length() - 1;
			for (int j = 0; j < numberOffers; j++) {
				int rand = MathRandomizer.getRandomIntBetweenRange(0, resultLength);
				JSONObject work = predictResult.getJSONArray("final_result").getJSONObject(rand);
				JSONObject offer = new JSONObject();
				offer.put("rank", j+1);
				offer.put("result", setValues(work));
				offer.put("offer_matrix", work.getJSONObject("offer_matrix"));
				offers.put(j, offer);
			}
		}
		return offers;
	}

	private static JSONObject setValues(JSONObject work) {
		JSONObject result = new JSONObject();
		result.put("score", work.get("score"));
		result.put("offer_name", work.get("offer_name"));
		result.put("modified_offer_score", work.get("modified_offer_score"));
		result.put("offer_value", work.get("offer_value"));

		result.put("daily_voice_usage_avg", work.get("daily_voice_usage_avg"));
		result.put("daily_data_usage_avg", work.get("daily_data_usage_avg"));

		result.put("voice_balance", work.get("voice_balance"));
		result.put("data_balance", work.get("data_balance"));

		result.put("preferred", work.get("preferred"));
		return result;
	}

	/**
	 * @param params
	 * @param predictResult
	 * @return
	 */
	private static JSONObject getTopScores(JSONObject params, JSONObject predictResult) {
		int resultCount = 1;
		predictResult.put("explore", 0);

		if (params.has("resultcount")) resultCount = params.getInt("resultcount");
		if (predictResult.getJSONArray("final_result").length() <= resultCount)
			resultCount = predictResult.getJSONArray("final_result").length();

		/* depending on epsilon and mab settings */
		if (params.getInt("explore") == 0) {
			predictResult.put("final_result", PostScoreBalanceEnquiry.getSelectedPredictResult(predictResult, resultCount));
			predictResult.put("explore", 0);
		} else {
			predictResult.put("final_result", PostScoreBalanceEnquiry.getSelectedPredictResultRandom(predictResult, resultCount));
			predictResult.put("explore", 1);
		}
		return predictResult;
	}


}
