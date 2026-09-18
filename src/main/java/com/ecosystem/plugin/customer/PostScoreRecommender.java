package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.MathRandomizer;
import hex.genmodel.easy.EasyPredictModelWrapper;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.HashSet;

/**
 * ECOSYSTEM.AI INTERNAL PLATFORM SCORING
 * Use this class to perform generic scoring based on model and generic settings with label from scoring.
 */
public class PostScoreRecommender {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreRecommender.class.getName());

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

	public PostScoreRecommender() {
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
			if (predictModelMojoResult.has("ErrorMessage")) {
				LOGGER.error("getPostPredict:E001a:" + predictModelMojoResult.get("ErrorMessage"));
				return null;
			}

			JSONObject domainsProbabilityObj = predictModelMojoResult.getJSONObject("domainsProbabilityObj");
			if (!predictModelMojoResult.has("label"))
				LOGGER.error("getPostPredict:E001b: No label found in predictModelMojoResult. Check that the configuration is correct.");

			if (predictModelMojoResult.getJSONArray("label").isEmpty())
				LOGGER.error("getPostPredict:E001c: Empty label array found in predictModelMojoResult. Check that the configuration is correct.");

			JSONArray domains = predictModelMojoResult.getJSONArray("domains");

			JSONArray finalOffers = new JSONArray();
			int offerIndex = 0;
			JSONArray scoredDomains = getScoredDomains(domains, domainsProbabilityObj);

			/** Select scored domains/offers; getTopScores will trim to resultcount after sorting. */
			for (int i = 0; i < scoredDomains.length(); i++) {
				String domain = String.valueOf(scoredDomains.get(i));
				if (!domainsProbabilityObj.has(domain)) continue;
				int explore = (int) params.get("explore");
				JSONObject finalOffersObject = new JSONObject();

				finalOffersObject.put("offer", domain);
				finalOffersObject.put("offer_name", domain);
				finalOffersObject.put("offer_name_desc", domain + " - " + offerIndex);

				/** process final */
				double p = domainsProbabilityObj.getDouble(domain);
				finalOffersObject.put("score", p);
				finalOffersObject.put("final_score", p);
				finalOffersObject.put("modified_offer_score", p);
				finalOffersObject.put("offer_value", 1.0); // use value from offer matrix
				finalOffersObject.put("price", 1.0);
				finalOffersObject.put("cost", 1.0);
				finalOffersObject.put("uuid", params.get("uuid"));

				finalOffersObject.put("p", p);
				finalOffersObject.put("explore", explore);

				/** Prepare array before final sort */
				finalOffers.put(offerIndex, finalOffersObject);
				offerIndex = offerIndex + 1;
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

	private static JSONArray getScoredDomains(JSONArray domains, JSONObject domainsProbabilityObj) {
		JSONArray scoredDomains = new JSONArray();
		HashSet<String> seen = new HashSet<>();

		if (domains != null && !domains.isEmpty()) {
			Object domainSource = domains.get(0);
			if (domainSource instanceof JSONArray) {
				JSONArray domainArray = (JSONArray) domainSource;
				for (int i = 0; i < domainArray.length(); i++) {
					addDomain(scoredDomains, seen, String.valueOf(domainArray.get(i)));
				}
			} else if (domainSource != null && domainSource.getClass().isArray()) {
				int length = Array.getLength(domainSource);
				for (int i = 0; i < length; i++) {
					addDomain(scoredDomains, seen, String.valueOf(Array.get(domainSource, i)));
				}
			}
		}

		for (String domain : domainsProbabilityObj.keySet()) {
			addDomain(scoredDomains, seen, domain);
		}

		return scoredDomains;
	}

	private static void addDomain(JSONArray scoredDomains, HashSet<String> seen, String domain) {
		if (domain == null || domain.isBlank() || seen.contains(domain)) return;
		seen.add(domain);
		scoredDomains.put(domain);
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
		if (work.has("price"))
			result.put("price", work.get("price"));
		if (work.has("cost"))
			result.put("cost", work.get("cost"));
		result.put("final_score", work.get("score"));
		result.put("offer", work.get("offer"));
		result.put("offer_name", work.get("offer_name"));
		result.put("modified_offer_score", work.get("modified_offer_score"));
		result.put("offer_value", work.get("offer_value"));
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
		HashSet<Integer> selectedRandomIndexes = new HashSet<>();

		for (int j = 0, k = 0; j < resultLength; j++) {
			JSONObject work = new JSONObject();
			if (explore == 1) {
				int rand;
				do {
					rand = MathRandomizer.getRandomIntBetweenRange(0, resultLength - 1);
				} while (selectedRandomIndexes.contains(rand) && selectedRandomIndexes.size() < resultLength);
				selectedRandomIndexes.add(rand);
				work = predictResult.getJSONArray("final_result").getJSONObject(rand);
			} else {
				work = predictResult.getJSONArray("final_result").getJSONObject(j);
			}

			/* test if budget is enabled && spend_limit is greater than 0, if budget is disabled, then this will be 1.0 */
			if (settings != null && settings.getPredictorOfferBudget() != null) {
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
		int resultCount = 1;
		/* "Number of Offers" is normalized into resultcount before post-scoring. */
		if (params.has("resultcount")) resultCount = params.getInt("resultcount");
		if (predictResult.getJSONArray("final_result").length() <= resultCount)
			resultCount = predictResult.getJSONArray("final_result").length();

		/* depending on epsilon and mab settings */
		if (params.getInt("explore") == 0) {
			predictResult.put("final_result", getSelectedPredictResult(predictResult, resultCount));
			predictResult.put("explore", 0);
		} else {
			predictResult.put("final_result", getSelectedPredictResultRandom(predictResult, resultCount));
			predictResult.put("explore", 1);
		}
		return predictResult;
	}

}
