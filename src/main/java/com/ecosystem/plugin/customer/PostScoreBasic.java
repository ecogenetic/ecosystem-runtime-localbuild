package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.JSONArraySort;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * This the ecosystem/Ai generic post-score template.
 * Customer plugin for specialized logic to be added to the runtime engine.
 * This class is loaded through the plugin loader system.
 */
public class PostScoreBasic extends PostScoreSuper {
	private static final Logger LOGGER = LogManager.getLogger(PostScoreBasic.class.getName());

	public PostScoreBasic() {
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

			if (params.has("preloadCorpora")) {
				if (params.getJSONObject("preloadCorpora").has("network")) {
					JSONObject a = params.getJSONObject("preloadCorpora");
					JSONObject preloadCorpora = a.getJSONObject("network");
				}
			}

			JSONArray finalOffers = new JSONArray();
			int resultcount = (int) params.get("resultcount");
			/* For each offer in offer matrix determine eligibility */
			/* get selector field from properties: predictor.selector.setup */
			// String s = new JSONObject(settings.getSelectorSetup()).getJSONObject("lookup").getString("fields");

			/** This loop can be used to add number of offers/options to return result */
			JSONObject finalOffersObject = new JSONObject();
			int offerIndex = 0;
			for (int i = 0; i < resultcount; i++) {

				/** Model type based approaches */
				String type = "";
				boolean explainability = false;
				// LOGGER.info("predictModelMojoResult: " + predictModelMojoResult.toString());
				if (predictModelMojoResult.get("type").getClass().getName().toLowerCase().contains("array")) {
					type = predictModelMojoResult
							.getJSONArray("type")
							.get(0)
							.toString().toLowerCase().trim();
					if (predictModelMojoResult.has("shapley_contributions"))
						explainability = true;
				} else {
					type = ((String) predictModelMojoResult.get("type")).toLowerCase().trim();
				}

				/** Offer name, defaults to type (replace with offer matrix etc) */
				if (featuresObj.has("offer_name_final"))
					finalOffersObject.put("offer_name", featuresObj.get("offer_name_final"));
				else
					finalOffersObject.put("offer_name", type);

				if (featuresObj.has("offer"))
					finalOffersObject.put("offer", featuresObj.get("offer"));
				else
					finalOffersObject.put("offer", type);

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

				/** Score based on model type */
				if (type.contains("clustering")) {
					finalOffersObject.put("cluster", predictModelMojoResult.getJSONArray("cluster").get(0));
					finalOffersObject.put("score", DataTypeConversions.getDouble(domainsProbabilityObj, "score"));
					finalOffersObject.put("modified_offer_score", DataTypeConversions.getDouble(domainsProbabilityObj, "score"));
				} else if (type.contains("anomalydetection")) {
					double[] score = (double[]) domainsProbabilityObj.get("score");
					finalOffersObject.put("score", score[0]);
					finalOffersObject.put("modified_offer_score", score[0]);
				} else if (type.contains("regression")) {
					Object score = predictModelMojoResult.getJSONArray("value").get(0);
					finalOffersObject.put("score", score);
					finalOffersObject.put("modified_offer_score", score);
				} else if (type.contains("multinomial")) {
					Object probability = predictModelMojoResult.getJSONArray("probability").get(0);
					Object label = null;
					try {
						label = predictModelMojoResult.getJSONArray("label").get(0);
					} catch (Exception e) {
						LOGGER.error("PostScoreBasic:getPostPredict:E001: Error relates to scoring your model. The model wasn't loaded or is not accessible.");
						e.printStackTrace();
					}
					Object response = predictModelMojoResult.getJSONArray("response").get(0);
					finalOffersObject.put("score", probability);
					finalOffersObject.put("modified_offer_score", probability);
					finalOffersObject.put("offer", label);
					finalOffersObject.put("offer_name", response);
				} else if (type.contains("coxph")) {
					Object score = predictModelMojoResult.getJSONArray("value").get(0);
					finalOffersObject.put("score", score);
					finalOffersObject.put("modified_offer_score", score);
				} else if (type.contains("wordembedding")) {
					float[] score = (float[]) predictModelMojoResult.getJSONArray("_text_word2vec").get(0);
					finalOffersObject.put("score", Double.valueOf(String.valueOf(score[0])));
					finalOffersObject.put("embedding", score);
					finalOffersObject.put("modified_offer_score", 0.0);
				} else if (type.contains("deeplearning")) {
					/** From TensorFlow or PyTorch */
					Object score = domainsProbabilityObj.getDouble("1");
					finalOffersObject.put("score", score);
					finalOffersObject.put("modified_offer_score", score);
					Object response = predictModelMojoResult.getJSONArray("response").get(0);
					finalOffersObject.put("offer_name", response);
				} else if (type.contains("empty score")) {
					/** This is typically used for data lookup only, obtain values from feature store! */
					if (featuresObj.has("offer_name"))
						finalOffersObject.put("offer_name", featuresObj.get("offer_name"));

					if (featuresObj.has("offer"))
						finalOffersObject.put("offer", featuresObj.get("offer"));

					if (featuresObj.has("score"))
						finalOffersObject.put("score", Double.valueOf(String.valueOf(featuresObj.get("score"))));
					else
						finalOffersObject.put("score", 1.0);

					if (featuresObj.has("modified_offer_score"))
						finalOffersObject.put("modified_offer_score", Double.valueOf(String.valueOf(featuresObj.get("modified_offer_score"))));
					else
						finalOffersObject.put("modified_offer_score", 1.0);

					if (featuresObj.has("cost"))
						finalOffersObject.put("cost", Double.valueOf(String.valueOf(featuresObj.get("cost"))));
					else
						finalOffersObject.put("cost", 0.0);

				} else {
					finalOffersObject.put("score", 1.0);
					finalOffersObject.put("modified_offer_score", 1.0);
				}

				finalOffersObject.put("offer_details", domainsProbabilityObj);
				if (explainability) {
					finalOffersObject.put("shapley_contributions", predictModelMojoResult.get("shapley_contributions"));
					finalOffersObject.put("shapley_contributions_names", predictModelMojoResult.get("shapley_contributions_names"));
				}

				/** Default value, could be replaced by offer matrix or feature store */
				double offer_value = 1.0;
				finalOffersObject.put("offer_value", offer_value);
				finalOffersObject.put("uuid", params.get("uuid"));

				/** Add other structures to the final result */
				finalOffersObject.put("offer_matrix", featuresObj);

				/** Budget processing option, if it's set in the properties */
				if (settings.getPredictorOfferBudget() != null) {
					JSONObject budgetItem = obtainBudget(featuresObj, params.getJSONObject("featuresObj"), offer_value);
					double budgetSpendLimit = budgetItem.getDouble("spend_limit");
					finalOffersObject.put("spend_limit", budgetSpendLimit);
				}

				/** Prepare offer array before final sorting */
				finalOffers.put(offerIndex, finalOffersObject);
				offerIndex = offerIndex + 1;
			}

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

/**
 * Example JSONObject: params:

{
  "channel" : "app",
  "uuid" : "ee9a5288-6063-4a71-8a4a-5da16a5ec319",
  "userid" : "ecosystem",
  "in_params" : { },
  "dbparam" : true,
  "mab" : {
    "epsilon" : 0,
    "class" : "mabone"
  },
  "mojo" : "1",
  "offerMatrixStatic" : [ ],
  "UPDATE" : true,
  "value" : [ "Grade12", "F", "English", 7, 2, 1, "Unmarried", 35, "Owner" ],
  "duration_paramsdb" : 78.629958,
  "resultcount" : 1,
  "lookup" : {
    "value" : 2401,
    "key" : "customer"
  },
  "explore" : 0,
  "explore_model" : 0,
  "subname" : "model",
  "whitelist" : { },
  "featuresObj" : {
    "education" : "Grade12",
    "numberOfChildren" : 2,
    "numberOfAddresses" : 1,
    "gender" : "F",
    "language" : "English",
    "numberOfProducts" : 7,
    "maritalStatus" : "Unmarried",
    "age" : 35,
    "proprtyOwnership" : "Owner"
  },
  "duration_whitelist" : 0.00475,
  "api_params" : {
    "resultcount" : 1,
    "mojo" : "1",
    "subname" : "model",
    "name" : "spend_personality",
    "subcampaign" : "model",
    "channel" : "app",
    "campaign" : "spend_personality",
    "UPDATE" : true,
    "uuid" : "ee9a5288-6063-4a71-8a4a-5da16a5ec319",
    "userid" : "ecosystem",
    "customer" : "2401"
  },
  "input" : [ "education", "gender", "language", "numberOfProducts", "numberOfChildren", "numberOfAddresses", "maritalStatus", "age", "proprtyOwnership" ],
  "model_selector" : {
    "model_selected" : [ 1 ]
  },
  "model_names" : {
    "GBM_1_AutoML_1_20240323_121956.zip" : 0
  },
  "offerMatrixWithKey" : { },
  "name" : "spend_personality",
  "subcampaign" : "model",
  "campaign" : "spend_personality",
  "offerMatrix" : [ ],
  "customer" : "2401",
  "default_lookup" : false,
  "preloadCorpora" : { }
}

 */


/**
 * Example JSONObject: predictModelMojoResult

{
  "features" : {
    "education" : "Grade12",
    "numberOfChildren" : 2,
    "numberOfAddresses" : 1,
    "gender" : "F",
    "language" : "English",
    "numberOfProducts" : 7,
    "maritalStatus" : "Unmarried",
    "age" : 35,
    "proprtyOwnership" : "Owner"
  },
  "names" : [ [ "account_type", "trns_amt", "mcc_category", "personality_description" ] ],
  "probability" : [ 0.9998964553366178 ],
  "label_index" : [ 2 ],
  "response" : [ "personality_description" ],
  "domains" : [ [ "Enthusiastic", "Experiential", "Industrious", "Intentional", "NA", "Uncategorised" ] ],
  "label" : [ "Industrious" ],
  "modelType" : [ "hex.genmodel.algos.gbm.GbmMojoModel" ],
  "type" : [ "multinomial" ],
  "domainsProbabilityObj" : {
    "Experiential" : 1.7523884343452256E-5,
    "NA" : 2.7868894316116586E-5,
    "Enthusiastic" : 2.5747886638038687E-5,
    "Uncategorised" : 1.547580899026345E-8,
    "Industrious" : 0.9998964553366178,
    "Intentional" : 3.2388522275699294E-5
  },
  "probabilities" : [ [ 2.5747886638038687E-5, 1.7523884343452256E-5, 0.9998964553366178, 3.2388522275699294E-5, 2.7868894316116586E-5, 1.547580899026345E-8 ] ],
  "featuresObj" : {
    "education" : "Grade12",
    "numberOfChildren" : 2,
    "numberOfAddresses" : 1,
    "gender" : "F",
    "language" : "English",
    "numberOfProducts" : 7,
    "maritalStatus" : "Unmarried",
    "age" : 35,
    "proprtyOwnership" : "Owner"
  }
}

 */

/**
 * Example JSONObject: featuresObj

 {
  "education" : "Grade12",
  "numberOfChildren" : 2,
  "numberOfAddresses" : 1,
  "gender" : "F",
  "language" : "English",
  "numberOfProducts" : 7,
  "maritalStatus" : "Unmarried",
  "age" : 35,
  "proprtyOwnership" : "Owner"
}

 */