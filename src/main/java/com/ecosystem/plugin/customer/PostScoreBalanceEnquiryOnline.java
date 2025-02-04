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
 * ECOSYSTEM.AI INTERNAL PLATFORM SCORING
 * Use this class to score with dynamic sampling configurations. This class is configured to work with no model.
 */
public class PostScoreBalanceEnquiryOnline extends PostScoreSuper {
    private static final Logger LOGGER = LogManager.getLogger(PostScoreBalanceEnquiryOnline.class.getName());

    public PostScoreBalanceEnquiryOnline() {
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

            JSONObject offerMatrixWithKey = new JSONObject();
            boolean om = false;
            if (params.has("offerMatrixWithKey")) {
                offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");
                om = true;
            }

            JSONObject work = params.getJSONObject("in_params");

            /* Standardized approach to access dynamic datasets in plugin.
             * The options array is the data set/feature_store that's keeping track of the dynamic changes.
             * The optionParams is the parameter set that will influence the real-time behavior through param changes.
             */
            JSONArray options = (JSONArray) ((
                    (JSONObject) params.getJSONObject("dynamicCorpora")
                            .get("dynamic_engagement_options")).get("data"));
            JSONObject optionParams = (JSONObject) ((
                    (JSONObject) params.getJSONObject("dynamicCorpora")
                            .get("dynamic_engagement")).get("data"));

            JSONObject contextual_variables = optionParams.getJSONObject("contextual_variables");
            JSONObject randomisation = optionParams.getJSONObject("randomisation");


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

            /* Configure rules for balance enquiry case */
            double voice_balance = 0.0;
            double data_balance = 0.0;
            double in_balance = 0.0;
            if (work.has("voice_balance")) {
                voice_balance = DataTypeConversions.getDouble(work, "voice_balance");
            } else {
                LOGGER.error("PostScoreBalanceEnquiryOnline: voice_balance not present in params, using default of 0");
            }
            if (work.has("data_balance")) {
                data_balance = DataTypeConversions.getDouble(work, "data_balance");
            } else {
                LOGGER.error("PostScoreBalanceEnquiryOnline: data_balance not present in params, using default of 0");
            }
            if (work.has("in_balance")) {
                in_balance = DataTypeConversions.getDouble(work, "in_balance");
            } else {
                LOGGER.error("PostScoreBalanceEnquiryOnline: in_balance not present in params, using default of 0");
            }

            /* Return input result if key features are missing */
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

            String payment_method_code = "p";

            if (featuresObj.has("payment_method_code")) {
                payment_method_code = featuresObj.getString("payment_method_code");
            } else {
                LOGGER.error("PostScoreBalanceEnquiry: payment_method_code not available in customer lookup, defaulting to P");
            }

            JSONArray finalOffers = new JSONArray();
            int offerIndex = 0;
            int explore;
//            int[] optionsSequence = generateOptionsSequence(options.length(), options.length());
            String contextual_variable_one = String.valueOf(work.get("contextual_variable_one"));
            String contextual_variable_two = String.valueOf(work.get("contextual_variable_two"));

            //for(int j : optionsSequence) {
            for (int j = 0; j < options.length(); j++) {
                if (j > params.getInt("resultcount")) break;

                JSONObject option = options.getJSONObject(j);
                String contextual_variable_one_Option = "";
                if (option.has("contextual_variable_one") && !contextual_variable_one.isEmpty())
                    contextual_variable_one_Option = String.valueOf(option.get("contextual_variable_one"));
                String contextual_variable_two_Option = "";
                if (option.has("contextual_variable_two") && !contextual_variable_two.isEmpty())
                    contextual_variable_two_Option = String.valueOf(option.get("contextual_variable_two"));

                if (contextual_variable_one_Option.equals(contextual_variable_one) && contextual_variable_two_Option.equals(contextual_variable_two)) {

                    double alpha = (double) DataTypeConversions.getDoubleFromIntLong(option.get("alpha"));
                    double beta = (double) DataTypeConversions.getDoubleFromIntLong(option.get("beta"));

                    /* r IS THE RANDOMIZED SCORE VALUE */
                    double p = 0.0;
                    double arm_reward = 0.001;
                    if (randomisation.getString("approach").equals("epsilonGreedy")) {
                        explore = 0;
                        p = DataTypeConversions.getDouble(option, "arm_reward");
                        arm_reward = p;
                    } else {
                        /* REMEMBER THAT THIS IS HERE BECAUSE OF BATCH PROCESS, OTHERWISE IT REQUIRES THE TOTAL COUNTS */
                        /* Phase 2: sampling - calculate the arms and rank them */
                        // params.put("explore", 0); // force explore to zero and use Thompson Sampling only!!
                        explore = 0; // set as explore as the dynamic responder is exploration based...
                        p = DataTypeConversions.getDouble(option, "arm_reward");
                        arm_reward = p;

                    }
                    /* Check if values are correct */
                    if (p != p) p = 0.0;
                    if (alpha != alpha) alpha = 0.0;
                    if (beta != beta) beta = 0.0;
                    if (arm_reward != arm_reward) arm_reward = 0.0;

                    String offer = option.getString("optionKey");

                    JSONObject singleOffer = new JSONObject();
                    double offer_value = 1.0;
                    double modified_offer_score = 1.0;
                    if (om) {
                        if (offerMatrixWithKey.has(offer)) {

                            singleOffer = offerMatrixWithKey.getJSONObject(offer);

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

                            if (singleOffer.has("payment_method_code")) {
                                if (singleOffer.getString("payment_method_code").equalsIgnoreCase(payment_method_code)) {
                                    double cop_car_value = 0.0;
                                    if (singleOffer.has("cop_car")) {
                                        String cop_car = singleOffer.getString("cop_car").toLowerCase();
                                        if (featuresObj.has(cop_car)) {
                                            /* if cop_car has a null or NaN it should be set to 0.0 */
                                            try {
                                                cop_car_value = featuresObj.getDouble(cop_car);
                                            } catch (Exception e) {
                                                cop_car_value = 0.0;
                                            }
                                        }
                                    }

                                    double offer_price = 0.0;
                                    if (singleOffer.has("offer_price"))
                                        offer_price = DataTypeConversions.getDouble(singleOffer, "offer_price");
                                    if (singleOffer.has("price"))
                                        offer_price = DataTypeConversions.getDouble(singleOffer, "price");

                                    double offer_matrix_alpha = 0.0;
                                    if (singleOffer.has("alpha"))
                                        offer_matrix_alpha = DataTypeConversions.getDouble(singleOffer, "alpha");

                                    offer_value = offer_price - cop_car_value * (offer_matrix_alpha - 2);
                                    if (offer_value >= 0 | whitelist) {
                                        double offer_score = 0.0;
                                        String offer_type = "Voice";
                                        if (singleOffer.has("offer_type")) {
                                            offer_type = singleOffer.getString("offer_type");
                                        } else{
                                            LOGGER.error("PostScoreBalanceEnquiry: offer_type not available in offer_matrix, defaulting to Voice");
                                        }
                                        if (offer_type.equals("Voice"))
                                            offer_score = 0.5;

                                        boolean offerTypeRule = (preferred.equalsIgnoreCase("Any") | preferred.equalsIgnoreCase(offer_type));

                                        double offer_weight = 0.0;
                                        if (singleOffer.has("offer_weight"))
                                            offer_weight = DataTypeConversions.getDouble(singleOffer, "offer_weight");
                                        if ((preferred.equals("Any") | (preferred.equals(offer_type)))) {
                                            modified_offer_score = offer_score * offer_weight;
                                        } else {
                                            modified_offer_score = offer_score * offer_weight * 0.0000000000000000000000000000000001;
                                        }

                                        if (singleOffer.getString("payment_method_code").equalsIgnoreCase("C"))
                                            in_balance = 0.0;

                                        JSONObject finalOffersObject = new JSONObject();
                                        finalOffersObject.put("offer_name", offer);
                                        finalOffersObject.put("score", offer_score);
                                        finalOffersObject.put("modified_offer_score", modified_offer_score);
                                        finalOffersObject.put("offer_value", offer_value);

                                        finalOffersObject.put("voice_balance", voice_balance);
                                        finalOffersObject.put("data_balance", data_balance);

                                        finalOffersObject.put("daily_voice_usage_avg", daily_voice_usage_avg);
                                        finalOffersObject.put("daily_data_usage_avg", daily_data_usage_avg);

                                        finalOffersObject.put("preferred", preferred);
                                        finalOffersObject.put("offer_matrix", singleOffer);

                                        finalOffersObject.put("p", p);
                                        if (option.has("contextual_variable_one"))
                                            finalOffersObject.put("contextual_variable_one", option.getString("contextual_variable_one"));
                                        else
                                            finalOffersObject.put("contextual_variable_one", "");

                                        if (option.has("contextual_variable_two"))
                                            finalOffersObject.put("contextual_variable_two", option.getString("contextual_variable_two"));
                                        else
                                            finalOffersObject.put("contextual_variable_two", "");

                                        finalOffersObject.put("alpha", alpha);
                                        finalOffersObject.put("beta", beta);
                                        finalOffersObject.put("explore", explore);
                                        finalOffersObject.put("uuid", params.get("uuid"));
                                        finalOffersObject.put("arm_reward", arm_reward);

                                        // TODO OBTAIN BUDGET PRIORITY AND ASSIGN
//                                        if (settings.getPredictorOfferBudget() != null) {
//                                            JSONObject budgetItem = obtainBudget(singleOffer, params.getJSONObject("featuresObj"), offer_value);
//                                         	double budgetSpendLimit = budgetItem.getDouble("spend_limit");
//                                         	finalOffersObject.put("spend_limit", budgetSpendLimit);
//                                        }

                                        /* set selection rules */
                                        boolean ruleSelect = false;
                                        boolean rule1 = (in_balance > 0.0 & offer_price < in_balance);
                                        boolean rule2 = (in_balance <= 0);
                                        //if (rule1 | rule2) ruleSelect = true;

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
                            } else {
                                LOGGER.error("PostScoreBalanceEnquiry: payment_method_code not available in offer matrix, skipping offer");
                            }
                        }
                    }
                }
            }

            JSONArray sortJsonArray = JSONArraySort.sortArray(finalOffers, "modified_offer_score", "double", "d");
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
