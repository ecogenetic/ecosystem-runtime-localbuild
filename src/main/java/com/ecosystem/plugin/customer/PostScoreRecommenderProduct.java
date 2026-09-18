package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.JSONArraySort;
import hex.genmodel.easy.EasyPredictModelWrapper;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * This the ecosystem/Ai personality series.
 * Customer plugin for specialized logic to be added to the runtime engine. This class is loaded through the plugin
 * loader system.
 */
public class PostScoreRecommenderProduct extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreRecommenderProduct.class.getName());

    public PostScoreRecommenderProduct() {
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
		try {
			/* Setup JSON objects for specific prediction case */
			JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
			JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");
			JSONArray offerMatrix = params.getJSONArray("offerMatrix");
//			JSONObject work = params.getJSONObject("params");

			/* If whitelist settings then only allow offers on list */
			ArrayList<String> offerWhiteList = new ArrayList<>();
			if (params.has("whitelist")) {
				if (!params.getJSONObject("whitelist").isEmpty()) {
					Object configuredWhitelist = params.getJSONObject("whitelist").get("whitelist");
					if (configuredWhitelist instanceof JSONArray) {
						JSONArray whitelistArray = (JSONArray) configuredWhitelist;
						for (int i = 0; i < whitelistArray.length(); i++) {
							offerWhiteList.add(whitelistArray.getString(i));
						}
					} else if (configuredWhitelist instanceof Iterable<?>) {
						for (Object offer : (Iterable<?>) configuredWhitelist) {
							offerWhiteList.add(String.valueOf(offer));
						}
					}
					params.put("resultcount", offerWhiteList.size());
				}
			}

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
							skip = false;
							w = offerWhiteList.size() + 1;
						}
					}
					if (skip) continue;
				}

				if (matchesSelector(singleOffer, featuresObj, params)) {
					// String cop_car = singleOffer.getString("cop_car").toLowerCase();

                String offer = resolveOffer(singleOffer, domainsProbabilityObj);
                if (!domainsProbabilityObj.has(offer)) {
                    LOGGER.error("PostScoreRecommenderProduct:E002: " + params.get("uuid")
                            + " - Not available in probabilities: " + offer + "\n" + domainsProbabilityObj.toString());
                    continue;
                }

                double p = domainsProbabilityObj.getDouble(offer);
                double offer_value = 1.0;
                double offer_cost = 1.0;

                if (singleOffer.has("offer_price"))
                    offer_value = DataTypeConversions.getDouble(singleOffer, "offer_price");
                if (singleOffer.has("price"))
                    offer_value = DataTypeConversions.getDouble(singleOffer, "price");

                if (singleOffer.has("offer_cost"))
                    offer_cost = singleOffer.getDouble("offer_cost");
                if (singleOffer.has("cost"))
                    offer_cost = singleOffer.getDouble("cost");

                double modified_offer_score = p * ((double) offer_value - offer_cost);


                JSONObject finalOffersObject = new JSONObject();


                /* process final */
                finalOffersObject.put("offer", offer);
                finalOffersObject.put("offer_name", resolveOfferName(singleOffer, offer));
                finalOffersObject.put("score", p);
                finalOffersObject.put("final_score", p);
                finalOffersObject.put("modified_offer_score", modified_offer_score);
                finalOffersObject.put("offer_value", offer_value);
                finalOffersObject.put("price", offer_value);
                finalOffersObject.put("cost", offer_cost);
                finalOffersObject.put("p", p);

				finalOffersObject.put("offer_matrix", singleOffer);
                finalOffersObject.put("product", singleOffer);

				// TODO OBTAIN BUDGET PRIORITY AND ASSIGN
				GlobalSettings activeSettings = PostScoreSuper.getSettings(params);
				if (activeSettings != null && activeSettings.getPredictorOfferBudget() != null) {
					JSONObject budgetItem = obtainBudget(singleOffer, params.getJSONObject("featuresObj"), 0.0);
					double budgetSpendLimit = budgetItem.getDouble("spend_limit");
					finalOffersObject.put("spend_limit", budgetSpendLimit);
				}

				finalOffers.put(offerIndex, finalOffersObject);
				offerIndex = offerIndex + 1;
				}
			}

			JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "modified_offer_score", "double", "d");
			predictModelMojoResult.put("final_result", sortJsonArray);

		} catch (Exception e) {
			LOGGER.error(e);
		}

		if (!predictModelMojoResult.has("final_result")) {
			predictModelMojoResult.put("final_result", new JSONArray());
		}
		predictModelMojoResult = PostScoreSuper.getTopScores(params, predictModelMojoResult);
		return predictModelMojoResult;

	}

	private static boolean matchesSelector(JSONObject singleOffer, JSONObject featuresObj, JSONObject params) {
		try {
			GlobalSettings activeSettings = PostScoreSuper.getSettings(params);
			String selectorSetup = activeSettings != null ? activeSettings.getSelectorSetup() : null;
			if (selectorSetup == null || selectorSetup.isBlank()) {
				return true;
			}

			String s = new JSONObject(selectorSetup).getJSONObject("lookup").getString("fields");
			if (!singleOffer.has(s)) {
				LOGGER.error("Not in offerMatrix: ".concat(s));
				return false;
			}
			if (!featuresObj.has(s)) {
				LOGGER.error("Not in featuresObj: " + s);
				return false;
			}
			return singleOffer.getString(s).equalsIgnoreCase(featuresObj.getString(s));
		} catch (Exception e) {
			LOGGER.warn("PostScoreRecommenderProduct:W001: selector setup not available, scoring all products.");
			return true;
		}
	}

	private static String resolveOffer(JSONObject singleOffer, JSONObject domainsProbabilityObj) {
		String[] keys = new String[]{"offer_id", "offer", "offer_name"};
		for (String key : keys) {
			String value = singleOffer.optString(key, "").trim();
			if (!value.isEmpty() && domainsProbabilityObj.has(value)) {
				return value;
			}
		}

		for (String key : keys) {
			String value = singleOffer.optString(key, "").trim();
			if (!value.isEmpty()) {
				return value;
			}
		}
		return "";
	}

	private static String resolveOfferName(JSONObject singleOffer, String fallback) {
		String offerName = singleOffer.optString("offer_name_final", "").trim();
		if (!offerName.isEmpty()) return offerName;

		offerName = singleOffer.optString("offer_name", "").trim();
		if (!offerName.isEmpty()) return offerName;

		offerName = singleOffer.optString("offer", "").trim();
		if (!offerName.isEmpty()) return offerName;

		offerName = singleOffer.optString("offer_id", "").trim();
		if (!offerName.isEmpty()) return offerName;

		return fallback;
	}

	public void offerRecommender() {
	}
}
